package com.mineserve.mobile.server

import android.util.Log
import io.airlift.compress.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

/**
 * 官方 BDS 在 arm64 设备上的运行环境：**box64 转译器 + 可执行的运行副本**。
 *
 * ## 为什么需要这一层
 *
 * 1. 官方只发布 Linux **x86_64 + glibc** 的 `bedrock_server`，本 APK 是 arm64 + bionic。
 *    唯一可行的运行方式是「glibc 容器（PRoot Ubuntu）+ box64 把 x86_64 指令翻译成 arm64」。
 *    box64 本身是 **aarch64 glibc** 程序，所以它只能跑在容器里，不能直接跑在 bionic 上。
 * 2. 公共存储 `/storage/emulated/0` 是 FUSE，**没有执行权限**。`bedrock_server` 约 244MB，
 *    让 box64 直接 mmap 它是必失败的（`EPERM`），因此必须先复制到应用私有目录
 *    （真实文件系统）再执行。这份副本按版本共享，同一个 BDS 版本只复制一次。
 *
 * ## 为什么不用 apt 装 box64
 *
 * box64 不在任何 Ubuntu 官方源里，只能来自第三方仓库（`rfort.net`，原
 * `ryanfortner/box64-debs`）。在容器里加第三方源要导入 GPG key、还要先有 CA 证书，
 * 而容器初始 rootfs 连 `ca-certificates` 都没有 —— 任何一步失败用户都只看到
 * 「装不上」。这里改成**在宿主侧下载 .deb 再解包**：只取 `usr/local/bin/box64`
 * 一个文件（依赖只有 glibc 的 libc/libm/libresolv，Ubuntu 基础镜像全都自带），
 * 全程可重试、可离线复现、不依赖容器里的包管理器状态。
 */
object BdsRuntime {

    private const val TAG = "BdsRuntime"

    /** .deb 所在目录；包内的 Filename 是相对它的 `./xxx.deb`（多源由 [com.mineserve.mobile.data.BdsSources] 提供） */
    const val DEB_BASE_URL = "https://rfort.net/box64-debs/debian/"

    /** 容器内的 bind 落点：宿主 `<prefix>/var/lib/mineserve/bds` 映射到这里 */
    const val GUEST_ROOT = "/opt/minexa-bds"

    const val BOX64_NAME = "box64"

    /** BDS 主程序名（与 [BdsSupport.ENTRY_NAME] 一致，这里再留一份是为了避免循环依赖） */
    const val ENTRY_NAME = "bedrock_server"

    /** box64 的可选配置（里面有针对常见程序的分支调优），缺了也能跑 */
    private const val BOX64RC_NAME = "box64.box64rc"

    /** 需要以「可执行副本」身份落到私有目录的文件；其余文件留在服务器目录里 */
    private const val PAYLOAD_VERSION_MARKER = "payload.version"

    /**
     * 索引里的一条 box64 包。
     *
     * [sha256] 来自 Packages 的 `SHA256:` 字段：同一个文件从不同入口下载，
     * 内容必须一致，下载后据此校验（截断/错误页都能挡住）。
     * 索引里没写哈希时为 null，调用方退回结构性校验。
     */
    data class DebRef(
        val version: String,
        val fileName: String,
        val sha256: String? = null,
        val size: Long = 0L
    )

    // ── 宿主侧路径 ───────────────────────────────────────────────

    /** box64 与运行副本的总根：`<prefix>/var/lib/mineserve/bds` */
    fun rootDir(prefix: File): File = File(prefix, "var/lib/mineserve/bds")

    fun box64Binary(prefix: File): File = File(rootDir(prefix), BOX64_NAME)

    fun box64VersionFile(prefix: File): File = File(rootDir(prefix), "box64.version")

    fun box64rcFile(prefix: File): File = File(rootDir(prefix), BOX64RC_NAME)

    /**
     * x86_64 运行库目录。
     *
     * 官方 `bedrock_server` **不是静态程序**：它的 `DT_NEEDED` 是
     * `libpthread.so.0 / libdl.so.2 / libm.so.6 / libgcc_s.so.1 / libc.so.6 /
     * ld-linux-x86-64.so.2 / librt.so.1`，全部是 x86_64 架构的 glibc 运行库。
     * box64 只负责把 x86_64 指令翻译成 arm64，**库还得自己给**。
     *
     * 不给的后果就是启动时 box64 报
     * `Error loading needed libs in elf .../bedrock_server` 然后 exit=255。
     */
    fun x86LibDir(prefix: File): File = File(rootDir(prefix), "x86libs")

    /** 七个必需库都在位才算完整（只判目录存在会被半途中断的安装骗过） */
    fun hasX86Libs(prefix: File): Boolean =
        REQUIRED_X86_LIBS.all { File(x86LibDir(prefix), it).isFile }

    /** 已安装的 box64 版本号；文件存在但为空也算未安装 */
    fun installedBox64Version(prefix: File): String? =
        box64VersionFile(prefix).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    // ── CA 根证书 ────────────────────────────────────────────────

    /** 容器里的 CA bundle（libcurl / OpenSSL 都认这个路径） */
    fun guestCaBundle(rootfs: File): File = File(rootfs, "etc/ssl/certs/ca-certificates.crt")

    /** 宿主机（Android）自带的根证书目录。Android 14+ 全部搬到了 Conscrypt APEX。 */
    private val HOST_CA_DIRS = listOf(
        "/apex/com.android.conscrypt/cacerts",
        "/system/etc/security/cacerts"
    )

    /**
     * 给容器补一份 CA 根证书。
     *
     * 为什么必须补：Ubuntu Base 的最小 rootfs **不一定**带 `ca-certificates`，
     * 实测（2026-10-31 真机）容器里 `/etc/ssl/certs/ca-certificates.crt` 不存在。
     * BDS 在线模式要用 libcurl 走 HTTPS 连微软认证服务，没有根证书就无从校验，
     * 表现是 `Waiting for Minecraft services...` 挂 60 秒后
     * `Could not connect to Minecraft services` —— 与「网络不通」的现象完全一样，
     * 极容易误判成被墙。
     *
     * 直接抄 Android 的根证书而不是 `apt install ca-certificates`：不用联网、
     * 不依赖 apt 源、秒级完成，且与系统受信任的根一致。
     *
     * @return 写入的证书数量；0 表示宿主侧没找到可用的根证书
     */
    fun ensureGuestCaBundle(rootfs: File): Int {
        val target = guestCaBundle(rootfs)
        if (target.isFile && target.length() > 32_768 && !target.readText().contains("DUMMY")) {
            return -1 // 已有可用 bundle（-1 = 未改动）
        }
        val pems = mutableListOf<String>()
        for (dir in HOST_CA_DIRS) {
            val files = File(dir).listFiles()?.filter { it.isFile } ?: continue
            for (file in files) {
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                if (text.contains("-----BEGIN CERTIFICATE-----")) pems.add(text.trim())
            }
            if (pems.isNotEmpty()) break
        }
        if (pems.isEmpty()) return 0
        val dir = target.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) return 0
        return runCatching {
            target.writeText(pems.joinToString(separator = "\n", postfix = "\n"))
            pems.size
        }.getOrDefault(0)
    }

    /**
     * 用**随 APK 内置**的 box64 安装运行环境。
     *
     * ## 为什么改成内置
     *
     * 原来是从 rfort.net / jsDelivr 下 `.deb` 再解包。但 Debian 的包是
     * `data.tar.zst`，而安卓上唯一能用的纯 Java zstd 解压库（aircompressor）
     * 依赖 `sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET` —— **Android 14+ 的 ART 把这个
     * 字段删了**，一构造就抛 `NoSuchFieldError`（是 Error 不是 Exception，
     * 会直接杀掉进程）。也就是说这条路在新系统上根本走不通，而且它还是
     * 三方小主机 + 10MB 下载 + 索引解析三重依赖。
     *
     * 内置之后：无网络、无解压、无索引，一次复制即完成，任何系统版本都一样。
     * 网络下载路径仍保留为**兜底**（资源缺失时才会走到），见
     * [com.mineserve.mobile.server.BdsEnvironmentManager]。
     *
     * 幂等：已经装过且是 aarch64 就直接返回 true，不重复写 28MB。
     */
    fun installBundledBox64(context: android.content.Context, prefix: File): Boolean {
        val target = box64Binary(prefix)
        val binaryOk = target.isFile && target.length() > MIN_BOX64_BYTES && elfIsAarch64(target)
        return try {
            target.parentFile?.mkdirs()
            if (!binaryOk) {
                val temp = File(target.parentFile, "$BOX64_NAME.installing")
                temp.delete()
                context.assets.open(BUNDLED_ASSET_PATH).use { input ->
                    FileOutputStream(temp).use { out -> input.copyTo(out, 256 * 1024) }
                }
                if (temp.length() <= MIN_BOX64_BYTES || !elfIsAarch64(temp)) {
                    temp.delete()
                    runCatching { Log.w(TAG, "内置 box64 不是可用的 aarch64 可执行文件") }
                    return false
                }
                BdsSupport.makeExecutable(temp)
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                BdsSupport.makeExecutable(target)
                box64VersionFile(prefix).writeText("$BUNDLED_VERSION\n")
            }
            // 分支调优配置也一并装：box64 默认只读 /etc 与 ~/.box64rc，
            // 没有它就只能用内置默认值（启动命令里会显式指 BOX64_RCFILE）。
            if (!box64rcFile(prefix).isFile) {
                runCatching {
                    context.assets.open(BUNDLED_RC_ASSET_PATH).use { input ->
                        FileOutputStream(box64rcFile(prefix)).use { out -> input.copyTo(out) }
                    }
                }
            }
            // x86_64 运行库：不给的话 bedrock_server 连库都加载不了（box64 只翻译指令）。
            // 已经齐了就不重复写 3.4MB —— 这条路径在每次进环境页时都会走一遍。
            val libsOk = hasX86Libs(prefix) || installX86Libs(context, prefix)
            runCatching {
                Log.i(
                    TAG,
                    "内置 box64 $BUNDLED_VERSION 就绪（binary=$binaryOk, x86 库=$libsOk）"
                )
            }
            libsOk
        } catch (e: Throwable) {
            runCatching { Log.w(TAG, "安装内置 box64 失败: ${e.javaClass.simpleName} ${e.message}") }
            false
        }
    }

    /**
     * 把内置的 x86_64 运行库解到 [x86LibDir]。
     *
     * 用 `assets.list()` 遍历而不是写死清单：以后补一个库只要往 assets 里丢文件，
     * 不用改代码；是否完整由 [hasX86Libs] 单独判定（那才是运行必需的那几个）。
     */
    private fun installX86Libs(context: android.content.Context, prefix: File): Boolean {
        val dir = x86LibDir(prefix).apply { mkdirs() }
        val names = context.assets.list(BUNDLED_X86LIB_ASSET_DIR)?.toList().orEmpty()
        if (names.isEmpty()) {
            runCatching { Log.w(TAG, "APK 内没有 x86_64 运行库资源目录") }
            return false
        }
        names.forEach { name ->
            val out = File(dir, name)
            runCatching {
                context.assets.open("$BUNDLED_X86LIB_ASSET_DIR/$name").use { input ->
                    FileOutputStream(out).use { stream -> input.copyTo(stream, 256 * 1024) }
                }
            }
        }
        // 逐个验「是 x86_64 的 ELF 且不像被截断」：assets 里这些是 3MB 级的二进制，
        // 一旦被替换成 LFS 指针或半截文件，box64 会在半分钟后的启动阶段才报一句
        // 难懂的 needed libs 错误。这里当场判定，失败直接让初始化报错。
        val allValid = names.all { name ->
            val file = File(dir, name)
            file.isFile && file.length() > 4096 &&
                runCatching { BdsSupport.elfArch(file) == BdsSupport.ElfArch.X86_64 }
                    .getOrDefault(false)
        }
        if (!allValid) {
            runCatching { Log.w(TAG, "内置 x86_64 运行库内容异常（非 x86_64 ELF 或被截断）") }
            return false
        }
        // 动态库不需要可执行位；只有 x86 的 ld-linux 有可能被当程序调用，给 755 更保险
        names.forEach { name ->
            runCatching { android.system.Os.chmod(File(dir, name).absolutePath, "644".toInt(8)) }
        }
        runCatching {
            val loader = File(dir, "ld-linux-x86-64.so.2")
            if (loader.isFile) BdsSupport.makeExecutable(loader)
        }
        return hasX86Libs(prefix)
    }

    private fun elfIsAarch64(file: File): Boolean =
        runCatching { BdsSupport.elfArch(file) == BdsSupport.ElfArch.AARCH64 }.getOrDefault(false)

    /** 内置 box64 的版本标签（写进 box64.version，界面直接展示） */
    const val BUNDLED_VERSION = "0.4.5+20261024（内置）"

    private const val BUNDLED_ASSET_PATH = "box64/box64"

    private const val BUNDLED_RC_ASSET_PATH = "box64/box64.box64rc"

    private const val BUNDLED_X86LIB_ASSET_DIR = "box64/x86libs"

    /** 官方 bedrock_server 的 DT_NEEDED（实测自 1.26.52.3，见 [x86LibDir] 注释） */
    val REQUIRED_X86_LIBS = listOf(
        "libc.so.6",
        "libm.so.6",
        "libdl.so.2",
        "libpthread.so.0",
        "librt.so.1",
        "libgcc_s.so.1",
        "ld-linux-x86-64.so.2"
    )

    /** 28MB 的二进制，低于 1MB 必然是被截断或被替换成了错误页 */
    private const val MIN_BOX64_BYTES = 1L * 1024 * 1024

    /** 按 BDS 版本共享的可执行副本目录 */
    fun payloadDir(prefix: File, version: String): File =
        File(rootDir(prefix), "payload/${sanitizeVersion(version)}")

    /**
     * 每个服务器目录一个「运行目录」：里面是指向共享副本的符号链接，
     * 外加指回服务器目录的内容目录链接（definitions / resource_packs …）。
     */
    fun runDir(prefix: File, dirName: String): File = File(rootDir(prefix), "run/$dirName")

    /** 版本号做目录名要安全：`1.26.52.3` 原样可用，其它可疑字符一律换成 `_` */
    fun sanitizeVersion(version: String): String {
        val cleaned = version.trim().map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_'
        }.joinToString("")
        return cleaned.trim('.', '_', '-').ifEmpty { "unknown" }
    }

    // ── box64 deb 索引 ───────────────────────────────────────────

    /**
     * 从 Packages 文本里挑出**最新**的一条 box64 arm64 包。
     *
     * 版本号形态是 `0.4.5+20261024.420eda6-1`，前两段是上游版本号，`+` 后面是
     * 构建日期 + 提交号。按字符串比较不安全（`0.4.10` < `0.4.9`），因此提取
     * 日期段（8 位数字）做数值比较；取不到日期时退回字符串比较，
     * 保证任何格式变化都不会导致「永远挑不到」。
     */
    /**
     * 从 apt 索引里挑最新的一条 arm64 box64。
     *
     * **必须按包名过滤**：同一个索引里除了通用包 `box64`，还有一堆按 SoC 调的
     * 变体（`box64-sd888`、`box64-rk3588`、`box64-tegrax1`、`box64-rpi5arm64`…），
     * 它们共用同一行 `Version`。只比版本号、让后者胜出的话，最终选中的是
     * 列表里最后那个 —— 一台普通手机就会装上给英伟达 Tegra X1 或树莓派编的
     * 版本，跑起来要么慢要么直接崩。通用 `box64` 才是唯一安全选择。
     */
    fun parseLatestDeb(packages: String): DebRef? {
        val candidates = mutableListOf<Pair<Int, DebRef>>() // 优先级(越小越好) 与条目
        for (block in packages.split(Regex("\n\\s*\n"))) {
            val version = field(block, "Version") ?: continue
            val file = field(block, "Filename") ?: continue
            val arch = field(block, "Architecture")
            if (arch != null && !arch.equals("arm64", ignoreCase = true)) continue
            val fileName = file.removePrefix("./").substringAfterLast('/')
            if (!fileName.endsWith(".deb", ignoreCase = true)) continue
            val rank = when (field(block, "Package")?.lowercase()) {
                "box64" -> 0
                "box64-android" -> 1
                null -> 1
                else -> 9 // 设备专用变体：只在没有任何通用包时兜底
            }
            candidates += rank to DebRef(
                version = version,
                fileName = fileName,
                sha256 = field(block, "SHA256")?.lowercase()?.takeIf { it.length == 64 },
                size = field(block, "Size")?.toLongOrNull() ?: 0L
            )
        }
        if (candidates.isEmpty()) return null
        val bestRank = candidates.minOf { it.first }
        // 同优先级里取构建日期最新的；同一天多次构建时用 >= 让后者胜出
        return candidates.filter { it.first == bestRank }
            .maxByOrNull { buildDateKey(it.second.version) }
            ?.second
    }

    private fun field(block: String, name: String): String? =
        block.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** `0.4.5+20261024.420eda6-1` → 20261024；取不到返回 0（视为最旧） */
    fun buildDateKey(version: String): Long {
        val afterPlus = version.substringAfter('+', "")
        val digits = Regex("\\d{8}").find(afterPlus)?.value
            ?: Regex("\\d{8}").find(version)?.value
            ?: return 0L
        return digits.toLongOrNull() ?: 0L
    }

    // ── .deb 解包 ────────────────────────────────────────────────

    /**
     * 从 box64 的 .deb 里取出可执行文件（以及可选的 `box64.box64rc`）。
     *
     * 手写 ar 解析而不是引入 `ArArchiveInputStream`：ar 的格式只有 60 字节定长头，
     * 二十行就够，而且这里只需要「找到 data.tar.* 再交给 tar 流」这一步。
     *
     * @return 目标文件是否真的被写出且非空
     */
    fun unpackBox64Deb(deb: File, targetBinary: File, keepConfig: File? = null): Boolean {
        if (!deb.isFile || deb.length() < 1024) return false
        var writtenBinary = false
        var writtenConfig = false
        deb.inputStream().buffered().use { input ->
            val magic = ByteArray(8)
            if (input.read(magic) != 8 || !magic.contentEquals("!<arch>\n".toByteArray())) {
                runCatching { Log.w(TAG, "不是 ar 归档，无法解包 box64: ${deb.name}") }
                return false
            }
            while (true) {
                val header = ByteArray(60)
                var read = 0
                while (read < 60) {
                    val n = input.read(header, read, 60 - read)
                    if (n <= 0) return@use
                    read += n
                }
                val name = String(header, 0, 16, Charsets.US_ASCII).trim()
                val sizeText = String(header, 48, 10, Charsets.US_ASCII).trim()
                val size = sizeText.toLongOrNull() ?: return@use
                if (name.startsWith("data.tar")) {
                    // 只处理 data.tar.*，其余成员（debian-binary / control.tar.*）跳过
                    val payload = LimitedInputStream(input, size)
                    val ok = extractDataTar(payload, targetBinary, keepConfig)
                    writtenBinary = ok.binary
                    writtenConfig = ok.config
                    return@use
                }
                skipFully(input, size)
                if (size % 2 == 1L) skipFully(input, 1)
            }
        }
        if (keepConfig != null && !writtenConfig) {
            // 没有 box64rc 不影响运行，只记录一条
            runCatching { Log.i(TAG, "box64 包内没有 $BOX64RC_NAME，使用 box64 内置默认配置") }
        }
        return writtenBinary && targetBinary.isFile && targetBinary.length() > 1024
    }

    private data class Extracted(val binary: Boolean, val config: Boolean)

    private fun extractDataTar(
        raw: InputStream,
        targetBinary: File,
        keepConfig: File?
    ): Extracted {
        val buffered = BufferedInputStream(raw, 64 * 1024)
        buffered.mark(8)
        val magic = ByteArray(6)
        val magicLen = buffered.read(magic)
        buffered.reset()
        val decompressed: InputStream = when {
            magicLen >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() ->
                GzipCompressorInputStream(buffered)
            magicLen >= 6 && magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() &&
                magic[2] == 0x7A.toByte() && magic[3] == 0x58.toByte() && magic[4] == 0x5A.toByte() ->
                XZCompressorInputStream(buffered)
            magicLen >= 4 && magic[0] == 0x28.toByte() && magic[1] == 0xB5.toByte() &&
                magic[2] == 0x2F.toByte() && magic[3] == 0xFD.toByte() ->
                // aircompressor 的 zstd 实现靠 sun.misc.Unsafe 的数组基址常量做零拷贝，
                // 而 Android 14+ 的 ART 删掉了这些字段，构造时抛 NoSuchFieldError。
                // 它是 Error，不包起来就会一路冒到线程顶层把进程杀掉（用户看到的是闪退）。
                try {
                    ZstdInputStream(buffered)
                } catch (e: Throwable) {
                    throw IllegalStateException(
                        "当前系统无法解压 zstd（Android 14+ 移除了 sun.misc.Unsafe 的数组基址字段），" +
                            "请改用内置的运行环境或 zip 格式的安装包",
                        e
                    )
                }
            else -> buffered
        }
        var binary = false
        var config = false
        TarArchiveInputStream(decompressed).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./")
                when {
                    name == "usr/local/bin/$BOX64_NAME" && entry.isFile -> {
                        targetBinary.parentFile?.mkdirs()
                        writeEntry(tar, targetBinary)
                        BdsSupport.makeExecutable(targetBinary)
                        binary = true
                    }
                    keepConfig != null && name == "etc/$BOX64RC_NAME" && entry.isFile -> {
                        keepConfig.parentFile?.mkdirs()
                        writeEntry(tar, keepConfig)
                        config = true
                    }
                }
                entry = tar.nextEntry
            }
        }
        return Extracted(binary, config)
    }

    private fun writeEntry(input: InputStream, target: File) {
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                output.write(buffer, 0, n)
            }
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n <= 0) return
            remaining -= n
        }
    }

    /** 把 ar 成员限制在声明长度内，避免 tar 流读到下一个成员 */
    private class LimitedInputStream(
        private val source: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = source.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun close() {
            // 不关闭底层流：外层还要继续读 ar 的后续成员
        }
    }

    // ── 可执行副本 ───────────────────────────────────────────────

    /**
     * 把 `bedrock_server` 复制到私有目录（可执行文件系统），按版本复用。
     *
     * 复用判据是**文件大小一致**（副本目录本身按版本分，升级就换目录）。
     * 刻意不比对修改时间：公共存储是 FUSE，部分实现返回的 mtime 不稳定，
     * 真实机上会表现为「每次启动都重搬 244MB」。
     *
     * @param onProgress (已复制字节, 总字节)
     * @return 可执行副本的路径；复制失败抛异常（调用方需要能明确报错）
     */
    fun stageEntry(
        serverDir: File,
        version: String,
        payloadDir: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File {
        val source = File(serverDir, ENTRY_NAME)
        if (!source.isFile) throw IllegalStateException("服务器目录里没有 $ENTRY_NAME，无法准备运行副本")
        payloadDir.mkdirs()
        val target = File(payloadDir, ENTRY_NAME)
        if (target.isFile && target.length() > 0 && target.length() == source.length()) {
            return target
        }
        val temp = File(payloadDir, "$ENTRY_NAME.part")
        temp.delete()
        val total = source.length()
        var copied = 0L
        source.inputStream().use { input ->
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                    copied += n
                    onProgress?.invoke(copied, total)
                }
                output.flush()
            }
        }
        if (temp.length() != total) {
            temp.delete()
            throw IllegalStateException(
                "复制 $ENTRY_NAME 到私有目录失败（期望 $total 字节，实际 ${temp.length()} 字节），" +
                    "请检查剩余存储空间后重试"
            )
        }
        // 保留源文件的修改时间，下次启动才能用「大小 + mtime」判断能否复用
        temp.setLastModified(source.lastModified())
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IllegalStateException("无法把运行副本落到 ${target.absolutePath}")
            }
        }
        BdsSupport.makeExecutable(target)
        File(payloadDir, PAYLOAD_VERSION_MARKER).writeText("$version\n")
        runCatching { Log.i(TAG, "BDS 运行副本已就绪：$version → ${target.absolutePath}") }
        return target
    }

    /**
     * 准备运行目录（guest 里的 `/opt/minexa-bds/run/<服务器目录>`）。
     *
     * 主程序用**硬链接**指向共享副本（同一分区，0 额外空间）：执行时内核解析到
     * 共享的那 244MB，但 `/proc/self/exe` 的 realpath 仍是运行目录里的那一份。
     * 这一点是刚需 —— 见 [linkEntry]。
     *
     * 内容目录/文件建一条指回服务器目录的链接：BDS 在多数版本按**工作目录**找
     * （工作目录就是服务器目录，本来就能找到），但 1.21+ 会按**可执行文件所在
     * 目录**找。两条都铺上，才不会出现「配置不生效、资源包全空」。
     */
    fun prepareRunDir(serverDir: File, runDir: File, payloadEntry: File, guestServerDir: String) {
        runDir.mkdirs()
        val plan = runDirPlan(serverDir, guestServerDir)
        plan.forEach { item ->
            if (item.name == ENTRY_NAME) {
                linkEntry(payloadEntry, File(runDir, item.name))
            } else {
                linkOrCopy(null, File(runDir, item.name), item.guestTarget)
            }
        }
        stageProperties(serverDir, runDir)
        // 清理上次留下、这次计划里已经没有的条目
        val wanted = plan.map { it.name }.toSet() + PROPERTIES_NAME
        runDir.listFiles()?.forEach { child -> if (child.name !in wanted) child.delete() }
    }

    /**
     * `server.properties` 用**复制**而不是符号链接。
     *
     * 这个文件是 BDS 唯一「必须确实存在、且每次启动都要是最新」的配置，而其余
     * 回链（`bootstrap.json`、`definitions/` …）都是只读资源，丢一次也无所谓。
     * 复制的代价可以忽略（几十行），换来的是配置一定跟着运行目录走，不依赖
     * proot 对绝对路径符号链接的翻译。
     *
     * ⚠️ 日志里的 `Contents of server.properties: {}` **不能**用来判断"有没有读到"：
     * 1.26.52.3 在解析之前就把一个空 map 打了出来，读到与读不到都是 `{}`。
     * 真正的判据是紧随其后的行为 —— 读到 `online-mode=true` 会去连微软服务
     * （`Waiting for Minecraft services...`），读到 `allow-list=true` 会直接
     * `Using an allowlist without online authentication ... is not allowed`。
     *
     * 权威文件永远是服务器目录里那份（用户能在文件管理和属性页里看到、跟着备份走），
     * 运行目录这份只是启动时的副本。
     */
    private fun stageProperties(serverDir: File, runDir: File) {
        val source = File(serverDir, PROPERTIES_NAME)
        if (!source.isFile) return
        val target = File(runDir, PROPERTIES_NAME)
        runCatching {
            // 上次可能留了符号链接（老版本）或 BDS 自己生成的空文件，两种都要替换
            if (target.exists() || Files.isSymbolicLink(target.toPath())) target.delete()
            source.copyTo(target, overwrite = true)
        }.onFailure {
            runCatching { Log.w(TAG, "复制 $PROPERTIES_NAME 到运行目录失败: ${it.message}") }
        }
    }

    /** BDS 的配置文件（按可执行文件所在目录读，见 [stageProperties]） */
    private const val PROPERTIES_NAME = "server.properties"

    /**
     * 主程序必须是**硬链接**，不能是符号链接。
     *
     * 官方 BDS 1.21+ 启动时会取 `/proc/self/exe` 的 **realpath**，把它所在目录
     * 当作「资源根」（`server.properties`、`definitions/`、`bootstrap.json` 都按
     * 这个目录找）。如果用符号链接指向 `payload/<版本>/bedrock_server`，realpath
     * 就落到按版本共享的副本目录里 —— 那里只有一个 exe，于是每个服务器都拿不到
     * 自己的配置：日志表现为 `Failed to load profiler config file (bootstrap.json)`
     * （路径写着 `payload/<版本>/data/…`）、`online-mode` 退回内置默认值。
     *
     * 硬链接让 realpath 留在 `run/<服务器目录>/` 里，而那个目录已经铺好了回链，
     * 于是每个服务器读到的都是自己的配置。
     */
    private fun linkEntry(source: File, link: File) {
        val existing = link.takeIf { it.isFile }
        if (existing != null &&
            runCatching { !Files.isSymbolicLink(existing.toPath()) }.getOrDefault(false) &&
            existing.length() == source.length()
        ) {
            return
        }
        runCatching { link.delete() }
        link.parentFile?.mkdirs()
        // 同分区硬链接：0 额外空间、realpath 留在运行目录
        val linked = runCatching {
            Files.createLink(link.toPath(), source.toPath())
            true
        }.getOrDefault(false)
        if (linked) return
        // 跨分区或文件系统不支持硬链接（极少见）：退回复制，宁可多占 244MB 也要能跑
        val copied = runCatching {
            source.copyTo(link, overwrite = true)
            BdsSupport.makeExecutable(link)
            true
        }.getOrDefault(false)
        if (!copied) {
            runCatching { Log.w(TAG, "无法把 $ENTRY_NAME 硬链接/复制到 ${link.absolutePath}") }
        }
    }

    /** 「运行目录里应该有什么」的一条：文件名 + 它在容器里的绝对路径 */
    data class RunEntry(val name: String, val guestTarget: String)

    /**
     * 计算运行目录的链接计划（纯函数，便于单测）。
     *
     * 第一条永远是主程序：它由 [linkEntry] 做**硬链接**，不需要容器内目标
     * （[RunEntry.guestTarget] 留空）；其余是「BDS 会按可执行文件所在目录去找」
     * 的配置与内容，指回服务器目录里的真实位置。
     */
    fun runDirPlan(serverDir: File, guestServerDir: String): List<RunEntry> {
        val entries = mutableListOf(RunEntry(ENTRY_NAME, ""))
        CARRY_OVER_NAMES.forEach { name ->
            if (File(serverDir, name).exists()) {
                entries += RunEntry(name, "$guestServerDir/$name")
            }
        }
        return entries
    }

    /**
     * 需要在运行目录里「指回服务器目录」的名字（目录与文件都要）。
     *
     * 都是**只读**资源，用符号链接即可；唯一例外是 `server.properties`，
     * 它有专门的 [stageProperties] 负责复制。
     */
    private val CARRY_OVER_NAMES = listOf(
        "permissions.json",
        "allowlist.json",
        "bootstrap.json",
        "valid_known_packs.json",
        "definitions",
        "resource_packs",
        "behavior_packs",
        "config",
        "data"
    )

    private fun linkOrCopy(source: File?, link: File, guestTarget: String) {
        if (link.exists() && Files.isSymbolicLink(link.toPath())) {
            val current = runCatching { Files.readSymbolicLink(link.toPath()).toString() }.getOrNull()
            if (current == guestTarget) return
        }
        runCatching { link.delete() }
        val created = runCatching {
            // 链接目标是 **guest 路径**：宿主上看是悬空的，只有容器里才有意义
            Files.createSymbolicLink(link.toPath(), File(guestTarget).toPath())
            true
        }.getOrDefault(false)
        if (!created && source != null) {
            // 私有文件系统不支持符号链接（极少见）：退回复制，保证能跑
            source.copyTo(link, overwrite = true)
            BdsSupport.makeExecutable(link)
        } else if (!created && source == null) {
            runCatching { Log.w(TAG, "无法创建内容目录链接：$guestTarget（BDS 会以工作目录为准继续）") }
        }
    }
}
