package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.server.CrashCaseStore
import com.mineserve.mobile.server.CrashInsight
import com.mineserve.mobile.server.CrashInsightEngine
import com.mineserve.mobile.server.EnvInfo
import com.mineserve.mobile.server.TriggerInfo
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class CrashDetailsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>
) {
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
    fun buildInsight(
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

    /** 用户反馈：回写 L2 案例库，让后续相似崩溃的建议更准。 */
    fun recordCrashFeedback(crashId: String, resolved: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { crashCaseStore.recordFeedback(crashId, resolved) }
        }
    }
}
