package com.mineserve.mobile.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Java 版世界生成参数：`level-type` 与 `generator-settings`。
 *
 * 与基岩版的差别（实测，见 `docs/JAVA-ADVANCED-WORLD-MANAGEMENT-PLAN.md` §2）：
 * - **不需要**预写 `level.dat`，两个键放在 `server.properties` 里由服务端读取；
 * - 超平坦层用的是 `height`（基岩版是 `count`），且要带 `biome`；
 * - 生成类型比基岩版多（放大化 / 大型生物群系 / 单一生物群系）。
 *
 * 只在**世界尚未生成**时生效（实测：已有世界改这两个键毫无效果）。
 */
object JavaWorldGen {

    /** `level-type` 的取值。1.19+ 还接受世界预设 ID，那部分未验证，这里不暴露。 */
    enum class LevelType(val id: String) {
        NORMAL("minecraft:normal"),
        FLAT("minecraft:flat"),
        LARGE_BIOMES("minecraft:large_biomes"),
        AMPLIFIED("minecraft:amplified"),
        SINGLE_BIOME("minecraft:single_biome_surface");

        companion object {
            /** 解析配置里的值：允许省略 `minecraft:` 前缀，大小写不敏感。 */
            fun fromId(raw: String?): LevelType? {
                val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
                val full = if (v.contains(':')) v else "minecraft:$v"
                return entries.firstOrNull { it.id == full }
                    // 简写形式（flat / normal / amplified）也认
                    ?: entries.firstOrNull { it.id == "minecraft:$v" }
            }
        }
    }

    /** 超平坦配置：层（自下而上）+ 生物群系。 */
    data class FlatConfig(
        val layers: List<BdsWorldGen.Layer>,
        val biome: String = DEFAULT_BIOME
    )

    const val DEFAULT_BIOME = "minecraft:plains"

    /** 资源 ID 校验（`namespace:path`，允许省略命名空间由调用方补）。 */
    private val ID_RE = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")

    private val BIOME_FALLBACK = listOf(
        "minecraft:plains", "minecraft:desert", "minecraft:forest", "minecraft:taiga",
        "minecraft:snowy_plains", "minecraft:jungle", "minecraft:swamp", "minecraft:badlands",
        "minecraft:savanna", "minecraft:cherry_grove"
    )

    /** UI 里的生物群系候选（只做候选提示，不限制用户输入）。 */
    val biomeSuggestions: List<String> get() = BIOME_FALLBACK

    /** 规范化资源 ID：补 `minecraft:`、转小写；非法返回 null。 */
    fun normalizeId(raw: String): String? {
        val v = raw.trim().lowercase().ifEmpty { return null }
        val full = if (v.contains(':')) v else "minecraft:$v"
        return if (ID_RE.matches(full)) full else null
    }

    /**
     * 生成 `generator-settings` 的值（**单行 JSON**）。
     *
     * 单行是硬要求：`server.properties` 一个键只能占一行，JSON 里带换行会破坏文件结构。
     */
    fun buildSettings(type: LevelType, flat: FlatConfig): String = when (type) {
        LevelType.FLAT -> buildString {
            append("""{"layers":[""")
            append(flat.layers.joinToString(",") { l ->
                """{"block":"${l.block}","height":${l.count}}"""
            })
            append("""],"biome":"${flat.biome}"}""")
        }
        LevelType.SINGLE_BIOME -> """{"biome":"${flat.biome}"}"""
        // 其余类型服务端不读 settings，保持空对象（与官方默认一致）
        else -> "{}"
    }

    /** 解析已有的 `generator-settings`（坏 JSON 返回 null，不抛）。 */
    fun parseSettings(json: String?): FlatConfig? {
        val text = json?.trim().orEmpty()
        if (text.isEmpty() || text == "{}") return null
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val biome = (obj["biome"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: DEFAULT_BIOME
        val layers = (obj["layers"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val block = (o["block"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val height = runCatching { o["height"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 1
            if (height <= 0) return@mapNotNull null
            BdsWorldGen.Layer(block, height)
        }.orEmpty()
        if (layers.isEmpty()) return FlatConfig(BdsWorldGen.DEFAULT_FLAT, biome)
        return FlatConfig(layers, biome)
    }

    /**
     * 把层配置渲染成可编辑文本（与基岩版同一套语法：`bedrock, dirt*2`）。
     *
     * 复用 [BdsWorldGen.layersText]：两个版本只是 JSON 字段名不同，用户输入语法一样。
     */
    fun layersText(layers: List<BdsWorldGen.Layer>): String = BdsWorldGen.layersText(layers)
}
