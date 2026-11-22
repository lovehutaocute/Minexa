package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.runtime.TermuxRuntime
import com.mineserve.mobile.server.CrashCaseStore
import com.mineserve.mobile.server.CrashInsight
import com.mineserve.mobile.server.CrashInsightEngine
import com.mineserve.mobile.server.CrashLogView
import com.mineserve.mobile.server.CrashReportAnalyzer
import com.mineserve.mobile.server.CrashReportManager
import com.mineserve.mobile.server.EnvInfo
import com.mineserve.mobile.server.McServerController
import com.mineserve.mobile.server.TriggerInfo
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CrashDetailsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val crashReportManager: CrashReportManager,
    private val termuxRuntime: TermuxRuntime,
    private val timeNow: () -> String,
    private val crashReportPath: (String) -> String?,
    private val reportError: (String) -> Unit
) {
    /** 当前查看的崩溃报告全文（供 UI 展示） */
    private val _currentCrashContent = MutableStateFlow<String?>(null)
    val currentCrashContent: StateFlow<String?> = _currentCrashContent.asStateFlow()
    private val _currentCrashAnalysis = MutableStateFlow<CrashReportAnalyzer.Analysis?>(null)
    val currentCrashAnalysis: StateFlow<CrashReportAnalyzer.Analysis?> = _currentCrashAnalysis.asStateFlow()

    /** 结构化报告（分析页主数据源）；生成失败时为 null，UI 退回纯规则归因 */
    private val _currentCrashInsight = MutableStateFlow<CrashInsight?>(null)
    val currentCrashInsight: StateFlow<CrashInsight?> = _currentCrashInsight.asStateFlow()

    /** 本次启动的日志视图（关键日志 / 全部日志）；与报告 sidecar 一一对应 */
    private val _currentCrashLog = MutableStateFlow<CrashLogView?>(null)
    val currentCrashLog: StateFlow<CrashLogView?> = _currentCrashLog.asStateFlow()

    /** L2 历史案例库（懒加载，避免拖慢 ViewModel 构建） */
    private val crashCaseStore by lazy { CrashCaseStore(File(app.filesDir, "crash-cases")) }
    private val crashInsightEngine by lazy { CrashInsightEngine(crashCaseStore) }

    /** 崩溃分析用的环境快照：从用户配置读取，比从日志里猜更可靠。 */
    private fun crashEnv(): EnvInfo {
        val c = config.value
        return EnvInfo(
            core = c.activeCoreName ?: "unknown",
            coreVersion = c.mcVersion,
            mcVersion = c.mcVersion,
            javaVersion = c.selectedJavaVersion.displayName.removePrefix("Java "),
            javaVendor = c.selectedJavaVendor.logName,
            heapMb = c.maxHeapMb,
            android = android.os.Build.VERSION.RELEASE.orEmpty(),
            abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        )
    }

    /**
     * 生成结构化报告（失败返回 null，绝不阻塞弹窗）。
     * @param logText 必须是**本次启动**的日志切片（会话日志或 sidecar `.log`）
     */
    private fun buildInsight(
        crashId: String,
        exitCode: Int,
        logText: String,
        sessionId: String,
        startedAt: Long,
        endedAt: Long,
        fallbackText: String,
        truncated: Boolean = false,
        fallbackToLatestLog: Boolean = false,
        /** native 信号退出时抓到的 abort 诊断；没有采集到传 null。 */
        abortText: String? = null
    ): CrashInsight? = runCatching {
        crashInsightEngine.analyze(
            CrashInsightEngine.Input(
                crashId = crashId,
                env = crashEnv(),
                trigger = TriggerInfo(exitCode = exitCode),
                logText = logText.ifBlank { fallbackText },
                abortText = abortText,
                sessionId = sessionId,
                startedAt = startedAt,
                endedAt = if (endedAt > 0L) endedAt else System.currentTimeMillis(),
                truncated = truncated,
                fallbackToLatestLog = fallbackToLatestLog
            )
        )
    }.getOrNull()

    /** Shows a structured report when validation fails before a process can create its own crash file. */
    fun showStartupFailureReport(error: Throwable) {
        val text = buildString {
            appendLine(str(R.string.startup_report_title))
            appendLine(str(R.string.startup_report_time, timeNow()))
            appendLine(str(R.string.startup_report_exception, error::class.java.name, error.message ?: str(R.string.startup_report_no_detail)))
            appendLine()
            append(error.stackTraceToString())
        }
        _currentCrashContent.value = text
        _currentCrashAnalysis.value = CrashReportAnalyzer.analyze(text)
        _currentCrashInsight.value = null
        _currentCrashLog.value = null
    }

    fun showStartupFailureReport(failure: McServerController.StartupFailure) {
        scope.launch(Dispatchers.IO) {
            val report = failure.reportPath?.let { runCatching { File(it).readText() }.getOrNull() }
            val text = report ?: buildString {
                appendLine(str(R.string.startup_report_title))
                appendLine(str(R.string.startup_report_time, timeNow()))
                appendLine(str(R.string.startup_report_exitcode, failure.code))
                appendLine(str(R.string.startup_report_reason, failure.detail))
                appendLine(str(R.string.startup_report_hint))
            }
            val crashId = failure.reportPath?.let { File(it).name.removeSuffix(".txt") }
                ?: "live_${System.currentTimeMillis()}"
            // 本次启动的会话切片（启动即失败时可能只有应用侧几行，但依然是有效证据）
            val session = termuxRuntime.currentLaunchSession()
            val sessionLog = session?.let { termuxRuntime.readSessionLog(it) }.orEmpty()
            val logView = sessionLog.takeIf { it.isNotBlank() }?.let {
                CrashLogView.from(
                    scopeId = session?.id.orEmpty(),
                    startedAt = session?.startedAt ?: 0L,
                    logText = it
                )
            }
            val insight = buildInsight(
                crashId = crashId,
                exitCode = failure.code,
                logText = sessionLog,
                sessionId = session?.id.orEmpty(),
                startedAt = session?.startedAt ?: 0L,
                endedAt = System.currentTimeMillis(),
                fallbackText = text,
                abortText = termuxRuntime.lastNativeAbort()
            )
            if (insight != null && failure.reportPath != null) {
                crashReportManager.writeInsight(crashId, CrashInsight.encode(insight))
            }
            withContext(Dispatchers.Main) {
                _currentCrashContent.value = text
                _currentCrashAnalysis.value = CrashReportAnalyzer.analyze(text)
                _currentCrashInsight.value = insight
                _currentCrashLog.value = logView
            }
        }
    }

    /**
     * 读取崩溃报告全文。
     *
     * 日志视图只从该报告的会话 sidecar（`{crashId}.log`）读取 —— 那是崩溃当时冻结的
     * 本次启动日志，App 重启后再打开也不会掺入任何历史启动内容。
     */
    fun readCrashReport(fileName: String) {
        scope.launch {
            try {
                val path = crashReportPath(fileName)
                val text = withContext(Dispatchers.IO) {
                    path?.let { File(it).takeIf { f -> f.isFile }?.readText() }
                }
                if (text == null) {
                    reportError(str(R.string.s288))
                    return@launch
                }
                val crashId = fileName.removeSuffix(".txt")
                val loaded = withContext(Dispatchers.IO) {
                    val sessionLog = crashReportManager.readSessionLogSidecar(crashId)
                    val insight = crashReportManager.readInsight(crashId)
                        ?: buildInsight(
                            crashId = crashId,
                            exitCode = CrashReportAnalyzer.analyze(text).exitCode ?: -1,
                            logText = sessionLog,
                            sessionId = "",
                            startedAt = 0L,
                            endedAt = 0L,
                            fallbackText = text
                        )?.also { crashReportManager.writeInsight(crashId, CrashInsight.encode(it)) }
                    val logView = sessionLog.takeIf { it.isNotBlank() }?.let {
                        CrashLogView.from(
                            scopeId = insight?.session?.id.orEmpty(),
                            startedAt = insight?.session?.startedAt ?: 0L,
                            logText = it
                        )
                    }
                    Triple(insight, logView, text)
                }
                _currentCrashContent.value = loaded.third
                _currentCrashAnalysis.value = CrashReportAnalyzer.analyze(loaded.third)
                _currentCrashInsight.value = loaded.first
                _currentCrashLog.value = loaded.second
            } catch (e: Exception) {
                reportError(str(R.string.s289, e.message))
            }
        }
    }

    /** 用户反馈：回写 L2 案例库，让后续相似崩溃的建议更准。 */
    fun recordCrashFeedback(resolved: Boolean) {
        val crashId = _currentCrashInsight.value?.crashId ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { crashCaseStore.recordFeedback(crashId, resolved) }
        }
    }

    /** 关闭崩溃报告详情视图 */
    fun clearCrashContent() {
        _currentCrashContent.value = null
        _currentCrashAnalysis.value = null
        _currentCrashInsight.value = null
        _currentCrashLog.value = null
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
