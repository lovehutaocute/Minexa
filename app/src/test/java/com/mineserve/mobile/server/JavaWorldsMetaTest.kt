package com.mineserve.mobile.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 26.x 世界元信息读取的回归测试。
 *
 * 真机 bug（2026-11-13）：「种子显示随机、生成类型显示未知」，而文件里明明有值。
 * 根因是 26.3 把 `world_gen_settings.dat` 从**世界根目录**挪到了
 * `dimensions/minecraft/overworld/data/minecraft/` 下，只认旧路径就永远读不到。
 *
 * 这里用合成文件覆盖两条路径，避免下次版本再挪位置时又要靠猜。
 */
class JavaWorldsMetaTest {

    private val seedValue = -6712945446881612999L

    /** 造一个 26.x 世界：level.dat + 维度目录内的 world_gen_settings.dat */
    private fun makeModernWorld(genSettingsRelative: String): File {
        val world = createTempWorldDir()

        // level.dat：Data 里放 LevelName / difficulty_settings / Version
        val data = LevelDat.Tag.CompoundTag()
        data["LevelName"] = LevelDat.Tag.StringTag("flat26")
        val difficulty = LevelDat.Tag.CompoundTag()
        difficulty["difficulty"] = LevelDat.Tag.StringTag("normal")
        data["difficulty_settings"] = difficulty
        val version = LevelDat.Tag.CompoundTag()
        version["Name"] = LevelDat.Tag.StringTag("26.3")
        data["Version"] = version
        val root = LevelDat.Tag.CompoundTag()
        root["Data"] = data
        assertTrue(
            JavaLevelDat.write(File(world, Worlds.LEVEL_DAT), JavaLevelDat.World(root), backup = false),
            "level.dat 写失败"
        )

        // world_gen_settings.dat：{ data: { seed, dimensions: { minecraft:overworld: { generator } } } }
        val layers = LevelDat.Tag.ListTag(
            LevelDat.Type.COMPOUND,
            mutableListOf(
                layerTag("minecraft:bedrock", 1),
                layerTag("minecraft:dirt", 2),
                layerTag("minecraft:grass_block", 1)
            )
        )
        val settings = LevelDat.Tag.CompoundTag()
        settings["layers"] = layers
        settings["biome"] = LevelDat.Tag.StringTag("minecraft:plains")

        val generator = LevelDat.Tag.CompoundTag()
        generator["type"] = LevelDat.Tag.StringTag("minecraft:flat")
        generator["settings"] = settings

        val overworld = LevelDat.Tag.CompoundTag()
        overworld["generator"] = generator

        val dimensions = LevelDat.Tag.CompoundTag()
        dimensions["minecraft:overworld"] = overworld

        val genData = LevelDat.Tag.CompoundTag()
        genData["seed"] = LevelDat.Tag.LongTag(seedValue)
        genData["dimensions"] = dimensions

        val genRoot = LevelDat.Tag.CompoundTag()
        genRoot["data"] = genData
        genRoot["DataVersion"] = LevelDat.Tag.IntTag(4000)

        val genFile = File(world, genSettingsRelative)
        genFile.parentFile?.mkdirs()
        assertTrue(
            JavaLevelDat.write(genFile, JavaLevelDat.World(genRoot), backup = false),
            "world_gen_settings.dat 写失败"
        )
        return world
    }

    private fun layerTag(block: String, height: Int) = LevelDat.Tag.CompoundTag().apply {
        this["block"] = LevelDat.Tag.StringTag(block)
        this["height"] = LevelDat.Tag.IntTag(height)
    }

    private fun createTempWorldDir(): File =
        File(System.getProperty("java.io.tmpdir"), "jw-meta-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    @Test
    fun `26_3 把生成设置放在维度目录里也能读出来`() {
        val world = makeModernWorld(
            "dimensions/minecraft/overworld/data/minecraft/world_gen_settings.dat"
        )
        assertEquals(JavaWorlds.Layout.MODERN, JavaWorlds.layout(world))

        val meta = JavaWorlds.readMeta(world)
        assertNotNull(meta, "readMeta 返回 null —— UI 会显示「种子随机 / 生成类型未知」")
        assertEquals(seedValue, meta.seed)
        assertEquals("minecraft:flat", meta.generatorType)
        assertEquals("flat26", meta.levelName)
        assertEquals("normal", meta.difficulty)

        val flat = JavaWorlds.readFlatLayers(world)
        assertNotNull(flat, "超平坦层读不出来")
        assertEquals(3, flat.size)
        assertEquals("minecraft:grass_block", flat.last().block)
        assertEquals(2, flat[1].count)

        world.deleteRecursively()
    }

    @Test
    fun `旧布局的世界根目录路径仍然兼容`() {
        val world = makeModernWorld("data/minecraft/world_gen_settings.dat")
        val meta = JavaWorlds.readMeta(world)
        assertNotNull(meta)
        assertEquals(seedValue, meta.seed)
        assertEquals("minecraft:flat", meta.generatorType)
        world.deleteRecursively()
    }
}
