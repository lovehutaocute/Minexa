package com.mineserve.mobile.ui.vm

import android.content.Context
import android.net.Uri
import com.mineserve.mobile.R
import com.mineserve.mobile.server.PluginManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class PluginManagementDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val pluginManager: PluginManager,
    private val activeDirName: () -> String?,
    private val serverDirFor: (String) -> File,
    private val requireServerStopped: (String) -> Boolean,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _installedPlugins = MutableStateFlow<List<PluginManager.InstalledPlugin>>(emptyList())
    val installedPlugins: StateFlow<List<PluginManager.InstalledPlugin>> = _installedPlugins.asStateFlow()

    fun refreshInstalledPlugins() {
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s212))
            return
        }
        scope.launch {
            try {
                _installedPlugins.value = pluginManager.scan(dirName)
            } catch (e: Exception) {
                reportError(str(R.string.s213, e.message))
            }
        }
    }

    fun installPluginFromUri(uri: Uri) {
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s212))
            return
        }
        scope.launch {
            try {
                val fileName = pluginManager.installFromUri(uri, dirName)
                reportMessage(str(R.string.s231, fileName))
                refreshInstalledPlugins()
            } catch (e: Exception) {
                reportError(str(R.string.s232, e.message))
            }
        }
    }

    fun deletePlugin(fileName: String, alsoRemoveDataDir: Boolean = false) {
        if (!requireServerStopped("删除插件")) return
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s212))
            return
        }
        scope.launch {
            try {
                val ok = pluginManager.delete(fileName, dirName, alsoRemoveDataDir)
                if (ok) {
                    val message = if (alsoRemoveDataDir) {
                        str(R.string.msg_deleted_with_data, fileName)
                    } else {
                        str(R.string.msg_deleted, fileName)
                    }
                    reportMessage(message)
                    refreshInstalledPlugins()
                } else {
                    reportError(str(R.string.s235))
                }
            } catch (e: Exception) {
                reportError(str(R.string.s236, e.message))
            }
        }
    }

    fun togglePluginEnabled(fileName: String) {
        if (!requireServerStopped("启用或停用插件")) return
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s212))
            return
        }
        scope.launch {
            try {
                val newName = pluginManager.toggleEnabled(fileName, dirName)
                if (newName != null) {
                    val action = if (newName.startsWith("-")) str(R.string.msg_disabled) else str(R.string.msg_enabled)
                    reportMessage("$action $fileName")
                    refreshInstalledPlugins()
                } else {
                    reportError(str(R.string.s239))
                }
            } catch (e: Exception) {
                reportError(str(R.string.s240, e.message))
            }
        }
    }

    fun isCuratedPluginInstalled(curated: PluginManager.CuratedPlugin): Boolean =
        pluginManager.isCuratedInstalled(curated, _installedPlugins.value)

    fun currentPluginsPath(): String? {
        val dirName = activeDirName() ?: return null
        return File(serverDirFor(dirName), "plugins").absolutePath
    }

    private fun str(id: Int, vararg args: Any?): String = app.getString(id, *args)
}
