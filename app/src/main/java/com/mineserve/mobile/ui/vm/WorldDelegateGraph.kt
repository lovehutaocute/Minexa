package com.mineserve.mobile.ui.vm

import android.content.Context
import android.net.Uri
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.runtime.TermuxRuntime
import com.mineserve.mobile.server.WorldManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * 世界、维度与附加包的惰性组合。
 *
 * 各 delegate 之间的回调（刷新世界列表 / 刷新 Java 附加内容 / 联动核心刷新）在
 * 图内直接连到兄弟 delegate，不再经 McViewModel 中转；scope、配置流、运行状态
 * 与消息回调仍由 McViewModel 持有，生命周期语义不变。
 */
internal class WorldDelegateGraph(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val serverState: StateFlow<ServerState>,
    private val termuxRuntime: TermuxRuntime,
    private val activeDirName: () -> String?,
    private val requireServerStopped: (String) -> Boolean,
    private val exportPathToUri: (File, Uri) -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    val worldManager by lazy { WorldManager(termuxRuntime) }

    val list by lazy {
        WorldListDelegate(
            app = app,
            scope = scope,
            config = config,
            serverState = serverState,
            worldManagerProvider = { worldManager },
            activeDirName = activeDirName,
            isMcRunningIn = { termuxRuntime.isMcRunningIn(it) },
            reportMessage = reportMessage,
            reportError = reportError
        )
    }

    val transfer by lazy {
        WorldTransferDelegate(
            app = app,
            scope = scope,
            worldManager = worldManager,
            activeDirName = activeDirName,
            requireServerStopped = requireServerStopped,
            exportPathToUri = exportPathToUri,
            loadWorlds = { list.loadWorlds() },
            reportMessage = reportMessage,
            reportError = reportError
        )
    }

    val creation by lazy {
        WorldCreationDelegate(
            app = app,
            scope = scope,
            worldManagerProvider = { worldManager },
            activeDirName = activeDirName,
            requireServerStopped = requireServerStopped,
            loadWorlds = { list.loadWorlds() },
            reportMessage = reportMessage,
            reportError = reportError
        )
    }

    val javaAddons by lazy {
        JavaAddonsDelegate(
            app = app,
            scope = scope,
            worldManagerProvider = { worldManager },
            activeDirName = activeDirName,
            refreshActiveCore = { list.refreshActiveCore() },
            isJavaCore = { list.isJavaCore() },
            setJavaDimensions = { list.setJavaDimensions(it) },
            requireServerStopped = requireServerStopped,
            reportMessage = reportMessage,
            reportError = reportError
        )
    }

    val maintenance by lazy {
        WorldMaintenanceDelegate(
            app = app,
            scope = scope,
            worldManagerProvider = { worldManager },
            serverDirFor = { termuxRuntime.serverDirFor(it) },
            activeDirName = activeDirName,
            requireServerStopped = requireServerStopped,
            loadWorlds = { list.loadWorlds() },
            loadJavaAddons = { javaAddons.loadJavaAddons() },
            reportMessage = reportMessage,
            reportError = reportError
        )
    }

    val bdsAddons by lazy {
        BdsAddonsDelegate(
            app = app,
            scope = scope,
            worldManagerProvider = { worldManager },
            activeDirName = activeDirName,
            refreshActiveCore = { list.refreshActiveCore() },
            requireServerStopped = requireServerStopped,
            reportMessage = reportMessage,
            reportError = reportError
        )
    }
}
