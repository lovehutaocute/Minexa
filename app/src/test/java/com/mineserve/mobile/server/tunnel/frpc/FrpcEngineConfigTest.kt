package com.mineserve.mobile.server.tunnel.frpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载源配置测试。
 *
 * 关心两件事：镜像前缀只该套在 GitHub 地址上（套在自建域名上会得到一个必然 404 的
 * 额外重试），以及原始地址必须排在第一位（否则自建服务器永远轮不到）。
 */
class FrpcEngineConfigTest {

    @Test
    fun `GitHub 地址展开成多镜像`() {
        val urls = FrpcEngineConfig.downloadUrls(
            "https://github.com/u/r/releases/download/v1/libfrpc.so"
        )

        // 原始地址优先：反代服务可能整体挂掉，自建/直连要能正常工作。
        assertEquals("https://github.com/u/r/releases/download/v1/libfrpc.so", urls.first())
        assertTrue(urls.size > 1)
        assertTrue(urls.any { it.startsWith("https://ghfast.top/") })
    }

    @Test
    fun `非 GitHub 地址不套镜像前缀`() {
        // 给自建域名套 ghfast.top/ 只会得到一个 404，
        // 让每次下载都白跑一轮回退。
        val urls = FrpcEngineConfig.downloadUrls("https://cdn.myown.host/libfrpc.so")
        assertEquals(listOf("https://cdn.myown.host/libfrpc.so"), urls)
    }

    @Test
    fun `raw_githubusercontent 也算 GitHub`() {
        val urls = FrpcEngineConfig.downloadUrls("https://raw.githubusercontent.com/u/r/main/x.so")
        assertTrue(urls.size > 1)
    }

    @Test
    fun `下载地址已配置`() {
        // 地址是编译期常量，指向 EdgeCube 的 frpc 分发站。
        assertTrue(FrpcEngineConfig.isConfigured)
        assertTrue(FrpcEngineConfig.metadataUrls().isNotEmpty())
    }

    @Test
    fun `元数据地址不套镜像前缀`() {
        // edgecubemc.com 是自建站，套上 ghfast.top/ 只会 404。
        // 原始地址必须排第一，且不该被加前缀。
        assertEquals(FrpcEngineConfig.METADATA_URL, FrpcEngineConfig.metadataUrls().first())
    }

    @Test
    fun `每个展开结果都不含重复项`() {
        val urls = FrpcEngineConfig.downloadUrls("https://github.com/u/r/x.so")
        assertEquals(urls.size, urls.distinct().size)
    }

    // ── 元数据地址的健壮性 ──

    @Test
    fun `元数据地址带版本号且指向 frp 目录`() {
        // edgecubemc.com 是 SPA：路径写错不会 404，而是回落成网页（200 + text/html），
        // 于是只会在「元数据格式无法识别」这里露头。所以地址本身要钉住。
        assertTrue(FrpcEngineConfig.METADATA_URL.contains("/ecpkg/metadata/frp/"))
        assertTrue(FrpcEngineConfig.METADATA_URL.endsWith(".json"))
    }

    @Test
    fun `目录清单地址可用作自动发现`() {
        assertTrue(FrpcEngineConfig.catalogUrls().isNotEmpty())
        assertEquals(FrpcEngineConfig.CATALOG_URL, FrpcEngineConfig.catalogUrls().first())
        assertTrue(FrpcEngineConfig.CATALOG_URL.endsWith("/ecpkg/list.json"))
    }

    @Test
    fun `Gitee 源被提到 GitHub 之前`() {
        // 元数据里 GitHub 排第一，而 GitHub 直连在部分网络下是静默超时：
        // 不调序就得白等满 15 秒才轮到本来能秒下的 Gitee。
        val ordered = FrpcEngineConfig.preferDomesticFirst(
            listOf(
                "https://github.com/u/r/releases/download/v1/x.ecpkg",
                "https://gitee.com/u/r/releases/download/v1/x.ecpkg",
                "https://ghfast.top/https://github.com/u/r/x.ecpkg"
            )
        )
        assertTrue(ordered.first().contains("gitee.com"))
    }

    @Test
    fun `调序是稳定的且不丢地址`() {
        val input = listOf(
            "https://github.com/a/x.ecpkg",
            "https://example.com/x.ecpkg"
        )
        val ordered = FrpcEngineConfig.preferDomesticFirst(input)
        // 没有国内源时顺序原样保留，一个地址都不能丢。
        assertEquals(input, ordered)
        val withGitee = input + "https://gitee.com/b/y"
        assertEquals(withGitee.size, FrpcEngineConfig.preferDomesticFirst(withGitee).size)
    }
}
