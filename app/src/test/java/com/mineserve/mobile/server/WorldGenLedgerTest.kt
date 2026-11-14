package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [WorldGenLedger] 的读写测试。
 *
 * 这份文件只存"用户当时要什么"，**不是**世界真实状态，所以测试重点在
 * 「读不到/坏文件不能崩、不能污染 UI」与「改名/删除要跟着走」两条。
 */
class WorldGenLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun serverDir(): File = tmp.newFolder("server")

    @Test
    fun `写入后能按世界名读回`() {
        val dir = serverDir()
        val record = WorldGenLedger.Record("hello", "2", WorldGenLedger.Kind.BDS, 123L)
        assertTrue(WorldGenLedger.put(dir, "my world", record))

        val back = WorldGenLedger.get(dir, "my world")
        assertEquals(record, back)
        // 文件落在 .minexa/ 下，不污染服务端根目录
        assertEquals(File(dir, ".minexa/world-gen.json").absolutePath, WorldGenLedger.file(dir).absolutePath)
    }

    @Test
    fun `同名世界覆盖而不是追加`() {
        val dir = serverDir()
        WorldGenLedger.put(dir, "w", WorldGenLedger.Record("1", "1", WorldGenLedger.Kind.BDS, 1L))
        WorldGenLedger.put(dir, "w", WorldGenLedger.Record("2", "5", WorldGenLedger.Kind.BDS, 2L))
        assertEquals(1, WorldGenLedger.read(dir).size)
        assertEquals("2", WorldGenLedger.get(dir, "w")?.seedText)
    }

    @Test
    fun `坏文件当空表处理`() {
        val dir = serverDir()
        WorldGenLedger.file(dir).apply { parentFile?.mkdirs() }.writeText("{ not json")
        assertTrue(WorldGenLedger.read(dir).isEmpty())
        assertNull(WorldGenLedger.get(dir, "w"))
        // 坏文件之后仍然能正常写入（覆盖掉坏内容）
        assertTrue(WorldGenLedger.put(dir, "w", WorldGenLedger.Record("", "1", WorldGenLedger.Kind.BDS, 0L)))
        assertEquals("1", WorldGenLedger.get(dir, "w")?.generator)
    }

    @Test
    fun `删除与改名`() {
        val dir = serverDir()
        WorldGenLedger.put(dir, "a", WorldGenLedger.Record("s", "1", WorldGenLedger.Kind.BDS, 0L))
        assertTrue(WorldGenLedger.rename(dir, "a", "b"))
        assertNull(WorldGenLedger.get(dir, "a"))
        assertEquals("s", WorldGenLedger.get(dir, "b")?.seedText)

        assertTrue(WorldGenLedger.remove(dir, "b"))
        assertTrue(WorldGenLedger.read(dir).isEmpty())
        // 删不存在的条目不算失败
        assertTrue(WorldGenLedger.remove(dir, "nope"))
    }

    @Test
    fun `生成方式按核心类型解析`() {
        val bds = WorldGenLedger.Record("42", "5", WorldGenLedger.Kind.BDS, 0L)
        val java = WorldGenLedger.Record("42", "minecraft:flat", WorldGenLedger.Kind.JAVA, 0L)
        assertEquals(5, WorldGenLedger.bdsGeneratorCode(bds))
        assertNull(WorldGenLedger.javaGeneratorId(bds))
        assertEquals("minecraft:flat", WorldGenLedger.javaGeneratorId(java))
        assertNull(WorldGenLedger.bdsGeneratorCode(java))
        assertNull(WorldGenLedger.bdsGeneratorCode(null))
    }
}
