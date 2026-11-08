package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * ELF 依赖解析与 SONAME 别名。
 *
 * 用内置 x86 运行库（真实 ELF）当样本，而不是造假字节：解析器的每一条偏移
 * 都必须对得上真实 ELF64 布局，造假数据只能测出「代码能跑完」。
 *
 * 背景（真机日志）：
 * ```
 * [BOX64] Error initializing native libcurl.so.4 (last dlerror is libcurl-gnutls.so.4: ...)
 * [BOX64] Error: Loading needed libs in elf /opt/minexa-bds/run/<dir>/bedrock_server
 * ```
 * BDS 包里的 `libcurl.so.4` 的 SONAME 是 `libcurl-gnutls.so.4`，包里没有这个名字的
 * 文件，box64 按 SONAME 找库就整条依赖链失败。
 */
class BdsElfDepsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun asset(name: String): File {
        val candidates = listOf(
            File("src/main/assets/box64/x86libs/$name"),
            File("app/src/main/assets/box64/x86libs/$name")
        )
        val found = candidates.firstOrNull { it.isFile }
        assumeTrue("找不到测试样本 $name（工作目录可能不同）", found != null)
        return found!!
    }

    @Test
    fun readsSonameAndNeededFromRealElf() {
        val info = BdsElfDeps.read(asset("libc.so.6"))
        assertNotNull(info)
        assertEquals("libc.so.6", info!!.soname)
        assertTrue(info.needed.any { it.contains("ld-linux-x86-64.so.2") })
    }

    @Test
    fun nonElfFileIsIgnoredInsteadOfThrowing() {
        val text = temp.newFile("not-elf.so").apply { writeText("not an elf") }
        assertEquals(null, BdsElfDeps.read(text))
    }

    @Test
    fun createsAliasWhenFileNameDiffersFromSoname() {
        val runDir = temp.newFolder("run")
        // 模拟 Debian 的 libcurl.so.4：文件名是 libcurl.so.4，SONAME 却是别的名字
        asset("libc.so.6").copyTo(File(runDir, "libcurl.so.4"), overwrite = true)

        val aliases = BdsElfDeps.ensureSonameAliases(runDir, "/opt/minexa-bds/run/server-1")
        assertEquals(listOf("libc.so.6" to "libcurl.so.4"), aliases)
        // 软链或复制都算成功：Windows/部分文件系统不允许建软链，实现里有复制兜底
        assertTrue(File(runDir, "libc.so.6").isFile)
        assertEquals(File(runDir, "libcurl.so.4").length(), File(runDir, "libc.so.6").length())

        // 幂等：第二次启动什么都不该新建（否则每次启动都刷一行日志）
        assertTrue(BdsElfDeps.ensureSonameAliases(runDir, "/opt/minexa-bds/run/server-1").isEmpty())
    }

    @Test
    fun nameMatchingSonameNeedsNoAlias() {
        val runDir = temp.newFolder("run-clean")
        asset("libc.so.6").copyTo(File(runDir, "libc.so.6"), overwrite = true)
        assertTrue(BdsElfDeps.ensureSonameAliases(runDir, "/opt/minexa-bds/run/server-2").isEmpty())
    }

    @Test
    fun loaderIsNotReportedAsMissing() {
        val entry = temp.newFile("bedrock_server").apply {
            asset("libc.so.6").copyTo(this, overwrite = true)
        }
        // 空目录里当然没有 ld-linux，但加载器由 box64 自己提供，不该报警
        val missing = BdsElfDeps.missingNeeded(entry, listOf(temp.newFolder("empty")))
        assertTrue(missing.isEmpty())
    }
}
