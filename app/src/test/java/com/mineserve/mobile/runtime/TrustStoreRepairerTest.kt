package com.mineserve.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 证书库重建的纯逻辑测试。
 *
 * 这里不生成真实证书（JDK 17 模块化后没有公开 API 能签自签证书），只覆盖
 * 「PEM 拆分」与「解析不到证书时绝不破坏原文件」这两条最容易出事的路径。
 * 真机上的真实证书导入由崩溃弹窗的「一键修复」验证。
 */
class TrustStoreRepairerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `PEM 拆分能处理多张证书与脏文本`() {
        val text = buildString {
            appendLine("# 注释行")
            appendLine("-----BEGIN CERTIFICATE-----")
            appendLine("AAAA")
            appendLine("-----END CERTIFICATE-----")
            appendLine("中间的日志噪音")
            appendLine("-----BEGIN CERTIFICATE-----")
            appendLine("BBBB")
            appendLine("-----END CERTIFICATE-----")
        }
        val blocks = TrustStoreRepairer.extractPemBlocks(text)
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it.startsWith("-----BEGIN CERTIFICATE-----") })
        assertTrue(blocks.all { it.endsWith("-----END CERTIFICATE-----") })
    }

    @Test
    fun `没有 PEM 头时返回空`() {
        assertTrue(TrustStoreRepairer.extractPemBlocks("hello world").isEmpty())
        assertTrue(TrustStoreRepairer.extractPemBlocks("-----BEGIN CERTIFICATE----- 没有结束标记").isEmpty())
    }

    @Test
    fun `解析不到证书时返回 0 且不改动原文件`() {
        val target = tmp.newFile("cacerts")
        target.writeText("原有内容")
        val imported = TrustStoreRepairer.rebuild(target, "not a certificate")
        assertEquals(0, imported)
        assertEquals("原有内容", target.readText())
        assertTrue("不应产生 .tmp 残留", File(target.parentFile, "cacerts.tmp").exists().not())
    }

    @Test
    fun `Base64 非证书内容不会被当成有效证书`() {
        val bogus = """
            -----BEGIN CERTIFICATE-----
            bm90IGEgY2VydGlmaWNhdGU=
            -----END CERTIFICATE-----
        """.trimIndent()
        assertTrue(TrustStoreRepairer.parseCertificates(bogus).isEmpty())
    }
}
