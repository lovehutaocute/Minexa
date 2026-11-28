package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.server.McServerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class McViewModelStartupDelegate(
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val serverState: StateFlow<ServerState>,
    private val repo: ServerRepository,
    private val controller: McServerController,
    private val playerHistory: PlayerHistoryDelegate,
    private val installedCores: InstalledCoresDelegate,
    private val serverProperties: ServerPropertiesDelegate,
    private val serverResources: ServerResourcesDelegate,
    private val playerPresence: PlayerPresenceDelegate,
    private val consoleLog: ConsoleLogDelegate,
    private val terminalSessions: TerminalSessionsDelegate,
    private val parseConsoleLine: (String) -> Unit,
    private val showStartupFailureReport: (McServerController.StartupFailure) -> Unit,
    private val setLastRunLog: (String) -> Unit
) {
    fun start() {
        playerHistory.loadPlayerHistory()
        installedCores.scanUnregisteredServers()
        scope.launch {
            config.map { it.activeCoreName }.distinctUntilChanged().collect { playerHistory.loadPlayerHistory() }
        }
        // Keep the runtime projection in one place for callers that do not have McConfig.
        scope.launch {
            config
                .map { it.selectedJavaVendor to it.prootDistro }
                .distinctUntilChanged()
                .collect { (vendor, distro) ->
                    repo.termuxRuntime.selectedProotVendor = vendor
                    repo.termuxRuntime.selectedProotDistro = distro
                }
        }
        // Read server.properties immediately so LAN/tunnel ports are correct before the settings page opens.
        scope.launch { serverProperties.refreshServerPropertiesNow() }
        scope.launch {
            var wasRunning = false
            serverState.collect { state ->
                if (wasRunning && !state.isRunning) {
                    // Clear the list and count together, then load the completed run log.
                    playerPresence.clearOnlinePlayerNames()
                    repo.updateServerState { if (it.isRunning) it else it.copy(onlinePlayers = 0) }
                    playerHistory.interruptPlayerSessions()
                    setLastRunLog(withContext(Dispatchers.IO) {
                        delay(150)
                        repo.termuxRuntime.readLastMcRunLog()
                    })
                }
                wasRunning = state.isRunning
            }
        }
        serverResources.startServerResourceCollection()
        // Recover a recent failure before subscribing, covering events emitted before this ViewModel existed.
        controller.recentStartupFailure()?.let(showStartupFailureReport)
        scope.launch(Dispatchers.Default) {
            controller.startupFailures.collect { showStartupFailureReport(it) }
        }
        // Keep per-line parsing and log processing off the main thread.
        scope.launch(Dispatchers.Default) {
            repo.termuxRuntime.consoleFlow.collect { line ->
                consoleLog.appendLine(line)
                parseConsoleLine(line)
            }
        }
        consoleLog.startOutputFlush()
        terminalSessions.startLegacyOutputFlush()
        terminalSessions.startOutputFlush()
    }
}
