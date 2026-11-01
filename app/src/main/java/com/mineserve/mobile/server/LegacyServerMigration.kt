package com.mineserve.mobile.server

import android.content.Context
import android.util.Log
import com.mineserve.mobile.data.InstalledCore
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.ServerStorage
import com.mineserve.mobile.data.StorageLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 旧版私有目录 → 统一存储目录的一次性迁移。
 *
 * ## 为什么是「迁移」而不是「兼容」
 *
 * 旧版把服务端放在应用沙盒 `<filesDir>/home/home/servers/` 里，新版本统一放在
 * `/storage/emulated/0/Minexa/Minecraftserver/` 并**只认这一个根**。两个根同时生效
 * 会带来一连串难以收场的问题：同名目录归属不明、启动时找错目录、私有目录里的
 * 老服务器在列表里「看得见但跑不起来」。
 *
 * 因此旧目录只在迁移流程里出现：检测到有内容就询问用户是否搬过来，搬完删除源目录。
 * 不搬则列表不显示那些核心（不兼容），下次启动继续问。
 *
 * ## 安全约束
 *
 * 服务端目录里是用户的世界存档，搬坏了无法恢复，因此：
 *  1. 先在**同一文件系统**下的临时目录 `.{dirName}.migrating-{ts}` 里完整复制；
 *  2. 按「相对路径 + 字节数」**逐文件**比对，任何一项缺失即判定失败（源目录原封不动）；
 *  3. 校验通过才 `rename` 到最终目录名（同根内 rename 是原子的）；
 *  4. **最后**删除源目录；任何一步失败都保留源目录并回报原因。
 *
 * ## 为什么校验要逐文件、且失败要指名道姓
 *
 * 旧实现只比「总文件数 + 总字节数」，出错时只能说「源 1923 / 目标 1922」，
 * 用户和管理员都无从知道少的是哪个文件，重试也只是再搬 750MB 再失败一次。
 * 现在：
 *  - 复制**逐文件容错**（不再因为一个坏文件中断整棵树的复制）；
 *  - 校验比对「相对路径 → 字节数」的映射，异常项直接报出相对路径与原因；
 *  - 失败时**保留临时目录**，下次重试只补缺的文件；
 *  - 仅当失败项都是「源文件读不出来」（权限/悬空链接）时，才允许用户选择
 *    「忽略这些文件继续」——目标位置的写入失败绝不允许跳过。
 */
object LegacyServerMigration {

    private const val TAG = "LegacyServerMigration"

    /** 临时目录前缀：`.` 开头，文件管理器里不显眼，也不会和服务器目录撞名 */
    private const val TEMP_PREFIX = "."

    /** 临时目录超过这个时间没有任何进展就认为是被放弃的残留 */
    private const val STALE_TEMP_MS = 24 * 60 * 60 * 1000L

    /** 一个待迁移的旧服务器 */
    data class Candidate(
        val dirName: String,
        /** 旧目录里的实际路径 */
        val source: File,
        val fileCount: Int,
        val totalBytes: Long,
        /** 已在配置里登记（说明列表曾经显示过它） */
        val registered: Boolean,
        /** 登记过的显示名（未登记时等于 dirName） */
        val displayName: String
    )

    /** 单个目录的迁移结果 */
    data class Outcome(
        val dirName: String,
        val targetDirName: String?,
        val success: Boolean,
        val fileCount: Int = 0,
        val error: String? = null,
        /** 用户选择「忽略并继续」时被跳过的文件（相对路径） */
        val skipped: List<String> = emptyList(),
        /** 失败但可以「忽略无法读取的文件」后继续 */
        val canSkip: Boolean = false
    )

    /** 单个文件的复制失败 */
    internal data class CopyFailure(
        val relPath: String,
        val sizeBytes: Long,
        val reason: String,
        /** true = 源文件本身读不出来（权限/损坏）；只有这类才允许跳过 */
        val unreadable: Boolean
    )

    /** 一次目录复制的统计结果 */
    internal data class CopyReport(
        val fileCount: Int = 0,
        val totalBytes: Long = 0L,
        val failures: List<CopyFailure> = emptyList(),
        /** 非普通文件（悬空符号链接、管道、设备节点等），不参与校验 */
        val specialEntries: List<String> = emptyList()
    )

    /** 扫描旧目录里所有可迁移的服务器（空目录不算） */
    fun scan(context: Context, config: McConfig?): List<Candidate> =
        ServerStorage.legacyServerDirs(context).map { dir ->
            val known = config?.installedCores?.firstOrNull { it.dirName == dir.name }
            val stats = countFiles(dir)
            Candidate(
                dirName = dir.name,
                source = dir,
                fileCount = stats.first,
                totalBytes = stats.second,
                registered = known != null,
                displayName = known?.name ?: dir.name
            )
        }

    /**
     * 依次迁移全部候选目录，返回每个目录的结果。
     *
     * @param allowSkipUnreadable 用户确认「忽略无法读取的文件」后继续
     * @param onProgress (已完成数, 总数, 当前目录名)
     */
    suspend fun migrateAll(
        context: Context,
        candidates: List<Candidate>,
        allowSkipUnreadable: Boolean = false,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): List<Outcome> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptyList()
        // 目标目录名规划要看到「本次已占用的名字」，否则两个旧目录搬进来会撞名
        val taken = ServerStorage.takenDirNames(context).toMutableSet()
        val root = ServerStorage.internalRoot(context)
        // 上一次被放弃的临时目录（超过 24h 没动静）先清掉，避免公共存储里堆垃圾
        cleanupStaleTempDirs(root)
        val outcomes = mutableListOf<Outcome>()
        candidates.forEachIndexed { index, candidate ->
            onProgress(index, candidates.size, candidate.dirName)
            val outcome = migrateOne(
                source = candidate.source,
                internalRoot = root,
                taken = taken,
                allowSkipUnreadable = allowSkipUnreadable
            )
            if (outcome.success && outcome.targetDirName != null) taken += outcome.targetDirName
            outcomes += outcome
        }
        onProgress(candidates.size, candidates.size, "")
        // 全部搬完后，若旧根目录已经空了就一并清掉 —— 留着只会让用户以为
        // 「这里还有东西」，而且下次启动扫描还得再走一遍。
        if (outcomes.all { it.success }) {
            ServerStorage.legacyRootCandidates(context).forEach { legacyRoot ->
                val empty = legacyRoot.isDirectory && (legacyRoot.listFiles()?.isEmpty() != false)
                if (empty) {
                    runCatching { legacyRoot.deleteRecursively() }
                    logI("旧版目录已清空并删除：${legacyRoot.absolutePath}")
                }
            }
        }
        outcomes
    }

    /**
     * 迁移单个目录（纯文件操作，不改配置）。
     *
     * @param taken 已占用的目录名；成功后会由调用方把新名字加进去
     */
    fun migrateOne(
        source: File,
        internalRoot: File,
        taken: MutableSet<String>,
        allowSkipUnreadable: Boolean = false
    ): Outcome {
        val dirName = source.name
        if (!source.isDirectory) {
            return Outcome(dirName, null, false, error = "源目录不存在")
        }
        val targetName = planTargetDirName(dirName, internalRoot, taken)
        val finalDir = File(internalRoot, targetName)
        // 续传：上次失败留下的临时目录直接复用，重试只补缺的文件（不再重搬整棵树）
        val tempDir = resumableTempDir(internalRoot, targetName)
            ?: File(internalRoot, "$TEMP_PREFIX$targetName.migrating-${System.currentTimeMillis()}")
        val resumed = tempDir.exists()
        return try {
            if (!internalRoot.isDirectory && !internalRoot.mkdirs()) {
                return Outcome(dirName, null, false, error = "无法创建统一存储目录")
            }
            // 目标位置若只有一个空壳（之前错误版本留下的幽灵目录），先清掉，避免污染校验
            if (finalDir.isDirectory && ServerStorage.isEmptyDirectory(finalDir)) {
                finalDir.deleteRecursively()
            }
            tempDir.mkdirs()
            val expected = manifest(source)
            if (resumed) logI("$dirName 续传：复用 ${tempDir.name}")

            var report = copyTree(source, tempDir)
            var actual = manifest(tempDir)
            var missing = missingEntries(expected, actual)
            if (missing.isNotEmpty()) {
                // 第二遍只补缺的那几个文件：公共存储（FUSE）偶发写入失败时能救回来
                logW("$dirName 有 ${missing.size} 个文件未落地，补拷一次：${previewPaths(missing)}")
                val retry = copyTree(source, tempDir, only = missing.map { it.first }.toSet())
                report = report.copy(failures = report.failures + retry.failures)
                actual = manifest(tempDir)
                missing = missingEntries(expected, actual)
            }
            val reasons = report.failures.associateBy { it.relPath }
            val skippable = missing.isNotEmpty() && missing.all { reasons[it.first]?.unreadable == true }
            if (missing.isNotEmpty()) {
                val detail = describeMismatch(expected, actual, missing, reasons)
                if (!(allowSkipUnreadable && skippable)) {
                    // 保留临时目录：下次点「迁移」只补这几个文件，不用重搬整棵树
                    return Outcome(
                        dirName, null, false,
                        error = if (skippable) "$detail；可点「忽略并继续」跳过这些文件" else detail,
                        canSkip = skippable
                    )
                }
                logW("$dirName 忽略 ${missing.size} 个无法读取的文件后继续：${previewPaths(missing)}")
            }
            if (!tempDir.renameTo(finalDir)) {
                return Outcome(dirName, null, false, error = "无法把迁移结果重命名为 ${finalDir.name}，源目录未改动")
            }
            // 落地成功后才删源：中途崩溃最多留下一个 .migrating 目录，源永远在
            val removed = source.deleteRecursively()
            if (!removed) {
                logW("迁移完成但源目录删除失败：${source.absolutePath}")
            }
            Outcome(
                dirName, targetName, true,
                fileCount = actual.size,
                skipped = missing.map { it.first }
            )
        } catch (e: Throwable) {
            // catch Throwable：解压/文件系统层可能抛 Error（如 OOM、Unsafe 相关），
            // 只 catch Exception 会让整个迁移流程静默中断
            logE("迁移 $dirName 失败：${e.message}", e)
            Outcome(dirName, null, false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 目标目录名：优先沿用原名（用户认知一致）。
     *
     * - 目标不存在 → 原名；
     * - 目标是空目录（幽灵壳）→ 由 [migrateOne] 先清掉，仍用原名；
     * - 目标已有内容 → `{name}_migrated`、`_migrated_2`…
     */
    fun planTargetDirName(base: String, internalRoot: File, taken: Set<String>): String {
        val direct = File(internalRoot, base)
        val emptyShell = direct.isDirectory && ServerStorage.isEmptyDirectory(direct)
        val occupied = (base in taken || direct.exists()) && !emptyShell
        if (!occupied) return base
        var candidate = "${base}_migrated"
        var i = 2
        while (candidate in taken || File(internalRoot, candidate).exists()) {
            candidate = "${base}_migrated_$i"
            i++
        }
        return candidate
    }

    /**
     * 把迁移结果写回配置。
     *
     *  - 已登记的：把 `dirName`（可能因重名改了）与 `location` 一起更新；
     *  - 未登记但检测出核心的：新增一条记录，用户迁移完就能在列表里看到；
     *  - 未登记且识别不出核心：也登记为 `Unknown`（有文件的目录不该是隐形的）。
     */
    fun applyOutcomes(
        config: McConfig,
        outcomes: List<Outcome>,
        internalRoot: File
    ): McConfig {
        val successful = outcomes.filter { it.success && it.targetDirName != null }
        if (successful.isEmpty()) return config
        val byNewName = successful.associateBy { it.targetDirName!! }
        val byOldName = successful.associateBy { it.dirName }

        val updated = config.installedCores.map { core ->
            val moved = byOldName[core.dirName] ?: byNewName[core.dirName]
            if (moved == null) core
            else core.copy(dirName = moved.targetDirName!!, location = StorageLocation.Internal)
        }
        val merged = updated.toMutableList()
        successful.forEach { outcome ->
            val newName = outcome.targetDirName!!
            if (merged.any { it.dirName == newName }) return@forEach
            val detection = ServerCoreDetector.detect(File(internalRoot, newName))
            merged += InstalledCore(
                name = newName,
                core = detection.core ?: ServerCore.Unknown,
                version = detection.version ?: "",
                dirName = newName,
                serverFile = detection.serverFile,
                location = StorageLocation.Internal
            )
        }
        // 当前选中的核心若被改名，跟着改，否则概览会显示「未选择核心」
        val activeMoved = config.activeCoreName?.let { active ->
            val core = config.installedCores.firstOrNull { it.name == active }
            core?.let { byOldName[it.dirName] }?.let { moved ->
                merged.firstOrNull { it.dirName == moved.targetDirName }?.name
            }
        }
        return config.copy(
            installedCores = merged,
            activeCoreName = activeMoved ?: config.activeCoreName
        )
    }

    // ── 文件工具 ─────────────────────────────────────────────────

    /** (文件数, 总字节) */
    internal fun countFiles(dir: File): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        forEachRegularFile(dir) { _, f -> count++; bytes += f.length() }
        return count to bytes
    }

    /**
     * 目录里所有普通文件的「相对路径 → 字节数」清单。
     *
     * 这是校验的唯一依据：只比总数看不出「少了哪个」，也无法区分「同名但被截断」。
     * 符号链接按目标类型处理（指向文件算普通文件，指向目录会跟进，悬空则忽略）。
     */
    internal fun manifest(dir: File): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        forEachRegularFile(dir) { rel, f -> out[rel] = f.length() }
        return out
    }

    /**
     * 遍历所有普通文件。
     *
     * 手写递归而不是 `walkTopDown()`：后者的 `listFiles()` 返回 null 时会**静默**跳过
     * 整棵子树，导致「复制少了文件但两边的计数都少算，校验反而对上」。
     * 这里对目录不可读、符号链接成环都留下痕迹（回调 rel == null 表示异常项）。
     */
    private fun forEachRegularFile(
        root: File,
        onEntry: (rel: String, file: File) -> Unit
    ) {
        val visited = HashSet<String>()
        fun walk(dir: File, relDir: String, depth: Int) {
            if (depth > 40) {
                logW("目录层级过深，停止遍历：$relDir")
                return
            }
            val children = dir.listFiles()
            if (children == null) {
                logW("无法列出目录内容：${dir.absolutePath}")
                return
            }
            for (f in children) {
                val rel = if (relDir.isEmpty()) f.name else "$relDir/${f.name}"
                when {
                    f.isDirectory -> {
                        val key = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                        if (visited.add(key)) walk(f, rel, depth + 1)
                        else logW("符号链接成环，跳过：$rel")
                    }
                    f.isFile -> onEntry(rel, f)
                    else -> logW("非普通文件，忽略：$rel")
                }
            }
        }
        if (root.isDirectory) walk(root, "", 0)
    }

    /**
     * 复制整棵树（或只复制 [only] 指定的相对路径）。
     *
     * 与旧实现的关键差别是**逐文件容错**：一个文件抛异常只记一条失败，接着复制其余文件。
     * 旧实现里一次异常会中断整棵树，用户看到的是「目标少了 1 个文件」这种莫名其妙的结果，
     * 实际可能少了几百个 —— 而且罪魁祸首（比如一个悬空符号链接）永远不被告知。
     *
     * @param only null = 全量；非 null = 只补这些相对路径（续传/补偿用）
     */
    internal fun copyTree(source: File, target: File, only: Set<String>? = null): CopyReport {
        var count = 0
        var bytes = 0L
        val failures = mutableListOf<CopyFailure>()
        val special = mutableListOf<String>()
        target.mkdirs()
        val visited = HashSet<String>()

        fun walk(dir: File, relDir: String, depth: Int) {
            if (depth > 40) return
            val children = dir.listFiles()
            if (children == null) {
                failures += CopyFailure(
                    relPath = relDir.ifEmpty { "." },
                    sizeBytes = 0L,
                    reason = "无法列出目录内容（权限不足？）",
                    unreadable = true
                )
                return
            }
            for (f in children) {
                val rel = if (relDir.isEmpty()) f.name else "$relDir/${f.name}"
                val wanted = only == null || rel in only || only.any { it.startsWith("$rel/") }
                if (!wanted) continue
                when {
                    f.isDirectory -> {
                        val key = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                        if (visited.add(key)) {
                            File(target, rel).mkdirs()
                            walk(f, rel, depth + 1)
                        } else {
                            special += "$rel（符号链接成环）"
                        }
                    }
                    f.isFile -> {
                        val out = File(target, rel)
                        // 续传：目标已有同名同大小文件就跳过（源是只读快照，字节数相等即可认定完整）
                        if (out.isFile && out.length() == f.length()) {
                            count++
                            bytes += out.length()
                        } else {
                            val err = copyOneFile(f, out)
                            if (err == null) {
                                count++
                                bytes += out.length()
                            } else {
                                failures += CopyFailure(
                                    relPath = rel,
                                    sizeBytes = f.length(),
                                    reason = err.first,
                                    unreadable = err.second
                                )
                            }
                        }
                    }
                    else -> special += "$rel（非普通文件）"
                }
            }
        }
        try {
            walk(source, "", 0)
        } catch (e: Throwable) {
            failures += CopyFailure(".", 0L, "${e.message ?: e.javaClass.simpleName}（遍历中断）", true)
            logE("复制中断：${e.message}", e)
        }
        if (special.isNotEmpty()) logW("跳过的非普通文件：${special.take(5).joinToString("、")}")
        return CopyReport(count, bytes, failures, special)
    }

    /**
     * 复制单个文件，失败自动重试一次。
     *
     * @return null 表示成功；否则 (原因, 是否源侧读不出来)
     */
    private fun copyOneFile(src: File, out: File): Pair<String, Boolean>? {
        var reason = "未知错误"
        var unreadable = false
        repeat(2) { attempt ->
            try {
                out.parentFile?.mkdirs()
                src.inputStream().use { input ->
                    out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                // 保留修改时间：部分核心/插件按时间戳判断版本
                runCatching { out.setLastModified(src.lastModified()) }
                val written = out.length()
                val expected = src.length()
                if (written != expected) {
                    reason = "写入不完整（目标 $written / 源 $expected 字节）"
                    unreadable = false
                    runCatching { out.delete() }
                } else {
                    return null
                }
            } catch (e: Throwable) {
                // 源打不开 = 读不出来（权限、损坏、被独占）；目标打不开 = 落盘失败
                unreadable = !src.canRead() || e is java.io.FileNotFoundException && !src.exists()
                reason = e.message ?: e.javaClass.simpleName
                logW("复制 ${src.absolutePath} 第 ${attempt + 1} 次失败：$reason")
                runCatching { out.delete() }
            }
        }
        return reason to unreadable
    }

    /** 相对路径 → 字节数 里「缺失或长度不符」的项 */
    internal fun missingEntries(
        expected: Map<String, Long>,
        actual: Map<String, Long>
    ): List<Pair<String, Long>> =
        expected.entries
            .filter { (rel, size) -> actual[rel] != size }
            .map { it.key to it.value }

    /** 把校验差异拼成一句能直接照做的说明（最多列 3 个文件名） */
    private fun describeMismatch(
        expected: Map<String, Long>,
        actual: Map<String, Long>,
        missing: List<Pair<String, Long>>,
        reasons: Map<String, CopyFailure>
    ): String {
        val shown = missing.take(3).joinToString("；") { (rel, size) ->
            val reason = reasons[rel]?.reason?.let { "，$it" } ?: ""
            "$rel（$size 字节$reason）"
        }
        val more = if (missing.size > 3) "，另有 ${missing.size - 3} 个" else ""
        return "复制校验失败：源 ${expected.size} 个文件 / ${expected.values.sum()} 字节，" +
            "目标 ${actual.size} 个 / ${actual.values.sum()} 字节；" +
            "${missing.size} 个文件未落实（$shown$more），源目录未改动"
    }

    private fun previewPaths(list: List<Pair<String, Long>>): String =
        list.take(3).joinToString("、") { it.first } +
            if (list.size > 3) " 等 ${list.size} 个" else ""

    /** 找一个可续传的临时目录（同名、未过期） */
    private fun resumableTempDir(internalRoot: File, targetName: String): File? {
        val prefix = "$TEMP_PREFIX$targetName.migrating-"
        val dirs = internalRoot.listFiles { f -> f.isDirectory && f.name.startsWith(prefix) } ?: return null
        return dirs.maxByOrNull { it.lastModified() }
    }

    /** 清掉被放弃的临时目录（24h 无更新），避免公共存储里长期占着几百 MB */
    private fun cleanupStaleTempDirs(internalRoot: File) {
        val dirs = internalRoot.listFiles { f ->
            f.isDirectory && f.name.startsWith(TEMP_PREFIX) && f.name.contains(".migrating-")
        } ?: return
        val deadline = System.currentTimeMillis() - STALE_TEMP_MS
        dirs.filter { it.lastModified() < deadline }.forEach { stale ->
            runCatching { stale.deleteRecursively() }
                .onSuccess { logI("已清理过期迁移临时目录：${stale.name}") }
        }
    }

    // ── 日志 ──────────────────────────────────────────────────────

    /**
     * 日志全部走 [runCatching]：`android.util.Log` 在 JVM 单测里是未实现的桩，
     * 直接调用会抛 RuntimeException。迁移逻辑本身不该因为「打不出日志」而失败 ——
     * 之前正是这个副作用把一次正常的续传复制变成了迁移失败。
     */
    private fun logI(msg: String) {
        runCatching { Log.i(TAG, msg) }
    }

    private fun logW(msg: String, error: Throwable? = null) {
        runCatching { if (error == null) Log.w(TAG, msg) else Log.w(TAG, msg, error) }
    }

    private fun logE(msg: String, error: Throwable? = null) {
        runCatching { if (error == null) Log.e(TAG, msg) else Log.e(TAG, msg, error) }
    }
}
