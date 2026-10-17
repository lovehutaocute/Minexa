package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隧道转发端口的对齐。
 *
 * 这些用例锁住的是真实故障：用户改了服务端端口，配置文本里还是旧端口，
 * 隧道于是把玩家送到没人监听的端口上（日志只有 connection refused）。
 */
class FrpLocalTargetTest {

    private val toml = """
        serverAddr = "1.2.3.4"
        serverPort = 7000
        auth.token = "abc"

        [[proxies]]
        name = "minecraft"
        type = "tcp"
        localIP = "127.0.0.1"
        localPort = 25565
        remotePort = 25565
    """.trimIndent()

    @Test
    fun `扫出配置里的本地端口`() {
        assertEquals(listOf(25565), FrpLocalTarget.target(toml).ports)
    }

    @Test
    fun `旧的 ini 写法也能扫出来`() {
        val ini = """
            [common]
            server_addr = 1.2.3.4

            [minecraft]
            local_port = 25565
        """.trimIndent()
        assertEquals(listOf(25565), FrpLocalTarget.target(ini).ports)
    }

    @Test
    fun `指向别的机器的配置一律不动`() {
        // 用户把隧道转到局域网里另一台机器上（localIP 不是回环）。
        // 那不是「端口没跟上」，改成手机上的游戏端口才是帮倒忙。
        val lan = """
            [[proxies]]
            name = "pc"
            localIP = "192.168.1.5"
            localPort = 25565
        """.trimIndent()

        val t = FrpLocalTarget.target(lan)
        assertTrue(t.foreignHost)

        val r = FrpLocalTarget.align(lan, 25566)
        assertEquals(0, r.changed)
        assertEquals(lan, r.text)
    }

    @Test
    fun `回环地址不算「别的机器」`() {
        assertFalse(FrpLocalTarget.target(toml).foreignHost)
        assertFalse(FrpLocalTarget.target("local_ip = \"127.0.0.1\"\nlocalPort = 1\n").foreignHost)
        assertFalse(FrpLocalTarget.target("localIp = localhost\nlocalPort = 1\n").foreignHost)
    }

    @Test
    fun `端口对不上时改成真实端口`() {
        val r = FrpLocalTarget.align(toml, 25566)

        assertEquals(1, r.changed)
        assertEquals(25565, r.from)
        assertTrue(r.text.contains("localPort = 25566"))
        // 其余内容一个字节都不能动
        assertTrue(r.text.contains("serverAddr = \"1.2.3.4\""))
        assertTrue(r.text.contains("remotePort = 25565"))
        assertFalse(r.text.contains("localPort = 25565"))
    }

    @Test
    fun `端口本来就对时不动文本`() {
        val r = FrpLocalTarget.align(toml, 25565)
        assertEquals(0, r.changed)
        assertNull(r.from)
        assertEquals(toml, r.text)
    }

    @Test
    fun `多个代理指向同一个端口时一起改`() {
        // MC 常配两条代理：一条 TCP、一条 UDP，都指同一个本地端口。
        val two = """
            [[proxies]]
            name = "mc-tcp"
            localPort = 25565

            [[proxies]]
            name = "mc-udp"
            localPort = 25565
        """.trimIndent()

        val r = FrpLocalTarget.align(two, 25566)

        assertEquals(2, r.changed)
        assertFalse(r.text.contains("25565"))
        assertEquals(2, r.text.split("25566").size - 1)
    }

    @Test
    fun `多个代理各指不同端口时拒绝改动`() {
        // 例如顺带代理了一个面板。全改成游戏端口会把那个服务也拉过来，
        // 那就不是「对齐」而是「改错」，所以这里必须一动不动。
        val mixed = """
            [[proxies]]
            name = "minecraft"
            localPort = 25565

            [[proxies]]
            name = "panel"
            localPort = 8080
        """.trimIndent()

        val r = FrpLocalTarget.align(mixed, 25566)

        assertTrue(r.refused)
        assertEquals(0, r.changed)
        assertEquals(mixed, r.text)
    }

    @Test
    fun `配置里没写本地端口时什么都不做`() {
        val noPort = "serverAddr = \"1.2.3.4\"\nserverPort = 7000\n"
        val r = FrpLocalTarget.align(noPort, 25566)

        assertFalse(r.refused)
        assertEquals(0, r.changed)
        assertEquals(noPort, r.text)
    }

    @Test
    fun `真实端口非法时什么都不做`() {
        // 0 表示「还没读到端口真源」，这时候绝不能把用户配置改成 0。
        val r = FrpLocalTarget.align(toml, 0)
        assertEquals(0, r.changed)
        assertEquals(toml, r.text)

        val r2 = FrpLocalTarget.align(toml, 70000)
        assertEquals(0, r2.changed)
        assertEquals(toml, r2.text)
    }

    @Test
    fun `行尾注释会被保留`() {
        val commented = "localPort = 25565   # 游戏端口\n"
        val r = FrpLocalTarget.align(commented, 25566)
        assertEquals(1, r.changed)
        assertEquals("localPort = 25566   # 游戏端口\n", r.text)
    }
}
