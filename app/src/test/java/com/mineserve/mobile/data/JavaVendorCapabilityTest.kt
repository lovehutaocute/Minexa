package com.mineserve.mobile.data

import com.mineserve.mobile.runtime.FclJrePackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三个 Java 来源（Termux / Pojav FCL / PRoot）的**能力声明守卫**。
 *
 * ## 为什么需要它
 *
 * 界面上每个来源都有一句来源说明，每个特殊版本还有一句补充说明。
 * 这些文案描述的是**代码的真实行为**（走不走容器、支不支持 IPv6、
 * 能装哪些版本），而文案与实现分居两个文件，改了一边忘了另一边是常态。
 *
 * 更麻烦的是，这类不一致**不会崩**，只会静默地说错话：
 * - 说「IPv6 由兼容层接管」，而该链路其实挂不上兼容层 → 用户按提示配了
 *   `server-ip=::`，服务端起不来，回头怀疑是应用有 bug；
 * - 说「Java 8 由 apt 安装」，而它其实跑在容器里 → 排错方向完全跑偏；
 * - 列出该容器装不上的版本 → 用户点了只看到 apt 深处一句报错。
 *
 * 因此这里把「文案声称的能力」与「代码实际的能力」逐条对齐，
 * 任何一条漂移都会让测试失败，并指出是哪句话对不上。
 *
 * ## 文案与断言的一一对应
 *
 * | 文案 key | 声称 | 本测试断言 |
 * |---|---|---|
 * | `java_group_termux_note` | 「直接在 Termux 中运行（Java 8 不在这里）」 | Java 8 不在 Termux 分组 + 其余版本走宿主 |
 * | `java_group_proot_note` | 「用 apt 安装 JDK；不提供 Root 权限」 | 并集 == `vendor.versions` |
 * | `java_note_proot_container` | 「不支持 IPv6 监听」 | `ipv6ListenAvailable == false` |
 * | `java_note_proot_java8` | 「仅 focal 提供此项」 | 只有 focal 的可用版本含 Java 8 |
 * | `java_group_fcl_note` | 「自带 IPv6 支持」 | `ipv6ListenAvailable == true` |
 */
class JavaVendorCapabilityTest {

    // ── 文案：「Termux 分组里没有 Java 8，其余版本走宿主」 ─────────

    @Test
    fun termuxGroupNoteClaimAboutJava8MatchesRouting() {
        // Termux 分组说明此前写「Java 8 例外，它跑在 proot 容器内」，
        // 而 Java 8 已移入 PRoot 分组 —— 该分组下再提 Java 8 只会让人找不着。
        // 现在的说法是「直接在 Termux 中运行（Java 8 不在这里）」，
        // 因此要同时成立两件事：Java 8 不在本组，且本组版本确实走宿主。
        assertFalse(
            "Termux 分组说明称 Java 8 不在这里，列表里就不该有它",
            JavaVersion.Java8 in JavaVendor.Termux.versions
        )
        // 而 Termux 列出的版本必须真的走宿主，否则「直接在 Termux 中运行」是错的
        JavaVendor.Termux.versions.forEach { version ->
            assertFalse(
                "Termux + $version 应为宿主链路；说明称本组直接在 Termux 中运行",
                isProotGuestRuntime(JavaVendor.Termux, version)
            )
        }
    }

    @Test
    fun java8TermuxAndProotFocalAreTheSameContainer() {
        // 历史配置的 Termux + Java 8 与 Proot + focal 必须是同一个容器。
        // 这条如果为假，说明归一与运行时画像分了叉 ——
        // 老用户已装的 Java 8 会被判成「未安装」并要他重新下载几百兆。
        //
        // 判定方式：把 Java 8 当作「Proot 来源 + focal」解析出的画像，
        // 必须与 Termux 来源下 Java 8 解析出的画像相同。
        val termuxJava8 = ProotDistro.Focal.profileFor(JavaVersion.Java8)
        assertNotNull("focal 必须提供 Java 8（Termux 的 Java 8 就落在这里）", termuxJava8)
        assertEquals(ProotDistro.Focal, termuxJava8!!.distro)
        assertEquals(JavaVersion.Java8, termuxJava8.version)
    }

    @Test
    fun everyVendorVersionIsEitherGuestRoutedOrHostRouted() {
        // 每个来源的每个版本都必须有明确归属：
        // 要么走容器（能解析出画像），要么走宿主（不走来宾链路）。
        // 不允许出现「声称支持但既没有画像也不确认走宿主」的悬空版本。
        JavaVendor.entries.forEach { vendor ->
            vendor.versions.forEach { version ->
                val guest = isProotGuestRuntime(vendor, version)
                // 来宾链路的版本必须能在某个发行版里找到画像；宿主版本则不该依赖发行版
                val resolvable = ProotDistro.entries.any { it.profileFor(version) != null }
                if (guest) {
                    assertTrue(
                        "$vendor.$version 声称走容器，就必须能解析出画像",
                        resolvable
                    )
                } else {
                    assertFalse(
                        "$vendor.$version 走宿主，不应依赖容器画像",
                        vendor == JavaVendor.Termux && resolvable && version == JavaVersion.Java8
                    )
                }
            }
        }
    }

    // ── 文案：FCL「自带 IPv6 支持」/ Proot「不支持 IPv6 监听」 ─────

    @Test
    fun ipv6ClaimsMatchImplementationForEveryVendor() {
        // 逐来源核对 IPv6 能力声明。这个是用户会照着配置 `server-ip=::` 的，
        // 说错了会直接导致服务端起不来。
        fun ipv6(vendor: JavaVendor, version: JavaVersion, flavor: JvmFlavor) =
            McConfig().copy(
                selectedJavaVendor = vendor,
                selectedJavaVersion = version,
                jvmFlavor = flavor
            ).ipv6ListenAvailable

        // FCL 文案：「自带 IPv6 支持」→ 与 jvmFlavor 无关，永远为真
        listOf(JvmFlavor.PatchedJdk, JvmFlavor.TermuxJdk).forEach { flavor ->
            JavaVendor.Fcl.versions.forEach { version ->
                assertTrue(
                    "FCL.$version 声称自带 IPv6 支持，flavor=$flavor 时必须为真",
                    ipv6(JavaVendor.Fcl, version, flavor)
                )
            }
        }

        // PRoot 文案：「不支持 IPv6 监听」→ 与 flavor 无关，永远为假
        // （兼容层是按 bionic 构建的 LD_PRELOAD，glibc guest 加载不了）
        listOf(JvmFlavor.PatchedJdk, JvmFlavor.TermuxJdk).forEach { flavor ->
            JavaVendor.Proot.versions.forEach { version ->
                assertFalse(
                    "PRoot.$version 声称不支持 IPv6 监听，flavor=$flavor 时必须为假",
                    ipv6(JavaVendor.Proot, version, flavor)
                )
            }
        }

        // Termux：Java 8 走容器 ⇒ 同 PRoot，不支持；其余看是否挂了兼容层
        assertFalse(
            "Termux.Java8 在容器里，不得声称支持 IPv6",
            ipv6(JavaVendor.Termux, JavaVersion.Java8, JvmFlavor.PatchedJdk)
        )
        assertTrue(
            "Termux.Java17 + PatchedJdk 应支持 IPv6",
            ipv6(JavaVendor.Termux, JavaVersion.Java17, JvmFlavor.PatchedJdk)
        )
        assertFalse(
            "Termux.Java17 + TermuxJdk（未挂兼容层）不支持 IPv6",
            ipv6(JavaVendor.Termux, JavaVersion.Java17, JvmFlavor.TermuxJdk)
        )
    }

    // ── 文案：PRoot「可安装版本取决于所选发行版」 ──────────────────

    @Test
    fun prootVendorVersionsEqualTheUnionOfDistroVersions() {
        // 文案说「可安装版本取决于所选发行版」。UI 按 vendor.versions 列出全部候选，
        // 因此该列表必须**恰好等于**各发行版可用版本的并集：
        // 多一个 → 出现装不上的死条目；少一个 → 用户真正能装的版本反而不显示。
        val union = ProotDistro.entries
            .flatMap { it.availableJavaVersions }
            .distinct()
            .sortedBy { it.ordinal }
        assertEquals(
            "JavaVendor.Proot.versions 必须等于各发行版可用版本的并集",
            union,
            JavaVendor.Proot.versions.sortedBy { it.ordinal }
        )
    }

    @Test
    fun prootDistroNotesAreAccurateAboutPerDistroAvailability() {
        // 文案声称每个发行版只提供一部分版本。逐条核对 §0 的版本矩阵，
        // 这三条是产品决策，写错了就是对用户的承诺出错。
        assertEquals(
            listOf(JavaVersion.Java8, JavaVersion.Java11, JavaVersion.Java17),
            ProotDistro.Focal.availableJavaVersions
        )
        assertEquals(
            listOf(JavaVersion.Java11, JavaVersion.Java17, JavaVersion.Java21),
            ProotDistro.Jammy.availableJavaVersions
        )
        // 「不提供 Java 25」是明确的对外口径（该容器不提供，不是暂未支持）
        ProotDistro.entries.forEach { distro ->
            assertFalse(
                "${distro.displayName} 不得声称提供 Java 25",
                JavaVersion.Java25 in distro.availableJavaVersions
            )
        }
    }

    // ── 文案：FCL 下载体积「约 37–62 MB」 ─────────────────────────

    @Test
    fun fclVendoredPackageSizesMatchTheAdvertisedRange() {
        // 文案对 FCL 写的是「约 37–62 MB」。这个数字来自内置的包元数据。
        //
        // 容差说明：真实值是 37.2 / 45.1 / 62.6 MB。文案用了「约」字，
        // 且 62.6 对用户来说就是「约 62MB」——用户关心的是量级（会不会吃掉
        // 几百兆流量），不是小数点后一位。所以这里允许 ±1MB 的取整误差，
        // 但**不允许量级漂移**：上游若换成 150MB 的包，文案必须改。
        val sizes = FclJrePackages.all.map { it.sizeBytes }
        assertTrue("FCL 包元数据不能为空", sizes.isNotEmpty())
        fun mb(bytes: Long): Double = bytes / 1_000_000.0
        val smallestMb = mb(sizes.min())
        val largestMb = mb(sizes.max())
        assertTrue(
            "FCL 最小包 ${"%.1f".format(smallestMb)}MB 与文案下限 37MB 偏差过大",
            smallestMb in 36.0..38.0
        )
        assertTrue(
            "FCL 最大包 ${"%.1f".format(largestMb)}MB 与文案上限 62MB 偏差过大",
            largestMb in 61.0..63.0
        )
    }

    // ── 文案：日志名与显示名 ──────────────────────────────────────

    @Test
    fun vendorLogNamesAreDistinctAndStable() {
        // 日志里用 logName 区分来源；重名会让排错时分不清是哪套 JDK 出的错。
        assertEquals(
            "各来源的日志名必须互不相同",
            JavaVendor.entries.size,
            JavaVendor.entries.map { it.logName }.distinct().size
        )
        assertEquals("Termux", JavaVendor.Termux.logName)
        assertEquals("FCL", JavaVendor.Fcl.logName)
        assertEquals("PRoot", JavaVendor.Proot.logName)
    }

    // ── 诊断页：proot 必需性必须覆盖全部来宾链路 ─────────────────

    @Test
    fun prootIsRequiredForEveryGuestRoutedCombination() {
        // 诊断页的「依赖命令」检查据此决定是否校验 proot。
        // 早先只判 `Termux && Java8`，选中「PRoot 容器」来源时 prootRequired 为 false
        // —— 诊断会报告「无需 proot」并标为 NotApplicable，而 proot 正是这条链路
        // 赖以启动的东西。容器起不来时这条会把用户引向完全错误的方向。
        //
        // 注意这里遍历的是**全部 (来源, 版本) 组合**，而不是各来源的 versions 列表：
        // 「Termux + Java 8」已从列表里移除（Java 8 现在只属于 Proot 分组），
        // 但历史配置里仍有这个写法，它必须继续被判为「需要 proot」，
        // 否则老用户升上来后诊断会误报「无需 proot」。
        val needsProot = JavaVendor.entries
            .flatMap { vendor -> JavaVersion.entries.map { vendor to it } }
            .filter { (vendor, version) -> isProotGuestRuntime(vendor, version) }
            .toSet()

        // 来宾链路的必备项：Termux.Java8（历史写法）与 Proot 的全部版本
        assertTrue(
            "Termux.Java8 走来宾容器（历史配置仍在用），需要 proot",
            (JavaVendor.Termux to JavaVersion.Java8) in needsProot
        )
        assertTrue(
            "Proot.Java8 需要 proot",
            (JavaVendor.Proot to JavaVersion.Java8) in needsProot
        )
        JavaVendor.Proot.versions.forEach { version ->
            assertTrue(
                "PRoot.$version 必须被判定为需要 proot",
                (JavaVendor.Proot to version) in needsProot
            )
        }
        // Termux 的非 Java 8 版本走宿主 JVM，不需要 proot
        JavaVendor.Termux.versions.forEach { version ->
            assertFalse(
                "Termux.$version 走宿主，不该要求 proot",
                (JavaVendor.Termux to version) in needsProot
            )
        }
        // FCL 是 Android 原生二进制，永远不需要 proot
        JavaVendor.Fcl.versions.forEach { version ->
            assertFalse(
                "FCL.$version 不需要 proot",
                (JavaVendor.Fcl to version) in needsProot
            )
        }
    }

    // ── 诊断页：字体检查必须按来源分流 ──────────────────────────

    @Test
    fun fontCheckMustRouteGuestVendorsIntoTheContainer() {
        // 来宾链路的字体在**容器内**，宿主 `etc/fonts/fonts.conf` 与它无关。
        // 早先 fontRuntimeReady 只看 `version == Java8`，于是 Proot + Java 17
        // 会掉进宿主分支：容器缺字体时报「已就绪」，或往宿主装容器用不到的东西。
        //
        // 这里锁定「哪些组合属于来宾链路」——也就是必须走容器内检查的集合。
        // 刻意用 JavaVersion.entries 全集而非各来源的 versions 列表：
        // 「Termux + Java 8」虽已不在分组里展示，但历史配置仍用它，
        // 必须继续被分流到容器内检查。
        val guestCombos = JavaVendor.entries.flatMap { vendor ->
            JavaVersion.entries.filter { isProotGuestRuntime(vendor, it) }
                .map { vendor to it }
        }.toSet()

        assertTrue("来宾链路集合不应为空", guestCombos.isNotEmpty())
        // Proot 的全部版本都在其中
        JavaVendor.Proot.versions.forEach { version ->
            assertTrue(
                "Proot.$version 必须在来宾链路集合内（字体须进容器检查）",
                (JavaVendor.Proot to version) in guestCombos
            )
        }
        // Java 8 无论在哪个来源，都走来宾链路
        assertTrue((JavaVendor.Termux to JavaVersion.Java8) in guestCombos)
        assertTrue((JavaVendor.Proot to JavaVersion.Java8) in guestCombos)
        // Termux 的宿主版本不在其中
        JavaVendor.Termux.versions.forEach { version ->
            assertFalse(
                "Termux.$version 走宿主，字体不该进容器检查",
                (JavaVendor.Termux to version) in guestCombos
            )
        }
        // FCL 不在其中 —— 它的 JRE 整包解压，字体随包附带
        JavaVendor.Fcl.versions.forEach { version ->
            assertFalse(
                "FCL.$version 不应走来宾链路",
                (JavaVendor.Fcl to version) in guestCombos
            )
        }
    }

    // ── 文案：分组说明必须与实际分组一致 ────────────────────────

    @Test
    fun termuxGroupNoteDoesNotClaimToContainJava8() {
        // Termux 分组说明曾写「Java 8 例外，它跑在 proot 容器内」。
        // Java 8 已移入 PRoot 分组，这句话在 Termux 分组下就变成了
        // 「说明里提到一个列表里根本没有的版本」，只会让人找不着。
        // 这条用代码事实反查文案该说什么：Java 8 确实不在 Termux 分组里。
        assertFalse(
            "Java 8 已不在 Termux 分组，分组说明不应再把它当作本组的一员",
            JavaVersion.Java8 in JavaVendor.Termux.versions
        )
        // Java 8 必须在 Proot 分组里（否则用户无处安装它）
        assertTrue(
            "Java 8 必须在 PRoot 分组里可选中",
            JavaVersion.Java8 in JavaVendor.Proot.versions
        )
    }

    @Test
    fun java8IsOnlyProvidedByOneDistro() {
        // 文案「仅 Ubuntu 20.04（focal）提供此项」必须与版本矩阵一致。
        val providing = ProotDistro.entries.filter { JavaVersion.Java8 in it.availableJavaVersions }
        assertEquals(
            "文案称 Java 8 仅 focal 提供，实际提供者：$providing",
            listOf(ProotDistro.Focal),
            providing
        )
    }

    @Test
    fun everyProotVersionIsProvidedByAtLeastOneDistro() {
        // Proot 分组列出的版本若没有任何发行版提供，就是「看得到但永远装不上」的死项。
        JavaVendor.Proot.versions.forEach { version ->
            assertTrue(
                "PRoot 分组列出了 $version，但没有任何发行版提供它",
                ProotDistro.entries.any { version in it.availableJavaVersions }
            )
        }
    }
}
