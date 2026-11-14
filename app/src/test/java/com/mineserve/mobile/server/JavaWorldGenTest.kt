package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `level-type` / `generator-settings` 与 properties 转义。
 *
 * 断言来自真实服务端行为（`docs/JAVA-ADVANCED-WORLD-MANAGEMENT-PLAN.md` §2.1 / §2.8）：
 * 超平坦层用 `height`、`generator-settings` 必须是单行 JSON、
 * 而服务端会把 `:` 转义成 `\:` 写回文件。
 */
class JavaWorldGenTest {

    @Test
    fun `解析 level-type 允许省略命名空间与大小写`() {
        assertEquals(JavaWorldGen.LevelType.FLAT, JavaWorldGen.LevelType.fromId("minecraft:flat"))
        assertEquals(JavaWorldGen.LevelType.FLAT, JavaWorldGen.LevelType.fromId("flat"))
        assertEquals(JavaWorldGen.LevelType.FLAT, JavaWorldGen.LevelType.fromId("FLAT"))
        assertEquals(JavaWorldGen.LevelType.AMPLIFIED, JavaWorldGen.LevelType.fromId("minecraft:amplified"))
        assertEquals(
            JavaWorldGen.LevelType.SINGLE_BIOME,
            JavaWorldGen.LevelType.fromId("single_biome_surface")
        )
        assertNull(JavaWorldGen.LevelType.fromId("nonsense"))
        assertNull(JavaWorldGen.LevelType.fromId(""))
    }

    @Test
    fun `超平坦 settings 用 height 且是单行 JSON`() {
        val layers = listOf(
            BdsWorldGen.Layer("minecraft:bedrock", 1),
            BdsWorldGen.Layer("minecraft:stone", 3)
        )
        val json = JavaWorldGen.buildSettings(
            JavaWorldGen.LevelType.FLAT,
            JavaWorldGen.FlatConfig(layers, "minecraft:plains")
        )
        assertEquals(
            """{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":3}],"biome":"minecraft:plains"}""",
            json
        )
        assertFalse(json.contains("\n"))
        // 不能出现基岩版的 count 字段
        assertFalse(json.contains("\"count\""))
    }

    @Test
    fun `非超平坦类型不写 layers`() {
        assertEquals("{}", JavaWorldGen.buildSettings(
            JavaWorldGen.LevelType.AMPLIFIED, JavaWorldGen.FlatConfig(BdsWorldGen.DEFAULT_FLAT)
        ))
        assertEquals("""{"biome":"minecraft:desert"}""", JavaWorldGen.buildSettings(
            JavaWorldGen.LevelType.SINGLE_BIOME,
            JavaWorldGen.FlatConfig(BdsWorldGen.DEFAULT_FLAT, "minecraft:desert")
        ))
    }

    @Test
    fun `settings 往返一致`() {
        val layers = listOf(
            BdsWorldGen.Layer("minecraft:bedrock", 1),
            BdsWorldGen.Layer("minecraft:glass", 2)
        )
        val json = JavaWorldGen.buildSettings(
            JavaWorldGen.LevelType.FLAT, JavaWorldGen.FlatConfig(layers, "minecraft:desert")
        )
        val back = JavaWorldGen.parseSettings(json)
        assertNotNull(back)
        assertEquals(layers, back!!.layers)
        assertEquals("minecraft:desert", back.biome)
    }

    @Test
    fun `坏 JSON 与空对象都返回 null`() {
        assertNull(JavaWorldGen.parseSettings(null))
        assertNull(JavaWorldGen.parseSettings("{}"))
        assertNull(JavaWorldGen.parseSettings("not json"))
    }

    @Test
    fun `资源 ID 规范化`() {
        assertEquals("minecraft:dirt", JavaWorldGen.normalizeId("dirt"))
        assertEquals("minecraft:dirt", JavaWorldGen.normalizeId("  Minecraft:Dirt "))
        assertNull(JavaWorldGen.normalizeId("bad id!"))
        assertNull(JavaWorldGen.normalizeId(""))
    }

    // ── properties 转义 ────────────────────────────────────────

    @Test
    fun `解码服务端写出的转义形式`() {
        assertEquals("minecraft:flat", PropertiesEscape.decode("""minecraft\:flat"""))
        assertEquals(
            """{"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}""",
            PropertiesEscape.decode(
                """{"layers"\:[{"block"\:"minecraft\:bedrock","height"\:1}],"biome"\:"minecraft\:plains"}"""
            )
        )
    }

    @Test
    fun `解码不影响普通值`() {
        assertEquals("true", PropertiesEscape.decode("true"))
        assertEquals("", PropertiesEscape.decode(""))
        assertEquals("hello world", PropertiesEscape.decode("hello world"))
    }

    @Test
    fun `编码只处理会破坏文件结构的字符`() {
        assertEquals("minecraft:flat", PropertiesEscape.encode("minecraft:flat"))
        assertEquals("""line1\nline2""", PropertiesEscape.encode("line1\nline2"))
        assertEquals("""a\\b""", PropertiesEscape.encode("a\\b"))
        assertTrue(PropertiesEscape.encode("  leading").startsWith("\\"))
    }

    @Test
    fun `转义解码与编码互补`() {
        val raw = "第一行\n第二行\t带制表"
        assertEquals(raw, PropertiesEscape.decode(PropertiesEscape.encode(raw)))
    }

    @Test
    fun `服务端转义后的 generator-settings 能被解析`() {
        // 这是 1.20.4 实际写回的形态
        val escaped = """{"layers"\:[{"block"\:"minecraft:bedrock","height"\:1},{"block"\:"minecraft:stone","height"\:3}],"biome"\:"minecraft:plains"}"""
        val cfg = JavaWorldGen.parseSettings(PropertiesEscape.decode(escaped))
        assertNotNull(cfg)
        assertEquals(2, cfg!!.layers.size)
        assertEquals("minecraft:stone", cfg.layers[1].block)
        assertEquals(3, cfg.layers[1].count)
    }
}
