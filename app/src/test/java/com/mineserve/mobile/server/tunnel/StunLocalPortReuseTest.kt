package com.mineserve.mobile.server.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 「隧道重建后公网端口不变」依赖两件事，本文件各守一件：
 *
 * 1. [StunTcpSession.connect] 传 `reuseLocalPort` 时**真的**占了那个本地端口。
 *    本地端口相同 + STUN 服务器相同 ⇒ 出站五元组相同 ⇒ NAT 只能沿用既有映射
 *    ⇒ 外部端口照旧。若这一步没生效，重建必然换端口。
 * 2. 内置服务器列表里不得再出现 TURN 服务器。它们会应答 Binding Request 看着可用，
 *    但会单方面关闭长连接，是「保活开着还换端口」的直接成因（2026-10-16 日志）。
 *
 * ⚠️ 本机跑的是 Windows JVM，Windows 与 Linux/Android 的 `SO_REUSEADDR` 语义不同
 * （Windows 允许绑到被占用的端口，Linux 只在双方都带该标志时放行），所以这里**不**
 * 断言「绑不到已被占用的端口」——那条在两边结果相反，只能真机验。
 */
class StunLocalPortReuseTest {

    @Test
    fun `指定本地端口时 连接确实占用该端口 且关闭后可再次复用`() {
        // Windows 上「设了 SO_REUSEADDR 再 bind 固定端口，然后 connect」会以
        // WSAEADDRINUSE（Address already in use: getsockopt）失败，Linux/Android 不会。
        // 所以这条只在类 Unix 上验；Windows 开发机上显式跳过而不是让它变红。
        val osName = System.getProperty("os.name").orEmpty()
        Assume.assumeTrue("仅 Linux/Android 语义下可验（当前 $osName）", osName.lowercase().contains("linux"))
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { server ->
            // 借一个空闲端口号当「上次用过的本地端口」。
            val probe = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val wanted = probe.localPort
            probe.close()

            val target = StunServer("127.0.0.1", server.localPort)

            val first = StunTcpSession.connect(target, connectTimeoutMs = 2000, reuseLocalPort = wanted)
            try {
                assertEquals("首次连接应占用指定的本地端口", wanted, first.localPort)
            } finally {
                // 复刻生产环境：STUN 服务器对 TCP 是「一问一答即 RST」，连接是**被对端重置**的，
                // 客户端根本不会进 TIME_WAIT，端口可以立刻复用。
                // 少了这一步，客户端主动 close 会把同一条四元组留在 TIME_WAIT，Linux 内核会以
                // EADDRNOTAVAIL 拒绝立刻重建 —— 那样测的就是内核对 TIME_WAIT 的策略，而不是
                // 「reuseLocalPort 有没有真的生效」。
                runCatching {
                    server.accept().use { accepted ->
                        accepted.setSoLinger(true, 0) // 0 = 立即 RST
                    }
                }
                first.close()
            }
            // 等 RST 落地：这一步是异步的，立刻重连有可能仍撞上尚未清理的四元组。
            Thread.sleep(150)

            // 重建路径：同一个端口再连一次（真实场景里该端口可能处于 TIME_WAIT）。
            val second = StunTcpSession.connect(target, connectTimeoutMs = 2000, reuseLocalPort = wanted)
            try {
                assertEquals("重建时应能再次复用同一本地端口", wanted, second.localPort)
            } finally {
                second.close()
            }
        }
    }

    @Test
    fun `不指定本地端口时 仍能正常建立连接`() {
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { server ->
            val session = StunTcpSession.connect(StunServer("127.0.0.1", server.localPort))
            try {
                assertTrue("内核应分配一个有效端口", session.localPort > 0)
                assertFalse(session.isClosed)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `端口保不住时 连接仍然成功（不得因复用失败让隧道起不来）`() {
        // 拿一个「已经有人在听」的端口当目标本地端口：这种端口一定保不住。
        // 关键不变量是**不抛异常**并且照常连通——地址稳定只能尽力而为。
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { server ->
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { occupied ->
                val session = StunTcpSession.connect(
                    StunServer("127.0.0.1", server.localPort),
                    connectTimeoutMs = 2000,
                    reuseLocalPort = occupied.localPort
                )
                try {
                    assertTrue("无论如何都应分到一个有效端口", session.localPort > 0)
                    assertFalse(session.isClosed)
                } finally {
                    session.close()
                }
            }
        }
    }

    @Test
    fun `内置服务器列表不含 TURN 服务器`() {
        // TURN（turn.* 或 global.turn.*）会把长连接当作未认证会话管理并主动关闭，
        // 与「探测连接兼作保活长连接」的设计冲突。
        val suspicious = StunClient.DEFAULT_SERVERS.filter { it.startsWith("turn.") || it.contains(".turn.") }
        assertTrue("默认列表不应包含 TURN 服务器，当前：$suspicious", suspicious.isEmpty())
    }
}
