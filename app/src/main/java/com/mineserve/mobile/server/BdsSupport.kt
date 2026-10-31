package com.mineserve.mobile.server

import android.system.Os
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 官方 Minecraft 基岩版专用服务器（BDS）的平台知识与文件布局。
 *
 * 为什么不套用其它核心的启动逻辑：BDS 不是 JVM 应用，也不是 PHP 脚本，而是
 * Mojang 直接发布的**原生可执行文件** `bedrock_server`（同目录下带一批 `.so`，
 * 靠 `LD_LIBRARY_PATH=.` 加载）。它没有 `server.jar`、不看 `eula.txt`、
 * 控制台命令集也不同（`save hold` / `stop`），因此下载形态（zip 而非 jar）、
 * 启动方式（exec 原生文件）、停止方式（stdin 发 `stop`）都必须单独处理。
 *
 * 平台事实（如实声明，不做伪装）：
 *  - 官方只发布 **Linux x86_64** 构建，且动态链接目标为 **glibc**
 *    （`/lib64/ld-linux-x86-64.so.2`）；
 *  - 本 APK 只打包 **arm64-v8a**，运行环境是 Android bionic；
 *  - 因此要在本机运行，必须 **glibc 容器（PRoot）+ box64 转译**：x86_64 指令
 *    由 box64 翻译成 arm64，glibc 由容器提供。这条路是社区在安卓/树莓派上
 *    跑官方 BDS 的通用做法，本项目的实现见 `TermuxRuntime.startBdsViaBox64`；
 *  - 转译有实打实的代价：启动慢（几十秒到几分钟）、性能约为原生的
 *    三到六成、需要额外的容器与 244MB 运行副本。界面必须把这点讲明白，
 *    不能让用户以为是原生性能。
 *
 * 大文件注意：1.21+ 的 `bedrock_server` 是**静态链接的单个文件**（解压后约 244MB），
 * 目录里没有 `.so`。公共存储是 FUSE 且 noexec，这个文件不能就地执行，
 * 必须复制到应用私有目录（真实文件系统）后再交给 box64。
 */
object BdsSupport {

    private const val TAG = "BdsSupport"

    /** 官方下载链接索引（返回各平台的 zip 直链，无需登录） */
    const val DOWNLOAD_LINKS_API = "https://net-secondary.web.minecraft-services.net/api/v1.0/download/links"

    /** 索引里 linux 服务器对应的 downloadType */
    const val DOWNLOAD_TYPE_LINUX = "serverBedrockLinux"

    /** BDS 主程序文件名（可执行 ELF） */
    const val ENTRY_NAME = "bedrock_server"

    /** BDS 的配置文件（与 Java 版同名同格式的 key=value） */
    const val PROPERTIES_NAME = "server.properties"

    data class Build(val version: String, val url: String)

    /** ELF 机器类型（只区分对本项目有意义的几种） */
    enum class ElfArch(val displayName: String) {
        X86_64("x86-64"),
        AARCH64("aarch64"),
        ARM("arm"),
        X86("x86"),
        UNKNOWN("未知架构")
    }

    /** 目录里的可执行文件该怎么跑。 */
    enum class LaunchMode {
        /** 可执行文件就是本机架构，直接 exec */
        Native,

        /** x86_64 发行版 + arm64 设备：交给 box64 转译执行 */
        Box64,

        /** 真的跑不了（架构既不是本机，也不是可转译的 x86_64） */
        Unsupported
    }

    /**
     * 启动可行性结论。
     *
     * [mode] 为 [LaunchMode.Unsupported] 时 [reason] 是**给用户看的**完整说明，
     * 调用方直接抛给界面，不再自己编文案；[LaunchMode.Box64] 时调用方负责先
     * 准备好 box64 运行环境（见 `TermuxRuntime.ensureBdsRuntime`）。
     */
    data class LaunchCheck(
        val mode: LaunchMode,
        val arch: ElfArch? = null,
        val reason: String = ""
    ) {
        val ok: Boolean get() = mode != LaunchMode.Unsupported

        /** 需要先安装 PRoot 容器 + box64 才能启动 */
        val needsBox64: Boolean get() = mode == LaunchMode.Box64

        companion object {
            fun native(arch: ElfArch) = LaunchCheck(LaunchMode.Native, arch)
            fun viaBox64(arch: ElfArch) = LaunchCheck(LaunchMode.Box64, arch)
            fun fail(reason: String, arch: ElfArch? = null) =
                LaunchCheck(LaunchMode.Unsupported, arch, reason)
        }
    }

    // ── 版本 / 下载地址 ───────────────────────────────────────────

    /**
     * 从官方 links 接口解析 linux BDS 的版本与直链。
     *
     * 用 kotlinx.serialization 的 JsonElement 手工遍历而不是建数据类：
     * 官方随时可能加平台（win-preview 之类），多出来的字段必须被忽略而不是解析失败。
     */
    fun parseLatestLinux(jsonText: String): Build? {
        val root = runCatching { Json.parseToJsonElement(jsonText) }.getOrNull() ?: return null
        val links = runCatching {
            root.jsonObject["result"]?.jsonObject?.get("links")?.jsonArray
        }.getOrNull() ?: return null
        for (element in links) {
            val obj: JsonObject = runCatching { element.jsonObject }.getOrNull() ?: continue
            val type = runCatching { obj["downloadType"]?.jsonPrimitive?.content }.getOrNull()
            if (!type.equals(DOWNLOAD_TYPE_LINUX, ignoreCase = true)) continue
            val url = runCatching { obj["downloadUrl"]?.jsonPrimitive?.content }.getOrNull() ?: continue
            val version = versionFromUrl(url) ?: continue
            return Build(version, url)
        }
        return null
    }

    /** `.../bedrock-server-1.26.52.3.zip` → `1.26.52.3` */
    fun versionFromUrl(url: String): String? {
        val name = url.substringAfterLast('/')
        if (!name.startsWith("bedrock-server-") || !name.endsWith(".zip", ignoreCase = true)) return null
        val version = name.removePrefix("bedrock-server-").removeSuffix(".zip")
        return version.takeIf { it.isNotBlank() && it.any(Char::isDigit) }
    }

    // ── 文件布局 ─────────────────────────────────────────────────

    fun entryFile(serverDir: File): File = File(serverDir, ENTRY_NAME)

    fun isBdsDir(dir: File): Boolean =
        dir.isDirectory && File(dir, ENTRY_NAME).isFile

    // ── ELF 架构识别 ─────────────────────────────────────────────

    /**
     * 读 ELF 头的 e_machine 判断可执行文件架构。
     *
     * 不依赖 `file` 命令、不依赖 NDK：ELF 头固定布局，
     * magic(4) + class(1) + data(1) + version(1) + osabi(1) + abiversion(1) + pad(7)
     * 之后（偏移 18）是 2 字节的 e_machine（按 data 指定的字节序）。
     */
    fun elfArch(file: File): ElfArch? {
        if (!file.isFile) return null
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(20)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n <= 0) return null
                    read += n
                }
                if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
                ) {
                    return null
                }
                val littleEndian = header[5] == 1.toByte()
                val machine = if (littleEndian) {
                    (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
                } else {
                    ((header[18].toInt() and 0xFF) shl 8) or (header[19].toInt() and 0xFF)
                }
                when (machine) {
                    0x3E -> ElfArch.X86_64
                    0xB7 -> ElfArch.AARCH64
                    0x28 -> ElfArch.ARM
                    0x03 -> ElfArch.X86
                    else -> ElfArch.UNKNOWN
                }
            }
        } catch (e: Throwable) {
            runCatching { Log.w(TAG, "读取 ELF 头失败: ${file.name} ${e.message}") }
            null
        }
    }

    /** 本机主 ABI 对应的 ELF 架构集合（按 supportedAbis 顺序判定可运行性） */
    fun deviceArch(abis: List<String>): Set<ElfArch> {
        val result = mutableSetOf<ElfArch>()
        abis.forEach { abi ->
            when {
                abi.contains("arm64") -> result += ElfArch.AARCH64
                abi.contains("armeabi") -> result += ElfArch.ARM
                abi.contains("x86_64") -> result += ElfArch.X86_64
                abi == "x86" -> result += ElfArch.X86
            }
        }
        return result
    }

    /**
     * 启动前检查：架构能不能跑 + 依赖库是否同架构。
     *
     * 三种结论，不能混为一谈：
     *  - 主程序是本机架构 → [LaunchMode.Native]，直接 exec；
     *  - 主程序是 x86_64 而本机是 arm64 → [LaunchMode.Box64]，**可以跑**，
     *    但要先装 PRoot 容器 + box64（官方只发布 Linux x86_64 构建，
     *    转译是唯一可行路径，这也是社区在安卓/树莓派上开官方 BDS 的通用做法）；
     *  - 其它组合（arm32 / x86 32 位）→ 拒绝，并说明替代方案。
     *
     * 除主程序外还要看同目录下随包分发的 `.so`：混合架构的目录一定跑不起来
     * （dlopen 直接失败），提前说清楚比运行期崩更好。
     */
    fun checkLaunch(serverDir: File, abis: List<String>): LaunchCheck {
        val entry = entryFile(serverDir)
        if (!entry.isFile) {
            return LaunchCheck.fail(
                "目录中缺少 $ENTRY_NAME，BDS 没有下载完整。请删除该服务器后重新下载官方 BDS"
            )
        }
        val entryArch = elfArch(entry)
            ?: return LaunchCheck.fail(
                "$ENTRY_NAME 不是可识别的 Linux 可执行文件（可能被解压工具破坏），请重新下载官方 BDS"
            )
        val device = deviceArch(abis)
        val mode = when {
            entryArch in device -> LaunchMode.Native
            // 官方发行版只有 x86_64；arm64 设备靠 box64 转译执行
            entryArch == ElfArch.X86_64 && ElfArch.AARCH64 in device -> LaunchMode.Box64
            else -> return LaunchCheck.fail(
                "BDS 主程序是 ${entryArch.displayName} 架构，本机支持的架构为 " +
                    device.joinToString("/") { it.displayName } +
                    "，无法执行。想在本机开基岩版服务器，请改用 PowerNukkitX 或 Allay" +
                    "（两者都是基岩协议、可在 Android 原生运行）",
                entryArch
            )
        }
        val mismatched = serverDir.listFiles { f ->
            f.isFile && f.name.endsWith(".so", ignoreCase = true)
        }?.mapNotNull { lib -> elfArch(lib)?.let { lib to it } }
            ?.firstOrNull { (_, arch) -> arch != entryArch }
        if (mismatched != null) {
            return LaunchCheck.fail(
                "BDS 目录里的 ${mismatched.first.name} 是 ${mismatched.second.displayName} 架构，" +
                    "与主程序 ${entryArch.displayName} 不一致，运行时会因无法加载动态库而立即退出。请重新解压完整安装包",
                entryArch
            )
        }
        return when (mode) {
            LaunchMode.Native -> LaunchCheck.native(entryArch)
            else -> LaunchCheck.viaBox64(entryArch)
        }
    }

    // ── 解压 ─────────────────────────────────────────────────────

    /**
     * 解压官方 zip 到服务器目录。
     *
     * 与 `ServerImporter` 的通用解压刻意分开：BDS 包没有顶层目录、必须保留
     * `bedrock_server` 的可执行位，且条目数是固定的小数量，不需要那套预算/剥离逻辑。
     *
     * @return 解压出的条目数
     */
    fun extractZip(zip: File, targetDir: File): Int {
        targetDir.mkdirs()
        val canonicalTarget = targetDir.canonicalFile
        var count = 0
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (name.isNotBlank() && !name.endsWith("/")) {
                    val outFile = File(targetDir, name)
                    // 防路径穿越：条目名里的 ../ 不允许落到服务器目录之外
                    if (!outFile.canonicalPath.startsWith(canonicalTarget.path + File.separator)) {
                        throw java.io.IOException("压缩包包含非法路径：$name")
                    }
                    outFile.parentFile?.mkdirs()
                    copyEntry(zis, outFile)
                    count++
                    if (outFile.name == ENTRY_NAME) makeExecutable(outFile)
                } else if (name.endsWith("/")) {
                    File(targetDir, name).mkdirs()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        // 某些压缩工具不保留权限位，zis 也读不到 unix mode，统一补一次
        entryFile(targetDir).takeIf { it.isFile }?.let { makeExecutable(it) }
        return count
    }

    private fun copyEntry(input: InputStream, outFile: File) {
        FileOutputStream(outFile).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                output.write(buffer, 0, n)
            }
        }
    }

    /**
     * 给 `bedrock_server` 加可执行位。
     *
     * 公共存储是 FUSE，权限位由内核统一给出（通常已可执行），因此这里失败不影响运行，
     * 只是内部存储/私有目录场景下的必要步骤，故不抛异常。
     */
    fun makeExecutable(file: File): Boolean = try {
        Os.chmod(file.absolutePath, "755".toInt(8))
        true
    } catch (e: Throwable) {
        // JVM 单测里 android.util.Log / android.system.Os 都是桩，会抛 RuntimeException；
        // 这里必须 catch Throwable 且日志本身也包一层，否则「记录失败」会把解压流程带崩。
        runCatching { Log.w(TAG, "chmod 失败（可能位于 FUSE，可忽略）：${file.name} ${e.message}") }
        false
    }
}
