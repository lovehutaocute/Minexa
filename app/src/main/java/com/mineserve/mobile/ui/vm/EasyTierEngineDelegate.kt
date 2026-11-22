package com.mineserve.mobile.ui.vm

import android.content.Context
import android.net.Uri
import com.mineserve.mobile.R
import com.mineserve.mobile.server.tunnel.easytier.EasyTierEngineStore
import com.mineserve.mobile.ui.McViewModel.DownloadState
import com.mineserve.mobile.ui.McViewModel.EasyTierEngineInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class EasyTierEngineDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val stopEasyTier: suspend () -> Unit
) {
    private val _easyTierEngine = MutableStateFlow(EasyTierEngineInfo())
    val easyTierEngine: StateFlow<EasyTierEngineInfo> = _easyTierEngine.asStateFlow()

    private val _easyTierEngineMessage = MutableStateFlow<String?>(null)
    val easyTierEngineMessage: StateFlow<String?> = _easyTierEngineMessage.asStateFlow()

    fun clearEasyTierEngineMessage() {
        _easyTierEngineMessage.value = null
    }

    private val _easyTierDownload = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val easyTierDownload: StateFlow<DownloadState> = _easyTierDownload.asStateFlow()

    fun refreshEasyTierEngine() {
        scope.launch(Dispatchers.IO) {
            val installed = EasyTierEngineStore.isInstalled()
            _easyTierEngine.value = EasyTierEngineInfo(
                installed = installed,
                version = if (installed) EasyTierEngineStore.ENGINE_VERSION else null,
                sizeBytes = if (installed) EasyTierEngineStore.engineFile().length() else 0,
                sha256 = if (installed) EasyTierEngineStore.installedSha256() else null
            )
        }
    }

    fun downloadEasyTierEngine() {
        if (_easyTierDownload.value is DownloadState.Downloading) return
        scope.launch(Dispatchers.IO) {
            _easyTierDownload.value = DownloadState.Downloading(-1f)
            try {
                val version = EasyTierEngineStore.download { fraction ->
                    _easyTierDownload.value = DownloadState.Downloading(fraction)
                }
                _easyTierEngineMessage.value = str(R.string.vm_engine_downloaded) +
                    str(R.string.vm_version_suffix, version)
            } catch (e: Exception) {
                _easyTierEngineMessage.value =
                    str(R.string.vm_download_failed, e.message ?: str(R.string.vm_network_unavailable))
            } finally {
                _easyTierDownload.value = DownloadState.Idle
                refreshEasyTierEngine()
            }
        }
    }

    fun importEasyTierEngine(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    EasyTierEngineStore.install(input)
                } ?: throw IllegalStateException(str(R.string.vm_cannot_read_file))
                _easyTierEngineMessage.value = str(R.string.vm_engine_imported) +
                    str(R.string.vm_version_suffix, EasyTierEngineStore.ENGINE_VERSION)
                refreshEasyTierEngine()
            } catch (e: Exception) {
                _easyTierEngineMessage.value = str(R.string.vm_import_failed, e.message ?: "")
            }
        }
    }

    fun uninstallEasyTierEngine() {
        scope.launch(Dispatchers.IO) {
            // 先走完整的停止流程（会把组网卡片同步成「已停止」），再删文件。
            // 直接删文件时实例和 TUN 都还在，正是用户报的「删了还有残留」。
            runCatching { stopEasyTier() }
            val ok = EasyTierEngineStore.uninstall()
            _easyTierEngineMessage.value =
                if (ok) str(R.string.vm_engine_uninstalled) else str(R.string.vm_uninstall_failed)
            refreshEasyTierEngine()
        }
    }

    val easyTierEngineInstalled: Boolean get() = EasyTierEngineStore.isInstalled()

    private fun str(id: Int, vararg args: Any?): String =
        context.getString(id, *args)
}
