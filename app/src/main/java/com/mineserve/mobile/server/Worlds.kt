package com.mineserve.mobile.server

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 世界（存档）目录的识别、统计与打包。
 *
 * 刻意做成**纯路径逻辑**（只依赖 java.io），不引用 TermuxRuntime，方便单元测试；
 * 路径解析与读写 server.properties 由 [WorldManager] 负责。
 *
 * 世界目录的判定（覆盖本项目支持的各服务端）：
 * - Java 系（Vanilla / Spigot / Paper / Forge / NeoForge / Fabric）：目录含 `level.dat`
 * - 只留下区域文件的第三方实现：目录含 `region`
 * - 基岩系（Nukkit / PowerNukkitX）：目录含 `db`
 *
 * 维度目录不算独立世界：`<名字>_nether` / `<名字>_the_end` 在 `<名字>` 也存在时
 * 视为它的下界/末地，导出与删除都要连带处理。
 */
object Worlds {

    enum class Kind { Java, Bedrock, Unknown }

    /** 一个世界目录的静态信息。 */
    data class WorldInfo(
        /** 目录名，也就是 server.properties 里 level-name 的取值。 */
        val name: String,
        val dir: File,
        val kind: Kind,
        /** 该世界及其维度目录占用的字节数。 */
        val sizeBytes: Long,
        /** 统计是否因文件数过多而提前停止（此时 sizeBytes 只是下限）。 */
        val sizeTruncated: Boolean,
        /** 是否是 server.properties 当前 level-name 指向的世界。 */
        val isActive: Boolean
    ) {
        val sizeText: String get() = formatSize(sizeBytes)
    }

    /** Java 版世界标识文件。 */
    const val LEVEL_DAT = "level.dat"

    /**
     * 各服务端默认/常见的世界容器目录名。
     *
     * 用于「有没有世界可备份」这类前置判断：Java 系是 `world/`，
     * 基岩系（PowerNukkitX）是 `worlds/`（里面才是世界目录）。
     */
    val WORLD_DIR_NAMES: List<String> = listOf("world", "worlds")

    /** 只留下区域文件的第三方实现的标识目录。 */
    const val REGION_DIR = "region"

    /** 基岩系（Nukkit / PowerNukkitX）的数据目录。 */
    const val DB_DIR = "db"

    /** 下界维度目录后缀。 */
    const val NETHER_SUFFIX = "_nether"

    /** 末地维度目录后缀。 */
    const val END_SUFFIX = "_the_end"

    /** 统计目录大小时最多遍历的文件数，避免超大世界长时间占用 IO。 */
    const val SIZE_SCAN_FILE_LIMIT = 20_000

    /** 备份目录的后缀标记：`world.bak.20260728_153000` 这类目录不作为独立世界展示。 */
    private const val BACKUP_MARKER = ".bak"

    /**
     * 判断一个目录是否为世界目录（含任一标识）。
     *
     * `db` 优先于 `level.dat`：PowerNukkitX 的世界目录**同时**有 `db/` 与 `level.dat`，
     * 若先判 `level.dat` 会把基岩版世界错认成 Java 版。代价是 Java 世界若被插件写了
     * 一个 `db/` 也会判成基岩版——后者没有实际影响（只影响列表上的类型标签）。
     */
    fun detectKind(dir: File): Kind = when {
        File(dir, DB_DIR).isDirectory -> Kind.Bedrock
        File(dir, LEVEL_DAT).isFile -> Kind.Java
        File(dir, REGION_DIR).isDirectory -> Kind.Java
        else -> Kind.Unknown
    }

    /** 该目录看起来是不是一个世界。 */
    fun isWorld(dir: File): Boolean = dir.isDirectory && detectKind(dir) != Kind.Unknown

    /**
     * 世界目录的**父目录**：Java 系就是服务端目录本身，
     * 基岩系（PNX）是服务端目录下的 `worlds`。
     */
    fun worldsRoot(serverDir: File, bedrock: Boolean): File =
        if (bedrock) File(serverDir, "worlds") else serverDir

    /**
     * 列出 [worldsRoot] 下可直接切换的世界。
     *
     * 维度目录（`X_nether` / `X_the_end` 且 `X` 也在）与备份目录不会单独列出。
     */
    fun list(worldsRoot: File, levelName: String): List<WorldInfo> {
        val dirs = worldsRoot.listFiles { f -> f.isDirectory } ?: return emptyList()
        val names = dirs.map { it.name }.toSet()

        return dirs
            .filter { isWorld(it) }
            .filter { !it.name.contains(BACKUP_MARKER) }
            .filter { dimensionOwner(it.name, names) == null }
            .map { dir ->
                val (size, truncated) = sizeOf(includeDimensions(dir, names))
                WorldInfo(
                    name = dir.name,
                    dir = dir,
                    kind = detectKind(dir),
                    sizeBytes = size,
                    sizeTruncated = truncated,
                    isActive = dir.name == levelName
                )
            }
            .sortedWith(compareByDescending<WorldInfo> { it.isActive }.thenBy { it.name })
    }

    /**
     * 该目录属于哪个世界的维度；返回 null 表示它自己就是主世界。
     *
     * `world_nether` 与 `world_the_end` 只在同名主世界存在时才归并——否则用户可能
     * 真的建了一个叫 `world_nether` 的世界，此时它应该是独立可切换的。
     */
    fun dimensionOwner(name: String, allNames: Set<String>): String? {
        for (suffix in listOf(NETHER_SUFFIX, END_SUFFIX)) {
            if (name.endsWith(suffix)) {
                val owner = name.removeSuffix(suffix)
                if (owner.isNotEmpty() && owner in allNames) return owner
            }
        }
        return null
    }

    /** 一个世界连同它的维度目录一起返回；顺序固定为 主世界、下界、末地。 */
    fun includeDimensions(worldDir: File, allNames: Set<String>): List<File> {
        val base = worldDir.name
        val candidates = listOf(
            worldDir,
            File(worldDir.parentFile, base + NETHER_SUFFIX),
            File(worldDir.parentFile, base + END_SUFFIX)
        )
        return candidates.filter { it.isDirectory }
    }

    /** 统计一组目录的占用大小，文件数超限时提前停止。 */
    fun sizeOf(dirs: List<File>): Pair<Long, Boolean> {
        var total = 0L
        var count = 0
        for (dir in dirs) {
            dir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    total += f.length()
                    count++
                }
                if (count >= SIZE_SCAN_FILE_LIMIT) return total to true
            }
        }
        return total to false
    }

    /** 在世界目录里挑一个尚未被占用的名字：`base`、`base-2`、`base-3`…… */
    fun uniqueName(worldsRoot: File, base: String): String {
        val safe = sanitize(base)
        if (!File(worldsRoot, safe).exists()) return safe
        var index = 2
        while (File(worldsRoot, "$safe-$index").exists()) index++
        return "$safe-$index"
    }

    /** 目录名净化：去掉路径分隔符与 Windows 非法字符，避免构造出越界路径。 */
    fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim().trim('.').ifBlank { "world" }

    /** 本应用导出世界时给人看的前缀（`世界_<服务器目录>_<世界名>_<时间戳>.zip`）。 */
    const val EXPORT_PREFIX = "世界_"

    /** 导出文件名尾部的时间戳，见 [WorldManager] 的导出命名。 */
    private val EXPORT_TIMESTAMP = Regex("""_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}$""")

    /**
     * 决定导入世界落盘用的目录名。
     *
     * 问题背景：导入时的期望名来自用户所选文件的名字。若他选的是本应用导出的包，
     * 名字会是 `世界_world`（甚至带服务器目录名与时间戳）——这是给人看的展示名，
     * 直接当目录名会在服务端目录里留下 `世界_world` 这种**不符合世界目录命名规范**
     * 的文件夹，也让 `_nether`/`_the_end` 的维度归并、`level-name` 的可读性一起变差。
     *
     * 所以：只有期望名确实带着本应用的导出前缀时，才改用压缩包里**真正的世界目录名**
     * （[inspectZip] 的 primary）；其余情况尊重调用方给的期望名，不做任何猜测。
     */
    fun importedWorldName(zip: File, preferred: String): String {
        val trimmed = preferred.trim()
        if (!trimmed.startsWith(EXPORT_PREFIX)) return sanitize(trimmed)
        val primary = inspectZip(zip).primary?.takeIf { it.isNotBlank() }
        return sanitize(primary ?: exportStem(trimmed))
    }

    /**
     * 从本应用的导出文件名还原世界名。
     *
     * 处理三种形态：`world`、`世界_world`、`世界_<服务器目录>_<世界名>_<时间戳>`。
     * 仅在压缩包里找不到世界目录（根级散放的包）时才需要它兜底，
     * 因此世界名本身带下划线时只能取到最后一段——这是可接受的退让。
     */
    fun exportStem(name: String): String {
        val trimmed = name.trim()
        val hasTimestamp = EXPORT_TIMESTAMP.containsMatchIn(trimmed)
        val core = trimmed.replace(EXPORT_TIMESTAMP, "")
        val picked = if (hasTimestamp && core.startsWith(EXPORT_PREFIX)) {
            // 去掉「世界_」后，倒数第二段才是世界名（最后一段是服务器目录名）
            core.removePrefix(EXPORT_PREFIX).substringAfterLast('_')
        } else {
            core.removePrefix(EXPORT_PREFIX)
        }
        return picked.ifBlank { "world" }
    }

    /** 把世界目录（含维度）打包为 zip，条目以世界目录名为顶层前缀。 */
    fun exportToZip(worldDir: File, allNames: Set<String>, out: File): Boolean = try {
        out.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            includeDimensions(worldDir, allNames).forEach { dir ->
                dir.walkTopDown().forEach { f ->
                    val rel = f.relativeTo(dir).path.replace('\\', '/')
                    val entryName = if (rel.isEmpty()) "${dir.name}/" else "${dir.name}/$rel"
                    if (f.isDirectory) {
                        zos.putNextEntry(ZipEntry(if (entryName.endsWith("/")) entryName else "$entryName/"))
                        zos.closeEntry()
                    } else {
                        zos.putNextEntry(ZipEntry(entryName))
                        FileInputStream(f).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
        out.exists() && out.length() > 0
    } catch (e: Exception) {
        out.delete()
        false
    }

    /**
     * 从 zip 导入一个世界到 [worldsRoot] 下名为 [targetName] 的目录。
     *
     * 兼容三种压缩结构：
     * 1. 本应用导出的格式：顶层是 `世界名/`、`世界名_nether/`、`世界名_the_end/`
     * 2. 世界文件直接躺在压缩包根目录（常见于网上下载的世界包）
     * 3. 顶层是单个世界目录，但名字与目标名不同（导入时整体改名）
     *
     * 只接受主世界与它的两个维度目录，其它顶层目录一律忽略——避免把压缩包里
     * 夹带的无关文件（甚至恶意路径）铺进服务端目录。含 zip-slip 防护。
     */
    fun importWorldZip(zip: File, worldsRoot: File, targetName: String): Boolean {
        val name = sanitize(targetName)
        val destRoot = File(worldsRoot, name)
        val dimensionDirs = listOf(File(worldsRoot, name + NETHER_SUFFIX), File(worldsRoot, name + END_SUFFIX))
        // 记录「本次导入确实写入过内容的顶层目录」。
        // 清理残渣时只能删这些目录：dimensionDirs 在导入开始前可能**本来就存在**
        // （Bukkit 布局下 world_nether / world_the_end 是独立世界，甚至可能只有维度目录
        // 而没有主世界），无条件 deleteRecursively() 会把与本次导入无关的既有世界删掉。
        val touchedTops = linkedSetOf<File>()
        return try {
            val layout = inspectZip(zip)
            val mapping = linkedMapOf<String, String>()
            if (layout.primary != null) {
                mapping[layout.primary] = name
                mapping[layout.primary + NETHER_SUFFIX] = name + NETHER_SUFFIX
                mapping[layout.primary + END_SUFFIX] = name + END_SUFFIX
            }
            // 根目录结构与「找得到主世界」都不成立 => 这不是一个世界包
            if (!layout.rootLevel && mapping.isEmpty()) return false
            // 根级散放文件 + 顶层还带着维度目录：两种结构混在一起，无法判断
            // `<世界>_nether` 到底该落到哪儿。硬导会把它塞进主世界目录内部，
            // 而 isWorld(destRoot) 仍然成立 —— 静默产出错误布局，不如直接拒绝。
            if (layout.rootLevel && layout.topLevels.any { isDimensionName(it) }) {
                return false
            }
            // 顶层有世界目录时按「整体改名」处理，根级散落的 readme.txt / .DS_Store 之类
            // 一律忽略；只有整包都散在根级时才走根级模式。
            val useRootLevel = layout.rootLevel && mapping.isEmpty()

            // 解压是逐条目写入，目标目录已存在会把两个世界混在一起，这里直接拒绝。
            // 维度目录同样要查：只查主世界会让已存在的同名独立世界被静默覆写。
            if (destRoot.exists()) return false
            if (!useRootLevel && dimensionDirs.any { it.exists() }) return false
            destRoot.mkdirs()

            // 落点上界：主世界目录本身，以及它旁边的两个维度目录。
            // 锚在 worldsRoot（= 服务端目录）会把 `world/../server.properties` 放行，
            // 所以除 canonical 前缀之外还要校验顶层成分确实落在允许集合里。
            val canonicalBase = (destRoot.parentFile ?: worldsRoot).canonicalPath + File.separator
            val allowedTops = setOf(name, name + NETHER_SUFFIX, name + END_SUFFIX)

            // 清理残渣时只能删这些目录（见 touchedTops 的说明）。
            FileInputStream(zip).buffered().use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val rawName = entry.name.replace('\\', '/').removePrefix("./").trimStart('/')
                        val target = resolveImportTarget(rawName, layout, mapping, destRoot, useRootLevel)
                        if (target != null && isAllowedTarget(target, canonicalBase, allowedTops)) {
                            owningTop(target, destRoot, dimensionDirs)?.let { touchedTops.add(it) }
                            if (entry.isDirectory) {
                                if (target.exists() && !target.isDirectory) target.delete()
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out -> zis.copyTo(out) }
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }

            val imported = isWorld(destRoot)
            // 解出来不是世界目录：清掉残渣，避免在服务端目录里留下一个空壳。
            // 只清本次真正写过东西的目录，绝不碰导入前就存在的世界。
            if (!imported) {
                touchedTops.forEach { it.deleteRecursively() }
                destRoot.deleteRecursively()
            }
            imported
        } catch (e: Exception) {
            // 异常时同样只清本次写入过的部分，避免把导入前就存在的维度世界连带删掉。
            touchedTops.forEach { it.deleteRecursively() }
            destRoot.deleteRecursively()
            false
        }
    }

    /** 把一个落点文件/目录映射回它所属的顶层目录（主世界或两个维度目录），不在其中则返回 null。 */
    private fun owningTop(target: File, destRoot: File, dimensionDirs: List<File>): File? {
        val candidates = listOf(destRoot) + dimensionDirs
        return candidates.firstOrNull { top ->
            target == top || target.absolutePath.startsWith(top.absolutePath + File.separator)
        }
    }

    /** 名字是否带维度后缀（`X_nether` / `X_the_end`）。 */
    private fun isDimensionName(name: String): Boolean =
        name.endsWith(NETHER_SUFFIX) || name.endsWith(END_SUFFIX)

    /**
     * 落盘位置的两级校验：canonical 必须在 [canonicalBase] 之内，且相对路径的
     * 第一个成分必须属于 [allowedTops]。
     *
     * 只做第一级是不够的——维度目录与目标世界目录是**兄弟**关系（都在 worldsRoot 下），
     * 因此上界只能是 worldsRoot，而 worldsRoot 里还有 server.properties / plugins 等
     * 不该被导入流程碰到的东西。第二级负责把「服务端目录内的任意文件」排除掉。
     */
    private fun isAllowedTarget(target: File, canonicalBase: String, allowedTops: Set<String>): Boolean {
        val canonical = try {
            target.canonicalPath
        } catch (_: Exception) {
            return false
        }
        if (!canonical.startsWith(canonicalBase)) return false
        val rel = canonical.removePrefix(canonicalBase)
        val top = rel.substringBefore(File.separatorChar)
        return top in allowedTops
    }

    /**
     * 压缩包的顶层结构。
     *
     * @param primary 含世界标识（level.dat / region / db）的顶层目录名；null 表示没有
     * @param rootLevel 世界文件是否直接躺在压缩包根目录（没有顶层目录）
     * @param topLevels 全部出现过的顶层目录名
     */
    data class ZipLayout(val primary: String?, val rootLevel: Boolean, val topLevels: Set<String>)

    /** 扫描压缩包判断它的顶层结构，不落盘。 */
    fun inspectZip(zip: File): ZipLayout {
        val topLevels = linkedSetOf<String>()
        val worldLike = linkedSetOf<String>()
        var rootLevel = false
        try {
            ZipInputStream(FileInputStream(zip).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/').removePrefix("./").trimStart('/')
                    if (name.isNotEmpty()) {
                        if (!name.contains('/')) {
                            // 条目直接在根：要么是文件，要么是 `<名>/` 这种目录占位
                            if (!entry.isDirectory) rootLevel = true
                        } else {
                            val top = name.substringBefore('/')
                            val rest = name.substringAfter('/', "").trimEnd('/')
                            topLevels += top
                            if (rest == LEVEL_DAT || rest == REGION_DIR || rest == DB_DIR) {
                                worldLike += top
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {
            // 损坏的 zip 交给调用方按「导入失败」处理
        }
        // 下界/末地目录自己也含 level.dat，若先到先得会把 `X_nether` 当成主世界，
        // 真正的 `X/` 条目全被丢掉。优先挑不带维度后缀的顶层目录；
        // 只有整包只剩维度目录时才退回用它（此时导入的是维度本身，至少不会静默错认）。
        val picked = worldLike.firstOrNull { !isDimensionName(it) } ?: worldLike.firstOrNull()
        return ZipLayout(picked, rootLevel, topLevels)
    }

    /** 把压缩包里的条目名映射到落盘位置；返回 null 表示该条目应被忽略。 */
    private fun resolveImportTarget(
        rawName: String,
        layout: ZipLayout,
        mapping: Map<String, String>,
        destRoot: File,
        useRootLevel: Boolean
    ): File? {
        if (rawName.isEmpty()) return null
        // 规范化后含 `..` 的条目直接丢弃：它是 zip-slip 的唯一入口，
        // 光靠落点前缀校验会把它变成「静默跳过」，排查时看不出包有问题。
        if (rawName.split('/').any { it == ".." }) return null
        if (useRootLevel) return File(destRoot, rawName)
        val top = rawName.substringBefore('/')
        val rest = rawName.substringAfter('/', "")
        val mappedTop = mapping[top] ?: return null
        val parent = destRoot.parentFile ?: return null
        return if (rest.isEmpty()) File(parent, mappedTop) else File(parent, "$mappedTop/$rest")
    }

    /**
     * 删除世界（含维度）。返回被删除的目录数量，0 表示什么都没删。
     *
     * 调用方负责先做安全备份；本方法只负责删。
     */
    fun deleteWorld(worldDir: File, allNames: Set<String>): Int {
        if (!worldDir.isDirectory) return 0
        var removed = 0
        includeDimensions(worldDir, allNames).forEach { dir ->
            if (dir.deleteRecursively()) removed++
        }
        return removed
    }

    /** 判断目录（或任一祖先）是否位于给定根目录之内，用于防止越界删除。 */
    fun isInside(child: File, root: File): Boolean = try {
        val rootPath = root.canonicalPath + File.separator
        child.canonicalPath.startsWith(rootPath)
    } catch (_: Exception) {
        false
    }

    /** 人类可读的大小文本。 */
    fun formatSize(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(java.util.Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(java.util.Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(java.util.Locale.US, "%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }
}
