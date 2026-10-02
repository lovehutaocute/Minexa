package com.mineserve.mobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * http_probe 的裸 socket 报文层。
 *
 * 为什么值得单测：明文 http 分支绕开了 HttpURLConnection（Android 明文策略会拒掉
 * 127.0.0.1），代价是要自己拼报文、自己解析响应。这两件事出错的典型表现是
 * 「探测结果看着有、其实是错的」，很难从返回值上直观发现，所以按纯函数钉死。
 */
class McpRawHttpTest {

    private fun req(uri: String, method: String = "GET", body: String? = null): String =
        String(McpRawHttp.buildRequest(URI(uri), method, body, "MineServe-MCP/test"), Charsets.UTF_8)

    // ── 请求构造 ──────────────────────────────────────────────

    @Test
    fun `get request uses path and terminates header block`() {
        val r = req("http://127.0.0.1:8931/mcp")
        assertTrue(r.startsWith("GET /mcp HTTP/1.1\r\n"))
        assertTrue(r.endsWith("\r\n\r\n"))
        assertTrue(r.contains("Connection: close\r\n"))
        assertTrue(r.contains("Host: 127.0.0.1:8931\r\n"))
    }

    @Test
    fun `default port is omitted from host header`() {
        assertTrue(req("http://example.com/").contains("Host: example.com\r\n"))
        assertTrue(req("https://example.com/").contains("Host: example.com\r\n"))
    }

    @Test
    fun `non default port is kept in host header`() {
        assertTrue(req("https://example.com:8443/x").contains("Host: example.com:8443\r\n"))
        assertTrue(req("http://example.com:80/x").contains("Host: example.com\r\n"))
    }

    @Test
    fun `empty path falls back to slash and query is preserved`() {
        assertTrue(req("http://h").startsWith("GET / HTTP/1.1\r\n"))
        assertTrue(req("http://h/a/b?x=1&y=2").startsWith("GET /a/b?x=1&y=2 HTTP/1.1\r\n"))
        assertTrue(req("http://h?q=1").startsWith("GET /?q=1 HTTP/1.1\r\n"))
    }

    @Test
    fun `body adds content length in utf8 bytes and is appended verbatim`() {
        val r = req("http://h/api", method = "POST", body = "{\"a\":\"中文\"}")
        // {"a":"中文"} = 8 个 ASCII 字节（{ " a " : " " }）+ 2 个汉字 × 3 字节 = 14
        assertTrue(r.contains("Content-Type: application/json\r\n"))
        assertTrue(r.contains("Content-Length: 14\r\n"))
        assertTrue(r.endsWith("{\"a\":\"中文\"}"))
    }

    @Test
    fun `content length counts bytes not characters`() {
        // 「中文」2 个字符 = 6 字节。若按字符数写就会短报，服务端会截断请求体。
        val r = req("http://h/api", method = "POST", body = "中文")
        assertTrue(r.contains("Content-Length: 6\r\n"))
    }

    @Test
    fun `empty body is treated as no body`() {
        val r = req("http://h/api", method = "POST", body = "")
        assertTrue(!r.contains("Content-Length"))
        assertTrue(!r.contains("Content-Type"))
    }

    @Test
    fun `head request has no content length`() {
        val r = req("http://h/", method = "HEAD")
        assertTrue(r.startsWith("HEAD / HTTP/1.1\r\n"))
        assertTrue(!r.contains("Content-Length"))
    }

    // ── 响应解析 ──────────────────────────────────────────────

    @Test
    fun `parses status line headers and body`() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nX-A: 1\r\n\r\nhello"
        val r = McpRawHttp.parseResponse(raw, 4096)
        assertEquals("HTTP/1.1 200 OK", r.statusLine)
        assertEquals(200, r.code)
        assertEquals(listOf("Content-Type: text/plain", "X-A: 1"), r.headerLines)
        assertEquals("hello", r.body)
    }

    @Test
    fun `body is truncated to maxBody characters`() {
        val raw = "HTTP/1.1 200 OK\r\n\r\n" + "A".repeat(100)
        assertEquals(10, McpRawHttp.parseResponse(raw, 10).body.length)
    }

    @Test
    fun `tolerates lf only separators`() {
        val r = McpRawHttp.parseResponse("HTTP/1.1 404 Not Found\nServer: x\n\nnope", 4096)
        assertEquals(404, r.code)
        assertEquals(listOf("Server: x"), r.headerLines)
        assertEquals("nope", r.body)
    }

    @Test
    fun `header only response yields empty body`() {
        val r = McpRawHttp.parseResponse("HTTP/1.1 204 No Content\r\n\r\n", 4096)
        assertEquals(204, r.code)
        assertTrue(r.body.isEmpty())
    }

    @Test
    fun `garbage response does not throw and reports code minus one`() {
        val r = McpRawHttp.parseResponse("not http at all", 4096)
        assertEquals(-1, r.code)
        assertEquals("not http at all", r.statusLine)
        assertTrue(r.body.isEmpty())
    }

    @Test
    fun `status code parsing`() {
        assertEquals(200, McpRawHttp.statusCode("HTTP/1.1 200 OK"))
        assertEquals(500, McpRawHttp.statusCode("HTTP/1.0 500 Internal Server Error"))
        assertEquals(-1, McpRawHttp.statusCode(""))
        assertEquals(-1, McpRawHttp.statusCode("200 OK"))
        assertEquals(-1, McpRawHttp.statusCode("HTTP/1.1 abc OK"))
    }

    // ── 端口与主机 ────────────────────────────────────────────

    @Test
    fun `port resolution`() {
        assertEquals(80, McpRawHttp.port(URI("http://h/x")))
        assertEquals(443, McpRawHttp.port(URI("https://h/x")))
        assertEquals(8931, McpRawHttp.port(URI("http://h:8931/x")))
        assertEquals(80, McpRawHttp.defaultPort(URI("ftp://h/x")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `url without host is rejected`() {
        McpRawHttp.host(URI("/relative/only"))
    }
}
