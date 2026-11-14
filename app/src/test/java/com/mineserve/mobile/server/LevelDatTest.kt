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
 * [LevelDat] 的读写测试。
 *
 * 样本是**官方 BDS 真实生成**的 `level.dat`（1.19.83 / 1.26.63.1，各约 3 KB），
 * 因为这东西的格式细节（8 字节头、小端、字段名大小写）靠构造数据测不出真问题：
 * 一旦解析错位，读出来的种子会是另一个数，而 UI 上只会显示一个"看起来正常"的种子。
 */
class LevelDatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(name: String): File {
        val res = javaClass.classLoader!!.getResource("leveldat/$name")
        assertNotNull("缺少测试样本 $name", res)
        val out = File(tmp.newFolder(), name)
        res!!.openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    @Test
    fun `读取 1_26 样本的种子与生成器`() {
        val w = LevelDat.read(sample("bds-1.26.63.1.dat"))
        assertNotNull(w)
        assertEquals(10, w!!.headerVersion)
        assertEquals(123456789L, w.seed)
        assertEquals(1, w.generator)
        assertEquals("testseed1", w.levelName)
        // 顶层字段数量：解析器必须一个不漏（漏字段 = 写回时丢数据）
        assertEquals(116, w.root.entries.size)
        assertTrue(w.string("InventoryVersion")!!.startsWith("1.26"))
        assertEquals(0, w.byte("texturePacksRequired"))
    }

    @Test
    fun `读取 1_19 样本（Generator 已被改成虚空）`() {
        val w = LevelDat.read(sample("bds-1.19.83.dat"))
        assertNotNull(w)
        assertEquals(99162322L, w!!.seed)   // 文本种子 hello 的 Java hashCode
        assertEquals(5, w.generator)
        assertEquals(101, w.root.entries.size)
    }

    @Test
    fun `真实样本按服务端生成过判定`() {
        // 官方 BDS 生成的 level.dat 一定带 InventoryVersion / NetworkVersion
        assertTrue(LevelDat.read(sample("bds-1.26.63.1.dat"))!!.generatedByServer)
        assertTrue(LevelDat.read(sample("bds-1.19.83.dat"))!!.generatedByServer)
    }

    @Test
    fun `我们预写的最小 level_dat 不算已生成`() {
        // 新建世界时只写 5 个字段：那是"用户的要求"，不是世界状态。
        // 判错的话 UI 会对着一个还没生成的世界报种子/地形（曾把"随机"当成真实种子显示）。
        val f = File(tmp.newFolder("pre"), "level.dat")
        assertTrue(BdsWorldGen.createLevelDat(f, "pre", 42L, BdsWorldGen.Generator.FLAT))
        val w = LevelDat.read(f)!!
        assertFalse(w.generatedByServer)
        assertEquals(2, w.generator)
    }

    @Test
    fun `改种子后写回不丢其它字段`() {
        val f = sample("bds-1.26.63.1.dat")
        val before = LevelDat.read(f)!!
        val beforeKeys = before.root.entries.keys.toList()

        assertTrue(LevelDat.update(f) { it.seed = -42L })

        val after = LevelDat.read(f)!!
        assertEquals(-42L, after.seed)
        assertEquals(10, after.headerVersion)
        assertEquals(beforeKeys, after.root.entries.keys.toList())   // 顺序与数量都不变
        assertEquals(before.string("InventoryVersion"), after.string("InventoryVersion"))
        assertEquals(before.int("StorageVersion"), after.int("StorageVersion"))
    }

    @Test
    fun `改生成器与超平坦层`() {
        val f = sample("bds-1.26.63.1.dat")
        val layers = """{"biome_id":1,"block_layers":[{"block_name":"minecraft:stone","count":4}],""" +
            """"encoding_version":6,"structure_options":null,"world_version":"version.post_1_18"}"""
        assertTrue(LevelDat.update(f) { w ->
            w.generator = 2
            w.flatWorldLayers = layers
        })
        val after = LevelDat.read(f)!!
        assertEquals(2, after.generator)
        assertEquals(layers, after.flatWorldLayers)
    }

    @Test
    fun `写回时留一份备份`() {
        val f = sample("bds-1.26.63.1.dat")
        val original = f.readBytes()
        assertTrue(LevelDat.update(f) { it.seed = 7L })
        val bak = File(f.parentFile, f.name + ".minexa-bak")
        assertTrue("应生成 .minexa-bak", bak.isFile)
        assertTrue(original.contentEquals(bak.readBytes()))
    }

    @Test
    fun `无法识别的文件读出 null 且 update 不创建文件`() {
        val dir = tmp.newFolder()
        val bad = File(dir, "level.dat").apply { writeBytes(ByteArray(16) { 0x7F }) }
        assertNull(LevelDat.read(bad))

        val missing = File(dir, "nope.dat")
        assertFalse(LevelDat.update(missing) { it.seed = 1L })
        assertFalse("读不到原文时绝不凭空造文件", missing.exists())
    }

    @Test
    fun `长度字段与实际不一致时按实际剩余字节解析`() {
        val src = sample("bds-1.26.63.1.dat").readBytes()
        // 把 length 写成一个离谱值，真实 BDS 不会这么写，但第三方工具可能
        val patched = src.copyOf()
        patched[4] = 0x7F; patched[5] = 0x7F; patched[6] = 0x7F; patched[7] = 0x7F
        val f = File(tmp.newFolder(), "level.dat").apply { writeBytes(patched) }
        assertEquals(123456789L, LevelDat.readSeed(f))
    }

    @Test
    fun `解析结果里没有 RandomSeed 时返回 null`() {
        val f = sample("bds-1.26.63.1.dat")
        assertTrue(LevelDat.update(f) { it.seed = null })
        assertNull(LevelDat.read(f)!!.seed)
    }
}
