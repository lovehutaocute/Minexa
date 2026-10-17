package com.mineserve.mobile.server.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「STUN 长连接被对端掐断后只换连接、不踢玩家」的守门测试。
 *
 * ## 为什么要守这条
 *
 * 2026-10-16 实测 `stun.telnyx.com` **每回应一次 Binding Request 就主动关掉 TCP
 * 连接**。旧实现在「连接断开」时走完整重建：`teardown()` 关监听 + 关全部玩家连接
 * + 清空已公布地址，于是几秒一轮地踢人、界面反复闪「启动中」。
 *
 * 修法依赖一条内核语义：[ServerSocket] 关闭**不会**连带关闭已经 `accept()` 出来的
 * 连接。只要这条成立，轻量重连就只是在「新建入站」上留 0.1–0.5 秒窗口，在位玩家
 * 完全无感。本文件把这条语义钉死——它在 Windows/Android 上一致，可跨平台验证。
 */
class StunSessionRenewalTest {

    @Test
    fun `关闭监听套接字不影响已接受的连接`() {
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port = listener.localPort
        val responded = AtomicBoolean(false)
        // accept 是否已完成必须显式等：Windows 上关掉监听会 RST 掉仍在 backlog 里、
        // 尚未 accept 的连接。生产路径里 accept 线程是一直在跑的，不存在这个窗口，
        // 但测试里必须把顺序钉死，否则验的是「backlog 里的连接」而不是「已接受的连接」。
        val accepted = CountDownLatch(1)
        val serverThread = Thread {
            runCatching {
                listener.accept().use { conn ->
                    accepted.countDown()
                    val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
                    val line = reader.readLine()
                    PrintWriter(conn.getOutputStream(), true).println("echo:$line")
                    responded.set(true)
                }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val client = Socket("127.0.0.1", port)
        try {
            assertTrue("accept 应已完成", accepted.await(3, TimeUnit.SECONDS))

            // 关键动作：把监听套接字关掉（轻量重连的第一步就是这么做的）。
            listener.close()

            // 已建立的连接必须仍然可用——双向读写都要通。
            PrintWriter(client.getOutputStream(), true).println("hello")
            val reply = BufferedReader(InputStreamReader(client.getInputStream())).readLine()
            assertEquals("关闭监听后连接应仍可读写", "echo:hello", reply)
            serverThread.join(2000)
            assertTrue("服务端线程应正常处理完", responded.get())
        } finally {
            runCatching { client.close() }
            runCatching { listener.close() }
        }
    }

    @Test
    fun `监听套接字关闭后 accept 立刻抛异常（用于让旧接受线程退出）`() {
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val thrown = AtomicBoolean(false)
        val acceptThread = Thread {
            runCatching { listener.accept() }.onFailure { thrown.set(true) }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        Thread.sleep(100)
        listener.close()
        acceptThread.join(2000)

        assertTrue("关闭监听后阻塞中的 accept 必须抛异常返回，否则旧接受线程无法退出", thrown.get())
    }
}
