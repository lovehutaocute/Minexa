package com.mineserve.mobile.server.tunnel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 回归测试：**会话自愈的日志节奏**。
 *
 * 背景（2026-10-17 真机日志，94 行 / 10 分钟）：
 * `stun.sonetel.com` **每回应一次 Binding Request 就 RST 这条 TCP**，于是每个保活
 * 周期都会跑一遍「保活失败 → 同端口重连 → 复用成功」。功能上完全正确（公网端口
 * 全程未变、外部查询始终在线），但每轮 3 行日志把面板刷成了滚动条。
 *
 * 修法是三件事：
 * 1. 轻量自愈路径上的「连接已断开 / 探测失败 / 端口复用成功」一律不播报——它们
 *    描述的是同一件事的三个瞬间，中间没有任何用户可采取的动作；对外只由
 *    「连接已恢复」那一行代表，且该行按 [shouldAnnounceRenewal] 的节奏节流。
 * 2. 连接已被读线程判死时**不再等 3 秒原地重试**，立刻去同端口重连（映射越早
 *    接回越可能保住同一个外部端口）。
 * 3. 失败路径（`原地重连失败` / `轻量重连未成功` / `判定映射失效`）全部保留原样，
 *    所以静默不会掩盖真故障。
 *
 * 第 1 条里唯一可单测的就是节流边界，直接断言；第 2 条依赖「对端 RST 后 isClosed
 * 会变 true」这个前提，单独用本地套接字验一遍。
 */
class StunRenewalQuietTest {

    @Test
    fun `前三次重连逐条播报`() {
        assertTrue("第 1 次必须看得见", shouldAnnounceRenewal(1))
        assertTrue("第 2 次必须看得见", shouldAnnounceRenewal(2))
        assertTrue("第 3 次必须看得见", shouldAnnounceRenewal(3))
    }

    @Test
    fun `第四第五次静默 之后每六次一条`() {
        assertFalse("第 4 次应静默", shouldAnnounceRenewal(4))
        assertFalse("第 5 次应静默", shouldAnnounceRenewal(5))
        assertTrue("第 6 次应播报（约 2 分钟一次心跳）", shouldAnnounceRenewal(6))
        assertFalse("第 7 次应静默", shouldAnnounceRenewal(7))
        assertTrue("第 12 次应播报", shouldAnnounceRenewal(12))
        assertTrue("第 18 次应播报", shouldAnnounceRenewal(18))
        assertFalse("第 19 次应静默", shouldAnnounceRenewal(19))
    }

    /**
     * 一小时按 20 秒保活间隔算 = 180 次重连。若逐次播报每轮 3 行就是 540 行日志；
     * 节流后应当降到 30 行量级（3 + 180/6）。这条断言把「降噪」量化住，
     * 免得以后有人把阈值改回「每次都播」还不自知。
     */
    @Test
    fun `按一小时 180 次重连估算 播报次数不超过 40`() {
        val announced = (1..180).count { shouldAnnounceRenewal(it) }
        assertTrue("一小时播报次数过多：$announced（预期 ≤ 40）", announced <= 40)
        assertTrue("一小时一条都不播会让人以为卡死：$announced", announced >= 20)
    }
}

/**
 * 守「keepAlive 快速路径」的前提：**对端 RST 之后，会话必须能被读出「已死」**。
 *
 * 若这条不成立（例如 socket 半关状态下 `alive` 不复位），新加的快速路径就永远进不去，
 * 于是每个保活周期仍会白等 3 秒、并把「视为偶发丢包」打进日志——退化成修复前的样子。
 */
class StunSessionDeathSignalTest {

    @Test
    fun `对端 RST 后会话被判死 且 requestMapping 立刻抛异常`() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = CountDownLatch(1)
            val peer = thread(isDaemon = true) {
                runCatching {
                    val socket = server.accept()
                    accepted.countDown()
                    Thread.sleep(100)
                    // soLinger=true + 0 秒 = 关闭时直接发 RST，而不是走四次挥手；
                    // 这才是 stun.sonetel.com 的真实行为（日志里的 Connection reset）。
                    socket.setSoLinger(true, 0)
                    socket.close()
                }
            }

            val session = StunTcpSession.connect(
                StunServer("127.0.0.1", server.localPort),
                connectTimeoutMs = 2000
            )
            try {
                assertTrue("服务端应已接受连接", accepted.await(3, TimeUnit.SECONDS))
                peer.join(2000)

                val deadline = System.currentTimeMillis() + 3000
                while (!session.isClosed && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                assertTrue(
                    "对端 RST 后读线程应把会话判死（keepAlive 快速路径依赖它）",
                    session.isClosed
                )

                val startedAt = System.currentTimeMillis()
                val thrown = runCatching { session.requestMapping(2000) }.exceptionOrNull()
                val elapsed = System.currentTimeMillis() - startedAt
                assertNotNull("连接已死时 requestMapping 必须抛异常", thrown)
                assertTrue("应立刻失败而不是等满超时，实际 ${elapsed}ms", elapsed < 1000)
            } finally {
                session.close()
            }
        }
    }
}
