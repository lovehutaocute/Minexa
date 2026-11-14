package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [BdsPacks] 的扫描 / manifest 解析 / 世界注册表读写测试。
 *
 * 断言全部对齐真实 BDS 行为（`docs/BDS-ADDONS-WORLDS-VERIFICATION.md`）：
 * 用 modules 的 uuid 登记会被静默忽略、数组顺序就是加载栈顺序、版本按
 * 「同 major 且 已装 ≤ 请求」命中、坏 JSON 不能被覆盖写。
 */
class BdsPacksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bpUuid = "11111111-1111-4111-8111-111111111111"
    private val rpUuid = "33333333-3333-4333-8333-333333333333"

    // ── 造包 ────────────────────────────────────────────────────

    private fun pack(
        root: File,
        dirName: String,
        kind: String,
        uuid: String,
        version: List<Int> = listOf(1, 0, 0),
        deps: String = "",
        minEngine: String? = "[1,20,0]",
        moduleType: String = if (kind == "resources") "resources" else "data",
        raw: String? = null
    ): File {
        val dir = File(root, dirName).apply { mkdirs() }
        val text = raw ?: buildString {
            append("{\n  \"format_version\": 2,\n  \"header\": {\n")
            append("    \"name\": \"$dirName\",\n    \"description\": \"test pack\",\n")
            append("    \"uuid\": \"$uuid\",\n    \"version\": ${version.joinToString(",", "[", "]")}")
            if (minEngine != null) append(",\n    \"min_engine_version\": $minEngine")
            append("\n  },\n  \"modules\": [{ \"type\": \"$moduleType\", \"uuid\": ")
            append("\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\", \"version\": ")
            append(version.joinToString(",", "[", "]"))
            append(" }]")
            if (deps.isNotEmpty()) append(",\n  \"dependencies\": $deps")
            append("\n}")
        }
        File(dir, "manifest.json").writeText(text)
        return dir
    }

    // ── manifest ────────────────────────────────────────────────

    @Test
    fun `解析正常 manifest`() {
        val dir = tmp.newFolder()
        pack(dir, "BP", "data", bpUuid, deps = """[{"uuid":"$rpUuid","version":[2,0,0]}]""")
        val m = BdsPacks.parseManifest(File(dir, "BP/manifest.json"))
        assertEquals(bpUuid, m.uuid)
        assertEquals(listOf(1, 0, 0), m.version)
        assertEquals(listOf(1, 20, 0), m.minEngineVersion)
        assertEquals(listOf("data"), m.moduleTypes)
        assertEquals(1, m.dependencies.size)
        assertEquals(listOf(2, 0, 0), m.dependencies[0].version)
        assertTrue("正常包不应有问题", m.problems.isEmpty())
    }

    @Test
    fun `缺少 uuid 与 min_engine_version 时给出 BDS 原文`() {
        val dir = tmp.newFolder()
        pack(dir, "BP", "data", bpUuid, raw = """{"format_version":2,"header":{"name":"x","version":[1,0,0]},"modules":[]}""")
        val m = BdsPacks.parseManifest(File(dir, "BP/manifest.json"))
        assertNull(m.uuid)
        assertTrue(m.problems.any { it.contains("Missing '/header/uuid'") })
        assertTrue(m.problems.any { it.contains("Missing '/header/min_engine_version'") })
    }

    @Test
    fun `带 BOM 与注释的 manifest 仍能解析`() {
        val dir = tmp.newFolder()
        val raw = "\uFEFF// 这是注释\n" + """
            {
              /* 块注释 */
              "format_version": 2,
              "header": { "name": "中文包", "uuid": "$bpUuid", "version": [1,0,0],
                          "min_engine_version": [1,20,0],
                          "description": "http://example.com 不应被当成注释" },
              "modules": [{ "type": "data", "uuid": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "version": [1,0,0] }]
            }
        """.trimIndent()
        pack(dir, "BP", "data", bpUuid, raw = raw)
        val m = BdsPacks.parseManifest(File(dir, "BP/manifest.json"))
        assertEquals(bpUuid, m.uuid)
        assertTrue(m.problems.isEmpty())
        assertTrue(m.description.contains("http://"))
    }

    @Test
    fun `版本可以写成字符串形式`() {
        val dir = tmp.newFolder()
        pack(
            dir, "BP", "data", bpUuid,
            raw = """{"format_version":3,"header":{"name":"x","uuid":"$bpUuid","version":"1.2.3","min_engine_version":"1.21.0"},"modules":[]}"""
        )
        val m = BdsPacks.parseManifest(File(dir, "BP/manifest.json"))
        assertEquals(listOf(1, 2, 3), m.version)
        assertEquals(listOf(1, 21, 0), m.minEngineVersion)
    }

    @Test
    fun `脚本模块依赖不算磁盘包`() {
        val dir = tmp.newFolder()
        pack(
            dir, "BP", "data", bpUuid,
            deps = """[{"module_name":"@minecraft/server","version":"1.9.0"}]"""
        )
        val m = BdsPacks.parseManifest(File(dir, "BP/manifest.json"))
        assertEquals(1, m.dependencies.size)
        assertTrue(m.dependencies[0].isScriptModule)
    }

    // ── 扫描 ────────────────────────────────────────────────────

    @Test
    fun `扫描全局与世界内的包并区分来源`() {
        val srv = tmp.newFolder("srv")
        pack(File(srv, "behavior_packs"), "BP_global", "data", bpUuid)
        pack(File(srv, "resource_packs"), "RP_global", "resources", rpUuid)
        pack(File(srv, "worlds/Bedrock level/behavior_packs"), "BP_world", "data",
            "22222222-2222-4222-8222-222222222222")
        pack(File(srv, "development_behavior_packs"), "BP_dev", "data",
            "44444444-4444-4444-8444-444444444444")

        val scan = BdsPacks.scan(srv, "Bedrock level")
        val byName = scan.packs.associateBy { it.name }
        assertEquals(BdsPacks.Scope.GLOBAL, byName["BP_global"]!!.scope)
        assertEquals(BdsPacks.Scope.GLOBAL, byName["RP_global"]!!.scope)
        assertEquals(BdsPacks.Scope.WORLD, byName["BP_world"]!!.scope)
        assertEquals(BdsPacks.Scope.DEV, byName["BP_dev"]!!.scope)
        assertEquals(BdsPacks.Kind.RESOURCE, byName["RP_global"]!!.kind)
        assertEquals(BdsPacks.Kind.BEHAVIOR, byName["BP_global"]!!.kind)
    }

    @Test
    fun `包目录多套一层也能被找到`() {
        val srv = tmp.newFolder("srv2")
        val nested = File(srv, "behavior_packs/nested").apply { mkdirs() }
        pack(nested, "BP", "data", bpUuid)
        val scan = BdsPacks.scan(srv, null)
        assertEquals(1, scan.packs.size)
        assertEquals(BdsPacks.Kind.BEHAVIOR, scan.packs[0].kind)
    }

    @Test
    fun `目录里的压缩包只列出不当作已安装`() {
        val srv = tmp.newFolder("srv3")
        val bp = File(srv, "behavior_packs").apply { mkdirs() }
        File(bp, "addon.mcpack").writeText("zip")
        File(bp, "bundle.mcaddon").writeText("zip")
        val scan = BdsPacks.scan(srv, null)
        assertTrue(scan.packs.isEmpty())
        assertEquals(2, scan.archives.size)
        assertEquals(BdsPacks.Kind.BEHAVIOR, scan.archives[0].kind)
    }

    @Test
    fun `内置包按 uuid 清单标记`() {
        val srv = tmp.newFolder("srv4")
        pack(File(srv, "behavior_packs"), "vanilla", "data", bpUuid)
        val scan = BdsPacks.scan(srv, null, builtinUuids = setOf(bpUuid))
        assertEquals(BdsPacks.Scope.BUILTIN, scan.packs[0].scope)
    }

    @Test
    fun `坏 manifest 的包仍出现在列表里并带问题说明`() {
        val srv = tmp.newFolder("srv5")
        val bp = File(srv, "behavior_packs").apply { mkdirs() }
        pack(bp, "broken", "data", bpUuid, raw = "{ this is not json ]]")
        val scan = BdsPacks.scan(srv, null)
        assertEquals(1, scan.packs.size)
        assertTrue(scan.packs[0].problems.isNotEmpty())
        assertFalse(scan.packs[0].valid)
    }

    // ── 世界注册表 ──────────────────────────────────────────────

    @Test
    fun `写出的注册表是严格 JSON 且能被读回`() {
        val world = tmp.newFolder("world")
        val entries = listOf(
            BdsPacks.Entry(bpUuid, listOf(1, 0, 0)),
            BdsPacks.Entry("22222222-2222-4222-8222-222222222222", listOf(2, 1, 7))
        )
        assertTrue(BdsPacks.writeWorldEntries(world, BdsPacks.Kind.BEHAVIOR, entries))

        val text = File(world, "world_behavior_packs.json").readText()
        assertTrue(text.startsWith("["))
        assertTrue(text.contains("\"pack_id\": \"$bpUuid\""))
        assertTrue(text.contains("\"version\": [2,1,7]"))
        assertFalse("不能带 BOM", text.startsWith("\uFEFF"))

        val (back, broken) = BdsPacks.readWorldEntries(world, BdsPacks.Kind.BEHAVIOR)
        assertFalse(broken)
        assertEquals(entries, back)
    }

    @Test
    fun `注册表不存在时返回空且不算损坏`() {
        val world = tmp.newFolder("empty")
        val (list, broken) = BdsPacks.readWorldEntries(world, BdsPacks.Kind.RESOURCE)
        assertTrue(list.isEmpty())
        assertFalse(broken)
    }

    @Test
    fun `坏注册表不能让启用操作把它覆盖成空表`() {
        val world = tmp.newFolder("bad")
        val f = File(world, "world_behavior_packs.json").apply { writeText("{ 不是数组 }") }
        val (_, broken) = BdsPacks.readWorldEntries(world, BdsPacks.Kind.BEHAVIOR)
        assertTrue(broken)

        assertFalse("解析失败时必须拒绝写入", BdsPacks.setEnabled(
            world, BdsPacks.Kind.BEHAVIOR, BdsPacks.Entry(bpUuid, listOf(1, 0, 0)), true
        ))
        assertEquals("{ 不是数组 }", f.readText())
    }

    @Test
    fun `启用与停用只动一条并保持顺序`() {
        val world = tmp.newFolder("toggle")
        val a = BdsPacks.Entry(bpUuid, listOf(1, 0, 0))
        val b = BdsPacks.Entry("22222222-2222-4222-8222-222222222222", listOf(1, 0, 0))
        val c = BdsPacks.Entry("44444444-4444-4444-8444-444444444444", listOf(1, 0, 0))
        BdsPacks.writeWorldEntries(world, BdsPacks.Kind.BEHAVIOR, listOf(a, b, c))

        assertTrue(BdsPacks.setEnabled(world, BdsPacks.Kind.BEHAVIOR, b, false))
        assertEquals(listOf(a, c), BdsPacks.readWorldEntries(world, BdsPacks.Kind.BEHAVIOR).first)

        assertTrue(BdsPacks.setEnabled(world, BdsPacks.Kind.BEHAVIOR, b, true))
        assertEquals(
            listOf(a, c, b),
            BdsPacks.readWorldEntries(world, BdsPacks.Kind.BEHAVIOR).first
        )
    }

    @Test
    fun `大写 uuid 的登记条目按小写归一`() {
        val world = tmp.newFolder("case")
        assertTrue(BdsPacks.writeWorldEntries(
            world, BdsPacks.Kind.BEHAVIOR, listOf(BdsPacks.Entry(bpUuid.uppercase(), listOf(1, 0, 0)))
        ))
        val (list, _) = BdsPacks.readWorldEntries(world, BdsPacks.Kind.BEHAVIOR)
        assertEquals(bpUuid, list[0].uuid)
    }

    @Test
    fun `孤儿条目会被识别出来`() {
        val srv = tmp.newFolder("orphan")
        pack(File(srv, "behavior_packs"), "BP", "data", bpUuid)
        val worldDir = File(srv, "worlds/w").apply { mkdirs() }
        BdsPacks.writeWorldEntries(
            worldDir, BdsPacks.Kind.BEHAVIOR,
            listOf(
                BdsPacks.Entry(bpUuid, listOf(1, 0, 0)),
                BdsPacks.Entry("99999999-9999-4999-8999-999999999999", listOf(1, 0, 0))
            )
        )
        val scan = BdsPacks.scan(srv, "w")
        val orphans = scan.orphans(BdsPacks.Kind.BEHAVIOR)
        assertEquals(1, orphans.size)
        assertEquals("99999999-9999-4999-8999-999999999999", orphans[0].uuid)
    }

    // ── 版本与依赖 ──────────────────────────────────────────────

    @Test
    fun `版本命中规则同 major 且已装不高于请求`() {
        // 实测：请求 [1,0,1] 命中磁盘 [1,0,0]；请求 [9,9,9] 不命中
        assertTrue(BdsPacks.versionAccepted(listOf(1, 0, 0), listOf(1, 0, 1)))
        assertTrue(BdsPacks.versionAccepted(listOf(1, 0, 0), listOf(1, 5, 0)))
        assertTrue(BdsPacks.versionAccepted(listOf(1, 0, 0), listOf(1, 0, 99)))
        assertTrue(BdsPacks.versionAccepted(listOf(2, 0, 0), listOf(2, 0, 0)))
        // 请求低于已装：不命中
        assertFalse(BdsPacks.versionAccepted(listOf(1, 5, 0), listOf(1, 0, 0)))
        assertFalse(BdsPacks.versionAccepted(listOf(2, 3, 0), listOf(2, 0, 0)))
        // major 不同：不命中
        assertFalse(BdsPacks.versionAccepted(listOf(1, 0, 0), listOf(2, 0, 0)))
        assertFalse(BdsPacks.versionAccepted(listOf(1, 0, 0), listOf(9, 9, 9)))
        // 缺位按 0 补齐
        assertTrue(BdsPacks.versionAccepted(listOf(1, 0), listOf(1, 0, 0)))
    }

    @Test
    fun `依赖闭包把被依赖包一并带回`() {
        val srv = tmp.newFolder("deps")
        pack(File(srv, "behavior_packs"), "BP_base", "data", rpUuid)
        pack(
            File(srv, "behavior_packs"), "BP_top", "data", bpUuid,
            deps = """[{"uuid":"$rpUuid","version":[1,0,0]}]"""
        )
        val scan = BdsPacks.scan(srv, null)
        val top = scan.packs.first { it.name == "BP_top" }
        val res = BdsPacks.resolveDependencies(top, scan.packs)
        assertEquals(1, res.required.size)
        assertEquals("BP_base", res.required[0].name)
        assertTrue(res.missing.isEmpty())
    }

    @Test
    fun `依赖版本不满足时记为缺失`() {
        val srv = tmp.newFolder("deps2")
        pack(File(srv, "behavior_packs"), "BP_base", "data", rpUuid)
        pack(
            File(srv, "behavior_packs"), "BP_top", "data", bpUuid,
            deps = """[{"uuid":"$rpUuid","version":[9,9,9]}]"""
        )
        val scan = BdsPacks.scan(srv, null)
        val top = scan.packs.first { it.name == "BP_top" }
        val res = BdsPacks.resolveDependencies(top, scan.packs)
        assertTrue(res.required.isEmpty())
        assertEquals(1, res.missing.size)
    }

    @Test
    fun `目录名冲突时追加序号`() {
        val root = tmp.newFolder("names")
        assertEquals("MyPack", BdsPacks.uniqueDirName(root, "MyPack"))
        File(root, "MyPack").mkdirs()
        assertEquals("MyPack-2", BdsPacks.uniqueDirName(root, "MyPack"))
    }

    @Test
    fun `目录名净化去掉非法字符`() {
        assertEquals("a_b_c", BdsPacks.sanitizeDirName("a/b:c"))
        assertEquals("pack", BdsPacks.sanitizeDirName("   "))
    }

    // ── 压缩包安装 ──────────────────────────────────────────────

    /** 造一个 zip：entries 是「相对路径 → 内容」。 */
    private fun zip(name: String, entries: Map<String, String>): File {
        val f = File(tmp.newFolder("src-" + name.replace('.', '_')), name)
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (path, text) ->
                z.putNextEntry(java.util.zip.ZipEntry(path))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return f
    }

    private fun manifest(name: String, uuid: String, moduleType: String, version: List<Int> = listOf(1, 0, 0)) =
        """{"format_version":2,"header":{"name":"$name","uuid":"$uuid",
           "version":${version.joinToString(",", "[", "]")},"min_engine_version":[1,20,0]},
           "modules":[{"type":"$moduleType","uuid":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
           "version":${version.joinToString(",", "[", "]")}}]}"""

    private fun dests(): (BdsPacks.Kind) -> File {
        val root = tmp.newFolder("server-" + System.nanoTime())
        File(root, "behavior_packs").mkdirs()
        File(root, "resource_packs").mkdirs()
        return { kind -> File(root, kind.dirName) }
    }

    @Test
    fun `mcpack 解压到行为包目录`() {
        val f = zip("cool.mcpack", mapOf(
            "manifest.json" to manifest("Cool Pack", bpUuid, "data"),
            "functions/hello.mcfunction" to "say hi\n"
        ))
        val dest = dests()
        val r = BdsPacks.installArchive(f, dest)
        assertTrue(r.error, r.success)
        assertEquals(1, r.installed.size)
        assertEquals(BdsPacks.Kind.BEHAVIOR, r.installed[0].kind)
        val dir = r.installed[0].dir
        assertEquals("Cool Pack", dir.name)
        assertTrue(File(dir, "manifest.json").isFile)
        // 子目录必须整棵解出来，否则行为包里的 function/结构就全丢了
        assertTrue(File(dir, "functions/hello.mcfunction").isFile)
    }

    @Test
    fun `mcaddon 拆成行为包与资源包各一份`() {
        val f = zip("bundle.mcaddon", mapOf(
            "bp/manifest.json" to manifest("BP One", bpUuid, "data"),
            "bp/items/x.json" to "{}",
            "rp/manifest.json" to manifest("RP One", rpUuid, "resources"),
            "rp/textures/x.png" to "png"
        ))
        val dest = dests()
        val r = BdsPacks.installArchive(f, dest)
        assertTrue(r.error, r.success)
        assertEquals(2, r.installed.size)
        assertEquals(1, r.installed.count { it.kind == BdsPacks.Kind.BEHAVIOR })
        assertEquals(1, r.installed.count { it.kind == BdsPacks.Kind.RESOURCE })
        val b = r.installed.first { it.kind == BdsPacks.Kind.BEHAVIOR }.dir
        assertTrue(File(b, "items/x.json").isFile)      // 前缀必须被剥掉
        assertFalse(File(b, "bp/items/x.json").exists())
    }

    @Test
    fun `内层 subpack 的 manifest 不单独安装`() {
        val f = zip("sub.mcpack", mapOf(
            "manifest.json" to manifest("Outer", bpUuid, "data"),
            "subpacks/lite/manifest.json" to manifest("Inner", "55555555-5555-4555-8555-555555555555", "data"),
            "subpacks/lite/x.json" to "{}"
        ))
        val dest = dests()
        val r = BdsPacks.installArchive(f, dest)
        assertTrue(r.error, r.success)
        assertEquals(1, r.installed.size)
        // 内层目录还是原样保留在包里（属于同一个包），只是不单独成包
        assertTrue(File(r.installed[0].dir, "subpacks/lite/x.json").isFile)
    }

    @Test
    fun `越界路径的条目被丢弃`() {
        val f = zip("evil.mcpack", mapOf(
            "manifest.json" to manifest("Evil", bpUuid, "data"),
            "../../escape.txt" to "boom"
        ))
        val dest = dests()
        val r = BdsPacks.installArchive(f, dest)
        // 只剩 manifest 一条 → 仍然算装上，但越界文件绝不能落到包目录之外
        val root = dest(BdsPacks.Kind.BEHAVIOR).parentFile!!
        assertFalse(File(root, "escape.txt").exists())
        assertFalse(File(root.parentFile, "escape.txt").exists())
    }

    @Test
    fun `同 uuid 同版本重复安装会被跳过`() {
        val dest = dests()
        val f1 = zip("a.mcpack", mapOf("manifest.json" to manifest("Same", bpUuid, "data")))
        assertTrue(BdsPacks.installArchive(f1, dest).success)
        val f2 = zip("b.mcpack", mapOf("manifest.json" to manifest("Same", bpUuid, "data")))
        val r2 = BdsPacks.installArchive(f2, dest)
        assertFalse(r2.success)
        assertEquals(1, r2.skipped.size)
    }

    @Test
    fun `没有 manifest 直接报错`() {
        val f = zip("plain.zip", mapOf("readme.txt" to "hi"))
        val r = BdsPacks.installArchive(f, dests())
        assertFalse(r.success)
        assertEquals("压缩包里没有 manifest.json", r.error)
    }

    @Test
    fun `装成功后原压缩包被移走`() {
        val f = zip("move.mcpack", mapOf("manifest.json" to manifest("Moved", bpUuid, "data")))
        val dest = dests()
        val out = File(tmp.newFolder("archived"), "move.mcpack")
        val r = BdsPacks.installArchive(f, dest, archiveOut = out)
        assertTrue(r.success)
        assertTrue(out.isFile)
        assertFalse(f.exists())
    }
}
