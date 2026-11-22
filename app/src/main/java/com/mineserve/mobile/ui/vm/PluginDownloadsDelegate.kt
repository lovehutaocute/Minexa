package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.server.PluginManager
import com.mineserve.mobile.ui.McViewModel.PluginDownloadProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PluginDownloadsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val pluginManager: PluginManager,
    private val activeDirName: () -> String?,
    private val currentModrinthLoader: () -> String,
    private val refreshMods: () -> Unit,
    private val refreshInstalledPlugins: () -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    /** 当前正在下载的插件 id → 进度 */
    private val _pluginDownloadProgress = MutableStateFlow<Map<String, PluginDownloadProgress>>(emptyMap())
    val pluginDownloadProgress: StateFlow<Map<String, PluginDownloadProgress>> = _pluginDownloadProgress.asStateFlow()

    /** 是否有任意插件正在下载 */
    val isPluginDownloading: StateFlow<Boolean> = _pluginDownloadProgress
        .map { it.isNotEmpty() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    fun installModrinthMod(hit: PluginManager.ModrinthHit, mcVersion: String) {
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s212))
            return
        }
        val loader = currentModrinthLoader()
        scope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    pluginManager.resolveModrinthDownload(hit.slug, mcVersion, loader)
                } ?: throw RuntimeException(str(R.string.err_mod_incompatible))
                val fileName = "${hit.slug}.jar"
                withContext(Dispatchers.IO) {
                    // 下载开始前先放占位进度，保证进度弹窗一定显示（下载瞬间完成也不会漏）
                    _pluginDownloadProgress.update { it + (hit.slug to PluginDownloadProgress(
                        pluginId = hit.slug, downloadedBytes = 0, totalBytes = 0, speedBytesPerSec = 0
                    )) }
                    pluginManager.installModFromUrl(url, fileName, dirName) { d, t, s ->
                        _pluginDownloadProgress.update { it + (hit.slug to PluginDownloadProgress(
                            pluginId = hit.slug, downloadedBytes = d, totalBytes = t, speedBytesPerSec = s
                        )) }
                    }
                }
                _pluginDownloadProgress.update { it - hit.slug }
                reportMessage(str(R.string.s225, hit.title))
                refreshMods()
            } catch (e: Exception) {
                _pluginDownloadProgress.update { it - hit.slug }
                reportError(str(R.string.s226, hit.title, e.message))
            }
        }
    }

    fun installModrinthPlugin(hit: PluginManager.ModrinthHit, mcVersion: String) {
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s212))
            return
        }
        val loader = "bukkit"
        scope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    pluginManager.resolveModrinthDownload(hit.slug, mcVersion, loader)
                } ?: throw RuntimeException(str(R.string.err_plugin_incompatible))
                val fileName = "${hit.slug}.jar"
                withContext(Dispatchers.IO) {
                    // 下载开始前先放占位进度，保证进度弹窗一定显示
                    _pluginDownloadProgress.update { it + (hit.slug to PluginDownloadProgress(
                        pluginId = hit.slug, downloadedBytes = 0, totalBytes = 0, speedBytesPerSec = 0
                    )) }
                    pluginManager.installFromUrl(url, fileName, dirName) { d, t, s ->
                        _pluginDownloadProgress.update { it + (hit.slug to PluginDownloadProgress(
                            pluginId = hit.slug, downloadedBytes = d, totalBytes = t, speedBytesPerSec = s
                        )) }
                    }
                }
                _pluginDownloadProgress.update { it - hit.slug }
                reportMessage(str(R.string.s229, hit.title))
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _pluginDownloadProgress.update { it - hit.slug }
                reportError(str(R.string.s230, hit.title, e.message))
            }
        }
    }

    fun installCuratedPlugin(curated: PluginManager.CuratedPlugin) {
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s212))
            return
        }
        if (_pluginDownloadProgress.value.containsKey(curated.id)) {
            reportError(str(R.string.s227, curated.name))
            return
        }
        scope.launch {
            try {
                // asset 文件名带版本号的精选资源（如 ViaVersion），先经 GitHub API 解析最新直链；
                // 解析失败直接报错，不静默回退到 HTML 页面
                val resolvedUrl = if (curated.githubAssetPattern != null) {
                    withContext(Dispatchers.IO) {
                        pluginManager.resolveLatestAsset(curated.repo, curated.githubAssetPattern)
                    } ?: throw RuntimeException(str(R.string.err_parse_curated_url, curated.name))
                } else {
                    curated.downloadUrl
                }
                // 下载开始前先放占位进度，保证进度弹窗一定显示（下载瞬间完成也不会漏）
                _pluginDownloadProgress.update { it + (curated.id to PluginDownloadProgress(
                    pluginId = curated.id, downloadedBytes = 0, totalBytes = 0, speedBytesPerSec = 0
                )) }
                pluginManager.installFromUrl(
                    resolvedUrl,
                    curated.targetFileName,
                    dirName
                ) { downloaded, total, speed ->
                    _pluginDownloadProgress.update { it + (curated.id to PluginDownloadProgress(
                        pluginId = curated.id,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speed
                    )) }
                }
                reportMessage(str(R.string.s229, curated.name))
                _pluginDownloadProgress.update { it - curated.id }
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _pluginDownloadProgress.update { it - curated.id }
                reportError(str(R.string.s230, curated.name, e.message))
            }
        }
    }

    private val URL_DOWNLOAD_ID = "__custom_url__"

    /** 从自定义 URL 下载插件 */
    fun installPluginFromUrl(url: String, customFileName: String) {
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s212))
            return
        }
        if (url.isBlank() || customFileName.isBlank()) {
            reportError(str(R.string.s244))
            return
        }
        if (_pluginDownloadProgress.value.containsKey(URL_DOWNLOAD_ID)) {
            reportError(str(R.string.s245))
            return
        }
        scope.launch {
            try {
                // 下载开始前先放占位进度，保证进度弹窗一定显示（下载瞬间完成也不会漏）
                _pluginDownloadProgress.update { it + (URL_DOWNLOAD_ID to PluginDownloadProgress(
                    pluginId = URL_DOWNLOAD_ID, downloadedBytes = 0, totalBytes = 0, speedBytesPerSec = 0
                )) }
                pluginManager.installFromUrl(url, customFileName, dirName) { downloaded, total, speed ->
                    _pluginDownloadProgress.update { it + (URL_DOWNLOAD_ID to PluginDownloadProgress(
                        pluginId = URL_DOWNLOAD_ID,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speed
                    )) }
                }
                reportMessage(str(R.string.s246, customFileName))
                _pluginDownloadProgress.update { it - URL_DOWNLOAD_ID }
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _pluginDownloadProgress.update { it - URL_DOWNLOAD_ID }
                reportError(str(R.string.s247, e.message))
            }
        }
    }

    /** 判断自定义 URL 是否正在下载 */
    fun isCustomUrlDownloading(): Boolean = _pluginDownloadProgress.value.containsKey(URL_DOWNLOAD_ID)

    /** 获取自定义 URL 下载进度（无则 null） */
    fun customUrlDownloadProgress(): PluginDownloadProgress? =
        _pluginDownloadProgress.value[URL_DOWNLOAD_ID]

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
