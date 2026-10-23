package com.mineserve.mobile.server.tunnel.frpc

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * frpc 引擎（libfrpc.so）的本地存储与校验。
 *
 * ## 为什么引擎不随 APK 打包
 *
 * frpc 引擎是 Go 以 `c-shared` 方式编译的 `libfrpc.so`（约 15MB，导出 `RunFrpc`）。
 * 把它放在**数据目录**而不是 `lib/` 有三个实际好处：
 *  - 可热更：换引擎不必重装 APK，用户导入新版本即可覆盖；
 *  - 可自由选择版本：frp 各版本接受的配置字段集不同，各托管平台下发配置的字段集
 *    又跟着官方新版走，把版本选择权交给用户比焊死在 APK 里更耐用；
 *  - 不撬动 APK 体积与签名流程。
 *
 * 数据目录里的 `.so` 不能直接 `execve`（API 29+ 的 SELinux 限制），所以由随包
 * 落在 `nativeLibraryDir` 的 `libfrpcloader.so` 去 `dlopen` 它 —— 见
 * `app/src/main/cpp/frpcloader.c`。
 *
 * ## 与 EdgeCube 的差异
 *
 * EdgeCube 用 `edgecube-package.json` 清单描述一个运行时包（含 arch 目录、launcher
 * 字段、env 等），支持一个包内装多种运行时。本项目只有 frpc 一种引擎，直接以
 * 「一个 .so 文件」为单位导入即可，不引入清单格式 —— 少一层可错的解析，用户也少
 * 一件要做的事。清单里真正有用的信息（版本号）改为从文件名里认，见 [installedVersion]。
 */
class FrpcRuntime(private val appContext: Context) {

    companion object {
        /** 引擎落盘文件名。必须保持 `.so` 后缀：dlopen 不关心后缀，但人工排查时认得出来。 */
        const val ENGINE_NAME = "libfrpc.so"

        /** 随 APK 打包、位于 nativeLibraryDir 的启动器。 */
        const val LOADER_NAME = "libfrpcloader.so"

        /** 版本号旁挂文件名（纯展示/排障用，不参与加载）。 */
        private const val VERSION_FILE = "version.txt"

        /**
         * 引擎体积下限（字节）。
         *
         * 真实引擎约 15MB（Go 运行时本身就有数 MB）。设一个下限是为了挡住
         * 「用户选错文件」这类错误 —— 比如把 config.toml、一个几百字节的脚本
         * 或别的第三方 .so 导进来。这种文件照样能写盘，但要等到隧道启动、
         * dlopen/dlsym 失败时才报错，那时错误信息离用户的操作已经很远了。
         */
        const val MIN_ENGINE_BYTES = 1L * 1024 * 1024

        private val VERSION_REGEX = Regex("""(\d+\.\d+\.\d+)""")

        /**
         * 校验引擎文件体积；通过返回 null，否则返回给用户看的错误信息。
         *
         * 与 [validateElfHeader] 分开是为了让测试能直接覆盖两条规则，也为了让
         * 真实导入流程能**先报体积**：一个 200 字节的文本文件，说「不像是引擎」
         * 比说「不是 ELF」更贴近用户的真实错误（选错了文件）。
         */
        internal fun validateSize(length: Long): String? {
            if (length >= MIN_ENGINE_BYTES) return null
            return "所选文件只有 $length 字节，不像是 frpc 引擎（至少应为 1MB）。" +
                "请确认选中的是 libfrpc.so，而不是配置文件。"
        }

        /**
         * 校验 ELF 头（魔数 + `e_machine`）；通过返回 null，否则返回错误信息。
         *
         * 三条检查各自挡住一类真实错误：
         *  - 魔数 —— 用户选中的是 zip/文本/配置；
         *  - `e_machine` —— 导入了 x86_64 或 arm32 的引擎（同样叫 libfrpc.so）；
         *  - 文件过短读不出头 —— 文件损坏或被截断。
         */
        internal fun validateElfHeader(header: ByteArray, length: Long): String? {
            validateSize(length)?.let { return it }

            if (header.size < 20) return "无法读取文件头，文件可能已损坏或被截断"

            // ELF 魔数 \x7fELF
            if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
            ) {
                return "所选文件不是 ELF 二进制（缺少 ELF 魔数），请选择 libfrpc.so"
            }

            // e_machine 位于偏移 18，小端 2 字节；0xB7 = AArch64，0x28 = ARM(32)。
            val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            if (machine != 0xB7) {
                val archName = when (machine) {
                    0x3E -> "x86-64"
                    0x28 -> "ARM 32 位"
                    0x03 -> "x86"
                    else -> "未知架构 (0x%02x)".format(machine)
                }
                return "引擎架构不匹配：该文件是 $archName，而本应用只支持 arm64-v8a。" +
                    "请导入 linux/arm64 版本的 libfrpc.so。"
            }
            return null
        }

        /** 从文件名里认出版本号（`libfrpc-0.71.0-arm64.so` → `0.71.0`）。 */
        internal fun parseVersion(name: String?): String? =
            name?.let { VERSION_REGEX.find(it)?.groupValues?.get(1) }
    }

    /** 引擎存放目录。 */
    val engineDir: File
        get() = File(appContext.filesDir, "frpc").apply { mkdirs() }

    /** 引擎文件路径。 */
    val engineFile: File
        get() = File(engineDir, ENGINE_NAME)

    /** 启动器路径（nativeLibraryDir 在真机上一定存在；单元测试里可能为 null）。 */
    val loaderFile: File?
        get() = appContext.applicationInfo?.nativeLibraryDir?.let { File(it, LOADER_NAME) }

    /** 引擎是否已就绪。 */
    fun isInstalled(): Boolean = engineFile.isFile && engineFile.length() >= MIN_ENGINE_BYTES

    /**
     * 引擎自报版本；无法从文件名/内容判定时返回 null。
     *
     * 没有清单文件，就从原始文件名里认版本号（用户导入的通常是
     * `libfrpc-0.71.0-arm64.so` 或 `frpc0.69.1-aarch64.ecpkg` 这类名字）。
     * 装好后写进 `version.txt` 旁挂文件 —— 否则进程一重启这个字段就空了，
     * 而排障时「设备上到底是哪个 frpc 在报错」往往是第一个要确定的事实。
     */
    val installedVersion: String?
        get() {
            if (!versionLoaded) {
                versionCache = readStoredVersion()
                versionLoaded = true
            }
            return versionCache
        }

    @Volatile
    private var versionCache: String? = null

    @Volatile
    private var versionLoaded = false

    private val versionFile: File
        get() = File(engineDir, VERSION_FILE)

    private fun readStoredVersion(): String? {
        if (!engineFile.isFile) return null
        return runCatching { versionFile.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun storeVersion(version: String?) {
        versionCache = version
        versionLoaded = true
        runCatching {
            if (version.isNullOrBlank()) versionFile.delete() else versionFile.writeText(version)
        }
    }

    /**
     * 从 [source] 导入引擎。
     *
     * 接受两种输入：裸的 `libfrpc.so`，或 `.ecpkg` 包（ZIP 容器，内含
     * `<架构目录>/lib/libfrpc.so`）。后者是官方分发物的原生形态 —— 用户从网盘
     * 或 Release 页拿到的就是这个，若只认裸库，「手动下载后导入」这条路等于不通。
     *
     * 先写临时文件、校验通过后再原子改名覆盖正式文件：直接往 [engineFile] 写的话，
     * 中途失败（用户取消、存储写满、文件不是合法引擎）会把**原本可用的引擎**毁掉，
     * 而用户手上未必还有第二份可导入的引擎。
     *
     * @param suggestedName 来源文件名，仅用于推断版本号展示。
     * @throws IllegalArgumentException 文件过小、不是合法 ELF、或架构不是 arm64。
     */
    fun install(source: InputStream, suggestedName: String?) {
        val tmp = File(engineDir, "$ENGINE_NAME.tmp")
        val raw = File(engineDir, "$ENGINE_NAME.raw.tmp")
        try {
            tmp.outputStream().use { out -> source.copyTo(out) }

            // .ecpkg 先解包；解出来的库落在 raw，之后与裸库走同一条校验/安装路径。
            val payload = if (FrpcEcpkg.looksLikeEcpkg(tmp)) {
                FrpcEcpkg.extractLib(tmp, raw)
                raw
            } else {
                tmp
            }

            validate(payload)

            if (engineFile.exists() && !engineFile.delete()) {
                throw IllegalStateException("无法覆盖已安装的引擎，请先卸载后重试")
            }
            if (!payload.renameTo(engineFile)) {
                // renameTo 在部分文件系统上会失败，退回「复制 + 删临时文件」。
                payload.copyTo(engineFile, overwrite = true)
                payload.delete()
            }
            storeVersion(parseVersion(suggestedName))
        } finally {
            if (tmp.exists()) tmp.delete()
            if (raw.exists()) raw.delete()
        }
        // 引擎必须可执行位；实际执行由 loader 的 dlopen 完成，但保持一致更省事，
        // 也便于未来改用别的加载路径。
        engineFile.setExecutable(true, false)
    }

    /** 卸载引擎。 */
    fun uninstall(): Boolean {
        storeVersion(null)
        return !engineFile.exists() || engineFile.delete()
    }

    /** 引擎 SHA-256（十六进制），用于界面展示与比对；文件缺失返回 null。 */
    fun engineSha256(): String? {
        if (!engineFile.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        engineFile.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 校验并安装前的最后一关：读文件头，交给 [validateElfHeader] 判定。
     *
     * 规则本身与用户可见的错误文案都在静态方法里（见那边的注释），这里只负责
     * 读盘与把结果转成异常。版本号不在这里处理 —— 它与内容无关，见 [storeVersion]。
     */
    private fun validate(file: File) {
        val length = file.length()

        val header = ByteArray(20)
        val read = file.inputStream().use { input -> input.read(header) }
        val effectiveHeader = if (read > 0) header.copyOf(read) else ByteArray(0)

        validateElfHeader(effectiveHeader, length)?.let { throw IllegalArgumentException(it) }
    }
}
