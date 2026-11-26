package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.server.PlayerNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PlayerPresenceDelegate(
    private val updateOnlinePlayerCount: (Boolean) -> Unit,
    private val isServerRunning: () -> Boolean,
    private val requestOnlineList: () -> Unit
) {
    private val _onlinePlayerNames = MutableStateFlow<List<String>>(emptyList())
    val onlinePlayerNames: StateFlow<List<String>> = _onlinePlayerNames.asStateFlow()

    fun setPlayerOnline(name: String, joined: Boolean): Boolean {
        val current = _onlinePlayerNames.value
        val next = if (joined) PlayerNames.mergeInto(current, name) else PlayerNames.removeFrom(current, name)
        if (next === current) return false
        _onlinePlayerNames.value = next
        updateOnlinePlayerCount(joined)
        return true
    }

    fun replaceOnlinePlayerNames(names: List<String>) {
        _onlinePlayerNames.value = names
    }

    fun clearOnlinePlayerNames() {
        _onlinePlayerNames.value = emptyList()
    }

    fun refreshOnlinePlayers() {
        if (!isServerRunning()) return
        requestOnlineList()
    }
}
