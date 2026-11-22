package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.server.PlayerManager
import com.mineserve.mobile.server.PowerNukkitXConfigManager
import com.mineserve.mobile.server.ServerPropertiesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val onlinePlayerNames: () -> List<String>,
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

    /**
     * 命令发送后延迟刷新，等待 MC 完成回写 JSON 文件
     * @param msg 操作成功消息
     * @param refresh 是否需要刷新列表（kick 不改列表无需刷新）
     */
    private fun afterCmd(sent: Boolean, msg: String, refresh: Boolean = true) {
        if (!sent) {
            reportError(str(R.string.s266))
            return
        }
        reportMessage(msg)
        if (refresh) {
            scope.launch {
                delay(600)  // 等待 MC 处理命令并回写 JSON
                refreshPlayers()
            }
        }
    }

    fun opPlayer(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.opPlayer(name, isBdsActive())
        afterCmd(sent, str(R.string.s1055, name))
    }

    /** 设置 OP 并指定等级（1-4）。手动指定等级需重启服务器才生效到权限系统。 */
    fun opPlayerWithLevel(name: String, level: Int) {
        if (name.isBlank()) return
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s266))
            return
        }
        val bds = isBdsActive()
        scope.launch {
            val sent = withContext(Dispatchers.IO) {
                playerManager.opPlayerWithLevel(name, level, dirName, bds)
            }
            afterCmd(sent, str(R.string.s1056, name, level))
        }
    }

    fun deopPlayer(name: String) {
        if (name.isBlank()) return
        val bds = isBdsActive()
        val dirName = activeDirName()
        // BDS 分支会同步读写 permissions.json（PlayerManager.removeBedrockOperator）；
        // 公共存储是 FUSE，主线程做文件 IO 会卡顿，慢盘上甚至掉帧。
        // 与 opPlayerWithLevel 一致，放到 IO 线程。
        scope.launch {
            val sent = withContext(Dispatchers.IO) { playerManager.deopPlayer(name, dirName, bds) }
            afterCmd(sent, str(R.string.msg_op_cancelled, name))
        }
    }

    fun whitelistAdd(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.whitelistAdd(name, isBdsActive())
        afterCmd(sent, str(R.string.msg_whitelist_added, name))
    }

    fun whitelistRemove(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.whitelistRemove(name, isBdsActive())
        afterCmd(sent, str(R.string.msg_whitelist_removed, name))
    }

    fun kickPlayer(name: String, reason: String = "") {
        if (name.isBlank()) return
        val sent = playerManager.kickPlayer(name, reason)
        afterCmd(sent, str(R.string.msg_kicked, name), refresh = false)
    }

    fun banPlayer(name: String, reason: String = "Banned by admin") {
        if (name.isBlank()) return
        // 官方 BDS 没有封禁命令，也不维护封禁列表，只能靠白名单 + 踢出
        if (isBdsActive()) {
            reportError(str(R.string.bds_players_ban_unsupported))
            return
        }
        val sent = playerManager.banPlayer(name, reason)
        afterCmd(sent, str(R.string.msg_banned, name))
    }

    /** 限时封禁 */
    fun tempBanPlayer(name: String, duration: String, reason: String = "") {
        if (name.isBlank() || duration.isBlank()) return
        if (isBdsActive()) {
            reportError(str(R.string.bds_players_ban_unsupported))
            return
        }
        val sent = playerManager.tempBanPlayer(name, duration, reason)
        afterCmd(sent, str(R.string.msg_banned_temp, name, duration))
    }

    fun pardonPlayer(name: String) {
        if (name.isBlank()) return
        if (isBdsActive()) {
            reportError(str(R.string.bds_players_ban_unsupported))
            return
        }
        val sent = playerManager.pardonPlayer(name)
        afterCmd(sent, str(R.string.msg_pardoned, name))
    }

    /** 请求在线玩家列表（发送 list 命令） */
    fun requestOnlinePlayers() {
        val sent = playerManager.requestOnlineList()
        if (sent) {
            reportMessage(str(R.string.s279))
        } else {
            reportError(str(R.string.s280))
        }
    }

    /** 设置玩家游戏模式 */
    fun setGameMode(name: String, mode: Int) {
        if (name.isBlank()) return
        // 基岩版只能给**在线**玩家改模式：BDS 没有离线玩家数据文件，
        // 目标不在线时命令会被丢弃（控制台只回 No targets matched selector），
        // 用户点完就是"没反应"。提前说清楚，别让人以为是按钮坏了。
        if (isBdsActive() &&
            onlinePlayerNames().none { it.equals(name, ignoreCase = true) }
        ) {
            reportError(str(R.string.bds_gamemode_needs_online, name))
            return
        }
        val sent = playerManager.setGameMode(name, mode)
        val modeNameRes = when (mode.coerceIn(0, 3)) {
            0 -> R.string.s281; 1 -> R.string.s282; 2 -> R.string.s283; 3 -> R.string.s284; else -> R.string.s281
        }
        afterCmd(sent, str(R.string.s285, name, str(modeNameRes)), refresh = false)
    }

    /** 给玩家经验 */
    fun giveXp(name: String, amount: Int) {
        if (name.isBlank() || amount <= 0) return
        val bds = isBdsActive()
        // 同 setGameMode：BDS 的经验命令只能作用于在线玩家
        if (bds && onlinePlayerNames().none { it.equals(name, ignoreCase = true) }) {
            reportError(str(R.string.bds_gamemode_needs_online, name))
            return
        }
        val sent = playerManager.giveXp(name, amount, bds)
        afterCmd(sent, str(R.string.msg_xp_given, name, amount), refresh = false)
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
