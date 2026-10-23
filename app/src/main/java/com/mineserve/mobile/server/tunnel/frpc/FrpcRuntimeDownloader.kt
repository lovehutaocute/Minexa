package com.mineserve.mobile.server.tunnel.frpc

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * frpc 引擎的在线下载器。
 *
 * 链路：拉元数据 → 取 arm64-v8a 包 → 多源回退下载 → SHA-256 校验 → 交给 [FrpcRuntime.install]。
 *
 * ## 为什么下载与安装是分开的两步
 *
 * [FrpcRuntime.install] 收 `InputStream`，写盘后校验的是 **ELF 头**。但这里还多一道
 * SHA-256 —— 它必须在内容落进正式文件**之前**完成。所以本类先下到临时文件、比对哈希、
 * 再以 `InputStream` 交给 `install` 走它那套原子改名逻辑。这样任何一步失败都不会
 * 碰到用户原本可用的引擎。
 */
internal class FrpcRuntimeDownloader(private val runtime: FrpcRuntime) {

    companion object {
        /** 元数据读取上限（字符）。真实元数据约 4KB，留足余量但仍能挡住误连的大文件。 */
        private const val MAX_METADATA_CHARS = 512 * 1024

        /** 取 URL 的主机名用于错误信息；解析不出来时退回原串。 */
        internal fun hostOf(url: String): String =
            runCatching { URL(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
    }

    /** 下载进度回调，参数为 0f..1f；总长度未知时传 -1f。 */
    fun interface OnProgress {
        fun onProgress(fraction: Float)
    }

    /** 一次下载的结果。 */
    data class Result(
        val version: String?,
        /** 实际取得该文件的源，展示出来便于排查镜像问题。 */
        val sourceUrl: String
    )

    /**
     * 拉取元数据。
     *
     * 候选地址 = 写死的 [FrpcEngineConfig.METADATA_URL] + 从 [FrpcEngineConfig.CATALOG_URL]
     * 现取的 frpc 地址列表。前者是快速路径（多数情况下一次命中），后者保证上游升版本后
     * 本应用不必发新版 —— 见 `FrpcEngineConfig.CATALOG_URL` 的注释里那段 SPA 兜底说明。
     *
     * 逐个尝试，第一个解析成功且含有可用包的源胜出。把"有没有可用包"也算进重试条件：
     * 镜像返回一个内容完整但过期的元数据是可能的，若只按 HTTP 状态码判断，
     * 就会拿着它去下一个源之前就放弃。
     *
     * @throws IOException 全部源都失败时抛出，消息里带上各源的真实错误。
     */
    fun fetchManifest(): EngineManifest {
        val errors = mutableListOf<String>()

        // 两阶段：首选地址（写死的最新版本）先试，命中就结束 —— 目录清单只是
        // 「上游升版本后仍能自动跟上」的兜底，不该每次下载都白跑一趟。
        tryManifest(FrpcEngineConfig.metadataUrls(), errors)?.let { return it }
        tryManifest(discoverMetadataUrls(), errors)?.let { return it }

        // 去重后拼接：同一个错误（比如全是「源返回的不是 JSON」）重复列 3 遍没有信息量。
        throw IOException("获取引擎元数据失败：${errors.distinct().joinToString("；").ifBlank { "所有下载源均不可用" }}")
    }

    /** 依次尝试 [urls]，第一个可用（能解析且含本架构直链包）的胜出；否则记录错误返回 null。 */
    private fun tryManifest(urls: List<String>, errors: MutableList<String>): EngineManifest? {
        for (url in urls) {
            val text = runCatching { readText(url) }
                .onFailure { errors += "${hostOf(url)}：${it.message}" }
                .getOrNull() ?: continue

            val manifest = EngineManifestParser.parse(text) ?: run {
                errors += "${hostOf(url)}：元数据格式无法识别"
                null
            } ?: continue

            if (EngineManifestParser.packageFor(manifest, FrpcEngineConfig.PACKAGE_KEY) == null) {
                errors += "${hostOf(url)}：元数据中没有可用的 ${FrpcEngineConfig.PACKAGE_KEY} 包"
                continue
            }
            return manifest
        }
        return null
    }

    /**
     * 从目录清单里现取 frpc 元数据地址；拿不到就返回空（不阻断首选地址那条路）。
     *
     * 只试第一个能解析出非空列表的目录源 —— 目录本身是便利通道，不值得为它
     * 把整条回退链再拉长一轮。
     */
    private fun discoverMetadataUrls(): List<String> {
        for (url in FrpcEngineConfig.catalogUrls()) {
            val text = runCatching { readText(url) }.getOrNull() ?: continue
            val found = EngineManifestParser.frpcMetadataUrls(text)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /**
     * 下载并安装引擎。
     *
     * 成功返回实际使用的源与版本；全部源失败抛 [IOException]。
     * 抛出时磁盘上**不会**留下半成品，引擎目录维持原状。
     */
    fun downloadAndInstall(
        manifest: EngineManifest,
        progress: OnProgress? = null
    ): Result {
        val pkg = EngineManifestParser.packageFor(manifest, FrpcEngineConfig.PACKAGE_KEY)
            ?: throw IOException("元数据中没有可用的 ${FrpcEngineConfig.PACKAGE_KEY} 包")

        val expectedSha = pkg.sha256!!.lowercase()
        // 每次下载一个独立的临时文件：多源回退要能重来，复用同一个文件会残留上一轮的半截数据。
        val tmp = File(runtime.engineDir, "${FrpcRuntime.ENGINE_NAME}.download")
        runtime.engineDir.mkdirs()

        var lastError: String? = null
        try {
            // 每个直链各自展开成一组镜像地址，按顺序整体回退。
            // 再把 Gitee 这类国内源提到前面：元数据里 GitHub 排第一，而 GitHub 直连
            // 在部分网络下是静默超时，白等 15 秒才轮到那个本来能秒下的源。
            val candidates = FrpcEngineConfig.preferDomesticFirst(
                pkg.directUrls.flatMap { FrpcEngineConfig.downloadUrls(it) }
            )
            for (url in candidates) {
                try {
                    downloadTo(url, tmp, pkg.size, progress)
                } catch (e: Exception) {
                    lastError = "${hostOf(url)}：${e.message}"
                    tmp.delete()
                    continue
                }

                val actual = sha256Of(tmp)
                if (actual != expectedSha) {
                    // 不删掉 tmp 就往下试是可以的，但会把"这轮拿到的是坏文件"和
                    // "下轮从哪儿接着写"搅在一起，直接删掉最清楚。
                    lastError = "${hostOf(url)}：校验失败（哈希不匹配）"
                    tmp.delete()
                    continue
                }

                // 校验通过的 .ecpkg 交给 install：它自己认得容器格式，
                // 解包 + ELF 校验 + 原子落盘都在那一处，和手动导入共用同一条路。
                try {
                    tmp.inputStream().use { runtime.install(it, libEntryName(manifest)) }
                } catch (e: Exception) {
                    lastError = e.message
                    tmp.delete()
                    continue
                }
                return Result(runtime.installedVersion ?: manifest.displayVersion, url)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }

        throw IOException("下载引擎失败：${lastError ?: "所有下载源均不可用"}")
    }

    /**
     * 把 [url] 读到临时文件，顺带报告进度。
     *
     * 先写独立文件再改名，避免读到一半的残缺内容被当成完整结果 —— 校验是逐字节的，
     * 但"读完了"这件事本身只有长度和正常结束能证明。
     */
    private fun downloadTo(url: String, target: File, expectedSize: Long?, progress: OnProgress?) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Minexa")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }

            val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize ?: -1L
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (progress != null) {
                            progress.onProgress(if (total > 0) written.toFloat() / total else -1f)
                        }
                    }
                }
            }

            if (written == 0L) {
                throw IOException("下载内容为空")
            }
            // 声明了长度却对不上，说明连接被中断或被反代截断。
            // 交给 SHA-256 也能发现，但那要多读一遍整个文件才报错。
            if (total > 0 && written != total) {
                throw IOException("下载不完整（$written/$total 字节）")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 读一个 JSON 源，并**确认它真的是 JSON**。
     *
     * 为什么要嗅探内容而不只看状态码：`edgecubemc.com` 是 SPA，任何不存在的路径都
     * 回落成 `index.html`（HTTP 200 + `text/html`）。只信状态码就会把一整页 HTML
     * 当元数据喂给解析器，用户看到的是「元数据格式无法识别」—— 而真正的原因是
     * **地址已经不存在了**。这里提前判定，错误信息才能指到真问题上。
     */
    private fun readText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Minexa")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            // 加长度上限：元数据只有几 KB，误连到一个大文件（比如某人的镜像把
            // 目录映射成了二进制流）时不该把整个内容读进内存再报错。
            val text = connection.inputStream.bufferedReader().use { reader ->
                val buf = CharArray(MAX_METADATA_CHARS)
                var read = 0
                while (read < buf.size) {
                    val n = reader.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                String(buf, 0, read)
            }
            val firstChar = text.firstOrNull { !it.isWhitespace() }
            if (firstChar != '{' && firstChar != '[') {
                val type = connection.contentType?.substringBefore(';')?.trim()
                throw IOException("返回的不是 JSON（${type ?: "未知类型"}，可能地址已失效并回落成网页）")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 传给 [FrpcRuntime.install] 的名字，只用于从里面推断版本号展示。
     *
     * 用元数据的 `id`（`frpc0.69.1`）而不是固定的 `libfrpc.so`：后者里没有版本号，
     * 安装完界面就显示不出「引擎 0.69.1」，而排障时第一件要确认的往往就是
     * 「设备上跑的是哪个 frpc」。认不出时才回落到文件名。
     */
    private fun libEntryName(manifest: EngineManifest): String =
        manifest.id?.takeIf { FrpcRuntime.parseVersion(it) != null } ?: "libfrpc.so"

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
