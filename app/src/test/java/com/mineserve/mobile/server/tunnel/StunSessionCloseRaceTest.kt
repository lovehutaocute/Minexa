package com.mineserve.mobile.server.tunnel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * 回归测试：**读线程不得让任何异常逃出去**。
 *
 * 背景（2026-10-16 真机崩溃）：
 * ```
 * FATAL EXCEPTION: stun-reader
 * java.net.SocketException: Socket is closed
 *   at java.net.Socket.getInputStream(Socket.java:1000)
 *   at StunTcpSession.readLoop(StunClient.kt:229)
 * ```
 * 并探测（S0.3）上线后，多台 STUN 服务器几乎同时握手成功，没抢到先手的那台会被
 * 立刻 `close()`——而它的读线程可能还没被调度到 `socket.getInputStream()` 这一行。
 * 对已关闭的 socket 取流会抛 `SocketException`，原先该语句在 try **之外**，
 * 异常逃出线程后触发 Android 默认未捕获异常处理器，整个 App 被杀。
 *
 * 本测试用一个本地 `ServerSocket` 复现「连接建立后立刻关闭」的时序，循环多轮，
 * 确认没有任何异常走默认未捕获异常处理器。
 *
 * ⚠️ 说明：真机上的竞态窗口比 JVM 更宽（线程调度粒度不同），所以本测试**通过
 * 不等于真机一定不崩**，它是防止「getInputStream 又跑回 try 外面」这类改动的
 * 守门测试，真机验收仍需按测试点清单跑一次启停 + 多服务器竞争场景。
 */
class StunSessionCloseRaceTest {

    @Test
    fun `连接建立后立刻关闭 异常不会逃出读线程`() {
        val escaped = Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped.add(e) }

        try {
            ServerSocket(0, 64, InetAddress.getByName("127.0.0.1")).use { server ->
                val port = server.localPort
                // 立刻关掉被接受的对端连接：模拟「STUN 服务器」这一侧不参与。
                val acceptor = thread(isDaemon = true) {
                    while (!server.isClosed) {
                        runCatching { server.accept().close() }
                    }
                }

                repeat(200) {
                    val session = StunTcpSession.connect(
                        StunServer("127.0.0.1", port),
                        connectTimeoutMs = 2000
                    )
                    // 关键时序：拿到会话后立刻关闭，读线程很可能尚未真正开跑。
                    session.close()
                }

                // 给读线程时间把该跑的代码跑完（崩溃发生在这一小段里）。
                val deadline = System.currentTimeMillis() + 2000
                while (escaped.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                acceptor.interrupt()
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertTrue(
            "读线程有异常逃逸（会导致真机闪退）：${escaped.firstOrNull()}",
            escaped.isEmpty()
        )
    }

    @Test
    fun `重复 close 幂等 不会抛异常`() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            val session = StunTcpSession.connect(
                StunServer("127.0.0.1", server.localPort),
                connectTimeoutMs = 2000
            )
            session.close()
            session.close()
            session.close()
            assertTrue("关闭后应报告 isClosed", session.isClosed)
        }
    }
}
