package com.mineserve.mobile.server

import com.mineserve.mobile.data.BdsChannel
import com.mineserve.mobile.data.BdsPackageIntegrity
import com.mineserve.mobile.data.BdsVersionCatalogLoader
import com.mineserve.mobile.data.BdsVersionEntry
import com.mineserve.mobile.data.BdsVersions
import com.mineserve.mobile.data.MultiSourceDownload
import com.mineserve.mobile.data.MultiThreadDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * BDS 历史版本兼容性矩阵（按需执行，默认跳过）。
 *
 * 为什么要有：历史版本 = 历史依赖链。1.21+ 是静态单文件，1.20 及更早要
 * `libcurl`/`libssl`，包结构（顶层目录、调试符号、是否自带 properties）也各不相同。
 * 靠人工一个个试，既慢又覆盖不到「用户正好选了某个冷门 build」的情况。
 *
 * 两个模式，分开跑是因为它们的门槛不同：
 *
 *  - `-DbdsMatrix=catalog`：**不需要能下包**。用真实清单（内置 + 远端 + 官方接口）
 *    校验「版本 ↔ 文件名 ↔ 主机 ↔ 摘要」的映射规则，几十秒跑完全部 150+ 条。
 *    这一类错误（主机选错、preview 命名没换、重复条目、排序错）占到实际翻车的大头，
 *    而且它们在下载之前就会导致 404。
 *  - `-DbdsMatrix=download`：**需要能访问 Mojang CDN**。逐版本下载 → SHA-256 →
 *    解出安装计划 → 读 ELF 依赖 → 给出「可直接跑 / 需容器补库 / 不可用」的结论。
 *    有 `-DbdsMatrixLimit=N` 控制条数（每个包 30~90MB）。
 *
 * 报告写到 `app/build/bds-matrix/`：`catalog-report.md|csv`、`package-report.md|csv`。
 * 结果文件不进仓库，只作排查依据。
 */
class BdsVersionMatrixTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val mode: String = System.getProperty("bdsMatrix").orEmpty()
    private val limit: Int = System.getProperty("bdsMatrixLimit")?.toIntOrNull() ?: 0

    /** 逗号分隔的显式版本列表：跑指定几个版本比 `limit=N` 更有针对性 */
    private val explicitVersions: List<String> =
        System.getProperty("bdsMatrixVersions").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 探测模式只看内置清单（手写的那份最容易记错构建号） */
    private val seedOnly: Boolean = System.getProperty("bdsMatrixSeedOnly") == "1"

    /** 与 APK 一起分发的 x86 运行库（判断「缺库」时必须算进来） */
    private fun bundledX86LibDir(): File? = listOf(
        File("src/main/assets/box64/x86libs"),
        File("app/src/main/assets/box64/x86libs")
    ).firstOrNull { it.isDirectory }

    private val outDir = File("build/bds-matrix").apply { mkdirs() }

    /** 内置清单（相对模块目录；Gradle 测试的工作目录就是 `app/`） */
    private fun seedText(): String {
        val file = listOf(
            File("src/main/assets/bds/bds-versions.json"),
            File("app/src/main/assets/bds/bds-versions.json")
        ).firstOrNull { it.isFile } ?: error("找不到内置 BDS 清单")
        return file.readText()
    }

    private fun getText(url: String, timeoutMs: Int = 15_000): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Minexa/1.0 (Matrix)")
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun mergedEntries(): List<BdsVersionEntry> {
        val seed = BdsVersionCatalogLoader.parseBundled(seedText())
        assertTrue("内置清单为空", seed.isNotEmpty())
        val official = getText("https://net-secondary.web.minecraft-services.net/api/v1.0/download/links")
            ?.let { text ->
                com.mineserve.mobile.data.BdsBuildCatalog.parse(text).map { build ->
                    BdsVersionEntry(
                        version = build.version,
                        channel = build.channel,
                        url = build.url,
                        source = com.mineserve.mobile.data.BdsVersionSource.OfficialApi,
                        remoteKey = BdsVersions.releaseKey(build.version)
                    )
                }
            }.orEmpty()
        val remote = getText(BdsVersionCatalogLoader.REMOTE_INDEX_URL)
            ?.let { BdsVersionCatalogLoader.parseRemoteIndex(it) }
            .orEmpty()
        return BdsVersionCatalogLoader.merge(seed, official, remote, emptyMap())
    }

    // ── 模式一：清单与直链规则（无需下包） ───────────────────────

    @Test
    fun catalogMatrix() {
        assumeTrue("未开启（-DbdsMatrix=catalog）", mode == "catalog" || mode == "all")

        val entries = mergedEntries()
        val stable = entries.filter { it.channel == BdsChannel.Stable }
        val preview = entries.filter { it.channel == BdsChannel.Preview }
        val failures = mutableListOf<String>()

        // ① 去重：同一通道下「前三段版本号」不能出现两次，否则列表里会有两个几乎一样的条目
        val dup = entries.groupBy { it.channel.name + "|" + BdsVersions.releaseKey(it.version) }
            .filterValues { it.size > 1 }
        if (dup.isNotEmpty()) failures += "重复条目 ${dup.keys}"

        // ② 排序：稳定版必须在 Preview 之前，且各自按版本号降序（列表首项 = 最新）
        if (entries != entries.sortedWith(entriesComparator())) {
            failures += "排序不符合「稳定在前 + 版本降序」"
        }

        // ③ 可安装性：三段版本号或直链至少有一样，否则用户点了只会得到一句「没有地址」
        entries.filter { !it.installable }.forEach { failures += "不可安装：${it.version}" }

        // ④ 主机规则：所有条目都走 www.minecraft.net；azureedge 已停用，
        //    出现它等于让每个老版本白等一次超时
        val hostErrors = entries.mapNotNull { entry ->
            val url = entry.url ?: return@mapNotNull null
            when {
                url.contains("azureedge") -> "${entry.version} 指向已停用的旧 CDN：$url"
                !url.startsWith("https://www.minecraft.net/") -> "${entry.version} 主机异常：$url"
                else -> null
            }
        }
        failures += hostErrors

        // ⑤ 候选链：每个条目至少有一个候选，且候选里不应出现 `bedrock-server-1.26.70-preview.23.zip` 这种错名
        entries.forEach { entry ->
            val urls = BdsVersions.candidates(entry).map { it.url }
            if (urls.isEmpty()) failures += "无候选直链：${entry.version}"
            urls.filter { it.contains("-preview.") && it.contains("bedrock-server-") }
                .forEach { failures += "预览版文件名错误：$it" }
        }

        // ⑥ 远端元数据抽样：真实取 metadata，确认「清单键 ↔ 文件名 ↔ SHA-256」对得上
        val warnings = mutableListOf<String>()
        val sample = (stable.take(4) + preview.take(3))
            .filter { it.remoteKey != null }
        val metaRows = sample.mapNotNull { entry ->
            val text = getText(
                BdsVersionCatalogLoader.remoteMetadataUrl(entry.channel, entry.remoteKey!!)
            ) ?: run {
                // 上游索引偶尔先列出条目、后补目录（例如 release 列表里出现只有
                // preview 目录的版本）。这属于外部数据缺口，记为警告而不是失败：
                // 运行时 resolve() 会退回命名规则拼直链，失败也有明确报错。
                warnings += "上游缺元数据：${entry.channel}/${entry.remoteKey}"
                return@mapNotNull null
            }
            val parsed = BdsVersionCatalogLoader.parseRemoteMetadata(text)
            if (parsed == null) {
                failures += "元数据无法解析：${entry.remoteKey}"
                return@mapNotNull null
            }
            val (url, sha) = parsed
            if (sha == null || sha.length != 64) failures += "摘要格式不对：${entry.remoteKey}"
            if (!url.contains("/bin-${if (entry.channel == BdsChannel.Preview) "linux-preview" else "linux"}/")) {
                failures += "通道路径错误：$url"
            }
            val fileNameVersion = BdsSupport.versionFromUrl(url)
            // 只比前三段：官方预览版键形如 `1.26.70-preview.23`，而文件名里的
            // 构建号有时与之无关（实测曾出现 `-preview.23` → `1.26.70.20.zip`）。
            // 对用户有意义的约束是「同一个 a.b.c 版本 + 同一条通道目录」。
            val keyCore = entry.version.substringBefore("-preview").trim()
            if (fileNameVersion == null ||
                BdsVersions.compare(BdsVersions.releaseKey(fileNameVersion), BdsVersions.releaseKey(keyCore)) != 0
            ) {
                failures += "文件名版本与清单键不符：${entry.version} → $url"
            }
            "${entry.version} | ${entry.channel} | $url | ${sha?.take(12)}…"
        }

        // ⑦ 预览版不能被去重键塌掉：远端索引里有几十个 preview，塌成十几个就是
        // 把「独立构建」当成「同一版本」，用户会找不到想装的那个 build
        if (preview.size < 40) {
            failures += "Preview 条目只有 ${preview.size} 条，疑似被去重键合并（应 ≥40）"
        }
        if (stable.size < 100) {
            failures += "稳定版只有 ${stable.size} 条，覆盖不足"
        }

        writeCatalogReport(entries, stable.size, preview.size, metaRows, failures, warnings)
        assertTrue("兼容性矩阵发现问题：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    // ── 模式二：真实包逐版本体检（需要 CDN 可达） ────────────────

    @Test
    fun packageMatrix() {
        assumeTrue("未开启（-DbdsMatrix=download）", mode == "download" || mode == "all")

        val all = mergedEntries()
        val targets = when {
            explicitVersions.isNotEmpty() -> explicitVersions.mapNotNull { wanted ->
                all.firstOrNull { BdsVersions.sameRelease(it.version, wanted) }
                    ?: all.firstOrNull { it.version.equals(wanted, ignoreCase = true) }
                    ?: run {
                        println("[bds-matrix] 清单里没有 $wanted，跳过")
                        null
                    }
            }
            limit > 0 -> all.take(limit)
            else -> all
        }
        val rows = mutableListOf<Row>()
        val cache = File(outDir, "packages").apply { mkdirs() }

        targets.forEach { entry ->
            rows += inspect(entry, cache)
        }

        writePackageReport(rows)
        reportToUser(rows)
        // 不因单个版本失败而让矩阵整体失败：这张表本身就是结论
        assertTrue("矩阵未产出任何行", rows.isNotEmpty())
    }

    private data class Row(
        val version: String,
        val channel: BdsChannel,
        val http: String,
        val bytes: Long,
        val sha: String,
        val entryBytes: Long,
        val rootPrefix: String,
        val libs: Int,
        val pruned: Int,
        val hasProperties: Boolean,
        val nestedRoot: Boolean,
        val needed: List<String>,
        val missing: List<String>,
        val verdict: String,
        val note: String
    )

    /**
     * 补全直链与摘要（与 App 里 `BdsVersionCatalogLoader.resolve` 同一套逻辑）。
     *
     * 合并出来的远端条目只有版本号，直链在 `metadata.json` 里；不补这一步，
     * 矩阵会拿命名规则拼出来的三段文件名去下载，得到一串假 404 ——
     * 那是测量方法的错，不是版本不可用。
     */
    private fun resolveEntry(entry: BdsVersionEntry): BdsVersionEntry {
        if (entry.url != null && entry.hasDigest) return entry
        val keys = listOfNotNull(entry.remoteKey, BdsVersions.releaseKey(entry.version)).distinct()
        keys.forEach { key ->
            val text = getText(BdsVersionCatalogLoader.remoteMetadataUrl(entry.channel, key)) ?: return@forEach
            val parsed = BdsVersionCatalogLoader.parseRemoteMetadata(text) ?: return@forEach
            return entry.copy(url = parsed.first, sha256 = parsed.second)
        }
        return entry
    }

    private fun inspect(entry: BdsVersionEntry, cache: File): Row {
        val resolved = resolveEntry(entry)
        val zip = File(cache, "bds-${BdsPackageDownload.safeName(resolved.version)}.zip")
        val candidates = BdsVersions.candidates(resolved)
        var usedUrl = ""
        var http = "-"
        var report: BdsPackageIntegrity.Report? = null
        var error: String? = null

        for (source in candidates.take(3)) {
            usedUrl = source.url
            val ok = runCatching {
                runBlocking {
                    MultiThreadDownloader.download(
                        url = source.url,
                        target = zip,
                        onProgress = { _, _, _ -> }
                    )
                }
                true
            }.getOrDefault(false)
            if (!ok) { http = "ERR"; continue }
            http = "200"
            report = runCatching {
                BdsPackageIntegrity.verify(
                    file = zip,
                    expectedSha256 = resolved.sha256,
                    expectedBytes = resolved.sizeBytes,
                    level = if (resolved.hasDigest) BdsPackageIntegrity.Level.ManifestSha256
                    else BdsPackageIntegrity.Level.Structural
                )
            }.getOrElse { e -> error = e.message; null }
            if (report != null) break
        }

        if (report == null) {
            runCatching { zip.delete() }
            return Row(
                entry.version, entry.channel, http, -1, "-", -1, "-", 0, 0, false, false,
                emptyList(), emptyList(), "FAIL", "下载/校验失败：${error ?: usedUrl}"
            )
        }

        val plan = runCatching { BdsCompatibility.plan(zip, resolved.version) }
            .getOrElse { e ->
                runCatching { zip.delete() }
                return Row(
                    resolved.version, resolved.channel, http, report.bytes, report.sha256.take(12),
                    -1, "-", 0, 0, false, false, emptyList(), emptyList(),
                    "FAIL", "包结构不可用：${e.message}"
                )
            }
        val entryFile = extractEntryOnly(zip, plan)
        val needed = entryFile?.let { BdsElfDeps.read(it)?.needed.orEmpty() }.orEmpty()
        // 判「缺库」要同时看两处：包自带的 .so（与主程序同目录）+ APK 内置的 x86 运行库。
        // 少了后者，libc/libm 这些会被误报成「需补库」。
        val searchDirs = listOfNotNull(entryFile?.parentFile, bundledX86LibDir())
        val missing = entryFile?.let { BdsElfDeps.missingNeeded(it, searchDirs) }.orEmpty()

        val arch = entryFile?.let { BdsSupport.elfArch(it) }
        val verdict = when {
            entryFile == null -> "FAIL"
            arch != BdsSupport.ElfArch.X86_64 -> "FAIL"
            else -> "OK"
        }
        val note = buildString {
            if (arch != null && arch != BdsSupport.ElfArch.X86_64) append("主程序架构 ${arch.displayName}；")
            if (missing.isNotEmpty()) append("需容器补库 ${missing.joinToString()}；")
            if (plan.pruned.isNotEmpty()) append("剔除 ${plan.pruned.size} 个符号文件；")
            if (plan.rootPrefix.isNotEmpty()) append("包内嵌根目录；")
            if (!plan.hasProperties) append("缺 server.properties；")
        }.ifBlank { "—" }

        runCatching { zip.delete() }
        entryFile?.delete()
        return Row(
            version = resolved.version,
            channel = resolved.channel,
            http = http,
            bytes = report.bytes,
            sha = report.sha256.take(12),
            entryBytes = plan.entryBytes,
            rootPrefix = plan.rootPrefix.ifEmpty { "-" },
            libs = plan.libs.size,
            pruned = plan.pruned.size,
            hasProperties = plan.hasProperties,
            nestedRoot = plan.rootPrefix.isNotEmpty(),
            needed = needed,
            missing = missing,
            verdict = verdict,
            note = note
        )
    }

    /** 只解出主程序用于 ELF 分析（不铺整棵树，省磁盘与时间） */
    private fun extractEntryOnly(zip: File, plan: BdsCompatibility.Plan): File? {
        val target = temp.newFolder("entry-${System.nanoTime()}")
        return runCatching {
            BdsCompatibility.extract(zip, target, plan.copy(pruned = emptyList()))
            File(target, plan.entryName).takeIf { it.isFile }
        }.getOrNull()
    }

    // ── 模式三：只探测直链是否真的存在（不下包） ─────────────────

    /**
     * 逐个候选发一个 `Range: bytes=0-0` 的 GET，只关心能不能拿到字节。
     *
     * 之所以不用 HEAD：Mojang CDN 对 HEAD 的策略不一致，线上实测过
     * 「HEAD 超时、GET 正常」。用 1 字节的 GET 更接近真实下载路径。
     *
     * 这一模式的作用是**校验清单本身**：内置清单里的构建号是人工整理的，
     * 记错一个数字就是一条永远 404 的条目，而用户点下去只会看到「所有源都不可用」。
     */
    @Test
    fun probeLinks() {
        assumeTrue("未开启（-DbdsMatrix=probe）", mode == "probe" || mode == "all")

        val all = mergedEntries()
        val targets = if (seedOnly) {
            all.filter { it.source == com.mineserve.mobile.data.BdsVersionSource.Bundled }
        } else all
        val rows = targets.map { entry -> inspectLink(entry) }

        val ok = rows.count { it.hit != null }
        val bad = rows.filter { it.hit == null }
        File(outDir, "probe-report.csv").writeText(buildString {
            appendLine("version,channel,candidates,hit,http,size")
            rows.forEach { appendLine("${it.version},${it.channel},${it.candidates},${it.hit ?: "-"},${it.http},${it.size}") }
        })
        File(outDir, "probe-report.md").writeText(buildString {
            appendLine("# BDS 直链探测")
            appendLine()
            appendLine("- 条目：${rows.size}，可用 $ok，404/超时 ${bad.size}")
            appendLine()
            if (bad.isNotEmpty()) {
                appendLine("## 不可用（清单需要修正）")
                appendLine()
                bad.forEach { appendLine("- `${it.version}` ${it.channel} → 试了 ${it.candidates} 个候选，最后 ${it.http}") }
            }
        })
        println("[bds-matrix] 探测 ${rows.size} 条：可用 $ok，不可用 ${bad.size}")
        bad.take(40).forEach { println("[bds-matrix] 404: ${it.version} ${it.channel}") }
        assertTrue("不可用条目过多：${bad.size}/${rows.size}", bad.size <= (rows.size / 10))
    }

    private data class LinkRow(
        val version: String,
        val channel: BdsChannel,
        val candidates: Int,
        val hit: String?,
        val http: String,
        val size: Long
    )

    private fun inspectLink(entry: BdsVersionEntry): LinkRow {
        val candidates = BdsVersions.candidates(entry)
        var code = "-"
        candidates.take(3).forEach { source ->
            val result = probeRange(source.url)
            code = result.first
            // 判据是状态码，不是体积：有的响应不带 Content-Length
            if (result.first.toIntOrNull() in 200..299) {
                return LinkRow(entry.version, entry.channel, candidates.size, source.url, code, result.second)
            }
        }
        return LinkRow(entry.version, entry.channel, candidates.size, null, code, -1)
    }

    /** @return (HTTP 状态, Content-Length 或 -1 表示没拿到字节) */
    private fun probeRange(url: String): Pair<String, Long> {
        repeat(2) { attempt ->
            val result = probeOnce(url)
            if (result.first.toIntOrNull() in 200..299) return result
            // 连续 100+ 次请求时 CDN 偶发返回 404/429（实测 5/123 会闪一下），
            // 稍等重试一次就能过 —— 否则矩阵会把「真实存在」误报成「清单写错」
            if (attempt == 0) Thread.sleep(400)
        }
        return probeOnce(url)
    }

    private fun probeOnce(url: String): Pair<String, Long> = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Range", "bytes=0-0")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", "Minexa/1.0 (Matrix)")
        val status = conn.responseCode
        if (status !in 200..299) {
            conn.disconnect()
            return@runCatching status.toString() to -1L
        }
        // 2xx 就说明文件存在；读一个字节只是为了尽早关闭连接。
        // 读失败不算「不存在」—— 之前把它当失败，导致已确认存在的版本被误报 404。
        val buffer = ByteArray(1)
        runCatching { conn.inputStream.read(buffer) }
        val len = conn.getHeaderField("Content-Range")
            ?.substringAfterLast('/')?.toLongOrNull()
            ?: conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        conn.disconnect()
        status.toString() to len
    }.getOrElse { "ERR" to -1L }

    // ── 报告 ─────────────────────────────────────────────────────

    private fun writeCatalogReport(
        entries: List<BdsVersionEntry>,
        stable: Int,
        preview: Int,
        metaRows: List<String>,
        failures: List<String>,
        warnings: List<String> = emptyList()
    ) {
        File(outDir, "catalog-report.csv").writeText(buildString {
            appendLine("version,channel,source,hasSha256,digestExpected,url,candidates")
            entries.forEach { e ->
                appendLine(
                    listOf(
                        e.version, e.channel, e.source, e.hasDigest, e.digestExpected,
                        e.url ?: "-", BdsVersions.candidates(e).size
                    ).joinToString(",")
                )
            }
        })
        File(outDir, "catalog-report.md").writeText(buildString {
            appendLine("# BDS 版本清单矩阵")
            appendLine()
            appendLine("- 版本总数：${entries.size}（稳定 $stable / Preview $preview）")
            appendLine("- 已取到 SHA-256：${entries.count { it.hasDigest }}")
            appendLine("- 上游有摘要（含未预取）：${entries.count { it.strictDigest }}")
            appendLine("- 内置清单：${entries.count { it.source == com.mineserve.mobile.data.BdsVersionSource.Bundled }}")
            appendLine("- 远端清单：${entries.count { it.source == com.mineserve.mobile.data.BdsVersionSource.RemoteCatalog }}")
            appendLine("- 官方接口：${entries.count { it.source == com.mineserve.mobile.data.BdsVersionSource.OfficialApi }}")
            appendLine()
            appendLine("## 远端元数据抽样")
            appendLine()
            metaRows.forEach { appendLine("- $it") }
            appendLine()
            if (warnings.isNotEmpty()) {
                appendLine("## 警告（外部数据缺口，不影响安装）")
                appendLine()
                warnings.forEach { appendLine("- ⚠️ $it") }
                appendLine()
            }
            appendLine("## 结论")
            appendLine()
            if (failures.isEmpty()) appendLine("全部通过。")
            else failures.forEach { appendLine("- ❌ $it") }
        })
    }

    private fun writePackageReport(rows: List<Row>) {
        File(outDir, "package-report.csv").writeText(buildString {
            appendLine("version,channel,http,bytes,sha12,entryBytes,rootPrefix,libs,pruned,hasProperties,verdict,missing,note")
            rows.forEach { r ->
                appendLine(
                    listOf(
                        r.version, r.channel, r.http, r.bytes, r.sha, r.entryBytes,
                        "\"${r.rootPrefix}\"", r.libs, r.pruned, r.hasProperties, r.verdict,
                        "\"${r.missing.joinToString(" ")}\"", "\"${r.note}\""
                    ).joinToString(",")
                )
            }
        })
        File(outDir, "package-report.md").writeText(buildString {
            appendLine("# BDS 历史版本实体包矩阵")
            appendLine()
            appendLine("| 版本 | 通道 | HTTP | 包体积 | 主程序 | 内嵌根目录 | .so | 剔除符号 | 自带配置 | 需补库 | 结论 |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
            rows.forEach { r ->
                appendLine(
                    "| ${r.version} | ${r.channel} | ${r.http} | ${r.bytes / 1024 / 1024}MB | " +
                        "${r.entryBytes / 1024 / 1024}MB | ${r.rootPrefix} | ${r.libs} | ${r.pruned} | " +
                        "${if (r.hasProperties) "是" else "否"} | ${r.missing.joinToString()} | ${r.verdict} |"
                )
            }
        })
    }

    private fun reportToUser(rows: List<Row>) {
        val ok = rows.count { it.verdict == "OK" && it.missing.isEmpty() }
        val withLibs = rows.count { it.verdict == "OK" && it.missing.isNotEmpty() }
        val fail = rows.count { it.verdict != "OK" }
        println("[bds-matrix] 共 ${rows.size} 个版本：直接可跑 $ok，需容器补库 $withLibs，失败 $fail")
        rows.filter { it.verdict != "OK" || it.missing.isNotEmpty() }.forEach {
            println("[bds-matrix] ${it.version} ${it.verdict} ${it.missing} ${it.note}")
        }
        println("[bds-matrix] 报告：${outDir.absolutePath}")
    }

    private fun entriesComparator(): Comparator<BdsVersionEntry> =
        compareBy<BdsVersionEntry> { if (it.channel == BdsChannel.Stable) 0 else 1 }
            .thenComparator { a, b -> BdsVersions.compare(b.version, a.version) }
}
