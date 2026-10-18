package com.mineserve.mobile.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.mineserve.mobile.service.AutomationAlarmReceiver
import java.util.Calendar

/**
 * 自动化定时任务的闹钟调度。
 *
 * ## 为什么用 AlarmManager 而不是常驻协程循环
 *
 * 定时任务必须在「App 进程已死、服务器未运行」时也能触发（例如每日开服），
 * 这正是 WorkManager/常驻协程都覆盖不了的场景。这里只为「最近一个待触发任务」
 * 注册一个精确闹钟；闹钟触发后执行该任务、重算下次时间，再排下一个。
 *
 * 计划存于 [McConfig.automationTasks]，每次配置变更后调用 [register] 重排。
 */
object AutomationScheduler {
    private const val REQUEST_CODE = 3120
    const val EXTRA_TASK_ID = "automation_task_id"

    /**
     * 为尚未排期（`nextRunMs <= 0`）的启用任务补上下一次触发时间。
     *
     * 纯函数，不落盘：调用方把它写回配置后再调用 [register]。
     */
    fun withNextRunsAssigned(
        tasks: List<AutomationTask>,
        now: Long = System.currentTimeMillis()
    ): List<AutomationTask> = tasks.map { task ->
        if (task.enabled && task.nextRunMs <= 0L) task.copy(nextRunMs = computeNextRun(task, now)) else task
    }

    /** 只按最近一个待触发任务注册/更新闹钟（幂等）。 */
    fun register(context: Context, tasks: List<AutomationTask>) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val next = tasks.filter { it.enabled && it.nextRunMs > 0L }.minByOrNull { it.nextRunMs }
        if (next == null) {
            cancel(am, context)
            return
        }
        // 过期任务（设备关机期间错过）最快 5 秒后补跑，避免与当前时刻形成紧密循环。
        val triggerAt = next.nextRunMs.coerceAtLeast(now + 5_000L)
        val pi = pendingIntent(context, next.id)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            // 未授予精确闹钟权限时退化为窗口内触发（约 ±2 分钟）
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 120_000L, pi)
        } catch (e: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** 最佳努力取当前任务列表（仅在内存态已就绪时），供 Receiver 同步重排闹钟使用。 */
    fun latestTasks(context: Context): List<AutomationTask>? = runCatching {
        com.mineserve.mobile.McApplication.get(context).repository.currentConfigOrNull()?.automationTasks
    }.getOrNull()

    /**
     * 计算 [task] 在 [referenceTime] 之后的下一次触发时间（毫秒）。
     *
     * - [AutomationFrequency.Interval]：`referenceTime + 周期`
     * - [AutomationFrequency.EveryXDays]：今天该时刻已过则顺延 `dayInterval` 天
     * - [AutomationFrequency.WeeklyDays]：向后找第一个命中且满足周间隔的日子
     * - [AutomationFrequency.CustomCron]：交给 [CronExpression.next]
     */
    fun computeNextRun(task: AutomationTask, referenceTime: Long = System.currentTimeMillis()): Long {
        return when (task.frequency) {
            AutomationFrequency.Interval -> {
                val mins = task.intervalMinutes.coerceAtLeast(1)
                referenceTime + mins * 60_000L
            }

            AutomationFrequency.EveryXDays -> {
                val step = task.dayInterval.coerceAtLeast(1)
                val cal = Calendar.getInstance().apply {
                    timeInMillis = referenceTime
                    set(Calendar.HOUR_OF_DAY, task.hour.coerceIn(0, 23))
                    set(Calendar.MINUTE, task.minute.coerceIn(0, 59))
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (cal.timeInMillis <= referenceTime) cal.add(Calendar.DAY_OF_YEAR, step)
                cal.timeInMillis
            }

            AutomationFrequency.WeeklyDays -> {
                val selectedDays = task.daysOfWeek.ifEmpty { listOf(Calendar.SUNDAY) }
                val weekInterval = task.weekInterval.coerceAtLeast(1)
                val cal = Calendar.getInstance().apply {
                    timeInMillis = referenceTime
                    set(Calendar.HOUR_OF_DAY, task.hour.coerceIn(0, 23))
                    set(Calendar.MINUTE, task.minute.coerceIn(0, 59))
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (cal.timeInMillis <= referenceTime) cal.add(Calendar.DAY_OF_YEAR, 1)

                val maxDaysAhead = (7 * weekInterval + 7).coerceAtMost(90)
                for (d in 0 until maxDaysAhead) {
                    val dow = cal.get(Calendar.DAY_OF_WEEK)
                    if (selectedDays.contains(dow)) {
                        if (weekInterval <= 1 || task.lastRunMs == 0L) {
                            return cal.timeInMillis
                        }
                        val elapsedDays = (cal.timeInMillis - task.lastRunMs) / (24 * 60 * 60 * 1000L)
                        if (elapsedDays >= (weekInterval - 1) * 7L + 1L) {
                            return cal.timeInMillis
                        }
                    }
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                cal.timeInMillis
            }

            AutomationFrequency.CustomCron -> {
                val cron = CronExpression.parse(task.cron)
                val next = cron?.next(referenceTime) ?: 0L
                if (next > 0L) next else referenceTime + 3_600_000L
            }
        }
    }

    private fun cancel(am: AlarmManager, context: Context) {
        am.cancel(pendingIntent(context, ""))
    }

    private fun pendingIntent(context: Context, taskId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, AutomationAlarmReceiver::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
