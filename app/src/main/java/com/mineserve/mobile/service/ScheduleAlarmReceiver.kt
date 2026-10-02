package com.mineserve.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mineserve.mobile.data.ScheduleManager

/**
 * 每日定时开/停服触发点。
 *
 * ## 为什么不能在 goAsync() 里跑完
 *
 * 定时开服的耗时是分钟级的（下载核心、装依赖、autoRepair），而 PendingResult
 * 有硬性时限（后台 60s），超时系统按 ANR 处理并杀掉 App 进程——正在运行的 MC
 * 会跟着一起死。所以这里只做转交：把动作交给 [McForegroundService]，
 * 由它在自己的作用域里执行，随后按最新配置重注册次日闹钟。
 *
 * 重注册放在 Receiver 里同步完成（纯计算 + AlarmManager 调用，毫秒级），
 * 保证“无论服务是否成功启动，第二天的闹钟都已经排上”。
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(ScheduleManager.EXTRA_ACTION) ?: return
        // 先重注册下一次（配置可能已被修改）。内存态已就绪时同步完成（仅 AlarmManager
        // 调用，毫秒级）；未就绪则跳过，由前台服务的协程补做——避免主线程读盘。
        ScheduleManager.latestConfig(context)?.let { config ->
            runCatching { ScheduleManager.register(context, config) }
        }
        McForegroundService.enqueueScheduleAction(context, action)
    }
}
