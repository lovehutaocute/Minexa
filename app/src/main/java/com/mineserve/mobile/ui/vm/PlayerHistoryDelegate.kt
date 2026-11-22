package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.ui.McViewModel.PlayerActivitySummary
import com.mineserve.mobile.ui.McViewModel.PlayerHistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class PlayerHistoryDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val activeDirName: () -> String?,
    private val timeNow: () -> String,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _playerHistory: MutableStateFlow<List<PlayerHistoryEntry>> by lazy { MutableStateFlow(emptyList()) }
    val playerHistory: StateFlow<List<PlayerHistoryEntry>> by lazy { _playerHistory.asStateFlow() }

    private val legacyPlayerHistoryFile: java.io.File get() = java.io.File(app.filesDir, "player_history.json")
    private fun playerHistoryFile() = java.io.File(app.filesDir, "player-history/${activeDirName() ?: "unselected"}.json")

    private val historyJson: Json by lazy { Json { ignoreUnknownKeys = true } }

    /** 历史记录文件读写互斥，避免多人进出服时并发写导致 JSON 损坏 */
    private val historyMutex: Mutex by lazy { Mutex() }

    /** 启动时异步加载历史记录文件（文件缺失/损坏时从空历史开始） */
    fun loadPlayerHistory() {
        // Activity data is isolated by active server. Do not merge a previous server's snapshot.
        _playerHistory.value = emptyList()
        scope.launch(Dispatchers.IO) {
            historyMutex.withLock {
                try {
                    val f = playerHistoryFile()
                    if (!f.exists() && legacyPlayerHistoryFile.exists()) {
                        f.parentFile?.mkdirs()
                        legacyPlayerHistoryFile.copyTo(f, overwrite = false)
                    }
                    if (f.exists()) {
                        val fileList = historyJson.decodeFromString<List<PlayerHistoryEntry>>(f.readText())
                        val merged = fileList.distinct().sortedByDescending { it.time }
                        _playerHistory.value = merged
                        // A process restart has no trustworthy leave timestamp. Preserve it as interrupted.
                        val interrupted = merged.map { if (it.event == "进服" && it.sessionEnd == null) it.copy(interrupted = true) else it }
                        _playerHistory.value = interrupted
                        f.writeText(historyJson.encodeToString(interrupted))
                    }
                } catch (e: Exception) {
                    // 忽略损坏文件
                }
            }
        }
    }

    /** 追加一条进服/离服事件并异步持久化（保留最近 500 条） */
    fun recordPlayerEvent(player: String, event: String) {
        val now = System.currentTimeMillis()
        val previous = _playerHistory.value
        val open = previous.firstOrNull { it.player.equals(player, true) && it.event == "进服" && it.sessionEnd == null && !it.interrupted }
        val entry = if (event == "离服" && open != null) {
            PlayerHistoryEntry(player, event, timeNow(), open.sessionStart ?: now, now)
        } else {
            PlayerHistoryEntry(player, event, timeNow(), if (event == "进服") now else null, null)
        }
        _playerHistory.value = listOf(entry) + previous.map {
            if (event == "离服" && it == open) it.copy(sessionEnd = now) else it
        }
        val snapshot = _playerHistory.value
        scope.launch(Dispatchers.IO) {
            historyMutex.withLock {
                try {
                    val file = playerHistoryFile(); file.parentFile?.mkdirs()
                    file.writeText(historyJson.encodeToString(snapshot))
                } catch (e: Exception) {
                    // 写入失败不阻断运行
                }
            }
        }
    }

    fun playerActivitySummaries(): List<PlayerActivitySummary> {
        val entries = _playerHistory.value
        return entries.groupBy { it.player }.map { (player, rows) ->
            val seconds = rows.filter { it.event == "离服" && !it.interrupted }.sumOf { ((it.sessionEnd ?: 0L) - (it.sessionStart ?: 0L)).coerceAtLeast(0L) / 1000 }
            val lastJoin = rows.filter { it.event == "进服" }.maxByOrNull { it.time }
            val lastLeave = rows.filter { it.event == "离服" }.maxByOrNull { it.time }
            PlayerActivitySummary(
                player = player,
                sessions = rows.count { it.event == "离服" },
                totalSeconds = seconds,
                active = rows.any { it.event == "进服" && it.sessionEnd == null },
                lastJoinTime = lastJoin?.time,
                lastLeaveTime = lastLeave?.time,
                lastExitReason = lastLeave?.let {
                    if (it.interrupted) str(R.string.vm_exit_interrupted) else str(R.string.vm_exit_unknown)
                }
            )
        }.sortedByDescending { it.totalSeconds }
    }

    fun interruptPlayerSessions() {
        val open = _playerHistory.value.filter { it.event == "进服" && it.sessionEnd == null }
        if (open.isEmpty()) return
        val replacements = open.associateWith { it.copy(interrupted = true) }
        _playerHistory.value = _playerHistory.value.map { replacements[it] ?: it }
        val snapshot = _playerHistory.value
        scope.launch(Dispatchers.IO) { historyMutex.withLock {
            try {
                val file = playerHistoryFile(); file.parentFile?.mkdirs(); file.writeText(historyJson.encodeToString(snapshot))
            } catch (e: Exception) {
                // 写入失败不阻断运行（与 recordPlayerEvent 一致）：viewModelScope 无 CoroutineExceptionHandler，
                // 磁盘满/目录不可写时异常逸出协程会直接崩溃 App。
            }
        } }
    }

    fun clearPlayerHistory() {
        _playerHistory.value = emptyList()
        scope.launch(Dispatchers.IO) { historyMutex.withLock {
            try {
                playerHistoryFile().delete()
            } catch (e: Exception) {
                // 删除失败不阻断运行
            }
        } }
    }

    fun exportPlayerHistory(uri: android.net.Uri) = scope.launch {
        try {
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
                    out.appendLine("player,event,time,session_start,session_end,duration_seconds,interrupted")
                    _playerHistory.value.forEach { row ->
                        fun csv(v: String) = "\"${v.replace("\"", "\"\"")}\""
                        val duration = if (row.sessionStart != null && row.sessionEnd != null && !row.interrupted) ((row.sessionEnd - row.sessionStart) / 1000).toString() else ""
                        out.appendLine(listOf(csv(row.player), csv(row.event), csv(row.time), row.sessionStart ?: "", row.sessionEnd ?: "", duration, row.interrupted).joinToString(","))
                    }
                } ?: error(str(R.string.err_open_export))
            }
            reportMessage(str(R.string.msg_history_exported))
        } catch (e: Exception) { reportError(e.message ?: str(R.string.err_export_fail)) }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
