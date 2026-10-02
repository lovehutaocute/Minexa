package com.mineserve.mobile.server.tunnel.frpc

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `.ecpkg` 包内清单 `edgecube-package.json` 的最小映射，只取架构目录。 */
@Serializable
internal data class EcpkgManifest(
    val arch: Map<String, EcpkgArch>? = null
)

@Serializable
internal data class EcpkgArch(
    val dir: String? = null
)

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
     * 逐个尝试 [FrpcEngineConfig.metadataUrls]，第一个解析成功且含有可用包的源胜出。
     * 把"有没有可用包"也算进重试条件：镜像返回一个内容完整但过期的元数据是可能的，
     * 若只按 HTTP 状态码判断，就会拿着它去下一个源之前就放弃。
     *
     * @throws IOException 全部源都失败时抛出，消息里带上最后一条真实错误。
     */
    fun fetchManifest(): EngineManifest {
        val urls = FrpcEngineConfig.metadataUrls()
        if (urls.isEmpty()) {
            throw IOException("未配置引擎下载地址")
        }

        var lastError: String? = null
        for (url in urls) {
            val text = runCatching { readText(url) }
                .onFailure { lastError = it.message }
                .getOrNull() ?: continue

            val manifest = EngineManifestParser.parse(text) ?: run {
                lastError = "元数据格式无法识别"
                null
            } ?: continue

            if (EngineManifestParser.packageFor(manifest, FrpcEngineConfig.PACKAGE_KEY) == null) {
                lastError = "元数据中没有可用的 ${FrpcEngineConfig.PACKAGE_KEY} 包"
                continue
            }
            return manifest
        }

        throw IOException("获取引擎元数据失败：${lastError ?: "所有下载源均不可用"}")
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
            val candidates = pkg.directUrls.flatMap { FrpcEngineConfig.downloadUrls(it) }
            for (url in candidates) {
                try {
                    downloadTo(url, tmp, pkg.size, progress)
                } catch (e: Exception) {
                    lastError = e.message
                    tmp.delete()
                    continue
                }

                val actual = sha256Of(tmp)
                if (actual != expectedSha) {
                    // 不删掉 tmp 就往下试是可以的，但会把"这轮拿到的是坏文件"和
                    // "下轮从哪儿接着写"搅在一起，直接删掉最清楚。
                    lastError = "校验失败（哈希不匹配）"
                    tmp.delete()
                    continue
                }

                // 校验通过的是 .ecpkg（ZIP 容器），里面的 libfrpc.so 才是引擎。
                // ELF 校验由 install 那侧完成，这里只负责把库从包里取出来。
                try {
                    extractLib(tmp).use { runtime.install(it, libEntryName()) }
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
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 从 `.ecpkg`（ZIP 容器）里取出 `libfrpc.so`。
     *
     * 包内布局是 `<架构目录>/lib/libfrpc.so`，架构目录名以包的清单
     * （`edgecube-package.json`）里的 `arch.<abi>.dir` 为准，而不是写死 ——
     * 分发方换了目录命名也能自适应。清单缺失或没写该架构时回退到
     * [FrpcEngineConfig.ECPKG_ARCH_DIR]。
     *
     * 不用 `ZipFile`（需要真实文件路径，还得自己管生命周期），
     * 直接在已下载的字节流上走一遍 `ZipInputStream`。
     *
     * @throws IOException 包损坏、或找不到库文件时抛出。
     */
    private fun extractLib(ecpkg: File): InputStream {
        val archDir = readArchDir(ecpkg) ?: FrpcEngineConfig.ECPKG_ARCH_DIR
        val entryName = "$archDir/${FrpcEngineConfig.ECPKG_LIB_SUFFIX}"

        ecpkg.inputStream().buffered().use { fileInput ->
            ZipInputStream(fileInput).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name == entryName) {
                        // 必须读完再关流：直接返回 zip 会让 use 块把下面的流关掉。
                        return ByteArrayInputStream(zip.readBytes())
                    }
                    zip.closeEntry()
                }
            }
        }
        throw IOException("包内未找到 $entryName")
    }

    /** 从 `.ecpkg` 的清单里读出本设备的架构目录名；清单缺失或没写则返回 null。 */
    private fun readArchDir(ecpkg: File): String? = runCatching {
        ecpkg.inputStream().buffered().use { fileInput ->
            ZipInputStream(fileInput).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == FrpcEngineConfig.ECPKG_MANIFEST) {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        val json = Json { ignoreUnknownKeys = true; isLenient = true }
                        val manifest = json.decodeFromString<EcpkgManifest>(text)
                        val spec = manifest.arch?.get(FrpcEngineConfig.ECPKG_ARCH_DIR)
                        return@use spec?.dir?.takeIf { it.isNotBlank() }
                    }
                    zip.closeEntry()
                }
                null
            }
        }
    }.getOrNull()

    /** 传给 [FrpcRuntime.install] 的文件名，用于从里面推断版本号展示。 */
    private fun libEntryName(): String = "libfrpc.so"

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
