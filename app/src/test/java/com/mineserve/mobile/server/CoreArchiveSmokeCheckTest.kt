package com.mineserve.mobile.server

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 安装完成判据（P2-4）。
 *
 * 这些用例守的是「装完就算成功」这个旧行为的三类翻车现场：运营商劫持页、
 * CDN 截断包、合法 zip 但内容为空。三者都能轻松超过 1KB，
 * 只查大小是拦不住的。
 */
class CoreArchiveSmokeCheckTest {

    private val dir: File = Files.createTempDirectory("smoke").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun write(name: String, bytes: ByteArray): File =
        File(dir, name).apply { writeBytes(bytes) }

    @Test
    fun `文件缺失时给出缺失原因`() {
        val reason = coreArchiveSmokeCheck(File(dir, "server.jar"), isPhar = false)
        assertNotNull(reason)
        assertTrue(reason!!.contains("缺失"))
    }

    @Test
    fun `空文件与超小文件被判为不完整`() {
        listOf(0, 100, 1024).forEach { size ->
            val f = write("small-$size.jar", ByteArray(size))
            val reason = coreArchiveSmokeCheck(f, isPhar = false)
            assertNotNull("size=$size 应判失败", reason)
            assertTrue(reason!!.contains("不完整"))
        }
    }

    @Test
    fun `劫持页等非 zip 文件被判为无效 JAR`() {
        // 典型形态：HTTP 200 + 一张 HTML 错误页，长度远超 1KB。
        val html = "<html><body>Access denied by CDN</body></html>".repeat(100)
        val f = write("hijacked.jar", html.toByteArray())
        val reason = coreArchiveSmokeCheck(f, isPhar = false)
        assertNotNull(reason)
        assertTrue(reason!!.contains("不是有效的 JAR"))
    }

    @Test
    fun `合法 zip 但没有 class 也没有主类时判失败`() {
        // 必须超过 1KB，否则会先被「大小不足」拦下，测不到真正想测的分支。
        val f = File(dir, "empty.zip")
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(payload)
            zip.closeEntry()
        }
        val reason = coreArchiveSmokeCheck(f, isPhar = false)
        assertNotNull("空内容包必须拦下", reason)
        assertTrue(reason!!.contains("不是有效的 JAR"))
    }

    /**
     * 让测试包超过 1KB 的载荷。
     *
     * 必须是随机字节：全 0 会被 deflate 压到几十字节，「大小不足」那一步会先拦下来，
     * 于是测到的不是想测的分支（踩过）。
     */
    private val payload = ByteArray(4096).also { java.util.Random(42).nextBytes(it) }

    @Test
    fun `含 class 的 jar 通过`() {
        val f = File(dir, "classes.jar")
        JarOutputStream(f.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("net/minecraft/server/Main.class"))
            jar.write(payload)
            jar.closeEntry()
        }
        assertNull(coreArchiveSmokeCheck(f, isPhar = false))
    }

    @Test
    fun `只声明 Main-Class 的 jar 也通过`() {
        // 部分核心（如 launch wrapper）把所有 class 打进内层 jar，外层只有 manifest。
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Main-Class", "io.example.Launch")
        }
        val f = File(dir, "wrapper.jar")
        JarOutputStream(f.outputStream(), manifest).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/notes.txt"))
            jar.write(payload)
            jar.closeEntry()
        }
        assertNull(coreArchiveSmokeCheck(f, isPhar = false))
    }

    @Test
    fun `phar 只做大小检查`() {
        // PocketMine 的 .phar 是带 PHP stub 的 zip 容器，JarFile 解析结果不可靠。
        val okPhar = write("PocketMine-MP.phar", ByteArray(2048))
        assertNull(coreArchiveSmokeCheck(okPhar, isPhar = true))

        val tinyPhar = write("tiny.phar", ByteArray(10))
        assertNotNull(coreArchiveSmokeCheck(tinyPhar, isPhar = true))
    }
}
