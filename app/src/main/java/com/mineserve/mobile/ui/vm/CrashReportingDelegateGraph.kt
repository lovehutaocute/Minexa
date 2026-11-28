package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.runtime.TermuxRuntime
import com.mineserve.mobile.server.CrashReportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** 崩溃报告、详情与在线知识库的惰性组合；生命周期仍由 McViewModel 持有。 */
internal class CrashReportingDelegateGraph(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val crashReportManager: CrashReportManager,
    private val termuxRuntime: TermuxRuntime,
    private val timeNow: () -> String,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    /** L3 在线规则包懒加载，避免拖慢 ViewModel 构建。 */
    val knowledge by lazy {
        CrashKnowledgeDelegate(
            app = app,
            scope = scope,
            reportUpdated = reportMessage
        )
    }

    val files by lazy {
        CrashReportFilesDelegate(
            app = app,
            scope = scope,
            config = config,
            crashReportManager = crashReportManager,
            refreshKnowledgeIfStale = { knowledge.refreshIfStale() },
            reportMessage = reportMessage,
            reportError = reportError
        )
    }

    val details by lazy {
        CrashDetailsDelegate(
            app = app,
            scope = scope,
            config = config,
            crashReportManager = crashReportManager,
            termuxRuntime = termuxRuntime,
            timeNow = timeNow,
            crashReportPath = { fileName ->
                files.crashReports.value.firstOrNull { it.fileName == fileName }?.path
            },
            reportError = reportError
        )
    }
}
