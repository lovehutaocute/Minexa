package com.mineserve.mobile.ui.vm

import android.content.Context
import android.net.Uri
import com.mineserve.mobile.R
import com.mineserve.mobile.server.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ModsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val pluginManager: PluginManager,
    private val activeDirName: () -> String?,
    private val requireServerStopped: (String) -> Boolean,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _mods = MutableStateFlow<List<PluginManager.ModEntry>>(emptyList())
    val mods: StateFlow<List<PluginManager.ModEntry>> = _mods.asStateFlow()

    val curatedMods: List<PluginManager.CuratedMod>
        get() = pluginManager.curatedMods

    /** 扫描当前核心的 mods 目录 */
    fun refreshMods() {
        val dirName = activeDirName() ?: return
        scope.launch {
            try {
                _mods.value = withContext(Dispatchers.IO) { pluginManager.readMods(dirName) }
            } catch (e: Exception) {
                reportError(str(R.string.s214, e.message))
            }
        }
    }

    /** 切换模组启用状态 */
    fun toggleModEnabled(fileName: String) {
        // VM 侧兜底：UI 前台虽已拦截，但其他调用入口不能绕过——运行中改 mods 目录会导致本轮加载状态错乱
        if (!requireServerStopped("启用或停用模组")) return
        val dirName = activeDirName() ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { pluginManager.toggleModEnabled(fileName, dirName) }
                refreshMods()
            } catch (e: Exception) {
                reportError(str(R.string.s215, e.message))
            }
        }
    }

    /** 删除模组 */
    fun deleteMod(fileName: String) {
        if (!requireServerStopped("删除模组")) return
        val dirName = activeDirName() ?: return
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { pluginManager.deleteMod(fileName, dirName) }
                if (ok) reportMessage(str(R.string.s216, fileName))
                refreshMods()
            } catch (e: Exception) {
                reportError(str(R.string.s217, e.message))
            }
        }
    }

    /** 从本地 Uri 上传模组 */
    fun installModFromUri(uri: Uri) {
        val dirName = activeDirName() ?: return
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) { pluginManager.installModFromUri(uri, dirName) }
                reportMessage(str(R.string.s218, name))
                refreshMods()
            } catch (e: Exception) {
                reportError(str(R.string.s219, e.message))
            }
        }
    }

    /** 从精选库下载安装模组（GitHub 动态解析最新版本） */
    fun installCuratedMod(mod: PluginManager.CuratedMod) {
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s212))
            return
        }
        scope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    pluginManager.resolveLatestAsset(mod.repo, mod.githubAssetPattern)
                } ?: throw RuntimeException(str(R.string.err_parse_latest_url))
                withContext(Dispatchers.IO) { pluginManager.installModFromUrl(url, mod.targetFileName, dirName) }
                reportMessage(str(R.string.s221, mod.name))
                refreshMods()
            } catch (e: Exception) {
                reportError(str(R.string.s222, mod.name, e.message))
            }
        }
    }

    /** 当前核心的 mods 目录路径 */
    fun currentModsPath(): String? {
        val dirName = activeDirName() ?: return null
        return pluginManager.currentModsPath(dirName)
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
