package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRoot 容器的「画像一致性守卫」，对标 [JavaRuntimeCatalogTest] 对 FCL 的那组约束。
 *
 * ## 为什么需要它
 *
 * PRoot 的运行环境是 **(发行版 × Java 版本)** 的二元组。这两个维度**不是**笛卡尔积 ——
 * Ubuntu 20.04 的源里没有 openjdk-21，Ubuntu 22.04 的源里没有 openjdk-8，
 * 而 Java 25 两个发行版都没有。一旦这层约束在代码里被写错（例如 UI 照抄
 * `JavaVersion.entries`），症状会非常隐蔽：
 *
 * - 界面列出一个**必然装不上**的版本，用户点了只看到 apt 深处一句
 *   `Unable to locate package`，很容易被当成应用 bug 而不是「该容器不提供」；
 * - 或者反过来，某个其实能装的版本被漏掉，用户根本选不到；
 * - 或者 `Proot.Java8 + jammy` 这种非法组合被写进 config，启动时才炸。
 *
 * 这些都是**纯静态事实**，不需要设备就能守住 —— 这正是本测试存在的意义。
 */
class ProotContainerCatalogTest {

    // ── 首期范围：只有 focal 与 jammy ─────────────────────────────

    @Test
    fun firstReleaseShipsExactlyFocalAndJammy() {
        // 首期范围是明确决策：只做 Ubuntu 20.04 / 22.04，不预置 Debian 等空壳条目。
        // 列表里出现的每一项都必须真能装上，所以这里锁死条目集合。
        assertEquals(
            listOf(ProotDistro.Focal, ProotDistro.Jammy),
            ProotDistro.entries.toList()
        )
    }

    @Test
    fun distroMetadataIsWellFormed() {
        ProotDistro.entries.forEach { distro ->
            assertTrue("$distro: displayName 不能为空", distro.displayName.isNotBlank())
            assertTrue("$distro: codename 不能为空", distro.codename.isNotBlank())
            assertTrue("$distro: dirName 不能为空", distro.dirName.isNotBlank())
            assertTrue("$distro: 至少要有一个 apt 镜像", distro.aptMirrors.isNotEmpty())
            assertTrue(
                "$distro: apt 镜像不该重复",
                distro.aptMirrors.size == distro.aptMirrors.distinct().size
            )
        }
    }

    @Test
    fun distroDirectoryNamesAreDistinct() {
        // 两个发行版共用一个 rootfs 目录会互相覆盖，是灾难性的
        val dirs = ProotDistro.entries.map { it.dirName }
        assertEquals("发行版的 rootfs 目录名有重复", dirs.size, dirs.distinct().size)
    }

    // ── 版本矩阵：这正是「上游包现实」的编码 ──────────────────────

    @Test
    fun focalProvidesJava8_11_17ButNot21Or25() {
        val versions = ProotDistro.Focal.availableJavaVersions
        assertTrue(JavaVersion.Java8 in versions)
        assertTrue(JavaVersion.Java11 in versions)
        assertTrue(JavaVersion.Java17 in versions)
        // Ubuntu 20.04 的 openjdk-21 不在官方源里
        assertFalse("focal 不该声称提供 Java 21（源里没有 openjdk-21）", JavaVersion.Java21 in versions)
        assertFalse("首期 PRoot 一律不提供 Java 25", JavaVersion.Java25 in versions)
    }

    @Test
    fun jammyProvidesJava11_17_21ButNot8Or25() {
        val versions = ProotDistro.Jammy.availableJavaVersions
        assertFalse("jammy 不该声称提供 Java 8（源里没有 openjdk-8）", JavaVersion.Java8 in versions)
        assertTrue(JavaVersion.Java11 in versions)
        assertTrue(JavaVersion.Java17 in versions)
        assertTrue(JavaVersion.Java21 in versions)
        assertFalse("首期 PRoot 一律不提供 Java 25", JavaVersion.Java25 in versions)
    }

    @Test
    fun noDistroEverClaimsJava25() {
        // 首期已确认的范围决策，不是待办：Ubuntu 官方 arm64 源根本没有 openjdk-25。
        // 这条测试的作用是阻止有人「顺手」把它加进某个发行版的列表里。
        ProotDistro.entries.forEach { distro ->
            assertFalse(
                "${distro.displayName} 声称提供 Java 25，但首期明确不支持",
                JavaVersion.Java25 in distro.availableJavaVersions
            )
        }
    }

    @Test
    fun everyDistroOffersAtLeastOneVersion() {
        ProotDistro.entries.forEach { distro ->
            assertTrue("${distro.displayName} 没有任何可用版本", distro.availableJavaVersions.isNotEmpty())
        }
    }

    @Test
    fun distroVersionListsAreDuplicateFree() {
        ProotDistro.entries.forEach { distro ->
            val versions = distro.availableJavaVersions
            assertEquals(
                "${distro.displayName} 的版本列表有重复",
                versions.size,
                versions.distinct().size
            )
        }
    }

    @Test
    fun vendorVersionsEqualTheUnionOfDistroVersions() {
        // JavaVendor.Proot.versions 是「用户在这个来源下能看到的版本」，
        // 必须是各发行版可用版本的并集（focal 8/11/17 ∪ jammy 11/17/21 = 8/11/17/21）。
        // 若这里不一致，UI 会出现「分组里有这一行，但每个发行版都装不上」的死选项。
        val union = ProotDistro.entries
            .flatMap { it.availableJavaVersions }
            .distinct()
            .sortedBy { it.ordinal }
        assertEquals(
            union,
            JavaVendor.Proot.versions.sortedBy { it.ordinal }
        )
    }

    @Test
    fun prootVendorOmitsJava25() {
        assertFalse(JavaVersion.Java25 in JavaVendor.Proot.versions)
    }

    // ── profileFor：非法组合必须被拦下 ───────────────────────────

    @Test
    fun profileForRejectsCombinationsTheDistroCannotInstall() {
        // 这两个是最容易写错的组合，也是最典型的「用户选了装不上」
        assertNull("focal 不提供 Java 21", ProotDistro.Focal.profileFor(JavaVersion.Java21))
        assertNull("jammy 不提供 Java 8", ProotDistro.Jammy.profileFor(JavaVersion.Java8))
        // Java 25 任何首期发行版都不提供
        ProotDistro.entries.forEach { distro ->
            assertNull(
                "${distro.displayName} 不该为 Java 25 造出画像",
                distro.profileFor(JavaVersion.Java25)
            )
        }
    }

    @Test
    fun profileForAcceptsEveryAdvertisedCombination() {
        ProotDistro.entries.forEach { distro ->
            distro.availableJavaVersions.forEach { version ->
                assertNotNull(
                    "${distro.displayName} 声明提供 $version，却造不出画像",
                    distro.profileFor(version)
                )
            }
        }
    }

    @Test
    fun profileAllCoversEveryLegalCombinationExactlyOnce() {
        val all = ProotContainerProfile.all
        val expected = ProotDistro.entries.sumOf { it.availableJavaVersions.size }
        assertEquals(expected, all.size)
        assertEquals("(发行版, 版本) 组合有重复", all.size, all.distinct().size)
        // 合法组合必须在，非法组合绝不能出现
        assertTrue(all.any { it.distro == ProotDistro.Focal && it.version == JavaVersion.Java8 })
        assertTrue(all.any { it.distro == ProotDistro.Jammy && it.version == JavaVersion.Java21 })
        assertTrue(
            "focal + Java 21 是非法组合，不该出现在画像清单里",
            all.none { it.distro == ProotDistro.Focal && it.version == JavaVersion.Java21 }
        )
        assertTrue(
            "jammy + Java 8 是非法组合，不该出现在画像清单里",
            all.none { it.distro == ProotDistro.Jammy && it.version == JavaVersion.Java8 }
        )
    }

    // ── JAVA_HOME 与 apt 包名推导 ───────────────────────────────

    @Test
    fun guestJavaHomeMatchesUbuntuMultiarchLayout() {
        // 这是与 PRoot 通用化之前硬编码的 /usr/lib/jvm/java-8-openjdk-arm64
        // 逐字符等价的推导，也是「装了却找不 java」这类事故的关键路径。
        assertEquals(
            "/usr/lib/jvm/java-8-openjdk-arm64",
            ProotContainerProfile(ProotDistro.Focal, JavaVersion.Java8).javaHomeInGuest
        )
        assertEquals(
            "/usr/lib/jvm/java-11-openjdk-arm64",
            ProotContainerProfile(ProotDistro.Jammy, JavaVersion.Java11).javaHomeInGuest
        )
        assertEquals(
            "/usr/lib/jvm/java-17-openjdk-arm64",
            ProotContainerProfile(ProotDistro.Jammy, JavaVersion.Java17).javaHomeInGuest
        )
        assertEquals(
            "/usr/lib/jvm/java-21-openjdk-arm64",
            ProotContainerProfile(ProotDistro.Jammy, JavaVersion.Java21).javaHomeInGuest
        )
    }

    @Test
    fun aptPackageMatchesTheJavaVersion() {
        val expected = mapOf(
            JavaVersion.Java8 to "openjdk-8-jdk",
            JavaVersion.Java11 to "openjdk-11-jdk",
            JavaVersion.Java17 to "openjdk-17-jdk",
            JavaVersion.Java21 to "openjdk-21-jdk"
        )
        ProotContainerProfile.all.forEach { profile ->
            assertEquals(
                "${profile.displayName} 的 apt 包名不对",
                expected[profile.version],
                profile.aptPackage
            )
        }
    }

    @Test
    fun guestBinaryAndLibraryCandidatesCoverBothUbuntuLayouts() {
        // Java 8 是老 layout（jre/ 子目录），11+ 是新 layout。
        // 少列一条 → 一半版本被判成「未安装」；多列一条不会有害。
        ProotContainerProfile.all.forEach { profile ->
            assertTrue(
                "${profile.displayName}: 缺少新 layout 的 java 路径",
                profile.guestJavaBinaryCandidates.any { it.endsWith("/bin/java") && !it.contains("/jre/") }
            )
            assertTrue(
                "${profile.displayName}: 缺少老 layout 的 java 路径",
                profile.guestJavaBinaryCandidates.any { it.contains("/jre/bin/java") }
            )
            assertTrue(
                "${profile.displayName}: 缺少新 layout 的 libjvm.so 路径",
                profile.guestJvmLibraryCandidates.any { it.endsWith("/lib/server/libjvm.so") }
            )
            assertTrue(
                "${profile.displayName}: 缺少老 layout 的 libjvm.so 路径",
                profile.guestJvmLibraryCandidates.any { it.contains("/jre/lib/") }
            )
        }
    }

    // ── 路径按发行版命名，不再编码 Java 版本 ─────────────────────

    @Test
    fun everyDistroUsesItsOwnConsistentlyNamedPaths() {
        // 路径只按「发行版」命名，与「里面装哪个 Java」完全解耦。
        // 旧命名 `java8-ubuntu-rootfs` 把发行版和 Java 版本混在一起，
        // 而同一个 rootfs 本就能装任意受支持的 JDK —— 现在不再有这种耦合，
        // 新增发行版也只需加一条 dirName，不含任何条件分支。
        val prefix = java.io.File("/prefix")
        val expected = mapOf(
            ProotDistro.Focal to "focal-rootfs",
            ProotDistro.Jammy to "jammy-rootfs"
        )
        assertEquals(
            "每个发行版都必须有明确的目录名，新增发行版别忘了配置",
            ProotDistro.entries.toSet(),
            expected.keys
        )
        expected.forEach { (distro, dirName) ->
            assertEquals(
                "/prefix/var/lib/mineserve/$dirName",
                ProotContainerProfile(distro, distro.availableJavaVersions.first())
                    .rootfsDir(prefix).invariantSeparatorsPath
            )
            assertEquals(
                "/prefix/var/lib/mineserve/$dirName.ready",
                ProotContainerProfile(distro, distro.availableJavaVersions.first())
                    .readyMarker(prefix).invariantSeparatorsPath
            )
        }
    }

    @Test
    fun legacyJava8PathsAreNoLongerUsed() {
        // 明确锁住「不再兼容旧路径」这个决策：
        // 升级前装好 Java 8 的用户会看到「未安装」，需要重装一次 —— 这是接受的代价。
        // 如果哪天有人重新引入旧路径判断，这条测试会失败并提醒他先确认决策已变更。
        val prefix = java.io.File("/prefix")
        val focal8 = ProotContainerProfile(ProotDistro.Focal, JavaVersion.Java8)
        assertNotEquals(
            "/prefix/var/lib/mineserve/java8-ubuntu-rootfs",
            focal8.rootfsDir(prefix).invariantSeparatorsPath
        )
        assertNotEquals(
            "/prefix/java-8-ubuntu-ready",
            focal8.readyMarker(prefix).invariantSeparatorsPath
        )
        assertNotEquals(
            "/prefix/tmp/java8-proot",
            focal8.prootTmpDir(prefix).invariantSeparatorsPath
        )
    }

    @Test
    fun sameDistroDifferentJavaVersionsShareOneRootfs() {
        // 同一个发行版下的所有 Java 版本共用**一个** rootfs 与**一个**标记 ——
        // 这正是「按发行版而非按 Java 版本命名」的意义：
        // focal 装 Java 8 之后，再装 Java 17 不需要重新下载 rootfs。
        val prefix = java.io.File("/prefix")
        val focal8 = ProotContainerProfile(ProotDistro.Focal, JavaVersion.Java8)
        val focal17 = ProotContainerProfile(ProotDistro.Focal, JavaVersion.Java17)
        assertEquals(focal8.rootfsDir(prefix), focal17.rootfsDir(prefix))
        assertEquals(focal8.readyMarker(prefix), focal17.readyMarker(prefix))
        assertEquals(focal8.prootTmpDir(prefix), focal17.prootTmpDir(prefix))
        // 但来宾 JAVA_HOME 必须不同，否则多版本会互相覆盖
        assertNotEquals(focal8.javaHomeInGuest, focal17.javaHomeInGuest)
    }

    @Test
    fun jammyUsesItsOwnPaths() {
        // 新发行版不得与 focal 共用 rootfs —— 否则一个覆盖另一个
        val prefix = java.io.File("/prefix")
        val jammy17 = ProotContainerProfile(ProotDistro.Jammy, JavaVersion.Java17)
        assertEquals(
            "/prefix/var/lib/mineserve/jammy-rootfs",
            jammy17.rootfsDir(prefix).invariantSeparatorsPath
        )

        val focal8 = ProotContainerProfile(ProotDistro.Focal, JavaVersion.Java8)
        assertTrue(
            "focal 与 jammy 不能共用 rootfs 目录",
            focal8.rootfsDir(prefix) != jammy17.rootfsDir(prefix)
        )
    }

    @Test
    fun resolvConfIsPerDistro() {
        // 两个容器抢同一个 resolv.conf 会互相覆盖 DNS 配置
        val tmp = java.io.File("/tmp")
        val focal8 = ProotContainerProfile(ProotDistro.Focal, JavaVersion.Java8)
        val jammy17 = ProotContainerProfile(ProotDistro.Jammy, JavaVersion.Java17)
        assertTrue(focal8.hostResolvConf(tmp) != jammy17.hostResolvConf(tmp))
    }

    // ── 构造函数守卫 ────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun constructingAnIllegalProfileThrows() {
        // 绕过 profileFor 手工拼一个装不上的组合，必须立刻炸掉，
        // 而不是等到用户点了「安装」才在容器里报 Unable to locate package。
        ProotContainerProfile(ProotDistro.Focal, JavaVersion.Java21)
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructingAJava25ProfileThrows() {
        ProotContainerProfile(ProotDistro.Jammy, JavaVersion.Java25)
    }

    // ── 来宾链路判定 ────────────────────────────────────────────

    @Test
    fun prootVendorAlwaysRoutesToTheGuestRuntime() {
        JavaVendor.Proot.versions.forEach { version ->
            assertTrue(
                "Proot.$version 必须走来宾链路",
                isProotGuestRuntime(JavaVendor.Proot, version)
            )
        }
    }

    @Test
    fun legacyTermuxJava8StillRoutesToTheGuestRuntime() {
        // 兼容性底线：PRoot 通用化之前，「Termux + Java 8」就是走容器的。
        // 老配置里存的就是这个组合，不能因为新增了 Proot 来源就把它改成宿主 JVM。
        assertTrue(isProotGuestRuntime(JavaVendor.Termux, JavaVersion.Java8))
    }

    @Test
    fun hostRuntimesDoNotRouteToTheGuestRuntime() {
        listOf(JavaVersion.Java11, JavaVersion.Java17, JavaVersion.Java21, JavaVersion.Java25).forEach {
            assertFalse("Termux.$it 应走宿主 JVM", isProotGuestRuntime(JavaVendor.Termux, it))
        }
        JavaVendor.Fcl.versions.forEach {
            assertFalse("FCL.$it 应走宿主 JVM", isProotGuestRuntime(JavaVendor.Fcl, it))
        }
    }

    // ── 配置层 ──────────────────────────────────────────────────

    @Test
    fun configDefaultDistroIsValidForTheDefaultSelection() {
        val cfg = McConfig()
        // 默认配置必须自洽：默认发行版要真的提供默认 Java 版本
        assertNotNull(
            "默认配置不自洽：${cfg.prootDistro.displayName} 不提供 ${cfg.selectedJavaVersion.displayName}",
            cfg.prootDistro.profileFor(cfg.selectedJavaVersion)
        )
    }

    @Test
    fun prootProfileReflectsDistroAndVersion() {
        val cfg = McConfig().copy(
            selectedJavaVendor = JavaVendor.Proot,
            selectedJavaVersion = JavaVersion.Java17,
            prootDistro = ProotDistro.Jammy
        )
        assertEquals(
            ProotContainerProfile(ProotDistro.Jammy, JavaVersion.Java17),
            cfg.prootProfile
        )
    }

    @Test
    fun prootProfileIsNullForUninstallableCombination() {
        val cfg = McConfig().copy(
            selectedJavaVendor = JavaVendor.Proot,
            selectedJavaVersion = JavaVersion.Java21,
            prootDistro = ProotDistro.Focal
        )
        // 不能静默换版本 —— 用户选的和他得到的必须是同一个东西
        assertNull(cfg.prootProfile)
    }

    @Test
    fun prootVendorNeverClaimsIpv6Support() {
        // guest 内是 glibc，兼容层是按 bionic 构建的 LD_PRELOAD 库，加载不了。
        // 这里如实为 false，避免 UI 给出错误的乐观提示。
        val cfg = McConfig().copy(
            selectedJavaVendor = JavaVendor.Proot,
            selectedJavaVersion = JavaVersion.Java17,
            jvmFlavor = JvmFlavor.PatchedJdk
        )
        assertFalse(cfg.ipv6ListenAvailable)
    }

    @Test
    fun vendorLogNamesRemainDistinct() {
        assertEquals(
            JavaVendor.entries.size,
            JavaVendor.entries.map { it.logName }.distinct().size
        )
        assertEquals("PRoot", JavaVendor.Proot.logName)
    }

    // ── 安装命令的版本校验正则 ─────────────────────────────────

    @Test
    fun java8VersionPatternMatchesWhatJava8ActuallyPrints() {
        // `java -version` 对 Java 8 输出的是 `1.8.0_xxx`（不是 `8.0.x`）。
        // 校验正则必须能匹配它，否则会出现「装成功了却报告校验失败」。
        assertTrue(
            "Java 8 的校验正则必须匹配 1.8.0_xxx",
            Regex("1\\.8\\.").containsMatchIn("openjdk version \"1.8.0_402\"")
        )
        // 反过来：Java 8 的正则**不能**匹配 Java 11 的输出，
        // 否则会在只装了 11 的容器里误判成「Java 8 已就绪」。
        assertFalse(
            "Java 8 的正则不该匹配 Java 11 的输出",
            Regex("1\\.8\\.").containsMatchIn("openjdk version \"11.0.22\"")
        )
    }

    @Test
    fun modernVersionPatternsMatchTheirOwnOutputOnly() {
        // 各版本的校验正则必须互不误伤：Java 17 的输出不能被 11 的正则匹配上，
        // 否则会出现「装的是 11 却报告 17 装好了」。
        val printed = mapOf(
            JavaVersion.Java11 to "openjdk version \"11.0.22\"",
            JavaVersion.Java17 to "openjdk version \"17.0.10\"",
            JavaVersion.Java21 to "openjdk version \"21.0.2\""
        )
        val patterns = mapOf(
            JavaVersion.Java11 to Regex("11\\."),
            JavaVersion.Java17 to Regex("17\\."),
            JavaVersion.Java21 to Regex("21\\.")
        )
        printed.forEach { (version, output) ->
            patterns.forEach { (otherVersion, pattern) ->
                if (version == otherVersion) {
                    assertTrue("$version 的输出应被自己的正则匹配", pattern.containsMatchIn(output))
                } else {
                    assertFalse(
                        "$otherVersion 的正则不该匹配 $version 的输出（$output）",
                        pattern.containsMatchIn(output)
                    )
                }
            }
        }
    }

    // ── 单 JDK 卸载：apt 包名必须精确到版本 ──────────────────────

    @Test
    fun aptPackageNamesAreDistinctPerJavaVersion() {
        // 卸载依赖这个包名**只命中目标版本**。如果两个 Java 版本解析出同一个包名，
        // 「卸载 Java 17」就会连带删掉另一个版本 —— 正是要避免的那种静默数据丢失。
        val packages = JavaVersion.entries.map { version ->
            // 用任一支持该版本的发行版取包名；包名本身只取决于 Java 版本
            val distro = ProotDistro.entries.firstOrNull { version in it.availableJavaVersions }
            distro?.let { ProotContainerProfile(it, version).aptPackage }
        }.filterNotNull()

        assertEquals(
            "同一个 apt 包名不能对应两个 Java 版本",
            packages.size,
            packages.distinct().size
        )
        // 包名必须带版本号，不能是元包（如 default-jdk），否则会把别的版本一起带进来
        packages.forEach { pkg ->
            assertTrue(
                "apt 包名 '$pkg' 必须显式包含 Java 主版本号，不能用元包",
                Regex("openjdk-\\d+-jdk").matches(pkg)
            )
        }
    }

    @Test
    fun uninstallingOneVersionDoesNotNameAnotherVersionsPackage() {
        // 逐条核对：卸载 focal+Java17 时脚本里出现的包名，
        // 不得等于 focal+Java8 / focal+Java11 的包名。
        val focal17 = ProotContainerProfile(ProotDistro.Focal, JavaVersion.Java17)
        val others = listOf(JavaVersion.Java8, JavaVersion.Java11)
            .map { ProotContainerProfile(ProotDistro.Focal, it).aptPackage }
        assertFalse(
            "卸载 java17 不能命中 ${others.joinToString()}",
            focal17.aptPackage in others
        )
        assertTrue(focal17.aptPackage == "openjdk-17-jdk")
    }

    @Test
    fun uninstallVerificationChecksEveryKnownJavaLayout() {
        // 卸载后的校验必须查**所有**候选路径。
        // Java 8 的包把 JRE 放在 jre/bin/java 下：只查新 layout 会漏掉它，
        // 于是「卸载成功」的判定会在 java 其实还在的时候通过。
        JavaVersion.entries.forEach { version ->
            val distro = ProotDistro.entries.firstOrNull { version in it.availableJavaVersions }
                ?: return@forEach
            val profile = ProotContainerProfile(distro, version)
            assertTrue(
                "${profile.displayName} 必须至少有一个 java 候选路径",
                profile.guestJavaBinaryCandidates.isNotEmpty()
            )
            profile.guestJavaBinaryCandidates.forEach { path ->
                assertTrue(
                    "候选路径 '$path' 必须以 /bin/java 结尾才可能是可执行文件",
                    path.endsWith("/bin/java")
                )
            }
        }
        // Java 8 必须同时包含两种 layout
        val focal8 = ProotContainerProfile(ProotDistro.Focal, JavaVersion.Java8)
        assertEquals(2, focal8.guestJavaBinaryCandidates.size)
        assertTrue(focal8.guestJavaBinaryCandidates.any { it.endsWith("jre/bin/java") })
    }
}
