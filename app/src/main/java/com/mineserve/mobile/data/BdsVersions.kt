package com.mineserve.mobile.data

import android.content.Context
import android.util.Log
import com.mineserve.mobile.server.BdsSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一个**可安装的** BDS 版本条目。
 *
 * 与 [BdsBuild] 的区别：那个只表示「官方接口此刻给出的两条最新直链」，
 * 一次只有两项、没有任何校验信息；这里的条目来自三处（内置存档清单 /
 * 官方 links 接口 / 带 SHA-256 的远端清单），带直链、摘要与体积，
 * 是所有历史版本选择的唯一数据形状。
 *
 * [version] 用**清单里的版本键**（可能是 `1.26.62` 三段，也可能是
 * `1.20.51.01` 四段），因为最终写进 `bds-version.txt` 的就是它；
 * 判定「是否同一个版本」时一律走 [BdsVersions.sameRelease]（前三段相等即视为同一版），
 * 不能直接比字符串 —— 同一版本在两处清单里段数常常不同。
 */
data class BdsVersionEntry(
    val version: String,
    val channel: BdsChannel,
    val url: String? = null,
    /** 清单公布的 SHA-256；没有公布时为 null，此时只能做结构校验 + 摘要固定 */
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val source: BdsVersionSource = BdsVersionSource.Bundled,
    /** 远端清单里的版本键（用于按需取 `metadata.json`） */
    val remoteKey: String? = null,
    /**
     * 上游**有**摘要，只是还没取回来。
     *
     * 远端清单的索引只列版本号，`sha256` 要单独取 `metadata.json`；
     * 列表里如果只看 [hasDigest]，几乎每一行都会显示「结构校验」，
     * 而实际上它们装的时候是按 SHA-256 硬比对的 —— 这是把「没预取」
     * 说成了「没有校验」，界面不能这么误导。
     */
    val digestExpected: Boolean = false
) {
    /** 安装前能不能做严格的 SHA-256 比对（已取到摘要 或 上游有摘要待取） */
    val strictDigest: Boolean get() = !sha256.isNullOrBlank() || digestExpected

    /** 是否已经拿到权威摘要 */
    val hasDigest: Boolean get() = !sha256.isNullOrBlank()

    /**
     * 是否可以尝试安装。
     *
     * 没有直链也行：清单只给版本号时按官方命名规则拼一条（[BdsVersions.candidates]）。
     * 只有版本号连三段都凑不齐时才判成不可安装。
     */
    val installable: Boolean
        get() = !url.isNullOrBlank() || BdsVersions.segments(version).size >= 3
}

enum class BdsVersionSource {
    /** 随 APK 内置的历史存档清单（老版本，只有直链） */
    Bundled,

    /** 官方 links 接口（永远是最新的稳定版 / Preview） */
    OfficialApi,

    /** 带 SHA-256 的远端清单（当前版本权威来源） */
    RemoteCatalog
}

/** 一次版本清单加载的结果 */
data class BdsVersionCatalog(
    val entries: List<BdsVersionEntry>,
    val fetchedAtMs: Long,
    /** 本次没能连上远端清单，展示的是缓存 + 内置清单 */
    val fromCache: Boolean = false,
    val notes: List<String> = emptyList()
) {
    fun find(version: String): BdsVersionEntry? {
        if (version.isBlank()) return null
        entries.firstOrNull { it.version.equals(version, ignoreCase = true) }?.let { return it }
        // 段数不同（1.21.84 vs 1.21.84.1）也算同一版本，否则「已安装」标记会永远对不上
        return entries.firstOrNull { BdsVersions.sameRelease(it.version, version) }
    }

    fun byChannel(channel: BdsChannel): List<BdsVersionEntry> =
        entries.filter { it.channel == channel }

    fun latest(channel: BdsChannel): BdsVersionEntry? = byChannel(channel).firstOrNull()

    val installableCount: Int get() = entries.count { it.installable }
}

/**
 * BDS 版本号的比较与直链构造规则（纯函数，无 IO）。
 *
 * 这些规则看起来零碎，但每一条都对应一次真实的失败：
 * 字符串比较会把 `1.26.9` 判成比 `1.26.61` 新；段数不同会把
 * 「已装 1.26.62」判成「可更新到 1.26.62.1」；直链主机写死会把整批
 * 1.18 及更早的包全部变成 404。
 */
object BdsVersions {

    /** 官方两条通道在 URL 里的目录段 */
    fun urlPathSegment(channel: BdsChannel): String = when (channel) {
        BdsChannel.Stable -> "bin-linux"
        BdsChannel.Preview -> "bin-linux-preview"
    }

    fun segments(version: String?): List<Int> {
        if (version.isNullOrBlank()) return emptyList()
        return version.trim().split('.', '-', '_')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }

    /** 按数字段比较；段数不同时短的补 0 */
    fun compare(a: String?, b: String?): Int {
        val left = segments(a)
        val right = segments(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    /**
     * [a] 与 [b] 是否是**同一个游戏版本**。
     *
     * 只比前三段：官方文件名带第四段构建号（`1.21.84.1`），而两份清单的
     * 版本键有的是三段（`1.21.84`），有的是四段。若按整串比，
     * 「已安装 1.21.84.1」永远匹配不到清单里的 `1.21.84`，
     * 界面会出现「已装 1.21.84.1 / 可更新到 1.21.84」这种自相矛盾的提示。
     *
     * **预览版例外**：`1.26.70-preview.23` 与 `1.26.70-preview.30` 是两次不同的
     * 构建（包内容、摘要都不同），必须整串相同才算同一版；按前三段比会把
     * 几十个预览构建塌成一条。
     */
    fun sameRelease(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val left = a.trim().lowercase()
        val right = b.trim().lowercase()
        if (left == right) return true
        if (left.contains("preview") || right.contains("preview")) return false
        val ls = segments(left)
        val rs = segments(right)
        if (ls.isEmpty() || rs.isEmpty()) return false
        val size = minOf(3, maxOf(ls.size, rs.size))
        for (i in 0 until size) {
            if (ls.getOrElse(i) { 0 } != rs.getOrElse(i) { 0 }) return false
        }
        return true
    }

    /**
     * 身份键（去重 / 摘要登记 / 元数据目录名）。
     *
     * 稳定版按前三段（`1.21.84.1` → `1.21.84`）；预览版整串保留，
     * 因为 `versions.json` 里的目录名就是 `1.26.70-preview.23` 这种整串，
     * 且每个预览构建都是独立条目。
     */
    fun releaseKey(version: String): String {
        val v = version.trim().lowercase()
        if (v.contains("-preview")) return v
        val parts = v.split('.')
        if (parts.size <= 3) return v
        return parts.take(3).joinToString(".")
    }

    /** 清单缺直链时按官方命名规则拼一条（主机按版本年份选择，见 [candidates]） */
    fun buildUrl(version: String, channel: BdsChannel): String {
        // 清单键写的是 `1.26.70-preview.23`，官方文件名却是 `bedrock-server-1.26.70.23.zip`；
        // 直接拼会 404，所以要把 `-preview.` 换成 `.`。
        val fileVersion = version.trim()
            .replace("-preview.", ".")
            .removeSuffix("-preview")
        return "${hostFor(channel)}/bedrock-server-$fileVersion.zip"
    }

    private fun hostFor(channel: BdsChannel): String = when (channel) {
        BdsChannel.Preview -> "https://www.minecraft.net/bedrockdedicatedserver/bin-linux-preview"
        BdsChannel.Stable -> "https://www.minecraft.net/bedrockdedicatedserver/bin-linux"
    }

    /**
     * 一个条目所有值得尝试的直链（顺序即优先级）。
     *
     * 来源只有两处：清单给的 URL、按命名规则拼出来的 URL。
     * 另外对**最后一段**做补零/去零变体：老包的构建号有的写 `1.16.100.4`、
     * 有的写 `1.20.51.01`，社区清单里这两种写法混着传，差一个字符就是 404。
     *
     * 历史上这里还试过 `minecraft.azureedge.net`（1.18 及更早版本的原始 CDN）。
     * 2026-11 实测该域名已停止解析/连接超时，只剩 `www.minecraft.net` 一套路径，
     * 且它同样服务老版本文件；继续保留只会让每个老版本白等一次超时。
     */
    fun candidates(entry: BdsVersionEntry): List<DownloadSource> {
        val urls = LinkedHashSet<String>()
        val base = entry.url?.trim().orEmpty()
        if (base.isNotEmpty()) urls += base
        if (base.isNotEmpty()) lastSegmentVariants(base).forEach { urls += it }
        // 清单没给直链时用命名规则兜底
        if (urls.isEmpty()) urls += buildUrl(entry.version, entry.channel)
        return urls.map { url ->
            DownloadSource("官方 CDN（www.minecraft.net）", url)
        }
    }

    /** `bedrock-server-1.20.51.01.zip` ↔ `bedrock-server-1.20.51.1.zip` */
    private fun lastSegmentVariants(url: String): List<String> {
        val slash = url.lastIndexOf('/')
        if (slash < 0) return emptyList()
        val name = url.substring(slash + 1)
        if (!name.startsWith("bedrock-server-") || !name.endsWith(".zip", ignoreCase = true)) return emptyList()
        val version = name.removePrefix("bedrock-server-").removeSuffix(".zip")
        val parts = version.split('.')
        if (parts.size < 3) return emptyList()
        val last = parts.last()
        if (last.isEmpty() || !last.all(Char::isDigit)) return emptyList()
        val head = parts.dropLast(1).joinToString(".")
        val variants = buildList {
            if (last.length > 1 && last.startsWith("0")) add(last.trimStart('0').ifEmpty { "0" })
            if (last.length == 1) add(last.padStart(2, '0'))
        }
        val prefix = url.substring(0, slash + 1)
        return variants.distinct().filter { it != last }
            .map { prefix + "bedrock-server-$head.$it.zip" }
    }
}

/**
 * 历史版本目录：**内置清单 + 官方接口 + 带摘要的远端清单** 三路合并。
 *
 * 为什么不是「一个接口拉全部」：
 *  - Mojang 只公布**当前**两条直链（[BdsSupport.DOWNLOAD_LINKS_API]），
 *    历史版本在官方没有任何索引可查；
 *  - 历史版本的老文件当年发在 `minecraft.azureedge.net`，后来的发在
 *    `www.minecraft.net/bedrockdedicatedserver`，命名规则只管路径不管构建号
 *    （`1.21.84` 的文件名是 `bedrock-server-1.21.84.1.zip`），所以**必须**有清单
 *    把完整文件名记下来，靠推算一定 404；
 *  - SHA-256 只有远端清单才有，而它只覆盖近两年。因此老版本走「结构校验 +
 *    摘要固定」，新版本走「清单摘要硬比对」，两条路都必须能装。
 *
 * 合并优先级（后者覆盖前者的同名字段）：内置清单 → 官方接口 → 远端清单。
 * 键用 [BdsVersions.sameRelease]，所以 `1.21.84` 与 `1.21.84.1` 不会在列表里出现两次。
 */
object BdsVersionCatalogLoader {

    private const val TAG = "BdsVersionCatalog"

    /** 内置历史清单（随 APK 分发，没有网络也能列出老版本） */
    const val ASSET_PATH = "bds/bds-versions.json"

    /** 带 SHA-256 的远端清单（Mojang 官方没有历史索引，这是社区维护的结构化元数据） */
    const val REMOTE_INDEX_URL =
        "https://raw.githubusercontent.com/EndstoneMC/bedrock-server-data/v2/versions.json"

    fun remoteMetadataUrl(channel: BdsChannel, key: String): String {
        val dir = if (channel == BdsChannel.Preview) "preview" else "release"
        return "https://raw.githubusercontent.com/EndstoneMC/bedrock-server-data/v2/$dir/$key/metadata.json"
    }

    /** 清单缓存：半小时内重复进页面不再联网 */
    const val CACHE_TTL_MS = 30 * 60 * 1000L

    private const val PREF = "bds_version_catalog_v2"
    private const val KEY_INDEX = "remote_index"
    private const val KEY_TS = "remote_ts"
    private const val KEY_META = "remote_meta"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 内置清单解析结果（每个进程只读一次 assets） */
    @Volatile
    private var bundledCache: List<BdsVersionEntry>? = null

    /** 进程内的清单合并结果，避免每次进页面都重解析 */
    @Volatile
    private var memory: BdsVersionCatalog? = null

    // ── 读取 ─────────────────────────────────────────────────────

    fun bundled(context: Context): List<BdsVersionEntry> {
        bundledCache?.let { return it }
        val parsed = runCatching {
            val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            parseBundled(text)
        }.onFailure { runCatching { Log.w(TAG, "读取内置 BDS 版本清单失败: ${it.message}") } }
            .getOrDefault(emptyList())
        bundledCache = parsed
        return parsed
    }

    /**
     * 加载完整清单。
     *
     * 失败语义：**远端不可用不算失败** —— 内置清单 + 官方接口通常仍能列出可装版本，
     * 界面会标注「离线清单」。只有三路全空才算失败。
     */
    suspend fun load(context: Context, force: Boolean = false): Result<BdsVersionCatalog> =
        withContext(Dispatchers.IO) {
            if (!force) memory?.let { cached ->
                if (System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS) return@withContext Result.success(cached)
            }
            val notes = mutableListOf<String>()
            val seed = bundled(context)

            val api = runCatching { fetchOfficialLatest() }
                .onFailure { notes += "官方接口不可用：${it.message}" }
                .getOrDefault(emptyList())

            val prefs = prefs(context)
            val cachedIndex = prefs.getString(KEY_INDEX, null)
            val cachedTs = prefs.getLong(KEY_TS, 0L)
            val fresh = force || cachedIndex == null || System.currentTimeMillis() - cachedTs > CACHE_TTL_MS
            var fromCache = false
            var remoteText = cachedIndex
            if (fresh) {
                remoteText = runCatching { fetchText(REMOTE_INDEX_URL) }
                    .onFailure { notes += "版本清单不可用：${it.message}" }
                    .getOrNull()
                if (remoteText != null) {
                    prefs.edit().putString(KEY_INDEX, remoteText)
                        .putLong(KEY_TS, System.currentTimeMillis()).apply()
                } else {
                    fromCache = cachedIndex != null
                }
            } else {
                fromCache = true
            }
            val remote = remoteText?.let { runCatching { parseRemoteIndex(it) }.getOrNull() }.orEmpty()
            val meta = readMeta(prefs)

            val merged = merge(seed, api, remote, meta)
            if (merged.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("没有可用的 BDS 版本清单（内置清单为空且远端不可用）")
                )
            }
            val catalog = BdsVersionCatalog(
                entries = merged,
                fetchedAtMs = System.currentTimeMillis(),
                fromCache = fromCache,
                notes = notes
            )
            memory = catalog
            Result.success(catalog)
        }

    /** 供调试/「刷新」用：清掉进程内与磁盘缓存 */
    fun clearCache(context: Context) {
        memory = null
        runCatching { prefs(context).edit().clear().apply() }
    }

    /**
     * 把某个版本补成**可下载**的条目（直链 + 摘要）。
     *
     * 清单里 1.21.62+ 的条目只有版本号，直链与 SHA-256 要单独取
     * `metadata.json`；这里按需拉一次并落缓存。老版本没有 metadata 时
     * 直接返回内置清单给的直链（摘要为空，安装时走结构校验）。
     */
    suspend fun resolve(context: Context, version: String): Result<BdsVersionEntry> =
        withContext(Dispatchers.IO) {
            val catalog = load(context).getOrElse { return@withContext Result.failure(it) }
            val entry = catalog.find(version)
                ?: return@withContext Result.failure(IllegalStateException("版本清单里没有 $version"))
            if (entry.hasDigest && entry.installable) return@withContext Result.success(entry)

            val prefs = prefs(context)
            val meta = readMeta(prefs).toMutableMap()
            val keys = listOfNotNull(entry.remoteKey, BdsVersions.releaseKey(entry.version)).distinct()
            // 远端清单只从 1.21.62（Preview 1.21.70）开始收录：更早的版本去问
            // metadata 一定是 404，白等一次超时还往日志里塞一条失败。
            if (!remoteCovers(entry.channel, entry.version)) {
                return@withContext if (entry.installable) Result.success(entry)
                else Result.failure(IllegalStateException("版本 $version 没有可用的下载地址"))
            }
            for (key in keys) {
                val cacheKey = "${entry.channel.name}|$key"
                val hit = meta[cacheKey]?.let { parseMetaValue(it) }
                val resolved = hit ?: runCatching {
                    val text = fetchText(remoteMetadataUrl(entry.channel, key))
                    val parsed = parseRemoteMetadata(text)
                    if (parsed != null) {
                        meta[cacheKey] = "${parsed.first}|${parsed.second}"
                        writeMeta(prefs, meta)
                    }
                    parsed
                }.getOrNull()
                if (resolved != null) {
                    return@withContext Result.success(
                        entry.copy(
                            url = resolved.first,
                            sha256 = resolved.second,
                            source = BdsVersionSource.RemoteCatalog,
                            remoteKey = key
                        )
                    )
                }
            }
            if (entry.installable) Result.success(entry)
            else Result.failure(IllegalStateException("版本 $version 没有可用的下载地址"))
        }

    // ── 解析 ─────────────────────────────────────────────────────

    /** 远端元数据覆盖的最早版本（两个通道不同：Preview 起步更晚） */
    private fun remoteCovers(channel: BdsChannel, version: String): Boolean {
        val floor = if (channel == BdsChannel.Preview) "1.21.70" else "1.21.62"
        return BdsVersions.compare(version, floor) >= 0
    }

    /**
     * 内置清单格式（刻意用短键：这份 JSON 打进 APK，100 多条时体积差一倍）：
     * `{"versions":[{"v":"1.20.51.01","c":"stable","u":"https://…","s":"<sha256>","n":12345}]}`
     *
     * 手工遍历 JsonElement 而不是建 @Serializable 数据类，与 [BdsBuildCatalog.parse]
     * 同一条约定：清单由外部维护，随时可能多出字段，多出来的必须被忽略而不是让整次加载失败。
     */
    fun parseBundled(text: String): List<BdsVersionEntry> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val versions = runCatching { root.jsonObject["versions"]?.jsonArray }.getOrNull() ?: return emptyList()
        val result = mutableListOf<BdsVersionEntry>()
        for (element in versions) {
            val obj = runCatching { element.jsonObject }.getOrNull() ?: continue
            val version = obj.string("v")?.takeIf { it.isNotBlank() } ?: continue
            val channel = when (obj.string("c")?.lowercase()) {
                "preview" -> BdsChannel.Preview
                else -> BdsChannel.Stable
            }
            val url = obj.string("u")?.takeIf { it.startsWith("http") }
            result += BdsVersionEntry(
                version = version,
                channel = channel,
                url = url,
                sha256 = obj.string("s")?.takeIf { it.length == 64 },
                sizeBytes = obj.string("n")?.toLongOrNull(),
                source = BdsVersionSource.Bundled
            )
        }
        return result
    }

    /** 远端索引：`{"release":{"latest":"…","versions":["…"]},"preview":{…}}` */
    fun parseRemoteIndex(text: String): List<BdsVersionEntry> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<BdsVersionEntry>()
        listOf("release" to BdsChannel.Stable, "preview" to BdsChannel.Preview).forEach { (field, channel) ->
            val versions = runCatching {
                root.jsonObject[field]?.jsonObject?.get("versions")?.jsonArray
            }.getOrNull() ?: return@forEach
            versions.forEach { element ->
                val key = runCatching { element.jsonPrimitive.content }.getOrNull() ?: return@forEach
                if (key.isBlank()) return@forEach
                result += BdsVersionEntry(
                    version = key,
                    channel = channel,
                    source = BdsVersionSource.RemoteCatalog,
                    remoteKey = key
                )
            }
        }
        return result
    }

    /** 单版本元数据：`{"binary":{"linux":{"url":"…","sha256":"…"}}}` → (url, sha) */
    fun parseRemoteMetadata(text: String): Pair<String, String?>? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val linux = runCatching {
            root.jsonObject["binary"]?.jsonObject?.get("linux")?.jsonObject
        }.getOrNull() ?: return null
        val url = runCatching { linux["url"]?.jsonPrimitive?.content }.getOrNull()
            ?.takeIf { it.startsWith("http") } ?: return null
        val sha = runCatching { linux["sha256"]?.jsonPrimitive?.content }.getOrNull()
            ?.takeIf { it.length == 64 }
        return url to sha
    }

    private fun parseMetaValue(value: String): Pair<String, String?>? {
        val url = value.substringBefore('|').takeIf { it.startsWith("http") } ?: return null
        val sha = value.substringAfter('|', "").takeIf { it.length == 64 }
        return url to sha
    }

    // ── 合并 ─────────────────────────────────────────────────────

    /**
     * 三路合并（internal 是为了让 `BdsVersionMatrixTest` 能在离线状态下
     * 用真实清单校验合并规则，不必构造 Android Context）。
     */
    internal fun merge(
        seed: List<BdsVersionEntry>,
        api: List<BdsVersionEntry>,
        remote: List<BdsVersionEntry>,
        meta: Map<String, String>
    ): List<BdsVersionEntry> {
        val acc = LinkedHashMap<String, BdsVersionEntry>()
        fun keyOf(entry: BdsVersionEntry) = entry.channel.name + "|" + BdsVersions.releaseKey(entry.version)

        // ① 内置：baseline（有直链、无摘要）
        seed.forEach { acc.putIfAbsent(keyOf(it), it) }
        // ② 官方接口：最新版直链最可靠（清单偶尔滞后一两天）
        api.forEach { entry ->
            val key = keyOf(entry)
            val old = acc[key]
            acc[key] = (old ?: entry).copy(
                url = entry.url ?: old?.url,
                source = if (old == null) BdsVersionSource.OfficialApi else old.source,
                remoteKey = old?.remoteKey ?: entry.remoteKey
            )
        }
        // ③ 远端清单：有 SHA-256，字段最权威，但直链要等 resolve 才补
        remote.forEach { entry ->
            val key = keyOf(entry)
            val old = acc[key]
            val cached = meta["${entry.channel.name}|${entry.remoteKey}"]?.let { parseMetaValue(it) }
            acc[key] = BdsVersionEntry(
                version = old?.version ?: entry.version,
                channel = entry.channel,
                url = cached?.first ?: old?.url,
                sha256 = cached?.second,
                sizeBytes = old?.sizeBytes,
                source = BdsVersionSource.RemoteCatalog,
                remoteKey = entry.remoteKey,
                // 远端清单收录的版本基本都公布了 linux 摘要；即使这次没预取到，
                // 安装时会按 SHA-256 校验，界面上就该显示「SHA-256」而不是「结构校验」
                digestExpected = true
            )
        }
        return acc.values
            .filter { it.version.isNotBlank() }
            .sortedWith(
                // 先按稳定/预览分组，再按版本号降序：列表第一项必须是真正的最新版
                compareBy<BdsVersionEntry> { if (it.channel == BdsChannel.Stable) 0 else 1 }
                    .thenComparator { a, b -> BdsVersions.compare(b.version, a.version) }
            )
    }

    // ── 网络 ─────────────────────────────────────────────────────

    private fun fetchOfficialLatest(): List<BdsVersionEntry> {
        val text = fetchText(BdsSupport.DOWNLOAD_LINKS_API)
        return BdsBuildCatalog.parse(text).map { build ->
            BdsVersionEntry(
                version = build.version,
                channel = build.channel,
                url = build.url,
                source = BdsVersionSource.OfficialApi,
                remoteKey = BdsVersions.releaseKey(build.version)
            )
        }
    }

    /**
     * 取文本。
     *
     * `Accept-Encoding: identity` 是硬要求：HttpURLConnection 会自动加 gzip 并在
     * 读流时透明解压，但 `Content-Length` 仍是压缩前的值，跟实际读到的字节数
     * 对不上 —— 历史上就是这条让「下载不完整」判定误触发、无限换源。
     */
    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Minexa/1.0 (Android)")
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun readMeta(prefs: android.content.SharedPreferences): Map<String, String> = runCatching {
        val raw = prefs.getString(KEY_META, null) ?: return emptyMap()
        val obj = json.parseToJsonElement(raw).jsonObject
        obj.mapNotNull { (k, v) -> runCatching { k to v.jsonPrimitive.content }.getOrNull() }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeMeta(prefs: android.content.SharedPreferences, meta: Map<String, String>) {
        runCatching {
            // 只留最近 64 条：每条 ~120 字节，长期不清理会无限长
            val kept = if (meta.size > 64) meta.entries.toList().takeLast(64) else meta.entries
            val text = kept.joinToString(",", "{", "}") { (k, v) ->
                "\"" + k.replace("\\", "\\\\").replace("\"", "\\\"") + "\":\"$v\""
            }
            prefs.edit().putString(KEY_META, text).apply()
        }
    }
}

private fun JsonObject.string(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
