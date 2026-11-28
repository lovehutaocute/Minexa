package com.mineserve.mobile.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * installer 失败归因（`explainInstallerFailure`）。
 *
 * 这些字符串全部来自真实用户日志：以前统一报 `exit=1`，用户只能反复重试。
 * 归因的价值在于「一句话指出该去修什么」，所以断言只压在「必须给出正确方向」上，
 * 不锁具体措辞 —— 文案以后可以改，方向不能错。
 */
class InstallerFailureExplanationTest {

    private fun explain(label: String = "NeoForge", code: Int = 1, tail: String) =
        explainInstallerFailure(label, code, tail)

    @Test
    fun `证书库为空时指向 cacerts 修复`() {
        val tail = """
            java.lang.RuntimeException: java.security.InvalidAlgorithmParameterException: 
            the trustAnchors parameter must be non-empty
                at java.base/sun.security.ssl.SSLSocketImpl.startHandshake(SSLSocketImpl.java:463)
        """.trimIndent()
        val msg = explain(tail = tail)
        assertTrue(msg, msg.contains("证书库"))
        // 带原始信息，用户/排查者不必再翻控制台
        assertTrue(msg, msg.contains("trustAnchors parameter must be non-empty"))
    }

    @Test
    fun `Java 版本不匹配时指向切换运行环境`() {
        val tail = "java.lang.UnsupportedClassVersionError: " +
            "net/neoforged/installer/Main has been compiled by a more recent version " +
            "(class file version 65.0), this version only recognizes up to 61.0"
        val msg = explain(tail = tail)
        assertTrue(msg, msg.contains("Java 版本"))
    }

    @Test
    fun `连接被墙时指向网络`() {
        val tail = "java.net.SocketTimeoutException: Connect timed out -> maven.neoforged.net"
        val msg = explain(tail = tail)
        assertTrue(msg, msg.contains("无法连接"))
    }

    @Test
    fun `依赖库缺失时指向网络重试`() {
        val tail = "Caused by: java.lang.NoClassDefFoundError: cpw/mods/securejarhandler/ModuleFinderFactory"
        val msg = explain(tail = tail)
        assertTrue(msg, msg.contains("依赖库缺失"))
    }

    @Test
    fun `超时退出码 124 单独处理`() {
        val msg = explain(code = 124, tail = "")
        assertTrue(msg, msg.contains("超时"))
        assertTrue(msg, msg.contains("15 分钟"))
    }

    @Test
    fun `命不中规则时如实报退出码而不是编原因`() {
        val msg = explain(code = 3, tail = "Something totally unexpected happened")
        assertTrue(msg, msg.contains("exit=3"))
        assertFalse(msg, msg.contains("证书库"))
        assertFalse(msg, msg.contains("依赖库"))
    }

    @Test
    fun `空尾部不产生悬空括号`() {
        val msg = explain(tail = "")
        assertFalse(msg, msg.contains("原始信息"))
    }

    @Test
    fun `HTTP 层 404 指向源不可用`() {
        val tail = "java.io.IOException: Server returned HTTP response code: 404 " +
            "for URL: https://maven.neoforged.net/releases/net/neoforged/neoforge/x.jar"
        val msg = explain(tail = tail)
        assertTrue(msg, msg.contains("4xx"))
    }

    @Test
    fun `裸 not found 不再被误判成源返回 4xx`() {
        // 真实的依赖库缺失日志里常出现 "not found"，以前裸关键词会把方向指成
        // 「源返回 4xx，请换网络」，而真正该做的是把库补齐。
        val tail = "Caused by: java.lang.IllegalStateException: module handler not found"
        val msg = explain(tail = tail)
        assertFalse(msg, msg.contains("4xx"))
    }

    @Test
    fun `超时文案报真实时长而不是固定 15 分钟`() {
        assertTrue(explain(code = 124, tail = ""), explain(code = 124, tail = "").contains("15 分钟"))
        assertTrue(
            explainInstallerFailure("NeoForge", 124, "", timeoutMinutes = 20).contains("20 分钟")
        )
    }
}
