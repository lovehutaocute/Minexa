package com.mineserve.mobile.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class AppRelease(val tag: String, val notes: String, val apkUrls: List<String>, val releaseUrl: String)

/**
 * GitHub Release 响应体。
 *
 * `body` 必须可空：发布说明为空时 GitHub 返回的是 `"body": null` 而不是 `""`。
 * kotlinx.serialization 的默认值只在**键缺失**时生效，键存在但值为 null 会直接抛
 * SerializationException（`Expected string literal but 'null' literal was found at path: $.body`），
 * 导致整个「检测更新」失败——v1.2.6 的发布说明留空后线上就踩到了这个坑。
 */
@Serializable internal data class ReleaseDto(
    val tag_name: String = "",
    val body: String? = null,
    val assets: List<AssetDto> = emptyList()
)
@Serializable internal data class AssetDto(val name: String = "", val browser_download_url: String = "")

object AppUpdateService {
    private const val API = "https://api.github.com/repos/lovehutaocute/MineServe-Mobile/releases/latest"
    const val PROJECT_URL = "https://github.com/lovehutaocute/MineServe-Mobile"
    private const val APK_NAME = "MineServeMobile-arm64-v8a-release.apk"
    // coerceInputValues：把上游写成 null 的字段归一到默认值，避免个别字段为 null 就整包解析失败。
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun latest(currentVersion: String): AppRelease? = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (api in apiUrls()) try {
            val connection = URL(api).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 12_000
                connection.readTimeout = 12_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "MineServeMobile")
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("update check failed: HTTP ${connection.responseCode}")
                }
                val release = decodeRelease(connection.inputStream.bufferedReader().use { it.readText() })
                val tag = release.tag_name.trim().removePrefix("v")
                val apk = release.assets.firstOrNull { it.name == APK_NAME }
                    ?: release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    ?: throw IllegalStateException("release has no ARM64 APK")
                return@withContext if (compare(tag, currentVersion) <= 0) null
                else AppRelease(tag, release.body?.trim().orEmpty(), downloadUrls(apk.browser_download_url), "$PROJECT_URL/releases/tag/$tag")
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            lastError = e
        }
        throw lastError ?: IllegalStateException("update check failed")
    }

    suspend fun download(urls: List<String>, target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        var lastError: Exception? = null
        for (url in urls.distinct()) {
            temp.delete()
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "MineServeMobile")
                if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (total > 0) onProgress(downloaded.toFloat() / total)
                        }
                    }
                }
                if (!isApk(temp)) throw IllegalStateException("downloaded content is not a valid APK")
                target.delete()
                if (!temp.renameTo(target)) throw IllegalStateException("cannot save update APK")
                onProgress(1f)
                return@withContext
            } catch (e: Exception) {
                lastError = e
            } finally {
                connection.disconnect()
            }
        }
        temp.delete()
        throw IllegalStateException("update download failed: ${lastError?.message ?: "all sources unavailable"}")
    }

    /** 解析 GitHub Release 响应。独立出来便于单测覆盖 `null` 字段等边界。 */
    internal fun decodeRelease(raw: String): ReleaseDto = json.decodeFromString(raw)

    private fun apiUrls() = listOf(
        "https://gh.api.99988866.xyz/$API",
        "https://ghfast.top/$API",
        API
    )

    private fun downloadUrls(url: String) = listOf(
        "https://ghfast.top/$url",
        "https://gh-proxy.com/$url",
        "https://mirror.ghproxy.com/$url",
        "https://ghproxy.net/$url",
        "https://github.moeyy.xyz/$url",
        url
    )

    /**
     * 校验下载到的 APK 与当前已安装应用的**签名**一致。
     *
     * 为什么必须做：升级通道本身就是代码执行通道，而 [downloadUrls] 里全是第三方 GitHub 反代
     * （ghfast.top / gh-proxy.com / …），它们对内容不作任何承诺。[isApk] 只看「是 zip 且
     * 内含 AndroidManifest.xml」，投毒者只要构造一个合法 APK 就能通过。
     *
     * `installApk` 那侧已经比对了包名（挡住"换个包名装成第二个应用"），签名比对补上剩下的：
     * 同包名但不同签名时，系统 PackageInstaller 其实也会拒绝安装，但那是"装到最后一步才失败"，
     * 用户只会看到一句含糊的失败；在这里提前判掉，能给出明确的错误并避免把 APK 交给安装器。
     *
     * 任一环节拿不到信息（解析失败、旧 API、无签名）都返回 false ——
     * 装更新这种事宁可失败，也不能在无法确认来源时放行。
     */
    internal fun verifySignature(context: android.content.Context, apk: File): Boolean {
        val pm = context.packageManager
        val installed = signatureDigests(pm, context.packageName) ?: return false
        val archive = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
        }.getOrNull() ?: return false
        @Suppress("DEPRECATION")
        val candidate = archive.signatures
            ?.mapNotNull { sig -> sig?.toByteArray()?.let { sha256Of(it) } }
            ?.toSet()
            .orEmpty()
        return candidate.isNotEmpty() && candidate.any { it in installed }
    }

    @Suppress("DEPRECATION")
    private fun signatureDigests(pm: android.content.pm.PackageManager, packageName: String): Set<String>? {
        val info = runCatching {
            pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        }.getOrNull() ?: return null
        val digests = info.signatures
            ?.mapNotNull { sig -> sig?.toByteArray()?.let { sha256Of(it) } }
            ?.toSet()
            .orEmpty()
        return digests.ifEmpty { null }
    }

    private fun sha256Of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun isApk(file: File): Boolean = file.length() > 256 * 1024 && runCatching {
        ZipFile(file).use { it.getEntry("AndroidManifest.xml") != null }
    }.getOrDefault(false)

    /**
     * 按数字段比较版本号。
     *
     * 两侧都剥掉 `v` 前缀：此前只剥了 [b]，若调用方忘了归一（例如把原始 tag 直接传进来），
     * `v1.2.6` 的第一段会解析失败退化成 0，比较结果变成「比当前版本旧」而静默漏掉更新。
     */
    internal fun compare(a: String, b: String): Int {
        val left = a.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(left.size, right.size)) { index ->
            val delta = left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }
            if (delta != 0) return delta
        }
        return 0
    }
}
