package com.mineserve.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mineserve.mobile.server.McServerController
import com.mineserve.mobile.server.PluginManager
import com.mineserve.mobile.server.TunnelManager
import com.mineserve.mobile.ui.McViewModel

/** Manual dependency graph for the single-process application. */
class AppContainer(private val app: McApplication) {
    val viewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return McViewModel(
                app = app,
                repo = app.repository,
                controller = McServerController(app.termuxRuntime, app.repository),
                pluginManager = PluginManager(app.termuxRuntime, app),
                tunnelManager = TunnelManager(app, logSink = { app.termuxRuntime.emitLog(it) }),
                bdsEnvironment = app.bdsEnvironment
            ) as T
        }
    }
}
