package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * BDS 核心替换时「哪些能覆盖、哪些绝不能碰」的规则。
 *
 * 这些规则错了就是不可逆的毁数据，而完整更新流程要下 93MB 包、要跑 box64 环境，
 * 没法在单测里跑 —— 所以规则被抽到 [BdsInstallRules]，在这里用真实的临时目录锁死。
 * 场景都按官方 1.26.61.1 安装包的真实结构构造（`server.properties` 是默认模板、
 * `allowlist.json` / `permissions.json` 是 3 字节的 `[]`、包里没有 `worlds/`）。
 */
class BdsInstallRulesTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 构造官方安装包解压后的目录结构（staging） */
    private fun stagePackage(): File {
        val staging = temp.newFolder("staging")
        File(staging, BdsSupport.ENTRY_NAME).writeText("new-core-binary")
        File(staging, "server.properties").writeText("server-port=19132\ndefault-template=yes\n")
        File(staging, "allowlist.json").writeText("[]")
        File(staging, "permissions.json").writeText("[]")
        File(staging, "definitions").mkdirs()
        File(staging, "definitions/packet.json").writeText("new-definitions")
        File(staging, "behavior_packs/vanilla_1.26.50").mkdirs()
        File(staging, "behavior_packs/vanilla_1.26.50/manifest.json").writeText("new-pack")
        File(staging, "resource_packs/new_only").mkdirs()
        File(staging, "resource_packs/new_only/manifest.json").writeText("brand-new")
        return staging
    }

    /** 构造一台已经在跑的 BDS 服务器目录（带用户数据） */
    private fun existingServer(): File {
        val dir = temp.newFolder("server")
        File(dir, BdsSupport.ENTRY_NAME).writeText("old-core-binary")
        File(dir, "server.properties").writeText("server-port=19999\nallow-list=true\nuser-line=keep-me\n")
        File(dir, "allowlist.json").writeText("""[{"name":"Steve"}]""")
        File(dir, "permissions.json").writeText("""[{"permission":"operator","xuid":"123"}]""")
        File(dir, "worlds/Bedrock level").mkdirs()
        File(dir, "worlds/Bedrock level/level.dat").writeText("world-data")
        File(dir, "definitions").mkdirs()
        File(dir, "definitions/old.json").writeText("old-definitions")
        File(dir, "behavior_packs/user_installed").mkdirs()
        File(dir, "behavior_packs/user_installed/manifest.json").writeText("user-pack")
        return dir
    }

    @Test
    fun `用户配置与白名单权限不被官方默认值覆盖`() {
        val staging = stagePackage()
        val server = existingServer()
        BdsInstallRules.installTree(staging, server, mutableListOf())

        val props = File(server, "server.properties").readText()
        assertTrue("端口必须保留用户设置", props.contains("server-port=19999"))
        assertTrue("自定义行必须保留", props.contains("user-line=keep-me"))
        assertFalse("不能被默认模板覆盖", props.contains("default-template=yes"))

        assertTrue(File(server, "allowlist.json").readText().contains("Steve"))
        assertTrue(File(server, "permissions.json").readText().contains("operator"))
    }

    @Test
    fun `世界目录完全不被触碰`() {
        val staging = stagePackage()
        // 极端场景：官方包将来若带上 worlds/，也不能覆盖用户存档
        File(staging, "worlds/Bedrock level").mkdirs()
        File(staging, "worlds/Bedrock level/level.dat").writeText("SHOULD-NOT-WIN")
        val server = existingServer()

        BdsInstallRules.installTree(staging, server, mutableListOf())

        assertEquals(
            "world-data",
            File(server, "worlds/Bedrock level/level.dat").readText()
        )
    }

    @Test
    fun `官方文件被更新且用户额外安装的包不会被删`() {
        val staging = stagePackage()
        val server = existingServer()
        BdsInstallRules.installTree(staging, server, mutableListOf())

        assertEquals("new-core-binary", File(server, BdsSupport.ENTRY_NAME).readText())
        assertEquals("new-definitions", File(server, "definitions/packet.json").readText())
        assertTrue(
            "用户自己装的行为包必须还在",
            File(server, "behavior_packs/user_installed/manifest.json").isFile
        )
        assertTrue(
            "新版内容也要装进去",
            File(server, "behavior_packs/vanilla_1.26.50/manifest.json").isFile
        )
        assertTrue(
            "新增的资源包也要装进去",
            File(server, "resource_packs/new_only/manifest.json").isFile
        )
    }

    @Test
    fun `回滚清单只记录本次新建的文件`() {
        val staging = stagePackage()
        val server = existingServer()
        val written = mutableListOf<File>()
        BdsInstallRules.installTree(staging, server, written)

        val names = written.map { it.name }.toSet()
        assertTrue("新建的包目录文件要能被回滚删掉", "manifest.json" in names)
        assertFalse("被覆盖的旧文件不在清单里（由旧核心备份与配置备份兜底）", BdsSupport.ENTRY_NAME in names)
    }

    @Test
    fun `历史旧核心只保留最近一份`() {
        val server = existingServer()
        File(server, "bedrock_server.old-1.26.40.1").writeText("a")
        File(server, "bedrock_server.old-1.26.52.3").writeText("b")
        File(server, "bedrock_server.old-1.26.61.1").writeText("c")

        val removed = BdsInstallRules.pruneOldEntries(server, keepName = "bedrock_server.old-1.26.61.1")

        assertEquals(2, removed)
        assertTrue(File(server, "bedrock_server.old-1.26.61.1").isFile)
        assertFalse(File(server, "bedrock_server.old-1.26.40.1").exists())
        assertFalse(File(server, "bedrock_server.old-1.26.52.3").exists())
        // 主程序不能被误当成旧核心删掉
        assertTrue(File(server, BdsSupport.ENTRY_NAME).isFile)
    }
}
