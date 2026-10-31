package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BDS 运行环境的「来源清单」与「阶段进度」。
 *
 * 这些是纯数据/纯函数，锁住的是几条容易被后续维护改坏的性质：
 *  - 来源列表必须真的多源，且每个来源都指向同一个文件名（否则换源就成了换版本）；
 *  - `SHA256SUMS` 的地址必须由归档地址推导，不能写死某一个镜像；
 *  - 哈希解析必须同时认 `哈希 文件名` 与 `哈希 *文件名`（两种镜像写法都真实存在）；
 *  - 阶段进度区间必须首尾相接，否则界面上进度条会跳。
 */
class BdsEnvSourcesTest {

    @Test
    fun ubuntuSourcesAreMultipleAndPointToTheSameArchive() {
        ProotDistro.entries.forEach { distro ->
            val sources = UbuntuImageSources.archiveSources(distro)
            assertTrue("${distro.name} 至少要有多于一个镜像", sources.size >= 4)
            val archive = UbuntuImageSources.archiveName(distro)
            sources.forEach { source ->
                assertTrue("${source.label} 必须是 https", source.url.startsWith("https://"))
                assertTrue("${source.label} 的文件名必须是 $archive", source.url.endsWith("/$archive"))
                assertTrue(source.url.contains(UbuntuImageSources.release(distro)))
            }
            // 不同镜像的目录不同 → 换源才有意义
            assertEquals(sources.size, sources.map { it.url }.distinct().size)
        }
    }

    @Test
    fun sha256SumsUrlIsDerivedFromArchiveUrl() {
        val archive = UbuntuImageSources.archiveSources(ProotDistro.Jammy).first()
        val sums = UbuntuImageSources.sha256SumsUrl(archive)
        assertEquals(
            archive.url.substringBeforeLast('/') + "/SHA256SUMS",
            sums
        )
        assertTrue(sums.endsWith("/SHA256SUMS"))
        // 不能把归档文件名留在里面
        assertTrue(!sums.contains("ubuntu-base-"))
    }

    @Test
    fun expectedSha256AcceptsBothSumFileFormats() {
        val name = "ubuntu-base-22.04.5-base-arm64.tar.gz"
        val hash = "a".repeat(64)
        val text = """
            ${"b".repeat(64)}  ubuntu-base-22.04.5-base-amd64.tar.gz
            $hash  $name
            ${"c".repeat(64)} *ubuntu-base-22.04.5-minimal-arm64.tar.gz
        """.trimIndent()
        assertEquals(hash, UbuntuImageSources.expectedSha256(text, name))

        // 二进制模式（星号前缀）
        val binaryMode = "${"d".repeat(64)} *$name"
        assertEquals("d".repeat(64), UbuntuImageSources.expectedSha256(binaryMode, name))

        // 取不到 / 格式不对 → null，而不是瞎猜
        assertNull(UbuntuImageSources.expectedSha256(text, "not-there.tar.gz"))
        assertNull(UbuntuImageSources.expectedSha256("short-hash  $name", name))
    }

    @Test
    fun box64SourcesCoverHttpsHttpAndLegacyHost() {
        val sources = BdsSources.box64Deb
        assertTrue("box64 至少要有两个入口", sources.size >= 2)
        assertTrue(sources.any { it.url.startsWith("https://") })
        assertTrue(sources.any { it.url.startsWith("http://") })
        assertEquals(sources.size, sources.map { it.url }.distinct().size)

        // 光有多个 URL 不够：如果全指向同一个域名，域名被墙时全军覆没。
        // 至少要有两拨互不相干的主机（rfort.net 与 jsDelivr 的 CDN 边缘）。
        val hosts = sources.map { it.url.substringAfter("://").substringBefore("/") }
        assertTrue(
            "box64 来源必须覆盖至少两个独立主机，实际：$hosts",
            hosts.any { it.contains("rfort.net") } && hosts.any { it.contains("jsdelivr.net") }
        )

        val index = BdsSources.indexUrl(sources.first())
        assertTrue(index.endsWith("/Packages"))
        val deb = BdsSources.debUrl(sources.first(), "box64_1.0_arm64.deb")
        assertTrue(deb.endsWith("/box64_1.0_arm64.deb"))
        assertTrue(deb.startsWith(sources.first().url))
    }

    /** 每个源的 URL 都以 `/` 结尾，否则 `url + 文件名` 会拼出 `debianbox64...` */
    @Test
    fun everyBox64SourceBaseEndsWithSlash() {
        BdsSources.box64Deb.forEach { source ->
            assertTrue("${source.label} 的地址未以 / 结尾：${source.url}", source.url.endsWith("/"))
        }
    }

    @Test
    fun stageRangesAreContiguous() {
        val stages = BdsEnvStage.entries
        assertEquals(0, stages.first().range.first)
        assertEquals(100, stages.last().range.last)
        stages.zipWithNext().forEach { (a, b) ->
            assertEquals("阶段之间不能有缝", a.range.last, b.range.first)
        }
    }
}
