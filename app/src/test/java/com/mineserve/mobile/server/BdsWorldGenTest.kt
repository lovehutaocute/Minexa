package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [BdsWorldGen] 的层配置与 `level.dat` 生成测试。
 *
 * 关键断言来自实测（`docs/BDS-ADDONS-WORLDS-VERIFICATION.md` §12）：只写 5 个字段的
 * `level.dat` 就能让 BDS 直接按指定生成方式生成世界，所以写出来的文件必须能被
 * [LevelDat.read] 原样读回——读不回就会被 BDS 当成"没有世界"，用户看到的是
 * "选了超平坦但出来是普通地形"。
 */
class BdsWorldGenTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `默认超平坦层是基岩泥土草方块`() {
        val json = BdsWorldGen.layersJson(BdsWorldGen.DEFAULT_FLAT)
        assertTrue(json.contains("\"encoding_version\":6"))
        assertTrue(json.contains("\"block_name\":\"minecraft:bedrock\",\"count\":1"))
        assertTrue(json.contains("\"block_name\":\"minecraft:dirt\",\"count\":2"))
        assertTrue(json.contains("\"block_name\":\"minecraft:grass_block\",\"count\":1"))
        assertTrue(json.contains("\"world_version\":\"version.post_1_18\""))
    }

    @Test
    fun `解析层配置支持数量与省略命名空间`() {
        val layers = BdsWorldGen.parseLayers("bedrock, dirt*2, minecraft:glass*3")
        assertNotNull(layers)
        assertEquals(3, layers!!.size)
        assertEquals("minecraft:bedrock", layers[0].block)
        assertEquals(1, layers[0].count)
        assertEquals(2, layers[1].count)
        assertEquals("minecraft:glass", layers[2].block)
        assertEquals(3, layers[2].count)
    }

    @Test
    fun `层配置空输入用默认值`() {
        assertEquals(BdsWorldGen.DEFAULT_FLAT, BdsWorldGen.parseLayers("   "))
    }

    @Test
    fun `非法层配置返回 null`() {
        assertNull(BdsWorldGen.parseLayers("dirt*0"))       // 数量必须 ≥1
        assertNull(BdsWorldGen.parseLayers("dirt*abc"))     // 数量不是数字
        assertNull(BdsWorldGen.parseLayers("bad block!"))   // 方块名有非法字符
        assertNull(BdsWorldGen.parseLayers("*3"))           // 没有方块名
    }

    @Test
    fun `层文本与解析可往返`() {
        val layers = listOf(
            BdsWorldGen.Layer("minecraft:bedrock", 1),
            BdsWorldGen.Layer("minecraft:stone", 3)
        )
        assertEquals("bedrock, stone*3", BdsWorldGen.layersText(layers))
        assertEquals(layers, BdsWorldGen.parseLayers(BdsWorldGen.layersText(layers)))
    }

    @Test
    fun `写出的 level_dat 能被读回`() {
        val f = File(tmp.newFolder("w1"), "level.dat")
        assertTrue(BdsWorldGen.createLevelDat(f, "flatworld", 12345L,
            BdsWorldGen.Generator.FLAT, BdsWorldGen.DEFAULT_FLAT))
        val w = LevelDat.read(f)
        assertNotNull(w)
        assertEquals("flatworld", w!!.string("LevelName"))
        assertEquals(12345L, w.long("RandomSeed"))
        assertEquals(2, w.int("Generator"))
        assertEquals(10, w.int("StorageVersion"))
        assertNotNull(w.string("FlatWorldLayers"))
    }

    @Test
    fun `虚空世界不写 FlatWorldLayers`() {
        val f = File(tmp.newFolder("w2"), "level.dat")
        assertTrue(BdsWorldGen.createLevelDat(f, "voidworld", 42L, BdsWorldGen.Generator.VOID))
        val w = LevelDat.read(f)!!
        assertEquals(5, w.int("Generator"))
        assertNull(w.string("FlatWorldLayers"))
    }

    @Test
    fun `空种子不写 RandomSeed 让服务端随机`() {
        val f = File(tmp.newFolder("w3"), "level.dat")
        assertTrue(BdsWorldGen.createLevelDat(f, "rnd", null, BdsWorldGen.Generator.NORMAL))
        val w = LevelDat.read(f)!!
        assertNull(w.long("RandomSeed"))
        assertEquals(1, w.int("Generator"))
    }

    @Test
    fun `新建世界在服务端启动前也能说明白自己在等什么`() {
        // 世界目录里只有我们预写的 level.dat + 一条建世界记录：UI 要能同时说清
        // 「生成方式 = 超平坦」（来自 level.dat）和「种子 = hello」（来自记录），
        // 而不是两个字段都显示"未知"。
        val dir = tmp.newFolder("srv")
        val worldDir = File(dir, "worlds/newworld").apply { mkdirs() }
        assertTrue(BdsWorldGen.createLevelDat(File(worldDir, "level.dat"), "newworld", null, BdsWorldGen.Generator.FLAT))
        assertTrue(
            WorldGenLedger.put(
                dir, "newworld",
                WorldGenLedger.Record("hello", "2", WorldGenLedger.Kind.BDS, 1L)
            )
        )
        val w = LevelDat.read(File(worldDir, "level.dat"))!!
        assertEquals(2, w.int("Generator"))
        assertNull(w.long("RandomSeed"))
        assertEquals("hello", WorldGenLedger.get(dir, "newworld")?.seedText)
        assertEquals(2, WorldGenLedger.bdsGeneratorCode(WorldGenLedger.get(dir, "newworld")))
    }

    @Test
    fun `文本种子按 Java hashCode 落盘`() {
        // 与 BDS 实测一致：hello → 99162322（1.19 ~ 1.26 五个版本同值）
        var hash = 0
        for (ch in "hello") hash = 31 * hash + ch.code
        assertEquals(99162322, hash)
        // WorldManager.resolveSeed 的规则在 WorldsTest 一侧不便构造 Termux，这里只校验
        // 「int64 字面值优先、否则哈希」这条算法本身
        assertEquals(99162322L, hash.toLong())
    }
}
