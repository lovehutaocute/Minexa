package com.mineserve.mobile.server

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class BdsCompatLibsTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ── 索引解析 ─────────────────────────────────────────────────

    @Test
    fun parseIndexPicksOnlyWantedPackages() {
        val text = """
            Package: libssl1.1
            Version: 1.1.1f-1ubuntu2
            Filename: pool/main/o/openssl/libssl1.1_1.1.1f-1ubuntu2_amd64.deb
            SHA256: aaaa

            Package: vim-common
            Version: 2:8.1
            Filename: pool/main/v/vim/vim-common_8.1_amd64.deb
            SHA256: bbbb

            Package: zlib1g
            Version: 1:1.2.11
            Filename: pool/main/z/zlib/zlib1g_1.2.11_amd64.deb
            SHA256: cccc

        """.trimIndent()
        val parsed = BdsCompatLibs.parseIndex(text, setOf("libssl1.1", "zlib1g"))
        assertEquals(setOf("libssl1.1", "zlib1g"), parsed.keys)
        assertEquals(
            "pool/main/o/openssl/libssl1.1_1.1.1f-1ubuntu2_amd64.deb",
            parsed.getValue("libssl1.1").filename
        )
        assertEquals("cccc", parsed.getValue("zlib1g").sha256)
    }

    @Test
    fun parseIndexIgnoresBlockWithoutFilename() {
        val text = "Package: libssl1.1\nVersion: 1.1.1\nSHA256: aa\n"
        assertTrue(BdsCompatLibs.parseIndex(text, setOf("libssl1.1")).isEmpty())
    }

    // ── 解包 ─────────────────────────────────────────────────────

    @Test
    fun extractFollowsSymlinkAndWritesRealFile() {
        val payload = elfBytes(0x3E)
        val deb = writeDeb(
            "data.tar.gz",
            listOf(
                Entry("usr/lib/x86_64-linux-gnu/libstdc++.so.6.0.28", payload),
                Entry("usr/lib/x86_64-linux-gnu/libstdc++.so.6", null, linkTo = "libstdc++.so.6.0.28"),
                Entry("usr/share/doc/libstdc++6/copyright", "x".toByteArray())
            )
        )
        val out = temp.newFolder("x86libs")
        val ok = BdsCompatLibs.extract(deb, out, setOf("libstdc++.so.6"), {})
        assertTrue(ok)
        val lib = File(out, "libstdc++.so.6")
        assertTrue("应当是真实文件", lib.isFile)
        assertFalse(java.nio.file.Files.isSymbolicLink(lib.toPath()))
        assertTrue(lib.readBytes().contentEquals(payload))
        // 不相干的条目不能被落地
        assertEquals(1, out.listFiles()?.size)
    }

    @Test
    fun extractFallsBackToVersionedFileWhenSonameLinkMissing() {
        // 真实的 zlib1g / libstdc++6 只有实体文件，soname 软链在 -dev 包里
        val payload = elfBytes(0x3E)
        val deb = writeDeb(
            "data.tar.gz",
            listOf(
                Entry("usr/lib/x86_64-linux-gnu/libz.so.1.2.11", payload),
                Entry("usr/share/doc/zlib1g/changelog", "x".toByteArray())
            )
        )
        val out = temp.newFolder("zlib")
        assertTrue(BdsCompatLibs.extract(deb, out, setOf("libz.so.1"), {}))
        val lib = File(out, "libz.so.1")
        assertTrue(lib.isFile)
        assertTrue(lib.readBytes().contentEquals(payload))
    }

    @Test
    fun extractMatchesGlibcStyleStemEntity() {
        // focal libc6 里的 libresolv：实体叫 libresolv-2.31.so，soname 软链指向它
        val payload = elfBytes(0x3E)
        val deb = writeDeb(
            "data.tar.gz",
            listOf(
                Entry("lib/x86_64-linux-gnu/libresolv-2.31.so", payload),
                Entry("lib/x86_64-linux-gnu/libresolv.so.2", null, linkTo = "libresolv-2.31.so"),
                Entry("lib/x86_64-linux-gnu/libc-2.31.so", ByteArray(8192))
            )
        )
        val out = temp.newFolder("resolv")
        assertTrue(BdsCompatLibs.extract(deb, out, setOf("libresolv.so.2"), {}))
        val lib = File(out, "libresolv.so.2")
        assertTrue(lib.isFile)
        assertTrue(lib.readBytes().contentEquals(payload))
        // 无关的 glibc 实体不能被顺带落地
        assertFalse(File(out, "libc-2.31.so").exists())
    }

    @Test
    fun extractAppliesExtraAliasForLegacyLibnsl() {
        val payload = elfBytes(0x3E)
        val deb = writeDeb(
            "data.tar.gz",
            listOf(Entry("lib/x86_64-linux-gnu/libnsl-2.21.so", payload))
        )
        val out = temp.newFolder("libnsl")
        val ok = BdsCompatLibs.extract(
            deb,
            out,
            setOf("libnsl-2.21.so"),
            {},
            extraAliases = mapOf("libnsl-2.21.so" to "libnsl.so.1")
        )
        assertTrue(ok)
        assertTrue(File(out, "libnsl.so.1").isFile)
        assertFalse(File(out, "libnsl-2.21.so").exists())
    }

    @Test
    fun extractRejectsNonDeb() {
        val bogus = temp.newFile("bogus.deb").apply { writeText("not an archive at all") }
        val out = temp.newFolder("out")
        assertFalse(BdsCompatLibs.extract(bogus, out, setOf("libz.so.1"), {}))
        assertEquals(0, out.listFiles()?.size ?: 0)
    }

    @Test
    fun isUsableLibNeedsX86Elf() {
        val dir = temp.newFolder("check")
        val good = File(dir, "libz.so.1").apply { writeBytes(elfBytes(0x3E) + ByteArray(8192)) }
        assertTrue(BdsCompatLibs.isUsableLib(good))
        val aarch64 = File(dir, "arm.so").apply { writeBytes(elfBytes(0xB7) + ByteArray(8192)) }
        assertFalse(BdsCompatLibs.isUsableLib(aarch64))
        val tiny = File(dir, "tiny.so").apply { writeBytes(elfBytes(0x3E)) }
        assertFalse("过小的文件不算可用", BdsCompatLibs.isUsableLib(tiny))
        assertFalse(BdsCompatLibs.isUsableLib(File(dir, "missing.so")))
    }

    @Test
    fun missingTargetsListsEverythingOnEmptyPrefix() {
        val prefix = temp.newFolder("prefix")
        val missing = BdsCompatLibs.missingTargets(prefix)
        assertTrue(missing.containsAll(listOf("libcurl.so.4", "libstdc++.so.6")))
        assertTrue(missing.containsAll(listOf("libssl.so.1.1", "libcrypto.so.1.1")))
        assertTrue(missing.contains("libz.so.1"))
        assertTrue(missing.contains("libnsl.so.1"))
    }

    // ── 端到端（联网，默认跳过） ─────────────────────────────────

    /**
     * 真下载一遍：索引 → SHA-256 → 递归解包 → 闭包自洽 → 二次调用零下载。
     *
     * 跑法：`./gradlew :app:testDebugUnitTest --tests "*BdsCompatLibsTest*" -DbdsCompat=live`
     * （约 13MB 下载；镜像不可达时整条失败，断言信息里带逐包日志）
     */
    @Test
    fun liveInstallFetchesEveryCompatLib() {
        org.junit.Assume.assumeTrue(System.getProperty("bdsCompat") == "live")
        val prefix = temp.newFolder("prefix-live")
        val tmpDir = temp.newFolder("tmp-live")
        val logs = mutableListOf<String>()
        val report = kotlinx.coroutines.runBlocking {
            BdsCompatLibs.install(prefix, tmpDir, { logs += it }, {})
        }
        assertTrue("补齐失败：${report.failed}\n${logs.joinToString("\n")}", report.ok)
        val dir = BdsRuntime.x86LibDir(prefix)
        listOf(
            "libcurl.so.4", "libstdc++.so.6", "libssl.so.1.1", "libcrypto.so.1.1",
            "libz.so.1", "libnsl.so.1"
        ).forEach { name ->
            assertTrue("$name 未落地", BdsCompatLibs.isUsableLib(File(dir, name)))
        }
        // 闭包自洽：x86libs 里每个库的 DT_NEEDED 都能在（内置库 + x86libs）里找到
        dir.listFiles()?.filter { it.isFile }?.forEach { lib ->
            val needed = BdsElfDeps.read(lib)?.needed.orEmpty()
            needed.filter { it.contains(".so") && !it.startsWith("ld-") }.forEach { dep ->
                val provided = File(dir, dep).isFile || dep in BdsRuntime.REQUIRED_X86_LIBS
                assertTrue("$lib 的依赖 $dep 未满足", provided)
            }
        }
        val again = kotlinx.coroutines.runBlocking {
            BdsCompatLibs.install(prefix, tmpDir, { logs += it }, {})
        }
        assertTrue("二次调用不应再下载：${again.installed}", again.installed.isEmpty())
        assertTrue(again.present.containsAll(listOf("libcurl.so.4", "libnsl.so.1")))
    }

    // ── 构造测试用 deb ───────────────────────────────────────────

    private data class Entry(val name: String, val content: ByteArray?, val linkTo: String? = null)

    /** 造一个最小可解析的 ar 归档（只有 data.tar.gz 成员） */
    private fun writeDeb(memberName: String, entries: List<Entry>): File {
        val tarBytes = ByteArrayOutputStream()
        TarArchiveOutputStream(tarBytes).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            entries.forEach { e ->
                val entry = TarArchiveEntry("./${e.name}")
                if (e.linkTo != null) {
                    entry.linkName = "./${e.name.substringBeforeLast('/')}/${e.linkTo}"
                } else {
                    entry.size = e.content!!.size.toLong()
                }
                tar.putArchiveEntry(entry)
                if (e.content != null) tar.write(e.content)
                tar.closeArchiveEntry()
            }
        }
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tarBytes.toByteArray()) }
        val body = gz.toByteArray()

        val out = ByteArrayOutputStream()
        out.write("!<arch>\n".toByteArray())
        out.write(arHeader("$memberName/", body.size.toLong()))
        out.write(body)
        if (body.size % 2 == 1) out.write('\n'.code)
        return temp.newFile("pkg-${System.nanoTime()}.deb").apply { writeBytes(out.toByteArray()) }
    }

    private fun arHeader(name: String, size: Long): ByteArray {
        fun field(value: String, width: Int) = value.padEnd(width).take(width).toByteArray()
        val header = ByteArrayOutputStream()
        header.write(field(name, 16))
        header.write(field("0", 12))          // mtime
        header.write(field("0", 6))           // uid
        header.write(field("0", 6))           // gid
        header.write(field("100644", 8))      // mode
        header.write(field(size.toString(), 10))
        header.write("`\n".toByteArray())
        return header.toByteArray().also { require(it.size == 60) }
    }

    /** 20 字节 ELF 头：架构字段在 18..19 */
    private fun elfBytes(machine: Int): ByteArray {
        val bytes = ByteArray(64)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2                       // ELFCLASS64
        bytes[5] = 1                       // little endian
        bytes[18] = (machine and 0xFF).toByte()
        bytes[19] = ((machine shr 8) and 0xFF).toByte()
        return bytes
    }
}
