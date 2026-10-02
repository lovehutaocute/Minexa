package com.mineserve.mobile.server.tunnel.frpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 钉住 [FrpcRuntime] 的引擎校验规则。
 *
 * 这些校验的价值在于**把错误拦在用户操作的那一刻**：导入一个错文件却不拦住的话，
 * 要等到启动隧道、dlopen 失败时才报错，那时错误信息离用户的操作已经很远，
 * 而用户手上未必还有第二份可用的引擎。所以逐条钉住。
 *
 * 校验逻辑以 `internal` 静态形式暴露给测试，避免为了可测性去起一个真的 Context。
 */
class FrpcRuntimeValidationTest {

    /** 构造一段带指定 e_machine 的假 ELF 头。 */
    private fun elfHeader(machine: Int): ByteArray {
        val bytes = ByteArray(20)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2 // ELFCLASS64
        bytes[5] = 1 // ELFDATA2LSB
        bytes[18] = (machine and 0xFF).toByte()
        bytes[19] = ((machine shr 8) and 0xFF).toByte()
        return bytes
    }

    @Test
    fun `arm64HeaderIsAccepted`() {
        assertEquals(null, FrpcRuntime.validateElfHeader(elfHeader(0xB7), length = 15_000_000L))
    }

    @Test
    fun `x86_64HeaderIsRejectedWithReadableReason`() {
        val error = FrpcRuntime.validateElfHeader(elfHeader(0x3E), length = 15_000_000L)
        assertTrue(error != null, "x86-64 引擎必须被拒绝")
        assertTrue(error!!.contains("x86-64"), "错误信息要说清实际架构，实际：$error")
        assertTrue(error.contains("arm64"), "错误信息要告诉用户该导入什么，实际：$error")
    }

    @Test
    fun `arm32HeaderIsRejected`() {
        val error = FrpcRuntime.validateElfHeader(elfHeader(0x28), length = 15_000_000L)
        assertTrue(error != null)
        assertTrue(error!!.contains("ARM 32"), "实际：$error")
    }

    @Test
    fun `nonElfFileIsRejected`() {
        // 用户误选了 config.toml 之类：开头是 's'（serverAddr = ...）
        val notElf = "serverAddr = \"example.com\"\n".toByteArray()
        val error = FrpcRuntime.validateElfHeader(notElf, length = 15_000_000L)
        assertTrue(error != null, "非 ELF 文件必须被拒绝")
        assertTrue(error!!.contains("ELF"), "实际：$error")
    }

    @Test
    fun `tinyFileIsRejectedBeforeHeaderCheck`() {
        // 体积检查先于头部检查：一个 200 字节的文本文件，报「不像是引擎」比报
        // 「不是 ELF」更贴近用户的真实错误（选错文件），也更好照做。
        val error = FrpcRuntime.validateSize(200)
        assertTrue(error != null)
        assertTrue(error!!.contains("200"), "实际：$error")
    }

    @Test
    fun `fullSizeFilePassesSizeCheck`() {
        assertEquals(null, FrpcRuntime.validateSize(FrpcRuntime.MIN_ENGINE_BYTES))
    }

    @Test
    fun `versionIsParsedFromFileName`() {
        assertEquals("0.71.0", FrpcRuntime.parseVersion("libfrpc-0.71.0-arm64.so"))
        assertEquals("0.61.2", FrpcRuntime.parseVersion("frpc_0.61.2_linux_arm64.so"))
        assertEquals(null, FrpcRuntime.parseVersion("libfrpc.so"))
        assertEquals(null, FrpcRuntime.parseVersion(null))
    }

    @Test
    fun `versionParsingPrefersFirstMatch`() {
        // 文件名里出现多个数字串时取第一个，避免把 arm64/aarch64 里的数字当版本
        assertEquals("1.2.3", FrpcRuntime.parseVersion("my-1.2.3-build-9.so"))
        assertFalse(FrpcRuntime.parseVersion("aarch64.so") != null)
    }
}
