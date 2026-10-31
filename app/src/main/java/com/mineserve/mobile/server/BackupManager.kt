package com.mineserve.mobile.server

import android.util.Log
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 备份管理器：列出 / 创建 / 恢复 / 删除世界快照。
 *
 * 快照存放于 /home/snapshots/world_yyyyMMdd_HHmmss.zip，
 * 世界目录位于 /home/servers/{dirName}/world/。
 *
 * 恢复流程：停止服务器 → 备份当前 world（重命名为 world.bak.<ts>）
 *           → 删除 world → 解压快照到 world 目录。
 */
class BackupManager(private val termux: TermuxRuntime) {

    enum class BackupKind { World, Server }
    enum class BackupOrigin(val label: String) { Manual("手动"), Automatic("自动") }

    data class ExternalBackupInfo(
        val kind: BackupKind,
        val origin: BackupOrigin?,
        val dirName: String?,
        val coreTag: String?,
        val createdTime: Long
    )

    /** 快照信息数据类 */
    data class SnapshotInfo(
        val name: String,           // 文件名（不含路径）
        val path: String,           // 完整路径
        val sizeBytes: Long,        // 文件大小
        val sizeText: String,       // 友好显示（如 "12.3 MB"）
        val createdTime: Long,      // 时间戳
        val createdText: String     // 友好显示（如 "2026-07-28 15:30"）
    )

    private val snapshotDir: File get() = File(termux.installer.rootDir, "home/snapshots")

    private val TAG = "BackupManager"

    private fun worldDir(dirName: String): File =
        File(termux.serverDirFor(dirName), "world")

    /** 列出所有快照，按时间倒序（最新在前） */
    fun listSnapshots(): List<SnapshotInfo> {
        val dir = snapshotDir.apply { mkdirs() }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?: return emptyList()
        val parser = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return files.map { f ->
            val (ts, tsText) = parseSnapshotTime(f.name, parser)
            SnapshotInfo(
                name = f.name,
                path = f.absolutePath,
                sizeBytes = f.length(),
                sizeText = formatSize(f.length()),
                createdTime = ts,
                createdText = tsText
            )
        }.sortedByDescending { it.createdTime }
    }

    /** 从文件名 world_yyyyMMdd_HHmmss.zip 解析时间戳与友好文本 */
    private fun parseSnapshotTime(
        fileName: String,
        parser: SimpleDateFormat
    ): Pair<Long, String> {
        val base = fileName.removeSuffix(".zip")
        val prefix = "world_"
        if (!base.startsWith(prefix)) return 0L to "--"
        val tsStr = base.substring(prefix.length)
        return try {
            val date = parser.parse(tsStr) ?: return 0L to "--"
            val outFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            date.time to outFmt.format(date)
        } catch (e: Exception) {
            0L to "--"
        }
    }

    /** 新建世界备份统一写入公共备份目录；私有快照目录仅用于兼容旧文件。 */
    fun createSnapshot(dirName: String): String? = backupWorldToExternal(dirName, BackupOrigin.Manual)

    // ── 外部备份（/storage/emulated/0/世界与服务器的备份/） ─────────────

    private fun serverDir(dirName: String): File =
        termux.serverDirFor(dirName)

    private fun ts(): String = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    private fun safeSegment(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().ifBlank { "unknown" }

    private fun backupName(
        kind: BackupKind,
        dirName: String,
        coreTag: String? = null,
        origin: BackupOrigin
    ): String {
        val prefix = if (kind == BackupKind.World) "world" else "server"
        val fields = mutableListOf(prefix, safeSegment(dirName), ts())
        if (kind == BackupKind.Server) fields += safeSegment(coreTag ?: "Unknown")
        fields += origin.label
        return fields.joinToString("_") + ".zip"
    }

    /** 打包目录列表到 zip（保留顶层目录名） */
    private fun zipDirs(dirs: List<File>, out: File): Boolean = try {
        java.util.zip.ZipOutputStream(FileOutputStream(out)).use { zos ->
            dirs.forEach { dir ->
                if (dir.isDirectory) {
                    dir.walkTopDown().forEach { f ->
                        val rel = f.relativeTo(dir)
                        if (f.isDirectory) {
                            zos.putNextEntry(java.util.zip.ZipEntry(dir.name + "/" + rel.path + "/"))
                            zos.closeEntry()
                        } else {
                            zos.putNextEntry(java.util.zip.ZipEntry(dir.name + "/" + rel.path))
                            FileInputStream(f).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
        }
        out.exists() && out.length() > 0
    } catch (e: Exception) { out.delete(); false }

    /** 打包整个目录到 zip（排除 logs/ 与 session.lock） */
    private fun zipDirFiltered(dir: File, out: File): Boolean = try {
        java.util.zip.ZipOutputStream(FileOutputStream(out)).use { zos ->
            dir.walkTopDown().forEach { f ->
                val rel = f.relativeTo(dir)
                val relStr = rel.path.replace('\\', '/')
                if (relStr == "logs" || relStr.startsWith("logs/") ||
                    relStr == "server.lock" || relStr == "session.lock" ||
                    relStr.endsWith("/server.lock") || relStr.endsWith("/session.lock")) return@forEach
                if (f.isDirectory) {
                    zos.putNextEntry(java.util.zip.ZipEntry(relStr + "/"))
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(java.util.zip.ZipEntry(relStr))
                    FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        out.exists() && out.length() > 0
    } catch (e: Exception) { out.delete(); false }

    /** 外部备份整个世界（world + world_nether + world_the_end）。 */
    fun backupWorldToExternal(
        dirName: String,
        origin: BackupOrigin = BackupOrigin.Manual,
        maxAutomaticBackups: Int = 0
    ): String? {
        if (!ExternalBackupStore.ensure()) return null
        val dir = serverDir(dirName)
        val worlds = if (PowerNukkitXLayout.isPowerNukkitX(dir)) {
            PowerNukkitXLayout.worldDirectories(dir)
        } else {
            listOf(File(dir, "world"), File(dir, "world_nether"), File(dir, "world_the_end"))
        }
        if (!worlds.any { it.isDirectory }) return null
        val out = File(ExternalBackupStore.rootDir, backupName(BackupKind.World, dirName, origin = origin))
        return if (zipDirs(worlds, out)) {
            cleanupAutomaticBackups(dirName, BackupKind.World, maxAutomaticBackups, origin)
            out.absolutePath
        } else null
    }

    /**
     * 外部备份整个服务器目录（world + 核心 jar + 配置 + 插件，排除 logs/session.lock）。
     * 文件名 server_{dir}_{ts}__{core}.zip（__ 后置核心类型，便于还原时识别核心；
     * 旧格式无 __ 段兼容）。
     */
    fun backupServerToExternal(
        dirName: String,
        coreTag: String? = null,
        origin: BackupOrigin = BackupOrigin.Manual,
        maxAutomaticBackups: Int = 0
    ): String? {
        if (!ExternalBackupStore.ensure()) return null
        val dir = serverDir(dirName)
        if (!dir.isDirectory) return null
        val out = File(ExternalBackupStore.rootDir, backupName(BackupKind.Server, dirName, coreTag, origin))
        return if (zipDirFiltered(dir, out)) {
            cleanupAutomaticBackups(dirName, BackupKind.Server, maxAutomaticBackups, origin)
            out.absolutePath
        } else null
    }

    /**
     * BDS 更新前的专用备份：只打包**用户数据面**，不含 265MB 的 `bedrock_server`。
     *
     * 为什么不复用 [backupServerToExternal]：那个会把整个服务器目录（含核心本体）
     * 压进 zip，BDS 的核心是 265MB 单文件，压完仍有近百兆 —— 每次升级都多一份，
     * 而升级流程本身已经把旧核心保留成 `bedrock_server.old-<版本>`，核心不需要再备份一份。
     *
     * 备份范围（更新会碰到的都在内）：
     *  - `worlds/`：基岩版世界存档（官方安装包里**没有**这一项，但必须备份，
     *    否则将来官方一旦带上就会出事）；
     *  - `server.properties` / `allowlist.json` / `permissions.json`：
     *    玩家设置、白名单、权限；
     *  - `behavior_packs/` / `resource_packs/` / `config/`：用户装的行为包、
     *    资源包与改过的引擎配置（官方包里也有同名项，更新会覆盖）。
     *
     * 条目名一律写成**相对服务器目录的路径**（`worlds/xxx`、`server.properties`），
     * 这样还原时直接按同名路径解压回去即可，不需要额外映射。
     *
     * @return 备份 zip 的绝对路径；目录不存在或打包失败返回 null（调用方必须中止更新）
     */
    fun backupBdsBeforeUpdate(dirName: String, fromVersion: String?): String? {
        val dir = serverDir(dirName)
        if (!dir.isDirectory) return null
        // coreTag 里带 bds-update 前缀：备份列表里一眼能看出这份是升级前自动生成的，
        // 也让它与「整服备份」共用同一套文件名解析（parseServerZipInfo 按 __ 分段）。
        val tag = "bds-update-" + (fromVersion?.takeIf { it.isNotBlank() } ?: "unknown")
        val name = backupName(BackupKind.Server, dirName, tag, BackupOrigin.Automatic)
        // 没有「所有文件访问」权限时外部备份目录不可写：退回应用私有目录，
        // 而不是让「更新核心」这个功能直接不可用（备份存在私有目录同样能救回数据，
        // 完整路径会写进日志）。
        val out = if (ExternalBackupStore.ensure()) {
            File(ExternalBackupStore.rootDir, name)
        } else {
            File(termux.installer.rootDir, "bds-backup").apply { mkdirs() }.let { File(it, name) }
        }
        return if (zipBdsUserData(dir, out)) out.absolutePath else null
    }

    /** 更新前备份的内容清单：目录按整棵子树打包，文件按单文件打包 */
    private val BDS_UPDATE_BACKUP_DIRS = listOf("worlds", "behavior_packs", "resource_packs", "config")

    private val BDS_UPDATE_BACKUP_FILES = listOf(
        "server.properties",
        "allowlist.json",
        "permissions.json",
        "valid_known_packs.json"
    )

    private fun zipBdsUserData(dir: File, out: File): Boolean = try {
        java.util.zip.ZipOutputStream(FileOutputStream(out)).use { zos ->
            BDS_UPDATE_BACKUP_DIRS.forEach { name ->
                val root = File(dir, name)
                if (!root.isDirectory) return@forEach
                root.walkTopDown().forEach { f ->
                    val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                    if (f.isDirectory) {
                        zos.putNextEntry(java.util.zip.ZipEntry("$rel/"))
                        zos.closeEntry()
                    } else {
                        zos.putNextEntry(java.util.zip.ZipEntry(rel))
                        FileInputStream(f).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            BDS_UPDATE_BACKUP_FILES.forEach { name ->
                val f = File(dir, name)
                if (!f.isFile) return@forEach
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                FileInputStream(f).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        out.exists() && out.length() > 0
    } catch (e: Exception) {
        Log.w(TAG, "BDS 更新前备份失败: ${e.message}")
        out.delete()
        false
    }

    /** 停止当前运行中的服务器（若在运行） */
    private suspend fun stopServerIfRunning() {
        if (termux.isMcRunning()) {
            termux.sendCommand("stop")
            var waited = 0
            while (termux.isMcRunning() && waited < 10_000) { delay(500); waited += 500 }
            if (termux.isMcRunning()) termux.stopMc()
        }
    }

    /** 从外部 zip 还原世界（zip 内 world/world_nether/world_the_end 前缀） */
    suspend fun restoreWorldFromExternal(file: File, dirName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext false
            // 与 restoreServerFromExternal / restoreSnapshot 保持一致：先在原世界未动的前提下整包校验。
            // 缺了这一步，坏 zip 或半截下载会先把旧世界挪走甚至删掉，再解压失败——
            // 用户看到「还原失败」，数据却已经没了。
            if (!validateZipFile(file)) {
                Log.w(TAG, "restoreWorldFromExternal: ${file.name} 校验未通过，已拒绝还原（原世界未改动）")
                return@withContext false
            }
            stopServerIfRunning()
            val serverDir = serverDir(dirName)
            val dims = if (PowerNukkitXLayout.isPowerNukkitX(serverDir)) {
                listOf(PowerNukkitXLayout.worldsDirectory)
            } else listOf("world", "world_nether", "world_the_end")
            // 备份旧 world。rename 失败时**不能**直接 deleteRecursively：
            // 那等于在没能保留副本的情况下主动毁掉用户唯一的世界。
            // 退化为「复制成 .bak 再删」，复制也不成功就中止还原，原世界保持原样。
            val backedUp = mutableListOf<Pair<File, File>>()
            for (dim in dims) {
                val d = File(serverDir, dim)
                if (!d.exists()) continue
                val bak = File(serverDir, "$dim.bak.${ts()}")
                if (d.renameTo(bak)) {
                    backedUp += d to bak
                } else if (d.copyRecursively(bak, overwrite = true)) {
                    if (!d.deleteRecursively()) {
                        Log.w(TAG, "restoreWorldFromExternal: $dim 已复制备份但原目录删除失败，继续还原")
                    }
                } else {
                    Log.e(TAG, "restoreWorldFromExternal: 无法备份 $dim，已中止还原（原世界保持不变）")
                    return@withContext false
                }
            }
            val extract = runCatching { extractZipToDir(file, serverDir) }
            val hasWorld = dims.any { dim ->
                val d = File(serverDir, dim)
                d.isDirectory && d.listFiles()?.isNotEmpty() == true
            }
            if (extract.isFailure || !hasWorld) {
                // 解压失败、或解出来的根本不是世界：回滚到备份，不留空壳服务端目录。
                Log.e(TAG, "restoreWorldFromExternal: 解压失败或内容不是世界，正在回滚", extract.exceptionOrNull())
                dims.forEach { File(serverDir, it).deleteRecursively() }
                backedUp.forEach { (original, bak) -> bak.renameTo(original) }
                return@withContext false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "restoreWorldFromExternal: ${e.message}", e)
            false
        }
    }

    /**
     * 从外部 zip 还原整个服务器（zip 内为服务器目录相对路径）。
     * @param overwrite true 时覆盖同名目录，false 时目标存在则失败（用于重名检测）
     *
     * 覆盖时的顺序是「先校验 → 解压到暂存目录 → 再换入」，**全程不先删原目录**：
     *
     * 旧实现是 `target.deleteRecursively()` 之后直接对着 target 解压。zip 损坏、
     * 半截下载、解压中途异常，任何一种都会让原目录已经不存在、也无法回滚，
     * 用户只看到一句「还原失败」——数据实际已经没了。这个是不可逆的，
     * 而 zip 恰恰是「本来就可能有问题」的东西（用户从别处拷来的）。
     */
    suspend fun restoreServerFromExternal(file: File, targetDirName: String, overwrite: Boolean): Boolean = withContext(Dispatchers.IO) {
        // 暂存目录无论成败都要清掉，别在 rootDir/tmp 里留垃圾。
        var staging: File? = null
        try {
            if (!file.exists()) return@withContext false

            // ① 先整包校验（全条目 CRC 读一遍）。这一步在动原目录之前，失败就原样返回。
            if (!validateZipFile(file)) {
                Log.w(TAG, "restoreServerFromExternal: ${file.name} 校验未通过，已拒绝还原（原目录未改动）")
                return@withContext false
            }

            stopServerIfRunning()

            val target = serverDir(targetDirName)
            if (target.exists() && !overwrite) return@withContext false

            // ② 解压到独立暂存目录。放在 rootDir/tmp 下而不是 servers/ 旁边——
            //    servers/ 的子目录会被当作服务器实例全量列出来（McViewModel 按 isDirectory 枚举）。
            val stageDir = File(termux.installer.rootDir, "tmp/restore-${System.currentTimeMillis()}")
            staging = stageDir
            stageDir.mkdirs()
            try {
                extractZipToDir(file, stageDir)
            } catch (e: Exception) {
                Log.e(TAG, "restoreServerFromExternal: 解压失败: ${e.message}", e)
                return@withContext false // 原目录一步都没动过
            }

            // ③ 换入。先把旧目录改名暂离（同分区 rename），失败就整体放弃。
            var rollback: File? = null
            if (target.exists()) {
                val old = File(termux.installer.rootDir, "tmp/restore-old-${System.currentTimeMillis()}")
                if (!target.renameTo(old)) {
                    Log.e(TAG, "restoreServerFromExternal: 无法暂离 ${target.name}，已中止覆盖（原目录未改动）")
                    return@withContext false
                }
                rollback = old
            }
            if (!stageDir.renameTo(target)) {
                Log.e(TAG, "restoreServerFromExternal: 换入失败，正在回滚")
                rollback?.let { if (it.renameTo(target)) Log.i(TAG, "已回滚到还原前状态") }
                return@withContext false
            }
            rollback?.deleteRecursively()
            true
        } catch (e: Exception) {
            Log.e(TAG, "restoreServerFromExternal failed: ${e.message}", e)
            false
        } finally {
            runCatching { staging?.deleteRecursively() }
        }
    }

    /** 解析新旧外部备份命名；无法识别的导入 ZIP 延续旧行为，按服务器备份处理。 */
    fun parseExternalBackup(name: String, modifiedTime: Long = 0L): ExternalBackupInfo {
        val base = name.removeSuffix(".zip")
        val newMatch = NEW_BACKUP.matchEntire(base)
        if (newMatch != null) {
            val kind = if (newMatch.groupValues[1] == "world") BackupKind.World else BackupKind.Server
            val timestamp = parseTime(newMatch.groupValues[3], NEW_TIME) ?: modifiedTime
            return ExternalBackupInfo(
                kind = kind,
                dirName = newMatch.groupValues[2],
                coreTag = newMatch.groupValues[4].ifBlank { null },
                origin = if (newMatch.groupValues[5] == BackupOrigin.Automatic.label) BackupOrigin.Automatic else BackupOrigin.Manual,
                createdTime = timestamp
            )
        }

        val legacyWorld = LEGACY_WORLD.matchEntire(base)
        if (legacyWorld != null) {
            return ExternalBackupInfo(
                kind = BackupKind.World,
                origin = null,
                dirName = legacyWorld.groupValues[1].ifBlank { null },
                coreTag = null,
                createdTime = parseTime(legacyWorld.groupValues[2], LEGACY_TIME) ?: modifiedTime
            )
        }

        val legacyServer = LEGACY_SERVER.matchEntire(base)
        if (legacyServer != null) {
            return ExternalBackupInfo(
                kind = BackupKind.Server,
                origin = null,
                dirName = legacyServer.groupValues[1].ifBlank { "default" },
                coreTag = legacyServer.groupValues[3].ifBlank { null },
                createdTime = parseTime(legacyServer.groupValues[2], LEGACY_TIME) ?: modifiedTime
            )
        }
        return ExternalBackupInfo(BackupKind.Server, null, "default", null, modifiedTime)
    }

    /** 兼容新旧服务器命名，目录名含下划线时通过时间字段定位，不再按 split 推测。 */
    fun parseServerZipInfo(name: String): Pair<String, String?> {
        val info = parseExternalBackup(name)
        return (info.dirName ?: "default") to info.coreTag
    }

    private fun cleanupAutomaticBackups(
        dirName: String,
        kind: BackupKind,
        keepCount: Int,
        origin: BackupOrigin
    ) {
        if (origin != BackupOrigin.Automatic || keepCount <= 0) return
        val safeDir = safeSegment(dirName)
        val files = ExternalBackupStore.listBackups().map { file -> file to parseExternalBackup(file.name, file.lastModified()) }
            .filter { (_, info) ->
                info.kind == kind && info.origin == BackupOrigin.Automatic && info.dirName == safeDir
            }
            .sortedByDescending { (_, info) -> info.createdTime }
        files.drop(keepCount).forEach { (file, _) -> file.delete() }
    }

    /** 删除外部备份文件 */
    fun deleteExternalBackup(name: String): Boolean {
        val f = File(ExternalBackupStore.rootDir, name)
        return f.exists() && f.isFile && f.delete()
    }

    /** 重命名外部备份文件（仅改名，须保留 .zip 后缀；目标重名时返回 false） */
    fun renameExternalBackup(oldName: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isBlank() || !trimmed.endsWith(".zip", ignoreCase = true)) return false
        val safeNew = safeSegment(trimmed)
        val src = File(ExternalBackupStore.rootDir, oldName)
        val dst = File(ExternalBackupStore.rootDir, safeNew)
        if (!src.exists() || !src.isFile || dst.exists()) return false
        return src.renameTo(dst)
    }

    /** 校验 zip 完整性：遍历全部条目并读取数据（触发 CRC 校验），损坏返回 false */
    fun validateZip(name: String): Boolean =
        validateZipFile(File(ExternalBackupStore.rootDir, name))

    /**
     * 同 [validateZip]，但直接针对文件对象。
     *
     * 还原流程需要在**动原目录之前**校验来源 zip，而那时手上只有 `File`（可能来自 SAF 临时拷贝，
     * 不一定在 ExternalBackupStore.rootDir 里）。
     */
    fun validateZipFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        zip.getInputStream(entry).use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (input.read(buf) >= 0) {
                                // 读取以触发 CRC 校验
                            }
                        }
                    }
                    count++
                    if (count > 200_000) break // 防御性上限
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 恢复快照：
     * 1. 若 MC 在运行，发送 stop 命令并轮询等待退出（最多 10s），超时则强制停止
     * 2. 备份当前 world 目录（重命名为 world.bak.<timestamp>）
     * 3. 删除 world 目录
     * 4. 解压快照到 world 目录
     */
    suspend fun restoreSnapshot(snapshotName: String, dirName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val snapshotFile = File(snapshotDir, snapshotName)
            if (!snapshotFile.exists() || !snapshotFile.isFile) return@withContext false
            // 还原前先校验整包可读性：失败必须在这里就返回，因为下一步就是把当前世界删掉。
            // 否则后面的 deleteRecursively 已经把当前世界清了，失败就变成永久数据丢失。
            if (!isValidZip(snapshotFile)) {
                Log.e(TAG, "restoreSnapshot: 快照不是完整 zip，已中止还原: $snapshotName")
                return@withContext false
            }

            // 1. 停止服务器（如果在运行）
            if (termux.isMcRunning()) {
                termux.sendCommand("stop")
                var waited = 0
                while (termux.isMcRunning() && waited < 10_000) {
                    delay(500)
                    waited += 500
                }
                // 超时仍未退出，强制停止
                if (termux.isMcRunning()) {
                    termux.stopMc()
                }
            }

            val serverDir = worldDir(dirName).parentFile ?: return@withContext false
            val dims = if (PowerNukkitXLayout.isPowerNukkitX(serverDir)) {
                listOf(PowerNukkitXLayout.worldsDirectory)
            } else listOf("world", "world_nether", "world_the_end")

            // 2. 备份当前三维度目录（重命名为 *.bak.<timestamp>）
            dims.forEach { dim ->
                val dimDir = File(serverDir, dim)
                if (dimDir.exists()) {
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val backup = File(serverDir, "$dim.bak.$ts")
                    if (!dimDir.renameTo(backup)) {
                        // 重命名失败，直接递归删除
                        dimDir.deleteRecursively()
                    }
                }
            }

            // 3. 确保已删除（重命名成功后原目录不存在）
            dims.forEach { dim ->
                val dimDir = File(serverDir, dim)
                if (dimDir.exists()) dimDir.deleteRecursively()
            }

            // 4. 解压快照到 serverDir（zip 条目带 world/ 前缀，恢复三维度目录）
            extractZipToDir(snapshotFile, serverDir)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 压缩包是否可完整读取。
     *
     * 用 [ZipFile] 走中央目录：比逐个条目解压更快，而且能检出「尾部中央目录缺失」
     * 这种典型截断。zip-slip 类的单条目多数 ballistic 情况仍要靠 [extractZipToDir] 的路径校验。
     */
    private fun isValidZip(file: File): Boolean = try {
        java.util.zip.ZipFile(file).use { zip ->
            zip.entries().toList()
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "invalid zip ${file.name}: ${e.message}")
        false
    }

    /** 删除快照文件 */
    fun deleteSnapshot(snapshotName: String): Boolean {
        val file = File(snapshotDir, snapshotName)
        return file.exists() && file.isFile && file.delete()
    }

    /**
     * 解压 zip 到目标目录。
     * 含 zip-slip 防护（参考 BootstrapInstaller.extractZipToDir）：
     * 校验每个条目的 canonical 路径必须以 destDir 的 canonical 路径为前缀，
     * 防止恶意 zip 通过 ../ 逃逸出目标目录。
     */
    private fun extractZipToDir(zipFile: File, destDir: File) {
        destDir.mkdirs()
        FileInputStream(zipFile).buffered().use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.removePrefix("./")
                    val outFile = File(destDir, entryName)

                    // zip-slip / path traversal 防护
                    val canonicalDest = destDir.canonicalPath + File.separator
                    val canonicalEntry = outFile.canonicalPath
                    if (!canonicalEntry.startsWith(canonicalDest)) {
                        entry = zis.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        // 若路径上已有同名文件，先删除
                        if (outFile.exists() && !outFile.isDirectory) outFile.delete()
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun parseTime(value: String, format: String): Long? = try {
        SimpleDateFormat(format, Locale.US).apply { isLenient = false }.parse(value)?.time
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val TAG = "BackupManager"
        const val NEW_TIME = "yyyy-MM-dd_HH-mm-ss"
        const val LEGACY_TIME = "yyyyMMdd_HHmmss"
        val NEW_BACKUP = Regex("^(world|server)_(.+)_(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})(?:_(.*))?_(手动|自动)$")
        val LEGACY_WORLD = Regex("^world_(?:(.+)_)?(\\d{8}_\\d{6})$")
        val LEGACY_SERVER = Regex("^server_(.+)_(\\d{8}_\\d{6})(?:__(.*))?$")
    }

    /** 格式化文件大小为 KB/MB/GB 友好文本 */
    private fun formatSize(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }
}