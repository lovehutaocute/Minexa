package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.runtime.TermuxRuntime
import com.mineserve.mobile.ui.LogBuffer
import com.mineserve.mobile.ui.TerminalLogProcessor
import com.mineserve.mobile.ui.TerminalSession
import com.mineserve.mobile.ui.TerminalSessionType
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class TerminalSessionsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val termuxRuntime: TermuxRuntime,
    private val isBootstrapped: StateFlow<Boolean>,
    private val isUiVisible: () -> Boolean,
    private val maxLogLines: Int,
    private val logFlushMs: Long,
    private val idleLogFlushMs: Long
) {
    private val terminalMutex = Mutex()
    @Volatile private var interactiveTerminalSessionId: String? = null
    private val _terminalSessions = MutableStateFlow(
        listOf(
            TerminalSession("minecraft", str(R.string.mc_console_label), TerminalSessionType.Minecraft),
            TerminalSession("termux-1", "Termux 1", TerminalSessionType.Termux)
        )
    )
    val terminalSessions: StateFlow<List<TerminalSession>> = _terminalSessions.asStateFlow()
    private val _activeTerminalSessionId = MutableStateFlow("minecraft")
    val activeTerminalSessionId: StateFlow<String> = _activeTerminalSessionId.asStateFlow()

    fun createTerminalSession() {
        val next = (_terminalSessions.value.count { it.type == TerminalSessionType.Termux } + 1)
        val id = "termux-${System.nanoTime()}"
        _terminalSessions.update { it + TerminalSession(id, "Termux $next", TerminalSessionType.Termux) }
        _activeTerminalSessionId.value = id
    }

    fun selectTerminalSession(id: String) { _activeTerminalSessionId.value = id }

    fun closeTerminalSession(id: String) {
        val session = _terminalSessions.value.firstOrNull { it.id == id } ?: return
        if (session.type == TerminalSessionType.Minecraft) return
        // 用 update 原子读改写：终端刷新协程（Dispatchers.Default）会对同一 StateFlow 做 map 写回，
        // 原先「读 remaining → 整条写回」的跨线程读改写会互相覆盖，导致新建会话整条消失或本轮输出被丢。
        var nextActive: String? = null
        _terminalSessions.update { list ->
            val remaining = list.filterNot { it.id == id }
            if (remaining.none { it.type == TerminalSessionType.Termux }) {
                val fallback = TerminalSession("termux-${System.nanoTime()}", "Termux 1", TerminalSessionType.Termux)
                nextActive = fallback.id
                remaining + fallback
            } else {
                if (_activeTerminalSessionId.value == id) nextActive = remaining.first().id
                remaining
            }
        }
        // 回收该会话的输出缓冲：此前只有 clearTerminalSession 会清，反复新建/关闭会让条目永久残留
        synchronized(terminalOutputBuffers) { terminalOutputBuffers.remove(id) }
        nextActive?.let { _activeTerminalSessionId.value = it }
    }

    fun executeTerminalCommand(id: String, command: String) {
        if (command.isBlank()) return
        val session = _terminalSessions.value.firstOrNull { it.id == id } ?: return
        if (session.type != TerminalSessionType.Termux || !isBootstrapped.value) return
        scope.launch {
            terminalMutex.withLock {
                updateTerminalSession(id) { it.copy(busy = true) }
                enqueueTerminalLine(id, "$ $command")
                interactiveTerminalSessionId = id
                try {
                    withContext(Dispatchers.IO) { termuxRuntime.refreshTermux() }
                    val exit = withContext(Dispatchers.IO) {
                        termuxRuntime.execTermux(command) { line -> enqueueTerminalLine(id, line) }
                    }
                    if (exit != 0) enqueueTerminalLine(id, str(R.string.term_exit_code, exit))
                } catch (e: Exception) {
                    enqueueTerminalLine(id, str(R.string.term_exec_error, e.message))
                } finally {
                    interactiveTerminalSessionId = null
                    updateTerminalSession(id) { it.copy(busy = false) }
                }
            }
        }
    }

    /** 向当前 Termux 会话中的命令发送一行 stdin。 */
    fun sendTerminalInput(id: String, input: String) {
        if (input.isBlank() || interactiveTerminalSessionId != id) return
        if (termuxRuntime.sendTermuxInput(input)) {
            enqueueTerminalLine(id, "> $input")
        }
    }

    /** 清空指定终端会话的显示行（保留会话本身） */
    fun clearTerminalSession(id: String) {
        synchronized(terminalOutputBuffers) { terminalOutputBuffers.remove(id) }
        updateTerminalSession(id) { it.copy(lines = kotlinx.collections.immutable.persistentListOf()) }
    }

    private fun updateTerminalSession(id: String, transform: (TerminalSession) -> TerminalSession) {
        // update 原子读改写：调用方既有 Dispatchers.Default 的行刷新循环，也有主线程的会话增删，
        // 直接 `value = value.map{}` 会跨线程丢更新（新建的会话整条消失 / 本轮终端输出被丢弃）。
        _terminalSessions.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private val terminalOutputBuffers = mutableMapOf<String, LogBuffer<String>>()

    private fun enqueueTerminalLine(id: String, line: String) {
        synchronized(terminalOutputBuffers) {
            terminalOutputBuffers.getOrPut(id) { LogBuffer(maxLogLines) }.add(line)
        }
    }

    fun startOutputFlush() {
        scope.launch(Dispatchers.Default) {
            while (true) {
                // 终端会话面板不可见时降频；界面不在前台（uiVisible=false）同样降频。
                val terminalVisible = _terminalSessions.subscriptionCount.value > 0 ||
                    _activeTerminalSessionId.subscriptionCount.value > 0
                if (!isUiVisible() || !terminalVisible) {
                    delay(idleLogFlushMs)
                    continue
                }
                delay(logFlushMs)
                val batch = synchronized(terminalOutputBuffers) {
                    terminalOutputBuffers.mapValues { (_, buffer) -> buffer.snapshotAndClear() }
                        .filterValues { it.isNotEmpty() }
                }
                batch.forEach { (id, lines) ->
                    updateTerminalSession(id) { session ->
                        val additions = lines.map { TerminalLogProcessor.process(it, false) }
                        session.copy(lines = (session.lines + additions).takeLast(maxLogLines).toImmutableList())
                    }
                }
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
