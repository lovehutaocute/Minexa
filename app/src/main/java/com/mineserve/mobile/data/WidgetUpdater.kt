package com.mineserve.mobile.data

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mineserve.mobile.MainActivity
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.widget.ConsoleWidget
import com.mineserve.mobile.widget.EventLogWidget
import com.mineserve.mobile.widget.EventLogWidgetService
import com.mineserve.mobile.widget.ModPluginWidget
import com.mineserve.mobile.widget.ModPluginWidgetService
import com.mineserve.mobile.widget.OverviewWidget
import com.mineserve.mobile.widget.WidgetActionReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 桌面组件刷新分发器：
 *  - 总览/控制台由 serverState+config 构建静态 RemoteViews；
 *  - 事件日志/模组插件仅更新外壳（列表数据由各自 RemoteViewsService 提供）。
 * 状态变化的 diff 判定在 ServerRepository.updateServerState 中完成。
 */
object WidgetUpdater {
    private const val ACTION_WIDGET_START = "com.mineserve.mobile.widget.START_SERVER"
    private const val ACTION_WIDGET_STOP = "com.mineserve.mobile.widget.STOP_SERVER"
    private const val DEBOUNCE_MS = 300L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var pending = false
    @Volatile private var lastPushAtMs = 0L

    /** 状态变化触发的刷新（防抖合并高频变化）。 */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        if (pending) return
        pending = true
        val since = System.currentTimeMillis() - lastPushAtMs
        if (since >= DEBOUNCE_MS) {
            scope.launch { pushNow(appContext) }
        } else {
            scope.launch {
                kotlinx.coroutines.delay(DEBOUNCE_MS - since)
                pushNow(appContext)
            }
        }
    }

    private suspend fun pushNow(context: Context) {
        pending = false
        lastPushAtMs = System.currentTimeMillis()
        try {
            val repo = McApplication.get(context).repository
            val state = repo.serverState.value
            val config = runCatching { repo.configFlow.first() }.getOrNull() ?: McConfig()
            val manager = AppWidgetManager.getInstance(context)
            updateProvider(context, manager, OverviewWidget::class.java) { buildOverview(context, state) }
            updateProvider(context, manager, EventLogWidget::class.java) { buildEventLogShell(context) }
            updateProvider(context, manager, ConsoleWidget::class.java) { buildConsole(context, state, config) }
            updateProvider(context, manager, ModPluginWidget::class.java) { buildModPluginShell(context) }
            // 外壳重建会覆盖 RemoteViews 的 adapter 连接；立即通知列表组件重新拉取数据，
            // 否则要等下一次事件写入或 15 分钟兜底才更新。
            notifyListChanged(context, manager, EventLogWidget::class.java, R.id.widget_event_list)
            notifyListChanged(context, manager, ModPluginWidget::class.java, R.id.widget_mod_list)
        } catch (_: Exception) {
            // Widget 刷新失败不影响服务器运行
        }
    }

    private fun notifyListChanged(context: Context, manager: AppWidgetManager, provider: Class<*>, listId: Int) {
        val ids = manager.getAppWidgetIds(ComponentName(context, provider))
        if (ids == null || ids.isEmpty()) return
        manager.notifyAppWidgetViewDataChanged(ids, listId)
    }

    private fun updateProvider(
        context: Context,
        manager: AppWidgetManager,
        provider: Class<*>,
        views: () -> RemoteViews
    ) {
        val ids = manager.getAppWidgetIds(ComponentName(context, provider))
        if (ids == null || ids.isEmpty()) return
        manager.updateAppWidget(ids, views())
    }

    /** ① 状态总览（4×1）：状态点 + 状态文本 + 在线人数；第二行 TPS/内存/时长/CPU。 */
    private fun buildOverview(context: Context, state: ServerState): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_overview).apply {
            bindStatus(context, this, state)
            setTextViewText(R.id.widget_metric_tps, tpsValue(state))
            setTextViewText(R.id.widget_metric_memory, memoryValue(state))
            setTextViewText(R.id.widget_metric_uptime, uptimeValue(state))
            setTextViewText(R.id.widget_metric_cpu, cpuValue(state))
            setOnClickPendingIntent(
                R.id.widget_refresh_btn,
                widgetActionIntent(context, WidgetActionReceiver.ACTION_REFRESH_STATUS, 4002)
            )
        }

    /** ② 事件日志（2×4）：外壳 + 列表适配器（列表数据由 EventLogWidgetService 提供）。 */
    private fun buildEventLogShell(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_event_log).apply {
            setRemoteAdapter(
                R.id.widget_event_list,
                Intent(context, EventLogWidgetService::class.java)
            )
            setEmptyView(R.id.widget_event_list, R.id.widget_event_empty)
            setOnClickPendingIntent(R.id.widget_event_root, openAppIntent(context))
        }

    /** ③ 控制台管理（4×3）：核心/Java 选择 + 启停 + 存档保存/备份。 */
    private fun buildConsole(context: Context, state: ServerState, config: McConfig): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_console).apply {
            val core = config.installedCores.find { it.name == config.activeCoreName }
            val running = state.isRunning
            setTextViewText(
                R.id.widget_console_core,
                core?.name ?: context.getString(R.string.widget_console_no_core)
            )
            setTextViewText(R.id.widget_console_java, config.selectedJavaVersion.displayName)
            val actionsEnabled = WidgetPreferences.areActionsEnabled(context)
            bindActionButton(context, this, state, actionsEnabled)
            // 服务器运行中禁止切换核心/Java；设置关闭时禁用全部写操作。
            viewsList().forEach {
                setTextColor(it, if (!actionsEnabled || running) 0xFF90A4AE.toInt() else 0xFF37474F.toInt())
            }
            bindConsolePendingIntents(context, this, actionsEnabled)
        }

    /** ④ 模组与插件（4×3）：外壳 + 列表适配器（列表数据由 ModPluginWidgetService 提供）。 */
    private fun buildModPluginShell(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_mod_plugin).apply {
            setRemoteAdapter(
                R.id.widget_mod_list,
                Intent(context, ModPluginWidgetService::class.java)
            )
            setEmptyView(R.id.widget_mod_list, R.id.widget_mod_empty)
            setOnClickPendingIntent(R.id.widget_mod_root, openAppIntent(context))
        }

    private fun viewsList() = listOf(
        R.id.widget_core_prev, R.id.widget_core_next,
        R.id.widget_java_prev, R.id.widget_java_next
    )

    private fun bindStatus(context: Context, views: RemoteViews, state: ServerState) {
        val starting = state.isRunning && state.runningSinceMs == 0L
        val statusText = when {
            starting -> context.getString(R.string.widget_status_starting)
            state.isRunning -> context.getString(R.string.widget_status_running)
            state.startupPhase == StartupPhase.Failed -> context.getString(R.string.widget_status_failed)
            else -> context.getString(R.string.widget_status_stopped)
        }
        views.setTextViewText(R.id.widget_status_text, statusText)
        views.setTextViewText(R.id.widget_players_text, playersText(state))
        val dotColor = when {
            starting -> 0xFFFFA726.toInt()
            state.isRunning -> 0xFF2FBF87.toInt()
            else -> 0xFFF97066.toInt()
        }
        views.setInt(R.id.widget_status_dot, "setColorFilter", dotColor)
    }

    private fun bindActionButton(context: Context, views: RemoteViews, state: ServerState, enabled: Boolean) {
        val running = state.isRunning
        views.setTextViewText(
            R.id.widget_action_btn,
            context.getString(if (running) R.string.widget_btn_stop else R.string.widget_btn_start)
        )
        views.setTextColor(R.id.widget_action_btn, if (enabled) 0xFFFFFFFF.toInt() else 0xFF9AA0A6.toInt())
        if (enabled) {
            views.setOnClickPendingIntent(
                R.id.widget_action_btn,
                widgetActionIntent(context, if (running) ACTION_WIDGET_STOP else ACTION_WIDGET_START, 4001)
            )
        } else {
            views.setOnClickPendingIntent(R.id.widget_action_btn, openAppIntent(context))
        }
    }

    private fun bindConsolePendingIntents(context: Context, views: RemoteViews, enabled: Boolean) {
        val actions = listOf(
            R.id.widget_core_prev to WidgetActionReceiver.ACTION_CORE_PREV,
            R.id.widget_core_next to WidgetActionReceiver.ACTION_CORE_NEXT,
            R.id.widget_java_prev to WidgetActionReceiver.ACTION_JAVA_PREV,
            R.id.widget_java_next to WidgetActionReceiver.ACTION_JAVA_NEXT,
            R.id.widget_save_btn to WidgetActionReceiver.ACTION_SAVE,
            R.id.widget_backup_btn to WidgetActionReceiver.ACTION_BACKUP
        )
        actions.forEachIndexed { index, (viewId, action) ->
            views.setOnClickPendingIntent(
                viewId,
                if (enabled) widgetActionIntent(context, action, 4010 + index) else openAppIntent(context)
            )
        }
    }

    private fun playersText(state: ServerState): String =
        if (state.isRunning) "${state.onlinePlayers}/${state.maxPlayers}" else "--"

    private fun tpsValue(state: ServerState): String =
        if (state.isRunning && state.tps > 0.0) String.format("%.1f", state.tps) else "--"

    private fun memoryValue(state: ServerState): String =
        if (state.isRunning && state.usedMemoryMb > 0) {
            val mb = state.usedMemoryMb
            if (mb >= 1024) String.format("%.1fG", mb / 1024.0) else "${mb}M"
        } else "--"

    private fun uptimeValue(state: ServerState): String {
        if (!state.isRunning || state.runningSinceMs <= 0L) return "--"
        val totalSec = (android.os.SystemClock.elapsedRealtime() - state.runningSinceMs) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return if (h > 0) "${h}h${m}m" else "${m}m"
    }

    private fun cpuValue(state: ServerState): String = (state.cpuPercent?.let { "$it%" }) ?: "--"

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun widgetActionIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, WidgetActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
