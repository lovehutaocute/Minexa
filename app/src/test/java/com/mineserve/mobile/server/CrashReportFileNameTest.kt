package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 回归测试：MC 原生崩溃报告文件名的时间戳解析。
 *
 * 旧实现是 `substringBefore("-")`，而时间戳本身是 `2024-01-02_12.34.56`，
 * 于是只截到 `"2024"`；SimpleDateFormat 按 `yyyy-MM-dd_HH.mm.ss` 解析 `"2024"` 必抛
 * ParseException（`Unparseable date: "2024"`）。该异常从 `maxByOrNull` 的 selector 里
 * 冒出去，被 captureCrash 的外层 catch 吞成 null，后果是：
 * **只要目录里存在原生崩溃报告，应用自己的崩溃报告就完全不会生成**。
 *
 * 所以这里既钉住「合法名字要解析出完整时间戳」，也钉住「畸形名字必须返回 null
 * 而不是抛异常」——两者缺一，同样的故障就会复发。
 */
class CrashReportFileNameTest {

    private fun expected(ts: String): Long =
        SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US).parse(ts)!!.time

    @Test
    fun `带 -server 后缀的原生报告名能解析出完整时间戳`() {
        val actual = CrashReportManager.parseNativeFilename("crash-2024-01-02_12.34.56-server.txt")

        assertNotNull("原生崩溃报告名必须能解析（旧实现这里返回 null）", actual)
        assertEquals(expected("2024-01-02_12.34.56"), actual)
    }

    @Test
    fun `不带 -server 后缀的名字同样能解析`() {
        assertEquals(
            expected("2024-01-02_12.34.56"),
            CrashReportManager.parseNativeFilename("crash-2024-01-02_12.34.56.txt")
        )
    }

    @Test
    fun `解析出的是完整时间戳而不是年份`() {
        // 直接钉住旧 bug：把时间戳截成 "2024" 会让时间落在 2024-01-01，
        // 于是「哪份报告最新」的排序会全错——最新的报告会被排到最后。
        val jan = CrashReportManager.parseNativeFilename("crash-2024-01-02_12.34.56-server.txt")
        val mar = CrashReportManager.parseNativeFilename("crash-2024-03-04_05.06.07-server.txt")

        assertNotNull(jan)
        assertNotNull(mar)
        assertTrue("3 月的报告必须比 1 月的新", mar!! > jan!!)
        assertTrue("间隔应超过 50 天", mar - jan > 50L * 24 * 3600 * 1000)
    }

    @Test
    fun `畸形名字一律返回 null 且不抛异常`() {
        // 合约：解析失败必须返回 null。抛异常会从 maxByOrNull 的 selector
        // 冒出去，毁掉整次崩溃报告生成——这正是旧 bug 的传播路径。
        listOf(
            "crash-2024.txt",                  // 只有年份：旧实现就死在这里
            "crash-abc.txt",
            "crash-.txt",
            "crash-2024-01-02.txt",            // 缺时分秒
            "crash-2024-01-02_12.34.56.log",   // 后缀不对
            "notacrash.txt",
            "crash-2024-01-02_12.34.56-server",
            ""
        ).forEach { name ->
            assertNull("不该解析出时间: '$name'", CrashReportManager.parseNativeFilename(name))
        }
    }

    @Test
    fun `非崩溃报告文件名被直接排除`() {
        assertNull(CrashReportManager.parseNativeFilename("latest.log"))
        assertNull(CrashReportManager.parseNativeFilename("2024-01-02_12.34.56-server.txt"))
    }
}
