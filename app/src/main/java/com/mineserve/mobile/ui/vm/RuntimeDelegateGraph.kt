package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.PhpVersion
import com.mineserve.mobile.data.ProotDistro
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** 仅组合运行环境相关委托；CoroutineScope 和共享消息流仍由 ViewModel 持有。 */
internal class RuntimeDelegateGraph(
    private val app: Context,
    private val scope: CoroutineScope,
    private val termuxRuntime: TermuxRuntime,
    private val config: StateFlow<McConfig>,
    private val isBootstrapped: StateFlow<Boolean>,
    private val isInstalling: StateFlow<Boolean>,
    private val setInstalling: (Boolean) -> Unit,
    private val updateConfig: ((McConfig) -> McConfig) -> Unit,
    private val updateAndSaveConfig: ((McConfig) -> McConfig) -> McConfig,
    private val formatMessage: (Int, List<Any?>) -> String,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    val environment by lazy { RuntimeEnvironmentDelegate(app) }

    val java by lazy {
        JavaRuntimeDelegate(
            app = app,
            scope = scope,
            termuxRuntime = termuxRuntime,
            config = config,
            isBootstrapped = isBootstrapped,
            isInstalling = isInstalling,
            setInstalling = setInstalling,
            updateAndSaveConfig = updateAndSaveConfig,
            reportMessage = reportMessage,
            reportError = reportError
        )
    }

    val php by lazy {
        PhpRuntimeDelegate(
            app = app,
            scope = scope,
            termuxRuntime = termuxRuntime,
            config = config,
            isBootstrapped = isBootstrapped,
            isInstalling = isInstalling,
            setInstalling = setInstalling,
            setJavaOperation = { java.setJavaOperation(it) },
            reportMessage = reportMessage,
            reportError = reportError
        )
    }

    val selection by lazy {
        RuntimeSelectionDelegate(
            config = config,
            updateConfig = updateConfig,
            formatMessage = formatMessage
        )
    }

    val prootContainer by lazy {
        ProotContainerDelegate(
            app = app,
            scope = scope,
            termuxRuntime = termuxRuntime,
            isInstalling = isInstalling,
            setJavaOperation = java::setJavaOperation,
            refreshJava = java::refreshJava,
            reportMessage = reportMessage,
            reportError = reportError
        )
    }
}
