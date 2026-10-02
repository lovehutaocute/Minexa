package com.mineserve.mobile.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.mineserve.mobile.service.ScheduleAlarmReceiver
import java.util.Calendar

/**
 * 每日定时开/停服闹钟管理。计划存于 McConfig（DataStore），
 * 每次配置变更后调用 [register] 重算下一次触发时间。
 */
object ScheduleManager {
    private const val REQUEST_START = 3001
    private const val REQUEST_STOP = 3002

    const val EXTRA_ACTION = "schedule_action"
    const val ACTION_START_SERVER = "start_server"
    const val ACTION_STOP_SERVER = "stop_server"

    /** 按配置注册/取消两个每日闹钟。幂等：重复调用只是重算下一次触发时间。 */
    fun register(context: Context, config: McConfig) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (config.dailyStartEnabled) {
            scheduleDaily(am, context, REQUEST_START, ACTION_START_SERVER, config.dailyStartHour, config.dailyStartMinute)
        } else {
            cancel(am, context, REQUEST_START)
        }
        if (config.dailyStopEnabled) {
            scheduleDaily(am, context, REQUEST_STOP, ACTION_STOP_SERVER, config.dailyStopHour, config.dailyStopMinute)
        } else {
            cancel(am, context, REQUEST_STOP)
        }
    }

    /**
     * 最佳努力地取当前配置（仅在内存态已就绪时）。
     *
     * 供 [ScheduleAlarmReceiver] 在主线程同步重注册闹钟使用：拿不到就返回 null，
     * 由前台服务在协程里补做，**绝不在主线程触发一次同步读盘**。
     */
    fun latestConfig(context: Context): McConfig? = runCatching {
        com.mineserve.mobile.McApplication.get(context).repository.currentConfigOrNull()
    }.getOrNull()

    private fun scheduleDaily(
        am: AlarmManager, context: Context, requestCode: Int,
        action: String, hour: Int, minute: Int
    ) {
        val triggerAt = nextTrigger(hour, minute)
        val pi = pendingIntent(context, requestCode, action)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            // 系统未授予精确闹钟权限时退化为窗口内触发（约 ±1 分钟）
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 120_000L, pi)
        } catch (e: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancel(am: AlarmManager, context: Context, requestCode: Int) {
        am.cancel(pendingIntent(context, requestCode, ""))
    }

    private fun nextTrigger(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // 今天该时刻已过则触发明天
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun pendingIntent(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, ScheduleAlarmReceiver::class.java).apply {
                putExtra(EXTRA_ACTION, action)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
