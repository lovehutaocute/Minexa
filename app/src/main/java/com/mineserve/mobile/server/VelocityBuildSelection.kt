package com.mineserve.mobile.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一个可下载的 Velocity build。 */
internal data class VelocityBuild(
    val id: Int,
    val channel: String,
    val download: CoreDownload
)

/** 渠道优先级：稳定版优先，快照/测试版靠后。 */
private val velocityChannelPriority =
    mapOf("STABLE" to 3, "RECOMMENDED" to 3, "BETA" to 2, "ALPHA" to 1)

/**
 * 解析 PaperMC v3 `/projects/velocity/versions/{v}/builds` 的响应。
 *
 * 这个接口的形状有三个坑，全部在 2026-11 用真实响应核对过：
 *  1. 顶层是**数组**（`[{...}, {...}]`），不是 `{"builds": [...]}`。按对象读会直接抛
 *     `JsonArray is not a JsonObject`，整条下载链断掉 —— 表现是 Velocity 核心 100% 下载失败。
 *  2. 下载键是 `server:default`，不是 `application`。老版本接口用过 `application`，
 *     这里两个都认，`server:default` 优先。
 *  3. 数组按 id **倒序**返回（新的在前，实测 563 → 437），`lastOrNull()` 拿到的是最老的构建。
 */
internal fun parseVelocityBuilds(element: JsonElement): List<VelocityBuild> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { entry ->
        val obj = entry as? JsonObject ?: return@mapNotNull null
        val downloads = obj["downloads"] as? JsonObject ?: return@mapNotNull null
        val download = (downloads["server:default"] as? JsonObject)
            ?: (downloads["application"] as? JsonObject)
            ?: return@mapNotNull null
        val url = (download["url"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        // 同一个下载对象里就带 checksums.sha256，扔掉它等于放弃坏包早发现
        val sha256 = ((download["checksums"] as? JsonObject)?.get("sha256") as? JsonPrimitive)
            ?.content
            ?.takeIf { it.length == 64 }
        VelocityBuild(
            id = (obj["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1,
            channel = (obj["channel"] as? JsonPrimitive)?.content.orEmpty(),
            download = CoreDownload(url, sha256 = sha256)
        )
    }
}

/** 在候选里挑最优 build：渠道优先，同渠道取 id 最大的（最新）。没有候选返回 null。 */
internal fun selectVelocityBuild(element: JsonElement): VelocityBuild? =
    parseVelocityBuilds(element).maxWithOrNull(
        compareBy({ velocityChannelPriority[it.channel] ?: 0 }, { it.id })
    )
