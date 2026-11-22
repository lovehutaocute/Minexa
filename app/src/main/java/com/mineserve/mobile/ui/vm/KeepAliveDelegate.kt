package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.BootReceiver
import com.mineserve.mobile.KeepAliveWorker
import com.mineserve.mobile.R
import com.mineserve.mobile.service.McForegroundService

internal class KeepAliveDelegate(
    private val app: Context,
    private val reportError: (String) -> Unit
) {
    private fun metaPrefs() =
        app.getSharedPreferences(BootReceiver.META_PREFS, android.content.Context.MODE_PRIVATE)

    fun isBootAutoStart(): Boolean = metaPrefs().getBoolean(BootReceiver.KEY_BOOT_AUTO_START, false)

    fun setBootAutoStart(v: Boolean) {
        metaPrefs().edit().putBoolean(BootReceiver.KEY_BOOT_AUTO_START, v).apply()
        if (v) startKeepAliveService() else stopKeepAliveService()
    }

    fun isKeepAliveEnabled(): Boolean = metaPrefs().getBoolean(BootReceiver.KEY_KEEP_ALIVE, true)

    fun setKeepAliveEnabled(v: Boolean) {
        metaPrefs().edit().putBoolean(BootReceiver.KEY_KEEP_ALIVE, v).apply()
        if (v) {
            scheduleKeepAlive()
            // 主心跳：AlarmManager 10 分钟一次（WorkManager 在 Doze/国产 ROM 下会被推迟）。
            // 打开开关后立刻排一次并拉起服务，不必等下一次 15 分钟周期。
            runCatching { com.mineserve.mobile.service.KeepAliveWatchdog.resetRestartGuard(app) }
            runCatching { com.mineserve.mobile.service.KeepAliveWatchdog.schedule(app) }
            startKeepAliveService()
        } else {
            cancelKeepAlive()
            runCatching { com.mineserve.mobile.service.KeepAliveWatchdog.cancel(app) }
        }
    }

    fun startKeepAliveService() {
        try {
            val intent = android.content.Intent(app, McForegroundService::class.java)
                .apply { action = McForegroundService.ACTION_START }
            app.startForegroundService(intent)
        } catch (e: Exception) {
            reportError(app.getString(R.string.s314, e.message))
        }
    }

    fun stopKeepAliveService() {
        try {
            val intent = android.content.Intent(app, McForegroundService::class.java)
                .apply { action = McForegroundService.ACTION_STOP }
            app.startService(intent)
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun scheduleKeepAlive() {
        val request = androidx.work.PeriodicWorkRequestBuilder<KeepAliveWorker>(15, java.util.concurrent.TimeUnit.MINUTES).build()
        androidx.work.WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            "keep_alive", androidx.work.ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    private fun cancelKeepAlive() {
        androidx.work.WorkManager.getInstance(app).cancelUniqueWork("keep_alive")
    }
}
