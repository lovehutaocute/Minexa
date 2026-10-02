package com.mineserve.mobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 长度未知（既无 Content-Length 也不用 chunked）时下载产物的完整性判据。
 *
 * 这条护栏直接对应一次真实故障：服务端中途断开、jar 被截断成 196KB，
 * 而下载返回「成功」，下游只判「文件够大」，于是坏核心被继续解压安装。
 * 所以这里的每个用例都要是「删掉判据就红」的，而不是「跑通流程就算过」。
 */
class MultiThreadDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val url = "http://192.168.1.10:8000/core.jar"

    private fun writeZip(file: File, entryName: String = "hello.txt") {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry(entryName))
            zos.write("content".toByteArray())
            zos.closeEntry()
        }
    }

    @Test
    fun `完整的 jar 能通过长度未知时的校验`() {
        val file = File(tmp.root, "good.jar")
        writeZip(file)
        MultiThreadDownloader.verifyUnknownLength(file, file.length(), url)
    }

    @Test
    fun `截断的 jar 必须被判失败并删掉残渣`() {
        val file = File(tmp.root, "truncated.jar")
        writeZip(file)
        val full = file.readBytes()
        FileOutputStream(file).use { it.write(full.copyOf(full.size / 2)) }
        val threw = runCatching {
            MultiThreadDownloader.verifyUnknownLength(file, file.length(), url)
        }.isFailure
        assertTrue("截断的 jar 不能当成下载成功", threw)
        assertFalse("失败后应删掉半截文件，免得下一次被当成已下载", file.exists())
    }

    @Test
    fun `一字节都没收到就是失败`() {
        val empty = File(tmp.root, "empty.jar").apply { writeText("") }
        assertTrue(
            runCatching { MultiThreadDownloader.verifyUnknownLength(empty, 0L, url) }.isFailure
        )
    }

    @Test
    fun `非 zip 系产物没有判据时放行`() {
        // tar.gz 之类的格式这里确实无从判断，保持放行——
        // 那个分支不该被「假装能校验」掩盖。
        val tarball = File(tmp.root, "php.tar.gz").apply { writeText("not really gzip") }
        MultiThreadDownloader.verifyUnknownLength(tarball, tarball.length(), url)
    }

    @Test
    fun `zip 系后缀的识别范围`() {
        assertTrue(MultiThreadDownloader.isZipLike("plugin.JAR"))
        assertTrue(MultiThreadDownloader.isZipLike("mod.zip"))
        assertTrue(MultiThreadDownloader.isZipLike("app.apk"))
        assertFalse(MultiThreadDownloader.isZipLike("core.tar.gz"))
        assertFalse(MultiThreadDownloader.isZipLike("server.jar.bak"))
    }
}
