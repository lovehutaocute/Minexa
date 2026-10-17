package com.mineserve.mobile.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 端口真源规则的单测。
 *
 * 覆盖历史上出问题的那条链路：「配置 25562、地址栏显示 19132」——
 * 根因是端口迁移单向且不判断源核心类型。
 */
class ServerPortTest {

    // ── parse / resolve ─────────────────────────────────────────

    @Test
    fun `解析合法端口`() {
        assertEquals(25562, ServerPort.parse("25562"))
        assertEquals(25562, ServerPort.parse("  25562  "))
        assertEquals(19132, ServerPort.parse("19132"))
    }

    @Test
    fun `非法端口返回 null`() {
        assertNull(ServerPort.parse(null))
        assertNull(ServerPort.parse(""))
        assertNull(ServerPort.parse("abc"))
        assertNull(ServerPort.parse("0"))
        assertNull(ServerPort.parse("65536"))
        assertNull(ServerPort.parse("-1"))
    }

    @Test
    fun `resolve 优先配置文件里的端口`() {
        // 用户把 properties 改成 25562：真源就是 25562，不受核心默认值影响
        assertEquals(25562, ServerPort.resolve("25562", ServerCore.Paper, fallback = 25565))
    }

    @Test
    fun `resolve 缺端口时退回核心默认值`() {
        assertEquals(25565, ServerPort.resolve(null, ServerCore.Paper))
        assertEquals(19132, ServerPort.resolve(null, ServerCore.PowerNukkitX))
        assertEquals(19132, ServerPort.resolve(null, ServerCore.Allay))
        assertEquals(19132, ServerPort.resolve(null, ServerCore.PocketMine))
    }

    @Test
    fun `resolve 端口非法时退回核心默认值`() {
        assertEquals(25565, ServerPort.resolve("not-a-port", ServerCore.Paper))
        assertEquals(19132, ServerPort.resolve("99999", ServerCore.PowerNukkitX))
    }

    @Test
    fun `resolve 核心未知时用 fallback`() {
        assertEquals(12345, ServerPort.resolve(null, null, fallback = 12345))
    }

    // ── migrateOnCoreSwitch ─────────────────────────────────────

    @Test
    fun `Java 切基岩 默认端口对调`() {
        assertEquals(
            19132,
            ServerPort.migrateOnCoreSwitch(25565, ServerCore.Paper, ServerCore.PowerNukkitX)
        )
    }

    @Test
    fun `基岩切回 Java 能回到 25565`() {
        // 旧实现是单向的：19132 → 切回 Java 仍是 19132，再也回不去
        assertEquals(
            25565,
            ServerPort.migrateOnCoreSwitch(19132, ServerCore.PowerNukkitX, ServerCore.Paper)
        )
    }

    @Test
    fun `自定义端口跨系切换时原样保留`() {
        assertEquals(
            25562,
            ServerPort.migrateOnCoreSwitch(25562, ServerCore.Paper, ServerCore.PowerNukkitX)
        )
        assertEquals(
            25562,
            ServerPort.migrateOnCoreSwitch(25562, ServerCore.PowerNukkitX, ServerCore.Paper)
        )
    }

    @Test
    fun `同类核心之间不动端口`() {
        assertEquals(
            25565,
            ServerPort.migrateOnCoreSwitch(25565, ServerCore.Paper, ServerCore.Purpur)
        )
        assertEquals(
            19132,
            ServerPort.migrateOnCoreSwitch(19132, ServerCore.PowerNukkitX, ServerCore.Allay)
        )
        // 自定义端口在同类之间当然也不动
        assertEquals(
            25562,
            ServerPort.migrateOnCoreSwitch(25562, ServerCore.Paper, ServerCore.Spigot)
        )
    }

    @Test
    fun `源核心未知时按 Java 系处理`() {
        // 未知源核心 + 基岩目标：视为 Java→基岩，默认值对调
        assertEquals(
            19132,
            ServerPort.migrateOnCoreSwitch(25565, null, ServerCore.PowerNukkitX)
        )
        // 未知源核心 + Java 目标：按 Java 系处理，与目标同类，端口不动
        assertEquals(
            19132,
            ServerPort.migrateOnCoreSwitch(19132, null, ServerCore.Paper)
        )
    }
}
