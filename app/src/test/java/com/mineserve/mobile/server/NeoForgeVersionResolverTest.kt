package com.mineserve.mobile.server

import kotlin.test.Test
import kotlin.test.assertEquals

class NeoForgeVersionResolverTest {
    @Test
    fun `keeps minecraft patch boundary when selecting NeoForge`() {
        val selected = selectNeoForgeVersion(
            "1.21.1",
            listOf("21.1.9", "21.1.99", "21.11.9-beta")
        )

        assertEquals("21.1.99", selected)
    }

    @Test
    fun `selects numeric highest build rather than lexical highest`() {
        val selected = selectNeoForgeVersion("1.21.1", listOf("21.1.9", "21.1.10"))

        assertEquals("21.1.10", selected)
    }

    // ── 1.21.1 vs 1.21.11 前缀冲突（真机事故）──────────────────────

    @Test
    fun `does not let mc 1_21_1 match neoforge 21_11 builds`() {
        // NeoForge 21.11.x 属于 MC 1.21.11，不属于 1.21.1。
        // 旧实现用裸前缀 "21.1" 做 startsWith，会把 21.11.45 也命中并选中。
        val selected = selectNeoForgeVersion(
            "1.21.1",
            listOf("21.1.9", "21.1.10", "21.11.45", "21.11.60")
        )

        assertEquals("21.1.10", selected)
    }

    @Test
    fun `does not let mc 1_21_1 match neoforge 21_11 even when it is the only candidate`() {
        // 真实事故形态：候选集里只有 21.11.x，此时正确结果是「没有匹配」，
        // 而不是回落到 21.11.x 去下载 1.21.11 的包。
        val selected = selectNeoForgeVersion("1.21.1", listOf("21.11.45"))

        assertEquals(null, selected)
    }

    @Test
    fun `mc 1_21_11 still matches its own neoforge builds`() {
        // 反向不能误伤：1.21.11 必须能选中 21.11.x，且不能选中 21.1.x。
        val selected = selectNeoForgeVersion(
            "1.21.11",
            listOf("21.1.10", "21.11.45", "21.11.60")
        )

        assertEquals("21.11.60", selected)
    }

    @Test
    fun `mc 1_21_1 does not match builds of other patches`() {
        // 21.2.x（1.21.2）、21.4.x（1.21.4）都不属于 1.21.1。
        val selected = selectNeoForgeVersion("1.21.1", listOf("21.1.5", "21.2.3", "21.4.7"))

        assertEquals("21.1.5", selected)
    }

    @Test
    fun `exact version matches without trailing patch`() {
        // 21.1 本身（无补丁段）也属于 MC 1.21.1。
        val selected = selectNeoForgeVersion("1.21.1", listOf("21.1", "21.11.1"))

        assertEquals("21.1", selected)
    }

    @Test
    fun `new versioning scheme keeps dot boundary`() {
        // 新编号体系 26.2 → 26.2.x，同样不许 26.2 命中 26.20。
        val selected = selectNeoForgeVersion("26.2", listOf("26.2.0.86", "26.20.1"))

        assertEquals("26.2.0.86", selected)
    }
}
