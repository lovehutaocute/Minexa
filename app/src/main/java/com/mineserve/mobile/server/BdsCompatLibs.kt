package com.mineserve.mobile.server

import android.util.Log
import com.mineserve.mobile.data.BdsPackageIntegrity
import com.mineserve.mobile.data.MultiThreadDownloader
import io.airlift.compress.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * 历史版本 BDS 需要的 **x86_64 兼容运行库**（初始化环境时联网取）。
 *
 * ## 为什么需要它
 *
 * 官方 zip 从 1.19 到 1.21.x 是「半动态」构建：包里**只有 bedrock_server 一个 ELF**，
 * 它的 `DT_NEEDED` 里有 `libcurl.so.4`（1.19/1.20 还有 `libstdc++.so.6`）——这些库
 * 不在包里、也不在 arm64 容器里（容器是 aarch64 rootfs，同名库 box64 用不了）。
 * 实测（2026-11-07，解包 1.19.83.01 / 1.21.51.02）：
 * ```
 * bedrock_server NEEDED = libdl.so.2, libcurl.so.4, libstdc++.so.6, libm.so.6,
 *                         libgcc_s.so.1, libpthread.so.0, libc.so.6, ld-linux-x86-64.so.2
 * 包内 .so 数量 = 0
 * ```
 * ≤1.18 的版本还直接需要 `libssl.so.1.1` / `libcrypto.so.1.1` / `libz.so.1` / `libnsl.so.1`。
 * 缺任何一个，box64 都只报一句 `Error loading needed libs` —— 真机日志里那条
 * `Error initializing native libcurl.so.4` 就是缺 `libcurl.so.4` 整条链的伪装。
 *
 * ## 怎么补
 *
 * 不是硬编码一堆包名，而是**按 ELF 依赖递归**：从 bedrock_server 的直接依赖出发，
 * 每装一个 `.so` 就读它的 `DT_NEEDED`，缺什么按「soname → Ubuntu 包」映射继续取，
 * 直到闭包自洽。libcurl 一条链实测 37 个库、解出后约 13MB（focal amd64）。
 *
 * 取包走 Ubuntu focal 的 `Packages.gz` 索引（不写死文件名，写死迟早 404），
 * 索引带 SHA-256，下载后硬校验；解 `data.tar.*` 只取需要的 `.so`，其余不落地。
 * 已存在的库跳过下载但**仍会递归它的依赖**（上次装到一半的情况能自动补全）。
 *
 * `libnsl.so.1` 是历史遗留：glibc 2.27 起从 libc6 拆出、focal 只剩 `libnsl.so.2`，
 * 单独从 old-releases 的 `libc6 2.21` 取；取不到只警告（只有 ≤1.18 用它）。
 */
object BdsCompatLibs {

    private const val TAG = "BdsCompatLibs"

    /**
     * bedrock_server 的直接依赖里**包内不带**、必须补齐的根（1.19~1.21 与 ≤1.18 的并集）。
     * 1.22+ 是全静态构建，一个都不需要。
     */
    private val ROOTS = listOf(
        "libcurl.so.4",
        "libstdc++.so.6",
        "libssl.so.1.1",
        "libcrypto.so.1.1",
        "libz.so.1",
        "libnsl.so.1"
    )

    /**
     * soname → Ubuntu focal (main, amd64) 的包名。
     *
     * 由实测闭包生成（2026-11-07）：从 ROOTS 出发按真实 DT_NEEDED 递归，全部命中、
     * 闭包自洽。以后要支持新的依赖（比如别的构建变体），往这里加映射即可，
     * 递归逻辑不用动。
     */
    internal val SONAME_TO_PKG: Map<String, String> = mapOf(
        "libcurl.so.4" to "libcurl4",
        "libnghttp2.so.14" to "libnghttp2-14",
        "libidn2.so.0" to "libidn2-0",
        "librtmp.so.1" to "librtmp1",
        "libssh.so.4" to "libssh-4",
        "libpsl.so.5" to "libpsl5",
        "libgssapi_krb5.so.2" to "libgssapi-krb5-2",
        "libkrb5.so.3" to "libkrb5-3",
        "libk5crypto.so.3" to "libk5crypto3",
        "libcom_err.so.2" to "libcom-err2",
        "libkrb5support.so.0" to "libkrb5support0",
        "libkeyutils.so.1" to "libkeyutils1",
        "liblber-2.4.so.2" to "libldap-2.4-2",
        "libldap_r-2.4.so.2" to "libldap-2.4-2",
        "libsasl2.so.2" to "libsasl2-2",
        "libgnutls.so.30" to "libgnutls30",
        "libnettle.so.7" to "libnettle7",
        "libhogweed.so.5" to "libhogweed5",
        "libgmp.so.10" to "libgmp10",
        "libtasn1.so.6" to "libtasn1-6",
        "libp11-kit.so.0" to "libp11-kit0",
        "libffi.so.7" to "libffi7",
        "libunistring.so.2" to "libunistring2",
        "libbrotlidec.so.1" to "libbrotli1",
        "libbrotlicommon.so.1" to "libbrotli1",
        "libzstd.so.1" to "libzstd1",
        "libsqlite3.so.0" to "libsqlite3-0",
        "libgssapi.so.3" to "libgssapi3-heimdal",
        "libasn1.so.8" to "libasn1-8-heimdal",
        "libhcrypto.so.4" to "libhcrypto4-heimdal",
        "libroken.so.18" to "libroken18-heimdal",
        "libwind.so.0" to "libwind0-heimdal",
        "libhx509.so.5" to "libhx509-5-heimdal",
        "libkrb5.so.26" to "libkrb5-26-heimdal",
        "libheimbase.so.1" to "libheimbase1-heimdal",
        "libheimntlm.so.0" to "libheimntlm0-heimdal",
        "libresolv.so.2" to "libc6",
        // glibc 2.21 的 libnsl 还依赖 libcrypt.so.1（focal 起从 libc6 拆出的独立包）
        "libcrypt.so.1" to "libcrypt1",
        "libssl.so.1.1" to "libssl1.1",
        "libcrypto.so.1.1" to "libssl1.1",
        "libstdc++.so.6" to "libstdc++6",
        "libz.so.1" to "zlib1g"
    )

    /** focal 的官方镜像；国内优先，下载后一律按索引里的 SHA-256 硬校验 */
    private val MIRRORS = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu",
        "https://mirrors.ustc.edu.cn/ubuntu",
        "https://mirrors.aliyun.com/ubuntu",
        "http://archive.ubuntu.com/ubuntu"
    )

    /**
     * `libnsl.so.1` 的专属来源：old-releases 归档里的 glibc 2.21。
     *
     * 不走索引（归档目录结构与 dists 不同），直接固定文件名；404 时降级成警告。
     */
    private const val LEGACY_LIBC6_FILE = "libc6_2.21-0ubuntu4.3_amd64.deb"
    private const val LEGACY_LIBC6_PATH = "/ubuntu/pool/main/g/glibc/"
    private val LEGACY_MIRRORS = listOf(
        "http://old-releases.ubuntu.com",
        "https://old-releases.ubuntu.com"
    )

    /** 主仓库（Ubuntu focal，amd64）：闭包里所有包都在 main 里 */
    private const val FOCAL_DIST = "dists/focal/main/binary-amd64/Packages.gz"

    /** 索引里的一个 .deb 条目 */
    data class DebRef(val version: String, val filename: String, val sha256: String?)

    data class Report(
        /** 实际下载写入的 soname */
        val installed: List<String>,
        /** 之前已在位、本次跳过的 soname */
        val present: List<String>,
        /** 没能补齐的 soname（附原因），调用方决定是警告还是失败 */
        val failed: Map<String, String>
    ) {
        val ok: Boolean get() = failed.isEmpty()
    }

    /** 缺哪些根库（界面上提示「需要重新初始化」用） */
    fun missingTargets(prefix: File): List<String> =
        ROOTS.filter { !isUsableLib(File(BdsRuntime.x86LibDir(prefix), it)) }

    /**
     * 递归补齐全部兼容库。
     *
     * 流程：从 [ROOTS] 出发 BFS——库不在 `x86libs/` 就按映射下载解包；
     * 无论新装还是已有，都读它的 `DT_NEEDED` 把缺失的传递依赖排进队列，
     * 直到队列为空（闭包自洽）或达到 [MAX_LIBS] 防失控上限。
     *
     * @param onProgress 0..100（按已处理/剩余的比值，不追求线性）
     */
    suspend fun install(
        prefix: File,
        tmpDir: File,
        onLog: (String) -> Unit,
        onProgress: (Int) -> Unit = {}
    ): Report {
        val dir = BdsRuntime.x86LibDir(prefix).apply { mkdirs() }
        val installed = mutableListOf<String>()
        val failed = LinkedHashMap<String, String>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(ROOTS)
        // 惰性索引：所有库都在位时一次网络请求都不发
        var index: Map<String, DebRef>? = null

        fun reportProgress() {
            val done = visited.size
            val total = done + queue.size
            onProgress(if (total <= 0) 100 else (done * 100 / total).coerceIn(0, 99))
        }

        reportProgress()
        while (queue.isNotEmpty()) {
            val soname = queue.removeFirst()
            if (!visited.add(soname)) continue
            if (visited.size > MAX_LIBS) {
                onLog("兼容库数量超出预期上限（$MAX_LIBS），停止递归")
                break
            }
            if (!soname.contains(".so")) continue
            // 动态加载器由 box64 提供（内置 x86libs 里那份）
            if (soname.startsWith("ld-linux") || soname.startsWith("ld-")) continue

            var file = File(dir, soname)
            if (!isUsableLib(file)) {
                when {
                    soname == "libnsl.so.1" ->
                        if (!installLegacyLibnsl(tmpDir, dir, onLog)) {
                            failed[soname] = "old-releases 归档下载失败（仅 1.18 及更早需要）"
                        }
                    else -> {
                        val pkg = SONAME_TO_PKG[soname]
                        when {
                            pkg == null -> failed[soname] = "没有对应的 Ubuntu 包映射"
                            else -> {
                                // 一个 deb 常提供两个 soname（liblber+libldap_r、brotli 两兄弟）：
                                // 一次解出同包的全部目标，后续 BFS 遇到它们自然跳过
                                val group = SONAME_TO_PKG.entries
                                    .filter { it.value == pkg }
                                    .map { it.key }
                                val ref = index ?: fetchIndex(tmpDir, onLog)?.also { index = it }
                                if (ref == null) {
                                    group.forEach { failed[it] = "包索引不可用" }
                                } else {
                                    val entryRef = ref[pkg]
                                    if (entryRef == null) {
                                        group.forEach { failed[it] = "包索引里没有 $pkg" }
                                    } else {
                                        val ok = installPack(pkg, group, entryRef, tmpDir, dir, onLog)
                                        if (ok) {
                                            installed += group.filter { isUsableLib(File(dir, it)) }
                                        } else {
                                            group.forEach { failed[it] = "$pkg 下载或解包失败" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                file = File(dir, soname)
            }

            // 关键：已存在的库也要递归依赖 —— 上次装到一半的闭包能自动补全
            if (isUsableLib(file)) {
                BdsElfDeps.read(file)?.needed.orEmpty().forEach { dep ->
                    if (dep.contains(".so") && !dep.startsWith("ld-") &&
                        !isUsableLib(File(dir, dep)) &&
                        dep !in BdsRuntime.REQUIRED_X86_LIBS
                    ) {
                        queue += dep
                    }
                }
            }
            reportProgress()
        }
        onProgress(100)
        val present = (ROOTS + visited)
            .filter { it !in installed && isUsableLib(File(dir, it)) }
            .distinct()
        return Report(installed, present, failed)
    }

    /** 一个 soname 是否可用：是文件、体积合理、且确实是 x86_64 ELF */
    fun isUsableLib(file: File): Boolean {
        if (!file.isFile || file.length() < 4096) return false
        return runCatching { BdsSupport.elfArch(file) == BdsSupport.ElfArch.X86_64 }
            .getOrDefault(false)
    }

    // ── 单个包 ───────────────────────────────────────────────────

    private suspend fun installPack(
        pkg: String,
        targets: List<String>,
        ref: DebRef,
        tmpDir: File,
        targetDir: File,
        onLog: (String) -> Unit
    ): Boolean {
        val deb = File(tmpDir, ref.filename.substringAfterLast('/'))
        if (!downloadDeb(
                candidates = MIRRORS.map { "$it/${ref.filename}" },
                target = deb,
                sha256 = ref.sha256,
                onLog = onLog
            )
        ) return false
        val written = extract(deb, targetDir, targets.toSet(), onLog)
        deb.delete()
        if (written) onLog("$pkg 已安装（${targets.joinToString("、")}）")
        return written
    }

    /**
     * 从 old-releases 取 glibc 2.21 的 `libnsl.so.1`。
     *
     * 失败是**预期内**的（归档站点偶尔抽风、或将来被清理），所以只返回 false 让
     * 上层记警告：≤1.18 才需要它，而 ≤1.18 本身已经是「能装就装」的档位。
     */
    private suspend fun installLegacyLibnsl(
        tmpDir: File,
        targetDir: File,
        onLog: (String) -> Unit
    ): Boolean {
        val deb = File(tmpDir, LEGACY_LIBC6_FILE)
        if (!downloadDeb(
                candidates = LEGACY_MIRRORS.map { "$it$LEGACY_LIBC6_PATH$LEGACY_LIBC6_FILE" },
                target = deb,
                sha256 = null,
                onLog = onLog
            )
        ) return false
        // 包里是 libnsl-2.21.so + libnsl.so.1 软链：两个名字都提，确保能解出实体
        val written = extract(
            deb,
            targetDir,
            setOf("libnsl.so.1", "libnsl-2.21.so"),
            onLog,
            extraAliases = mapOf("libnsl-2.21.so" to "libnsl.so.1")
        )
        deb.delete()
        if (written) onLog("libnsl.so.1 已安装（glibc 2.21 归档）")
        return written
    }

    // ── 索引 ─────────────────────────────────────────────────────

    /**
     * 解析 `Packages.gz`，只留下 [SONAME_TO_PKG] 里需要的包。
     *
     * 索引 1.2MB（gz），比 deb 小得多，多镜像逐个试很划算；解析失败（比如镜像返回
     * HTML 错误页）视为该源不可用，继续下一个。
     */
    private suspend fun fetchIndex(tmpDir: File, onLog: (String) -> Unit): Map<String, DebRef>? {
        val want = SONAME_TO_PKG.values.toSet()
        MIRRORS.forEach { base ->
            // 不用 java.io.tmpdir：Android 上它指向 /data/local/tmp，普通应用不可写
            val gz = File(tmpDir, "bds-index-${System.nanoTime()}.gz")
            try {
                val ok = try {
                    MultiThreadDownloader.download(
                        url = "$base/$FOCAL_DIST",
                        target = gz,
                        onProgress = { _, _, _ -> },
                        onLog = { }
                    )
                    true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "索引下载失败（${hostOf(base)}）：${e.message}")
                    false
                }
                if (!ok || gz.length() < 100_000) {
                    onLog("索引源不可用：${hostOf(base)}")
                    return@forEach
                }
                val text = runCatching {
                    GzipCompressorInputStream(BufferedInputStream(gz.inputStream())).use {
                        String(it.readBytes(), Charsets.UTF_8)
                    }
                }.getOrNull()
                if (text == null || !text.contains("Package: ")) {
                    onLog("索引源内容异常：${hostOf(base)}")
                    return@forEach
                }
                val parsed = parseIndex(text, want)
                if (parsed.isNotEmpty()) {
                    onLog("包索引命中 ${parsed.size}/${want.size}（${hostOf(base)}）")
                    return parsed
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "解析索引失败: ${e.message}")
            } finally {
                gz.delete()
            }
        }
        return null
    }

    /** `Packages` 格式：空行分段，`键: 值`，续行以空格开头 */
    internal fun parseIndex(text: String, want: Set<String>): Map<String, DebRef> {
        val result = HashMap<String, DebRef>()
        text.split("\n\n").forEach { block ->
            if (!block.contains("Package:")) return@forEach
            var pkg: String? = null
            var version = ""
            var filename = ""
            var sha: String? = null
            block.lineSequence().forEach { line ->
                if (line.startsWith("Package:")) pkg = line.substringAfter(':').trim()
                else if (line.startsWith("Version:")) version = line.substringAfter(':').trim()
                else if (line.startsWith("Filename:")) filename = line.substringAfter(':').trim()
                else if (line.startsWith("SHA256:")) sha = line.substringAfter(':').trim()
            }
            val name = pkg ?: return@forEach
            if (name in want && filename.isNotEmpty()) {
                result[name] = DebRef(version, filename, sha)
            }
        }
        return result
    }

    // ── 下载 ─────────────────────────────────────────────────────

    private suspend fun downloadDeb(
        candidates: List<String>,
        target: File,
        sha256: String?,
        onLog: (String) -> Unit
    ): Boolean {
        candidates.forEach { url ->
            val host = hostOf(url)
            target.delete()
            val ok = try {
                MultiThreadDownloader.download(
                    url = url,
                    target = target,
                    onProgress = { _, _, _ -> },
                    onLog = { }
                )
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                onLog("下载失败（$host）：${e.message ?: e.javaClass.simpleName}")
                false
            }
            if (!ok) return@forEach
            val problem = validateDeb(target, sha256)
            if (problem != null) {
                onLog("$host 的文件不可用：$problem")
                target.delete()
                return@forEach
            }
            onLog("已下载 ${target.name}（${target.length() / 1024}KB，$host）")
            return true
        }
        return false
    }

    private fun validateDeb(file: File, expectedSha: String?): String? {
        if (!file.isFile || file.length() < 1024) return "文件过小（${file.length()} 字节）"
        val header = ByteArray(8)
        val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(0)
        if (read != 8 || !header.contentEquals("!<arch>\n".toByteArray())) return "不是 deb（ar）归档"
        if (expectedSha != null) {
            val actual = runCatching { BdsPackageIntegrity.sha256(file) }.getOrNull()
                ?: return "无法计算 SHA-256"
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                return "SHA-256 不匹配（下载不完整或镜像内容不一致）"
            }
        }
        return null
    }

    // ── 解包 ─────────────────────────────────────────────────────

    /**
     * 从 deb 里取出 [targets] 对应的 `.so` 写到 [targetDir]。
     *
     * 包里的库有三种形态，全都要认：
     *  - 实体与 soname 同名（`libssl.so.1.1`）；
     *  - 只有版本尾实体、soname 软链在 -dev 包里（`libz.so.1` ← `libz.so.1.2.11`）；
     *  - glibc 的 `stem-2.xx.so` 实体 + soname 软链（`libresolv.so.2` ← `libresolv-2.31.so`）。
     *
     * 落盘一律写成**真实文件**：这些 `.so` 会被 box64 通过容器路径加载，
     * 软链跨 PRoot 命名空间不可靠。
     */
    internal fun extract(
        deb: File,
        targetDir: File,
        targets: Set<String>,
        onLog: (String) -> Unit,
        extraAliases: Map<String, String> = emptyMap()
    ): Boolean {
        val blobs = HashMap<String, ByteArray>()
        val links = HashMap<String, String>()
        var sawData = false
        try {
            deb.inputStream().buffered().use { input ->
                val magic = ByteArray(8)
                if (input.read(magic) != 8 || !magic.contentEquals("!<arch>\n".toByteArray())) {
                    onLog("${deb.name} 不是 ar 归档")
                    return false
                }
                members@ while (true) {
                    val header = ByteArray(60)
                    var read = 0
                    while (read < 60) {
                        val n = input.read(header, read, 60 - read)
                        if (n <= 0) break@members
                        read += n
                    }
                    val name = String(header, 0, 16, Charsets.US_ASCII).trim()
                    val size = String(header, 48, 10, Charsets.US_ASCII)
                        .trim().toLongOrNull() ?: break@members
                    if (name.startsWith("data.tar")) {
                        sawData = true
                        val tar = openTar(LimitedStream(input, size)) ?: break@members
                        tar.use { collect(it, targets, blobs, links) }
                        break@members
                    }
                    skipFully(input, size)
                    if (size % 2 == 1L) skipFully(input, 1)
                }
            }
        } catch (e: Throwable) {
            onLog("${deb.name} 解包失败：${e.message ?: e.javaClass.simpleName}")
            return false
        }
        if (!sawData) {
            onLog("${deb.name} 里没有 data.tar")
            return false
        }

        targetDir.mkdirs()
        var written = false
        targets.forEach { name ->
            val bytes = resolve(blobs, links, name, 0) ?: return@forEach
            val out = File(targetDir, extraAliases[name] ?: name)
            val wrote = runCatching { out.writeBytes(bytes) }.isSuccess
            if (!wrote) {
                onLog("写入 ${out.name} 失败")
                return@forEach
            }
            written = true
            // 动态库不需要可执行位；chmod 单独包起来（单元测试里 android.system.Os 是桩）
            runCatching { android.system.Os.chmod(out.absolutePath, "644".toInt(8)) }
        }
        return written
    }

    /** 扫一遍 tar，只留住命中 [targets] 的条目（普通文件存字节，软链记目标名） */
    private fun collect(
        tar: TarArchiveInputStream,
        targets: Set<String>,
        blobs: MutableMap<String, ByteArray>,
        links: MutableMap<String, String>
    ) {
        var entry: TarArchiveEntry? = tar.nextEntry
        while (entry != null) {
            val base = entry.name.removePrefix("./").substringAfterLast('/')
            if (isWanted(base, targets)) {
                when {
                    entry.isSymbolicLink -> links[base] = entry.linkName.substringAfterLast('/')
                    entry.isFile && entry.size in 1..MAX_LIB_BYTES ->
                        blobs[base] = readLimited(tar, entry.size)
                }
            }
            entry = tar.nextEntry
        }
    }

    /** 三种形态：精确 soname、`soname.版本尾`、`stem-2.xx.so`（glibc 实体） */
    private fun isWanted(base: String, targets: Set<String>): Boolean {
        if (base in targets) return true
        if (!base.contains(".so")) return false
        targets.forEach { t ->
            if (base.startsWith("$t.")) return true
            val stem = t.substringBefore(".so")
            if (stem.length > 3 && base.startsWith("$stem-")) return true
        }
        return false
    }

    /**
     * 解析出一个 soname 的实际字节。
     *
     * 三级回退：实体同名 → 跟软链（最多 4 跳）→ 同前缀/同 stem 的最长实体。
     */
    private fun resolve(
        blobs: Map<String, ByteArray>,
        links: Map<String, String>,
        name: String,
        depth: Int
    ): ByteArray? {
        blobs[name]?.let { return it }
        if (depth < 4) {
            links[name]?.let { next -> resolve(blobs, links, next, depth + 1)?.let { return it } }
        }
        val stem = name.substringBefore(".so")
        val fallback = blobs.keys
            .filter { it.startsWith("$name.") || it.startsWith("$stem-") }
            .maxByOrNull { it.length }
            ?: return null
        return blobs[fallback]
    }

    private fun openTar(raw: InputStream): TarArchiveInputStream? {
        val buffered = BufferedInputStream(raw, 64 * 1024)
        buffered.mark(8)
        val magic = ByteArray(6)
        val len = buffered.read(magic)
        buffered.reset()
        val decompressed: InputStream = when {
            len >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() ->
                GzipCompressorInputStream(buffered)
            len >= 6 && magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() &&
                magic[2] == 0x7A.toByte() && magic[3] == 0x58.toByte() && magic[4] == 0x5A.toByte() ->
                XZCompressorInputStream(buffered)
            len >= 4 && magic[0] == 0x28.toByte() && magic[1] == 0xB5.toByte() &&
                magic[2] == 0x2F.toByte() && magic[3] == 0xFD.toByte() ->
                try {
                    ZstdInputStream(buffered)
                } catch (e: Throwable) {
                    // Android 14+ 上 aircompressor 的 zstd 会抛 NoSuchFieldError（Error 不是 Exception）
                    Log.w(TAG, "无法解压 zstd：${e.message}")
                    return null
                }
            else -> buffered
        }
        return TarArchiveInputStream(decompressed)
    }

    private fun readLimited(input: InputStream, size: Long): ByteArray {
        val out = ByteArrayOutputStream(minOf(size, 1L shl 20).toInt())
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n <= 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n <= 0) return
            remaining -= n
        }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/')

    /** ar 成员是有界的：不能读到下一个成员的头里 */
    private class LimitedStream(
        private val source: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = source.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
    }

    /** 单个 `.so` 上限：libcrypto 约 3MB，32MB 足够拦住异常条目（防止解压炸弹） */
    private const val MAX_LIB_BYTES = 32L * 1024 * 1024

    /** 闭包防失控：实测 37 个，留一倍余量 */
    private const val MAX_LIBS = 96
}
