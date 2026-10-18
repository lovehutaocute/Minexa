package com.mineserve.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mineserve.mobile.data.ScheduleManager
import com.mineserve.mobile.data.AutomationScheduler
import com.mineserve.mobile.service.McForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启动：设备开机完成后，若开启「开机自启」开关，拉起前台保活服务；
 * 同时按配置重注册每日定时开/停服闹钟（进程与闹钟在重启后不会保留）。
 * 开关状态存于 SharedPreferences（mc_config_meta），由保活页面设置。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // manifest 同时声明了 BOOT_COMPLETED 与 QUICKBOOT_POWERON（部分厂商快速启动广播）。
        // 原实现只放行 BOOT_COMPLETED，导致 QUICKBOOT_POWERON 分支永远不可达。
        // 局部变量刻意不叫 action：下面 Intent.apply 里的 `action = ...` 会被它遮蔽，
        // 变成「给 val 赋值」而编译不过。
        val intentAction = intent.action
        val isBoot = intentAction == Intent.ACTION_BOOT_COMPLETED ||
            intentAction == "android.intent.action.QUICKBOOT_POWERON" ||
            intentAction == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!isBoot) return

        // 重新注册每日定时开/停服闹钟
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { McApplication.get(context).repository.configFlow.first() }
                    .getOrNull()
                    ?.let { config ->
                        ScheduleManager.register(context, config)
                        // 自动化定时任务同样在重启后不保留闹钟，按配置重排最近一条。
                        AutomationScheduler.register(
                            context,
                            AutomationScheduler.withNextRunsAssigned(config.automationTasks)
                        )
                    }
                // 开机后纠正桌面组件（重启前可能停留在“运行中”）
                com.mineserve.mobile.data.WidgetUpdater.refresh(context)
            } finally {
                pending.finish()
            }
        }

        val bootAuto = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BOOT_AUTO_START, false)
        if (bootAuto) {
            Log.i(TAG, "开机自启：拉起前台保活服务")
            try {
                context.startForegroundService(
                    Intent(context, McForegroundService::class.java).apply {
                        action = McForegroundService.ACTION_START
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "开机自启失败: ${e.message}")
            }
        }
    }

    companion object {
        const val META_PREFS = "mc_config_meta"
        const val KEY_BOOT_AUTO_START = "boot_auto_start"
        const val KEY_KEEP_ALIVE = "keep_alive_enabled"
        private const val TAG = "BootReceiver"
    }
}
