package com.mineserve.mobile.server.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 公网地址解析测试。
 *
 * 重点钉住两类回归：
 * 1. **绝不拼出 `:null`** —— 曾经的实现无条件 `"$addr:$port"`，域名类隧道没有
 *    remotePort 时界面就显示成 `frp.example.com:null`。
 * 2. **平台 API 的 remoteAddress 优先于配置里的 serverAddr** —— 前者是玩家该连的
 *    地址，后者只是 frps 节点地址，两者在自定义域名/HTTP 隧道下不同。
 */
class FrpAddressTest {

    // ── build：优先级 ───────────────────────────────────────────

    @Test
    fun `remoteAddress 优先于节点名与端口`() {
        // 平台按隧道实际映射算出来的地址最可信，即使其它字段也在，也用它。
        assertEquals(
            "abc.mefrp.com:23456",
            FrpAddress.build(
                remoteAddress = "abc.mefrp.com:23456",
                nodeName = "frp.mefrp.com",
                remotePort = 7000
            )
        )
    }

    @Test
    fun `没有 remoteAddress 时用节点名加端口`() {
        assertEquals(
            "node.example.com:25565",
            FrpAddress.build(remoteAddress = null, nodeName = "node.example.com", remotePort = 25565)
        )
    }

    @Test
    fun `缺端口时只返回节点名而绝不拼 null`() {
        // 这条是本次修复的核心回归点。
        assertEquals(
            "abc.mefrp.com",
            FrpAddress.build(remoteAddress = null, nodeName = "abc.mefrp.com", remotePort = null)
        )
    }

    @Test
    fun `端口为 0 或负数时按缺端口处理`() {
        // 平台偶尔用 0 表示「未分配」，拼出 `:0` 同样是错信息。
        assertEquals("n.example.com", FrpAddress.build(null, "n.example.com", 0))
        assertEquals("n.example.com", FrpAddress.build(null, "n.example.com", -1))
    }

    @Test
    fun `全空返回 null`() {
        assertNull(FrpAddress.build(null, null, null))
        assertNull(FrpAddress.build("", "", null))
        assertNull(FrpAddress.build("   ", "   ", null))
    }

    @Test
    fun `有端口但没有节点名时返回 null 而不是裸端口`() {
        // 只有端口没有主机，拼出来的东西没有任何意义。
        assertNull(FrpAddress.build(null, null, 25565))
    }

    @Test
    fun `两端空白被清理`() {
        assertEquals("abc.com", FrpAddress.build("  abc.com  ", null, null))
        assertEquals("abc.com:80", FrpAddress.build(null, " abc.com ", 80))
    }

    // ── fromConfigText：兜底解析 ─────────────────────────────────

    @Test
    fun `解析 toml 的 serverAddr 与 remotePort`() {
        val toml = """
            serverAddr = "frp.example.com"
            serverPort = 7000

            [[proxies]]
            name = "mc"
            type = "tcp"
            localPort = 25565
            remotePort = 25565
        """.trimIndent()

        assertEquals("frp.example.com:25565", FrpAddress.fromConfigText(toml))
    }

    @Test
    fun `配置缺 remotePort 时只返回 serverAddr`() {
        val toml = """
            serverAddr = "frp.example.com"
            serverPort = 7000
        """.trimIndent()

        // 关键：不能因为 serverPort 存在就拿它当访问端口，
        // 也不能拼出 `frp.example.com:null`。
        assertEquals("frp.example.com", FrpAddress.fromConfigText(toml))
    }

    @Test
    fun `解析 json 写法`() {
        val json = """{"serverAddr":"frp.example.com","remotePort":12345}"""
        assertEquals("frp.example.com:12345", FrpAddress.fromConfigText(json))
    }

    @Test
    fun `解析 yaml 写法与下划线字段名`() {
        val yaml = """
            server_addr: frp.example.com
            remote_port: 12345
        """.trimIndent()
        assertEquals("frp.example.com:12345", FrpAddress.fromConfigText(yaml))
    }

    @Test
    fun `正则不跨行吞掉后续字段`() {
        // 曾经的 `.*` 会跨行匹配，把不同字段的值拼到一起。
        val toml = """
            serverAddr = "frp.example.com"

            [[proxies]]
            name = "25565-should-not-be-read"
        """.trimIndent()

        assertEquals("frp.example.com", FrpAddress.fromConfigText(toml))
    }

    @Test
    fun `配置里完全没有地址时返回 null`() {
        assertNull(FrpAddress.fromConfigText("[[proxies]]\nname = \"x\""))
        assertNull(FrpAddress.fromConfigText(""))
    }
}
