package com.mineserve.mobile.server

import android.util.Log
import com.mineserve.mobile.data.BdsEnvError
import com.mineserve.mobile.data.BdsEnvStage
import com.mineserve.mobile.data.BdsEnvStatus
import com.mineserve.mobile.data.BdsSources
import com.mineserve.mobile.data.BdsContainerDistro
import com.mineserve.mobile.data.BdsBox64MinGlibc
import com.mineserve.mobile.data.DownloadSource
import com.mineserve.mobile.data.MultiSourceDownload
import com.mineserve.mobile.runtime.BDS_RUNTIME_MIN_FREE_MB
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 官方 BDS 的**专属运行环境**管理器。
 *
 * ## 它管的是什么
 *
 * 一套「arm64 安卓设备能跑官方 x86_64 BDS」的完整环境：
 *
 *  1. glibc 容器（PRoot Ubuntu rootfs）—— box64 本身是 aarch64 **glibc** 程序，
 *     bionic 上跑不起来；容器同时提供 BDS 依赖的 glibc；
 *  2. box64 转译器 —— 从第三方 apt 仓库取 .deb，只解出可执行文件；
 *  3. 就绪标记 —— 写 `<prefix>/var/lib/mineserve/bds/runtime.ready`，
 *     标记里带发行版与 box64 版本，重启后据此判定「已初始化」。
 *
 * 这**不是** Java 运行环境：它没有 JDK、不参与 `JavaVendor` 的选择，
 * 界面上也独立成一栏（「BDS 专属」），避免用户把它当成某个 Java 版本。
 *
 * ## 为什么初始化必须显式触发
 *
 * 首次初始化要下 40MB 左右、还要解压 rootfs，耗时几分钟。放在「点启动」路径里
 * 会让用户以为服务器卡死，也没有地方显示阶段与失败原因。因此约定：
 * **只有这里能装**，启动时若未初始化则直接拒绝并指向本页面。
 *
 * ## 状态机
 *
 * [status] 是唯一对外状态：`installed / installing / stage / percent / error`。
 * 进度不是「假滚动条」—— 每一档都对应真实动作（解压、下载字节数、容器内实测）。
 * 失败后可以原地重试：所有中间产物都写在临时目录并在失败时清理，
 * 重试不会读到上一次的半截文件。
 */
class BdsEnvironmentManager(
    private val context: android.content.Context,
    private val termux: TermuxRuntime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val prefix: File get() = termux.installer.rootDir

    private val _status = MutableStateFlow(
        BdsEnvStatus(distroLabel = runCatching { termux.bdsContainerProfile().distro.displayName }.getOrNull())
    )
    val status: StateFlow<BdsEnvStatus> = _status.asStateFlow()

    /** 单飞：同一时刻只允许一次初始化 */
    private val running = AtomicReference<Job?>(null)

    /** 缺 x86 运行库时的自动补齐只做一次，避免失败后每次刷新都重来 */
    private var libRepairAttempted = false

    fun refresh() {
        if (_status.value.installing) return
        val previous = _status.value
        // 老版本装过 box64 但没有 x86_64 运行库（那一版根本没有这个目录）：
        // 升级后第一次进页面就补齐，避免用户看到「已就绪」却起不来。
        // 放到 IO 协程里做：3.4MB 的复制不该占着主线程。
        if (!libRepairAttempted && BdsRuntime.box64Binary(prefix).isFile && !BdsRuntime.hasX86Libs(prefix)) {
            // 只试一次：资源本身坏了（比如 APK 被改）的话，反复重试只会每进一次页面
            // 就复制 3.4MB，还得不到任何改善。
            libRepairAttempted = true
            scope.launch {
                runCatching { BdsRuntime.installBundledBox64(context, prefix) }
                refresh()
            }
            return
        }
        val installed = isReady(prefix)
        val version = BdsRuntime.installedBox64Version(prefix)
        _status.value = BdsEnvStatus(
            installed = installed,
            box64Version = version,
            distroLabel = runCatching { termux.bdsContainerProfile().distro.displayName }.getOrNull(),
            percent = if (installed) 100 else 0,
            // 未就绪时保留上一次的失败原因：用户切页再回来仍能看到「为什么失败」，
            // 而不是一个干净的「未初始化」把线索抹掉。装好之后一律清空。
            error = if (installed) null else previous.error,
            detail = if (installed) null else previous.detail,
            rawDetail = if (installed) null else previous.rawDetail,
            attempts = if (installed) emptyList() else previous.attempts
        )
    }

    /** 用户点「初始化 / 重新初始化」 */
    fun startInitialize() {
        val current = running.get()
        if (current?.isActive == true) return
        val job = scope.launch { runInitialize() }
        running.set(job)
    }

    /**
     * 取消初始化。
     *
     * 只停协程，**删掉半成品**：rootfs 目录、box64 可执行文件与标记。
     * 否则下次 refresh() 会把「下了一半的 box64」当成已安装。
     */
    fun cancel() {
        running.getAndSet(null)?.cancel()
        scope.launch { cleanPartialArtifacts() }
    }

    /**
     * 删除已装的 box64 与就绪标记（保留容器）。
     *
     * 刻意不删容器：容器是发行版级的共享资源，Java 版（PRoot 来源）也在用，
     * 从 BDS 这一栏把它删掉等于悄悄破坏别人的运行环境。
     */
    fun removeBox64() {
        if (running.get()?.isActive == true) return
        BdsRuntime.box64Binary(prefix).delete()
        BdsRuntime.box64VersionFile(prefix).delete()
        readyMarker(prefix).delete()
        BdsRuntime.rootDir(prefix).resolve("run").deleteRecursively()
        refresh()
    }

    // ── 初始化流程 ───────────────────────────────────────────────

    private suspend fun runInitialize() {
        val profile = termux.bdsContainerProfile()
        _status.update {
            it.copy(
                installing = true,
                installed = false,
                error = null,
                rawDetail = null,
                attempts = emptyList(),
                stage = BdsEnvStage.Container,
                percent = 0,
                source = null,
                detail = null,
                distroLabel = profile.distro.displayName
            )
        }
        loggedStage = null
        loggedBucket = -1
        stageFloor = -1
        log("开始初始化 BDS 运行环境（${profile.distro.displayName}，x86_64 → arm64 转译）")
        // 容器发行版不再跟随用户的 Java 选择：内置 box64 的符号版本是编译期写死的，
        // 装进 glibc 更旧的容器里连 main 都进不去。这里把「为什么不是用户选的那个」
        // 明确说一次 —— 否则用户会以为自己选的 focal 被无视了。
        val wantDistro = termux.selectedProotDistro
        if (!wantDistro.glibcAtLeast(BdsBox64MinGlibc)) {
            log(
                "注意：BDS 容器固定使用 ${profile.distro.dirName}（glibc ${profile.distro.glibc}）。" +
                    "内置 box64 需要 glibc ≥ $BdsBox64MinGlibc，而 ${wantDistro.displayName} " +
                    "只有 ${wantDistro.glibc}，按其运行会报 GLIBC version not found。"
            )
        }
        try {
            val freeMb = prefix.usableSpace / (1024 * 1024)
            if (freeMb < BDS_RUNTIME_MIN_FREE_MB) {
                fail(
                    BdsEnvError.Space,
                    "应用私有分区只剩 ${freeMb}MB，BDS 运行环境约需 ${BDS_RUNTIME_MIN_FREE_MB}MB" +
                        "（容器约 120MB + box64 约 30MB + 服务端运行副本约 244MB）"
                )
                return
            }

            // ① 容器
            emitStage(BdsEnvStage.Container, 0)
            val containerOk = termux.prepareBdsContainer(profile) { pct ->
                emitStage(BdsEnvStage.Container, pct)
            }
            if (!containerOk) {
                fail(BdsEnvError.Container, "PRoot 容器（${profile.distro.displayName}）无法准备完成")
                return
            }

            // ② box64
            if (!installBox64()) return

            // ③ 容器内实测：文件在位 ≠ 能跑
            emitStage(BdsEnvStage.Verify, 10)
            val problem = termux.verifyBdsBox64(profile, BdsRuntime.rootDir(prefix))
            if (problem != null) {
                fail(BdsEnvError.Verify, problem)
                return
            }
            emitStage(BdsEnvStage.Verify, 100)

            /*
             * ④ 出网能力（只有在线模式需要，但装环境时就该修好）
             *
             * 两个动作合并做：
             *  - **修复**：容器里常常没有 CA 根证书（Ubuntu Base 最小 rootfs 不带
             *    ca-certificates），没有它 libcurl 校验不了微软的证书链，在线模式
             *    会挂 60 秒后报 `Could not connect to Minecraft services`，现象与
             *    「被墙」完全一样（2026-10-31 真机踩过）。
             *  - **检测**：DNS / TCP 443 / TLS 三段分开打印，出问题时用户能直接看出
             *    断在哪一段，不用再猜。
             *
             * 失败不阻断初始化：离线服不依赖外网，把这些当警告写进日志即可。
             */
            val probe = runCatching { termux.probeBdsNetwork(null) }.getOrElse { e ->
                listOf("网络自检未执行：${e.message}")
            }
            probe.forEach { log(it) }
            val caRepaired = probe.any { it.startsWith("CA 修复: 已从系统根证书补入") }
            val tlsBroken = probe.any { it.startsWith("TLS 失败") }
            val tcpBroken = probe.any { it.startsWith("TCP 失败") }
            when {
                caRepaired -> log("已补齐容器 CA 根证书：在线模式（正版验证）需要它才能连上微软服务")
                tlsBroken -> log("注意：TLS 握手被中断，在线模式仍会失败，需要让代理接管 TCP")
                tcpBroken -> log("注意：部分 443 连接失败，在线模式可能连不上微软服务")
                else -> log("外网链路正常（在线模式可用）")
            }

            readyMarker(prefix).parentFile?.mkdirs()
            readyMarker(prefix).writeText(
                "${profile.distro.dirName}\n${BdsRuntime.installedBox64Version(prefix).orEmpty()}\n"
            )
            _status.update {
                it.copy(
                    installing = false,
                    installed = true,
                    stage = null,
                    percent = 100,
                    source = null,
                    detail = null,
                    error = null,
                    rawDetail = null,
                    // 卡片上的「box64 · 版本」直接取磁盘上的实际值，不依赖中间状态
                    box64Version = BdsRuntime.installedBox64Version(prefix) ?: it.box64Version
                )
            }
            Log.i(TAG, "BDS 运行环境初始化完成：${profile.distro.dirName}")
        } catch (e: CancellationException) {
            cleanPartialArtifacts()
            _status.update {
                it.copy(installing = false, installed = false, stage = null, percent = 0)
            }
            throw e
        } catch (e: Exception) {
            fail(BdsEnvError.Unknown, e.message ?: e.javaClass.simpleName)
        } catch (e: Throwable) {
            // Error（NoSuchFieldError / OutOfMemoryError / StackOverflowError…）也必须
            // 落成一条可见的失败状态：否则协程直接死掉，界面永远停在「正在初始化」。
            fail(BdsEnvError.Unknown, e.message ?: e.javaClass.simpleName, e.toString())
        } finally {
            running.set(null)
        }
    }

    /**
     * 下载并安装 box64。
     *
     * 索引与 .deb 各自走多源：索引小（10KB），换了源也只是再问一次；
     * .deb 有 10MB，每个源都按索引里给的 SHA-256 校验 —— 三个入口是同一个仓库，
     * 哈希一致是它们「内容相同」的唯一硬证据。
     *
     * @return 是否成功（失败时已把状态写成 Failed）
     */
    private suspend fun installBox64(): Boolean {
        emitStage(BdsEnvStage.Box64, 0)

        // ① 内置 box64：随 APK 分发的 aarch64 可执行文件，复制即用。
        //    这一条把「网络 + 索引解析 + zstd 解包」三重依赖全绕开了，
        //    也是 Android 14+ 上唯一可靠的路（系统删掉了 zstd 库依赖的 Unsafe 字段）。
        log("正在安装内置 box64（无需下载）")
        if (BdsRuntime.installBundledBox64(context, prefix)) {
            _status.update {
                it.copy(
                    source = "内置",
                    detail = BdsRuntime.BUNDLED_VERSION,
                    box64Version = BdsRuntime.BUNDLED_VERSION
                )
            }
            emitStage(BdsEnvStage.Box64, 100)
            log(
                "内置 box64 ${BdsRuntime.BUNDLED_VERSION} 与 " +
                    "${BdsRuntime.REQUIRED_X86_LIBS.size} 个 x86_64 运行库已就位"
            )
            return true
        }
        log("内置 box64 不可用，改为从网络获取")

        val mirrors = BdsSources.box64Deb

        // ① 索引
        log("正在获取 box64 安装索引（${mirrors.size} 个来源，单源超时 ${MultiSourceDownload.TEXT_TIMEOUT_MS / 1000} 秒）")
        val indexSources = mirrors.map { DownloadSource(it.label, BdsSources.indexUrl(it)) }
        val indexText: String
        try {
            val (text, _) = MultiSourceDownload.fetchText(
                sources = indexSources,
                tempDir = termux.installer.tmpDir,
                onAttempt = { attempt ->
                    recordAttempt("索引 · ${attempt.label}", attempt.ok, attempt.reason)
                },
                validate = { text ->
                    if (text.contains("Package:") && text.contains("Filename:")) null
                    else "不是 box64 的 apt 索引"
                }
            )
            indexText = text
        } catch (e: MultiSourceDownload.AllSourcesFailed) {
            fail(BdsEnvError.Download, "所有 box64 索引源都不可用", e.message)
            return false
        }

        val ref = BdsRuntime.parseLatestDeb(indexText)
        if (ref == null) {
            fail(BdsEnvError.Corrupt, "box64 索引里没有可用的 arm64 包（接口内容可能已变更）")
            return false
        }
        _status.update { it.copy(detail = ref.version) }

        // ② .deb
        log("正在下载 box64 ${ref.version}（${ref.fileName}，单源超时 ${MultiSourceDownload.BINARY_TIMEOUT_MS / 1000} 秒）")
        val deb = File(termux.installer.tmpDir, ref.fileName)
        val debSources = mirrors.map { DownloadSource(it.label, BdsSources.debUrl(it, ref.fileName)) }
        try {
            val (used, _) = MultiSourceDownload.download(
                sources = debSources,
                target = deb,
                onAttempt = { attempt ->
                    recordAttempt("box64 · ${attempt.label}", attempt.ok, attempt.reason)
                },
                onProgress = { done, total, _ ->
                    if (total > 0) emitStage(BdsEnvStage.Box64, 10 + (done * 100 / total).toInt() * 70 / 100)
                },
                validate = { file -> validateDeb(file, ref.sha256) }
            )
            _status.update { it.copy(source = used.label) }
        } catch (e: MultiSourceDownload.AllSourcesFailed) {
            fail(BdsEnvError.Download, "所有 box64 下载源都不可用", e.message)
            return false
        }

        // ③ 解包 + 架构自证
        emitStage(BdsEnvStage.Box64, 85)
        val binary = BdsRuntime.box64Binary(prefix)
        try {
            // 这里必须接 Throwable：解包链路会抛 NoSuchFieldError 这类 Error
            // （zstd 在新版 Android 上就是如此），只 catch Exception 会直接闪退。
            val unpacked = try {
                BdsRuntime.unpackBox64Deb(
                    deb = deb,
                    targetBinary = binary,
                    keepConfig = BdsRuntime.box64rcFile(prefix)
                )
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                binary.delete()
                fail(BdsEnvError.Unpack, e.message ?: "${e.javaClass.simpleName}：安装包无法解压", e.toString())
                return false
            }
            if (!unpacked) {
                binary.delete()
                fail(BdsEnvError.Unpack, "box64 安装包里没有 usr/local/bin/box64")
                return false
            }
            val arch = BdsSupport.elfArch(binary)
            if (arch != BdsSupport.ElfArch.AARCH64) {
                // 拿到别的架构说明源换了产物，装上只会 Exec format error
                binary.delete()
                fail(BdsEnvError.Corrupt, "下载到的 box64 是 ${arch?.displayName ?: "未知"} 构建，本机无法执行")
                return false
            }
            BdsRuntime.box64VersionFile(prefix).writeText("${ref.version}\n")
            emitStage(BdsEnvStage.Box64, 100)
            return true
        } finally {
            deb.delete()
        }
    }

    /**
     * .deb 的内容校验。
     *
     * 顺序有意为之：**先看长度与哈希**，再谈能不能解开。
     * 镜像返回 HTML 错误页时长度只有几 KB，哈希对不上则说明传输被截断。
     */
    private fun validateDeb(file: File, expectedSha: String?): String? {
        if (!file.isFile || file.length() < 1024) {
            return "文件过小（${file.length()} 字节），可能是错误页"
        }
        val header = ByteArray(8)
        val read = runCatching {
            file.inputStream().use { it.read(header) }
        }.getOrDefault(0)
        if (read != 8 || !header.contentEquals("!<arch>\n".toByteArray())) {
            return "不是 deb（ar）归档"
        }
        if (expectedSha != null) {
            val actual = runCatching { MultiSourceDownload.sha256(file) }.getOrNull()
                ?: return "无法计算 SHA-256"
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                return "SHA-256 不匹配（下载不完整或镜像文件已损坏）"
            }
        }
        return null
    }

    // ── 状态工具 ─────────────────────────────────────────────────

    /**
     * 把进度也写进控制台日志。
     *
     * 为什么不能只更新卡片状态：初始化要跑几分钟，用户往往盯着「控制台」那一页看
     * （`[proot] ...` 这些行的确在那里滚动），而卡片上的进度条不在视野里。
     * 之前只有 proot 自己会往控制台写，换到 box64 阶段就彻底安静了 ——
     * 现象就是「卡在 [proot] 正在部署 那一行不动」。
     */
    private fun log(message: String) = runCatching { termux.emitLog("[bds] $message") }

    /** 上一次写进控制台的阶段与百分比档位（每 10% 一条，避免刷屏） */
    private var loggedStage: BdsEnvStage? = null
    private var loggedBucket: Int = -1

    /** 本阶段已到达过的最大百分比：换源会从 0 重新报进度，不夹住就会出现 80%→63% 这种倒退 */
    private var stageFloor: Int = -1

    /** 把一个阶段内的局部进度（0..100）折算成整体进度 */
    private fun emitStage(stage: BdsEnvStage, localPercent: Int) {
        val range = stage.range
        val clamped = localPercent.coerceIn(0, 100)
        val computed = range.first + (range.last - range.first) * clamped / 100

        if (stage != loggedStage) {
            loggedStage = stage
            loggedBucket = -1
            stageFloor = -1
            log("阶段：${stageLogLabel(stage)}")
        }
        // 换源时同一个阶段会从 0 重新报进度：对外必须是单调不减的，
        // 否则用户看到的就是「进度条往回跳」，很像卡死前的乱象。
        val overall = maxOf(computed, stageFloor)
        stageFloor = overall

        val bucket = overall / 10
        if (bucket != loggedBucket) {
            loggedBucket = bucket
            log("进度 $overall%${stageDetail(stage)}")
        }

        _status.update {
            it.copy(
                installing = true,
                installed = false,
                stage = stage,
                percent = overall.coerceIn(0, 100)
            )
        }
    }

    private fun stageLogLabel(stage: BdsEnvStage): String = when (stage) {
        BdsEnvStage.Container -> "准备 PRoot 容器（提供 glibc）"
        BdsEnvStage.Box64 -> "下载并安装 box64"
        BdsEnvStage.Verify -> "容器内实测 box64"
    }

    private fun stageDetail(stage: BdsEnvStage): String = when (stage) {
        BdsEnvStage.Container -> "（PRoot 容器）"
        BdsEnvStage.Box64 -> "（box64）"
        BdsEnvStage.Verify -> "（环境自检）"
    }

    private fun recordAttempt(label: String, ok: Boolean, reason: String?) {
        val line = "$label: " + if (ok) "成功" else (reason ?: "失败")
        log("下载源 $line")
        _status.update { it.copy(attempts = (it.attempts + line).takeLast(8)) }
    }

    private fun fail(error: BdsEnvError, message: String, raw: String? = null) {
        _status.update {
            it.copy(
                installing = false,
                installed = false,
                stage = null,
                error = error,
                detail = message,
                rawDetail = raw ?: it.rawDetail
            )
        }
    }

    /** 清掉半成品：只删 BDS 自己的东西，不碰容器与其他服务器 */
    private fun cleanPartialArtifacts() {
        runCatching { BdsRuntime.box64Binary(prefix).delete() }
        runCatching { BdsRuntime.box64VersionFile(prefix).delete() }
        runCatching { readyMarker(prefix).delete() }
    }

    companion object {
        private const val TAG = "BdsEnvironment"

        fun readyMarker(prefix: File): File = File(BdsRuntime.rootDir(prefix), "runtime.ready")

        /**
         * 环境是否已就绪（纯磁盘判定，不需要管理器实例）。
         *
         * 启动链路用它做硬闸门：未就绪就不启动，也不在启动过程中偷偷装。
         */
        fun isReady(prefix: File): Boolean {
            if (!BdsRuntime.box64Binary(prefix).isFile) return false
            // x86_64 运行库也是必需件：缺了它们 bedrock_server 会以
            // `Error loading needed libs` 退出，与其让用户在启动时猜，
            // 不如在这里就判成「未初始化」，指向重新初始化。
            if (!BdsRuntime.hasX86Libs(prefix)) return false
            val marker = readyMarker(prefix)
            if (!marker.isFile) return false
            // marker 首行是当初落地用的容器目录名。BDS 容器从「跟随用户选择的
            // 发行版」改成固定的 [BdsContainerDistro] 之后，老环境（多半是 focal）
            // 里的 box64 会因为 glibc 太旧而连 main 都进不去 ——
            // 所以容器换了就必须重装，不能让「就绪」这个标记继续骗人。
            val recorded = runCatching { marker.readLines().firstOrNull()?.trim() }.getOrNull()
            return recorded == BdsContainerDistro.dirName
        }
    }
}
