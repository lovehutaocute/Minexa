package com.mineserve.mobile.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.mineserve.mobile.BootReceiver

/**
 * 保活心跳：用 AlarmManager 周期把 [McForegroundService] 拉起来。
 *
 * ## 为什么不能只靠 WorkManager
 *
 * 旧实现只有一条 WorkManager 周期任务（15 分钟）当重启链路。它在国产 ROM 上
 * 基本不可靠：进入 Doze / 应用被冻结后 JobScheduler 会被无限期推迟，而
 * 「划掉卡片 + 锁屏 10 分钟」正是用户反馈的现象。闹钟在 Doze 下仍是**唯一**
 * 会被系统唤醒应用的机制，所以这里把它做成主心跳，WorkManager 降级为兜底。
 *
 * ## 为什么用 PendingIntent.getForegroundService
 *
 * 旧代码用 `PendingIntent.getService` 排重启闹钟。targetSdk ≥ 26 时后台
 * `startService()` 会被系统拒绝（IllegalStateException 抛在 system_server 侧），
 * 闹钟触发了但服务永远起不来 —— 看起来就是「保活完全没生效」。
 * `getForegroundService`（API 26+）直接走前台服务启动通道，不受该限制。
 *
 * ## 间隔
 *
 * `setExactAndAllowWhileIdle` 在低电耗空闲下会被系统限制到约每 9–15 分钟一次，
 * 因此心跳取 10 分钟：正常使用下能及时补活，进入 Doze 后也只会被系统顺延，
 * 不会失效。
 */
object KeepAliveWatchdog {

    /** 心跳 action：由闹钟投递给 [McForegroundService]。 */
    const val ACTION_HEARTBEAT = "com.mineserve.mobile.action.KEEP_ALIVE_HEARTBEAT"

    /** 心跳间隔（毫秒）。 */
    const val INTERVAL_MS = 10 * 60_000L

    private const val RC_HEARTBEAT = 0x4B41
    private const val RC_RESTART = 0x4B42
    private const val TAG = "KeepAliveWatchdog"

    /** 重启退避：同一窗口内重启过多说明「起来就崩」，继续拉只会耗电。 */
    private const val GUARD_PREFS = "keep_alive_guard"
    private const val KEY_WINDOW_START = "window_start"
    private const val KEY_WINDOW_COUNT = "window_count"
    private const val MAX_RESTARTS_PER_WINDOW = 8
    private const val WINDOW_MS = 30 * 60_000L

    /** 「后台保活」开关是否开启（与保活页同一个偏好键，默认开启）。 */
    fun isEnabled(context: Context): Boolean = context
        .getSharedPreferences(BootReceiver.META_PREFS, Context.MODE_PRIVATE)
        .getBoolean(BootReceiver.KEY_KEEP_ALIVE, true)

    /** 排下一次心跳（幂等：同 requestCode 覆盖旧闹钟）。 */
    fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
        arm(context, RC_HEARTBEAT, ACTION_HEARTBEAT, delayMs)
    }

    /**
     * 排一次「立刻拉起服务」的闹钟。
     *
     * 使用场景：进程即将被系统回收（onTaskRemoved / onDestroy）时先把闹钟立好，
     * 进程死掉之后由系统在 [delayMs] 后重新拉起。带重启退避，避免崩溃循环。
     */
    fun scheduleRestart(context: Context, delayMs: Long = 1_500L) {
        if (!allowRestart(context)) {
            Log.w(TAG, "重启过于频繁，跳过本次拉起（保活停摆直到下次心跳）")
            return
        }
        arm(context, RC_RESTART, McForegroundService.ACTION_START, delayMs)
    }

    /** 取消所有保活闹钟（用户关闭保活时调用）。 */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        listOf(RC_HEARTBEAT to ACTION_HEARTBEAT, RC_RESTART to McForegroundService.ACTION_START)
            .forEach { (rc, action) ->
                runCatching { am.cancel(pendingIntent(context, rc, action)) }
            }
    }

    /** 清空重启退避计数（用户手动开启保活时给一次干净的机会）。 */
    fun resetRestartGuard(context: Context) {
        context.getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_WINDOW_START).remove(KEY_WINDOW_COUNT).apply()
    }

    private fun allowRestart(context: Context): Boolean {
        val prefs = context.getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val start = prefs.getLong(KEY_WINDOW_START, 0L)
        if (start == 0L || now - start > WINDOW_MS) {
            prefs.edit().putLong(KEY_WINDOW_START, now).putInt(KEY_WINDOW_COUNT, 1).apply()
            return true
        }
        val count = prefs.getInt(KEY_WINDOW_COUNT, 0) + 1
        prefs.edit().putInt(KEY_WINDOW_COUNT, count).apply()
        if (count <= MAX_RESTARTS_PER_WINDOW) return true
        Log.w(TAG, "30 分钟内已重启 $count 次，进入退避")
        return false
    }

    private fun arm(context: Context, requestCode: Int, action: String, delayMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context, requestCode, action)
        val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(1_000L)
        try {
            if (canScheduleExact(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                // 未授予精确闹钟权限：仍能在 Doze 下唤醒，只是触发时间被系统顺延。
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            runCatching {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }.onFailure { Log.w(TAG, "闹钟排期失败: ${it.message}") }
        } catch (e: Exception) {
            Log.w(TAG, "闹钟排期失败: ${e.message}")
        }
    }

    private fun canScheduleExact(am: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true

    private fun pendingIntent(context: Context, requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, McForegroundService::class.java).setAction(action)
        // minSdk = 26 → getForegroundService 恒定可用，后台启动不会因
        // 「后台不允许 startService」被系统拒绝。
        return PendingIntent.getForegroundService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
