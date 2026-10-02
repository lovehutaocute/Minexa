package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * [CleartextHttp] 的报文构造 / 解析与分块解码测试。
 *
 * 这些都刻意做成不碰网络的纯函数，因为裸 socket 的实现没法在 CI 上真连：
 * 而它们恰恰是最容易写错、错了又只在特定服务端（chunked、畸形头、重定向）才暴露的部分。
 */
class CleartextHttpTest {

    private fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun readAll(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("ISO-8859-1")
    }

    // ── scheme 判定 ─────────────────────────────────────────────

    @Test
    fun `只把 http 判定为明文`() {
        assertTrue(CleartextHttp.isCleartext("http://192.168.1.156:8899/a.jar"))
        assertFalse(CleartextHttp.isCleartext("https://repo1.maven.org/a.jar"))
        assertFalse(CleartextHttp.isCleartext("ftp://example.com/a.jar"))
        assertFalse(CleartextHttp.isCleartext(""))
    }

    // ── 报文构造 ────────────────────────────────────────────────

    @Test
    fun `GET 报文带 Host 与 Connection close`() {
        val raw = CleartextHttp.buildGet(
            java.net.URI("http://192.168.1.156:8899/FakePlugin.jar"),
            "192.168.1.156", 8899, "UA/1"
        ).toString(Charsets.UTF_8)
        assertTrue(raw.startsWith("GET /FakePlugin.jar HTTP/1.1\r\n"))
        assertTrue(raw.contains("Host: 192.168.1.156:8899\r\n"))
        assertTrue(raw.contains("User-Agent: UA/1\r\n"))
        assertTrue(raw.contains("Connection: close\r\n"))
        // 不发 Accept-Encoding：拿到 gzip 会把压缩流当文件写坏
        assertFalse(raw.contains("Accept-Encoding"))
        assertTrue(raw.endsWith("\r\n\r\n"))
    }

    @Test
    fun `默认端口不带端口号 路径带 query`() {
        val raw = CleartextHttp.buildGet(
            java.net.URI("http://example.com/a/b?x=1&y=2"), "example.com", 80, "UA/1"
        ).toString(Charsets.UTF_8)
        assertTrue(raw.startsWith("GET /a/b?x=1&y=2 HTTP/1.1\r\n"))
        assertTrue(raw.contains("Host: example.com\r\n"))
    }

    @Test
    fun `根路径补成斜杠`() {
        val raw = CleartextHttp.buildGet(java.net.URI("http://h"), "h", 80, "UA")
            .toString(Charsets.UTF_8)
        assertTrue(raw.startsWith("GET / HTTP/1.1\r\n"))
    }

    // ── 响应解析 ────────────────────────────────────────────────

    @Test
    fun `状态码解析`() {
        assertEquals(200, CleartextHttp.statusCode("HTTP/1.1 200 OK"))
        assertEquals(206, CleartextHttp.statusCode("HTTP/1.0 206 Partial Content"))
        assertEquals(302, CleartextHttp.statusCode("HTTP/1.1 302 Found"))
        assertEquals(-1, CleartextHttp.statusCode("not-a-status-line"))
        assertEquals(-1, CleartextHttp.statusCode("HTTP/1.1 abc"))
        // 手写的极简 http 服务常见多打一个空格；严格按单空格 split 会把它判成畸形
        assertEquals(200, CleartextHttp.statusCode("HTTP/1.1  200 OK"))
        assertEquals(200, CleartextHttp.statusCode("HTTP/1.1\t200 OK"))
    }

    @Test
    fun `chunked 与 Content-Length 并存时忽略 Content-Length`() {
        // RFC 7230 §3.3.3：chunked 优先。代理拼接 chunked 时常顺手带上原始 CL，
        // 拿它当期望长度会把一个完整响应判成「大小不完整」。
        val headChunked = mapOf("content-length" to "347", "transfer-encoding" to "chunked")
        val headPlain = mapOf("content-length" to "347")
        val stream = ByteArrayInputStream(ByteArray(0))
        assertEquals(-1L, CleartextHttp.Exchange(200, headChunked, stream, true) {}.contentLength)
        assertEquals(347L, CleartextHttp.Exchange(200, headPlain, stream, false) {}.contentLength)
        // 头缺失 / 非法同样按未知处理
        assertEquals(-1L, CleartextHttp.Exchange(200, emptyMap(), stream, false) {}.contentLength)
        assertEquals(-1L, CleartextHttp.Exchange(200, mapOf("content-length" to "abc"), stream, false) {}.contentLength)
    }

    @Test
    fun `读取状态行与头 名字大小写不敏感`() {
        val head = CleartextHttp.readHead(
            BufferedInputStream(
                ByteArrayInputStream(
                    bytes("HTTP/1.1 200 OK\r\nContent-Length: 347\r\nLocation: /next\r\n\r\nbody")
                )
            )
        )!!
        assertEquals(200, head.code)
        assertEquals("347", head.headers["content-length"])
        assertEquals("/next", head.headers["location"])
    }

    @Test
    fun `畸形响应返回 null`() {
        assertNull(CleartextHttp.readHead(BufferedInputStream(ByteArrayInputStream(bytes("")))))
        assertNull(CleartextHttp.readHead(BufferedInputStream(ByteArrayInputStream(bytes("garbage\r\n\r\n")))))
        // 只给了状态行、没有空行时按读到 EOF 处理，仍然可用
        val noBlank = CleartextHttp.readHead(
            BufferedInputStream(ByteArrayInputStream(bytes("HTTP/1.1 204 No Content\r\n")))
        )!!
        assertEquals(204, noBlank.code)
    }

    @Test
    fun `超长头抛异常而不是静默截断`() {
        val huge = "HTTP/1.1 200 OK\r\nX-Big: " + "a".repeat(9000) + "\r\n\r\n"
        // 截断会让 body 从这一行中间开始读，把响应头当文件内容写进目标文件 —— 宁可报错
        try {
            CleartextHttp.readHead(BufferedInputStream(ByteArrayInputStream(bytes(huge))))
            org.junit.Assert.fail("超长响应头应当抛 IOException")
        } catch (expected: java.io.IOException) {
            // 预期
        }
    }

    @Test
    fun `Content-Length 非数字或负数按未知处理`() {
        assertEquals(347L, CleartextHttp.parseContentLength("347"))
        assertEquals(-1L, CleartextHttp.parseContentLength(null))
        assertEquals(-1L, CleartextHttp.parseContentLength("abc"))
        assertEquals(-1L, CleartextHttp.parseContentLength("-5"))
    }

    // ── 重定向 ──────────────────────────────────────────────────

    @Test
    fun `只在 3xx 且未超次数时跟随重定向`() {
        val base = java.net.URI("http://192.168.1.156:8899/a/FakePlugin.jar")
        val headers = mapOf("location" to "/b/FakePlugin.jar")
        assertEquals(
            "http://192.168.1.156:8899/b/FakePlugin.jar",
            CleartextHttp.redirectTarget(302, headers, base, 0)
        )
        // 非重定向、缺 Location、次数用尽都返回 null（由调用方当成普通响应处理）
        assertNull(CleartextHttp.redirectTarget(200, headers, base, 0))
        assertNull(CleartextHttp.redirectTarget(302, emptyMap(), base, 0))
        assertNull(CleartextHttp.redirectTarget(302, headers, base, CleartextHttp.MAX_REDIRECTS))
    }

    // ── chunked 解码 ────────────────────────────────────────────

    private fun chunked(body: String) =
        ChunkedInputStream(BufferedInputStream(ByteArrayInputStream(bytes(body))))

    @Test
    fun `还原分块编码的 body`() {
        assertEquals("Wikipedia", readAll(chunked("4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n")))
    }

    @Test
    fun `忽略块扩展与结束后的 trailer`() {
        val raw = "4;ext=1\r\nWiki\r\n0\r\nX-Trailer: 1\r\n\r\n"
        assertEquals("Wiki", readAll(chunked(raw)))
    }

    @Test
    fun `逐字节读与分块读结果一致`() {
        val stream = chunked("3\r\nabc\r\n3\r\ndef\r\n0\r\n\r\n")
        val sb = StringBuilder()
        while (true) {
            val b = stream.read()
            if (b < 0) break
            sb.append(b.toChar())
        }
        assertEquals("abcdef", sb.toString())
        // 读完再读仍是 -1，不会重复吐数据
        assertEquals(-1, stream.read())
    }

    @Test
    fun `分块中途断流视为失败`() {
        // 半块就断掉：chunked 没有 Content-Length，不报错的话调用方无从发现，
        // 用户会拿到一个看似完好的半截 jar
        try {
            readAll(chunked("4\r\nWd"))
            org.junit.Assert.fail("截断的 chunked body 应当抛 IOException")
        } catch (expected: java.io.IOException) {
            // 预期
        }
    }

    @Test
    fun `块长度不是十六进制时报错`() {
        try {
            readAll(chunked("zz\r\nabc\r\n0\r\n\r\n"))
            org.junit.Assert.fail("非法的块长度应当抛 IOException")
        } catch (expected: java.io.IOException) {
            // 预期
        }
    }
}
