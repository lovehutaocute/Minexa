package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.server.BackupManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SnapshotsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val backupManager: BackupManager,
    private val activeDirName: () -> String?,
    private val runtimeRoot: () -> File,
    private val requireServerStopped: (String) -> Boolean,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _snapshots = MutableStateFlow<List<BackupManager.SnapshotInfo>>(emptyList())
    val snapshots: StateFlow<List<BackupManager.SnapshotInfo>> = _snapshots.asStateFlow()

    /** 加载快照列表 */
    fun loadSnapshots() {
        scope.launch {
            try {
                _snapshots.value = withContext(Dispatchers.IO) { backupManager.listSnapshots() }
            } catch (e: Exception) {
                reportError(str(R.string.s248, e.message))
            }
        }
    }

    /** 恢复快照（会先停止服务器） */
    fun restoreSnapshot(name: String) {
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s249))
            return
        }
        scope.launch {
            try {
                reportMessage(str(R.string.s250))
                val ok = withContext(Dispatchers.IO) { backupManager.restoreSnapshot(name, dirName) }
                if (ok) {
                    reportMessage(str(R.string.s251))
                    loadSnapshots()
                } else {
                    reportError(str(R.string.s252))
                }
            } catch (e: Exception) {
                reportError(str(R.string.s253, e.message))
            }
        }
    }

    /** 导出快照 zip 到用户选择的 Uri（自定义导出路径） */
    fun exportSnapshotToUri(snapshotName: String, uri: android.net.Uri) {
        val src = java.io.File(runtimeRoot(), "home/snapshots/$snapshotName")
        if (!src.exists()) {
            reportError(str(R.string.s254))
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri)
                        ?: throw RuntimeException(str(R.string.err_open_export))
                    output.use { os -> src.inputStream().use { it.copyTo(os) } }
                }
                reportMessage(str(R.string.s256, snapshotName))
            } catch (e: Exception) {
                reportError(str(R.string.s257, e.message))
            }
        }
    }

    /** 删除快照 */
    fun deleteSnapshot(name: String) {
        // 兜底：运行中删除快照会毁掉「出事可回滚」的备份，与服务端活动期解耦更安全
        if (!requireServerStopped("删除快照")) return
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.deleteSnapshot(name) }
                if (ok) {
                    reportMessage(str(R.string.s258, name))
                    loadSnapshots()
                } else {
                    reportError(str(R.string.s259))
                }
            } catch (e: Exception) {
                reportError(str(R.string.s260, e.message))
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
