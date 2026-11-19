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

    @Test
    fun `残缺判定：存在的空文件也不能算就绪`() {
        // 这条是「点了一键修复却说已恢复」的回归测试：早期只判「存在且非空」，
        // 于是 1 字节的坏文件也能通过检查，修复函数直接返回成功、什么都不做。
        val target = tmp.newFile("cacerts")
        target.writeText("x")
        val health = TrustStoreRepairer.inspect(target)
        assertEquals(TrustStoreFormat.KEYSTORE, health.format)
        assertTrue("解析不了的库必须判为不可用", !health.usable)
        assertTrue("必须给出原因", health.error != null)
    }

    @Test
    fun `缺失文件判为 MISSING 且不可用`() {
        val target = File(tmp.root, "not-there")
        val health = TrustStoreRepairer.inspect(target)
        assertEquals(TrustStoreFormat.MISSING, health.format)
        assertEquals(0, health.certificates)
        assertTrue(!health.usable)
    }

    @Test
    fun `PEM 证书包按文本识别格式`() {
        val pem = tmp.newFile("cacerts.pem")
        pem.writeText("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n")
        assertEquals(TrustStoreFormat.PEM, TrustStoreRepairer.detectFormat(pem))
    }

    @Test
    fun `PEM 即使证书很多也不算 JDK 可用`() {
        // 真实崩溃日志：cacerts 存在、非空、能解析出上百张证书，JDK 却报
        // `trustAnchors parameter must be non-empty` —— 它是 PEM 文本，JDK 读不到锚点。
        // 这条锁住「只有 keystore 格式 + 证书数达标才算可用」。
        assertTrue("PEM 一律不可用", !TrustStoreHealth(TrustStoreFormat.PEM, 200).usable)
        assertTrue("keystore 且证书够多才可用", TrustStoreHealth(TrustStoreFormat.KEYSTORE, 200).usable)
        assertTrue("keystore 但证书太少仍不可用", !TrustStoreHealth(TrustStoreFormat.KEYSTORE, 3).usable)
        assertTrue(!TrustStoreHealth(TrustStoreFormat.MISSING, 0).usable)
    }

    @Test
    fun `软链指向 PEM 时写回软链自身而不是链接目标`() {
        // 转换时必须替换软链，否则会把 apt/curl 共用的 PEM 证书包灌成二进制。
        val bundle = tmp.newFile("ca-certificates.crt")
        bundle.writeText("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n")
        val link = File(tmp.root, "cacerts")
        val created = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), bundle.toPath())
        }.isSuccess
        if (!created) return // Windows 非开发者模式无权限建软链，跳过
        assertEquals("软链指向 PEM → 写软链自身", link.absolutePath, TrustStoreRepairer.writeDestination(link).absolutePath)
        assertTrue("普通文件原样返回", TrustStoreRepairer.writeDestination(bundle).absolutePath == bundle.absolutePath)
    }

    @Test
    fun `补齐时拿不到任何证书就不动原文件`() {
        // 本机证书包全被截断/损坏时，topUp 必须原地失败，而不是把原库清空。
        val target = tmp.newFile("cacerts")
        target.writeText("原有内容")
        val write = TrustStoreRepairer.topUp(target, listOf("not a certificate", ""))
        assertEquals(0, write.imported)
        assertTrue(write.error != null)
        assertEquals("原有内容", target.readText())
    }
}
