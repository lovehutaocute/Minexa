package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private fun writeJar(file: File, classes: Map<String, ByteArray>) {
        JarOutputStream(file.outputStream()).use { out ->
            classes.forEach { (entry, bytes) ->
                out.putNextEntry(JarEntry(entry))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun jarWithClasses(name: String, classes: Map<String, ByteArray>): File =
        File(temp.root, name).also { writeJar(it, classes) }

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

    @Test
    fun `unreadable jar stays optimistic but reports the reason`() {
        // 半下载 / 损坏的 jar：JarFile 直接抛异常。判定仍乐观为 true（交给运行期重试），
        // 但必须把原因带出来 —— 否则控制台只会写「带镜像安装失败」，把用户引向镜像排查。
        val broken = File(temp.root, "broken.jar").apply { writeText("PK\u0003\u0004 not really a zip") }

        val probe = InstallerCapabilities.probe(broken)

        assertTrue(probe.supported)
        assertNotNull(probe.readFailure)
    }

    @Test
    fun `probe result is cached per jar fingerprint`() {
        val jar = File(temp.root, "cached.jar")
        writeJar(jar, mapOf("a/SimpleInstaller.class" to classBytes("installServer", "mirror")))

        assertTrue(InstallerCapabilities.probe(jar).supported)
        // jar 被替换（长度变了 → fingerprint 变）后必须重扫，不能命中旧结论
        writeJar(jar, mapOf("a/SimpleInstaller.class" to classBytes("installServer")))
        assertFalse(InstallerCapabilities.probe(jar).supported)
    }

    @Test
    fun `read failure is not cached`() {
        // 读失败常常是瞬时原因（文件正被占用、还在下载）。若把它写进缓存，jar 不变就
        // 再也不会重探。这里用「同长度 + 同 mtime」把指纹锁死，只有不缓存失败结论才能
        // 在第二次调用里读出真实答案。
        val source = File(temp.root, "source.jar").also {
            writeJar(it, mapOf("a/SimpleInstaller.class" to classBytes("installServer")))
        }
        val bytes = source.readBytes()
        val jar = File(temp.root, "flaky.jar")
        jar.writeBytes(corruptEndOfCentralDirectory(bytes))
        val mtime = jar.lastModified()

        assertNotNull(InstallerCapabilities.probe(jar).readFailure)

        jar.writeBytes(bytes)
        assertTrue("mtime 必须回到原值，否则这条用例测的不是缓存", jar.setLastModified(mtime))
        val second = InstallerCapabilities.probe(jar)

        assertEquals(null, second.readFailure)
        assertFalse(second.supported)
    }

    /** 抹掉 EOCD 签名但保持字节长度不变，让 JarFile 必抛 ZipException。 */
    private fun corruptEndOfCentralDirectory(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        val signature = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
        outer@ for (i in copy.size - 22 downTo 0) {
            for (j in signature.indices) if (copy[i + j] != signature[j]) continue@outer
            for (k in signature.indices) copy[i + k] = 0
            return copy
        }
        error("EOCD not found")
    }
}
