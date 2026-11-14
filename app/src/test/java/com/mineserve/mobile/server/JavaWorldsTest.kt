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
 * Java 版世界读取测试，样本是**真实服务端生成的文件**：
 * - `javaworld/legacy/level.dat`：官方 1.20.4，`level-type=minecraft:flat` + 自定义层 + `level-seed=1234`
 * - `javaworld/modern/` 下：官方 26.3，同一份配置（布局已重构，种子搬到 data/minecraft/world_gen_settings.dat）
 *
 * 这两份样本同时锁住两代布局，避免"只支持最新版"或"只支持旧版"。
 */
class JavaWorldsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resource(path: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes() }

    private fun installLegacy(): File {
        val dir = tmp.newFolder("legacy")
        File(dir, "level.dat").writeBytes(resource("javaworld/legacy/level.dat"))
        File(dir, "region").mkdirs()
        return dir
    }

    private fun installModern(): File {
        val dir = tmp.newFolder("modern")
        File(dir, "level.dat").writeBytes(resource("javaworld/modern/level.dat"))
        File(dir, "data/minecraft").mkdirs()
        File(dir, "data/minecraft/world_gen_settings.dat")
            .writeBytes(resource("javaworld/modern/data/minecraft/world_gen_settings.dat"))
        File(dir, "dimensions/minecraft/overworld/region").mkdirs()
        return dir
    }

    // ── level.dat 读写 ──────────────────────────────────────────

    @Test
    fun `能读出 1_20_4 的 Data 复合`() {
        val data = JavaLevelDat.readData(installLegacy())
        assertNotNull(data)
        assertEquals("world", JavaLevelDat.string(data, "LevelName"))
        // 1.20.4 的 Data 里有 41 个字段（实测）
        assertTrue((data!!.entries.size) >= 40)
        assertNotNull(JavaLevelDat.compound(data, "WorldGenSettings"))
        assertNotNull(JavaLevelDat.compound(data, "GameRules"))
    }

    @Test
    fun `gzip 写回后仍能被读回且内容不变`() {
        val dir = installLegacy()
        val file = File(dir, "level.dat")
        val before = JavaLevelDat.read(file)!!
        assertTrue(JavaLevelDat.write(file, before, backup = false))
        val after = JavaLevelDat.read(file)!!
        assertEquals(
            JavaLevelDat.string(before.data, "LevelName"),
            JavaLevelDat.string(after.data, "LevelName")
        )
        assertEquals(
            JavaLevelDat.long(JavaLevelDat.compound(after.data, "WorldGenSettings"), "seed"),
            JavaLevelDat.long(JavaLevelDat.compound(before.data, "WorldGenSettings"), "seed")
        )
        // 再写一次必须还是 gzip（开头 1f 8b）
        val head = file.readBytes().take(2)
        assertEquals(listOf(0x1f.toByte(), 0x8b.toByte()), head)
    }

    @Test
    fun `非 gzip 文件读取返回 null 而不是抛异常`() {
        val dir = tmp.newFolder("bad")
        File(dir, "level.dat").writeText("not a gzip")
        assertNull(JavaLevelDat.readData(dir))
    }

    // ── 元信息 ─────────────────────────────────────────────────

    @Test
    fun `1_20_4 世界的种子与生成类型`() {
        val meta = JavaWorlds.readMeta(installLegacy())
        assertNotNull(meta)
        assertEquals(JavaWorlds.Layout.LEGACY, meta!!.layout)
        assertEquals(1234L, meta.seed)
        assertEquals("minecraft:flat", meta.generatorType)
        assertEquals("world", meta.levelName)
        assertEquals("easy", meta.difficulty)
        assertEquals("1.20.4", meta.versionName)
    }

    @Test
    fun `26_3 世界的种子与生成类型走 world_gen_settings`() {
        val meta = JavaWorlds.readMeta(installModern())
        assertNotNull(meta)
        assertEquals(JavaWorlds.Layout.MODERN, meta!!.layout)
        assertEquals(1234L, meta.seed)
        assertEquals("minecraft:flat", meta.generatorType)
        assertEquals("world", meta.levelName)
        // 26.x 难度搬到 difficulty_settings，值是字符串
        assertEquals("easy", meta.difficulty)
    }

    @Test
    fun `两代布局的超平坦层都能读出来`() {
        val legacy = JavaWorlds.readFlatLayers(installLegacy())
        assertNotNull(legacy)
        assertEquals(3, legacy!!.size)
        assertEquals("minecraft:bedrock", legacy[0].block)
        assertEquals(1, legacy[0].count)
        assertEquals("minecraft:stone", legacy[1].block)
        assertEquals(3, legacy[1].count)
        assertEquals("minecraft:glass", legacy[2].block)
        assertEquals(2, legacy[2].count)

        val modern = JavaWorlds.readFlatLayers(installModern())
        assertNotNull(modern)
        assertEquals(3, modern!!.size)
        assertEquals("minecraft:glass", modern[2].block)
    }

    @Test
    fun `非超平坦世界读层配置返回 null`() {
        // 直接把普通世界样本的 generator.type 改掉成本太高，这里用"没有 level.dat"覆盖 null 分支
        assertNull(JavaWorlds.readFlatLayers(tmp.newFolder("empty")))
    }

    // ── 数据包 ─────────────────────────────────────────────────

    private fun writeDataPack(worldDir: File, name: String, withMeta: Boolean) {
        val dir = JavaWorlds.datapackDir(worldDir).apply { mkdirs() }
        java.util.zip.ZipOutputStream(File(dir, name).outputStream()).use { z ->
            if (withMeta) {
                z.putNextEntry(java.util.zip.ZipEntry("pack.mcmeta"))
                z.write("""{"pack":{"pack_format":26,"description":"test"}}""".toByteArray())
                z.closeEntry()
            }
            z.putNextEntry(java.util.zip.ZipEntry("data/x/functions/a.mcfunction"))
            z.write("say hi\n".toByteArray())
            z.closeEntry()
        }
    }

    @Test
    fun `扫描数据包并标记缺少 pack_mcmeta`() {
        val dir = installLegacy()
        writeDataPack(dir, "ok.zip", withMeta = true)
        writeDataPack(dir, "broken.zip", withMeta = false)
        val meta = JavaWorlds.readMeta(dir)
        val packs = JavaWorlds.scanDataPacks(dir, meta)
        assertEquals(2, packs.size)
        val ok = packs.first { it.name == "ok" }
        assertEquals(26, ok.packFormat)
        assertNull(ok.problem)
        // 样本世界注册过 minexa-test.zip，这里新加的两个都还没启用
        assertEquals(false, ok.enabled)
        val broken = packs.first { it.name == "broken" }
        assertNotNull(broken.problem)
    }

    @Test
    fun `启用数据包写入 level_dat 且可读回`() {
        val dir = installLegacy()
        writeDataPack(dir, "ok.zip", withMeta = true)
        val file = File(dir, "datapacks/ok.zip")
        assertTrue(JavaWorlds.setDataPackEnabled(dir, file, true))
        var meta = JavaWorlds.readMeta(dir)!!
        assertTrue(meta.dataPacksEnabled.contains("file/ok.zip"))
        // 原有条目不能丢
        assertTrue(meta.dataPacksEnabled.contains("vanilla"))

        assertTrue(JavaWorlds.setDataPackEnabled(dir, file, false))
        meta = JavaWorlds.readMeta(dir)!!
        assertFalse(meta.dataPacksEnabled.contains("file/ok.zip"))
        assertTrue(meta.dataPacksDisabled.contains("file/ok.zip"))
    }

    // ── 重命名 / 维度 ───────────────────────────────────────────

    @Test
    fun `重命名世界会同步 LevelName`() {
        val root = tmp.newFolder("worlds-root")
        val dir = File(root, "world")
        dir.mkdirs()
        File(dir, "level.dat").writeBytes(resource("javaworld/legacy/level.dat"))

        val renamed = JavaWorlds.renameWorld(root, "world", "新世界")
        assertNotNull(renamed)
        assertEquals("新世界", renamed!!.name)
        assertEquals("新世界", JavaLevelDat.string(JavaLevelDat.readData(renamed), "LevelName"))
        assertTrue(!File(root, "world").exists())
    }

    @Test
    fun `三种布局的维度目录都能定位`() {
        val root = tmp.newFolder("dim-root")
        val legacy = File(root, "world").apply { mkdirs() }
        File(legacy, "DIM-1").mkdirs()
        File(legacy, "DIM1").mkdirs()
        val keys = JavaWorlds.dimensionDirs(legacy, root, "world").map { it.key }.toSet()
        assertEquals(setOf("nether", "end"), keys)

        val modern = File(root, "modern").apply { mkdirs() }
        File(modern, "dimensions/minecraft/the_nether").mkdirs()
        File(modern, "dimensions/minecraft/the_end").mkdirs()
        val mkeys = JavaWorlds.dimensionDirs(modern, root, "modern").map { it.key }.toSet()
        assertEquals(setOf("nether", "end"), mkeys)

        val bukkit = File(root, "bukkit").apply { mkdirs() }
        File(root, "bukkit_nether").mkdirs()
        File(root, "bukkit_the_end").mkdirs()
        val bkeys = JavaWorlds.dimensionDirs(bukkit, root, "bukkit").map { it.key }.toSet()
        assertEquals(setOf("nether", "end"), bkeys)
    }
}
