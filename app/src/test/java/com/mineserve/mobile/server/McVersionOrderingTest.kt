package com.mineserve.mobile.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MC 版本列表的排序语义。
 *
 * 回归背景（用户报「偶尔最高只显示到 1.21.4」）：
 * `sortedDescending()` 是**字符串**排序，会把 `1.21.4` 排在 `1.21.10` 前面，
 * 于是下拉列表顶部显示的不是真正的最新版；配合 `.take(n)` 还会把新版本截掉。
 * 所有版本列表必须改用 [mcVersionComparator]（按数字段逐级比较）。
 */
class McVersionOrderingTest {

    @Test
    fun `semantic order puts 1_21_10 above 1_21_4`() {
        // 字符串排序会给出 ["1.21.4", "1.21.10"]，语义排序必须反过来。
        val sorted = listOf("1.21.4", "1.21.10").sortedWith(mcVersionComparator)

        assertEquals(listOf("1.21.10", "1.21.4"), sorted)
    }

    @Test
    fun `semantic order distingushes 1_21_1 and 1_21_11`() {
        // 1.21.11 > 1.21.1（按段比较 11 > 1），而不是按字符串长度比较。
        val sorted = listOf("1.21.1", "1.21.11", "1.21.2").sortedWith(mcVersionComparator)

        assertEquals(listOf("1.21.11", "1.21.2", "1.21.1"), sorted)
    }

    @Test
    fun `semantic order handles the full 1_21 line`() {
        val input = listOf(
            "1.21.1", "1.21.10", "1.21.2", "1.21.11", "1.21",
            "1.21.4", "1.21.9", "1.21.3", "1.21.5"
        )

        val sorted = input.sortedWith(mcVersionComparator)

        assertEquals(
            listOf(
                "1.21.11", "1.21.10", "1.21.9", "1.21.5",
                "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21"
            ),
            sorted
        )
    }

    @Test
    fun `new versioning scheme sorts above the old line`() {
        // 26.2 > 1.21.11 > 1.21.4（跨编号体系，纯字符串比较会把 1.21.x 排到 26.2 前面）。
        val sorted = listOf("1.21.4", "26.2", "1.21.11").sortedWith(mcVersionComparator)

        assertEquals(listOf("26.2", "1.21.11", "1.21.4"), sorted)
    }

    @Test
    fun `take after semantic sort keeps the newest versions`() {
        // 这正是「只显示到 1.21.4」的成因：按 API 原始顺序 take 会丢掉新版本。
        // 语义排序后再 take，取到的必须是最新的一批。
        val all = listOf("1.21.4", "1.21.11", "1.21.1", "1.21.10", "1.20.6")
        val top3 = all.sortedWith(mcVersionComparator).take(3)

        assertEquals(listOf("1.21.11", "1.21.10", "1.21.4"), top3)
        assertTrue(top3.none { it == "1.21.1" })
    }

    @Test
    fun `comparator is consistent for equal versions`() {
        // 比较器必须自反，否则 sortedWith 在重复元素上行为未定义。
        assertEquals(0, mcVersionComparator.compare("1.21.4", "1.21.4"))
    }
}
