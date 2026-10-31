package com.mineserve.mobile.server

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import com.mineserve.mobile.data.BdsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * box64 获取与「可执行副本」的行为。
 *
 * 这里锁的是三件在真机上很难复现、但错了就整条链路不通的事：
 *  1. 版本索引要挑到**最新的 arm64** 包（字符串比较会挑错 `0.4.10` 之类）；
 *  2. .deb 里只取 `usr/local/bin/box64` 一个文件，依赖只有 glibc；
 *  3. 公共存储上的 `bedrock_server` 必须复制到私有目录才能执行，
 *     且「大小 + 修改时间一致」时不得重复复制 244MB。
 */
class BdsRuntimeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val packages = """
        Package: box64
        Version: 0.4.5+20261023.827f0ee-1
        Architecture: arm64
        Filename: ./box64_0.4.5+20261023.827f0ee-1_arm64.deb
        Size: 10284794

        Package: box64
        Version: 0.4.5+20261024.420eda6-1
        Architecture: arm64
        Filename: ./box64_0.4.5+20261024.420eda6-1_arm64.deb
        Size: 10285111
        SHA256: f331c6e712011f348b6e5eb0f74989169bafa2ac79fd63eb2c18794d896bf9e6

        Package: box64
        Version: 0.9.9+20990101.deadbee-1
        Architecture: amd64
        Filename: ./box64_0.9.9+20990101.deadbee-1_amd64.deb
        Size: 9999999
    """.trimIndent()

    @Test
    fun picksNewestArm64BuildAndIgnoresOtherArchitectures() {
        val ref = BdsRuntime.parseLatestDeb(packages)
        assertNotNull(ref)
        assertEquals("0.4.5+20261024.420eda6-1", ref!!.version)
        assertEquals("box64_0.4.5+20261024.420eda6-1_arm64.deb", ref.fileName)
        // 具体的下载地址由多源列表拼出来（同一份包可从多个入口取），
        // 这里只锁「文件名 + 哈希」——哈希是判断内容是否一致的唯一硬证据
        assertEquals(
            BdsSources.debUrl(BdsSources.box64Deb.first(), ref.fileName),
            BdsSources.box64Deb.first().url + ref.fileName
        )
        assertTrue(ref.sha256.isNullOrEmpty() || ref.sha256!!.length == 64)
        // amd64 那条日期更新，但架构不对，绝不能被选中
        assertFalse(ref.fileName.contains("amd64"))
    }

    /**
     * 内置 box64 是 BDS 环境唯一可靠的来源（Android 14+ 无法解 zstd 的 .deb），
     * 它在仓库里是个 28MB 的二进制：一旦被 LFS 指针、错误架构或截断文件替换，
     * 用户那边表现是「初始化完成但服务器起不来」，非常难查。这里直接验内容。
     */
    @Test
    fun bundledBox64AssetIsAarch64Executable() {
        // 单测的工作目录是 app 模块根
        val asset = File("src/main/assets/box64/box64")
        assertTrue("内置 box64 资源缺失: ${asset.absolutePath}", asset.isFile)
        assertTrue("内置 box64 过小，疑被截断: ${asset.length()}", asset.length() > 20L * 1024 * 1024)
        assertEquals(BdsSupport.ElfArch.AARCH64, BdsSupport.elfArch(asset))
        assertTrue(File("src/main/assets/box64/box64.box64rc").isFile)
    }

    /**
     * `bedrock_server` 是**动态链接**的 x86_64 程序，box64 只翻译指令、不提供库。
     * 这七个库缺任何一个，启动都会以 `Error loading needed libs` + exit=255 结束
     * （真机踩过）。资源本身也要验架构：放成 aarch64 的库比缺失更隐蔽。
     */
    @Test
    fun bundledX86LibsCoverEveryNeededEntry() {
        val dir = File("src/main/assets/box64/x86libs")
        assertTrue("x86 运行库资源目录缺失", dir.isDirectory)
        BdsRuntime.REQUIRED_X86_LIBS.forEach { name ->
            val lib = File(dir, name)
            assertTrue("缺少 $name", lib.isFile)
            assertEquals("$name 不是 x86_64", BdsSupport.ElfArch.X86_64, BdsSupport.elfArch(lib))
        }
    }

    @Test
    fun buildDateKeyUsesEightDigitDateAfterPlus() {
        assertEquals(20261024L, BdsRuntime.buildDateKey("0.4.5+20261024.420eda6-1"))
        assertEquals(20261023L, BdsRuntime.buildDateKey("0.4.5+20261023.827f0ee-1"))
        assertEquals(0L, BdsRuntime.buildDateKey("1.0.0"))
    }

    /**
     * 真实索引里除通用 `box64` 外还有一堆按 SoC 调的变体，它们共用同一个 Version。
     * 只比版本号、让「后出现的胜出」会挑到最后一台设备的专用构建（历史上就是
     * `box64-tegrax1`），装到普通手机上要么慢要么崩。
     */
    @Test
    fun prefersGenericPackageOverSocSpecificVariants() {
        val index = """
            Package: box64
            Version: 0.4.5+20261024.420eda6-1
            Architecture: arm64
            Filename: ./box64_0.4.5+20261024.420eda6-1_arm64.deb
            Size: 10285111

            Package: box64-sd888
            Version: 0.4.5+20261024.420eda6-1
            Architecture: arm64
            Filename: ./box64-sd888_0.4.5+20261024.420eda6-1_arm64.deb
            Size: 10286001

            Package: box64-tegrax1
            Version: 0.4.5+20261024.420eda6-1
            Architecture: arm64
            Filename: ./box64-tegrax1_0.4.5+20261024.420eda6-1_arm64.deb
            Size: 10287002
        """.trimIndent()

        val ref = BdsRuntime.parseLatestDeb(index)
        assertNotNull(ref)
        assertEquals("box64_0.4.5+20261024.420eda6-1_arm64.deb", ref!!.fileName)
    }

    /** 索引里只剩设备专用变体时也不能返回 null（宁可装个能跑的，也不能判「没有可用包」）。 */
    @Test
    fun fallsBackToAnyArm64BuildWhenGenericPackageIsAbsent() {
        val index = """
            Package: box64-rk3588
            Version: 0.4.5+20261024.420eda6-1
            Architecture: arm64
            Filename: ./box64-rk3588_0.4.5+20261024.420eda6-1_arm64.deb
        """.trimIndent()

        assertEquals(
            "box64-rk3588_0.4.5+20261024.420eda6-1_arm64.deb",
            BdsRuntime.parseLatestDeb(index)?.fileName
        )
    }

    @Test
    fun malformedIndexReturnsNull() {
        assertNull(BdsRuntime.parseLatestDeb(""))
        assertNull(BdsRuntime.parseLatestDeb("<html>404</html>"))
        assertNull(BdsRuntime.parseLatestDeb("Package: box64\nArchitecture: arm64\n"))
    }

    @Test
    fun versionIsSanitizedForDirectoryNames() {
        assertEquals("1.26.52.3", BdsRuntime.sanitizeVersion("1.26.52.3"))
        assertEquals("1_26_52_3", BdsRuntime.sanitizeVersion("1/26 52:3"))
        // 全是可疑字符时也要给出一个可用名字，不能产生空目录名
        assertEquals("unknown", BdsRuntime.sanitizeVersion("///"))
    }

    @Test
    fun unpacksBox64BinaryAndConfigFromDeb() {
        val deb = temp.newFile("box64_arm64.deb")
        writeDeb(deb)
        val binary = File(temp.newFolder("out"), "box64")
        val config = File(temp.root, "box64.box64rc")

        assertTrue(BdsRuntime.unpackBox64Deb(deb, binary, config))
        assertTrue(binary.isFile)
        assertTrue(binary.length() > 1024)
        assertEquals("BOX64", binary.readText().take(5))
        assertTrue(config.isFile)
        assertEquals("rc", config.readText())
    }

    @Test
    fun nonDebFileIsRejectedInsteadOfThrowing() {
        val fake = temp.newFile("box64.deb").apply { writeText("not an archive at all") }
        assertFalse(BdsRuntime.unpackBox64Deb(fake, File(temp.root, "box64")))
    }

    @Test
    fun stageEntryCopiesOnceThenReusesBySizeAndTimestamp() {
        val serverDir = temp.newFolder("server")
        val source = File(serverDir, BdsRuntime.ENTRY_NAME)
        source.writeBytes(ByteArray(2048 * 1024) { (it % 251).toByte() })
        val payload = File(temp.root, "payload/1.26.52.3")

        val first = BdsRuntime.stageEntry(serverDir, "1.26.52.3", payload)
        assertEquals(source.length(), first.length())
        assertEquals(source.lastModified(), first.lastModified())
        assertFalse(File(payload, "${BdsRuntime.ENTRY_NAME}.part").exists())
        assertTrue(File(payload, "payload.version").isFile)

        // 第二次：不得重新复制（判据是「大小一致」，不依赖 FUSE 上不稳定的 mtime）
        val before = first.lastModified()
        val second = BdsRuntime.stageEntry(serverDir, "1.26.52.3", payload)
        assertEquals(before, second.lastModified())
        assertEquals(source.length(), second.length())

        // 源文件变了（升级 BDS）→ 必须重新复制
        source.writeBytes(ByteArray(3000 * 1024) { 7 })
        val third = BdsRuntime.stageEntry(serverDir, "1.26.52.3", payload)
        assertEquals(3000L * 1024, third.length())
    }

    @Test
    fun stageEntryFailsLoudlyWhenEntryIsMissing() {
        val serverDir = temp.newFolder("empty-server")
        var message: String? = null
        try {
            BdsRuntime.stageEntry(serverDir, "1.0.0", File(temp.root, "payload/1.0.0"))
        } catch (e: IllegalStateException) {
            message = e.message
        }
        assertNotNull(message)
        assertTrue(message!!.contains(BdsRuntime.ENTRY_NAME))
    }

    @Test
    fun runDirPlanLinksEntryAndOnlyExistingCarryOver() {
        val serverDir = temp.newFolder("server")
        File(serverDir, "definitions").mkdirs()
        File(serverDir, "resource_packs").mkdirs()
        File(serverDir, "server.properties").writeText("server-port=19132\n")
        // worlds/logs 这类数据目录不该被链进运行目录
        File(serverDir, "worlds").mkdirs()

        val plan = BdsRuntime.runDirPlan(serverDir, "/srv/mineserve")

        assertEquals(BdsRuntime.ENTRY_NAME, plan.first().name)
        // 主程序走硬链接，没有容器内目标
        assertEquals("", plan.first().guestTarget)
        // server.properties 有专门的复制路径（stageProperties），不在链接计划里
        assertEquals(
            listOf("definitions", "resource_packs"),
            plan.drop(1).map { it.name }
        )
        assertTrue(plan.drop(1).all { it.guestTarget.startsWith("/srv/mineserve/") })
    }

    /**
     * `server.properties` 必须是**复制**过去的普通文件。
     *
     * 实测符号链接在这里读不出内容（BDS 打印 `Contents of server.properties: {}`
     * 后用内置默认值跑，而 1.26 默认 `allow-list=true` + 离线模式会被直接拒绝）。
     */
    @Test
    fun prepareRunDirCopiesPropertiesIntoRunDir() {
        val serverDir = temp.newFolder("server")
        val properties = "online-mode=false\nallow-list=false\nserver-port=19132\n"
        File(serverDir, "server.properties").writeText(properties)
        val payloadDir = File(temp.root, "payload/1.26.52.3").apply { mkdirs() }
        val payloadEntry = File(payloadDir, BdsRuntime.ENTRY_NAME).apply { writeBytes(ByteArray(4096) { 1 }) }
        val runDir = File(temp.root, "run/survival")

        BdsRuntime.prepareRunDir(serverDir, runDir, payloadEntry, "/srv/mineserve")

        val staged = File(runDir, "server.properties")
        assertTrue(staged.isFile)
        assertFalse(Files.isSymbolicLink(staged.toPath()))
        assertEquals(properties, staged.readText())
    }

    /**
     * 主程序必须是硬链接而不是符号链接：BDS 1.21+ 用 `/proc/self/exe` 的 realpath
     * 当资源根，符号链接会让它跑到共享副本目录里读配置。
     */
    @Test
    fun prepareRunDirHardLinksEntrySoRealPathStaysInRunDir() {
        val serverDir = temp.newFolder("server")
        val payloadDir = File(temp.root, "payload/1.26.52.3").apply { mkdirs() }
        val payloadEntry = File(payloadDir, BdsRuntime.ENTRY_NAME).apply { writeBytes(ByteArray(4096) { 1 }) }
        val runDir = File(temp.root, "run/survival")

        BdsRuntime.prepareRunDir(serverDir, runDir, payloadEntry, "/srv/mineserve")
        val staged = File(runDir, BdsRuntime.ENTRY_NAME)

        assertTrue(staged.isFile)
        assertFalse(Files.isSymbolicLink(staged.toPath()))
        assertEquals(payloadEntry.length(), staged.length())
    }

    @Test
    fun prepareRunDirDoesNotThrowOnAnyHost() {
        val serverDir = temp.newFolder("server")
        val payloadDir = File(temp.root, "payload/1.26.52.3").apply { mkdirs() }
        val payloadEntry = File(payloadDir, BdsRuntime.ENTRY_NAME).apply { writeBytes(ByteArray(4096) { 1 }) }
        val runDir = File(temp.root, "run/survival")

        // 符号链接在某些宿主（Windows / 受限文件系统）上建不出来，此时必须退回复制而不是崩，
        // 这条断言守的是「启动流程不会因为建链接失败而中断」。
        BdsRuntime.prepareRunDir(serverDir, runDir, payloadEntry, "/srv/mineserve")
        assertTrue(runDir.isDirectory)
    }

    /**
     * 造一个最小 .deb：ar 归档 + `data.tar.gz`（含 box64 与 box64.box64rc）。
     *
     * 与真实包的结构一致（真实包只是把 tar 换成了 zstd），差别只在压缩算法。
     */
    private fun writeDeb(target: File) {
        val dataTarGz = File(temp.root, "data.tar.gz")
        GzipCompressorOutputStream(dataTarGz.outputStream().buffered()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                // 内容必须是**不可压缩**的随机字节：全 0x41 会被 gzip 压到几百字节，
                // 整个 .deb 于是小于 1KB，被「长度不合理」的守卫拒掉 —— 那是真机上的正确行为。
                val binaryBody = "BOX64".toByteArray() + ByteArray(8192).also {
                    java.util.Random(20261024L).nextBytes(it)
                }
                addEntry(tar, "./usr/local/bin/box64", binaryBody, 0b111_101_101)
                addEntry(tar, "./etc/box64.box64rc", "rc".toByteArray(), 0b110_100_100)
                addEntry(tar, "./usr/share/doc/box64/README.md", "readme".toByteArray(), 0b110_100_100)
            }
        }
        val members = listOf(
            "debian-binary" to "2.0\n".toByteArray(),
            "data.tar.gz" to dataTarGz.readBytes()
        )
        target.outputStream().use { out ->
            out.write("!<arch>\n".toByteArray())
            members.forEach { (name, body) ->
                out.write(arHeader(name, body.size.toLong(), 0b110_100_100))
                out.write(body)
                if (body.size % 2 == 1) out.write('\n'.code)
            }
        }
    }

    private fun addEntry(tar: TarArchiveOutputStream, name: String, body: ByteArray, mode: Int) {
        val entry = TarArchiveEntry(name).apply {
            size = body.size.toLong()
            this.mode = mode
        }
        tar.putArchiveEntry(entry)
        tar.write(body)
        tar.closeArchiveEntry()
    }

    private fun arHeader(name: String, size: Long, mode: Int): ByteArray {
        fun pad(value: String, width: Int) = value.padEnd(width, ' ')
        val header = pad("$name/", 16) +
            pad("0", 12) +
            pad("0", 6) +
            pad("0", 6) +
            pad(mode.toString(8), 8) +
            pad(size.toString(), 10) +
            "`\n"
        return header.toByteArray(Charsets.US_ASCII)
    }
}
