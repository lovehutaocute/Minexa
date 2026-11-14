package com.mineserve.mobile.server

import java.io.File
import java.security.MessageDigest

/**
 * Java 版服务端资源包（`resource-pack` 系列属性）。
 *
 * 与基岩版的根本差别（实测 + 官方文档，见 `docs/JAVA-ADVANCED-WORLD-MANAGEMENT-PLAN.md` §2.6）：
 * **只支持一个包**，而且只能是外部 URL —— 服务端自己**不提供**静态文件服务，
 * 所以不能像基岩版那样把 zip 丢进目录就完事。
 *
 * ```
 * resource-pack=<url>           单个 URL，包体上限 250 MiB
 * resource-pack-sha1=<sha1>     小写十六进制；不一致时控制台给黄字警告
 * resource-pack-id=<uuid>       可选，向客户端标识这个包
 * resource-pack-prompt=<json>   可选，拒绝时的提示语（聊天组件语法）
 * require-resource-pack=true    拒绝下载即断开连接
 * ```
 */
object JavaResourcePack {

    /** 包体上限（官方限制）。 */
    const val MAX_BYTES = 250L * 1024 * 1024

    data class Config(
        val url: String = "",
        val sha1: String = "",
        val id: String = "",
        val prompt: String = "",
        val required: Boolean = false
    ) {
        val configured: Boolean get() = url.isNotBlank()
    }

    /**
     * 校验 URL：只接受 http/https。
     *
     * @return 规范化后的 URL；空字符串表示"清空"；非法返回 null
     */
    fun normalizeUrl(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return ""
        val lower = v.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        if (v.contains(' ') || v.contains('\n')) return null
        return v
    }

    /** 计算文件 SHA-1（小写十六进制）；失败返回 null。 */
    fun sha1(file: File): String? = runCatching {
        if (!file.isFile) return null
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** 校验 SHA-1 文本；空串合法（表示不校验），非法返回 null。 */
    fun normalizeSha1(raw: String): String? {
        val v = raw.trim().lowercase()
        if (v.isEmpty()) return ""
        return if (v.matches(Regex("^[0-9a-f]{40}$"))) v else null
    }

    /** 从属性表读配置。 */
    fun fromProperties(map: Map<String, String>): Config = Config(
        url = map["resource-pack"].orEmpty(),
        sha1 = map["resource-pack-sha1"].orEmpty(),
        id = map["resource-pack-id"].orEmpty(),
        prompt = map["resource-pack-prompt"].orEmpty(),
        required = map["require-resource-pack"]?.trim()?.lowercase() in setOf("true", "on", "1")
    )

    /** 转成属性表（只包含需要写的键；空值也写空串，让用户能清空）。 */
    fun toProperties(config: Config): Map<String, String> = mapOf(
        "resource-pack" to config.url,
        "resource-pack-sha1" to config.sha1,
        "resource-pack-id" to config.id,
        "resource-pack-prompt" to config.prompt,
        "require-resource-pack" to config.required.toString()
    )

    /**
     * 静态校验：返回需要提醒用户的问题（空列表 = 没有明显问题）。
     *
     * 只做**能确定**的判断，不预测客户端行为。
     */
    fun issues(config: Config): List<String> = buildList {
        if (config.required && !config.configured) {
            add("开启了强制资源包，但没有填下载地址：玩家进服不会有任何变化")
        }
        if (config.configured && config.sha1.isBlank()) {
            add("没有填 SHA-1：服务端不会校验包体完整性，换包后客户端可能仍在用旧缓存")
        }
        if (config.id.isNotBlank() && !isUuid(config.id)) {
            add("resource-pack-id 不是合法的 UUID，服务端会忽略它")
        }
        if (config.prompt.isNotBlank() && !config.prompt.trimStart().startsWith("{")) {
            add("resource-pack-prompt 需要聊天组件 JSON（形如 {\"text\":\"...\"}），纯文本会被服务端丢弃")
        }
    }

    private fun isUuid(v: String): Boolean =
        v.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
}
