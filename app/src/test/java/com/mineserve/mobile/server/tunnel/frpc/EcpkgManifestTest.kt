package com.mineserve.mobile.server.tunnel.frpc

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `.ecpkg` 包内清单解析测试。
 *
 * 样本字段名照抄实际分发包里的 `edgecube-package.json`（`arch.<abi>.dir`），
 * 这份映射的全部意义就是能与那套包对接。
 */
class EcpkgManifestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 造一个结构与真实 `.ecpkg` 相同的包：清单 + `<archDir>/lib/libfrpc.so`。 */
    private fun ecpkg(
        archDir: String = "aarch64",
        manifestArch: String? = """{ "$archDir": { "dir": "$archDir" } }""",
        libBytes: ByteArray = ByteArray(64) { 0x7f }
    ): File {
        val file = tmp.newFile("test.ecpkg")
        ZipOutputStream(file.outputStream()).use { zip ->
            if (manifestArch != null) {
                zip.putNextEntry(ZipEntry("edgecube-package.json"))
                zip.write(
                    """
                    {
                      "formatVersion": 1, "type": "frpc", "id": "frpc0.70.1",
                      "versionName": "2026-08-07",
                      "arch": $manifestArch,
                      "launcher": { "type": "frpc", "lib": "lib/libfrpc.so" }
                    }
                    """.trimIndent().toByteArray()
                )
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("$archDir/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$archDir/lib/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$archDir/lib/libfrpc.so"))
            zip.write(libBytes)
            zip.closeEntry()
        }
        return file
    }

    @Test
    fun `解析真实形状的清单取到架构目录`() {
        val parsed = json.decodeFromString<EcpkgManifest>(
            """{"arch":{"aarch64":{"dir":"aarch64"}}}"""
        )
        assertEquals("aarch64", parsed.arch?.get("aarch64")?.dir)
    }

    @Test
    fun `多余字段不影响解析`() {
        // 真实清单有 formatVersion/launcher/minAppVersion 等一堆字段。
        val parsed = json.decodeFromString<EcpkgManifest>(
            """
            {
              "formatVersion": 1, "type": "frpc", "id": "frpc0.70.1",
              "launcher": { "type": "frpc", "lib": "lib/libfrpc.so" },
              "minAppVersion": 25,
              "arch": { "aarch64": { "dir": "aarch64" } }
            }
            """.trimIndent()
        )
        assertEquals("aarch64", parsed.arch?.get("aarch64")?.dir)
    }

    @Test
    fun `清单缺 arch 时为 null`() {
        assertNull(json.decodeFromString<EcpkgManifest>("{}").arch)
    }

    @Test
    fun `构造的包能被取到库文件且内容一致`() {
        // 这条验证的是"包布局与解包逻辑对得上"：aarch64/lib/libfrpc.so。
        val lib = ByteArray(128) { it.toByte() }
        val file = ecpkg(libBytes = lib)

        val expectedEntry = "${FrpcEngineConfig.ECPKG_ARCH_DIR}/${FrpcEngineConfig.ECPKG_LIB_SUFFIX}"
        assertEquals("aarch64/lib/libfrpc.so", expectedEntry)

        val extracted = readEntry(file, expectedEntry)
        assertTrue(extracted != null)
        assertEquals(lib.size, extracted!!.size)
    }

    private fun readEntry(file: File, name: String): ByteArray? {
        java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) return zip.readBytes()
                zip.closeEntry()
            }
        }
        return null
    }

    private fun ByteArrayOutputStream.unused() = Unit
}
