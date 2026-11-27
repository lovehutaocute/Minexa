package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R

internal class ConsoleEntryDelegate(
    private val app: Context,
    private val reportMessage: (String) -> Unit
) {
    /** 打开 MC 终端前初始化日志 */
    fun launchMcConsole() {
        // 确保终端日志色标已处理
        reportMessage(app.getString(R.string.s303))
    }
}
