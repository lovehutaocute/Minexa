package com.mineserve.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.server.PluginManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.jar.JarFile

/** 模组与插件组件的列表数据源（含 jar 内图标提取与缓存）。 */
class ModPluginWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = ModPluginFactory(applicationContext)

    class ModPluginFactory(private val context: Context) : RemoteViewsFactory {

        private data class Row(val title: String, val subtitle: String, val icon: Bitmap?, val initial: String)

        private var rows: List<Row> = emptyList()

        override fun onCreate() {}

        override fun onDataSetChanged() {
            // onDataSetChanged 由 AppWidgetManager 经 binder 回调，跑在本进程主线程；其中原先
            // 用 runBlocking 读 DataStore 并在主线程扫 JarFile / 解压图标，jar 多时会 ANR
            //（本进程同时托管 MC 服务端）。这里只读进程内快照，缓存由后台线程预取。
            rows = cache?.rows ?: emptyList()
            requestReload(context)
        }

        override fun onDestroy() {}
        override fun getCount(): Int = rows.size
        override fun getViewTypeCount(): Int = 1
        override fun hasStableIds(): Boolean = false
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewAt(position: Int): RemoteViews {
            val row = rows[position]
            val views = RemoteViews(context.packageName, R.layout.widget_mod_plugin_item)
            views.setTextViewText(R.id.item_title, row.title)
            views.setTextViewText(R.id.item_subtitle, row.subtitle)
            if (row.icon != null) {
                views.setImageViewBitmap(R.id.item_icon, row.icon)
                views.setViewVisibility(R.id.item_badge, android.view.View.INVISIBLE)
            } else {
                views.setViewVisibility(R.id.item_icon, android.view.View.INVISIBLE)
                views.setViewVisibility(R.id.item_badge, android.view.View.VISIBLE)
                views.setTextViewText(R.id.item_badge, row.initial)
            }
            return views
        }

        override fun getLoadingView(): RemoteViews? = null

        companion object {
            /** 后台构建好的快照。signature 用于判断内容是否变化（Bitmap 不参与比较）。 */
            private data class Snapshot(val signature: String, val rows: List<Row>)

            @Volatile private var cache: Snapshot? = null
            @Volatile private var loading = false
            /** 加载进行中又有新请求：标记为 pending，当前加载结束后补跑一次，避免丢更新。 */
            @Volatile private var pending = false

            private val executor: ExecutorService =
                Executors.newSingleThreadExecutor { r -> Thread(r, "mod-plugin-widget-load") }

            /**
             * 触发后台重建（单飞 + 尾随补跑）。缓存为空（冷启动）时立即执行；
             * 单个线程串行构建，通知密集时合并，保证最后一次数据变化不会丢失。
             */
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
                            // 仅在内容真正变化时通知列表刷新，否则会「通知→重建→通知」自激循环。
                            val changed = snapshot.signature != cache?.signature
                            cache = snapshot
                            if (changed) notifyListChanged(appContext)
                        } while (pending)
                    } catch (_: Throwable) {
                    } finally {
                        loading = false
                        // 关闭瞬间到达的请求可能在 pending 检查之后，补触发一次即可收敛。
                        if (pending) requestReload(appContext)
                    }
                }
            }

            /** 在后台线程完成配置读取、目录扫描与图标解码。 */
            private fun buildSnapshot(context: Context): Snapshot {
                val app = McApplication.get(context)
                val pluginManager = PluginManager(app.termuxRuntime, app)
                val config = runCatching {
                    kotlinx.coroutines.runBlocking { app.repository.configFlow.first() }
                }.getOrNull()
                val core = config?.installedCores?.find { it.name == config.activeCoreName }
                val dirName = core?.dirName ?: return Snapshot("", emptyList())
                val root = app.termuxRuntime.installer.rootDir
                val modsDir = File(root, "home/servers/$dirName/mods")
                val pluginsDir = File(root, "home/servers/$dirName/plugins")

                val list = mutableListOf<Row>()
                val mods = runCatching { pluginManager.readMods(dirName) }.getOrDefault(emptyList())
                if (mods.isNotEmpty()) {
                    list += Row(
                        context.getString(R.string.widget_mod_label),
                        context.getString(R.string.widget_mod_count, mods.size), null, ""
                    )
                    mods.forEach { mod ->
                        list += Row(
                            mod.baseName,
                            statusText(context, mod.isEnabled),
                            WidgetIconCache.modIcon(File(modsDir, mod.fileName)),
                            mod.baseName.take(1).uppercase()
                        )
                    }
                }
                val plugins = runCatching {
                    kotlinx.coroutines.runBlocking { pluginManager.scan(dirName) }
                }.getOrDefault(emptyList())
                if (plugins.isNotEmpty()) {
                    list += Row(
                        context.getString(R.string.widget_plugin_label),
                        context.getString(R.string.widget_plugin_count, plugins.size), null, ""
                    )
                    plugins.forEach { plugin ->
                        val title = plugin.meta?.name ?: plugin.baseName
                        list += Row(
                            title,
                            statusText(context, plugin.isEnabled),
                            WidgetIconCache.pluginIcon(File(pluginsDir, plugin.fileName)),
                            title.take(1).uppercase()
                        )
                    }
                }
                val signature = buildString {
                    append(dirName).append('\n')
                    list.forEach {
                        append(it.title).append('\u0001').append(it.subtitle).append('\u0001').append(it.initial).append('\n')
                    }
                }
                return Snapshot(signature, list)
            }

            private fun statusText(context: Context, enabled: Boolean): String =
                context.getString(if (enabled) R.string.widget_status_enabled else R.string.widget_status_disabled)

            private fun notifyListChanged(context: Context) {
                runCatching {
                    val manager = AppWidgetManager.getInstance(context)
                    val ids = manager.getAppWidgetIds(ComponentName(context, ModPluginWidget::class.java))
                    // getAppWidgetIds 返回 IntArray（非空类型），IntArray 上没有 isNullOrEmpty。
                    if (ids.isNotEmpty()) {
                        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_mod_list)
                    }
                }
            }
        }
    }
}

/** jar 内图标 → Bitmap；按 (绝对路径+lastModified+entry) 缓存，失败用哨兵占位避免重复解压。 */
object WidgetIconCache {
    // ConcurrentHashMap 不接受 null key/value；解码失败用哨兵占位表示“已尝试过”。
    private val cache = ConcurrentHashMap<String, Any>()
    private val MISS = Any()

    fun pluginIcon(jar: File): Bitmap? = jarIcon(jar, listOf("icon.png"))

    /** 模组图标：fabric.mod.json 的 icon（字符串或 {src}），回退常见固定路径。 */
    fun modIcon(jar: File): Bitmap? {
        val fabricIcon = runCatching {
            JarFile(jar).use { jf ->
                val entry = jf.getEntry("fabric.mod.json") ?: return@use null
                val obj = Json.parseToJsonElement(
                    jf.getInputStream(entry).bufferedReader().readText()
                ).jsonObject
                obj["icon"]?.let { el ->
                    when {
                        el.toString().startsWith("\"") -> el.toString().trim('"')
                        else -> runCatching { el.jsonObject["src"]?.toString()?.trim('"') }.getOrNull()
                    }
                }
            }
        }.getOrNull()
        val candidates = buildList {
            fabricIcon?.takeIf { it.isNotBlank() }?.let { add(it.trimStart('/')) }
            add("icon.png")
            add("assets/icon.png")
        }.distinct()
        return jarIcon(jar, candidates)
    }

    private fun jarIcon(jar: File, entryNames: List<String>): Bitmap? {
        val key = "${jar.absolutePath}:${jar.lastModified()}:${entryNames.firstOrNull() ?: ""}"
        val cached = cache[key]
        if (cached !== null) return cached as? Bitmap
        val bitmap = runCatching {
            JarFile(jar).use { jf ->
                entryNames.firstNotNullOfOrNull { name ->
                    jf.getEntry(name)?.let { entry ->
                        jf.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                    }
                }
            }
        }.getOrNull()
        cache[key] = bitmap ?: MISS
        return bitmap
    }
}
