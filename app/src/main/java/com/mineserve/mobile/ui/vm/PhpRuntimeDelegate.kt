package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.PhpVersion
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PhpRuntimeDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val termuxRuntime: TermuxRuntime,
    config: StateFlow<McConfig>,
    private val isBootstrapped: StateFlow<Boolean>,
    private val isInstalling: StateFlow<Boolean>,
    private val setInstalling: (Boolean) -> Unit,
    private val setJavaOperation: (String?) -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _installedPhp = MutableStateFlow<Set<PhpVersion>>(emptySet())
    val installedPhp: StateFlow<Set<PhpVersion>> = _installedPhp.asStateFlow()

    /** 当前所选 PHP 版本（来自 config，配置未加载时回退默认） */
    val selectedPhpVersion: StateFlow<PhpVersion> = config
        .map { it.selectedPhpVersion }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, PhpVersion.Default)

    /** 当前 PHP 版本是否已安装 */
    val phpInstalled: StateFlow<Boolean> = combine(_installedPhp, selectedPhpVersion) { installed, selected ->
        selected in installed
    }.stateIn(scope, SharingStarted.Eagerly, false)

    fun refreshPhp() {
        if (!isBootstrapped.value) { _installedPhp.value = emptySet(); return }
        scope.launch(Dispatchers.IO) {
            _installedPhp.value = termuxRuntime.installedPhpVersions()
        }
    }

    fun installPhp(version: PhpVersion) {
        if (!isBootstrapped.value || isInstalling.value) return
        if (termuxRuntime.isMcRunning()) {
            reportError(str(R.string.msg_php_running))
            return
        }
        setInstalling(true)
        setJavaOperation(str(R.string.msg_php_op_installing))
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    termuxRuntime.installPhp(version) { line -> termuxRuntime.emitLog(line) }
                }
                refreshPhp()
                if (ok) reportMessage(str(R.string.msg_php_installed, version.displayName))
                else reportError(str(R.string.err_php_install_fail, version.displayName))
            } catch (e: Exception) {
                reportError(e.message ?: str(R.string.err_php_install_fail, version.displayName))
            } finally {
                setJavaOperation(null)
                setInstalling(false)
            }
        }
    }

    fun deletePhp(version: PhpVersion) {
        if (!isBootstrapped.value || isInstalling.value) return
        setInstalling(true)
        setJavaOperation(str(R.string.msg_php_op_deleting))
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { termuxRuntime.deletePhp(version) }
                refreshPhp()
                if (ok) reportMessage(str(R.string.msg_php_deleted, version.displayName))
                else reportError(str(R.string.err_php_delete_fail, version.displayName))
            } catch (e: Exception) {
                reportError(e.message ?: str(R.string.err_php_delete_fail, version.displayName))
            } finally {
                setJavaOperation(null)
                setInstalling(false)
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
