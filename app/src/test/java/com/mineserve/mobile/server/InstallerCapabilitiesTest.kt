package com.mineserve.mobile.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * installer `--mirror` 参数能力探测。
 *
 * 背景：Forge 1.7.10 / 1.8.9 / 1.10.2 的 installer 没有 `--mirror`，传进去 jopt 直接抛
 * `UnrecognizedOptionException` 退出 —— 白跑一轮安装，还把 Java 堆栈刷进控制台。
 * 1.12.2 / 1.14.4 / 1.16.5 / 1.17.1 / 1.20.1 反而支持（实测会改写下载 URL），
 * 所以不能按版本号猜，只能看 jar。
 *
 * 判据是「同一个类里同时出现 mirror 与 installServer」，因为 jopt 的选项表把两者写在
 * 同一个 SimpleInstaller 类里；老 installer 整包也有 mirror 字样（MirrorData 的横幅文案、
 * VersionInfo 的 mirrorList 字段），但那不是能力声明 —— 下面专门锁住这个反例。
 */
class InstallerCapabilitiesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun jarWithClasses(name: String, classes: Map<String, ByteArray>): File {
        val file = File(temp.root, name)
        JarOutputStream(file.outputStream()).use { out ->
            classes.forEach { (entry, bytes) ->
                out.putNextEntry(JarEntry(entry))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun classBytes(vararg tokens: String): ByteArray =
        byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()) +
            tokens.joinToString("").toByteArray()

    @Test
    fun `detects mirror option declared alongside installServer`() {
        // 1.16.5 形状：jopt 选项表在同一个类里声明 mirror 与 installServer
        val jar = jarWithClasses(
            "new-gen.jar",
            mapOf("net/minecraftforge/installer/SimpleInstaller.class" to
                classBytes("installServer", "--offline", "mirror"))
        )

        assertTrue(InstallerCapabilities.supportsMirror(jar))
    }

    @Test
    fun `mirror banner classes are not an option declaration`() {
        // 1.7.10 形状：MirrorData 只有横幅文案，选项表里没有 --mirror
        val jar = jarWithClasses(
            "old-gen.jar",
            mapOf(
                "net/minecraftforge/installer/MirrorData.class" to
                    classBytes("Failed to download mirrors, assuming none:", "Data kindly mirrored by %s"),
                "net/minecraftforge/installer/ServerInstall.class" to
                    classBytes("Data kindly mirrored by %s"),
                "net/minecraftforge/installer/VersionInfo.class" to
                    classBytes("mirrorList"),
                "net/minecraftforge/installer/SimpleInstaller.class" to
                    classBytes("--installServer", "--offline", "--extract")
            )
        )

        assertFalse(InstallerCapabilities.supportsMirror(jar))
    }

    @Test
    fun `mirror and installServer in different classes is not enough`() {
        val jar = jarWithClasses(
            "split.jar",
            mapOf(
                "a/OptionTable.class" to classBytes("installServer"),
                "a/Banner.class" to classBytes("mirror")
            )
        )

        assertFalse(InstallerCapabilities.supportsMirror(jar))
    }

    @Test
    fun `ignores non class entries`() {
        val jar = jarWithClasses(
            "resources-only.jar",
            mapOf("install_profile.json" to "{\"mirror\":\"x\",\"installServer\":1}".toByteArray())
        )

        assertFalse(InstallerCapabilities.supportsMirror(jar))
    }
}
