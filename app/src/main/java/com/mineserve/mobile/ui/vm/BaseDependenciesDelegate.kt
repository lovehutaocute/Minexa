package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.StepState
import com.mineserve.mobile.runtime.TermuxRuntime
import com.mineserve.mobile.server.McServerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BaseDependenciesDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val controller: McServerController,
    private val termuxRuntime: TermuxRuntime,
    private val isBootstrapped: StateFlow<Boolean>,
    private val isInstalling: StateFlow<Boolean>,
    private val setInstalling: (Boolean) -> Unit,
    private val updateInstallSteps: (List<StepState>) -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _installSpeed = MutableStateFlow(0L)
    val installSpeed: StateFlow<Long> = _installSpeed.asStateFlow()

    fun refreshDependencies() {
        if (!isBootstrapped.value) return
        scope.launch(Dispatchers.IO) {
            val steps = termuxRuntime.installedDependencySteps()
            updateInstallSteps(steps)
        }
    }

    fun deleteDependencies() {
        if (!isBootstrapped.value || isInstalling.value) return
        setInstalling(true)
        scope.launch {
            try {
                val removed = withContext(Dispatchers.IO) { termuxRuntime.deleteDependencies() }
                refreshDependencies()
                reportMessage(str(R.string.msg_deps_deleted, removed))
            } catch (e: Exception) {
                reportError(str(R.string.s195, e.message))
            } finally {
                setInstalling(false)
            }
        }
    }

    fun reinstallDependencies() {
        if (!isBootstrapped.value || isInstalling.value) return
        setInstalling(true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { termuxRuntime.deleteDependencies() }
            } catch (_: Exception) {}
            setInstalling(false)
            installDependencies()
        }
    }

    fun installDependencies() {
        if (!isBootstrapped.value) {
            reportError(str(R.string.s192))
            return
        }
        if (isInstalling.value) return
        setInstalling(true)
        scope.launch {
            try {
                val ok = controller.installDependencies { speedBps ->
                    _installSpeed.value = speedBps
                }
                _installSpeed.value = 0L
                if (ok) {
                    refreshDependencies()
                    reportMessage(str(R.string.s193))
                } else {
                    reportError(str(R.string.s194))
                }
            } catch (e: Exception) {
                reportError(str(R.string.s195, e.message))
            } finally {
                setInstalling(false)
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
