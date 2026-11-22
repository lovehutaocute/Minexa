package com.mineserve.mobile.ui.vm

import android.content.Context
import java.io.File

internal class ConsoleClearMarks(
    private val app: Context,
    private val logFileFor: (String) -> File?
) {
    /**
     * 终端「清除」的落点：日志文件字节偏移，按服务器（dirName）记账。
     *
     * 内存一份给当前进程，SharedPreferences 一份给「杀进程重进」——
     * 用户清完屏关掉 App 再打开，历史日志同样不该复活。
     */
    private val consoleClearMarks = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun terminalPrefs() = app.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)

    fun markConsoleCleared(dirName: String) {
        val length = runCatching {
            logFileFor(dirName)?.length() ?: 0L
        }.getOrDefault(0L)
        consoleClearMarks[dirName] = length
        runCatching { terminalPrefs().edit().putLong(clearMarkKey(dirName), length).apply() }
    }

    fun consoleClearMark(dirName: String): Long =
        consoleClearMarks[dirName] ?: runCatching {
            terminalPrefs().getLong(clearMarkKey(dirName), 0L)
        }.getOrDefault(0L).also { consoleClearMarks[dirName] = it }

    fun dropConsoleClearMark(dirName: String) {
        consoleClearMarks[dirName] = 0L
        runCatching { terminalPrefs().edit().remove(clearMarkKey(dirName)).apply() }
    }
}
