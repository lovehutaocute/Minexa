package com.mineserve.mobile.data

import com.mineserve.mobile.runtime.FCL_RUNTIME_ROOT
import com.mineserve.mobile.runtime.FclJrePackage
import com.mineserve.mobile.runtime.FclJrePackages
import com.mineserve.mobile.runtime.fclRuntimeRelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「两套 JDK」目录与包清单的一致性守卫。
 *
 * ## 为什么需要它（真实事故）
 * 分组列表、启动卡片的运行环境标签、启动链路选哪套 JDK，全都靠
 * [JavaVendor.versions] 与 [JavaRuntime] 这一组数据。这组数据一旦与
 * [FclJrePackages]（真实可下载的包）脱节，症状会是：
 *
 * - 界面上列出一个永远下不下来的版本（点了就一个 sha256 校验失败）；
 * - 或者上游发布了某个版本、清单里却有，而 `vendor.versions` 里没有，
 *   于是用户根本选不到；
 * - 或者 `Fcl.Java8` 这种「来源 + 版本」组合被写进 config —— 启动时必然
 *   找不到 java，而日志只会说一句「Java 不可用」。
 *
 * 这些都是**纯静态事实**，不需要设备就能守住。
 */
class JavaRuntimeCatalogTest {

    // ── 来源 × 版本 ────────────────────────────────────────────

    @Test
    fun termuxVendorOffersEveryVersionExceptJava8() {
        // Java 8 从 Termux 分组里移除了：它在 Termux 下同样跑在 proot 容器里，
        // 而那个容器现在归「PRoot 容器」分组。
        // 两个分组都列它 = 同一个容器列两遍，用户会以为有两份而重复安装，
        // 或看不懂磁盘占用为何翻倍。
        assertEquals(
            listOf(JavaVersion.Java11, JavaVersion.Java17, JavaVersion.Java21, JavaVersion.Java25),
            JavaVendor.Termux.versions
        )
        assertFalse(
            "Java 8 不应出现在 Termux 分组（它属于 PRoot 容器分组）",
            JavaVersion.Java8 in JavaVendor.Termux.versions
        )
    }

    @Test
    fun java8IsOfferedExactlyOnceAcrossAllVendors() {
        // 「同一份运行时只在一个分组里出现」是本轮修正的核心不变量。
        // 若将来又有人把 Java 8 加回 Termux，这条会立刻失败。
        val offeringJava8 = JavaVendor.entries.filter { JavaVersion.Java8 in it.versions }
        assertEquals(
            "Java 8 必须且只能在一个来源下出现，实际：$offeringJava8",
            listOf(JavaVendor.Proot),
            offeringJava8
        )
    }

    @Test
    fun everyVersionThatRunsInAGuestIsOfferedUnderProot() {
        // 来宾链路（容器内运行的版本）必须在 Proot 分组里可选中，
        // 否则用户装上了却选不到。这是上一轮「Java 8 藏在 Termux 下」
        // 问题的通用形式。
        val guestVersions = JavaVendor.entries
            .flatMap { vendor -> vendor.versions.map { vendor to it } }
            .filter { (vendor, version) -> isProotGuestRuntime(vendor, version) }
            .map { (_, version) -> version }
            .distinct()
        guestVersions.forEach { version ->
            assertTrue(
                "$version 走来宾容器链路，但 Proot 分组里没有它 —— 用户装得上却选不到",
                version in JavaVendor.Proot.versions
            )
        }
    }

    // ── 历史配置归一 ──────────────────────────────────────────

    @Test
    fun legacyTermuxJava8ConfigIsNormalizedToProotFocal() {
        // 老配置里 Java 8 存在 Termux 名下。归一后它必须落到 Proot + focal，
        // 否则启动抽屉会出现「发行版 jammy + 版本 Java8」这种页面内自相矛盾的组合
        // （jammy 不提供 Java 8，版本一栏会是空白）。
        val legacy = McConfig(
            selectedJavaVendor = JavaVendor.Termux,
            selectedJavaVersion = JavaVersion.Java8
        ).normalizeProotRuntime()
        assertEquals(JavaVendor.Proot, legacy.selectedJavaVendor)
        assertEquals(JavaVersion.Java8, legacy.selectedJavaVersion)
        assertEquals(LegacyJava8Distro, legacy.prootDistro)
        // 归一后必须是「该发行版确实提供这个版本」的合法组合
        assertTrue(
            "归一后的组合 ${legacy.selectedJavaVendor}.${legacy.selectedJavaVersion} 在所选发行版上装不上",
            legacy.selectedJavaVersion in legacy.prootDistro.availableJavaVersions
        )
    }

    @Test
    fun normalizationKeepsPointingAtTheSameContainer() {
        // 归一**不能**改变实际运行的容器：老用户已装的 Java 8 若被指到另一个 rootfs，
        // 会被判成「未安装」而要求重新下载几百兆。这里锁定两者一致。
        val legacy = McConfig(
            selectedJavaVendor = JavaVendor.Termux,
            selectedJavaVersion = JavaVersion.Java8
        ).normalizeProotRuntime()
        assertTrue(
            "归一后的画像必须仍落在 focal（与 TermuxRuntime.legacyJava8Profile 同一容器）",
            isProotGuestRuntime(legacy.selectedJavaVendor, legacy.selectedJavaVersion)
        )
        assertNotNull(
            "归一后的组合必须能解析出容器画像",
            ProotContainerProfile.find(legacy.prootDistro, legacy.selectedJavaVersion)
        )
    }

    @Test
    fun normalizationLeavesOtherConfigurationsUntouched() {
        // 归一必须只动「Termux + Java 8」这一种形态。
        // 误伤其他组合会把用户正常的选择改掉（例如把 Proot 显式选的 jammy 改成 focal）。
        val keep = listOf(
            JavaRuntime(JavaVendor.Termux, JavaVersion.Java17),
            JavaRuntime(JavaVendor.Termux, JavaVersion.Java25),
            JavaRuntime(JavaVendor.Fcl, JavaVersion.Java21),
            JavaRuntime(JavaVendor.Proot, JavaVersion.Java21)
        )
        keep.forEach { runtime ->
            val before = McConfig(
                selectedJavaVendor = runtime.vendor,
                selectedJavaVersion = runtime.version,
                prootDistro = ProotDistro.Jammy
            )
            val after = before.normalizeProotRuntime()
            assertEquals("$runtime 不应被归一改动：来源", before.selectedJavaVendor, after.selectedJavaVendor)
            assertEquals("$runtime 不应被归一改动：版本", before.selectedJavaVersion, after.selectedJavaVersion)
            assertEquals("$runtime 不应被归一改动：发行版", before.prootDistro, after.prootDistro)
        }
    }

    @Test
    fun normalizationIsIdempotent() {
        // 归一在每次读盘时都会跑，必须幂等，否则重复读会把字段越改越偏。
        val once = McConfig(
            selectedJavaVendor = JavaVendor.Termux,
            selectedJavaVersion = JavaVersion.Java8
        ).normalizeProotRuntime()
        val twice = once.normalizeProotRuntime()
        assertEquals(once, twice)
    }

    @Test
    fun fclVendorOffersExactlyTheVersionsUpstreamPublishes() {
        // 上游（EdgeCubePackage-JRE）只打包 17/21/25；虚构 8/11 等于给一个必然失败的选项
        assertEquals(
            listOf(JavaVersion.Java17, JavaVersion.Java21, JavaVersion.Java25),
            JavaVendor.Fcl.versions
        )
    }

    @Test
    fun everyAdvertisedFclVersionHasADownloadPackage() {
        JavaVendor.Fcl.versions.forEach { version ->
            assertNotNull(
                "FCL 来源列出了 $version，但 FclJrePackages 里没有对应的包",
                FclJrePackages.forVersion(version)
            )
        }
    }

    @Test
    fun fclPackagesNeverReferenceAVersionTheVendorDoesNotOffer() {
        FclJrePackages.all.forEach { pkg ->
            assertTrue(
                "${pkg.fileName} 不在 JavaVendor.Fcl.versions 里，界面永远不会显示它",
                pkg.version in JavaVendor.Fcl.versions
            )
        }
    }

    @Test
    fun vendorVersionsAreNonEmptyAndDuplicateFree() {
        JavaVendor.entries.forEach { vendor ->
            val versions = vendor.versions
            assertTrue("$vendor 没有任何可用版本", versions.isNotEmpty())
            assertEquals("$vendor 的版本列表有重复", versions.size, versions.distinct().size)
        }
    }

    @Test
    fun runtimeAllCoversEveryVendorVersionPairExactlyOnce() {
        val all = JavaRuntime.all
        val expected = JavaVendor.entries.sumOf { it.versions.size }
        assertEquals(expected, all.size)
        assertEquals("(来源, 版本) 组合有重复", all.size, all.distinct().size)
        // FCL 没有 8/11，这两个组合必须不存在
        assertFalse(JavaRuntime(JavaVendor.Fcl, JavaVersion.Java8) in all)
        assertFalse(JavaRuntime(JavaVendor.Fcl, JavaVersion.Java11) in all)
        // 同一个版本号在两套来源下必须是两个不同的对象
        assertTrue(JavaRuntime(JavaVendor.Termux, JavaVersion.Java17) != JavaRuntime(JavaVendor.Fcl, JavaVersion.Java17))
    }

    @Test
    fun defaultRuntimeIsWithinTheTermuxVendor() {
        assertTrue(JavaRuntime.Default.version in JavaRuntime.Default.vendor.versions)
        assertEquals(JavaRuntime(JavaVendor.Termux, JavaVersion.Java17), JavaRuntime.Default)
    }

    @Test
    fun vendorLogNamesAreDistinct() {
        assertEquals(JavaVendor.entries.size, JavaVendor.entries.map { it.logName }.distinct().size)
    }

    // ── 包清单字段完整性 ───────────────────────────────────────

    @Test
    fun packageMetadataIsWellFormed() {
        FclJrePackages.all.forEach { pkg -> assertWellFormed(pkg) }
    }

    private fun assertWellFormed(pkg: FclJrePackage) {
        val who = pkg.version.name
        assertTrue("$who: sizeBytes 必须为正", pkg.sizeBytes > 0)
        assertTrue("$who: releaseTag 不能为空", pkg.releaseTag.isNotBlank())
        assertTrue("$who: sha256 必须是 64 位十六进制", pkg.sha256.matches(Regex("[0-9a-f]{64}")))
        // 包名与主版本号必须自洽：jre21-aarch64.ecpkg 对应 Java21
        assertEquals("$who: 包名与版本对不上", "jre${pkg.majorVersion}-aarch64.ecpkg", pkg.fileName)
        assertEquals("$who: majorVersion 解析错误", pkg.version.name.removePrefix("Java"), pkg.majorVersion.toString())
        assertTrue("$who: 至少要有一个下载源", pkg.urls.isNotEmpty())
        assertTrue("$who: 必须有 https 源（明文 http 走另一条下载通道，这里不该出现）",
            pkg.urls.all { it.startsWith("https://") })
        assertTrue("$who: 换源重试要求所有源指向同一个文件名",
            pkg.urls.all { it.endsWith(pkg.fileName) })
        assertEquals("$who: 下载源不该重复", pkg.urls.size, pkg.urls.distinct().size)
        // Gitee 优先（国内直连快），GitHub 兜底
        assertTrue("$who: 缺少 Gitee 源", pkg.urls.any { it.contains("gitee.com") })
        assertTrue("$who: 缺少 GitHub 兜底源", pkg.urls.any { it.contains("github.com") })
        // 换源重试的 sha256 相同，否则「换个源再试」会直接变成校验失败
        assertEquals("$who: 同一版本的多个源必须指向同一份文件",
            pkg.urls.map { it.substringAfterLast('/') }.distinct().size, 1)
    }

    // ── .ecpkg 剥层规则 ────────────────────────────────────────

    @Test
    fun stripsOnlyTheRuntimeRootComponent() {
        assertEquals("bin/java", fclRuntimeRelativePath("$FCL_RUNTIME_ROOT/bin/java"))
        assertEquals("lib/libjli.so", fclRuntimeRelativePath("$FCL_RUNTIME_ROOT/lib/libjli.so"))
        assertEquals("release", fclRuntimeRelativePath("$FCL_RUNTIME_ROOT/release"))
        // 前导斜杠（部分 zip 工具会写出来）要归一
        assertEquals("bin/java", fclRuntimeRelativePath("/$FCL_RUNTIME_ROOT/bin/java"))
    }

    @Test
    fun rejectsEverythingOutsideTheRuntimeRoot() {
        // 顶层清单文件：落进 JDK 根目录会污染 java.home
        assertNull(fclRuntimeRelativePath("edgecube-package.json"))
        assertNull(fclRuntimeRelativePath("META-INF/MANIFEST.MF"))
        // 光有目录层没有内容
        assertNull(fclRuntimeRelativePath(FCL_RUNTIME_ROOT))
        assertNull(fclRuntimeRelativePath("$FCL_RUNTIME_ROOT/"))
        assertNull(fclRuntimeRelativePath(""))
    }

    @Test
    fun rejectsSiblingDirectoriesSharingThePrefix() {
        // 这就是不能用 startsWith("aarch64") 的原因：aarch64-old 是兄弟目录，不是它
        assertNull(fclRuntimeRelativePath("aarch64-old/bin/java"))
        assertNull(fclRuntimeRelativePath("aarch64.bak/bin/java"))
        assertNull(fclRuntimeRelativePath("aarch64x/bin/java"))
    }

    // ── IPv6 能力（决定 server-ip 能不能写 ::）────────────────

    @Test
    fun fclRuntimeAlwaysReportsIpv6Capable() {
        // FCL 那套 libnet.so 编译期就跳过了 /proc/net/if_inet6 检查，与兼容层无关
        JavaVendor.Fcl.versions.forEach { version ->
            val cfg = baseConfig().copy(
                selectedJavaVendor = JavaVendor.Fcl,
                selectedJavaVersion = version,
                jvmFlavor = JvmFlavor.TermuxJdk
            )
            assertTrue("FCL.$version 应当始终支持 IPv6 监听", cfg.ipv6ListenAvailable)
        }
    }

    @Test
    fun termuxRuntimeDependsOnTheCompatibilityLayer() {
        val patched = baseConfig().copy(
            selectedJavaVendor = JavaVendor.Termux,
            jvmFlavor = JvmFlavor.PatchedJdk
        )
        val stock = baseConfig().copy(
            selectedJavaVendor = JavaVendor.Termux,
            jvmFlavor = JvmFlavor.TermuxJdk
        )
        assertTrue(patched.ipv6ListenAvailable)
        assertFalse(stock.ipv6ListenAvailable)
    }

    @Test
    fun patchedJdkIsTheDefaultFlavor() {
        // 默认必须是 PatchedJdk：Termux 来源不挂兼容层时 server-ip=:: 必崩
        assertEquals(JvmFlavor.PatchedJdk, baseConfig().jvmFlavor)
    }

    @Test
    fun selectedJavaRuntimeTracksBothFields() {
        val cfg = baseConfig().copy(
            selectedJavaVendor = JavaVendor.Fcl,
            selectedJavaVersion = JavaVersion.Java25
        )
        assertEquals(JavaRuntime(JavaVendor.Fcl, JavaVersion.Java25), cfg.selectedJavaRuntime)
    }

    private fun baseConfig(): McConfig = McConfig()
}
