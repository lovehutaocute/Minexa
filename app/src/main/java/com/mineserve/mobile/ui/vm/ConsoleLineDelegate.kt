package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.data.ServerEventNotifier
import com.mineserve.mobile.runtime.ConsoleLineParser
import com.mineserve.mobile.server.PlayerManager
import kotlinx.coroutines.flow.StateFlow

internal class ConsoleLineDelegate(
    private val app: Context,
    private val config: StateFlow<McConfig>,
    private val repo: ServerRepository,
    private val playerManager: PlayerManager,
    private val replaceOnlinePlayerNames: (List<String>) -> Unit,
    private val setPlayerOnline: (String, Boolean) -> Boolean,
    private val recordPlayerEvent: (String, String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private var lastJavaCompatibilityWarning: String? = null
    private var bdsListExpected = 0
    private val bdsListBuffer = mutableListOf<String>()
    fun resetJavaCompatibilityWarning() {
        lastJavaCompatibilityWarning = null
    }
    fun parseConsoleLine(line: String) {
        try {
            val signals = ConsoleLineParser.parse(line)
            val startupPhase = signals.startupPhase
            updateStartupPhaseFromLog(startupPhase)
            val requiredJava = signals.requiredJavaVersion
            if (requiredJava != null) {
                val selected = when (config.value.selectedJavaVersion) {
                    JavaVersion.Java8 -> 8
                    JavaVersion.Java11 -> 11
                    JavaVersion.Java17 -> 17
                    JavaVersion.Java21 -> 21
                    JavaVersion.Java25 -> 25
                }
                if (selected < requiredJava) {
                    val core = config.value.installedCores
                        .firstOrNull { it.name == config.value.activeCoreName }?.core?.displayName
                        ?: str(R.string.diag_core_unknown_name)
                    val warning = str(R.string.msg_java_warning, core, selected, requiredJava)
                    if (warning != lastJavaCompatibilityWarning) {
                        lastJavaCompatibilityWarning = warning
                        reportError(str(R.string.err_java_unsupported, warning))
                    }
                }
            }
            var handled = false
            val bdsEvent = playerManager.extractBedrockPlayerEvent(line)
            when {
                bdsEvent != null -> {
                    val name = bdsEvent.name
                    setPlayerOnline(name, bdsEvent.joined)
                    playerManager.noteBedrockPlayer(name, bdsEvent.xuid)
                    recordPlayerEvent(name, if (bdsEvent.joined) "进服" else "离服")
                    notifyPlayerEvent(name, bdsEvent.joined)
                    handled = true
                }
                playerManager.extractPowerNukkitPlayerEvent(line) != null -> {
                    val (name, joined) = playerManager.extractPowerNukkitPlayerEvent(line)!!
                    setPlayerOnline(name, joined)
                    recordPlayerEvent(name, if (joined) "进服" else "离服")
                    notifyPlayerEvent(name, joined)
                    handled = true
                }
                line.contains("joined the game") -> {
                    playerManager.extractPlayerName(line)?.let { name ->
                        setPlayerOnline(name, true)
                        recordPlayerEvent(name, "进服")
                        notifyPlayerEvent(name, true)
                    }
                    handled = true
                }
                line.contains("left the game") -> {
                    playerManager.extractPlayerName(line)?.let { name ->
                        setPlayerOnline(name, false)
                        recordPlayerEvent(name, "离服")
                        notifyPlayerEvent(name, false)
                    }
                    handled = true
                }
            }
            if (!handled && signals.players != null) {
                val p = signals.players
                repo.updateServerState { it.copy(onlinePlayers = p.online, maxPlayers = p.max) }
                markServerReady()
                playerManager.parseOnlinePlayers(line, p.online)?.let { names ->
                    replaceOnlinePlayerNames(names)
                }
                if (p.online > 0 && line.contains("players online:") && !line.contains(" of a max of ")) {
                    bdsListExpected = p.online
                    synchronized(bdsListBuffer) { bdsListBuffer.clear() }
                }
                handled = true
            }
            if (!handled && signals.tps != null) {
                val value = signals.tps
                val health = ((value / 20.0) * 100).toInt().coerceIn(0, 100)
                repo.updateServerState {
                    it.copy(tps = value, healthPercent = health, maxMemoryMb = config.value.maxHeapMb.toLong())
                }
                handled = true
            }
            if (!handled && bdsListExpected > 0) {
                val entry = playerManager.parseBedrockListEntry(line)
                if (entry == null) {
                    bdsListExpected = 0
                } else {
                    val done = synchronized(bdsListBuffer) {
                        bdsListBuffer.add(entry)
                        if (bdsListBuffer.size >= bdsListExpected) bdsListBuffer.toList() else null
                    }
                    if (done != null) {
                        bdsListExpected = 0
                        replaceOnlinePlayerNames(done)
                        repo.updateServerState { it.copy(onlinePlayers = done.size) }
                    }
                }
            }
            if (!handled && startupPhase == StartupPhase.Ready) markServerReady()
        } catch (_: Exception) {
            // 解析失败不影响正常运行
        }
    }
    private fun markServerReady() {
        var becameReady = false
        repo.updateServerState { state ->
            if (!state.isRunning) return@updateServerState state
            becameReady = state.runningSinceMs == 0L
            state.copy(
                tps = 20.0,
                healthPercent = 100,
                maxMemoryMb = config.value.maxHeapMb.toLong(),
                startupPhase = StartupPhase.Ready,
                runningSinceMs = state.runningSinceMs.takeIf { it > 0L }
                    ?: android.os.SystemClock.elapsedRealtime()
            )
        }
        if (becameReady && repo.termuxRuntime.isMcRunning()) playerManager.requestOnlineList()
        if (becameReady) {
            ServerEventNotifier.notify(
                app,
                str(R.string.notif_server_started_title),
                str(R.string.notif_server_started_text),
                ServerEventNotifier.ID_STARTED, 1
            )
        }
    }
    private fun notifyPlayerEvent(name: String, joined: Boolean) {
        ServerEventNotifier.notify(
            app,
            if (joined) str(R.string.notif_player_joined_title, name) else str(R.string.notif_player_left_title, name),
            if (joined) str(R.string.notif_player_joined_text) else str(R.string.notif_player_left_text),
            ServerEventNotifier.ID_PLAYER, 2
        )
    }
    private fun updateStartupPhaseFromLog(phase: StartupPhase?) {
        phase ?: return
        repo.updateServerState { state ->
            if (!state.isRunning || state.startupPhase == StartupPhase.Ready) return@updateServerState state
            if (phase.progress <= state.startupPhase.progress) return@updateServerState state
            state.copy(startupPhase = phase)
        }
    }
    private fun str(id: Int, vararg args: Any?): String = app.getString(id, *args)
}
