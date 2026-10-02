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
}
