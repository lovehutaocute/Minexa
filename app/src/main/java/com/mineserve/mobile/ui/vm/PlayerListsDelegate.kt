package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.server.PlayerManager
import com.mineserve.mobile.server.PowerNukkitXConfigManager
import com.mineserve.mobile.server.ServerPropertiesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PlayerListsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val playerManager: PlayerManager,
    private val propertiesManager: ServerPropertiesManager,
    private val powerNukkitXConfigManager: PowerNukkitXConfigManager,
    private val activeDirName: () -> String?,
    private val isBdsActive: () -> Boolean,
    private val isPowerNukkitXActive: () -> Boolean,
    private val serverProperties: () -> Map<String, String>,
    private val setWhitelistProperty: (String, Boolean) -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _ops = MutableStateFlow<List<PlayerManager.OpEntry>>(emptyList())
    val ops: StateFlow<List<PlayerManager.OpEntry>> = _ops.asStateFlow()

    private val _whitelist = MutableStateFlow<List<PlayerManager.WhitelistEntry>>(emptyList())
    val whitelist: StateFlow<List<PlayerManager.WhitelistEntry>> = _whitelist.asStateFlow()

    private val _bannedPlayers = MutableStateFlow<List<PlayerManager.BannedEntry>>(emptyList())
    val bannedPlayers: StateFlow<List<PlayerManager.BannedEntry>> = _bannedPlayers.asStateFlow()

    /** 白名单开关状态（由 server.properties 的 white-list 控制） */
    private val _whitelistEnabled = MutableStateFlow(false)
    val whitelistEnabled: StateFlow<Boolean> = _whitelistEnabled.asStateFlow()

    /** 当前 server.properties 配置的默认 OP 等级（null 表示未配置，使用 MC 默认 4） */
    private val _defaultOpLevel = MutableStateFlow<Int?>(null)
    val defaultOpLevel: StateFlow<Int?> = _defaultOpLevel.asStateFlow()

    /** 刷新玩家数据（OP/白名单/封禁列表） */
    fun refreshPlayers() {
        val dirName = activeDirName() ?: return
        // 官方 BDS 的玩家数据文件与 Java 版完全不同：
        //  - OP → permissions.json（permission=operator，只有 xuid）
        //  - 白名单 → allowlist.json
        //  - 没有封禁文件
        // 所以整条读取链都要按核心分别走，否则 BDS 下三个列表永远为空。
        val bds = isBdsActive()
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (bds) playerManager.loadBedrockXuidNames(dirName)
                    _ops.value = playerManager.readOps(dirName, bds)
                    _whitelist.value = playerManager.readWhitelist(dirName, bds)
                    _bannedPlayers.value = playerManager.readBanned(dirName, bds)
                    if (bds) playerManager.saveBedrockXuidNames(dirName)
                    // 同步白名单开关状态（从 server.properties 读取；BDS 的键名是 allow-list）
                    val props = if (isPowerNukkitXActive()) powerNukkitXConfigManager.read(dirName)
                    else propertiesManager.readProperties(dirName)
                    _whitelistEnabled.value =
                        props[if (bds) "allow-list" else "white-list"]?.equals("true", ignoreCase = true) == true
                    // 同步默认 OP 等级（op-permission-level）；BDS 没有等级概念
                    _defaultOpLevel.value = if (bds) null else props["op-permission-level"]?.toIntOrNull()
                }
            } catch (e: Exception) {
                reportError(str(R.string.s265, e.message))
            }
        }
    }

    /** 切换白名单开关 */
    fun toggleWhitelist(enabled: Boolean) {
        val bds = isBdsActive()
        val dirName = activeDirName()
        scope.launch {
            // 官方 BDS 的硬约束：关闭正版验证（online-mode=false）时不允许开白名单，
            // 服务端启动时会直接报错并退出。这里先读盘确认，别让用户把服务器按死。
            if (bds && enabled) {
                val onlineMode = withContext(Dispatchers.IO) {
                    dirName?.let { propertiesManager.readProperties(it)["online-mode"] }?.equals("true", true)
                } ?: (serverProperties()["online-mode"]?.equals("true", true) == true)
                if (!onlineMode) {
                    _whitelistEnabled.value = false
                    reportError(str(R.string.bds_players_allowlist_needs_online_mode))
                    return@launch
                }
            }
            val sent = if (enabled) playerManager.whitelistOn(bds) else playerManager.whitelistOff(bds)
            if (sent) {
                _whitelistEnabled.value = enabled
                // 同步镜像：概览页的白名单提示读的是 [serverProperties]，只改 _whitelistEnabled
                // 的话要等下一次读配置文件才会更新，提示会慢半拍。
                val key = if (bds) "allow-list" else "white-list"
                setWhitelistProperty(key, enabled)
                reportMessage(if (enabled) str(R.string.msg_whitelist_toggled_on) else str(R.string.msg_whitelist_toggled_off))
            } else {
                reportError(str(R.string.s274))
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
