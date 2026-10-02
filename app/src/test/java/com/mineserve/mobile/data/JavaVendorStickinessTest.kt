package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「运行环境选择必须被尊重」的守卫。
 *
 * ## 为什么需要它
 *
 * 启动服务端时有一套「按 MC 版本自动匹配 Java」的逻辑：当前版本不够新就自动升级，
 * 必要时还会降级（Forge ≤1.16 只支持 Java 8/11）。
 *
 * 这套逻辑早先允许**连来源一起改**：所选来源不提供目标版本时，回退到
 * 「第一个提供该版本的来源」。后果是用户在「运行环境」里明确选了 PRoot 容器，
 * 启动 Paper 26.1（需要 Java 25）时被静默改成 **Termux + Java 25** ——
 * 而且这个改动会落盘，用户的显式选择被永久覆盖。
 *
 * 运行环境是用户唯一能指定「用哪个 libc 世界跑服务端」的入口，PRoot 的存在意义
 * 正是脱离 bionic。替用户换掉它，等于换掉整个运行前提。
 *
 * 现在只允许在**同一来源内**调整版本；来源提供不了时明确报错。
 * 这个文件用纯数据事实钉住该策略的前提：哪些版本在任何来源里都不存在，
 * 哪些组合会被这条策略拒绝。
 */
class JavaVendorStickinessTest {

    // ── provides / availableVersions：可安装性的唯一判定 ─────────

    @Test
    fun prootAvailabilityIsNarrowedByDistro() {
        // Proot 的「可用版本」必须随发行版收窄，这是自动匹配时判断
        // 「能不能在这个来源里升到目标版本」的依据。
        assertEquals(
            listOf(JavaVersion.Java8, JavaVersion.Java11, JavaVersion.Java17),
            JavaVendor.Proot.availableVersions(ProotDistro.Focal)
        )
        assertEquals(
            listOf(JavaVersion.Java11, JavaVersion.Java17, JavaVersion.Java21),
            JavaVendor.Proot.availableVersions(ProotDistro.Jammy)
        )
    }

    @Test
    fun nonProotVendorsIgnoreDistro() {
        // 其余来源与发行版无关，换发行版不该影响它们的可用版本 ——
        // 否则用户在别处切一下 Ubuntu 版本就可能把自己的 JDK 判成不可用。
        ProotDistro.entries.forEach { distro ->
            assertEquals(JavaVendor.Termux.versions, JavaVendor.Termux.availableVersions(distro))
            assertEquals(JavaVendor.Fcl.versions, JavaVendor.Fcl.availableVersions(distro))
        }
    }

    @Test
    fun prootAvailabilityNeverExceedsTheUnion() {
        // 单个发行版的可用版本必须是并集的子集。若某个发行版漏出一个不在
        // JavaVendor.Proot.versions 里的版本，UI 的置灰逻辑（基于并集渲染）
        // 就永远看不到它，用户装得上却选不了。
        ProotDistro.entries.forEach { distro ->
            JavaVendor.Proot.availableVersions(distro).forEach { version ->
                assertTrue(
                    "$distro 提供 $version，但它不在 JavaVendor.Proot.versions 里，界面不会显示",
                    version in JavaVendor.Proot.versions
                )
            }
        }
    }

    @Test
    fun noDistroProvidesJava25SoProotCanNeverAutoUpgradeToIt() {
        // 这是「自动匹配把来源换成 Termux + Java 25」的触发条件：
        // Paper 26.1+ 推荐 Java 25，而 PRoot 全系列都装不了它。
        // 锁定这个事实：PRoot 下 Java 25 永远不可用，
        // 因此这条路径必须报错而不是换来源。
        ProotDistro.entries.forEach { distro ->
            assertFalse(
                "$distro 声称提供 Java 25 —— Ubuntu arm64 源并没有该包",
                JavaVendor.Proot.provides(JavaVersion.Java25, distro)
            )
        }
        // 而 Termux 确实提供 Java 25 —— 所以「换来源」看起来总能成功，
        // 这正是它危险的地方：它会静默成功，而不是失败暴露出来。
        assertTrue(
            "Termux 提供 Java 25，因此换来源会静默成功 —— 必须靠策略阻止",
            JavaVendor.Termux.provides(JavaVersion.Java25, ProotDistro.Default)
        )
    }

    @Test
    fun legacyForgeCandidatesAreOnlyOfferedByGuestAndTermuxSources() {
        // Forge ≤1.16 需要 Java 8/11，自动降级会在这两个版本里挑。
        // FCL 上游只发布 17/21/25，两个都没有 —— 也就是说选了 FCL 的用户
        // 撞上老 Forge 时，旧代码会把他换到 Termux。现在改为明确报错，
        // 因此这里锁定 FCL 确实一个都提供不了（报错文案据此写「不提供 Java 8/11」）。
        val candidates = listOf(JavaVersion.Java8, JavaVersion.Java11)
        candidates.forEach { version ->
            assertFalse(
                "FCL 若开始提供 $version，Forge 降级路径的报错文案需要同步修改",
                JavaVendor.Fcl.provides(version, ProotDistro.Default)
            )
        }
        // Java 8 只有 PRoot 提供；Java 11 由 PRoot 与 Termux 共同提供
        assertTrue(JavaVendor.Proot.provides(JavaVersion.Java8, ProotDistro.Focal))
        assertFalse(JavaVendor.Termux.provides(JavaVersion.Java8, ProotDistro.Default))
        assertTrue(JavaVendor.Termux.provides(JavaVersion.Java11, ProotDistro.Default))
    }

    // ── 胶囊控件的短名 ──────────────────────────────────────────

    @Test
    fun distroShortNamesAreCompactAndDistinct() {
        // 胶囊每个选项只占一半宽度，短名必须短且互不相同。
        val names = ProotDistro.entries.map { it.shortName }
        assertEquals("短名必须互不相同", ProotDistro.entries.size, names.distinct().size)
        names.forEach { name ->
            // 带代号的 displayName 在半个屏宽里会挤成两行
            assertFalse("短名不应再带代号括号：$name", name.contains("("))
            assertTrue("短名过长，胶囊放不下：$name", name.length <= 16)
        }
        assertEquals("Ubuntu 20.04", ProotDistro.Focal.shortName)
        assertEquals("Ubuntu 22.04", ProotDistro.Jammy.shortName)
    }

    // ── 折叠分组的默认状态 ──────────────────────────────────────

    @Test
    fun thereAreEnoughVersionsToJustifyCollapsingByDefault() {
        // 「默认全部折叠」的理由是行数太多会把主操作按钮顶出屏幕。
        // 若将来版本行变少，这条会提醒重新考虑默认状态。
        val rows = JavaVendor.entries.sumOf { it.versions.size }
        assertTrue(
            "三个来源共 $rows 条版本行，少于 6 条时默认折叠就不再有意义",
            rows >= 6
        )
    }
}
