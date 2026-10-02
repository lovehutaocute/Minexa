package com.mineserve.mobile.runtime

import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 远端产物的完整性校验。
 *
 * 为什么需要它：frpc 二进制、PHP 运行时、自更新 APK 都会被下载到设备上并**直接执行 / 安装**，
 * 而这几处的下载源列表里塞满了第三方 GitHub 反代（ghfast.top / gh-proxy.com / mirror.ghproxy.com /
 * ghproxy.net / github.moeyy.xyz）。这些反代对内容不作任何承诺，只要其中任何一个被投毒或劫持，
 * 落盘的就是攻击者构造的可执行文件。
 *
 * 原先只有 bootstrap rootfs 做了 sha256 白名单（见 [BootstrapInstaller.bootstrapSha256]），
 * 其余几处只判「文件够大」就放行。这里把同一套做法抽出来给它们复用。
 */
object RemoteArtifact {

    private const val TAG = "RemoteArtifact"

    private val json = Json { ignoreUnknownKeys = true }

    /** 流式算 SHA-256。几十 MB 的运行时、几百 MB 的整合包都不能全量读进内存。 */
    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 归一化期望值：既接受裸 hex，也接受 GitHub 的 `sha256:<hex>` 形式。 */
    fun normalize(expected: String): String =
        expected.trim().lowercase().removePrefix("sha256:").trim()

    /**
     * 校验文件摘要。
     *
     * [expected] 为空串表示「没有可用的期望值」——此时返回 false，由调用方决定是拒绝还是降级。
     * 这里不静默放行，是为了让"忘了配摘要"这件事必须显式处理。
     */
    fun matches(file: File, expected: String): Boolean {
        val want = normalize(expected)
        if (want.isEmpty()) return false
        val actual = runCatching { sha256Hex(file) }.getOrNull() ?: return false
        return actual.equals(want, ignoreCase = true)
    }

    /**
     * 从 GitHub Release API 取某个资产的官方 sha256。
     *
     * 为什么走 API 而不是写死常量：PHP 运行时用的是**滚动 tag**（`pm5-latest`），上游一发新版
     * 写死的摘要就失效，会把正常更新误判成篡改、直接把功能弄坏。GitHub 的 asset 对象里带
     * `digest` 字段（形如 `sha256:...`），由上游上传时计算；而 api.github.com 与下载用的镜像站
     * 是**不同主机**，镜像无法伪造它。
     *
     * 任何失败（网络不通、超时、字段缺失、老接口不返回 digest）都返回 null，
     * 由调用方按自己的策略决定是拒绝还是降级到「仅接受直连来源」。
     */
    fun githubAssetSha256(owner: String, repo: String, tag: String, assetName: String): String? {
        val url = "https://api.github.com/repos/$owner/$repo/releases/tags/$tag"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Minexa")
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val assets = Json.parseToJsonElement(body).jsonObject["assets"]?.jsonArray
                    ?: return@runCatching null
                assets.asSequence()
                    .mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                    .firstOrNull { it["name"]?.jsonPrimitive?.content == assetName }
                    ?.get("digest")
                    ?.jsonPrimitive
                    ?.content
                    ?.let(::normalize)
                    ?.takeIf { it.isNotEmpty() }
            } finally {
                runCatching { conn.disconnect() }
            }
        }.onFailure {
            Log.w(TAG, "取 GitHub 资产摘要失败($owner/$repo@$tag/$assetName): ${it.message}")
        }.getOrNull()
    }

    /**
     * 下载源可信度判断——用于「拿不到官方摘要时」的降级决策。
     *
     * 直连 github.com / *.githubusercontent.com 走的是 TLS 证书链，内容在传输途中不可篡改，
     * 在摘要缺失时可以接受；第三方反代没有这个保证，拿不到摘要就必须拒绝，否则等于没有校验。
     */
    fun isDirectGitHubSource(url: String): Boolean {
        val host = runCatching { URL(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "github.com" ||
            host == "api.github.com" ||
            host.endsWith(".githubusercontent.com")
    }
}
