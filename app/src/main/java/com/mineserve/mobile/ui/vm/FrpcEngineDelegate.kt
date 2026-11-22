package com.mineserve.mobile.ui.vm

import android.content.Context
import android.net.Uri
import com.mineserve.mobile.R
import com.mineserve.mobile.server.tunnel.frpc.FrpcRuntime
import com.mineserve.mobile.server.tunnel.frpc.FrpcRuntimeDownloader
import com.mineserve.mobile.ui.McViewModel.DownloadState
import com.mineserve.mobile.ui.McViewModel.FrpcEngineInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class FrpcEngineDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val frpcRuntime: FrpcRuntime
) {
    private val _frpcEngine = MutableStateFlow(FrpcEngineInfo())
    val frpcEngine: StateFlow<FrpcEngineInfo> = _frpcEngine.asStateFlow()

    private val _frpcEngineMessage = MutableStateFlow<String?>(null)
    val frpcEngineMessage: StateFlow<String?> = _frpcEngineMessage.asStateFlow()

    fun clearFrpcEngineMessage() {
        _frpcEngineMessage.value = null
    }

    private val _frpcDownload = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val frpcDownload: StateFlow<DownloadState> = _frpcDownload.asStateFlow()


    /** 刷新 frpc 引擎状态（含 SHA-256 计算，走 IO 线程）。 */
    fun refreshFrpcEngine() {
        scope.launch(Dispatchers.IO) {
            val installed = frpcRuntime.isInstalled()
            _frpcEngine.value = FrpcEngineInfo(
                installed = installed,
                version = if (installed) frpcRuntime.installedVersion else null,
                sizeBytes = if (installed) frpcRuntime.engineFile.length() else 0,
                sha256 = if (installed) frpcRuntime.engineSha256() else null
            )
        }
    }

    /**
     * 在线下载并安装引擎。
     *
     * 下载失败**不动**已有引擎（[FrpcRuntimeDownloader] 保证）：用户可能只是想升级，
     * 升级失败却把当前可用的版本弄没了，那是最糟的结果。所以这里失败只提示，不清理。
     */
    fun downloadFrpcEngine() {
        if (_frpcDownload.value is DownloadState.Downloading) return
        scope.launch(Dispatchers.IO) {
            _frpcDownload.value = DownloadState.Downloading(-1f)
            try {
                val downloader = FrpcRuntimeDownloader(frpcRuntime)
                val manifest = downloader.fetchManifest()
                val result = downloader.downloadAndInstall(manifest) { fraction ->
                    _frpcDownload.value = DownloadState.Downloading(fraction)
                }
                _frpcEngineMessage.value = str(R.string.vm_engine_downloaded) +
                    (result.version?.let { str(R.string.vm_version_suffix, it) } ?: "")
            } catch (e: Exception) {
                _frpcEngineMessage.value = str(R.string.vm_download_failed, e.message ?: str(R.string.vm_network_unavailable))
            } finally {
                _frpcDownload.value = DownloadState.Idle
                refreshFrpcEngine()
            }
        }
    }

    /**
     * 从用户选中的文件导入 frpc 引擎（libfrpc.so）。
     *
     * 校验在 [com.mineserve.mobile.server.tunnel.frpc.FrpcRuntime.install] 内完成
     * （体积 / ELF 魔数 / arm64 架构）。校验失败时**不覆盖**已装引擎 ——
     * 用户手上未必还有第二份可用的引擎。
     */
    fun importFrpcEngine(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val name = queryDisplayName(uri)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    frpcRuntime.install(input, name)
                } ?: throw IllegalStateException(str(R.string.vm_cannot_read_file))
                _frpcEngineMessage.value = str(R.string.vm_engine_imported) +
                    (frpcRuntime.installedVersion?.let { str(R.string.vm_version_suffix, it) } ?: "")
                refreshFrpcEngine()
            } catch (e: Exception) {
                _frpcEngineMessage.value = str(R.string.vm_import_failed, e.message ?: "")
            }
        }
    }

    /** 卸载 frpc 引擎。 */
    fun uninstallFrpcEngine() {
        scope.launch(Dispatchers.IO) {
            val ok = frpcRuntime.uninstall()
            _frpcEngineMessage.value = if (ok) str(R.string.vm_engine_uninstalled) else str(R.string.vm_uninstall_failed)
            refreshFrpcEngine()
        }
    }

    /** 取 SAF 文件的显示名，仅用于从文件名里认引擎版本号。 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun str(id: Int, vararg args: Any?): String =
        context.getString(id, *args)
}
