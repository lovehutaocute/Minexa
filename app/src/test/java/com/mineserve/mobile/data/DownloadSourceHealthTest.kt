package com.mineserve.mobile.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载源健康度的判据。
 *
 * 这些用例守的是一个很容易「优化坏」的点：降权必须**只影响失败过的源**，
 * 且必须在时间上自然过期。写错了两侧的代价都很实在 ——
 * 太激进会把唯一可用的源排到最后，太保守则等于没做。
 */
class DownloadSourceHealthTest {

    private val now = 1_800_000_000_000L
    private val min = 60_000L

    @After
    fun tearDown() {
        DownloadSourceHealth.resetForTest()
    }

    @Test
    fun `没有失败记录时惩罚分为零`() {
        assertEquals(0L, DownloadSourceHealth.penaltyOf(null, now))
    }

    @Test
    fun `惩罚分随时间衰减分档`() {
        fun p(ageMs: Long) = DownloadSourceHealth.penaltyOf(
            DownloadSourceHealth.Entry(failures = 1, lastFailureMs = now - ageMs),
            now
        )
        assertTrue("10 分钟内应最高档", p(1 * min) > p(30 * min))
        assertTrue("1 小时内应高于 1 小时后", p(30 * min) > p(3 * 60 * min))
        assertTrue("1 天后应基本归零", p(3 * 60 * min) > p(2 * 24 * 60 * min))
        assertEquals("一天前的失败不再影响排序", 0L, p(2 * 24 * 60 * min))
    }

    @Test
    fun `连续失败次数加权但封顶`() {
        val one = DownloadSourceHealth.Entry(1, now)
        val five = DownloadSourceHealth.Entry(5, now)
        val fifty = DownloadSourceHealth.Entry(50, now)
        assertTrue(DownloadSourceHealth.penaltyOf(five, now) > DownloadSourceHealth.penaltyOf(one, now))
        assertEquals(
            "第 5 次之后的失败不再加权，避免永久沉底",
            DownloadSourceHealth.penaltyOf(five, now),
            DownloadSourceHealth.penaltyOf(fifty, now)
        )
    }

    @Test
    fun `未来时间戳不会算出负分`() {
        // 系统时钟回拨会留下「未来」的 lastFailureMs；不能因此把这个源顶到最前面。
        val entry = DownloadSourceHealth.Entry(1, now + 10 * min)
        assertTrue(DownloadSourceHealth.penaltyOf(entry, now) > 0)
    }

    @Test
    fun `健康时保持调用方原始顺序`() {
        val urls = listOf("a", "b", "c")
        assertEquals(urls, DownloadSourceHealth.rank(urls, now))
    }

    @Test
    fun `近期失败的源被挪到后面`() {
        DownloadSourceHealth.recordFailure("a", now)
        assertEquals(listOf("b", "a"), DownloadSourceHealth.rank(listOf("a", "b"), now))
    }

    @Test
    fun `同分源保持原始优先级`() {
        // b、c 都失败过且档位相同：必须按原顺序（b 在 c 前），不能被打乱。
        DownloadSourceHealth.recordFailure("b", now)
        DownloadSourceHealth.recordFailure("c", now)
        assertEquals(
            listOf("a", "b", "c"),
            DownloadSourceHealth.rank(listOf("a", "b", "c"), now)
        )
    }

    @Test
    fun `成功一次即恢复原顺序`() {
        DownloadSourceHealth.recordFailure("a", now)
        assertEquals(listOf("b", "a"), DownloadSourceHealth.rank(listOf("a", "b"), now))

        DownloadSourceHealth.recordSuccess("a")
        assertEquals(
            "成功说明该源此刻可用，必须立刻恢复优先级",
            listOf("a", "b"),
            DownloadSourceHealth.rank(listOf("a", "b"), now)
        )
    }

    @Test
    fun `一天前的失败不再影响排序`() {
        DownloadSourceHealth.recordFailure("a", now - 2 * 24 * 60 * min)
        assertEquals(listOf("a", "b"), DownloadSourceHealth.rank(listOf("a", "b"), now))
    }
}
