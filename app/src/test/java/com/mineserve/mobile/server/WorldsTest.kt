package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [Worlds] 的世界识别与打包测试。
 *
 * 这些都是**纯文件系统事实**，不需要设备、不需要 Termux，属于可以在 CI 上提前
 * 拦住回归的那一类：世界目录判定错了会让用户看到不存在（或重复）的世界，
 * 打包/解包错了会丢存档或写到目录外面。
 */
class WorldsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun javaWorld(root: File, name: String, withNether: Boolean = false, withEnd: Boolean = false): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "level.dat").writeText("nbt")
        File(dir, "region").mkdirs()
        if (withNether) File(root, "${name}_nether").also { it.mkdirs(); File(it, "level.dat").writeText("nbt") }
        if (withEnd) File(root, "${name}_the_end").also { it.mkdirs(); File(it, "level.dat").writeText("nbt") }
        return dir
    }

    // ── 识别 ────────────────────────────────────────────────────

    @Test
    fun `level_dat 判定为 Java 世界`() {
        val root = tmp.newFolder("srv")
        val dir = File(root, "world").apply { mkdirs(); File(this, "level.dat").writeText("x") }
        assertEquals(Worlds.Kind.Java, Worlds.detectKind(dir))
        assertTrue(Worlds.isWorld(dir))
    }

    @Test
    fun `只有 region 目录也判定为 Java 世界`() {
        val root = tmp.newFolder("srv2")
        val dir = File(root, "world").apply { mkdirs(); File(this, "region").mkdirs() }
        assertEquals(Worlds.Kind.Java, Worlds.detectKind(dir))
    }

    @Test
    fun `db 目录判定为基岩系世界`() {
        val root = tmp.newFolder("srv3")
        val dir = File(root, "world").apply { mkdirs(); File(this, "db").mkdirs() }
        assertEquals(Worlds.Kind.Bedrock, Worlds.detectKind(dir))
    }

    @Test
    fun `普通目录不是世界`() {
        val root = tmp.newFolder("srv4")
        val dir = File(root, "plugins").apply { mkdirs() }
        assertEquals(Worlds.Kind.Unknown, Worlds.detectKind(dir))
        assertFalse(Worlds.isWorld(dir))
    }

    // ── 列表 ────────────────────────────────────────────────────

    @Test
    fun `列出世界时排除非世界目录与备份目录`() {
        val root = tmp.newFolder("srv5")
        javaWorld(root, "world")
        File(root, "plugins").mkdirs()
        File(root, "logs").mkdirs()
        File(root, "libraries").mkdirs()
        File(root, "world.bak.20260728_153000").apply { mkdirs(); File(this, "level.dat").writeText("x") }

        val names = Worlds.list(root, "world").map { it.name }
        assertEquals(listOf("world"), names)
    }

    @Test
    fun `维度目录不单独列出但算进主世界`() {
        val root = tmp.newFolder("srv6")
        javaWorld(root, "world", withNether = true, withEnd = true)
        javaWorld(root, "creative")

        val list = Worlds.list(root, "world")
        assertEquals(listOf("world", "creative"), list.map { it.name })

        val world = list.first { it.name == "world" }
        // 主世界 + 下界 + 末地三个目录都要参与大小统计
        val (size, _) = Worlds.sizeOf(Worlds.includeDimensions(world.dir, setOf("world", "world_nether", "world_the_end")))
        assertTrue("三个维度的 level.dat 至少 3 字节，实际 $size", size >= 3)
    }

    @Test
    fun `同名主世界不存在时 下界后缀目录算独立世界`() {
        val root = tmp.newFolder("srv7")
        javaWorld(root, "world_nether")
        val names = Worlds.list(root, "world").map { it.name }
        assertEquals(listOf("world_nether"), names)
    }

    @Test
    fun `当前生效的世界排在最前`() {
        val root = tmp.newFolder("srv8")
        javaWorld(root, "alpha")
        javaWorld(root, "beta")
        val list = Worlds.list(root, "beta")
        assertEquals("beta", list.first().name)
        assertTrue(list.first().isActive)
        assertFalse(list[1].isActive)
    }

    @Test
    fun `世界目录不存在时返回空列表`() {
        assertEquals(emptyList<Worlds.WorldInfo>(), Worlds.list(File(tmp.root, "not-there"), "world"))
    }

    @Test
    fun `dimensionOwner 只在同名主世界存在时归并`() {
        assertEquals("world", Worlds.dimensionOwner("world_nether", setOf("world", "world_nether")))
        assertEquals("world", Worlds.dimensionOwner("world_the_end", setOf("world", "world_the_end")))
        assertNull(Worlds.dimensionOwner("world_nether", setOf("world_nether")))
        assertNull(Worlds.dimensionOwner("world", setOf("world")))
        assertNull(Worlds.dimensionOwner("my_the_end", setOf("my_the_end")))
    }

    // ── 命名 ────────────────────────────────────────────────────

    @Test
    fun `重名时自动追加序号`() {
        val root = tmp.newFolder("srv9")
        File(root, "world").mkdirs()
        File(root, "world-2").mkdirs()
        assertEquals("world-3", Worlds.uniqueName(root, "world"))
        assertEquals("newworld", Worlds.uniqueName(root, "newworld"))
    }

    @Test
    fun `目录名净化去掉路径分隔符与非法字符`() {
        assertEquals("a_b_c", Worlds.sanitize("a/b\\c"))
        assertEquals("a_b", Worlds.sanitize("a:b"))
        assertFalse(Worlds.sanitize("../../etc").contains("/"))
        assertEquals("world", Worlds.sanitize("   "))
        assertEquals("world", Worlds.sanitize("."))
    }

    // ── 打包 / 解包 ─────────────────────────────────────────────

    @Test
    fun `导出再导入可以还原世界`() {
        val source = tmp.newFolder("src")
        javaWorld(source, "world", withNether = true)
        File(File(source, "world"), "region/r.0.0.mca").writeText("chunk-data")

        val zip = File(tmp.newFolder("zipout"), "world.zip")
        val allNames = source.listFiles()!!.map { it.name }.toSet()
        assertTrue(Worlds.exportToZip(File(source, "world"), allNames, zip))
        assertTrue(zip.length() > 0)

        val destRoot = tmp.newFolder("dest")
        assertTrue("导入失败", Worlds.importWorldZip(zip, destRoot, "world"))
        val dest = File(destRoot, "world")
        assertTrue(File(dest, "level.dat").isFile)
        assertEquals("chunk-data", File(dest, "region/r.0.0.mca").readText())
        // 维度目录也要一起还原
        assertTrue(File(destRoot, "world_nether/level.dat").isFile)
    }

    @Test
    fun `导入时可以整体改名（含维度目录）`() {
        val source = tmp.newFolder("srcrename")
        javaWorld(source, "world", withNether = true, withEnd = true)
        val zip = File(tmp.newFolder("ziprename"), "world.zip")
        val allNames = source.listFiles()!!.map { it.name }.toSet()
        assertTrue(Worlds.exportToZip(File(source, "world"), allNames, zip))

        val destRoot = tmp.newFolder("destrename")
        assertTrue(Worlds.importWorldZip(zip, destRoot, "newworld"))
        assertTrue(File(destRoot, "newworld/level.dat").isFile)
        assertTrue(File(destRoot, "newworld_nether/level.dat").isFile)
        assertTrue(File(destRoot, "newworld_the_end/level.dat").isFile)
        // 旧名字的目录不应出现
        assertFalse(File(destRoot, "world").exists())
    }

    @Test
    fun `导入没有顶层目录的压缩包（世界文件在根）`() {
        val zip = File(tmp.newFolder("flat"), "flat.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("level.dat"))
            zos.write("nbt".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("region/"))
            zos.closeEntry()
        }
        val destRoot = tmp.newFolder("flatdest")
        assertTrue(Worlds.importWorldZip(zip, destRoot, "world"))
        assertTrue(File(destRoot, "world/level.dat").isFile)
        assertTrue(File(destRoot, "world/region").isDirectory)
    }

    /**
     * 两条逃逸路径都要挡住：
     * - `world/../server.properties`：normalized 落在 **worldsRoot（= 服务端目录）**，
     *   这正是以前 anchored 在 worldsRoot 时唯一放行的那一层——能覆盖 server.properties / ops.json。
     * - `world/../../escaped.txt`：越过服务端目录之外。
     *
     * 用例必须同时断言两件事：① 导入确实成功（否则「因为整个包被拒绝所以没写出文件」也算通过，
     * 护栏是假的）；② 服务端目录里除了目标世界目录没有别的条目。
     */
    @Test
    fun `导入时拒绝逃逸出目标目录的条目`() {
        val zip = File(tmp.newFolder("evil"), "evil.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("world/level.dat"))
            zos.write("nbt".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("world/../server.properties"))
            zos.write("pwned".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("world/../../escaped.txt"))
            zos.write("pwned".toByteArray())
            zos.closeEntry()
        }
        val destRoot = tmp.newFolder("evilward")
        assertTrue("正常世界应当照常导入", Worlds.importWorldZip(zip, destRoot, "world"))

        assertTrue(File(destRoot, "world/level.dat").isFile)
        assertFalse(
            "zip-slip 写进了服务端目录（server.properties 可被覆盖）",
            File(destRoot, "server.properties").exists()
        )
        assertFalse("zip-slip 逃出了服务端目录", File(tmp.root, "escaped.txt").exists())
        // 服务端目录里除目标世界外不应出现任何新条目
        assertEquals(listOf("world"), destRoot.listFiles()!!.map { it.name })
    }

    /** 反护栏：目标名字改了之后，`../server.properties` 依然不能被写成 `newworld/../server.properties`。 */
    @Test
    fun `改名导入时同样不能写到服务端目录`() {
        val zip = File(tmp.newFolder("evil2"), "evil2.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("myworld/level.dat"))
            zos.write("nbt".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("myworld/../ops.json"))
            zos.write("[{}]".toByteArray())
            zos.closeEntry()
        }
        val destRoot = tmp.newFolder("evilward2")
        assertTrue(Worlds.importWorldZip(zip, destRoot, "newworld"))
        assertFalse(File(destRoot, "ops.json").exists())
        assertEquals(listOf("newworld"), destRoot.listFiles()!!.map { it.name })
    }

    @Test
    fun `导入时忽略压缩包里夹带的无关顶层目录`() {
        val zip = File(tmp.newFolder("extra"), "extra.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("myworld/level.dat"))
            zos.write("nbt".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("evil-payload/run.sh"))
            zos.write("#!/bin/sh".toByteArray())
            zos.closeEntry()
        }
        val destRoot = tmp.newFolder("extradest")
        assertTrue(Worlds.importWorldZip(zip, destRoot, "world"))
        assertTrue(File(destRoot, "world/level.dat").isFile)
        assertFalse("无关顶层目录不应被解出来", File(destRoot, "evil-payload").exists())
    }

    @Test
    fun `顶层世界目录优先于根级散放文件`() {
        // 网上常见的包：世界目录在 `MyWorld/`，根目录还散着一个 readme.txt / .DS_Store。
        // 以前 rootLevel 一短路就按根级模式解包，最终 isWorld(destRoot) 为假 → 提示「没找到世界」。
        val zip = File(tmp.newFolder("mixed"), "mixed.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("MyWorld/level.dat"))
            zos.write("nbt".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("readme.txt"))
            zos.write("how to install".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(".DS_Store"))
            zos.write("mac".toByteArray())
            zos.closeEntry()
        }
        val destRoot = tmp.newFolder("mixeddest")
        assertTrue("顶层有世界目录时应按「整体改名」导入", Worlds.importWorldZip(zip, destRoot, "world"))
        assertTrue(File(destRoot, "world/level.dat").isFile)
        assertFalse("根级散放文件不应落盘", File(destRoot, "world/readme.txt").exists())
        assertFalse("根级散放文件不应落盘", File(destRoot, "world/.DS_Store").exists())
    }

    @Test
    fun `主世界不会被排在前面的下界目录抢走`() {
        val zip = File(tmp.newFolder("netherfirst"), "netherfirst.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            // X_nether 排在 X 之前：以前「第一个含 level.dat 的顶层目录」会把下界当主世界
            zos.putNextEntry(ZipEntry("X_nether/level.dat"))
            zos.write("nether".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("X/level.dat"))
            zos.write("overworld".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("X/region/r.0.0.mca"))
            zos.write("chunk".toByteArray())
            zos.closeEntry()
        }
        assertEquals("X", Worlds.inspectZip(zip).primary)

        val destRoot = tmp.newFolder("netherfirstdest")
        assertTrue(Worlds.importWorldZip(zip, destRoot, "imported"))
        assertEquals("overworld", File(destRoot, "imported/level.dat").readText())
        assertTrue(File(destRoot, "imported/region/r.0.0.mca").isFile)
        assertTrue("X_nether 应落到维度目录", File(destRoot, "imported_nether/level.dat").isFile)
    }

    @Test
    fun `同名维度目录已存在时拒绝导入而不是覆盖`() {
        val source = tmp.newFolder("dimconflict")
        javaWorld(source, "world", withNether = true)
        val zip = File(tmp.newFolder("dimzip"), "world.zip")
        val allNames = source.listFiles()!!.map { it.name }.toSet()
        assertTrue(Worlds.exportToZip(File(source, "world"), allNames, zip))

        val destRoot = tmp.newFolder("dimdest")
        // 服务端里已经有一个独立的 `imported_nether` 世界
        File(destRoot, "imported_nether").apply { mkdirs(); File(this, "level.dat").writeText("precious") }

        assertFalse("维度目录已存在时必须拒绝，不能静默覆写", Worlds.importWorldZip(zip, destRoot, "imported"))
        assertEquals("precious", File(destRoot, "imported_nether/level.dat").readText())
        assertFalse(File(destRoot, "imported").exists())
    }

    @Test
    fun `db 与 level dat 共存时按基岩世界识别`() {
        val root = tmp.newFolder("pnx")
        val dir = File(root, "world").apply {
            mkdirs()
            File(this, "db").mkdirs()
            File(this, "level.dat").writeText("x")
        }
        assertEquals(Worlds.Kind.Bedrock, Worlds.detectKind(dir))
    }
    @Test
    fun `导入不是世界的压缩包时判定失败且不落盘`() {
        val zip = File(tmp.newFolder("junk"), "junk.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("random/readme.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        val destRoot = tmp.newFolder("junkdest")
        assertFalse(Worlds.importWorldZip(zip, destRoot, "world"))
        assertFalse("导入失败应清理残留目录", File(destRoot, "world").exists())
    }

    /**
     * 根级散放的包里没有主世界时，失败清理**只能删本次写过的东西**。
     *
     * 回归：旧实现无条件 `dimensionDirs.forEach { deleteRecursively() }`，
     * 于是「foo 不存在、但服务端里有独立的 foo_nether」时，导入一个内容不合格的
     * foo.zip 会把与本次导入毫无关系的 foo_nether 一起删掉（不可逆的数据丢失）。
     */
    @Test
    fun `导入失败的清理不碰既有的兄弟维度世界`() {
        val zip = File(tmp.newFolder("junksib"), "foo.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            // 根级散放、且不含任何世界结构 => 会被判定为「不是世界包」
            zos.putNextEntry(ZipEntry("readme.txt"))
            zos.write("not a world".toByteArray())
            zos.closeEntry()
        }
        val root = tmp.newFolder("junksiBDest")
        // 导入前就存在、且属于**另一个**世界的下界目录
        File(root, "foo_nether").apply { mkdirs(); File(this, "level.dat").writeText("precious") }

        assertFalse(Worlds.importWorldZip(zip, root, "foo"))
        assertTrue("既有的 foo_nether 不能被导入失败的清理删掉", File(root, "foo_nether/level.dat").isFile)
        assertEquals("precious", File(root, "foo_nether/level.dat").readText())
        assertFalse("本次导入的残渣仍应清掉", File(root, "foo").exists())
    }

    // ── 导入命名 ────────────────────────────────────────────────

    /** 本应用导出的包：顶层就是世界目录，导入名取了导出文件名时必须以包内真实世界名为准。 */
    private fun appExportedZip(dirName: String, worldName: String): File {
        val zip = File(tmp.newFolder("appzip"), "世界_${dirName}_${worldName}_2026-09-25_21-16-09.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            listOf("level.dat", "region/r.0.0.mca").forEach { rel ->
                zos.putNextEntry(ZipEntry("$worldName/$rel"))
                zos.write("x".toByteArray())
                zos.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `导入导出包时不用世界_前缀当目录名`() {
        val zip = appExportedZip("77887878", "world")
        assertEquals("world", Worlds.importedWorldName(zip, "世界_world"))
        assertEquals("world", Worlds.importedWorldName(zip, "世界_77887878_world_2026-09-25_21-16-09"))
    }

    @Test
    fun `导入导出包后落盘目录符合世界命名规范`() {
        val zip = appExportedZip("77887878", "world")
        val root = tmp.newFolder("namedest")
        val name = Worlds.importedWorldName(zip, "世界_world")
        assertTrue(Worlds.importWorldZip(zip, root, name))
        assertTrue(File(root, "world/level.dat").isFile)
        assertFalse("不应再出现 世界_world 这种目录", File(root, "世界_world").exists())
    }

    @Test
    fun `期望名不来自导出文件名时原样保留`() {
        val zip = appExportedZip("srv", "world")
        // 用户自己起的名字、下载来的世界包名：都不该被「纠正」成包内的目录名
        assertEquals("我的世界", Worlds.importedWorldName(zip, "我的世界"))
        assertEquals("EpicAdventure", Worlds.importedWorldName(zip, "EpicAdventure"))
    }

    @Test
    fun `导出包里找不到顶层世界目录时退回文件名`() {
        val zip = File(tmp.newFolder("flatnamed"), "flat.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("level.dat"))
            zos.write("nbt".toByteArray())
            zos.closeEntry()
        }
        assertEquals("myworld", Worlds.importedWorldName(zip, "世界_myworld"))
        // 名字里没有可用的世界名时兜底成默认世界名，不能落出一个空目录名
        assertEquals("world", Worlds.importedWorldName(zip, "世界_"))
    }

    @Test
    fun `exportStem 还原三种导出文件名形态`() {
        assertEquals("world", Worlds.exportStem("world"))
        assertEquals("world", Worlds.exportStem("世界_world"))
        assertEquals("world", Worlds.exportStem("世界_77887878_world_2026-09-25_21-16-09"))
        // 不带「世界_」前缀且带时间戳的文件名：时间戳只是尾巴，世界名本身保持完整
        assertEquals("my_world", Worlds.exportStem("my_world_2026-09-25_21-16-09"))
    }

    // ── 删除 ────────────────────────────────────────────────────

    @Test
    fun `删除世界会连带删除维度目录`() {
        val root = tmp.newFolder("del")
        javaWorld(root, "world", withNether = true)
        javaWorld(root, "keep")
        val allNames = setOf("world", "world_nether", "keep")

        val removed = Worlds.deleteWorld(File(root, "world"), allNames)
        assertEquals(2, removed)
        assertFalse(File(root, "world").exists())
        assertFalse(File(root, "world_nether").exists())
        assertTrue(File(root, "keep").isDirectory)
    }

    @Test
    fun `删除不存在的世界返回 0`() {
        val root = tmp.newFolder("del2")
        assertEquals(0, Worlds.deleteWorld(File(root, "nope"), emptySet()))
    }

    @Test
    fun `越界判断能识别目录是否在根目录内`() {
        val root = tmp.newFolder("inside")
        val child = File(root, "world")
        assertTrue(Worlds.isInside(child, root))
        assertFalse(Worlds.isInside(File(root.parentFile, "outside"), root))
        assertFalse(Worlds.isInside(root, root))
    }

    // ── 大小文本 ────────────────────────────────────────────────

    @Test
    fun `大小文本按量级格式化`() {
        assertEquals("512 B", Worlds.formatSize(512))
        assertEquals("1.0 KB", Worlds.formatSize(1024))
        assertEquals("1.0 MB", Worlds.formatSize(1024L * 1024))
        assertEquals("1.00 GB", Worlds.formatSize(1024L * 1024 * 1024))
    }
}
