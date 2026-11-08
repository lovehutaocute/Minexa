package com.mineserve.mobile.server

import com.mineserve.mobile.data.BdsVersions
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 跨版本安装兼容层：**先看清这个包长什么样，再决定怎么解**。
 *
 * 官方 BDS 从 1.6 发到 1.26，同一个 zip 的形态并不统一，而这些差异恰好都会让
 * 原来只按「最新版」写的解压/校验逻辑失败：
 *
 * | 差异 | 出现在 | 原来的后果 |
 * |---|---|---|
 * | 主程序 40~90MB（动态链接，带一批 `.so`） | ≤1.16 | 被 `MIN_ENTRY_BYTES=50MB` 判成「包不完整」 |
 * | 主程序 244~265MB 静态链接 | 1.21+ | 正常 |
 * | 带 `bedrock_server_symbols.debug`（数百 MB） | 部分 1.16~1.19 | 白占几百 MB，手机上直接吃满空间 |
 * | 包内多一层顶层目录（第三方重打包） | 非官方来源 | 解压出一堆散文件，主程序落到子目录里 |
 * | 缺 `server.properties` | 个别 Preview | 校验判「不是完整安装包」，装不上 |
 *
 * 因此安装不再写死「平铺 + 固定阈值」，而是先读 zip 中央目录得出
 * [BdsInstallPlan]，再按计划解压。计划是纯数据、可单测。
 *
 * 刻意**不做**的事：不按版本号写死「这个版本用哪个布局」的表。版本号只是提示，
 * 真实结构永远以 zip 里的条目为准 —— 清单里记错构建号、用户手动换包、
 * 官方悄悄重打包，都不会让安装逻辑走错分支。
 */
object BdsCompatibility {

    /** 安装计划算不出来（不是官方 BDS 包 / Windows 包 / 路径非法） */
    class PlanFailed(message: String) : RuntimeException(message)

    /**
     * 一个安装包的解压计划。
     *
     * @param entryPath  zip 内主程序条目名（可能带顶层目录前缀）
     * @param rootPrefix 需要剥离的前缀（`""` 或 `"dir/"`）：解压时按它裁掉
     * @param entryBytes 主程序在包内声明的解压后体积；-1 表示未知
     * @param libs       与主程序同目录的动态库（老版本有，1.21+ 为空）
     * @param hasProperties 包里是否自带 `server.properties`
     * @param pruned     主动跳过的条目（调试符号，几百 MB 且运行不需要）
     * @param notes      给控制台看的事实说明
     */
    data class Plan(
        val version: String,
        val entryPath: String,
        val rootPrefix: String,
        val entryBytes: Long,
        val libs: List<String>,
        val hasProperties: Boolean,
        val pruned: List<String>,
        val notes: List<String>
    ) {
        /** 主程序在目标目录里的相对路径（`rootPrefix` 已剥掉，所以就是文件名） */
        val entryName: String get() = entryPath.substringAfterLast('/')

        /** 老版本（动态链接）：运行时要 `LD_LIBRARY_PATH=.` 才能找到同目录的 .so */
        val isDynamic: Boolean get() = libs.isNotEmpty()
    }

    /** 调试符号：运行完全不需要，老版本里能占几百 MB */
    fun isSymbolEntry(name: String): Boolean {
        val lower = name.lowercase()
        if (!lower.endsWith(".debug") && !lower.contains("_symbols")) return false
        return !lower.endsWith("bedrock_server")
    }

    /**
     * 读 zip 中央目录得出安装计划。
     *
     * 只读目录不读数据：90MB 的包在手机上解压前先扫一遍数据太慢，
     * 而条目名、声明体积、是否有 `.so` 全在中央目录里。
     */
    fun plan(zip: File, version: String): Plan {
        val entries = try {
            ZipFile(zip).use { zf ->
                val list = mutableListOf<ZipEntry>()
                val it = zf.entries()
                while (it.hasMoreElements()) list += it.nextElement()
                list
            }
        } catch (e: Throwable) {
            throw PlanFailed("安装包无法打开：${e.message ?: e.javaClass.simpleName}")
        }
        if (entries.isEmpty()) throw PlanFailed("安装包里没有任何文件，不是 BDS 安装包")

        entries.forEach { entry ->
            val name = normalize(entry.name)
            if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                throw PlanFailed("压缩包包含非法路径：${entry.name}")
            }
        }

        val normalized = entries.map { normalize(it.name) to it }
        val bootEntries = normalized.filter { (name, entry) ->
            !entry.isDirectory && name.substringAfterLast('/') == BdsSupport.ENTRY_NAME
        }
        if (bootEntries.isEmpty()) {
            val windowsBuild = normalized.any { (name, _) ->
                !name.endsWith("/") && name.substringAfterLast('/').equals("bedrock_server.exe", ignoreCase = true)
            }
            throw PlanFailed(
                if (windowsBuild) "这是 Windows 版 BDS 安装包（只有 bedrock_server.exe），本机无法运行"
                else "安装包里没有 ${BdsSupport.ENTRY_NAME}，不是官方 BDS 安装包"
            )
        }
        // 有多个同名条目时取层级最浅的那个：重打包的包里常见
        // `bedrock_server` 和一个备份副本并存。
        val (entryPath, entryEntry) = bootEntries.minByOrNull { it.first.count { c -> c == '/' } }!!
        val rootPrefix = entryPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

        val notes = mutableListOf<String>()
        if (rootPrefix.isNotEmpty()) {
            notes += "安装包内多一层目录 ${rootPrefix.trimEnd('/')}，已自动剥离"
        }

        val siblings = normalized.filter { (name, entry) ->
            !entry.isDirectory && name.startsWith(rootPrefix) && !name.removePrefix(rootPrefix).contains('/')
        }
        val libs = siblings.map { it.first.removePrefix(rootPrefix) }
            .filter { it.endsWith(".so", ignoreCase = true) }
            .sorted()
        val pruned = normalized.filter { (name, entry) ->
            !entry.isDirectory && name.startsWith(rootPrefix) && isSymbolEntry(name.removePrefix(rootPrefix))
        }.map { it.first.removePrefix(rootPrefix) }.sorted()
        val hasProperties = siblings.any {
            it.first.removePrefix(rootPrefix).equals(BdsSupport.PROPERTIES_NAME, ignoreCase = true)
        }

        val entryBytes = entryEntry.size.takeIf { it > 0 } ?: -1L
        if (entryBytes in 1L until ABSURD_SMALL_ENTRY) {
            throw PlanFailed(
                "主程序只有 $entryBytes 字节，安装包不完整（正常版本在 " +
                    "${ABSURD_SMALL_ENTRY / (1024 * 1024)}MB 以上）"
            )
        }
        if (libs.isEmpty()) {
            notes += "该版本主程序为静态链接（目录内无 .so），与 1.21+ 一致"
        } else {
            notes += "该版本为动态链接，依赖同目录 ${libs.size} 个 .so（启动时已带 LD_LIBRARY_PATH=.）"
        }
        if (!hasProperties) notes += "包里没有 ${BdsSupport.PROPERTIES_NAME}，将按官方默认值生成一份"
        if (pruned.isNotEmpty()) {
            notes += "已跳过 ${pruned.size} 个调试符号文件（运行不需要，可省下数百 MB）"
        }
        // 版本号只用于提示，不参与分支：老版本的控制台命令集与新版本不同，
        // 这一点用户改配置时最容易踩。
        if (BdsVersions.compare(version, "1.13.0") < 0) {
            notes += "这是 1.13 之前的存档版本，控制台命令与 1.21 不同（例如 save hold/query/resume 不存在）"
        }
        if (libs.size > MAX_REASONABLE_LIBS) {
            throw PlanFailed("主程序同目录有 ${libs.size} 个 .so，远超官方包的结构，可能是错误重打包")
        }

        return Plan(
            version = version,
            entryPath = entryPath,
            rootPrefix = rootPrefix,
            entryBytes = entryBytes,
            libs = libs,
            hasProperties = hasProperties,
            pruned = pruned,
            notes = notes
        )
    }

    /**
     * 按计划解压到 [targetDir]。
     *
     * 与通用解压的区别，每条都是为兼容性服务：
     *  - 只解 [Plan.rootPrefix] 之下的条目，顶层目录因此被剥掉；
     *  - 跳过 [Plan.pruned]（调试符号）；
     *  - 每个文件写完比对**声明体积**：CRC 已在校验阶段验过，
     *    这里再核对一次是防止「边下边解」时读到被截断的数据流；
     *  - 主程序补可执行位。
     *
     * @return 解压出的文件数
     */
    fun extract(zip: File, targetDir: File, plan: Plan): Int {
        targetDir.mkdirs()
        val canonicalTarget = targetDir.canonicalFile
        var count = 0
        ZipFile(zip).use { zf ->
            val list = zf.entries()
            while (list.hasMoreElements()) {
                val entry = list.nextElement()
                val name = normalize(entry.name)
                if (name.isBlank()) continue
                if (!name.startsWith(plan.rootPrefix)) continue
                val relative = name.removePrefix(plan.rootPrefix)
                if (relative.isBlank()) continue
                if (relative.endsWith("/")) {
                    File(targetDir, relative).mkdirs()
                    continue
                }
                if (relative in plan.pruned) continue

                val outFile = File(targetDir, relative)
                if (!outFile.canonicalPath.startsWith(canonicalTarget.path + File.separator)) {
                    throw PlanFailed("压缩包包含非法路径：${entry.name}")
                }
                outFile.parentFile?.mkdirs()
                var written = 0L
                zf.getInputStream(entry).use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            written += n
                        }
                    }
                }
                if (entry.size >= 0 && written != entry.size) {
                    throw PlanFailed("${entry.name} 解压不完整：期望 ${entry.size} 字节，实际 $written 字节")
                }
                if (relative.substringAfterLast('/') == BdsSupport.ENTRY_NAME) {
                    BdsSupport.makeExecutable(outFile)
                }
                count++
            }
        }
        // 某些打包工具不写 unix mode，统一再补一次
        File(targetDir, plan.entryName).takeIf { it.isFile }?.let { BdsSupport.makeExecutable(it) }
        if (count == 0) throw PlanFailed("安装包里没有可解压的内容")
        return count
    }

    /**
     * 包里缺 `server.properties` 时按官方默认值生成。
     *
     * 键与顺序取自官方包自带的模板；`online-mode` 默认 false 是本项目的既定约定
     * （正版验证要连微软服务，国内网络常不通），`allow-list` 保持 false 是因为
     * 离线模式 + 白名单是 BDS 的硬性禁组合。
     */
    fun propertiesTemplate(port: Int = 19132, levelName: String = "Bedrock level"): String = buildString {
        appendLine("# Generated by Minexa: official package did not include server.properties")
        appendLine("server-name=Minexa BDS")
        appendLine("gamemode=survival")
        appendLine("force-gamemode=false")
        appendLine("difficulty=easy")
        appendLine("allow-cheats=false")
        appendLine("max-players=10")
        appendLine("online-mode=false")
        appendLine("allow-list=false")
        appendLine("server-port=$port")
        appendLine("server-portv6=19133")
        appendLine("enable-lan-visibility=true")
        appendLine("view-distance=32")
        appendLine("tick-distance=4")
        appendLine("player-idle-timeout=30")
        appendLine("max-threads=8")
        appendLine("level-name=$levelName")
        appendLine("level-seed=")
        appendLine("default-player-permission-level=member")
        appendLine("texturepack-required=false")
        appendLine("content-log-file-enabled=false")
        appendLine("compression-threshold=1")
        appendLine("compression-algorithm=zlib")
        appendLine("server-authoritative-movement=server-auth")
        appendLine("player-movement-score-threshold=20")
        appendLine("player-movement-action-direction-threshold=0.85")
        appendLine("player-movement-distance-threshold=0.3")
        appendLine("player-movement-duration-threshold-in-ms=500")
        appendLine("correct-player-movement=false")
        appendLine("server-authoritative-block-breaking=false")
    }

    /**
     * 落地后复检：主程序体积、架构、依赖库架构一致性。
     *
     * @return 失败原因；null 表示通过
     */
    fun verifyInstalled(dir: File, plan: Plan, abis: List<String>): String? {
        val entry = File(dir, plan.entryName)
        if (!entry.isFile) return "目录里没有 ${BdsSupport.ENTRY_NAME}，解压不完整"
        if (plan.entryBytes > 0 && entry.length() != plan.entryBytes) {
            return "主程序体积不符：期望 ${plan.entryBytes} 字节，实际 ${entry.length()} 字节"
        }
        if (entry.length() < ABSURD_SMALL_ENTRY) return "主程序只有 ${entry.length()} 字节，明显不完整"
        val arch = BdsSupport.elfArch(entry)
            ?: return "$BdsSupport.ENTRY_NAME 不是可识别的 Linux 可执行文件"
        val supported = BdsSupport.deviceArch(abis)
        val runnable = arch in supported ||
            (arch == BdsSupport.ElfArch.X86_64 && BdsSupport.ElfArch.AARCH64 in supported)
        if (!runnable) return "主程序是 ${arch.displayName} 架构，本机无法运行"
        // 老版本的自带 .so 必须是同一架构，混架构目录一定 dlopen 失败
        plan.libs.forEach { name ->
            val lib = File(dir, name)
            if (!lib.isFile) return "缺少动态库 $name（该版本依赖它，运行时会立即退出）"
            val libArch = BdsSupport.elfArch(lib)
            if (libArch != null && libArch != arch) {
                return "动态库 $name 是 ${libArch.displayName}，与主程序 ${arch.displayName} 不一致"
            }
        }
        return null
    }

    private fun normalize(name: String): String =
        name.replace('\\', '/').removePrefix("./").trim()

    /** 官方包里主程序最小也有 30MB 级；低于 8MB 一定是错误页或残包 */
    private const val ABSURD_SMALL_ENTRY = 8L * 1024 * 1024

    /** 官方包同目录的 .so 最多十几个（1.16 那批）；几十个说明结构不对 */
    private const val MAX_REASONABLE_LIBS = 40
}
