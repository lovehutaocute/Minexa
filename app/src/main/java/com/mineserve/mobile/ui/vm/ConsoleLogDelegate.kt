package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.runtime.TermuxRuntime
import com.mineserve.mobile.ui.LogBuffer
import com.mineserve.mobile.ui.TerminalDisplayLine
import com.mineserve.mobile.ui.TerminalLogProcessor
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ConsoleLogDelegate(
    app: Context,
    private val scope: CoroutineScope,
    private val termuxRuntime: TermuxRuntime,
    private val activeDirName: () -> String?,
    private val isUiVisible: () -> Boolean,
    private val maxLogLines: Int,
    private val idleLogFlushMs: Long
) {
    private val _consoleLines = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val consoleLines: StateFlow<ImmutableList<String>> = _consoleLines.asStateFlow()

    /** 终端专用的后台预处理日志，避免 Compose 主线程翻译和关键词扫描。 */
    private val _terminalConsoleLines = MutableStateFlow<ImmutableList<TerminalDisplayLine>>(persistentListOf())
    val terminalConsoleLines: StateFlow<ImmutableList<TerminalDisplayLine>> = _terminalConsoleLines.asStateFlow()
    private val _logTranslationEnabled = MutableStateFlow(true)
    val logTranslationEnabled: StateFlow<Boolean> = _logTranslationEnabled.asStateFlow()

    /** Small, stable preview for cards that only show the latest few server messages. */
    private val _consolePreviewLines = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val consolePreviewLines: StateFlow<ImmutableList<String>> = _consolePreviewLines.asStateFlow()

    @Volatile private var terminalHistoryLoading = false

    /** 控制台行环形缓冲，避免每行 O(n) 拷贝 */
    private val consoleBuffer = LogBuffer<String>(maxLogLines)
    private val pendingConsoleBuffer = LogBuffer<String>(maxLogLines)
    private val terminalConsoleBuffer = LogBuffer<TerminalDisplayLine>(maxLogLines)
    @Volatile private var terminalDisplayDirty = false
    private var consoleGeneration = 0L

    /**
     * 「用户点了清除」的代际，**只在 [clearConsole] 里自增**。
     *
     * 与 [consoleGeneration] 分开是必要的：后者每收到一批实时日志都会自增，
     * 用它判定「读盘期间是否被清除」会让历史补读几乎每次都被丢弃。
     */
    @Volatile private var consoleClearGeneration = 0L
    private var terminalGeneration = 0L
    private var consoleUiGeneration = -1L
    private var terminalUiGeneration = -1L
    private var previewUiGeneration = -1L
    private var lastPreviewPublishedAtMs = 0L

    fun setLogTranslationEnabled(enabled: Boolean) {
        _logTranslationEnabled.value = enabled
        terminalDisplayDirty = true
    }

    /** 清空 MC 控制台缓冲 */
    fun clearConsole() {
        // 只清内存是不够的：[loadRecentTerminalLog] 每次进终端页都会从磁盘日志
        // 补读几百行，刚清掉的内容会原样回到屏幕上 —— 用户看到的是「清除没生效」。
        // 这里记下清除时刻的文件长度，下次只补读这之后的新增。
        activeDirName()?.let { consoleClearMarks.markConsoleCleared(it) }
        consoleBuffer.clear()
        pendingConsoleBuffer.clear()
        terminalConsoleBuffer.clear()
        consoleClearGeneration++
        consoleGeneration++
        terminalGeneration++
        consoleUiGeneration = consoleGeneration
        terminalUiGeneration = terminalGeneration
        previewUiGeneration = consoleGeneration
        _consoleLines.value = persistentListOf()
        _consolePreviewLines.value = persistentListOf()
        _terminalConsoleLines.value = persistentListOf()
    }

    private val consoleClearMarks by lazy {
        ConsoleClearMarks(app) { dirName -> termuxRuntime.mcLogFileFor(dirName) }
    }

    /** 进入 MC 终端时异步补读磁盘日志，实时输出仍由 consoleFlow 持续追加。 */
    fun loadRecentTerminalLog() {
        if (terminalHistoryLoading) return
        val dirName = activeDirName() ?: return
        terminalHistoryLoading = true
        scope.launch {
            try {
                // 用「清除代际」而不是 consoleGeneration：后者每收到一批实时日志都会自增
                // （见下方刷新循环），服务端在跑时历史补读一返回就必然命中 `!=` 被整段丢弃，
                // 表现为「App 重启后进终端看不到历史，只有新追加的行」。
                val clearGen = consoleClearGeneration
                val mark = consoleClearMarks.consoleClearMark(dirName)
                val history = withContext(Dispatchers.IO) {
                    val fileLength = runCatching {
                        termuxRuntime.mcLogFileFor(dirName)?.length() ?: 0L
                    }.getOrDefault(0L)
                    // 文件比清除落点还短：latest.log 已被新一轮运行覆盖，旧落点失效，
                    // 此时整份读才符合「新的一轮日志」的预期。
                    val offset = if (fileLength < mark) 0L else mark
                    if (offset == 0L && mark != 0L) consoleClearMarks.dropConsoleClearMark(dirName)
                    termuxRuntime.readRecentMcLog(dirName, fromOffset = offset)
                }
                // 读盘期间用户可能点了「清除」：此时写回历史等于把清除撤销掉。
                if (consoleClearGeneration != clearGen) return@launch
                if (history.isNotEmpty()) {
                    val live = consoleBuffer.snapshot()
                    val overlap = (minOf(history.size, live.size) downTo 1).firstOrNull { size ->
                        history.takeLast(size) == live.take(size)
                    } ?: 0
                    val merged = (history + live.drop(overlap)).takeLast(maxLogLines)
                    consoleBuffer.replace(merged)
                    terminalConsoleBuffer.replace(
                        merged.map { TerminalLogProcessor.process(it, _logTranslationEnabled.value) }
                    )
                    consoleGeneration++
                    terminalGeneration++
                    _consoleLines.value = consoleBuffer.snapshot()
                    _terminalConsoleLines.value = terminalConsoleBuffer.snapshot()
                    consoleUiGeneration = consoleGeneration
                    terminalUiGeneration = terminalGeneration
                }
            } finally {
                terminalHistoryLoading = false
            }
        }
    }

    fun appendLine(line: String) {
        consoleBuffer.add(line)
        pendingConsoleBuffer.add(line)
    }

    fun startOutputFlush() {
        // Process only newly received lines. Older terminal rows keep their identity across UI flushes.
        scope.launch(Dispatchers.Default) {
            while (true) {
                // 无 UI 订阅（后台/无页面），或界面不可见（Activity 已停止但 collectAsState 不销毁、订阅数仍 >0）
                // 时长时间睡眠；控制台可见时以帧友好的频率刷新。
                if (!isUiVisible() ||
                    (_consoleLines.subscriptionCount.value <= 0 &&
                        _consolePreviewLines.subscriptionCount.value <= 0 &&
                        _terminalConsoleLines.subscriptionCount.value <= 0)
                ) {
                    delay(idleLogFlushMs)
                    continue
                }
                delay(200)
                val batch = pendingConsoleBuffer.snapshotAndClear()
                if (batch.isNotEmpty()) {
                    consoleGeneration++
                }
                if (terminalDisplayDirty) {
                    terminalConsoleBuffer.replace(
                        consoleBuffer.snapshot().map { TerminalLogProcessor.process(it, _logTranslationEnabled.value) }
                    )
                    terminalDisplayDirty = false
                    terminalGeneration++
                } else if (batch.isNotEmpty()) {
                    batch.forEach { terminalConsoleBuffer.add(TerminalLogProcessor.process(it, _logTranslationEnabled.value)) }
                    terminalGeneration++
                }
                if (_consoleLines.subscriptionCount.value > 0 && consoleUiGeneration != consoleGeneration) {
                    _consoleLines.value = consoleBuffer.snapshot()
                    consoleUiGeneration = consoleGeneration
                }
                if (_terminalConsoleLines.subscriptionCount.value > 0 && terminalUiGeneration != terminalGeneration) {
                    _terminalConsoleLines.value = terminalConsoleBuffer.snapshot()
                    terminalUiGeneration = terminalGeneration
                }
                val now = System.currentTimeMillis()
                if (_consolePreviewLines.subscriptionCount.value > 0 &&
                    previewUiGeneration != consoleGeneration &&
                    now - lastPreviewPublishedAtMs >= CONSOLE_PREVIEW_FLUSH_MS
                ) {
                    _consolePreviewLines.value = consoleBuffer.last(8)
                    previewUiGeneration = consoleGeneration
                    lastPreviewPublishedAtMs = now
                }
            }
        }
    }
}

private const val CONSOLE_PREVIEW_FLUSH_MS = 750L
