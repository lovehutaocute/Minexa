package com.mineserve.mobile.data

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Locale

/**
 * 明文 `http://` 的裸 socket HTTP/1.1 客户端（只做 GET）。
 *
 * **为什么不能用 [java.net.HttpURLConnection]**：Android 的明文流量策略
 * （`android.security.NetworkSecurityPolicy`）会直接拒绝 http:// 连接，实测报
 * `Cleartext HTTP traffic to 192.168.1.156 not permitted` —— 连 `127.0.0.1` 都不放过。
 * 而插件 / 模组 / 世界包的下载地址经常是局域网里的明文地址（PC 上临时起的 http 服务、
 * 内网文件站），这类地址走 HttpURLConnection 必然失败。裸 [Socket] 不经过该策略。
 *
 * 刻意只实现下载需要的部分：GET、`Connection: close`、按 Content-Length 或 chunked
 * 读 body、跟随 http→http 重定向。不实现 Range / POST / cookie / 连接复用 / TLS：
 * 遇到跳到 https 的重定向会抛 [RedirectNotCleartext]，由调用方改走 HttpURLConnection。
 */
internal object CleartextHttp {

    /** 默认 UA，与 [MultiThreadDownloader] 保持一致，避免同一应用出现两种身份。 */
    const val DEFAULT_USER_AGENT = "MineServeMobile/1.0 (Android)"

    /** 最多跟随的重定向次数，防止环状重定向把下载卡死。 */
    const val MAX_REDIRECTS = 5

    /** 单行响应头上限；超过即视为畸形响应，避免被畸形服务用超长行撑爆内存。 */
    private const val MAX_LINE_CHARS = 8192

    /** 是否是明文 http 地址（调用方据此决定走裸 socket 还是 HttpURLConnection）。 */
    fun isCleartext(url: String): Boolean =
        url.trim().startsWith("http://", ignoreCase = true)

    /**
     * 重定向到了非明文地址（通常是 https）。裸 socket 读不了 TLS，
     * 调用方应改用该地址走 [java.net.HttpURLConnection] 重新下载。
     */
    class RedirectNotCleartext(val target: String) :
        IOException("Redirect target is not cleartext http: $target")

    /**
     * 一次成功的响应：状态码 + 头 + **已解包**（chunked 已还原）的 body 流。
     * [close] 会同时关闭 body 流与底层 socket。
     */
    class Exchange(
        val code: Int,
        /** 响应头（键已小写） */
        val headers: Map<String, String>,
        val body: InputStream,
        /** 响应体是否用 chunked 分帧（此时 Content-Length 无意义） */
        val chunked: Boolean,
        private val closeAction: () -> Unit
    ) : Closeable {

        /**
         * 期望的总字节数；未知时返回 -1。
         *
         * chunked 与 Content-Length 并存时必须忽略 CL（RFC 7230 §3.3.3）：
         * 代理拼接 chunked 时会顺手带上原始 CL，二者不一致，拿 CL 当期望长度会把
         * 一个完整响应判成「大小不完整」。
         */
        val contentLength: Long get() =
            if (chunked) -1L else parseContentLength(headers["content-length"])

        override fun close() = closeAction()
    }

    /**
     * 发起一次 GET；http→http 的重定向内部跟随。
     *
     * @throws RedirectNotCleartext 重定向目标是 http 以外的协议
     * @throws IOException 连接失败、响应畸形、重定向次数超限等
     */
    fun get(
        url: String,
        timeoutMs: Int = 15_000,
        userAgent: String = DEFAULT_USER_AGENT
    ): Exchange {
        var current = url
        var redirects = 0
        while (true) {
            val uri = runCatching { URI(current.trim()) }.getOrNull()
                ?: throw IOException("Invalid URL: $current")
            if (!isCleartext(current)) throw RedirectNotCleartext(current)
            val host = uri.host?.takeIf { it.isNotEmpty() }
                ?: throw IOException("URL has no host: $current")
            val port = if (uri.port > 0) uri.port else 80

            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.tcpNoDelay = true
                socket.getOutputStream().apply {
                    write(buildGet(uri, host, port, userAgent))
                    flush()
                }
                val input = BufferedInputStream(socket.getInputStream())
                val head = readHead(input) ?: throw IOException("Malformed HTTP response from $current")
                val redirect = redirectTarget(head.code, head.headers, uri, redirects)
                if (redirect != null) {
                    redirects++
                    runCatching { socket.close() }
                    if (!isCleartext(redirect)) throw RedirectNotCleartext(redirect)
                    current = redirect
                    continue
                }
                val chunked = head.headers["transfer-encoding"]
                    ?.split(',')?.any { it.trim().equals("chunked", ignoreCase = true) } == true
                val body: InputStream = if (chunked) ChunkedInputStream(input) else input
                return Exchange(head.code, head.headers, body, chunked) {
                    runCatching { body.close() }
                    runCatching { socket.close() }
                }
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
        }
    }

    // ── 报文构造 / 解析（纯函数，便于单测） ───────────────────────

    /**
     * 构造 GET 报文。固定 `Connection: close`：读到流结束就代表响应收完，
     * 不必解析 `Content-Length` / chunked 之外的分帧，少一类出错点。
     * 不发 `Accept-Encoding`，避免拿到 gzip 而写坏文件。
     */
    fun buildGet(uri: URI, host: String, port: Int, userAgent: String): ByteArray {
        val target = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val full = uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { "$target?$it" } ?: target
        val hostHeader = if (port == 80) host else "$host:$port"
        val head = buildString {
            append("GET ").append(full).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("User-Agent: ").append(userAgent).append("\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        return head.toByteArray(Charsets.UTF_8)
    }

    /** 从 `HTTP/1.1 200 OK` 取状态码；取不到返回 -1。 */
    fun statusCode(statusLine: String): Int {
        // 用空白分割并丢掉空串：手写的极简 http 服务常见 `HTTP/1.1  200 OK`（多打一个空格），
        // 严格按单空格 split 会把它判成畸形响应。
        val parts = statusLine.trim().split(Regex("""\s+"""))
        if (parts.size < 2 || !parts[0].startsWith("HTTP/", ignoreCase = true)) return -1
        return parts[1].toIntOrNull() ?: -1
    }

    /** 是否是「需要跟随 Location」的重定向状态码。 */
    fun isRedirect(code: Int): Boolean = code == 301 || code == 302 || code == 303 || code == 307 || code == 308

    /**
     * 计算重定向目标；不需要跟随（非 3xx、缺 Location、超次数）时返回 null。
     * 超次数时返回 null 会让调用方把 3xx 当作普通响应处理，最终报「HTTP 3xx 下载失败」。
     */
    fun redirectTarget(code: Int, headers: Map<String, String>, base: URI, followed: Int): String? {
        if (!isRedirect(code) || followed >= MAX_REDIRECTS) return null
        val location = headers["location"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { base.resolve(location).toString() }.getOrNull()
    }

    /** 解析 Content-Length；缺失、非数字或负数一律当未知（-1）。 */
    fun parseContentLength(raw: String?): Long {
        val v = raw?.trim()?.toLongOrNull() ?: return -1L
        return if (v >= 0L) v else -1L
    }

    /** 读取状态行与响应头，直到空行为止；响应畸形返回 null。 */
    fun readHead(input: InputStream): Head? {
        val statusLine = readLine(input) ?: return null
        val code = statusCode(statusLine)
        if (code < 0) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            // 同名头（Set-Cookie 常见）只保留第一个：本客户端只关心 Location / Length 这类单值头
            if (!headers.containsKey(name)) headers[name] = value
        }
        return Head(code, headers)
    }

    /** 状态行 + 头。 */
    data class Head(val code: Int, val headers: Map<String, String>)

    /**
     * 逐字节读一行（去掉行尾 CR/LF）。
     *
     * 读到 EOF 且该行为空时返回 null（调用方据此判断「响应头结束」）。
     * 行超过 [maxChars] 时抛 [IOException] 而**不是**静默截断：截断会让后续的 body
     * 从这一行的中间开始读，把响应头当文件内容写进目标文件。
     */
    fun readLine(input: InputStream, maxChars: Int = MAX_LINE_CHARS): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= maxChars) throw IOException("HTTP line exceeds $maxChars chars")
            sb.append(b.toChar())
        }
    }
}

/**
 * 还原 `Transfer-Encoding: chunked` 的分块封装。
 *
 * 只有读到「0 长度块」才算正常结束。中途断流、块长度非法一律抛 [IOException]：
 * chunked 响应没有 Content-Length，调用方无从做长度校验，若把截断当成功，
 * 用户会拿到一个看似完好的半截 jar。
 */
internal class ChunkedInputStream(private val source: BufferedInputStream) : InputStream() {

    private var remaining = 0L
    private var finished = false

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (remaining == 0L) {
            if (finished) return -1
            if (!readChunkHeader()) return -1
        }
        val want = minOf(len.toLong(), remaining).toInt()
        val n = source.read(b, off, want)
        if (n < 0) {
            finished = true
            throw IOException("Chunked body truncated: $remaining byte(s) of the current chunk missing")
        }
        remaining -= n
        if (remaining == 0L) skipChunkTail()
        return n
    }

    /** 读块头；返回 false 表示正常读到末块（0 长度）。 */
    private fun readChunkHeader(): Boolean {
        val line = CleartextHttp.readLine(source)
            ?: throw IOException("Chunked body truncated: missing chunk header")
        // 块扩展（`1a;name=value`）直接丢弃
        val size = line.trim().substringBefore(';').trim().toLongOrNull(16)
            ?: throw IOException("Malformed chunk size: '$line'")
        if (size <= 0L) {
            finished = true
            // 吃掉 trailer 段，让连接处于干净状态
            while (true) {
                val trailer = CleartextHttp.readLine(source) ?: break
                if (trailer.isEmpty()) break
            }
            return false
        }
        remaining = size
        return true
    }

    /** 块尾的 CRLF：宽容处理，不是 CRLF 就回退（不吞掉下一个块头）。 */
    private fun skipChunkTail() {
        if (!source.markSupported()) return
        source.mark(2)
        val a = source.read()
        val b = source.read()
        if (a != '\r'.code || b != '\n'.code) source.reset()
    }
}
