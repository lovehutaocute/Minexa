package com.mineserve.mobile.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 玩家名清洗回归测试。
 *
 * 现场问题：用户反馈在线列表里出现 `[0mdeepseek_flash`、`wehxc147[37m` 这种条目，
 * 且「重开服务器也在」。根因是 list 响应带 ANSI 颜色码、ESC 被日志管道吞掉后只剩
 * 字面 `[0m`/`[37m`，被当成了名字的一部分。
 */
class PlayerNamesTest {

    @Test
    fun `ESC 被吞后残留的_0m 前缀被剔除`() {
        assertEquals("deepseek_flash", PlayerNames.sanitize("[0mdeepseek_flash"))
        assertEquals("453_ab", PlayerNames.sanitize("[0m453_ab"))
    }

    @Test
    fun `颜色码残留的_37m 后缀被剔除`() {
        assertEquals("wehxc147", PlayerNames.sanitize("wehxc147[37m"))
        assertEquals("wehxc147", PlayerNames.sanitize("[0mwehxc147[1;32m"))
    }

    @Test
    fun `完整 ANSI 转义序列被剔除`() {
        assertEquals("Steve", PlayerNames.sanitize("\u001B[0mSteve\u001B[37m"))
    }

    @Test
    fun `纯转义段落清洗后为空应被判为垃圾`() {
        assertNull(PlayerNames.sanitize("[0m"))
        assertNull(PlayerNames.sanitize("  "))
    }

    @Test
    fun `带空格与代理前缀的合法名字保留`() {
        assertEquals("Steve Smith", PlayerNames.sanitize("[0mSteve Smith[37m"))
        assertEquals(".BedrockPlayer", PlayerNames.sanitize(".BedrockPlayer"))
        assertEquals("* Steve", PlayerNames.sanitize("* Steve"))
    }

    @Test
    fun `超长与含非法字符的名字被丢弃`() {
        assertNull(PlayerNames.sanitize("a".repeat(33)))
        assertNull(PlayerNames.sanitize("Bad[Name"))
        assertNull(PlayerNames.sanitize("Bad\\Name"))
    }

    @Test
    fun `list 响应按逗号切分后每段都能正确清洗`() {
        // 现场日志的真实形态：颜色码被拆散到每个名字前后
        val tail = "[0mwehxc147[37m, [0mdeepseek_flash[37m, [0m453_ab[37m"
        val names = tail.split(",").mapNotNull { PlayerNames.sanitize(it) }
        assertEquals(listOf("wehxc147", "deepseek_flash", "453_ab"), names)
    }
}
