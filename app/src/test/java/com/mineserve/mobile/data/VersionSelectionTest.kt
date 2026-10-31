package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 版本校正规则的单测：覆盖「该保留」和「该回落到最新」两类，重点是别把手填的
 * 自定义版本误伤、也别让跨体系的旧版本活下来。
 */
class VersionSelectionTest {

    private val javaVersions = listOf("1.21.4", "1.20.4", "1.20.1")

    @Test
    fun sameFamilyKeepsExistingVersion() {
        // Paper → Purpur：都是 MC 版本号，1.20.4 在列表里，保留
        assertNull(VersionSelection.resolve("1.20.4", javaVersions, familyChanged = true, firstLoadInProcess = false))
    }

    @Test
    fun switchToOtherFamilyFallsBackToLatest() {
        // Paper(1.20.4) → BDS：官方 build 号体系，旧值不存在，落到列表第一项
        val bds = listOf("1.21.50.7")
        assertEquals(
            "1.21.50.7",
            VersionSelection.resolve("1.20.4", bds, familyChanged = true, firstLoadInProcess = false)
        )
    }

    @Test
    fun coldStartStaleValueIsCorrected() {
        // 冷启动：持久化的是 BDS 的版本，但 selectedCore 已经是 Paper
        assertEquals(
            "1.21.4",
            VersionSelection.resolve("1.21.50.7", javaVersions, familyChanged = false, firstLoadInProcess = true)
        )
    }

    @Test
    fun coldStartValidCustomVersionIsKept() {
        // 冷启动但版本号仍在列表里 → 不动
        assertNull(VersionSelection.resolve("1.20.1", javaVersions, familyChanged = false, firstLoadInProcess = true))
    }

    @Test
    fun sentinelIsReplacedWhenCoreHasNoLatest() {
        // 基岩版残留的 latest 带到 Paper 上，列表里没有 latest → 必须换成具体版本
        assertEquals(
            "1.21.4",
            VersionSelection.resolve(VersionSelection.LATEST, javaVersions, familyChanged = false, firstLoadInProcess = false)
        )
    }

    @Test
    fun bungeeCordLatestIsLegitimate() {
        // BungeeCord 的官方列表本身就是 ["latest"]，不能当成哨兵换掉
        assertNull(
            VersionSelection.resolve(
                VersionSelection.LATEST,
                listOf("latest"),
                familyChanged = false,
                firstLoadInProcess = true
            )
        )
    }

    @Test
    fun sameCoreReloadKeepsHandWrittenVersion() {
        // 同一个核心、列表已缓存时重新进入页面：手填的版本不在官方列表里也要保留
        assertNull(VersionSelection.resolve("1.20.5", javaVersions, familyChanged = false, firstLoadInProcess = false))
    }

    @Test
    fun emptyVersionsNoOp() {
        assertNull(VersionSelection.resolve("1.20.4", emptyList(), familyChanged = true, firstLoadInProcess = true))
    }

    @Test
    fun blankVersionIsLeftForUserToPick() {
        // 空版本 = 用户刚点过核心、被显式清空，等着自己挑。
        // 这里返回 null（保持为空）而不是自动选第一项：否则「切换核心后必须重新选版本」
        // 这条交互要求会被自动填充悄悄抵消。
        assertNull(VersionSelection.resolve("", javaVersions, familyChanged = false, firstLoadInProcess = false))
    }

    @Test
    fun familiesPartitionCoresCorrectly() {
        assertEquals("mc-java", VersionSelection.familyOf(ServerCore.Paper))
        assertEquals("mc-java", VersionSelection.familyOf(ServerCore.Fabric))
        assertEquals("mc-java", VersionSelection.familyOf(ServerCore.Vanilla))
        assertEquals("velocity", VersionSelection.familyOf(ServerCore.Velocity))
        assertEquals("bungee", VersionSelection.familyOf(ServerCore.BungeeCord))
        assertEquals("pnx", VersionSelection.familyOf(ServerCore.PowerNukkitX))
        assertEquals("bds", VersionSelection.familyOf(ServerCore.Bds))
        // 同属基岩版，但版本编号互不相通，必须是不同 family
        assertNotEquals(
            VersionSelection.familyOf(ServerCore.PowerNukkitX),
            VersionSelection.familyOf(ServerCore.Bds)
        )
    }
}
