package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.server.BackupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ExternalBackupsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val backupManager: BackupManager,
    private val config: StateFlow<McConfig>,
    private val activeDirName: () -> String?,
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

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
