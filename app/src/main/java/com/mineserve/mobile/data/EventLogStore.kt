package com.mineserve.mobile.data

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.widget.EventLogWidget
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 桌面组件事件类型 */
object WidgetEventType {
    const val JOIN = "join"
    const val LEAVE = "leave"
    const val CHAT = "chat"
}

/** 事件日志条目（进服/离服/聊天公告） */
@Serializable
data class WidgetEvent(
    val type: String,
    val player: String,
    val message: String = "",
    val time: Long
)

/**
 * 桌面组件事件环形存储：最近 [MAX_ENTRIES] 条，按服务器目录隔离并持久化 JSON。
 * 写入方是前台服务的控制台解析；事件日志组件通过 [snapshot] 读取。
 */
object EventLogStore {
    private const val MAX_ENTRIES = 20
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val cache = mutableMapOf<String, MutableList<WidgetEvent>>()

    fun add(context: Context, dirName: String, type: String, player: String, message: String = "") {
        val entry = WidgetEvent(type, player, message, System.currentTimeMillis())
        val list = synchronized(lock) {
            loadLocked(context, dirName).apply {
                add(0, entry)
                while (size > MAX_ENTRIES) removeAt(size - 1)
            }
        }
        persist(context, dirName, list)
        notifyWidgets(context)
    }

    fun snapshot(context: Context, dirName: String): List<WidgetEvent> =
        synchronized(lock) { loadLocked(context, dirName).toList() }

    private fun loadLocked(context: Context, dirName: String): MutableList<WidgetEvent> =
        cache.getOrPut(dirName) {
            runCatching {
                val file = eventFile(context, dirName)
                if (file.isFile) json.decodeFromString<List<WidgetEvent>>(file.readText()).toMutableList()
                else mutableListOf()
            }.getOrDefault(mutableListOf())
        }

    private fun persist(context: Context, dirName: String, list: List<WidgetEvent>) {
        runCatching {
            val file = eventFile(context, dirName)
            val text = json.encodeToString(list)
            // 临时文件 + rename 覆盖：writeText 直接截断覆盖，若写一半被杀进程会留下
            // 截断的 JSON，下次 loadLocked 解析失败静默返回空列表，整份历史丢失。
            val temp = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
            try {
                temp.writeText(text)
                if (!temp.renameTo(file)) {
                    // 个别文件系统上 rename 到已存在文件会失败：退化为直接写（仍尽量让
                    // 新数据落盘），失败时保留旧文件，不主动删除。
                    file.writeText(text)
                }
            } catch (_: Exception) {
                // 写临时文件失败时保留旧文件，绝不把有效数据弄丢
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }

    private fun eventFile(context: Context, dirName: String): File {
        val dir = File(context.filesDir, "widget-events").apply { mkdirs() }
        return File(dir, "${dirName}.json")
    }

    private fun notifyWidgets(context: Context) {
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, EventLogWidget::class.java))
            if (ids == null || ids.isEmpty()) return
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_event_list)
        }
    }
}
