package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.server.BackupManager
import com.mineserve.mobile.server.McServerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ServerLifecycleDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val isBootstrapped: StateFlow<Boolean>,
    private val repo: ServerRepository,
    private val controller: McServerController,
    private val backupManager: BackupManager,
    private val activeDirName: () -> String?,
    private val runtimeKindMismatchMessage: () -> String?,
    private val refreshServerPropertiesNow: suspend () -> Unit,
    private val realServerPort: () -> Int,
    private val clearLastRunLog: () -> Unit,
    private val resetJavaCompatibilityWarning: () -> Unit,
    private val showStartupFailureReport: (Throwable) -> Unit,
    private val startKeepAliveService: () -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _stopInFlight = MutableStateFlow(false)
    val stopInFlight: StateFlow<Boolean> = _stopInFlight.asStateFlow()

    private val _forceStopInFlight = MutableStateFlow(false)
    val forceStopInFlight: StateFlow<Boolean> = _forceStopInFlight.asStateFlow()

    fun startServer() {
        if (!isBootstrapped.value) {
            reportError(str(R.string.s192))
            return
        }
        runtimeKindMismatchMessage()?.let {
            reportError(it)
            return
        }
        scope.launch {
            try {
                resetJavaCompatibilityWarning()
                clearLastRunLog()
                repo.termuxRuntime.markStartInFlight()
                refreshServerPropertiesNow()
                val current = config.value
                val detectedPort = realServerPort()
                if (detectedPort != current.localPort) {
                    repo.termuxRuntime.emitLog(
                        "[startMc] 配置文件端口 $detectedPort 与本地记录 ${current.localPort} 不一致，本次按配置文件启动"
                    )
                }
                val startConfig = current.copy(localPort = detectedPort)
                repo.updateServerState {
                    it.copy(
                        isRunning = true,
                        runningSinceMs = 0L,
                        startupPhase = StartupPhase.PreparingEnvironment
                    )
                }
                reportMessage(str(R.string.s196))
                controller.start(startConfig, userInitiated = true)
                if (repo.serverState.value.isRunning) {
                    startKeepAliveService()
                }
            } catch (e: Exception) {
                repo.termuxRuntime.clearStartInFlight()
                repo.updateServerState {
                    it.copy(isRunning = false, runningSinceMs = 0L, startupPhase = StartupPhase.Failed)
                }
                repo.termuxRuntime.emitLog("[startMc] 启动失败: ${e.message}")
                showStartupFailureReport(e)
                reportError(str(R.string.s197, e.message))
            }
        }
    }

    fun stopServer() {
        if (_stopInFlight.value) return
        _stopInFlight.value = true
        scope.launch {
            try {
                val stopped = controller.stopGracefully()
                if (!stopped) {
                    reportError(str(R.string.dash_stop_timeout_hint))
                    return@launch
                }
                reportMessage(str(R.string.s198))
                if (config.value.stopAutoBackup) {
                    val dirName = activeDirName() ?: return@launch
                    val path = withContext(Dispatchers.IO) {
                        backupManager.backupWorldToExternal(
                            dirName, BackupManager.BackupOrigin.Automatic, config.value.maxSnapshots
                        )
                    }
                    if (path != null) reportMessage(str(R.string.msg_stop_backup_done))
                }
            } catch (e: Exception) {
                reportError(str(R.string.s199, e.message))
            } finally {
                _stopInFlight.value = false
            }
        }
    }

    fun forceStopServer() {
        if (_forceStopInFlight.value) return
        _forceStopInFlight.value = true
        scope.launch {
            try {
                controller.forceStop()
                reportMessage(str(R.string.dash_force_stop_done))
            } catch (e: Exception) {
                reportError(str(R.string.s199, e.message))
            } finally {
                _forceStopInFlight.value = false
                _stopInFlight.value = false
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String = app.getString(id, *args)
}
