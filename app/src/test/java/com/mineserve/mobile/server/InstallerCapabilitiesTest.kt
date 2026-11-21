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
 * 背景：Forge 1.7.10 的 installer 没有 `--mirror`，传进去 jopt 直接抛
 * `UnrecognizedOptionException` 退出 —— 白跑一轮安装，还把 Java 堆栈刷进控制台。
 * 1.12.2 / 1.16.5 反而支持（实测会改写下载 URL），所以不能按版本号猜，只能看 jar。
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

    @Test
    fun `detects mirror option string in class pool`() {
        // 常量池里的选项名是明文 UTF8，无需解析 class 结构
        val bytes = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()) +
            "Lmirror;".toByteArray()
        val jar = jarWithClasses("with-mirror.jar", mapOf("a/SimpleInstaller.class" to bytes))

        assertTrue(InstallerCapabilities.supportsMirror(jar))
    }

    @Test
    fun `reports unsupported when no class mentions mirror`() {
        val bytes = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()) +
            "--installServer".toByteArray()
        val jar = jarWithClasses("no-mirror.jar", mapOf("a/OldInstaller.class" to bytes))

        assertFalse(InstallerCapabilities.supportsMirror(jar))
    }

    @Test
    fun `ignores non class entries`() {
        val jar = jarWithClasses(
            "resources-only.jar",
            mapOf("install_profile.json" to "{\"mirror\":\"x\"}".toByteArray())
        )

        assertFalse(InstallerCapabilities.supportsMirror(jar))
    }
}
