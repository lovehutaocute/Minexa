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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * BDS 的平台判定与解压行为。
 *
 * 这些断言锁的是「不许把不支持伪装成支持」：架构不匹配时必须失败并给出替代方案，
 * 而不是返回 ok 让上层拉起一个必然崩的进程。
 */
class BdsSupportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val officialJson = """
        {"result":{"links":[
          {"downloadType":"serverBedrockWindows","downloadUrl":"https://www.minecraft.net/bedrockdedicatedserver/bin-win/bedrock-server-1.26.52.3.zip"},
          {"downloadType":"serverBedrockLinux","downloadUrl":"https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-1.26.52.3.zip"},
          {"downloadType":"serverJar","downloadUrl":"https://piston-data.mojang.com/v1/objects/x/server.jar"}
        ]}}
    """.trimIndent()

    @Test
    fun picksLinuxLinkAndDerivesVersion() {
        val build = BdsSupport.parseLatestLinux(officialJson)
        assertNotNull(build)
        assertEquals("1.26.52.3", build!!.version)
        assertTrue(build.url.contains("bin-linux"))
    }

    @Test
    fun ignoresWindowsAndPreviewEntries() {
        val json = """
            {"result":{"links":[
              {"downloadType":"serverBedrockPreviewLinux","downloadUrl":"https://x/bin-linux-preview/bedrock-server-1.26.70.21.zip"},
              {"downloadType":"serverBedrockLinux","downloadUrl":"https://x/bin-linux/bedrock-server-1.26.52.3.zip"}
            ]}}
        """.trimIndent()
        // 只认 preview 之外的正式 linux 条目，取到的是正式版
        assertEquals("1.26.52.3", BdsSupport.parseLatestLinux(json)?.version)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(BdsSupport.parseLatestLinux("<html>404</html>"))
        assertNull(BdsSupport.parseLatestLinux("{}"))
        assertNull(BdsSupport.parseLatestLinux("""{"result":{"links":[]}}"""))
    }

    @Test
    fun versionFromUrlRejectsUnexpectedNames() {
        assertEquals(
            "1.26.52.3",
            BdsSupport.versionFromUrl("https://x/bin-linux/bedrock-server-1.26.52.3.zip")
        )
        assertNull(BdsSupport.versionFromUrl("https://x/bin-linux/bedrock_server"))
        assertNull(BdsSupport.versionFromUrl("https://x/bin-linux/other-1.0.zip"))
    }

    @Test
    fun elfArchReadsMachineField() {
        val aarch64 = writeElf(temp.newFile("arm64.bin"), machine = 0xB7)
        val x86 = writeElf(temp.newFile("x86_64.bin"), machine = 0x3E)
        assertEquals(BdsSupport.ElfArch.AARCH64, BdsSupport.elfArch(aarch64))
        assertEquals(BdsSupport.ElfArch.X86_64, BdsSupport.elfArch(x86))
        assertNull(BdsSupport.elfArch(temp.newFile("not-elf.txt").apply { writeText("hello") }))
    }

    @Test
    fun deviceArchMapsSupportedAbis() {
        // 声明 32 位兼容的设备（SUPPORTED_ABIS 里带 armeabi-v7a）也能跑 ARM 构建
        assertEquals(
            setOf(BdsSupport.ElfArch.AARCH64, BdsSupport.ElfArch.ARM),
            BdsSupport.deviceArch(listOf("arm64-v8a", "armeabi-v7a"))
        )
        assertEquals(
            setOf(BdsSupport.ElfArch.AARCH64, BdsSupport.ElfArch.X86_64),
            BdsSupport.deviceArch(listOf("arm64-v8a", "x86_64"))
        )
    }

    @Test
    fun arm64OnlyDeviceCannotRunArm32Build() {
        // 纯 arm64 设备（无 32 位兼容）跑 armeabi 构建必须判为不可运行
        val dir = temp.newFolder("bds-arm32")
        writeElf(File(dir, BdsSupport.ENTRY_NAME), machine = 0x28)
        assertFalse(BdsSupport.checkLaunch(dir, listOf("arm64-v8a")).ok)
    }

    @Test
    fun officialX8664BuildRunsThroughBox64OnArmDevice() {
        val dir = temp.newFolder("bds-x86")
        writeElf(File(dir, BdsSupport.ENTRY_NAME), machine = 0x3E)

        val check = BdsSupport.checkLaunch(dir, listOf("arm64-v8a"))
        // 官方发行版是 x86_64，arm64 设备上**可以**跑，但要先准备 box64 运行环境
        assertTrue(check.ok)
        assertEquals(BdsSupport.LaunchMode.Box64, check.mode)
        assertTrue(check.needsBox64)
        assertEquals(BdsSupport.ElfArch.X86_64, check.arch)
    }

    @Test
    fun x86DeviceRunsOfficialBuildNatively() {
        val dir = temp.newFolder("bds-x86-native")
        writeElf(File(dir, BdsSupport.ENTRY_NAME), machine = 0x3E)

        val check = BdsSupport.checkLaunch(dir, listOf("x86_64"))
        assertEquals(BdsSupport.LaunchMode.Native, check.mode)
        assertFalse(check.needsBox64)
    }

    @Test
    fun x8664BuildWithMixedLibrariesIsRejectedEvenUnderBox64() {
        val dir = temp.newFolder("bds-x86-mixed")
        writeElf(File(dir, BdsSupport.ENTRY_NAME), machine = 0x3E)
        writeElf(File(dir, "libCrypto.so"), machine = 0xB7)

        val check = BdsSupport.checkLaunch(dir, listOf("arm64-v8a"))
        assertFalse(check.ok)
        assertTrue(check.reason.contains("libCrypto.so"))
    }

    @Test
    fun missingEntryFailsInsteadOfPretendingSupported() {
        val dir = temp.newFolder("bds-empty")
        val check = BdsSupport.checkLaunch(dir, listOf("arm64-v8a"))
        assertFalse(check.ok)
        assertTrue(check.reason.contains(BdsSupport.ENTRY_NAME))
    }

    @Test
    fun mismatchedBundledLibraryIsReported() {
        val dir = temp.newFolder("bds-mixed")
        writeElf(File(dir, BdsSupport.ENTRY_NAME), machine = 0xB7)
        writeElf(File(dir, "libCrypto.so"), machine = 0x3E)

        val check = BdsSupport.checkLaunch(dir, listOf("arm64-v8a"))
        assertFalse(check.ok)
        assertTrue(check.reason.contains("libCrypto.so"))
    }

    @Test
    fun matchingArchitecturePasses() {
        val dir = temp.newFolder("bds-ok")
        writeElf(File(dir, BdsSupport.ENTRY_NAME), machine = 0xB7)
        writeElf(File(dir, "libssl.so"), machine = 0xB7)

        val check = BdsSupport.checkLaunch(dir, listOf("arm64-v8a"))
        assertTrue(check.ok)
        assertEquals(BdsSupport.LaunchMode.Native, check.mode)
    }

    @Test
    fun extractZipWritesEntries() {
        val zip = temp.newFile("bds.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            listOf("bedrock_server", "server.properties", "behavior_packs/a.json").forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write("x".toByteArray())
                zos.closeEntry()
            }
        }
        val target = temp.newFolder("extracted")
        val count = BdsSupport.extractZip(zip, target)

        assertEquals(3, count)
        assertTrue(File(target, BdsSupport.ENTRY_NAME).isFile)
        assertTrue(File(target, "server.properties").isFile)
        assertTrue(File(target, "behavior_packs/a.json").isFile)
        assertTrue(BdsSupport.isBdsDir(target))
    }

    @Test
    fun extractZipRejectsPathTraversal() {
        val zip = temp.newFile("evil.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("../../escaped.txt"))
            zos.write("x".toByteArray())
            zos.closeEntry()
        }
        val target = temp.newFolder("safe")
        var threw = false
        try {
            BdsSupport.extractZip(zip, target)
        } catch (e: java.io.IOException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse(File(target.parentFile, "escaped.txt").exists())
    }

    /** 造一个只有 ELF 头 + e_machine 的文件，默认 64 位小端 */
    private fun writeElf(file: File, machine: Int): File {
        val header = ByteArray(20)
        header[0] = 0x7F
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2                 // 64-bit
        header[5] = 1                 // little endian
        header[6] = 1                 // version
        header[18] = (machine and 0xFF).toByte()
        header[19] = ((machine shr 8) and 0xFF).toByte()
        file.writeBytes(header)
        return file
    }
}
