package com.mineserve.mobile.server.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探测失败分类与结论优先级的单测。
 *
 * ## 为什么值得单独测
 *
 * 「失败」在这一层会被折叠成一个分类，界面据此决定说什么话、要不要重试。
 * 分类错了有两个具体后果：
 * 1. 把「运营商给了 CGNAT 地址」误判成「服务器连不上」→ 用户去换服务器列表，
 *    换一整天也没用，真正该做的是改用 frp。
 * 2. 把确定性失败（CGNAT）当成可重试失败 → 白等 3 轮 × 15 秒。
 *
 * 不 import 任何 `android.*`：本模块未开 `isReturnDefaultValues`。
 */
class StunDiagnosticsTest {

    private val backend = StunTunnelBackend()

    // ── 失败分类 ────────────────────────────────────────────────

    @Test
    fun `等待响应超时归为「服务器无响应」`() {
        // 只支持 UDP 的服务器就是这个表现：TCP 连得上，但永远不回包。
        assertEquals(
            StunFailCategory.NoResponse,
            backend.classifyProbeFailure(StunException("等待 STUN 响应超时"))
        )
    }

    @Test
    fun `服务器返回 ERROR-CODE 归为「被拒绝」`() {
        assertEquals(
            StunFailCategory.ServerRejected,
            backend.classifyProbeFailure(StunException("STUN 服务器返回错误：401 Unauthorized"))
        )
    }

    @Test
    fun `响应里没有映射地址归为「无映射」`() {
        assertEquals(
            StunFailCategory.NoMapping,
            backend.classifyProbeFailure(StunException("响应中没有可用的映射地址"))
        )
    }

    @Test
    fun `连接层异常归为「不可达」`() {
        assertEquals(
            StunFailCategory.Unreachable,
            backend.classifyProbeFailure(java.net.ConnectException("Connection refused"))
        )
        assertEquals(
            StunFailCategory.Unreachable,
            backend.classifyProbeFailure(StunException("stun.example.com"))
        )
    }

    // ── 结论优先级 ──────────────────────────────────────────────

    @Test
    fun `非公网地址的结论压过其它所有失败`() {
        // 只要有一台服务器明确反馈「你本机地址是非公网」，那就是最有价值的结论：
        // 问题在运营商，换服务器列表没用。
        assertEquals(
            StunFailCategory.NonPublicAddress,
            backend.dominantFailure(
                listOf(
                    StunFailCategory.Unreachable,
                    StunFailCategory.NoResponse,
                    StunFailCategory.NonPublicAddress
                )
            )
        )
    }

    @Test
    fun `没有非公网结论时依次退到被拒 无映射 无响应 不可达`() {
        assertEquals(
            StunFailCategory.ServerRejected,
            backend.dominantFailure(listOf(StunFailCategory.Unreachable, StunFailCategory.ServerRejected))
        )
        assertEquals(
            StunFailCategory.NoMapping,
            backend.dominantFailure(listOf(StunFailCategory.Unreachable, StunFailCategory.NoMapping))
        )
        assertEquals(
            StunFailCategory.NoResponse,
            backend.dominantFailure(listOf(StunFailCategory.Unreachable, StunFailCategory.NoResponse))
        )
        assertEquals(
            StunFailCategory.Unreachable,
            backend.dominantFailure(listOf(StunFailCategory.Unreachable))
        )
    }

    @Test
    fun `没有任何分类时退到「不可达」而不是崩溃`() {
        assertEquals(StunFailCategory.Unreachable, backend.dominantFailure(emptyList()))
    }

    // ── 结论文案 ────────────────────────────────────────────────

    @Test
    fun `CGNAT 结论里带上具体的地址，用户能拿去搜索`() {
        val message = backend.failureMessageFor(
            StunFailCategory.NonPublicAddress,
            StunDiagnostics(nonPublicAddress = "100.64.3.7:51234（运营商 CGNAT 地址 100.64.0.0/10）")
        )
        assertTrue("应包含拿到的地址，实际：$message", message.contains("100.64.3.7:51234"))
        assertTrue("应引导改用 frp，实际：$message", message.contains("frp"))
    }

    @Test
    fun `无响应结论提示更换服务器而不是让用户怀疑网络`() {
        val message = backend.failureMessageFor(StunFailCategory.NoResponse, StunDiagnostics())
        assertTrue("实际：$message", message.contains("UDP"))
        assertTrue("实际：$message", message.contains("更换"))
    }

    // ── 诊断快照 ────────────────────────────────────────────────

    @Test
    fun `默认诊断是「未探测」且不宣称有结果`() {
        val diag = StunDiagnostics()
        assertEquals(StunVerdict.Unknown, diag.verdict)
        assertEquals(StunFailCategory.None, diag.failCategory)
        assertEquals(false, diag.hasResult)
        assertTrue(diag.publicAddress.isEmpty())
        assertTrue(diag.portStable)
    }
}
