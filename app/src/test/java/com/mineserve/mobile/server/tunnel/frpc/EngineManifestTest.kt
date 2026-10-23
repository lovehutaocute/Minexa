package com.mineserve.mobile.server.tunnel.frpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 元数据解析测试。
 *
 * 样本 JSON 取自 EdgeCube 元数据的实际形状（`packages[key]/{urls,sha256,size,arch}`），
 * 而不是我臆想的字段名 —— 这份解析器的全部意义就是能与那套元数据对接。
 */
class EngineManifestTest {

    private fun parse(raw: String) = EngineManifestParser.parse(raw)

    @Test
    fun `解析完整元数据`() {
        val manifest = parse(
            """
            {
              "id": "frpc",
              "version": 71,
              "versionName": "0.71.0",
              "packages": {
                "aarch64": {
                  "urls": [
                    { "type": "direct", "url": "https://example.com/libfrpc-0.71.0-arm64.so" }
                  ],
                  "sha256": "abc123",
                  "size": 15204352,
                  "arch": ["aarch64"]
                }
              }
            }
            """.trimIndent()
        )!!

        assertEquals("frpc", manifest.id)
        assertEquals(71, manifest.version)
        assertEquals("0.71.0", manifest.displayVersion)

        val pkg = EngineManifestParser.packageFor(manifest, FrpcEngineConfig.PACKAGE_KEY)!!
        assertEquals(listOf("https://example.com/libfrpc-0.71.0-arm64.so"), pkg.directUrls)
        assertEquals("abc123", pkg.sha256)
        assertEquals(15204352L, pkg.size)
    }

    @Test
    fun `web 类型条目被滤掉`() {
        // web 是给人点开下载页看的，程序抓回来是 HTML —— 那会被当成引擎写进磁盘。
        val manifest = parse(
            """
            {
              "packages": {
                "aarch64": {
                  "urls": [
                    { "type": "web", "url": "https://example.com/download-page" },
                    { "type": "direct", "url": "https://cdn.example.com/libfrpc.so" }
                  ],
                  "sha256": "abc"
                }
              }
            }
            """.trimIndent()
        )!!

        val pkg = EngineManifestParser.packageFor(manifest, FrpcEngineConfig.PACKAGE_KEY)!!
        assertEquals(listOf("https://cdn.example.com/libfrpc.so"), pkg.directUrls)
    }

    @Test
    fun `type 缺失时按直链对待`() {
        // 老格式没有 type 字段，缺了就当直链，不能因此判定不可用。
        val pkg = parse(
            """{"packages":{"aarch64":{"urls":[{"url":"https://x/libfrpc.so"}],"sha256":"a"}}}"""
        )!!
            .let { EngineManifestParser.packageFor(it, FrpcEngineConfig.PACKAGE_KEY) }!!

        assertEquals(listOf("https://x/libfrpc.so"), pkg.directUrls)
    }

    @Test
    fun `缺 sha256 视为不可用`() {
        // 这批二进制会被 dlopen 进进程执行，链路里全是第三方反代。
        // 没有哈希就无从判断拿到的是不是原物，绝不能当成「跳过校验」放行。
        val manifest = parse(
            """{"packages":{"aarch64":{"urls":[{"url":"https://x/libfrpc.so"}]}}}"""
        )!!
        assertNull(EngineManifestParser.packageFor(manifest, FrpcEngineConfig.PACKAGE_KEY))
    }

    @Test
    fun `缺对应架构的包返回 null`() {
        val manifest = parse(
            """{"packages":{"armeabi-v7a":{"urls":[{"url":"https://x/a.so"}],"sha256":"a"}}}"""
        )!!
        assertNull(EngineManifestParser.packageFor(manifest, FrpcEngineConfig.PACKAGE_KEY))
    }

    @Test
    fun `没有直链的包不可用`() {
        val manifest = parse(
            """{"packages":{"aarch64":{"urls":[{"type":"web","url":"https://x/page"}],"sha256":"a"}}}"""
        )!!
        assertNull(EngineManifestParser.packageFor(manifest, FrpcEngineConfig.PACKAGE_KEY))
    }

    @Test
    fun `畸形 JSON 返回 null 而不抛异常`() {
        // 元数据经反代可能被截断；调用方要的是「换下一个源」，不是层层捕获异常。
        assertNull(parse("{ 这不是 json"))
        assertNull(parse(""))
    }

    @Test
    fun `多余字段被忽略`() {
        // 元数据由服务端演进，加字段不该让旧客户端整个失效。
        val manifest = parse(
            """
            {
              "id": "frpc",
              "versionName": "0.72.0",
              "brandNewField": { "nested": [1, 2, 3] },
              "packages": {
                "aarch64": {
                  "urls": [{ "type": "direct", "url": "https://x/libfrpc.so", "mirror": true }],
                  "sha256": "a",
                  "futureField": "whatever"
                }
              }
            }
            """.trimIndent()
        )!!

        assertEquals("0.72.0", manifest.displayVersion)
        assertTrue(
            EngineManifestParser.packageFor(manifest, FrpcEngineConfig.PACKAGE_KEY) != null
        )
    }

    @Test
    fun `displayVersion 缺 versionName 时回退到构建号`() {
        assertEquals("71", parse("""{"version":71}""")!!.displayVersion)
        // 空串不该显示成"版本 "，应回退。
        assertEquals("71", parse("""{"version":71,"versionName":""}""")!!.displayVersion)
    }

    // ── 目录清单（list.json）──

    @Test
    fun `目录清单里取出 frpc 的元数据地址`() {
        // 真实形状：顶层数组，每行一个 type，packages 是元数据地址列表。
        val raw = """
            [
              { "type": "java", "nameKey": "Java", "packages": [
                  "https://edgecubemc.com/app/ecpkg/metadata/java/jre17.json" ] },
              { "type": "frpc", "nameKey": "Frp", "packages": [
                  "https://edgecubemc.com/app/ecpkg/metadata/frp/frpc0.69.1.json" ] }
            ]
        """.trimIndent()

        assertEquals(
            listOf("https://edgecubemc.com/app/ecpkg/metadata/frp/frpc0.69.1.json"),
            EngineManifestParser.frpcMetadataUrls(raw)
        )
    }

    @Test
    fun `目录清单里没有 frpc 时返回空`() {
        val raw = """[{ "type": "java", "packages": ["https://x/jre17.json"] }]"""
        assertTrue(EngineManifestParser.frpcMetadataUrls(raw).isEmpty())
    }

    @Test
    fun `目录清单被回落成网页时返回空而不抛异常`() {
        // SPA 兜底会让不存在的路径返回 index.html（200）。这里不能炸，
        // 否则「首选地址失效」会被放大成「整个下载入口不可用」。
        assertTrue(EngineManifestParser.frpcMetadataUrls("<!doctype html><html></html>").isEmpty())
        assertTrue(EngineManifestParser.frpcMetadataUrls("").isEmpty())
    }
}
