package com.mineserve.mobile.server.tunnel.frpc

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `.ecpkg` 包内清单 `edgecube-package.json` 的最小映射，只取架构目录。 */
@Serializable
internal data class EcpkgManifest(
    val arch: Map<String, EcpkgArch>? = null
)

@Serializable
internal data class EcpkgArch(
    val dir: String? = null
)

/**
 * `.ecpkg`（EdgeCube 的运行时包格式）解包。
 *
 * ## 为什么单独成一处
 *
 * 包可能从两条路进来：在线下载（[FrpcRuntimeDownloader]）和用户手动导入
 * （网盘下完后在文件选择器里选中它）。两条路都必须认得同一种容器、同一个内部布局，
 * 各写一份就会有一份慢慢落后。
 *
 * ## 布局
 *
 * ```
 * edgecube-package.json      # 清单，arch.<abi>.dir 给出架构目录名
 * aarch64/lib/libfrpc.so     # 真正的引擎
 * ```
 *
 * 架构目录名以清单为准而不是写死 —— 分发方换了命名也能自适应；
 * 清单缺失或没写该架构时回退到 [FrpcEngineConfig.ECPKG_ARCH_DIR]。
 *
 * 不用 `ZipFile`（需要真实路径，还得自己管生命周期），直接在文件流上走
 * `ZipInputStream`；解出来的库写进调用方指定的文件，**不进内存** ——
 * 真实引擎 15MB，multi 包 18MB，堆里再兜一份没有意义。
 */
internal object FrpcEcpkg {

    /** ZIP 本地文件头魔数 `PK\x03\x04`，用于把 .ecpkg 与裸 .so 区分开。 */
    private fun isZip(header: ByteArray): Boolean =
        header.size >= 4 &&
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()

    /** 按文件头判断 [file] 是不是 ZIP 容器（`.ecpkg` / `.zip`）。 */
    fun looksLikeEcpkg(file: File): Boolean = runCatching {
        val header = ByteArray(4)
        val read = file.inputStream().use { it.read(header) }
        read == 4 && isZip(header)
    }.getOrDefault(false)

    /**
     * 从 [container] 里把本机架构的 `libfrpc.so` 解到 [dest]。
     *
     * @throws IOException 容器损坏、或包内找不到库文件。
     */
    fun extractLib(container: File, dest: File) {
        val archDir = readArchDir(container) ?: FrpcEngineConfig.ECPKG_ARCH_DIR
        val entryName = "$archDir/${FrpcEngineConfig.ECPKG_LIB_SUFFIX}"

        container.inputStream().buffered().use { fileInput ->
            ZipInputStream(fileInput).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name == entryName) {
                        dest.outputStream().use { out -> zip.copyTo(out) }
                        return
                    }
                    zip.closeEntry()
                }
            }
        }
        throw IOException("包内未找到 $entryName")
    }

    /** 从清单里读出本设备的架构目录名；清单缺失、包损坏或没写该架构时返回 null。 */
    private fun readArchDir(container: File): String? = runCatching {
        container.inputStream().buffered().use { fileInput ->
            ZipInputStream(fileInput).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == FrpcEngineConfig.ECPKG_MANIFEST) {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        val json = Json { ignoreUnknownKeys = true; isLenient = true }
                        val manifest = json.decodeFromString<EcpkgManifest>(text)
                        val spec = manifest.arch?.get(FrpcEngineConfig.ECPKG_ARCH_DIR)
                        return@use spec?.dir?.takeIf { it.isNotBlank() }
                    }
                    zip.closeEntry()
                }
                null
            }
        }
    }.getOrNull()
}
