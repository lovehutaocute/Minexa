package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 跨版本安装计划。
 *
 * 这些用例对应真实存在过的包形态差异，每一条都曾经（或必然）让「只按最新版写」
 * 的安装逻辑失败：
 *  - 主程序 40MB 级的老包被 50MB 阈值判成残包；
 *  - 带顶层目录的重打包产出散落文件、主程序落进子目录；
 *  - `bedrock_server_symbols.debug` 白占几百 MB；
 *  - 老包缺 `server.properties` 直接判「不是完整安装包」。
 *
 * 判据全部来自 zip 本身，不来自版本号 —— 所以这里用「假版本号 + 真实结构」
 * 的包来验证：结论必须跟着结构走。
 */
class BdsCompatibilityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun buildZip(name: String, entries: Map<String, ByteArray>): File {
        val zip = temp.newFile(name)
        ZipOutputStream(zip.outputStream()).use { zos ->
            entries.forEach { (entryName, body) ->
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(body)
                zos.closeEntry()
            }
        }
        return zip
    }

    /** 假 ELF 头：只写 magic + e_machine，够 elfArch 识别 */
    private fun elf(machine: Int, size: Int = 64): ByteArray {
        val bytes = ByteArray(maxOf(size, 20))
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        bytes[6] = 1
        bytes[18] = (machine and 0xFF).toByte()
        bytes[19] = ((machine shr 8) and 0xFF).toByte()
        return bytes
    }

    @Test
    fun legacyDynamicPackageIsPlannedWithLibs() {
        val zip = buildZip(
            "legacy.zip",
            mapOf(
                "bedrock_server" to elf(0x3E, size = 9 * 1024 * 1024),
                "libCrypto.so" to elf(0x3E, size = 512),
                "libssl.so" to elf(0x3E, size = 512),
                "server.properties" to "server-port=19132\n".toByteArray(),
                "bedrock_server_symbols.debug" to ByteArray(1024)
            )
        )
        val plan = BdsCompatibility.plan(zip, "1.16.100.4")
        assertEquals("", plan.rootPrefix)
        assertEquals("bedrock_server", plan.entryPath)
        assertEquals(listOf("libCrypto.so", "libssl.so"), plan.libs)
        assertTrue(plan.isDynamic)
        assertTrue(plan.hasProperties)
        assertEquals(listOf("bedrock_server_symbols.debug"), plan.pruned)
        assertTrue(plan.notes.any { it.contains("动态链接") })
    }

    @Test
    fun nestedRootDirectoryIsStripped() {
        val zip = buildZip(
            "nested.zip",
            mapOf(
                "bedrock-server-1.20.1/" to ByteArray(0),
                "bedrock-server-1.20.1/bedrock_server" to elf(0x3E, size = 9 * 1024 * 1024),
                "bedrock-server-1.20.1/server.properties" to "server-port=19132\n".toByteArray(),
                "bedrock-server-1.20.1/behavior_packs/vanilla/manifest.json" to "{}".toByteArray()
            )
        )
        val plan = BdsCompatibility.plan(zip, "1.20.1.2")
        assertEquals("bedrock-server-1.20.1/", plan.rootPrefix)
        assertEquals("bedrock_server", plan.entryName)
        assertTrue(plan.notes.any { it.contains("多一层目录") })

        val target = temp.newFolder("out")
        val count = BdsCompatibility.extract(zip, target, plan)
        assertEquals(3, count)
        assertTrue(File(target, "bedrock_server").isFile)
        assertTrue(File(target, "server.properties").isFile)
        assertTrue(File(target, "behavior_packs/vanilla/manifest.json").isFile)
        assertFalse(File(target, "bedrock-server-1.20.1").exists())
    }

    @Test
    fun symbolsAreNotExtracted() {
        val zip = buildZip(
            "symbols.zip",
            mapOf(
                "bedrock_server" to elf(0x3E, size = 9 * 1024 * 1024),
                "bedrock_server_symbols.debug" to ByteArray(4096),
                "server.properties" to "x=1\n".toByteArray()
            )
        )
        val plan = BdsCompatibility.plan(zip, "1.17.41.1")
        val target = temp.newFolder("out-symbols")
        BdsCompatibility.extract(zip, target, plan)
        assertFalse(File(target, "bedrock_server_symbols.debug").exists())
        assertTrue(File(target, "bedrock_server").isFile)
    }

    @Test
    fun missingPropertiesIsReportedNotFatal() {
        val zip = buildZip(
            "no-props.zip",
            mapOf("bedrock_server" to elf(0x3E, size = 9 * 1024 * 1024))
        )
        val plan = BdsCompatibility.plan(zip, "1.21.100-preview.2")
        assertFalse(plan.hasProperties)
        assertTrue(plan.notes.any { it.contains("server.properties") })
        // 合成出来的模板必须带本项目的两条约定：离线可跑、不在离线模式开白名单
        val template = BdsCompatibility.propertiesTemplate(port = 19133)
        assertTrue(template.contains("server-port=19133"))
        assertTrue(template.contains("online-mode=false"))
        assertTrue(template.contains("allow-list=false"))
    }

    @Test
    fun windowsPackageIsRejectedWithClearReason() {
        val zip = buildZip(
            "win.zip",
            mapOf("bedrock_server.exe" to elf(0x8664, size = 9 * 1024 * 1024))
        )
        try {
            BdsCompatibility.plan(zip, "1.21.0.3")
            fail("Windows 包必须被拒绝")
        } catch (e: BdsCompatibility.PlanFailed) {
            assertTrue(e.message!!.contains("Windows"))
        }
    }

    @Test
    fun pathTraversalIsRejected() {
        val zip = buildZip(
            "evil.zip",
            mapOf(
                "bedrock_server" to elf(0x3E, size = 9 * 1024 * 1024),
                "../../escaped.txt" to "x".toByteArray()
            )
        )
        try {
            BdsCompatibility.plan(zip, "1.21.0.3")
            fail("路径穿越必须被拒绝")
        } catch (e: BdsCompatibility.PlanFailed) {
            assertTrue(e.message!!.contains("非法路径"))
        }
        assertFalse(File(temp.root, "escaped.txt").exists())
    }

    @Test
    fun installedPackageIsVerifiedAgainstPlan() {
        val dir = temp.newFolder("server")
        val entry = File(dir, BdsSupport.ENTRY_NAME)
        entry.writeBytes(elf(0x3E, size = 9 * 1024 * 1024))
        val plan = BdsCompatibility.Plan(
            version = "1.20.1.2",
            entryPath = "bedrock_server",
            rootPrefix = "",
            entryBytes = entry.length(),
            libs = emptyList(),
            hasProperties = true,
            pruned = emptyList(),
            notes = emptyList()
        )
        // x86_64 主程序在 arm64 设备上可跑（box64 转译），不该判失败
        assertNull(BdsCompatibility.verifyInstalled(dir, plan, listOf("arm64-v8a")))
    }

    @Test
    fun missingLibFailsVerification() {
        val dir = temp.newFolder("server-lib")
        File(dir, BdsSupport.ENTRY_NAME).writeBytes(elf(0x3E, size = 9 * 1024 * 1024))
        val plan = BdsCompatibility.Plan(
            version = "1.16.100.4",
            entryPath = "bedrock_server",
            rootPrefix = "",
            entryBytes = File(dir, BdsSupport.ENTRY_NAME).length(),
            libs = listOf("libCrypto.so"),
            hasProperties = true,
            pruned = emptyList(),
            notes = emptyList()
        )
        val problem = BdsCompatibility.verifyInstalled(dir, plan, listOf("arm64-v8a"))
        assertTrue(problem != null && problem.contains("libCrypto.so"))
    }

    @Test
    fun staleLibsArePrunedOnlyWhenNotInPlan() {
        val dir = temp.newFolder("server-prune")
        File(dir, BdsSupport.ENTRY_NAME).writeText("core")
        File(dir, "libCrypto.so").writeText("old")
        File(dir, "libssl.so.1.1").writeText("old-versioned")
        File(dir, "server.properties").writeText("server-port=19132")

        val removed = BdsInstallRules.pruneStaleLibs(dir, keep = setOf("libCrypto.so"))
        assertEquals(listOf("libssl.so.1.1"), removed.map { it.name })
        assertTrue(File(dir, "libCrypto.so").isFile)
        // 非 .so 的用户文件一个都不能碰
        assertTrue(File(dir, "server.properties").isFile)
    }
}
