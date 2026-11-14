package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 关键日志抽取：块识别、行号正确性、预算上限。 */
class KeyLogExtractorTest {

    private val log = listOf(
        "[12:00:00] [main/INFO]: Loading properties",          // 1
        "[12:00:01] [main/ERROR]: Exception in thread \"main\"", // 2 块起点
        "java.lang.RuntimeException: boom",                      // 3
        "\tat a.B.c(B.java:1)",                                  // 4
        "\tat a.B.d(B.java:2)",                                  // 5
        "Caused by: java.io.IOException: disk",                  // 6 新块起点
        "\tat a.C.e(C.java:9)",                                  // 7
        "[12:00:02] [main/WARN]: Could not resolve host",         // 8 严重单行
        "[12:00:03] [main/INFO]: Stopping server",               // 9
        "[12:00:04] [main/INFO]: Bye"                            // 10 尾部
    )

    @Test
    fun `异常块带行号且包含完整栈`() {
        val segments = KeyLogExtractor.extract(log)
        val exceptions = segments.filter { it.reason == KeyLogExtractor.Reason.EXCEPTION }
        assertTrue("应至少抽到两段异常（异常本体 + Caused by）", exceptions.size >= 2)
        val first = exceptions.first()
        assertEquals(2, first.startLine)
        assertTrue("栈行应被保留：${first.lines}", first.lines.any { it.contains("a.B.c") })
        assertTrue(
            "Caused by 链应完整保留：${exceptions.flatMap { it.lines }}",
            exceptions.any { seg -> seg.lines.any { it.contains("Caused by") } }
        )
    }

    @Test
    fun `严重单行会单独成段`() {
        val segments = KeyLogExtractor.extract(log)
        assertTrue(
            "应命中 Could not resolve host",
            segments.any { it.reason == KeyLogExtractor.Reason.SEVERE && it.lines.any { l -> l.contains("Could not resolve") } }
        )
    }

    @Test
    fun `尾部上下文始终存在且不与异常块重复`() {
        val segments = KeyLogExtractor.extract(log)
        val tail = segments.firstOrNull { it.reason == KeyLogExtractor.Reason.CRASH_TAIL }
        assertTrue("应有尾部上下文", tail != null)
        val covered = segments.filter { it.reason != KeyLogExtractor.Reason.CRASH_TAIL }
            .flatMap { it.startLine..it.endLine }
        assertTrue(
            "尾部行号不得与异常块重叠：${tail!!.startLine}-${tail.endLine}",
            tail.startLine !in covered
        )
    }

    @Test
    fun `Watchdog 转储按 Watchdog 归类`() {
        val watchdog = listOf(
            "Paper Watchdog Thread/ERROR: The server has not responded for 60 seconds",
            "Server thread dump (Look for locks):",
            "\tat net.minecraft.server.MinecraftServer.tick(MinecraftServer.java:1)"
        )
        val segments = KeyLogExtractor.extract(watchdog)
        // 「Server thread dump」本身也是块起点：头部一行 + 转储体可能被拆成两段，
        // 只要存在一段 Watchdog 且带上了栈体即可。
        val watchdogSegments = segments.filter { it.reason == KeyLogExtractor.Reason.WATCHDOG }
        assertTrue("应识别 Watchdog 块：$segments", watchdogSegments.isNotEmpty())
        assertTrue(
            "Watchdog 段应保留转储内容：${watchdogSegments.map { it.lines }}",
            watchdogSegments.any { it.lines.size >= 2 }
        )
    }

    @Test
    fun `行数预算生效`() {
        val huge = buildString {
            repeat(2000) { appendLine("  at a.B.c(B.java:$it)") }
        }.lines()
        val segments = KeyLogExtractor.extract(listOf("Exception in thread \"main\" java.lang.Error: x") + huge, maxLines = 100)
        val total = segments.sumOf { it.lineCount }
        assertTrue("应抽到内容", total > 0)
        assertTrue("总行数应受限（实际 $total）", total <= 200)
    }

    @Test
    fun `空日志返回空结果`() {
        assertTrue(KeyLogExtractor.extract(emptyList()).isEmpty())
    }
}
