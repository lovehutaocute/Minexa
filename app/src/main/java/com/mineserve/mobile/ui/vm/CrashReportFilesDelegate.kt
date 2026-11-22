package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.server.CrashReportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CrashReportFilesDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val crashReportManager: CrashReportManager,
    private val refreshKnowledgeIfStale: suspend () -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _crashReports = MutableStateFlow<List<CrashReportManager.CrashReport>>(emptyList())
    val crashReports: StateFlow<List<CrashReportManager.CrashReport>> = _crashReports.asStateFlow()

    /** 加载崩溃报告列表 */
    fun loadCrashReports() {
        scope.launch {
            try {
                _crashReports.value = withContext(Dispatchers.IO) {
                    val native = config.value.installedCores.flatMap { core ->
                        crashReportManager.listNativeCrashReports(core.dirName)
                    }
                    (crashReportManager.listCrashReports() + native).sortedByDescending { it.createdTime }
                }
            } catch (e: Exception) {
                reportError(str(R.string.s287, e.message))
            }
        }
        // 进崩溃页时顺带到点刷新在线规则包（24h 节流；失败静默，不影响本地分析）
        scope.launch(Dispatchers.IO) {
            runCatching { refreshKnowledgeIfStale() }
        }
    }

    /** 删除崩溃报告 */
    fun deleteCrashReport(fileName: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { crashReportManager.deleteCrashReport(fileName) }
                reportMessage(str(R.string.s290, fileName))
                loadCrashReports()
            } catch (e: Exception) {
                reportError(str(R.string.s291, e.message))
            }
        }
    }

    /** 清空所有崩溃报告 */
    /** 读取应用自身崩溃日志（全局 uncaught handler 写入），无则返回 null */
    fun appCrashLog(): String? {
        val dir = java.io.File(app.filesDir, "home")
        val candidates = listOf(
            java.io.File(dir, "crash_log.txt"),
            java.io.File(dir, "crash_log_read.txt")
        )
        val file = candidates.firstOrNull { it.exists() && it.length() > 0 } ?: return null
        return try {
            val text = file.readText()
            if (text.length > 4000) text.takeLast(4000) else text
        } catch (_: Exception) {
            null
        }
    }

    /** 清空应用崩溃日志文件 */
    fun clearAppCrashLog() {
        try {
            val dir = java.io.File(app.filesDir, "home")
            java.io.File(dir, "crash_log.txt").delete()
            java.io.File(dir, "crash_log_read.txt").delete()
        } catch (_: Exception) {
        }
    }

    fun clearCrashReports() {
        scope.launch {
            try {
                val count = withContext(Dispatchers.IO) { crashReportManager.clearAllCrashReports() }
                reportMessage(str(R.string.s292, count))
                loadCrashReports()
            } catch (e: Exception) {
                reportError(str(R.string.s293, e.message))
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
