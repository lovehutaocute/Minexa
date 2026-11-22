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

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
