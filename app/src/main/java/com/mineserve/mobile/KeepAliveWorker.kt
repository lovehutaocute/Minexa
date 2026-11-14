package com.mineserve.mobile

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mineserve.mobile.service.KeepAliveWatchdog
import com.mineserve.mobile.service.McForegroundService

/**
 * 后台周期保活（兜底）：每 15 分钟检查前台服务是否存活，未存活且开启「后台保活」时重新拉起。
 *
 * 它不再是主链路 —— JobScheduler/WorkManager 在 Doze 与国产 ROM 下会被无限期推迟，
 * 主心跳改由 [KeepAliveWatchdog] 的 AlarmManager 承担。这里做两件事：
 * 1. 服务没在跑时拉起；
 * 2. **无论服务在不在跑都重排一次心跳闹钟** —— 用户清掉通知、清后台数据、
 *    系统清理闹钟之后，这条 15 分钟的任务能把闹钟链重新接上。
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val keepAlive = KeepAliveWatchdog.isEnabled(applicationContext)
        if (!keepAlive) return Result.success()
        if (!McForegroundService.isRunning) {
            try {
                applicationContext.startForegroundService(
                    Intent(applicationContext, McForegroundService::class.java).apply {
                        action = McForegroundService.ACTION_START
                    }
                )
            } catch (e: Exception) {
                // 后台启动限制等异常，等待下次周期
                Log.w("KeepAliveWorker", "拉起前台服务失败: ${e.message}")
            }
        }
        // 重建闹钟链（幂等覆盖，不会堆叠）。
        KeepAliveWatchdog.schedule(applicationContext)
        return Result.success()
    }
}
