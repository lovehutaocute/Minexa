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
 *  1. 先在**同一文件系统**下的临时目录 `{dirName}.migrating-{ts}` 里完整复制；
 *  2. 逐项比对文件数与总字节数，不一致即判定失败（源目录原封不动）；
 *  3. 校验通过才 `rename` 到最终目录名（同根内 rename 是原子的）；
 *  4. **最后**删除源目录；任何一步失败都保留源目录并回报原因。
 */
object LegacyServerMigration {

    private const val TAG = "LegacyServerMigration"

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
        val error: String? = null
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
     * @param onProgress (已完成数, 总数, 当前目录名)
     */
    suspend fun migrateAll(
        context: Context,
        candidates: List<Candidate>,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): List<Outcome> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptyList()
        // 目标目录名规划要看到「本次已占用的名字」，否则两个旧目录搬进来会撞名
        val taken = ServerStorage.takenDirNames(context).toMutableSet()
        val outcomes = mutableListOf<Outcome>()
        candidates.forEachIndexed { index, candidate ->
            onProgress(index, candidates.size, candidate.dirName)
            val outcome = migrateOne(
                source = candidate.source,
                internalRoot = ServerStorage.internalRoot(context),
                taken = taken
            )
            if (outcome.success && outcome.targetDirName != null) taken += outcome.targetDirName
            outcomes += outcome
        }
        onProgress(candidates.size, candidates.size, "")
        // 全部搬完后，若旧根目录已经空了就一并清掉 —— 留着只会让用户以为
        // 「这里还有东西」，而且下次启动扫描还得再走一遍。
        if (outcomes.all { it.success }) {
            ServerStorage.legacyRootCandidates(context).forEach { root ->
                val empty = root.isDirectory && (root.listFiles()?.isEmpty() != false)
                if (empty) {
                    runCatching { root.deleteRecursively() }
                    Log.i(TAG, "旧版目录已清空并删除：${root.absolutePath}")
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
    fun migrateOne(source: File, internalRoot: File, taken: MutableSet<String>): Outcome {
        val dirName = source.name
        if (!source.isDirectory) {
            return Outcome(dirName, null, false, error = "源目录不存在")
        }
        val targetName = planTargetDirName(dirName, internalRoot, taken)
        val finalDir = File(internalRoot, targetName)
        val tempDir = File(internalRoot, "$targetName.migrating-${System.currentTimeMillis()}")
        return try {
            if (!internalRoot.isDirectory && !internalRoot.mkdirs()) {
                return Outcome(dirName, null, false, error = "无法创建统一存储目录")
            }
            // 目标位置若只有一个空壳（之前错误版本留下的幽灵目录），先清掉，避免污染校验
            if (finalDir.isDirectory && ServerStorage.isEmptyDirectory(finalDir)) {
                finalDir.deleteRecursively()
            }
            tempDir.mkdirs()
            val expected = countFiles(source)
            copyTree(source, tempDir)
            val actual = countFiles(tempDir)
            if (expected != actual) {
                tempDir.deleteRecursively()
                return Outcome(
                    dirName, null, false,
                    error = "复制校验失败（源 ${expected.first} 个文件 / ${expected.second} 字节，" +
                        "目标 ${actual.first} 个文件 / ${actual.second} 字节），源目录未改动"
                )
            }
            if (!tempDir.renameTo(finalDir)) {
                tempDir.deleteRecursively()
                return Outcome(dirName, null, false, error = "无法把迁移结果重命名为 ${finalDir.name}，源目录未改动")
            }
            // 落地成功后才删源：中途崩溃最多留下一个 .migrating 目录，源永远在
            val removed = source.deleteRecursively()
            if (!removed) {
                Log.w(TAG, "迁移完成但源目录删除失败：${source.absolutePath}")
            }
            Outcome(dirName, targetName, true, fileCount = actual.first)
        } catch (e: Exception) {
            runCatching { tempDir.deleteRecursively() }
            Log.e(TAG, "迁移 $dirName 失败：${e.message}", e)
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
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                count++
                bytes += f.length()
            }
        }
        return count to bytes
    }

    internal fun copyTree(source: File, target: File): Boolean = try {
        target.mkdirs()
        source.walkTopDown().forEach { file ->
            val rel = file.relativeTo(source).path
            val out = File(target, rel)
            if (file.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                file.inputStream().use { input ->
                    out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                // 保留修改时间：部分核心/插件按时间戳判断版本
                out.setLastModified(file.lastModified())
            }
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "复制失败：${e.message}", e)
        false
    }
}
