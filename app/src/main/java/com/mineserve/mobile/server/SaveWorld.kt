package com.mineserve.mobile.server

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.runtime.TermuxRuntime

/**
 * 「保存世界」的统一入口。
 *
 * Java 版一条 `save-all` 就够；**基岩版没有 `save-all`**，只有
 * `save hold` / `save query` / `save resume`。而且 `hold` 不能单独发：
 * 它的语义是「冻结写入、刷盘，等我把文件拷走再放行」，不跟 `save resume`
 * 世界就从此刻起再也不落盘（真机实测：之后 `save query` 恒报
 * `A previous save has not been completed`，只能手工 resume 恢复）。
 *
 * `resume` 延迟 [RESUME_DELAY_MS] 再发，避免 `hold` 还没走完就收到 `resume`
 * 而报「没有进行中的保存」。
 */
fun TermuxRuntime.sendSaveWorld(core: ServerCore?) {
    val commands = core?.consoleSaveCommands ?: listOf("save-all")
    if (commands.isEmpty()) return
    try {
        sendCommand(commands.first())
    } catch (e: Exception) {
        Log.w("SaveWorld", "发送 ${commands.first()} 失败: ${e.message}")
        return
    }
    commands.drop(1).forEachIndexed { index, cmd ->
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { sendCommand(cmd) }
                .onFailure { Log.w("SaveWorld", "发送 $cmd 失败: ${it.message}") }
        }, RESUME_DELAY_MS * (index + 1))
    }
}

/** `hold` 与 `resume` 之间的间隔：够 BDS 把刷盘做完，又不至于让用户觉得卡住。 */
private const val RESUME_DELAY_MS = 800L
