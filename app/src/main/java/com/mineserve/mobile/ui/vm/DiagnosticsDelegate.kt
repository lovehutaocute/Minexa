package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.DiagnosticCheck
import com.mineserve.mobile.data.DiagnosticReport
import com.mineserve.mobile.data.DiagnosticStatus
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DiagnosticsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val termuxRuntime: TermuxRuntime,
    private val buildReport: () -> DiagnosticReport,
    private val refreshJava: () -> Unit,
    private val refreshDependencies: () -> Unit,
    private val refreshPhp: () -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _diagnosticReport = MutableStateFlow(DiagnosticReport())
    val diagnosticReport: StateFlow<DiagnosticReport> = _diagnosticReport.asStateFlow()
    private val _isDiagnosing = MutableStateFlow(false)
    val isDiagnosing: StateFlow<Boolean> = _isDiagnosing.asStateFlow()
    private val _isRepairingRuntime = MutableStateFlow(false)
    val isRepairingRuntime: StateFlow<Boolean> = _isRepairingRuntime.asStateFlow()

    fun runDiagnostics() {
        if (_isDiagnosing.value || _isRepairingRuntime.value) return
        _isDiagnosing.value = true
        scope.launch {
            try {
                _diagnosticReport.value = withContext(Dispatchers.IO) { buildReport() }
            } catch (e: Exception) {
                _diagnosticReport.value = DiagnosticReport(
                    checks = listOf(DiagnosticCheck(
                        "scan", str(R.string.diag_check_scan), e.message ?: str(R.string.diag_check_scan_fail), DiagnosticStatus.Failed
                    )),
                    generatedAtMs = System.currentTimeMillis()
                )
            } finally {
                _isDiagnosing.value = false
            }
        }
    }

    fun safeRepairRuntime() {
        if (_isRepairingRuntime.value || _isDiagnosing.value) return
        _isRepairingRuntime.value = true
        _diagnosticReport.value = _diagnosticReport.value.copy(isRunning = true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cfg = config.value
                    val core = cfg.installedCores.find { it.name == cfg.activeCoreName }
                    termuxRuntime.fixRootfsPermissions()
                    termuxRuntime.repairInstalledCommands()
                    if (termuxRuntime.isReady()) {
                        termuxRuntime.autoRepairRuntime(
                            cfg.selectedJavaVersion,
                            core?.let { termuxRuntime.needsFontRuntime(it.core) } == true
                        )
                    }
                }
                refreshJava()
                refreshDependencies()
                refreshPhp()
                reportMessage(str(R.string.msg_repair_done))
            } catch (e: Exception) {
                reportError(e.message ?: str(R.string.msg_repair_fail))
            } finally {
                _isRepairingRuntime.value = false
                runDiagnostics()
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
