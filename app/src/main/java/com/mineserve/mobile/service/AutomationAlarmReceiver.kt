package com.mineserve.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mineserve.mobile.data.AutomationScheduler

/**
 * 自动化定时任务闹钟触发点。
 *
 * 与 [ScheduleAlarmReceiver] 同样的取舍：定时任务可能是分钟级的（开服、打包备份），
 * PendingResult 有硬性时限，因此这里只做两件事——先在 Receiver 里同步重排下一次
 * 闹钟（纯计算 + AlarmManager 调用，毫秒级，保证「服务没起来计划也不丢」），
 * 再把任务转交给 [McForegroundService] 在自身作用域里执行。
 */
class AutomationAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(AutomationScheduler.EXTRA_TASK_ID) ?: return

        // 先在内存里乐观推进被触发任务的下次时间，避免服务尚未落盘时把闹钟重新排到「现在」
        // 造成紧密循环；服务执行完会用落盘后的最新配置再次排期（幂等覆盖）。
        AutomationScheduler.latestTasks(context)?.let { tasks ->
            val now = System.currentTimeMillis()
            val rearmed = tasks.map { task ->
                if (task.id == taskId && task.enabled) {
                    val next = AutomationScheduler.computeNextRun(task, now)
                    task.copy(lastRunMs = now, nextRunMs = next)
                } else {
                    task
                }
            }
            runCatching { AutomationScheduler.register(context, rearmed) }
        }

        McForegroundService.enqueueAutomationTask(context, taskId)
    }
}
