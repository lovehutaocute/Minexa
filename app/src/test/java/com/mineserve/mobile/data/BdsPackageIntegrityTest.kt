package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 安装包完整性校验的三档强度。
 *
 * 这些用例锁的是「什么情况下**必须**拒绝安装」，因为那一步一旦放过，
 * 用户拿到的是一个解压成功、启动即崩的核心，事后完全看不出是下载环节的问题：
 *  - 摘要不符 → 内容被换过；
 *  - 体积不符 → 半截文件；
 *  - 没有摘要 → 至少要能逐条目读完整个 zip（CRC + 长度由 ZipFile 校验）。
 */
class BdsPackageIntegrityTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** "hello" 的 SHA-256，用来验证算法实现本身没写错 */
    private val helloSha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    private fun file(name: String, content: String): File =
        temp.newFile(name).apply { writeText(content) }

    private fun zip(name: String, entries: Map<String, String>): File {
        val zip = temp.newFile(name)
        ZipOutputStream(zip.outputStream()).use { zos ->
            entries.forEach { (entryName, body) ->
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(helloSha, BdsPackageIntegrity.sha256(file("hello.txt", "hello")))
    }

    @Test
    fun matchingDigestPasses() {
        val f = file("ok.bin", "hello")
        val report = BdsPackageIntegrity.verify(
            file = f,
            expectedSha256 = helloSha,
            expectedBytes = f.length(),
            level = BdsPackageIntegrity.Level.ManifestSha256
        )
        assertEquals(BdsPackageIntegrity.Level.ManifestSha256, report.level)
        assertEquals(helloSha, report.sha256)
        assertTrue(report.strict)
        // 有权威摘要时不再做全量 CRC 扫描：省掉一次 90MB 的解压
        assertEquals(0, report.crcEntries)
    }

    @Test
    fun digestMismatchIsRejected() {
        val f = file("bad.bin", "hello world")
        try {
            BdsPackageIntegrity.verify(f, expectedSha256 = helloSha)
            fail("摘要不符必须抛异常")
        } catch (e: BdsPackageIntegrity.Failure) {
            assertEquals(BdsPackageIntegrity.Reason.ShaMismatch, e.reason)
            assertEquals(helloSha, e.expectedSha256)
            assertEquals(BdsPackageIntegrity.sha256(f), e.actualSha256)
        }
    }

    @Test
    fun sizeMismatchIsRejectedBeforeHashing() {
        val f = file("short.bin", "hello")
        try {
            BdsPackageIntegrity.verify(f, expectedSha256 = helloSha, expectedBytes = 9999)
            fail("体积不符必须抛异常")
        } catch (e: BdsPackageIntegrity.Failure) {
            assertEquals(BdsPackageIntegrity.Reason.Truncated, e.reason)
        }
    }

    @Test
    fun emptyFileIsRejected() {
        val f = temp.newFile("empty.bin")
        try {
            BdsPackageIntegrity.verify(f, expectedSha256 = null)
            fail("空文件必须抛异常")
        } catch (e: BdsPackageIntegrity.Failure) {
            assertEquals(BdsPackageIntegrity.Reason.Empty, e.reason)
        }
    }

    @Test
    fun structuralVerificationReadsEveryEntry() {
        val archive = zip(
            "pkg.zip",
            mapOf("bedrock_server" to "x".repeat(2048), "server.properties" to "server-port=19132")
        )
        val report = BdsPackageIntegrity.verify(archive, expectedSha256 = null)
        assertEquals(BdsPackageIntegrity.Level.Structural, report.level)
        assertEquals(2, report.crcEntries)
        assertEquals(null, report.expectedSha256)
    }

    @Test
    fun structuralVerificationRejectsNonZip() {
        val junk = file("junk.zip", "this is an html error page")
        try {
            BdsPackageIntegrity.verify(junk, expectedSha256 = null)
            fail("非 zip 必须抛异常")
        } catch (e: BdsPackageIntegrity.Failure) {
            assertEquals(BdsPackageIntegrity.Reason.Corrupt, e.reason)
        }
    }

    @Test
    fun truncatedZipFailsStructuralScan() {
        val full = zip("full.zip", mapOf("bedrock_server" to "y".repeat(64 * 1024)))
        val truncated = temp.newFile("truncated.zip").apply {
            writeBytes(full.readBytes().copyOf((full.length() / 2).toInt()))
        }
        try {
            BdsPackageIntegrity.verify(truncated, expectedSha256 = null)
            fail("截断的 zip 必须抛异常")
        } catch (e: BdsPackageIntegrity.Failure) {
            // 中央目录缺失 → Corrupt；校验读者据此提示「已损坏，可重新下载」
            assertEquals(BdsPackageIntegrity.Reason.Corrupt, e.reason)
        }
    }
}
