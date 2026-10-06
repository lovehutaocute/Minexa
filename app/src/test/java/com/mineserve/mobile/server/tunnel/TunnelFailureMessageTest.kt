package com.mineserve.mobile.server.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 钉住隧道启动失败的**可读性**回归。
 *
 * 背景：1.2.8 的启动路径在 `FrpcBackend.start()` 里对 SakuraFrp / MeFrp 做了
 * 同步 HTTP 请求，却漏了 `withContext(Dispatchers.IO)`；调用方
 * `McViewModel.startTunnel()` 用的是 `viewModelScope.launch`（默认 Main），
 * 于是网络请求落在主线程，被 Android 抛 `NetworkOnMainThreadException`。
 *
 * 该异常在 AOSP 里 **message 就是 null**，而当时的代码写的是
 * `"启动失败：${e.message}"` 与 `e.message ?: "未知错误"`，
 * 用户看到的日志因此是「启动失败：null」，完全无法定位 —— 这条信息缺口
 * 直接导致排查方向走错（误判为服务商 / frpc 引擎 / 配置问题）。
 *
 * 修复有两点：
 * 1. `FrpcBackend.start()` 补 `withContext(Dispatchers.IO)`。调度器无法在纯 JVM
 *    单测里断言，靠编译期与代码评审保证；相关调用链见该类注释。
 * 2. 失败原因在 message 为空时回退到**异常类名**，绝不再产出 "null"。
 *
 * 这里测的是第 2 点的取值逻辑本身。不 import 任何 `android.*` 类：
 * 本模块未开 `isReturnDefaultValues`，未 mock 的 Android 类型会抛
 * "not mocked" 让测试假失败。
 */
class TunnelFailureMessageTest {

    /**
     * 与 FrpcBackend.onFailure / TunnelManager.start 中 `reason` 的取值逻辑一致。
     * 两处实现如需改动，这里要同步改 —— 所以断言写成「绝不含 null」这种
     * 与实现无关的语义约束优先。
     */
    private fun reasonOf(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName

    @Test
    fun `message 为 null 时回退到异常类名，不产出 null 字面量`() {
        // message 为 null 的异常 —— NetworkOnMainThreadException 就是这种形态。
        // 用 NullPointerException(null) 造出同样的「无 message」状态，
        // 且带一个稳定的类名可供断言。
        val e = NullPointerException(null as String?)
        assertEquals("前提：message 为 null", null, e.message)

        val reason = reasonOf(e)
        assertFalse("绝不能把 null 印给用户", reason == "null" || reason.contains("null"))
        assertEquals("NullPointerException", reason)
    }

    @Test
    fun `message 为空白串时同样回退到类名`() {
        val e = RuntimeException("   ")
        assertEquals("RuntimeException", reasonOf(e))
    }

    @Test
    fun `有正常 message 时原样使用，不覆盖业务提示`() {
        val e = IllegalArgumentException("请先在 sakurafrp.com 用户面板获取访问密钥并填写")
        assertEquals("请先在 sakurafrp.com 用户面板获取访问密钥并填写", reasonOf(e))
    }
}
