package com.mineserve.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.data.EventLogStore
import com.mineserve.mobile.data.WidgetEventType
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 事件日志组件的列表数据源。 */
class EventLogWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = EventLogFactory(applicationContext)

    class EventLogFactory(private val context: Context) : RemoteViewsFactory {
        private var events: List<Triple<String, String, String>> = emptyList() // (类型, 主文本, 时间)

        override fun onCreate() {}

        override fun onDataSetChanged() {
            // 同 ModPluginWidgetService：onDataSetChanged 由 binder 在主线程回调，原先用
            // runBlocking 读 DataStore（并在首次访问时读事件文件），这里只读进程内快照。
            events = cache?.events ?: emptyList()
            requestReload(context)
        }

        override fun onDestroy() {}
        override fun getCount(): Int = events.size
        override fun getViewTypeCount(): Int = 1
        override fun hasStableIds(): Boolean = false
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewAt(position: Int): RemoteViews {
            val (type, text, time) = events[position]
            val views = RemoteViews(context.packageName, R.layout.widget_event_log_item)
            views.setTextViewText(R.id.item_text, text)
            views.setTextViewText(R.id.item_time, time)
            val color = when (type) {
                WidgetEventType.JOIN -> 0xFF2FBF87.toInt()
                WidgetEventType.LEAVE -> 0xFFFFA726.toInt()
                else -> 0xFF3B4C9C.toInt()
            }
            views.setInt(R.id.item_dot, "setColorFilter", color)
            return views
        }

        override fun getLoadingView(): RemoteViews? = null

        companion object {
            private data class Snapshot(val signature: String, val events: List<Triple<String, String, String>>)

            @Volatile private var cache: Snapshot? = null
            @Volatile private var loading = false
            /** 加载进行中又有新请求：标记为 pending，当前加载结束后补跑一次，避免丢更新。 */
            @Volatile private var pending = false

            private val executor: ExecutorService =
                Executors.newSingleThreadExecutor { r -> Thread(r, "event-log-widget-load") }

            /** 触发后台重建（单飞 + 尾随补跑），保证最后一次事件写入不会因合并而丢失。 */
            private fun requestReload(context: Context) {
                if (loading) {
                    pending = true
                    return
                }
                loading = true
                val appContext = context.applicationContext
                executor.execute {
                    try {
                        do {
                            pending = false
                            val snapshot = buildSnapshot(appContext)
                            // 仅在内容变化时通知刷新，避免自激循环。
                            val changed = snapshot.signature != cache?.signature
                            cache = snapshot
                            if (changed) notifyListChanged(appContext)
                        } while (pending)
                    } catch (_: Throwable) {
                    } finally {
                        loading = false
                        if (pending) requestReload(appContext)
                    }
                }
            }

            /** 在后台线程读取配置目录与事件快照。 */
            private fun buildSnapshot(context: Context): Snapshot {
                val app = McApplication.get(context)
                val config = runCatching {
                    kotlinx.coroutines.runBlocking { app.repository.configFlow.first() }
                }.getOrNull()
                val dirName = config?.installedCores
                    ?.find { it.name == config.activeCoreName }?.dirName
                    ?: return Snapshot("", emptyList())
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                val events = EventLogStore.snapshot(context, dirName).map { e ->
                    val text = when (e.type) {
                        WidgetEventType.JOIN -> context.getString(R.string.widget_event_join, e.player)
                        WidgetEventType.LEAVE -> context.getString(R.string.widget_event_leave, e.player)
                        else -> if (e.message.isBlank()) e.player else "${e.player}: ${e.message}"
                    }
                    Triple(e.type, text, fmt.format(Date(e.time)))
                }
                val signature = buildString {
                    append(dirName).append('\n')
                    events.forEach {
                        append(it.first).append('\u0001').append(it.second).append('\u0001').append(it.third).append('\n')
                    }
                }
                return Snapshot(signature, events)
            }

            private fun notifyListChanged(context: Context) {
                runCatching {
                    val manager = AppWidgetManager.getInstance(context)
                    val ids = manager.getAppWidgetIds(ComponentName(context, EventLogWidget::class.java))
                    // getAppWidgetIds 返回 IntArray（非空类型），IntArray 上没有 isNullOrEmpty。
                    if (ids.isNotEmpty()) {
                        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_event_list)
                    }
                }
            }
        }
    }
}
