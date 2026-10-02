package com.mineserve.mobile.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mineserve.mobile.data.WidgetPreferences
import com.mineserve.mobile.data.WidgetUpdater
import com.mineserve.mobile.service.McForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 桌面组件按钮的统一广播入口：控制台启停、核心/Java 切换、存档保存/备份，
 * 以及状态总览组件的“立即刷新”（只读，不受操作按钮开关限制）。
 *
 * ## 为什么 onReceive 里不能直接跑动作
 *
 * 这些动作大多是**分钟级**的：启动服务端要下载核心、装依赖、跑 autoRepair；
 * 备份要打包整个世界。旧实现用 `goAsync()` 拿到 PendingResult 后在 IO 协程里
 * 一路做完，而 PendingResult 有硬性时限（前台 10s / 后台 60s），超时系统按 ANR
 * 处理并杀掉 App 进程 —— 正在运行的 MC 会跟着一起死。
 *
 * 现在只有两条轻量路径留在这里：
 *  - ACTION_REFRESH_STATUS：只是让前台服务刷新一次状态，很快；
 *  - 其余动作：转交给 [McForegroundService] 执行（前台服务没有广播时限，
 *    也顺带获得前台保活）。
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // 状态刷新只读且是组件核心功能，不纳入“允许操作按钮”开关。
        if (action == ACTION_REFRESH_STATUS) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    WidgetConsoleActions.refreshServerStatus(context)
                    WidgetUpdater.refresh(context)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        if (!WidgetPreferences.areActionsEnabled(context)) return
        when (action) {
            ACTION_WIDGET_START, ACTION_WIDGET_STOP,
            ACTION_CORE_PREV, ACTION_CORE_NEXT,
            ACTION_JAVA_PREV, ACTION_JAVA_NEXT,
            ACTION_SAVE, ACTION_BACKUP ->
                McForegroundService.enqueueWidgetAction(context, action)
        }
    }

    companion object {
        const val ACTION_WIDGET_START = "com.mineserve.mobile.widget.START_SERVER"
        const val ACTION_WIDGET_STOP = "com.mineserve.mobile.widget.STOP_SERVER"
        const val ACTION_CORE_PREV = "com.mineserve.mobile.widget.CORE_PREV"
        const val ACTION_CORE_NEXT = "com.mineserve.mobile.widget.CORE_NEXT"
        const val ACTION_JAVA_PREV = "com.mineserve.mobile.widget.JAVA_PREV"
        const val ACTION_JAVA_NEXT = "com.mineserve.mobile.widget.JAVA_NEXT"
        const val ACTION_SAVE = "com.mineserve.mobile.widget.SAVE_WORLD"
        const val ACTION_BACKUP = "com.mineserve.mobile.widget.BACKUP_WORLD"
        const val ACTION_REFRESH_STATUS = "com.mineserve.mobile.widget.REFRESH_STATUS"

        /**
         * 执行一个组件动作。由 [McForegroundService] 在服务作用域内调用，
         * 不在广播回调里执行。
         */
        suspend fun dispatchWidgetAction(context: Context, action: String) {
            when (action) {
                ACTION_WIDGET_START -> WidgetConsoleActions.startServer(context)
                ACTION_WIDGET_STOP -> WidgetConsoleActions.stopServer(context)
                ACTION_CORE_PREV -> WidgetConsoleActions.cycleCore(context, -1)
                ACTION_CORE_NEXT -> WidgetConsoleActions.cycleCore(context, 1)
                ACTION_JAVA_PREV -> WidgetConsoleActions.cycleJava(context, -1)
                ACTION_JAVA_NEXT -> WidgetConsoleActions.cycleJava(context, 1)
                ACTION_SAVE -> WidgetConsoleActions.saveWorld(context)
                ACTION_BACKUP -> WidgetConsoleActions.backupWorld(context)
            }
        }

        /**
         * 执行一个定时任务动作（开/停服）。由 [McForegroundService] 在服务作用域内调用。
         */
        suspend fun dispatchScheduleAction(context: Context, action: String) {
            val app = com.mineserve.mobile.McApplication.get(context)
            val repository = app.repository
            val config = repository.configFlow.first()
            val controller = com.mineserve.mobile.server.McServerController(app.termuxRuntime, repository)
            if (action == com.mineserve.mobile.data.ScheduleManager.ACTION_START_SERVER) {
                try {
                    // start 本身是 suspend：直接挂起调用即可，不要再套 runBlocking
                    //（会无谓占用一个 IO 线程并屏蔽结构化取消）。
                    controller.start(config, userInitiated = false)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.mineserve.mobile.data.ServerEventNotifier.notify(
                        app,
                        app.getString(com.mineserve.mobile.R.string.notif_schedule_start_fail_title),
                        app.getString(
                            com.mineserve.mobile.R.string.notif_schedule_start_fail_text,
                            e.message ?: ""
                        ),
                        com.mineserve.mobile.data.ServerEventNotifier.ID_SCHEDULE_FAIL, 1
                    )
                }
            } else if (action == com.mineserve.mobile.data.ScheduleManager.ACTION_STOP_SERVER) {
                runCatching { controller.stop() }
            }
        }
    }
}
