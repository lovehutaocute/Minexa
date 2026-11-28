package com.mineserve.mobile.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Velocity build 选择（PaperMC v3）。
 *
 * 这段逻辑之前是「按对象读 `builds` + 取 `lastOrNull()` + 读 `application`」，
 * 三处全错，表现为 Velocity 核心 100% 下载失败。断言用**真实响应形状**钉住三点：
 * 顶层是数组、下载键是 `server:default`、数组按 id 倒序。
 */
class VelocityBuildSelectionTest {

    private fun json(body: String) = Json.parseToJsonElement(body.trimIndent())

    @Test
    fun `真实响应是数组且下载键为 server_default`() {
        // 2026-11 实测 https://fill.papermc.io/v3/projects/velocity/versions/4.3.1/builds
        val builds = parseVelocityBuilds(
            json(
                """
                [
                  {
                    "id": 45,
                    "time": "2025-11-20T12:00:00Z",
                    "channel": "STABLE",
                    "downloads": {
                      "server:default": {
                        "name": "velocity-4.3.1-45.jar",
                        "checksums": { "sha256": "3f786850e387550fdab836ed7e6dc881de23001b" },
                        "size": 22000000,
                        "url": "https://fill-data.papermc.io/v2/objects/ab/cd/abcdef"
                      }
                    }
                  }
                ]
                """
            )
        )
        assertEquals(1, builds.size)
        assertEquals(45, builds[0].id)
        assertEquals("STABLE", builds[0].channel)
        assertEquals("https://fill-data.papermc.io/v2/objects/ab/cd/abcdef", builds[0].download.url)
        // 40 字符的假摘要长度不对，必须被丢弃（否则校验必然失败）
        assertNull(builds[0].download.sha256)
    }

    @Test
    fun `64 位 sha256 会被保留`() {
        val sha = "a".repeat(64)
        val builds = parseVelocityBuilds(
            json(
                """
                [{
                  "id": 7, "channel": "STABLE",
                  "downloads": { "server:default": {
                    "url": "https://example.com/v.jar",
                    "checksums": { "sha256": "$sha" }
                  } }
                }]
                """
            )
        )
        assertEquals(sha, builds.single().download.sha256)
    }

    @Test
    fun `倒序数组里取的是 id 最大而不是最后一个`() {
        // 实测 121 条，id 从 563 递减到 437 —— lastOrNull 拿到的是最老的构建
        val element = json(
            """
            [
              { "id": 563, "channel": "STABLE", "downloads": { "server:default": {
                  "url": "https://example.com/563.jar" } } },
              { "id": 562, "channel": "STABLE", "downloads": { "server:default": {
                  "url": "https://example.com/562.jar" } } },
              { "id": 437, "channel": "STABLE", "downloads": { "server:default": {
                  "url": "https://example.com/437.jar" } } }
            ]
            """
        )
        assertEquals(563, selectVelocityBuild(element)?.id)
    }

    @Test
    fun `稳定版优先于更新的测试版`() {
        val element = json(
            """
            [
              { "id": 900, "channel": "ALPHA", "downloads": { "server:default": {
                  "url": "https://example.com/alpha.jar" } } },
              { "id": 12, "channel": "STABLE", "downloads": { "server:default": {
                  "url": "https://example.com/stable.jar" } } }
            ]
            """
        )
        assertEquals("https://example.com/stable.jar", selectVelocityBuild(element)?.download?.url)
    }

    @Test
    fun `兼容历史 application 下载键`() {
        val builds = parseVelocityBuilds(
            json(
                """
                [{
                  "id": 3, "channel": "STABLE",
                  "downloads": { "application": { "url": "https://example.com/legacy.jar" } }
                }]
                """
            )
        )
        assertEquals("https://example.com/legacy.jar", builds.single().download.url)
    }

    @Test
    fun `没有 server_default 的 build 被跳过`() {
        // 只有前端资源、没有服务端包的构建不能当候选
        val element = json(
            """
            [
              { "id": 10, "channel": "STABLE", "downloads": {
                  "server:default": { "url": "https://example.com/ok.jar" } } },
              { "id": 11, "channel": "STABLE", "downloads": {
                  "sources": { "url": "https://example.com/src.jar" } } }
            ]
            """
        )
        assertEquals(10, selectVelocityBuild(element)?.id)
    }

    @Test
    fun `对象形式或空数组都不抛异常`() {
        // 兼容旧假设的形状：以前按 jsonObject 读，换成新代码后也不能因为结构不对就崩
        assertTrue(parseVelocityBuilds(json("""{"builds":[]}""")).isEmpty())
        assertTrue(parseVelocityBuilds(json("[]")).isEmpty())
        assertNull(selectVelocityBuild(json("[]")))
        assertNull(selectVelocityBuild(JsonObject(emptyMap())))
    }
}
