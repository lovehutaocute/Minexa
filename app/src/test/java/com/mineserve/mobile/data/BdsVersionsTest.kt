package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号比较、段数容错与直链候选。
 *
 * 这几条规则每错一条都有具体的可见后果：
 *  - 字符串比较 → 列表把 `1.26.9` 排在 `1.26.61` 前面；
 *  - 整串相等 → 「已装 1.21.84.1」永远匹配不到清单里的 `1.21.84`，
 *    界面一直提示「可更新到同一版本」；
 *  - 直链只认一个主机 → 1.18 及更早的包全部 404。
 */
class BdsVersionsTest {

    @Test
    fun numericCompareNotLexicographic() {
        assertTrue(BdsVersions.compare("1.26.61", "1.26.9") > 0)
        assertTrue(BdsVersions.compare("1.21.84.1", "1.21.84") > 0)
        assertEquals(0, BdsVersions.compare("1.26.62", "1.26.62.0"))
    }

    @Test
    fun sameReleaseIgnoresBuildSegment() {
        assertTrue(BdsVersions.sameRelease("1.21.84", "1.21.84.1"))
        assertTrue(BdsVersions.sameRelease("1.20.51.01", "1.20.51"))
        // 预览版不参与「前三段归一」：见 previewBuildsKeepDistinctIdentity
        assertFalse(BdsVersions.sameRelease("1.26.70-preview.23", "1.26.70"))
        assertFalse(BdsVersions.sameRelease("1.21.84", "1.21.83.1"))
        assertFalse(BdsVersions.sameRelease("", "1.21.84"))
    }

    @Test
    fun releaseKeyTrimsToThreeSegments() {
        assertEquals("1.21.84", BdsVersions.releaseKey("1.21.84.1"))
        assertEquals("1.21.84", BdsVersions.releaseKey("1.21.84"))
    }

    /**
     * 预览构建必须各自独立。
     *
     * 曾经的实现按「前三段」归一，`1.26.70-preview.23` / `.30` / `.29` 全部塌成
     * 同一条键 —— 清单里 93 个预览版只剩 16 条，用户根本找不到想装的 build。
     */
    @Test
    fun previewBuildsKeepDistinctIdentity() {
        assertNotEquals(
            BdsVersions.releaseKey("1.26.70-preview.23"),
            BdsVersions.releaseKey("1.26.70-preview.30")
        )
        assertFalse(BdsVersions.sameRelease("1.26.70-preview.23", "1.26.70-preview.30"))
        assertFalse(BdsVersions.sameRelease("1.26.70-preview.23", "1.26.70"))
        assertTrue(BdsVersions.sameRelease("1.26.70-preview.23", "1.26.70-preview.23"))
    }

    @Test
    fun remoteEntriesAdvertiseExpectedDigest() {
        val remote = BdsVersionEntry("1.21.84", BdsChannel.Stable, digestExpected = true)
        assertFalse(remote.hasDigest)
        // 上游有摘要、只是没预取 —— 界面仍应显示「SHA-256」，否则等于谎称没校验
        assertTrue(remote.strictDigest)
        val seed = BdsVersionEntry("1.16.100.4", BdsChannel.Stable)
        assertFalse(seed.strictDigest)
    }

    @Test
    fun candidatesNeverPointAtRetiredCdn() {
        // azureedge 已停用（2026-11 实测连接超时），候选里不能再出现它
        val old = BdsVersionEntry(
            version = "1.16.100.04",
            channel = BdsChannel.Stable,
            url = "https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-1.16.100.04.zip"
        )
        val urls = BdsVersions.candidates(old).map { it.url }
        assertTrue(urls.none { it.contains("azureedge") })
        // 清单给的直链优先级最高
        assertTrue(urls.first().contains("bin-linux/bedrock-server-1.16.100.04.zip"))
    }

    @Test
    fun candidatesTryZeroPaddingVariants() {
        val entry = BdsVersionEntry(
            version = "1.21.51.2",
            channel = BdsChannel.Stable,
            url = "https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-1.21.51.02.zip"
        )
        val urls = BdsVersions.candidates(entry).map { it.url }
        // 官方老包的构建号常写成两位（1.21.51.02），清单里记成 .2 时也要能试到
        assertTrue(urls.any { it.endsWith("bedrock-server-1.21.51.2.zip") })
        assertTrue(urls.any { it.endsWith("bedrock-server-1.21.51.02.zip") })
    }

    @Test
    fun candidatesFallBackToNamingRuleWithoutUrl() {
        val entry = BdsVersionEntry("1.19.83", BdsChannel.Stable, url = null)
        val urls = BdsVersions.candidates(entry).map { it.url }
        assertTrue(urls.isNotEmpty())
        assertTrue(urls.first().endsWith("bedrock-server-1.19.83.zip"))
        assertTrue(urls.first().contains("/bedrockdedicatedserver/bin-linux/"))
    }

    @Test
    fun previewUrlUsesPreviewPathAndPlainBuildNumber() {
        // 清单键 1.26.70-preview.23 → 官方文件名 bedrock-server-1.26.70.23.zip
        val url = BdsVersions.buildUrl("1.26.70-preview.23", BdsChannel.Preview)
        assertEquals(
            "https://www.minecraft.net/bedrockdedicatedserver/bin-linux-preview/bedrock-server-1.26.70.23.zip",
            url
        )
    }

    @Test
    fun oldVersionsUseCurrentCdnToo() {
        // 实测：1.13.3.0 / 1.7.0.13 这类老包在 www.minecraft.net 上同样可下
        val url = BdsVersions.buildUrl("1.16.100.04", BdsChannel.Stable)
        assertTrue(url.startsWith("https://www.minecraft.net/bedrockdedicatedserver/bin-linux/"))
        assertEquals("https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-1.16.100.04.zip", url)
    }

    @Test
    fun bundledManifestParses() {
        val json = """
            {"versions":[
              {"v":"1.20.51.1","c":"stable","u":"https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-1.20.51.01.zip"},
              {"v":"1.21.100-preview.2","c":"preview","u":"https://www.minecraft.net/bedrockdedicatedserver/bin-linux-preview/bedrock-server-1.21.100.2.zip","s":"${"a".repeat(64)}"},
              {"v":"","u":"https://example.com/x.zip"}
            ]}
        """.trimIndent()
        val entries = BdsVersionCatalogLoader.parseBundled(json)
        assertEquals(2, entries.size)
        assertEquals(BdsChannel.Stable, entries[0].channel)
        assertEquals(BdsChannel.Preview, entries[1].channel)
        assertTrue(entries[1].hasDigest)
        assertFalse(entries[0].hasDigest)
    }

    @Test
    fun remoteIndexParsesBothChannels() {
        val json = """
            {"release":{"latest":"1.26.62","versions":["1.26.62","1.26.61"]},
             "preview":{"latest":"1.26.70-preview.23","versions":["1.26.70-preview.23"]}}
        """.trimIndent()
        val entries = BdsVersionCatalogLoader.parseRemoteIndex(json)
        assertEquals(3, entries.size)
        assertEquals(2, entries.count { it.channel == BdsChannel.Stable })
        assertEquals("1.26.62", entries.first().remoteKey)
    }

    @Test
    fun remoteMetadataPullsLinuxDigest() {
        val json = """
            {"version":"1.21.84","binary":{
              "windows":{"url":"https://www.minecraft.net/bedrockdedicatedserver/bin-win/bedrock-server-1.21.84.1.zip","sha256":"${"b".repeat(64)}"},
              "linux":{"url":"https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-1.21.84.1.zip","sha256":"${"c".repeat(64)}"}}}
        """.trimIndent()
        val parsed = BdsVersionCatalogLoader.parseRemoteMetadata(json)
        assertNotNull(parsed)
        assertTrue(parsed!!.first.contains("bin-linux"))
        assertEquals("c".repeat(64), parsed.second)
    }

    @Test
    fun catalogFindMatchesAcrossSegmentCounts() {
        val catalog = BdsVersionCatalog(
            entries = listOf(
                BdsVersionEntry("1.21.84", BdsChannel.Stable),
                BdsVersionEntry("1.21.83", BdsChannel.Stable)
            ),
            fetchedAtMs = 0L
        )
        assertEquals("1.21.84", catalog.find("1.21.84.1")?.version)
        assertEquals(null, catalog.find("1.19.0"))
    }

    @Test
    fun installableRequiresEnoughSegments() {
        // 只有两段时既拼不出文件名也没有直链：不能显示成可选
        assertFalse(BdsVersionEntry("1.21", BdsChannel.Stable).installable)
        assertTrue(BdsVersionEntry("1.21.84", BdsChannel.Stable).installable)
    }
}
