package com.mineserve.mobile.server.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * STUN 报文层的字节级单测（计划表 V0.1）。
 *
 * ## 为什么先补这一层
 *
 * `StunClient` 的解析路径处理的是**来自公网、不可信**的数据，而在此之前它
 * 一条单测都没有（`docs/STUN-PARITY-CHECKLIST.md` 自述那轮 P0–P6 重写只保证
 * 「编译通过 + 构建 APK」）。XOR 掩码顺序写反、属性 4 字节对齐漏掉、
 * 越界没挡住 —— 这几种错误在真机上表现为「偶尔连不上」，极难归因。
 *
 * 这里全部是纯字节断言，不需要真机、不需要网络、不 import 任何 `android.*`
 * （本模块未开 `isReturnDefaultValues`，未 mock 的 Android 类型会抛
 * "not mocked" 造成假失败）。
 */
class StunClientTest {

    // ── 报文构造辅助 ────────────────────────────────────────────

    /** 按 RFC 5389 拼一条 STUN 消息：20 字节头 + 属性区（每个属性按 4 字节对齐）。 */
    private fun stunMessage(
        type: Int,
        attributes: List<Pair<Int, ByteArray>>,
        txId: ByteArray = ByteArray(12)
    ): ByteArray {
        val body = ByteArrayOutputStream()
        attributes.forEach { (attrType, value) ->
            body.write((attrType shr 8) and 0xFF)
            body.write(attrType and 0xFF)
            body.write((value.size shr 8) and 0xFF)
            body.write(value.size and 0xFF)
            body.write(value)
            repeat((4 - (value.size % 4)) % 4) { body.write(0) }
        }
        val payload = body.toByteArray()
        val message = ByteArray(20 + payload.size)
        message[0] = ((type shr 8) and 0xFF).toByte()
        message[1] = (type and 0xFF).toByte()
        message[2] = ((payload.size shr 8) and 0xFF).toByte()
        message[3] = (payload.size and 0xFF).toByte()
        message[4] = 0x21
        message[5] = 0x12
        message[6] = 0xA4.toByte()
        message[7] = 0x42
        System.arraycopy(txId, 0, message, 8, 12)
        System.arraycopy(payload, 0, message, 20, payload.size)
        return message
    }

    /** 按 RFC 5389 §15.2 生成 XOR-MAPPED-ADDRESS 的属性值。 */
    private fun xorMappedValue(ip: String, port: Int, family: Int = 0x01): ByteArray {
        val value = ByteArray(8)
        value[0] = 0
        value[1] = family.toByte()
        val xoredPort = port xor 0x2112
        value[2] = ((xoredPort shr 8) and 0xFF).toByte()
        value[3] = (xoredPort and 0xFF).toByte()
        val parts = ip.split('.').map { it.toInt() }
        value[4] = (parts[0] xor 0x21).toByte()
        value[5] = (parts[1] xor 0x12).toByte()
        value[6] = (parts[2] xor 0xA4).toByte()
        value[7] = (parts[3] xor 0x42).toByte()
        return value
    }

    /** 按 RFC 5389 §15.6 生成 ERROR-CODE 的属性值（class 与 number 分置两个字节）。 */
    private fun errorCodeValue(code: Int, reason: String): ByteArray {
        val reasonBytes = reason.toByteArray(Charsets.ISO_8859_1)
        val value = ByteArray(4 + reasonBytes.size)
        value[0] = 0
        value[1] = 0
        value[2] = ((code / 100) and 0xFF).toByte()
        value[3] = ((code % 100) and 0xFF).toByte()
        System.arraycopy(reasonBytes, 0, value, 4, reasonBytes.size)
        return value
    }

    // ── Binding Request 编码 ────────────────────────────────────

    @Test
    fun `Binding Request 是 20 字节且头部正确`() {
        val txId = ByteArray(12) { it.toByte() }
        val request = buildBindingRequest(txId)
        assertEquals(20, request.size)
        assertEquals(0x00, request[0].toInt() and 0xFF)   // 类型 0x0001 = Binding Request
        assertEquals(0x01, request[1].toInt() and 0xFF)
        assertEquals(0x00, request[2].toInt() and 0xFF)   // 属性区长度 0
        assertEquals(0x00, request[3].toInt() and 0xFF)
        assertArrayEquals(StunClient.MAGIC_COOKIE, request.copyOfRange(4, 8))
        assertArrayEquals(txId, request.copyOfRange(8, 20))
    }

    // ── XOR-MAPPED-ADDRESS 解码 ─────────────────────────────────

    @Test
    fun `报文布局符合 RFC 5389：头 20 字节 + 属性区按 4 字节对齐`() {
        val m = stunMessage(0x0101, listOf(0x0020 to xorMappedValue("203.0.113.7", 51234)))
        assertEquals("报文总长", 32, m.size)
        assertEquals("头部长度字段", 12, ((m[2].toInt() and 0xFF) shl 8) or (m[3].toInt() and 0xFF))
        assertEquals("属性类型", 0x0020, ((m[20].toInt() and 0xFF) shl 8) or (m[21].toInt() and 0xFF))
        assertEquals("属性长度", 8, ((m[22].toInt() and 0xFF) shl 8) or (m[23].toInt() and 0xFF))
        assertEquals("family", 1, m[25].toInt() and 0xFF)
    }

    @Test
    fun `XOR-MAPPED-ADDRESS 解掩码后得到真实公网地址`() {
        val message = stunMessage(
            type = 0x0101,
            attributes = listOf(0x0020 to xorMappedValue("203.0.113.7", 51234))
        )
        val mapped = parseMappedAddress(message, 0, message.size)
        assertNotNull(mapped)
        assertEquals("203.0.113.7", mapped!!.host)
        assertEquals(51234, mapped.port)
    }

    @Test
    fun `XOR 掩码按 cookie 逐字节异或——掩码写反会立刻失败`() {
        // 选一组每个字节都不同的地址，任何一个字节的掩码顺序错位都无法凑巧通过。
        val message = stunMessage(
            type = 0x0101,
            attributes = listOf(0x0020 to xorMappedValue("1.2.3.4", 1))
        )
        val mapped = parseMappedAddress(message, 0, message.size)
        assertEquals("1.2.3.4", mapped?.host)
        assertEquals(1, mapped?.port)
    }

    @Test
    fun `XOR-MAPPED-ADDRESS 优先于 MAPPED-ADDRESS`() {
        // 明文 MAPPED-ADDRESS（会指向内网）与 XOR 版本（真实公网地址）同时存在时，
        // 必须取 XOR 版本 —— 这也是真实服务器的常见形态。
        val plain = ByteArray(8)
        plain[1] = 0x01
        plain[2] = 0x00
        plain[3] = 0x01           // 端口 1
        plain[4] = 10.toByte()
        plain[5] = 0
        plain[6] = 0
        plain[7] = 1              // 10.0.0.1

        val message = stunMessage(
            type = 0x0101,
            attributes = listOf(
                0x0001 to plain,
                0x0020 to xorMappedValue("203.0.113.7", 51234)
            )
        )
        val mapped = parseMappedAddress(message, 0, message.size)
        assertEquals("203.0.113.7", mapped?.host)
        assertEquals(51234, mapped?.port)
    }

    @Test
    fun `没有 XOR 版本时回退到 MAPPED-ADDRESS`() {
        // MAPPED-ADDRESS 的属性值是明文 family/port/ip，不做异或。
        val plain = ByteArray(8)
        plain[1] = 0x01
        plain[2] = ((25565 shr 8) and 0xFF).toByte()
        plain[3] = (25565 and 0xFF).toByte()
        plain[4] = 198.toByte()
        plain[5] = 51.toByte()
        plain[6] = 100.toByte()
        plain[7] = 9.toByte()

        val message = stunMessage(0x0101, listOf(0x0001 to plain))
        val mapped = parseMappedAddress(message, 0, message.size)
        assertEquals("198.51.100.9", mapped?.host)
        assertEquals(25565, mapped?.port)
    }

    @Test
    fun `IPv6 映射（family 0x02）被忽略——本功能只处理 IPv4`() {
        val ipv6Like = ByteArray(8)
        ipv6Like[1] = 0x02
        val message = stunMessage(0x0101, listOf(0x0020 to ipv6Like))
        assertNull(parseMappedAddress(message, 0, message.size))
    }

    @Test
    fun `属性长度声明越界时不抛异常且放弃该属性`() {
        // 手工构造：属性声明长度 200，但报文总长才 40 —— 畸形/恶意数据常见形态。
        val message = stunMessage(0x0101, listOf(0x0020 to xorMappedValue("1.2.3.4", 1)))
        val corrupted = message.copyOf()
        corrupted[22] = 0x00
        corrupted[23] = 0xC8.toByte()   // 属性长度改成 200
        // 关键断言：不抛异常（越界必须在这里被挡住，不能逃逸到读线程）
        assertNull(parseMappedAddress(corrupted, 0, corrupted.size))
    }

    @Test
    fun `属性区不足 4 字节时安全返回 null`() {
        val message = stunMessage(0x0101, emptyList())
        assertNull(parseMappedAddress(message, 0, message.size))
    }

    // ── ERROR-CODE 解码 ────────────────────────────────────────

    @Test
    fun `ERROR-CODE 解析出数字与原因短语`() {
        val message = stunMessage(
            type = 0x0111,
            attributes = listOf(0x0009 to errorCodeValue(401, "Unauthorized"))
        )
        assertEquals("401 Unauthorized", parseErrorCode(message, 0, message.size))
    }

    @Test
    fun `没有 ERROR-CODE 属性时返回 null`() {
        val message = stunMessage(0x0111, listOf(0x0020 to xorMappedValue("1.2.3.4", 1)))
        assertNull(parseErrorCode(message, 0, message.size))
    }

    // ── 服务器列表解析 ─────────────────────────────────────────

    @Test
    fun `服务器行支持裸主机名与带端口两种写法`() {
        assertEquals("stun.example.com", StunClient.parseServer("stun.example.com")?.host)
        assertEquals(3478, StunClient.parseServer("stun.example.com")?.port)
        assertEquals(1234, StunClient.parseServer("stun.example.com:1234")?.port)
        assertEquals("stun.example.com", StunClient.parseServer("stun://stun.example.com")?.host)
    }

    @Test
    fun `非法端口与空行返回 null——一行写错不该让整条隧道起不来`() {
        assertNull(StunClient.parseServer(""))
        assertNull(StunClient.parseServer("   "))
        assertNull(StunClient.parseServer("stun.example.com:0"))
        assertNull(StunClient.parseServer("stun.example.com:70000"))
        assertNull(StunClient.parseServer(":3478"))
    }

    @Test
    fun `多行解析会去重并保序`() {
        val servers = StunClient.parseServers(
            """
            b.example.com
            a.example.com
            b.example.com
            """.trimIndent()
        )
        assertEquals(listOf("b.example.com", "a.example.com"), servers.map { it.host })
    }

    @Test
    fun `内置服务器列表非空且无重复`() {
        assertTrue(StunClient.DEFAULT_SERVERS.isNotEmpty())
        assertEquals(
            StunClient.DEFAULT_SERVERS.size,
            StunClient.DEFAULT_SERVERS.distinct().size
        )
    }

    // ── 公网地址判定（S0.2） ────────────────────────────────────

    @Test
    fun `公网地址被判定为可用`() {
        listOf("1.1.1.1", "8.8.8.8", "223.5.5.5", "114.114.114.114", "223.6.6.6")
            .forEach { assertTrue("$it 应判为公网", isPublicIpv4Address(it)) }
    }

    @Test
    fun `CGNAT 100_64 段被判为非公网——这是手机流量下最常见的坑`() {
        listOf("100.64.0.1", "100.127.255.254").forEach {
            assertFalse(it, isPublicIpv4Address(it))
            assertEquals("运营商 CGNAT 地址 100.64.0.0/10", nonPublicIpv4Reason(it))
        }
        // 边界外仍应是公网：100.63.x 与 100.128.x
        assertTrue(isPublicIpv4Address("100.63.0.1"))
        assertTrue(isPublicIpv4Address("100.128.0.1"))
    }

    @Test
    fun `私网与回环被判为非公网`() {
        assertFalse(isPublicIpv4Address("10.0.0.1"))
        assertFalse(isPublicIpv4Address("172.16.0.1"))
        assertFalse(isPublicIpv4Address("172.31.255.255"))
        assertFalse(isPublicIpv4Address("192.168.1.100"))
        assertFalse(isPublicIpv4Address("127.0.0.1"))
        // 172.15 与 172.32 不在 172.16/12 内，应是公网
        assertTrue(isPublicIpv4Address("172.15.0.1"))
        assertTrue(isPublicIpv4Address("172.32.0.1"))
    }

    @Test
    fun `链路本地与保留段被判为非公网`() {
        assertFalse(isPublicIpv4Address("169.254.1.1"))
        assertFalse(isPublicIpv4Address("0.0.0.0"))
        assertFalse(isPublicIpv4Address("198.18.0.1"))
        assertFalse(isPublicIpv4Address("192.0.2.1"))
        assertFalse(isPublicIpv4Address("203.0.113.1"))
        assertFalse(isPublicIpv4Address("224.0.0.1"))
        assertFalse(isPublicIpv4Address("255.255.255.255"))
    }

    @Test
    fun `非法字符串不会崩也不会被误判为公网`() {
        listOf("", "abc", "1.2.3", "1.2.3.4.5", "1.2.3.999", "1.2.3.-1", "::1").forEach {
            assertFalse("[$it] 不应判为公网", isPublicIpv4Address(it))
        }
    }

    // ── 辅助 ───────────────────────────────────────────────────

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(
            "字节数组不一致：期望 ${expected.joinToString(",")}，实际 ${actual.joinToString(",")}",
            expected.joinToString(","),
            actual.joinToString(",")
        )
    }
}
