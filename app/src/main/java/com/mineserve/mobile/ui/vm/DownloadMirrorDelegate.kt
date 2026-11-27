package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.DownloadMirror
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.runtime.BootstrapInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class DownloadMirrorDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val installer: BootstrapInstaller,
    private val updateConfig: ((McConfig) -> McConfig) -> Unit,
    private val applyMirror: (DownloadMirror) -> Unit
) {
    private val _mirrorTestRunning = MutableStateFlow(false)
    val mirrorTestRunning: StateFlow<Boolean> = _mirrorTestRunning.asStateFlow()

    private val _mirrorTestResult = MutableStateFlow<String?>(null)
    val mirrorTestResult: StateFlow<String?> = _mirrorTestResult.asStateFlow()

    private fun appendMirrorTest(line: String) {
        val current = _mirrorTestResult.value
        _mirrorTestResult.value = if (current.isNullOrEmpty()) line else "$current\n$line"
    }

    fun setDownloadMirror(mirror: DownloadMirror) {
        updateConfig { it.copy(downloadMirror = mirror) }
        // 立即应用到 bootstrap 下载器（下次下载生效；App 重启后由 McApplication 从配置恢复）
        applyMirror(mirror)
    }

    fun testDownloadSources() {
        if (_mirrorTestRunning.value) return
        _mirrorTestRunning.value = true
        _mirrorTestResult.value = null
        scope.launch {
            try {
                val index = installer.probeMirrors(::appendMirrorTest)
                val mirror = if (index >= 0) installer.mirrorAt(index) else null
                if (mirror != null) {
                    updateConfig { it.copy(downloadMirror = mirror) }
                    applyMirror(mirror)
                    appendMirrorTest(app.getString(R.string.vm_mirror_selected, mirror.displayName))
                } else {
                    appendMirrorTest(app.getString(R.string.vm_mirror_all_failed))
                }
            } catch (e: Exception) {
                appendMirrorTest(
                    app.getString(
                        R.string.vm_mirror_test_failed,
                        e.message ?: app.getString(R.string.vm_unknown_error)
                    )
                )
            } finally {
                _mirrorTestRunning.value = false
            }
        }
    }
}
