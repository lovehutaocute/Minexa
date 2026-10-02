package com.mineserve.mobile.server.tunnel.frpc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隧道就绪判定的回归测试。
 *
 * ## 背景：日志已显示启动成功，界面却一直卡在「启动中」
 *
 * [FrpcProcessManager] 靠输出内容判定隧道是否可用。早先只认一条
 * `login to server success`（字面量、大小写敏感），换个 frpc 版本或换个平台的
 * 输出顺序就再也命不中，界面永远停在 Starting。
 *
 * 下面用例里的日志文案**取自 frpc 二进制内的实际字符串**（0.61.2 与 0.70.1 一致）：
 *
 * ```
 * login to server success, get run id [%s]
 * proxy added: %s
 * [%s] start proxy success
 * ```
 *
 * 这三条对应三种成功阶段，命中任一即应切到 Running。
 */
class FrpcReadyPatternTest {

    private val pattern = FrpcProcessManager.TUNNEL_READY_PATTERN

    /** 模拟一行带时间戳与级别的真实 frpc 输出。 */
    private fun line(body: String) = "2026-10-02 09:02:12.274 [I] [client/service.go:311] $body"

    // ── 三个成功标志 ───────────────────────────────────────────

    @Test
    fun `login to server success 判为就绪`() {
        assertTrue(pattern.containsMatchIn(line("login to server success, get run id [abc123]")))
    }

    @Test
    fun `proxy added 判为就绪`() {
        assertTrue(pattern.containsMatchIn(line("proxy added: mc-tunnel")))
    }

    @Test
    fun `start proxy success 判为就绪`() {
        // 这是用户日志里出现的那一行 —— 修复前它不被识别，所以界面卡住。
        assertTrue(pattern.containsMatchIn(line("[mc-tunnel] start proxy success")))
    }

    // ── 不该误判 ───────────────────────────────────────────────

    @Test
    fun `连接失败不判为就绪`() {
        assertFalse(
            pattern.containsMatchIn(
                line("connect to server error: dial tcp 60.182.113.248:8120: connect: connection refused")
            )
        )
    }

    @Test
    fun `登录失败不判为就绪`() {
        // 含 "login to the server failed"，绝不能因为含 "login to" 就误判成功。
        assertFalse(
            pattern.containsMatchIn(
                line("login to the server failed: dial tcp: connection refused")
            )
        )
    }

    @Test
    fun `正在连接不判为就绪`() {
        assertFalse(pattern.containsMatchIn(line("try to connect to server...")))
        assertFalse(pattern.containsMatchIn(line("start new connection to server")))
    }

    @Test
    fun `心跳日志不判为就绪`() {
        // 进程活着、心跳照发，但没登录成功 —— 不能当成功。
        assertFalse(pattern.containsMatchIn(line("send heartbeat to server")))
    }

    // ── 大小写容错 ─────────────────────────────────────────────

    @Test
    fun `大小写不敏感`() {
        // 不同 frpc 构建的日志级别前缀/文案大小写可能不同，不该因此卡住。
        assertTrue(pattern.containsMatchIn("Login To Server Success, get run id [x]"))
        assertTrue(pattern.containsMatchIn("[mc] Start Proxy Success"))
    }

    @Test
    fun `带 ANSI 颜色码的行在剔除转义后仍能命中`() {
        // 引擎输出常带颜色码；[FrpcProcessManager] 先用 ANSI_PATTERN 剔除再判定。
        val raw = "\u001B[32m2026-10-02 09:02:12 [I] start proxy success\u001B[0m"
        val clean = Regex("\\x1B\\[[0-?]*[ -/]*[@-~]").replace(raw, "")
        assertTrue(pattern.containsMatchIn(clean))
    }
}
