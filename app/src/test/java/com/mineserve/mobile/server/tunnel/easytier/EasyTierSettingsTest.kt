package com.mineserve.mobile.server.tunnel.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住生成的 TOML。
 *
 * 这份配置是 JNI 唯一的输入：错了不会有编译错误，只会在真机上以一行
 * `os error 99` 或「实例启动失败(-1)」结束。所以把几个**踩过坑**的点写成断言。
 */
class EasyTierSettingsTest {

    private fun settings(
        virtualIp: String = "10.144.144.7",
        port: Int = 25565,
        peers: List<String> = listOf(EasyTierSettings.DEFAULT_PUBLIC_PEER)
    ) = EasyTierSettings(
        instanceName = "minexa",
        hostname = "minexa-phone",
        virtualIp = virtualIp,
        networkName = "test-net",
        networkSecret = "secret",
        serverPort = port,
        peers = peers
    )

    @Test
    fun `core 不自己建 TUN`() {
        // no_tun 的语义是"core 别自己建 TUN"（TUN 由 VpnService 建、fd 交进来），
        // 不是"不要 TUN"。写成 false 时 core 会去建它没权限建的设备，实例直接起不来。
        val toml = settings().toToml()
        assertTrue("必须是 no_tun = true：\n$toml", toml.contains("no_tun = true"))
        assertFalse("不能出现 no_tun = false：\n$toml", toml.contains("no_tun = false"))
    }

    @Test
    fun `虚拟 IP 不带掩码`() {
        // 带掩码时 core 解析出的地址找不对接口（真机日志 can not find interface name by ip）。
        // 掩码只由 VpnService.addAddress 提供。
        val toml = settings(virtualIp = "10.144.144.7").toToml()
        assertTrue("ipv4 不能带 /24：\n$toml", toml.contains("ipv4 = \"10.144.144.7\""))
        assertFalse("不该出现 /24 后缀：\n$toml", toml.contains("10.144.144.7/24"))
    }

    @Test
    fun `端口转发用独立端口，绝不和服务端抢 25565`() {
        // 真机事故（2026-10-24，用户报 TUN fd 被拒绝 -1）：
        // port_forward 若和服务端绑同一个端口，两者只能活一个 ——
        // 通配地址与具体地址在同一端口上同样冲突（实测 nc 绑定直接 EADDRINUSE）。
        val toml = settings(virtualIp = "10.144.144.7", port = 25565).toToml()
        assertTrue("bind_addr 用转发端口 25566：\n$toml", toml.contains("bind_addr = \"0.0.0.0:25566\""))
        assertFalse("转发监听不能落在 25565：\n$toml", toml.contains("bind_addr = \"0.0.0.0:25565\""))
        assertFalse("也不能绑虚拟 IP（通配地址已存在时同样冲突）：\n$toml", toml.contains("bind_addr = \"10.144.144.7"))
        assertTrue("dst 仍指向服务端真实端口：\n$toml", toml.contains("dst_addr = \"127.0.0.1:25565\""))
        assertTrue("proto 必须是 tcp：\n$toml", toml.contains("proto = \"tcp\""))
    }

    @Test
    fun `服务端端口变化时转发端口保持不变`() {
        // 服务端换到 25570：转发监听仍是 25566，只有 dst 跟着变。
        val toml = settings(port = 25570).toToml()
        assertTrue(toml.contains("bind_addr = \"0.0.0.0:25566\""))
        assertTrue(toml.contains("dst_addr = \"127.0.0.1:25570\""))
    }

    @Test
    fun `服务端自己占了转发端口时顺延`() {
        // 极端情况：用户把服务端改到 25566。这时转发只能换 25567，
        // 否则又回到"两个监听抢一个端口"。
        val toml = settings(port = 25566).toToml()
        assertTrue("必须顺延到 25567：\n$toml", toml.contains("bind_addr = \"0.0.0.0:25567\""))
        assertTrue(toml.contains("dst_addr = \"127.0.0.1:25566\""))
    }

    @Test
    fun `玩家地址用转发端口`() {
        // 界面上、二维码里、日志里都用这一个函数，避免各拼一套。
        assertEquals("10.144.144.7:25566", EasyTierSettings.playerAddress("10.144.144.7", 25565))
        assertEquals("10.144.144.7:25567", EasyTierSettings.playerAddress("10.144.144.7", 25566))
        assertEquals("", EasyTierSettings.playerAddress("", 25565))
    }

    @Test
    fun `派生 IP 落在 EasyTier 的 DHCP 默认网段`() {
        // 真机事故（2026-10-24）：服务端写死 10.144.144.x，玩家 DHCP 拿到的是
        // EasyTier 默认池 10.126.126.x —— 两边各在一个网段，玩家路由表里没有服务端，
        // 组网层 p2p 已通但游戏照样超时。静态 IP 必须落在 DHCP 池内。
        repeat(50) { i ->
            val ip = EasyTierSettings.deriveVirtualIp("net-$i")
            assertTrue("必须在 10.126.126.0/24：$ip", ip.startsWith("10.126.126."))
            val last = ip.substringAfterLast('.').toInt()
            assertTrue("避开网络/广播/网关地址：$ip", last in 20..219)
        }
        // 同名网络永远同一个地址（玩家抄过一次就一直有效）
        assertEquals(EasyTierSettings.deriveVirtualIp("abc"), EasyTierSettings.deriveVirtualIp("abc"))
        assertNotEquals(EasyTierSettings.deriveVirtualIp("abc"), EasyTierSettings.deriveVirtualIp("abd"))
    }

    @Test
    fun `多个引荐节点各生成一段 peer`() {
        val toml = settings(
            peers = listOf("tcp://public.easytier.top:11010", "udp://1.2.3.4:11010")
        ).toToml()
        assertEquals("两个 peer 应有两段 [[peer]]：\n$toml", 2, Regex("\\[\\[peer]]").findAll(toml).count())
        assertTrue(toml.contains("uri = \"udp://1.2.3.4:11010\""))
    }

    @Test
    fun `空 peer 被过滤`() {
        val toml = settings(peers = listOf("", "  ", "tcp://a:11010")).toToml()
        assertEquals("空串不该生成空的 [[peer]] 段：\n$toml", 1, Regex("\\[\\[peer]]").findAll(toml).count())
    }

    @Test
    fun `网络名与密钥原样写入`() {
        val toml = settings().toToml()
        assertTrue(toml.contains("network_name = \"test-net\""))
        assertTrue(toml.contains("network_secret = \"secret\""))
    }

    @Test
    fun `instance_name 与 setTunFd 用的是同一个值`() {
        // 两者不一致时 TUN fd 会挂到不存在的实例上，失败信息只有 -1。
        val s = settings()
        assertTrue(s.toToml().contains("instance_name = \"${s.instanceName}\""))
    }

    @Test
    fun `密钥长度足够且只含十六进制`() {
        repeat(20) {
            val secret = EasyTierSettings.generateSecret()
            assertEquals(32, secret.length)
            assertTrue("非十六进制字符：$secret", secret.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun `默认引荐节点不再用已死的官方公共节点`() {
        // public.easytier.top 自 2026-02 起无响应（域名解析但 TCP 不通），
        // public.easytier.cn 直接 NXDOMAIN。官方没公告也没换域名，
        // 默认值必须换成社区节点，否则所有用户开箱即"连不上任何人"。
        assertTrue(
            "默认 peer 不能是 public.easytier.top：${EasyTierSettings.DEFAULT_PUBLIC_PEER}",
            !EasyTierSettings.DEFAULT_PUBLIC_PEER.contains("public.easytier.top")
        )
        assertTrue(
            "备用节点里也不能有官方死节点：${EasyTierSettings.FALLBACK_PUBLIC_PEERS}",
            EasyTierSettings.FALLBACK_PUBLIC_PEERS.none { it.contains("public.easytier") }
        )
        assertEquals(
            "默认列表 = 主节点 + 备用",
            listOf(EasyTierSettings.DEFAULT_PUBLIC_PEER) + EasyTierSettings.FALLBACK_PUBLIC_PEERS,
            EasyTierSettings.DEFAULT_PEERS
        )
        // 数据类不传 peers 时应落到默认列表（直接构造，绕开本测试的 settings() 帮手）
        assertEquals(
            EasyTierSettings.DEFAULT_PEERS,
            EasyTierSettings(virtualIp = "10.144.144.7", networkName = "n", networkSecret = "s").peers
        )
    }
}
