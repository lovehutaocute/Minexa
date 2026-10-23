package com.mineserve.mobile.server.tunnel.frpc

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `.ecpkg` 解包测试。
 *
 * 包的构造照抄真实分发包（`frpc0.69.1-aarch64.ecpkg`）：清单 + `aarch64/lib/libfrpc.so`。
 * 覆盖两条**用户真会走到**的路径：在线下载（下载器直接交给 install）与手动导入
 * （用户在文件选择器里选中 `.ecpkg`）。
 */
class FrpcEcpkgTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ecpkg(
        archDir: String = "aarch64",
        manifestArch: String? = """{ "$archDir": { "dir": "$archDir" } }""",
        libBytes: ByteArray = ByteArray(64) { 0x7f },
        includeLib: Boolean = true
    ): File {
        val file = tmp.newFile("test.ecpkg")
        ZipOutputStream(file.outputStream()).use { zip ->
            if (manifestArch != null) {
                zip.putNextEntry(ZipEntry("edgecube-package.json"))
                zip.write(
                    """
                    {
                      "formatVersion": 1, "type": "frpc", "id": "frpc0.69.1",
                      "arch": $manifestArch
                    }
                    """.trimIndent().toByteArray()
                )
                zip.closeEntry()
            }
            if (includeLib) {
                zip.putNextEntry(ZipEntry("$archDir/lib/libfrpc.so"))
                zip.write(libBytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `zip 头被识别为 ecpkg`() {
        assertTrue(FrpcEcpkg.looksLikeEcpkg(ecpkg()))
    }

    @Test
    fun `裸 ELF 不被当成 ecpkg`() {
        val so = tmp.newFile("libfrpc.so")
        so.writeBytes(ByteArray(1024) { i -> if (i == 0) 0x7F else 0 })
        assertFalse(FrpcEcpkg.looksLikeEcpkg(so))
    }

    @Test
    fun `按清单里的架构目录取库`() {
        val lib = ByteArray(4096) { (it % 251).toByte() }
        val dest = File(tmp.root, "out.so")

        FrpcEcpkg.extractLib(ecpkg(libBytes = lib), dest)

        assertArrayEquals(lib, dest.readBytes())
    }

    @Test
    fun `清单缺失时回退到默认架构目录`() {
        // 分发方漏了清单也不该整包作废：目录名有默认值可退。
        val lib = ByteArray(256) { 7 }
        val dest = File(tmp.root, "out.so")

        FrpcEcpkg.extractLib(ecpkg(manifestArch = null, libBytes = lib), dest)

        assertArrayEquals(lib, dest.readBytes())
    }

    @Test
    fun `清单写了别的架构目录时跟着清单走`() {
        // 目录名以清单为准而不是写死 aarch64 —— 分发方换了命名也能自适应。
        val lib = ByteArray(128) { 3 }
        val dest = File(tmp.root, "out.so")

        FrpcEcpkg.extractLib(
            ecpkg(archDir = "arm64-v8a", manifestArch = """{ "aarch64": { "dir": "arm64-v8a" } }""", libBytes = lib),
            dest
        )

        assertArrayEquals(lib, dest.readBytes())
    }

    @Test(expected = IOException::class)
    fun `包里没有库文件时报错`() {
        FrpcEcpkg.extractLib(ecpkg(includeLib = false), File(tmp.root, "out.so"))
    }
}
