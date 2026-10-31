package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 官方 BDS 版本目录的解析与比较。
 *
 * 这里的样例是 2026-10-30 实抓的官方响应结构（只保留了 linux/windows 四条与
 * serverJar 一条），锁住三件事：
 *  1. 稳定版与 Preview 靠 `downloadType` 区分，不能靠文件名或顺序；
 *  2. 与项目无关的平台（Windows、serverJar）必须被忽略而不是解析失败；
 *  3. 版本比较必须按数字段（`1.26.9` < `1.26.61`），字符串比较会判错。
 */
class BdsBuildCatalogTest {

    private val official = """
        {"result":{"links":[
          {"downloadType":"serverBedrockWindows","downloadUrl":"https://www.minecraft.net/bedrockdedicatedserver/bin-win/bedrock-server-1.26.61.1.zip"},
          {"downloadType":"serverBedrockLinux","downloadUrl":"https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-1.26.61.1.zip"},
          {"downloadType":"serverBedrockPreviewWindows","downloadUrl":"https://www.minecraft.net/bedrockdedicatedserver/bin-win-preview/bedrock-server-1.26.70.22.zip"},
          {"downloadType":"serverBedrockPreviewLinux","downloadUrl":"https://www.minecraft.net/bedrockdedicatedserver/bin-linux-preview/bedrock-server-1.26.70.22.zip"},
          {"downloadType":"serverJar","downloadUrl":"https://piston-data.mojang.com/v1/objects/xxx/server.jar"}
        ]}}
    """.trimIndent()

    @Test
    fun `解析出稳定的 linux 与 preview 两条通道`() {
        val builds = BdsBuildCatalog.parse(official)
        assertEquals(2, builds.size)
        val stable = builds.first { it.channel == BdsChannel.Stable }
        assertEquals("1.26.61.1", stable.version)
        assertTrue(stable.url.endsWith("bin-linux/bedrock-server-1.26.61.1.zip"))
        val preview = builds.first { it.channel == BdsChannel.Preview }
        assertEquals("1.26.70.22", preview.version)
        assertTrue(preview.url.contains("bin-linux-preview"))
    }

    @Test
    fun `windows 与 serverJar 不会被当成 BDS 构建`() {
        val builds = BdsBuildCatalog.parse(official)
        assertTrue(builds.none { it.url.contains("bin-win") })
        assertTrue(builds.none { it.url.endsWith(".jar") })
    }

    @Test
    fun `接口结构变化时返回空列表而不是抛异常`() {
        assertEquals(0, BdsBuildCatalog.parse("not json").size)
        assertEquals(0, BdsBuildCatalog.parse("""{"result":{}}""").size)
        assertEquals(0, BdsBuildCatalog.parse("""{"result":{"links":[]}}""").size)
    }

    @Test
    fun `版本比较按数字段而不是字符串`() {
        assertTrue(BdsBuildCatalog.compareVersions("1.26.9", "1.26.61") < 0)
        assertTrue(BdsBuildCatalog.compareVersions("1.26.70.22", "1.26.61.1") > 0)
        assertEquals(0, BdsBuildCatalog.compareVersions("1.26", "1.26.0"))
        assertTrue(BdsBuildCatalog.compareVersions("1.27", "1.26.99") > 0)
    }

    @Test
    fun `当前版本未知时视为有更新`() {
        assertTrue(BdsBuildCatalog.isNewer("1.26.61.1", null))
        assertTrue(BdsBuildCatalog.isNewer("1.26.61.1", ""))
        assertFalse(BdsBuildCatalog.isNewer(null, "1.26.61.1"))
    }

    @Test
    fun `同版本不提示更新_更高版本才提示`() {
        assertFalse(BdsBuildCatalog.isNewer("1.26.61.1", "1.26.61.1"))
        assertFalse(BdsBuildCatalog.isNewer("1.26.61.1", "1.26.70.22"))
        assertTrue(BdsBuildCatalog.isNewer("1.26.70.22", "1.26.61.1"))
    }

    @Test
    fun `状态卡片按通道给出更新结论`() {
        val builds = BdsBuildCatalog.parse(official)
        val fresh = BdsCoreStatus(
            dirName = "bds",
            installedVersion = "1.26.52.3",
            stable = builds.first { it.channel == BdsChannel.Stable },
            preview = builds.first { it.channel == BdsChannel.Preview },
            checked = true
        )
        assertTrue(fresh.hasUpdate(BdsChannel.Stable))
        assertTrue(fresh.hasUpdate(BdsChannel.Preview))
        assertFalse(fresh.isAheadOfStable)

        val upToDate = fresh.copy(installedVersion = "1.26.61.1")
        assertFalse(upToDate.hasUpdate(BdsChannel.Stable))
        // 稳定版已是最新，但 Preview 通道仍然更新 —— 界面要能单独提示
        assertTrue(upToDate.hasUpdate(BdsChannel.Preview))

        val onPreview = fresh.copy(installedVersion = "1.26.70.22")
        assertTrue(onPreview.isAheadOfStable)
        assertFalse(onPreview.hasUpdate(BdsChannel.Preview))
    }

    @Test
    fun `build 取不到通道时返回 null 而不是抛异常`() {
        val status = BdsCoreStatus(installedVersion = "1.26.61.1")
        assertNull(status.build(BdsChannel.Stable))
        assertFalse(status.hasUpdate(BdsChannel.Stable))
        assertNull(BdsCoreStatus().installedVersion)
        assertFalse(BdsCoreStatus().isAheadOfStable)
    }
}
