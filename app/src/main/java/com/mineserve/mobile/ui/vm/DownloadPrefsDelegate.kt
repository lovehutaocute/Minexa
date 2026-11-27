package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.DownloadPrefs

internal class DownloadPrefsDelegate(
    private val app: Context,
    private val reportMessage: (String) -> Unit
) {
    fun isMultiThreadDownloadEnabled(): Boolean = DownloadPrefs.isEnabled()

    fun downloadThreadCount(): Int = DownloadPrefs.threadCount()

    fun setMultiThreadDownloadEnabled(enabled: Boolean) {
        DownloadPrefs.setEnabled(enabled)
        reportMessage(app.getString(if (enabled) R.string.msg_mt_enabled else R.string.msg_mt_disabled))
    }

    fun setDownloadThreadCount(count: Int) {
        DownloadPrefs.setThreadCount(count)
    }
}
