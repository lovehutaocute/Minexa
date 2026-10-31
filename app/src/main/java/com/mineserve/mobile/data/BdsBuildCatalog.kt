package com.mineserve.mobile.data

import android.content.Context
import android.util.Log
import com.mineserve.mobile.server.BdsSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 官方 BDS 的两条发布通道。
 *
 * 官方 links 接口同时给出两组直链，`downloadType` 是唯一可靠的区分依据
 * （文件名、目录都靠不住）：`serverBedrockLinux` 是稳定版，
 * `serverBedrockPreviewLinux` 是 Preview。Windows 那两条在本项目里没有意义
 * （跑不了），解析时直接忽略。
 */
enum class BdsChannel(val downloadType: String) {
    Stable("serverBedrockLinux"),
    Preview("serverBedrockPreviewLinux");

    companion object {
        fun of(downloadType: String?): BdsChannel? {
            if (downloadType.isNullOrBlank()) return null
            return entries.firstOrNull { it.downloadType.equals(downloadType, ignoreCase = true) }
        }
    }
}

/** 一个可直接下载的官方构建：[channel] 表明它来自哪条通道。 */
data class BdsBuild(val version: String, val url: String, val channel: BdsChannel)

/**
 * 一次版本检测的结果。
 *
 * [fromCache] 为 true 说明这次**没能连上官方接口**，展示的是上一次的缓存 ——
 * 界面必须据此提示「离线数据」，否则用户会以为官方真的没发新版。
 * 与「连上了但官方返回的版本没变」是两件不同的事。
 */
data class BdsCatalog(
    val builds: List<BdsBuild>,
    val fetchedAtMs: Long,
    val fromCache: Boolean = false
) {
    fun build(channel: BdsChannel): BdsBuild? = builds.firstOrNull { it.channel == channel }
    val stable: BdsBuild? get() = build(BdsChannel.Stable)
    val preview: BdsBuild? get() = build(BdsChannel.Preview)
}

/**
 * Mojang 官方 BDS 版本目录：拉取 / 解析 / 缓存 / 版本比较。
 *
 * 为什么单独一层：更新提醒要在**每次进服务器管理页**时给出结论，
 * 而官方接口在移动网络下偶发超时；没有缓存就会出现「有时提示有新版本、
 * 刷新一下又没了」。缓存同时承担「离线时仍能看到上次检测结果」。
 *
 * 解析刻意手工遍历 JsonElement 而不是建数据类：官方随时可能增减平台条目，
 * 多出来的字段必须被忽略而不是让整次检测失败（见 [BdsSupport.parseLatestLinux]
 * 的同一条约定）。
 */
object BdsBuildCatalog {

    private const val TAG = "BdsBuildCatalog"

    const val API = BdsSupport.DOWNLOAD_LINKS_API

    /** 缓存有效期：10 分钟内重复进页面不再请求官方接口 */
    const val CACHE_TTL_MS = 10 * 60 * 1000L

    private const val PREF = "bds_build_catalog"
    private const val KEY_JSON = "catalog_json_v1"
    private const val KEY_TS = "catalog_ts_v1"

    /** 解析官方 links 响应 → 两条通道的构建（解析不出的通道缺席，由调用方兜底）。 */
    fun parse(jsonText: String): List<BdsBuild> {
        val root = runCatching { Json.parseToJsonElement(jsonText) }.getOrNull() ?: return emptyList()
        val links = runCatching {
            root.jsonObject["result"]?.jsonObject?.get("links")?.jsonArray
        }.getOrNull() ?: return emptyList()
        val result = mutableListOf<BdsBuild>()
        for (element in links) {
            val obj = runCatching { element.jsonObject }.getOrNull() ?: continue
            val type = runCatching { obj["downloadType"]?.jsonPrimitive?.content }.getOrNull()
            val channel = BdsChannel.of(type) ?: continue
            val url = runCatching { obj["downloadUrl"]?.jsonPrimitive?.content }.getOrNull() ?: continue
            val version = BdsSupport.versionFromUrl(url) ?: continue
            if (result.none { it.channel == channel }) {
                result += BdsBuild(version, url, channel)
            }
        }
        return result
    }

    /**
     * 按数字段比较两个 BDS 版本号（`1.26.61.1` vs `1.26.70.22`）。
     *
     * 不能直接比字符串：`1.26.9` 会大于 `1.26.61`。段数不同时短的用 0 补齐
     * （`1.26` == `1.26.0`），非数字段按 0 处理，保证任何输入都有确定结果。
     */
    fun compareVersions(a: String?, b: String?): Int {
        val left = segments(a)
        val right = segments(b)
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun segments(version: String?): List<Int> {
        if (version.isNullOrBlank()) return emptyList()
        return version.trim()
            .split('.', '-', '_')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }

    /** [candidate] 是否比 [current] 新；[current] 未知时一律认为「有事可做」。 */
    fun isNewer(candidate: String?, current: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        if (current.isNullOrBlank()) return true
        return compareVersions(candidate, current) > 0
    }

    /**
     * 读取版本目录。
     *
     * @param forceRefresh true 时忽略 TTL 直接请求；请求失败且已有缓存则返回缓存
     *  （[BdsCatalog.fromCache] = true），而不是抛异常 —— 「检查更新」按钮
     *  在弱网下也应该给出上次的结果而不是一句失败。
     */
    suspend fun load(context: Context, forceRefresh: Boolean = false): Result<BdsCatalog> =
        withContext(Dispatchers.IO) {
            val cached = readCache(context)
            if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS) {
                return@withContext Result.success(cached)
            }
            runCatching { fetch() }
                .map { builds ->
                    if (builds.isEmpty()) throw IllegalStateException("官方接口未返回 Linux 版 BDS 构建")
                    val snapshot = BdsCatalog(builds, System.currentTimeMillis())
                    writeCache(context, builds, snapshot.fetchedAtMs)
                    snapshot
                }
                .recoverCatching { error ->
                    Log.w(TAG, "拉取官方 BDS 版本失败: ${error.message}")
                    // 有缓存就给缓存：界面上会标注「离线数据」，比直接报错有用
                    cached?.copy(fromCache = true) ?: throw error
                }
        }

    private fun fetch(): List<BdsBuild> {
        val conn = URL(API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("User-Agent", "Minexa/1.0 (Android)")
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun readCache(context: Context): BdsCatalog? = runCatching {
        val p = prefs(context)
        val ts = p.getLong(KEY_TS, 0L)
        val raw = p.getString(KEY_JSON, null) ?: return null
        val builds = parse(raw)
        if (builds.isEmpty()) return null
        BdsCatalog(builds, ts)
    }.getOrNull()

    private fun writeCache(context: Context, builds: List<BdsBuild>, atMs: Long) {
        runCatching {
            // 回写的是「官方原始结构」而不是我们的数据类：这样缓存和线上响应
            // 走同一个解析函数，将来解析规则变化时缓存不会变成另一种格式。
            val links = builds.joinToString(",") { build ->
                "{\"downloadType\":\"${build.channel.downloadType}\",\"downloadUrl\":\"${build.url}\"}"
            }
            prefs(context).edit()
                .putString(KEY_JSON, "{\"result\":{\"links\":[$links]}}")
                .putLong(KEY_TS, atMs)
                .apply()
        }
    }

    /** 供调试/诊断：清掉缓存，下次必然走网络 */
    fun clearCache(context: Context) {
        runCatching { prefs(context).edit().clear().apply() }
    }
}
