package com.mineserve.mobile.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在线崩溃知识库（知识源 L3）。
 *
 * 形态是**可签发的规则包**而不是「把日志上传给云端」：
 *  - 规则包与内置 `server_errors.json` 同 schema，id 必须以 `kb-` 开头；
 *  - 下载后做体积 / JSON / 结构 / 前缀校验，通过才原子落盘到 `filesDir/kb/`；
 *  - 落盘后重载 [CrashReportAnalyzer]，立即生效；
 *  - 断网、超时、包损坏一律**保留旧版**并静默降级（分析永远可用）。
 *
 * 隐私：这条链路只下载规则，不上传任何日志或设备信息。
 * 需要云端协助时由用户显式「分享/上报」，不在这里发生。
 */
class CrashKnowledgeBase(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("crash_kb", Context.MODE_PRIVATE)
    }

    fun packFile(): File = File(CrashReportAnalyzer.knowledgeRulesDir(context), PACK_FILE)

    fun cachedVersion(): Int = prefs.getInt(KEY_VERSION, 0)

    fun lastRefreshAt(): Long = prefs.getLong(KEY_LAST_REFRESH, 0L)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 到期自动刷新（默认 24h）。失败不抛异常，返回是否真的更新了规则包。
     */
    suspend fun refreshIfStale(minIntervalMs: Long = DEFAULT_INTERVAL_MS): Boolean {
        if (!isEnabled()) return false
        if (System.currentTimeMillis() - lastRefreshAt() < minIntervalMs) return false
        return refresh()
    }

    /** 强制刷新（用户在分析页点「更新知识库」）。 */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        for (url in SOURCES) {
            val text = runCatching { fetch(url) }.getOrNull() ?: continue
            if (!validate(text)) {
                Log.w(TAG, "知识库规则包校验失败，跳过: $url")
                continue
            }
            if (writeAtomically(text)) {
                prefs.edit()
                    .putLong(KEY_LAST_REFRESH, System.currentTimeMillis())
                    .putInt(KEY_VERSION, parseVersion(text))
                    .apply()
                // 重新装载规则：内置 + 知识库 + 用户自定义（后者优先级最高）
                runCatching { CrashReportAnalyzer.initialize(context) }
                Log.i(TAG, "知识库规则包已更新: ${parseVersion(text)}")
                return@withContext true
            }
        }
        // 全部失败：记录一次尝试时间，避免每次崩溃都反复拉取
        prefs.edit().putLong(KEY_LAST_REFRESH, System.currentTimeMillis()).apply()
        false
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            requestMethod = "GET"
            // 与下载层同样的坑：默认 gzip 会让 Content-Length 与实际字节不一致，
            // 这里用 identity 保证「读多少就是多少」，便于体积上限判断。
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Minexa-CrashKB/1")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val length = conn.contentLengthLong
            if (length > MAX_PACK_BYTES) throw IllegalStateException("规则包过大: $length")
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (text.length > MAX_PACK_BYTES) throw IllegalStateException("规则包过大: ${text.length}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** 结构校验：规则 id 必须带 `kb-` 前缀，且至少有一条可用规则。 */
    private fun validate(text: String): Boolean {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
        val rules = root["rules"] as? JsonArray ?: return false
        if (rules.isEmpty()) return false
        return rules.all { element ->
            val obj = element as? JsonObject ?: return@all false
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@all false
            val label = (obj["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@all false
            val hasMatcher = (obj["contains"] as? JsonArray)?.isNotEmpty() == true ||
                (obj["regex"] as? JsonPrimitive)?.takeIf { it.isString } != null ||
                (obj["exitCodes"] as? JsonArray)?.isNotEmpty() == true
            id.startsWith(CrashInsightEngine.KB_RULE_PREFIX) && label.isNotBlank() && hasMatcher
        }
    }

    private fun parseVersion(text: String): Int {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return 0
        return (root["version"] as? JsonPrimitive)?.intOrNull ?: 0
    }

    /** 临时文件 + rename：避免下载中断留下半个规则包，把整套归因弄坏。 */
    private fun writeAtomically(text: String): Boolean = runCatching {
        val target = packFile()
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$PACK_FILE.tmp")
        tmp.writeText(text)
        if (target.exists() && !target.delete()) return@runCatching false
        tmp.renameTo(target)
    }.getOrDefault(false)

    companion object {
        private const val TAG = "CrashKnowledgeBase"
        private const val PACK_FILE = "crash_rules_pack.json"
        private const val KEY_VERSION = "pack_version"
        private const val KEY_LAST_REFRESH = "last_refresh"
        private const val KEY_ENABLED = "online_enabled"
        private const val MAX_PACK_BYTES = 512 * 1024
        private const val DEFAULT_INTERVAL_MS = 24L * 60L * 60L * 1000L

        /**
         * 规则包地址（国内镜像优先，GitHub 兜底）。
         *
         * 发布方式：把 `docs/crash-rules-pack.example.json` 按格式扩充后放到仓库
         * `docs/crash-rules-pack.json`，这两个地址即自动生效；App 侧不需要发版。
         */
        private val SOURCES = listOf(
            "https://cdn.jsdelivr.net/gh/lovehutaocute/Minexa@master/docs/crash-rules-pack.json",
            "https://raw.githubusercontent.com/lovehutaocute/Minexa/master/docs/crash-rules-pack.json"
        )
    }
}
