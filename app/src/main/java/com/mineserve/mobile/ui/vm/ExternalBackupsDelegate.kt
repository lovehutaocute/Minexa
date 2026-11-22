package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.StorageLocation
import com.mineserve.mobile.server.BackupManager
import com.mineserve.mobile.ui.McViewModel.RestoreConflict
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ExternalBackupsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val backupManager: BackupManager,
    private val config: StateFlow<McConfig>,
    private val activeDirName: () -> String?,
    private val serversDir: () -> File,
    private val updateConfig: ((McConfig) -> McConfig) -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    /** 创建 world 目录快照（zip 打包），返回快照文件路径或 null */
    suspend fun createSnapshot(): String? {
        val dirName = activeDirName() ?: return null
        return withContext(Dispatchers.IO) {
            backupManager.backupWorldToExternal(dirName, BackupManager.BackupOrigin.Manual)
        }
    }

    /** 更新前备份存档（更新对话框「先备份存档」入口） */
    fun backupBeforeUpdate() {
        scope.launch {
            try {
                val path = createSnapshot()
                if (path != null) {
                    reportMessage(str(R.string.update_backup_done, java.io.File(path).name))
                } else {
                    reportMessage(str(R.string.update_backup_empty))
                }
            } catch (e: Exception) {
                reportMessage(str(R.string.err_backup_fail, e.message))
            }
        }
    }

    /** 外部备份整个世界（world+nether+end → 外部目录） */
    fun backupWorldToExternal() {
        val dirName = activeDirName() ?: run { reportError(str(R.string.s212)); return }
        scope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    backupManager.backupWorldToExternal(dirName, BackupManager.BackupOrigin.Manual)
                }
                if (path != null) {
                    reportMessage(str(R.string.msg_world_backup_done, java.io.File(path).name))
                } else {
                    reportError(str(R.string.err_world_backup_empty))
                }
            } catch (e: Exception) {
                reportError(str(R.string.err_world_backup_fail, e.message))
            }
        }
    }

    /** 外部备份整个服务器（world+核心+配置+插件 → 外部目录，文件名带核心类型） */
    fun backupServerToExternal() {
        val dirName = activeDirName() ?: run { reportError(str(R.string.s212)); return }
        // 当前服务器核心类型写入文件名（还原时据此识别核心）
        val coreTag = config.value.installedCores
            .firstOrNull { it.dirName == dirName }?.core?.displayName
        scope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    backupManager.backupServerToExternal(dirName, coreTag, BackupManager.BackupOrigin.Manual)
                }
                if (path != null) {
                    reportMessage(str(R.string.msg_server_backup_done, java.io.File(path).name))
                } else {
                    reportError(str(R.string.err_server_backup_fail))
                }
            } catch (e: Exception) {
                reportError(str(R.string.err_server_backup_fail_msg, e.message))
            }
        }
    }

    /** 列出外部备份 zip（供备份页展示） */
    fun externalBackups(): List<java.io.File> = com.mineserve.mobile.server.ExternalBackupStore.listBackups()

    fun externalBackupInfo(file: java.io.File): BackupManager.ExternalBackupInfo =
        backupManager.parseExternalBackup(file.name, file.lastModified())

    /** 删除外部备份文件 */
    fun deleteExternalBackup(name: String) {
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    backupManager.deleteExternalBackup(name)
                }
                if (ok) reportMessage(str(R.string.msg_backup_deleted, name))
                else reportError(str(R.string.err_backup_delete_fail, name))
            } catch (e: Exception) {
                reportError(str(R.string.err_backup_delete_fail, e.message))
            }
        }
    }

    /** 从手机其他位置导入备份 zip 到外部备份目录（SAF 选择） */
    fun importBackupToExternal(uri: android.net.Uri) {        scope.launch {
            try {
                if (!com.mineserve.mobile.server.ExternalBackupStore.ensure()) {
                    reportError(str(R.string.err_ext_dir_unavailable))
                    return@launch
                }
                val fileName = withContext(Dispatchers.IO) {
                    app.contentResolver.query(
                        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "backup_${System.currentTimeMillis()}.zip"
                }
                val safeName = fileName?.takeIf { it.endsWith(".zip") } ?: "backup_${System.currentTimeMillis()}.zip"
                val target = java.io.File(com.mineserve.mobile.server.ExternalBackupStore.rootDir, safeName)
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                reportMessage(str(R.string.msg_backup_imported, safeName))
            } catch (e: Exception) {
                reportError(str(R.string.err_backup_import_fail, e.message))
            }
        }
    }

    /** 重命名外部备份文件 */
    fun renameExternalBackup(oldName: String, newName: String) {
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.renameExternalBackup(oldName, newName) }
                if (ok) reportMessage(str(R.string.bk_rename_done, newName))
                else reportError(str(R.string.bk_rename_fail))
            } catch (e: Exception) {
                reportError(str(R.string.bk_rename_fail))
            }
        }
    }

    /** 校验外部备份 zip 完整性 */
    fun validateExternalBackup(name: String) {
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.validateZip(name) }
                if (ok) reportMessage(str(R.string.bk_validate_done, name))
                else reportError(str(R.string.bk_validate_fail, name))
            } catch (e: Exception) {
                reportError(str(R.string.bk_validate_fail, name))
            }
        }
    }

    private val _restoreConflict = kotlinx.coroutines.flow.MutableStateFlow<RestoreConflict?>(null)
    val restoreConflict: kotlinx.coroutines.flow.StateFlow<RestoreConflict?> = _restoreConflict.asStateFlow()

    /** 从外部 zip 还原世界（zip 内 world/ 前缀） */
    fun restoreWorldFromExternal(zipName: String) {
        val dirName = activeDirName() ?: run { reportError(str(R.string.s212)); return }
        val file = java.io.File(com.mineserve.mobile.server.ExternalBackupStore.rootDir, zipName)
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.restoreWorldFromExternal(file, dirName) }
                if (ok) reportMessage(str(R.string.msg_world_restored, zipName))
                else reportError(str(R.string.err_world_restore_fail))
            } catch (e: Exception) {
                reportError(str(R.string.err_world_restore_fail_msg, e.message))
            }
        }
    }

    /** 请求还原服务器：解析目标目录名与核心类型，存在同名则发冲突事件（UI 弹框） */
    fun requestRestoreServer(zipName: String) {
        val (dirName, coreTag) = backupManager.parseServerZipInfo(zipName)
        val target = java.io.File(serversDir(), dirName)
        if (target.exists()) {
            _restoreConflict.value = RestoreConflict(zipName, dirName)
        } else {
            performRestoreServer(zipName, dirName, overwrite = false, coreTag = coreTag)
        }
    }

    /** 确认还原服务器（overwrite=true 覆盖同名） */
    fun confirmRestoreServer(overwrite: Boolean) {
        val conflict = _restoreConflict.value ?: return
        _restoreConflict.value = null
        val coreTag = backupManager.parseServerZipInfo(conflict.zipName).second
        performRestoreServer(conflict.zipName, conflict.dirName, overwrite, coreTag)
    }

    fun dismissRestoreConflict() { _restoreConflict.value = null }

    private fun performRestoreServer(zipName: String, dirName: String, overwrite: Boolean, coreTag: String? = null) {
        val file = java.io.File(com.mineserve.mobile.server.ExternalBackupStore.rootDir, zipName)
        scope.launch {
            try {
                // 静默失败会让用户以为「点了没反应」。这两步是 restoreServerFromExternal
                // 内部最常见的两个 early-return，先在这里判一次并给出明确原因。
                if (!file.isFile) {
                    reportError(str(R.string.err_restore_zip_missing, zipName))
                    return@launch
                }
                if (!withContext(Dispatchers.IO) { backupManager.validateZipFile(file) }) {
                    reportError(str(R.string.err_restore_zip_corrupt, zipName))
                    return@launch
                }
                val ok = withContext(Dispatchers.IO) {
                    backupManager.restoreServerFromExternal(file, dirName, overwrite)
                }
                if (ok) {
                    // 注册到已安装核心列表并设为当前选中（否则概览/核心列表不显示）
                    registerRestoredCore(dirName, coreTag)
                    reportMessage(str(R.string.msg_server_restored, zipName))
                } else {
                    reportError(str(R.string.err_server_restore_fail))
                }
            } catch (e: Exception) {
                reportError(str(R.string.err_server_restore_fail_msg, e.message))
            }
        }
    }

    /**
     * 把还原的服务器注册进已安装核心列表（config.installedCores）并设为当前选中。
     * 核心类型从备份文件名识别（coreTag），无法识别时标记 Unknown。
     */
    private fun registerRestoredCore(dirName: String, coreTag: String? = null) {
        val cfg = config.value
        cfg.installedCores.firstOrNull { it.dirName == dirName }?.let { existing ->
            // 已存在：确保设为当前选中。
            // activeCoreName 对应的是 InstalledCore.**name**（全项目都按
            // `find { it.name == activeCoreName }` 取，见 WidgetUpdater / McpServers），
            // 而改名只改 name 不改 dirName —— 这里写成 dirName 会让改过名的核心
            // 变成一个查不到的值，启动时报「未选择要启动的服务端核心」。
            if (cfg.activeCoreName != existing.name) {
                updateConfig { it.copy(activeCoreName = existing.name) }
            }
            return
        }
        // 从 coreTag（核心 displayName）匹配枚举，未知则 Unknown
        val core = com.mineserve.mobile.data.ServerCore.entries
            .firstOrNull { it.displayName == coreTag }
            ?: com.mineserve.mobile.data.ServerCore.Unknown
        updateConfig {
            it.copy(
                installedCores = it.installedCores + com.mineserve.mobile.data.InstalledCore(
                    name = dirName,
                    core = core,
                    version = str(R.string.ver_restored),
                    dirName = dirName,
                    location = StorageLocation.Internal
                ),
                activeCoreName = dirName
            )
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
