package com.mineserve.mobile.mcp

import java.net.URI
import java.util.Locale

/**
 * `http_probe` 用的裸 socket HTTP/1.1 报文构造与解析。
 *
 * **为什么不用 [java.net.HttpURLConnection]**：Android 的明文流量策略
 * （`android.security.NetworkSecurityPolicy`）会直接拒绝 http:// 连接，实测报
 * `Cleartext HTTP traffic to 127.0.0.1 not permitted` —— 连 localhost 都不放过。
 * 而 http_probe 存在的意义就是探内网地址（LAN 上的 MCP 端点、FTP 面板、内网穿透的回环端口），
 * 这些几乎全是明文 http。裸 [java.net.Socket] 不经过该策略，因此可用。
 *
 * 拆成纯函数是为了能在 JVM 单测里验证报文格式与解析，不需要真起网络。
 */
internal object McpRawHttp {

    /** 解析后的原始响应 */
    data class RawResponse(
        val statusLine: String,
        val code: Int,
        val headerLines: List<String>,
        val body: String
    )

    /** 默认端口：按 scheme 给，未识别则 80 */
    fun defaultPort(uri: URI): Int =
        if (uri.scheme?.lowercase(Locale.ROOT) == "https") 443 else 80

    /** 目标主机 */
    fun host(uri: URI): String =
        uri.host?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("URL has no host")

    /** 连接端口（显式端口优先） */
    fun port(uri: URI): Int = if (uri.port > 0) uri.port else defaultPort(uri)

    /**
     * request-target：path + query，两者都空时给 `/`。
     * 必须原样带上 query，否则探带参数的端点（例如 `/mcp?x=1`）会失真。
     */
    fun requestTarget(uri: URI): String {
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.takeIf { it.isNotEmpty() } ?: return path
        return "$path?$query"
    }

    /** `Host:` 头：非默认端口才带端口号 */
    fun hostHeader(uri: URI): String {
        val h = host(uri)
        val p = port(uri)
        return if (p == defaultPort(uri)) h else "$h:$p"
    }

    /**
     * 构造完整请求报文。固定 `Connection: close`：读到流结束就代表响应收完，
     * 不必解析 Content-Length / chunked，少一类出错点。
     */
    fun buildRequest(uri: URI, method: String, body: String?, userAgent: String): ByteArray {
        val payload = body?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
        head.append(method).append(' ').append(requestTarget(uri)).append(" HTTP/1.1\r\n")
        head.append("Host: ").append(hostHeader(uri)).append("\r\n")
        head.append("User-Agent: ").append(userAgent).append("\r\n")
        head.append("Accept: */*\r\n")
        head.append("Connection: close\r\n")
        if (payload != null) {
            head.append("Content-Type: application/json\r\n")
            head.append("Content-Length: ").append(payload.size).append("\r\n")
        }
        head.append("\r\n")
        val headBytes = head.toString().toByteArray(Charsets.UTF_8)
        return if (payload == null) headBytes else headBytes + payload
    }

    /**
     * 解析原始响应文本。响应头在第一个空行处截断，body 按 [maxBody] 个字符截断
     * （按字符而非字节：这里只用于展示，且不会切坏 UTF-8 的显示）。
     */
    fun parseResponse(raw: String, maxBody: Int): RawResponse {
        var separator = raw.indexOf("\r\n\r\n")
        var sepLen = 4
        if (separator < 0) {
            // 容错：少数服务只用 \n 分行
            separator = raw.indexOf("\n\n")
            sepLen = 2
        }
        val headPart = if (separator >= 0) raw.substring(0, separator) else raw
        val bodyPart = if (separator >= 0) raw.substring(separator + sepLen) else ""

        val lines = headPart.split('\n').map { it.trimEnd('\r') }
        val statusLine = lines.firstOrNull()?.trim().orEmpty()
        val headers = lines.drop(1).filter { it.isNotBlank() }.map { it.trim() }
        val body = if (bodyPart.length > maxBody) bodyPart.substring(0, maxBody) else bodyPart
        return RawResponse(statusLine, statusCode(statusLine), headers, body)
    }

    /** 从 `HTTP/1.1 200 OK` 取状态码；取不到返回 -1 */
    fun statusCode(statusLine: String): Int {
        val parts = statusLine.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/", ignoreCase = true)) return -1
        return parts[1].toIntOrNull() ?: -1
    }
}
