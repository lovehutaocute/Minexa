package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.server.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class CuratedPluginUpdatesDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val pluginManager: PluginManager,
    private val activeDirName: () -> String?,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _curatedUpdates = MutableStateFlow<Map<String, PluginManager.CuratedUpdateInfo>>(emptyMap())
    val curatedUpdates: StateFlow<Map<String, PluginManager.CuratedUpdateInfo>> = _curatedUpdates.asStateFlow()

    private val _isCheckingUpdates = MutableStateFlow(false)
    val isCheckingUpdates: StateFlow<Boolean> = _isCheckingUpdates.asStateFlow()

    fun checkCuratedUpdates() {
        if (_isCheckingUpdates.value) return
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s212))
            return
        }
        scope.launch {
            _isCheckingUpdates.value = true
            try {
                val installed = pluginManager.scan(dirName)
                val updates = pluginManager.checkCuratedUpdates(installed)
                _curatedUpdates.value = updates.associateBy { it.curated.id }
                val updateCount = updates.count { it.hasUpdate }
                reportMessage(
                    if (updateCount > 0) str(R.string.msg_updates_found, updateCount)
                    else str(R.string.msg_updates_all_latest)
                )
            } catch (e: Exception) {
                reportError(str(R.string.s243, e.message))
            } finally {
                _isCheckingUpdates.value = false
            }
        }
    }

    fun forceRecheckUpdates() {
        pluginManager.invalidateUpdateCache()
        checkCuratedUpdates()
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
