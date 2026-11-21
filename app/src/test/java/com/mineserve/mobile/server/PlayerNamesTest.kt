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
    fun `基岩版颜色码被剔除`() {
        assertEquals("Steve", PlayerNames.sanitize("§aSteve"))
        assertEquals("Steve", PlayerNames.sanitize("§a§lSteve"))
        // 十六进制逐对写法：§x §f §f §0 §0 §0 §0 是同一个颜色
        assertEquals("Stone", PlayerNames.sanitize("§x§f§f§0§0§0§0Stone"))
    }

    @Test
    fun `多属性与 256 色残码被剔除而不是丢弃整条名字`() {
        assertEquals("Steve", PlayerNames.sanitize("[0;1;32mSteve"))
        assertEquals("Steve", PlayerNames.sanitize("[38;5;202mSteve"))
    }

    @Test
    fun `真彩色与叠加写法的残码同样被剔除`() {
        assertEquals("Steve", PlayerNames.sanitize("[38;2;255;0;0mSteve"))
        assertEquals("Steve", PlayerNames.sanitize("[48;2;1;2;3mSteve"))
        assertEquals("Steve", PlayerNames.sanitize("[1;38;5;202mSteve"))
        assertEquals("Steve", PlayerNames.sanitize("[38;5;202;1mSteve"))
    }

    @Test
    fun `list 响应按逗号切分后每段都能正确清洗`() {
        // 现场日志的真实形态：颜色码被拆散到每个名字前后
        val tail = "[0mwehxc147[37m, [0mdeepseek_flash[37m, [0m453_ab[37m"
        val names = tail.split(",").mapNotNull { PlayerNames.sanitize(it) }
        assertEquals(listOf("wehxc147", "deepseek_flash", "453_ab"), names)
    }

    // ── Java 版进服/离服：脏码粘在名字前，必须在跑正则之前清掉 ──────────

    @Test
    fun `颜色码前缀的进服日志仍能取到真名`() {
        assertEquals("Steve", PlayerNames.fromJoinLeaveLine("[12:00:00 INFO]: [0mSteve joined the game"))
        assertEquals("Steve", PlayerNames.fromJoinLeaveLine("[12:00:00 INFO]: [0mSteve[37m joined the game"))
        assertEquals("deepseek_flash", PlayerNames.fromJoinLeaveLine("[12:00:00 INFO]: [0mdeepseek_flash has joined the game"))
    }

    @Test
    fun `真实 ANSI 序列包裹的离服日志仍能取到真名`() {
        assertEquals("Steve", PlayerNames.fromJoinLeaveLine("[12:00:00 INFO]: \u001B[1;32mSteve\u001B[0m left the game"))
    }

    @Test
    fun `干净日志与聊天消息的既有行为不变`() {
        assertEquals("Steve", PlayerNames.fromJoinLeaveLine("[12:00:00 INFO]: Steve joined the game"))
        assertEquals("Steve", PlayerNames.fromJoinLeaveLine("[12:00:00 INFO]: Steve[/127.0.0.1:5555] joined the game"))
        // 聊天消息不该被当成玩家事件
        assertNull(PlayerNames.fromJoinLeaveLine("[12:00:00 INFO]: <Steve> I joined the game"))
    }

    @Test
    fun `tab 分隔的日志不会被粘成一个词`() {
        // 控制字符不能直接删：`Steve\tjoined` 删掉 tab 会变成 `Stevejoined`，\s+ 匹配失败
        assertEquals("Steve", PlayerNames.fromJoinLeaveLine("[12:00:00 INFO] Steve\tjoined the game"))
    }
}
