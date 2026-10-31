package com.mineserve.mobile.runtime

import android.content.Context
import android.util.Log
import android.system.Os
import com.mineserve.mobile.runtime.NativeLibraryBundler.PACKAGED_LIBRARIES
import com.mineserve.mobile.data.InstallStep
import com.mineserve.mobile.data.MultiSourceDownload
import com.mineserve.mobile.data.UbuntuImageSources
import com.mineserve.mobile.data.JavaRuntime
import com.mineserve.mobile.data.JavaVendor
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.JvmFlavor
import com.mineserve.mobile.data.PhpVersion
import com.mineserve.mobile.data.AptMirror
import com.mineserve.mobile.data.DownloadMirror
import com.mineserve.mobile.data.LegacyJava8Distro
import com.mineserve.mobile.data.BdsContainerProfile
import com.mineserve.mobile.data.BdsContainerDistro
import com.mineserve.mobile.data.ProotDistro
import com.mineserve.mobile.data.ProotContainerProfile
import com.mineserve.mobile.data.isProotGuestRuntime
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.ServerStorage
import com.mineserve.mobile.data.StepState
import com.mineserve.mobile.data.StepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Termux 运行时（去 tmux 化，参考 MC-Minder 思路直接管理进程）：
 *  - bootstrap()：下载 + 解压 Termux bootstrap rootfs，安装 JDK/wget
 *  - startMc()：用 ProcessBuilder 直接启动 java 进程，stdout 推送到 consoleFlow
 *  - stopMc()：向 stdin 发送 stop 命令，超时后 destroy
 *  - sendCommand()：向 MC 进程 stdin 写入命令
 *  - isMcRunning()：检查 Process.isAlive
 *
 * 不再依赖 tmux，避免 tmux 未安装时整个应用不可用的问题。
 */
class TermuxRuntime(context: Context) {

    private val appContext = context.applicationContext
    internal val installer = BootstrapInstaller(context)

    /**
     * FCL 来源的 JDK（FoldCraftLauncher 血统的原生 JRE）安装器。
     *
     * 它与 [installer] 平级：装到 `filesDir/jvm-fcl/`，不在 Termux 前缀里，
     * 因而不受 apt/dpkg 与 `fixUsrBin` 的 jvm 目录替换影响（见 [FclJreInstaller]）。
     */
    internal val fclJre = FclJreInstaller(context)

    /**
     * 随 APK 打包的 Termux 共享库在设备上的可读路径（nativeLibraryDir）。
     *
     * 缺库会导致 Termux 侧二进制在动态链接阶段直接失败：
     *   F linker: CANNOT LINK EXECUTABLE ".../apt-get": library "libandroid-glob.so" not found
     * 一旦 apt-get 起不来，apt/dpkg/proot/Java 8 全部连锁失效。
     *
     * targetSdk = 28 < 29，系统会把 jniLibs 下的 .so 解压到 nativeLibraryDir，
     * 因此这里拿到的是真实可读文件路径（可直接 cp / 走 ELF 搜索路径），
     * 而不是 targetSdk ≥ 29 时那种仅供 dlopen 的不可读映射。
     *
     * 见 NativeLibraryBundler：目录不存在或文件缺失时为 null，调用方需容忍。
     */
    private val bundledLibDir: String? by lazy {
        NativeLibraryBundler.nativeLibraryDir(context)
    }

    private val executor = CommandExecutor(installer, bundledLibDir)

    /** 关闭堆指针标签的注入库文件名（随 APK 打包在 jniLibs 下）。 */
    private val HEAP_TAG_FIX_LIB = NativeLibraryBundler.HEAP_TAG_FIX_LIB

    /** 应用侧日志落盘队列上限：异常刷屏时丢弃多余条目，避免内存膨胀。 */
    private val APP_LOG_QUEUE_LIMIT = 4096

    /** 库缺失只提示一次，避免每次启动命令都刷日志。 */
    private val heapTagFixWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 「Termux 来源 + Java 8」这条历史组合对应的容器画像。
     *
     * 它不是一个特殊实体，就是普通的 focal + Java 8 —— 保留这个名字只是为了让
     * 调用点读起来能表达「这是那条遗留组合」，而不必到处写
     * `ProotContainerProfile(ProotDistro.Focal, JavaVersion.Java8)`。
     *
     * 发行版取自 [LegacyJava8Distro]（数据层），与配置归一 `normalizeProotRuntime()`
     * 用的是同一个常量：两处若各写一份 focal，将来改了一处就会出现
     * 「归一后配置指向 A，实际启动找 B」这种服务端起不来的错位。
     */
    private val legacyJava8Profile: ProotContainerProfile =
        ProotContainerProfile(LegacyJava8Distro, JavaVersion.Java8)

    /** 按 (发行版, Java 版本) 取得容器画像；组合不可安装时返回 null。 */
    fun prootProfileFor(distro: ProotDistro, version: JavaVersion): ProotContainerProfile? =
        distro.profileFor(version)

    /**
     * 启动时用的当前发行版选择。
     *
     * 由 [McApplication] / ViewModel 在启动服务端前写入，与 `McConfig.prootDistro`
     * 保持同步。之所以做成运行时的可变状态而不是每处都传参：
     * [startMc] 的签名已被多处调用（MCP、桌面组件、定时任务、自动重启），
     * 再加一个参数会让这些调用点全部改一遍，而它们本来就该跟随当前配置。
     */
    @Volatile
    var selectedProotDistro: ProotDistro = ProotDistro.Default

    /**
     * 当前选用的 Java 来源。
     *
     * 与 [selectedProotDistro] 一样，是「当前配置」在运行时的投影，
     * 供那些拿不到 `McConfig` 的路径（Forge 启动参数拼接、installer 分派）
     * 判断该走来宾还是宿主链路。
     */
    @Volatile
    var selectedProotVendor: JavaVendor = JavaVendor.Termux

    /**
     * 解析「本次启动要用的容器画像」。
     *
     * 用户选的发行版**不提供**所选 Java 版本时（例如发行版 focal、版本 Java 21），
     * 直接抛出带解释的错误，而不是静默回退到另一个发行版 ——
     * 静默回退会让「用户选的」和「实际跑的」变成两个东西，
     * 而用户完全无从察觉，正是最难排查的那类问题。
     */
    private fun awaitProotProfile(version: JavaVersion): ProotContainerProfile {
        val distro = selectedProotDistro
        return distro.profileFor(version) ?: run {
            val err = "${distro.displayName} 不提供 ${version.displayName}，" +
                "可选：${distro.availableJavaVersions.joinToString { it.displayName }}。" +
                "请在「依赖与环境管理」里改选发行版或 Java 版本。"
            emitLog("[startMc] $err")
            throw RuntimeException(err)
        }
    }

    /**
     * 从 (来源, 版本) 解析出容器画像，供 installer / 命令执行等**不经过 startMc** 的路径使用。
     *
     * 与 [awaitProotProfile] 的区别：本方法显式接收来源，因此
     * 「Termux + Java 8」这条历史组合会稳定解析到 focal，
     * 而不会受当前 [selectedProotDistro] 影响（用户切发行版不该改变 Java 8 的行为）。
     */
    fun resolveProotProfile(vendor: JavaVendor, version: JavaVersion): ProotContainerProfile =
        if (vendor == JavaVendor.Proot) awaitProotProfile(version) else legacyJava8Profile

    /** 某个容器画像的 rootfs 是否已经解压出可用的 shell。 */
    private fun containerRootfsReady(profile: ProotContainerProfile): Boolean =
        hasUsableUbuntuShell(profile.rootfsDir(installer.rootDir))

    /** 该画像的 rootfs 根目录。 */
    private fun containerRootfs(profile: ProotContainerProfile): File =
        profile.rootfsDir(installer.rootDir)

    /**
     * 指定容器画像里的 Java 是否**装好且可用**。
     *
     * 判定三件事同时成立：就绪标记存在、来宾 `java` 可执行、`libjvm.so` 存在。
     * 只看标记是不够的——rootfs 可能被清理、apt 事务可能中断在半途，
     * 而标记还在，于是界面显示「已安装」，点启动才发现根本找不到 java。
     *
     * 两个候选路径是必要的：Java 8 的 Ubuntu 包用的是老 layout（JRE 在 `jre/` 下），
     * 11 及以上是新 layout。少写一条就会让其中一半版本被误判成未安装。
     */
    fun isContainerJavaReady(profile: ProotContainerProfile): Boolean {
        val prefix = installer.rootDir
        val rootfs = containerRootfs(profile)
        if (!rootfs.isDirectory) return false
        if (!profile.readyMarker(prefix).isFile) return false
        val hasJava = profile.guestJavaBinaryCandidates
            .map { File(rootfs, it.removePrefix("/")) }
            .any { it.isFile }
        val hasJvmLibrary = profile.guestJvmLibraryCandidates
            .map { File(rootfs, it.removePrefix("/")) }
            .any { it.isFile }
        return hasJava && hasJvmLibrary
    }

    /** 已就绪的容器画像集合（供依赖管理页与诊断共用）。 */
    fun installedProotContainers(): Set<ProotContainerProfile> =
        ProotContainerProfile.all.filter { isContainerJavaReady(it) }.toSet()

    /** MC 服务器进程（null 表示未启动） */
    @Volatile
    private var mcProcess: Process? = null
    @Volatile private var mcPid: Int? = null
    /** Java PID identified for this launch; never rescan into an installer or older server. */
    @Volatile private var mcServerPid: Int? = null
    @Volatile private var mcServerDir: File? = null
    @Volatile private var lastMcLogFile: File? = null
    @Volatile private var lastMcLogStartOffset: Long = 0L
    private val mcProcessLock = Any()

    /**
     * 串行化「启动服务端」这件事。
     *
     * [startMc] / [startPhp] / [startMcCustom] 都是同样的三步：
     * 「读 mcProcess 判断是否已在跑 → spawn 进程 → [trackMcProcess] 写入」。
     * 这三步之间原先**没有任何互斥**，于是两个线程可以都读到 mcProcess == null：
     * 崩溃自动重启线程（McServerController 里那个 `Thread { Thread.sleep; runBlocking { launchMc } }`）
     * 和用户手动点「启动」撞在一起就会各起一个 JVM，同一个 world 被两个进程打开 ——
     * Paper/Spigot 报 `already locked`，最坏情况写坏 region 文件。
     *
     * 用一把独立的锁而不是 [mcProcessLock]：启动过程包含 spawn 进程，可能耗到秒级，
     * 不该让 [clearMcProcessIfCurrent] 这种短操作陪着等。
     * 可重入，所以 [startMc] 内部再调 [startMcInProot] 不会自锁。
     */
    private val mcLaunchLock = java.util.concurrent.locks.ReentrantLock()

    /** 持 [mcLaunchLock] 执行 [block]；供各 start* 入口做启动串行化。 */
    private inline fun <T> withLaunchLock(block: () -> T): T {
        mcLaunchLock.lock()
        try {
            return block()
        } finally {
            mcLaunchLock.unlock()
        }
    }

    init {
        // 把打包的 libandroid-glob.so 落到 Termux 库目录，必须在任何命令执行前完成。
        // 这里只做落盘，具体路径由 repairProotLibraries() 统一汇报。
        provisionBundledLibraries()
    }

    /** MC 进程的 stdin，用于发送命令 */
    @Volatile
    private var mcStdin: OutputStream? = null

    // 进程级 CPU 采样基线：只在服务器启动/重启后建立，避免把停机时间计入使用率。
    @Volatile private var cpuBaselinePid: Int? = null
    @Volatile private var cpuBaselineJiffies: Long = 0L
    @Volatile private var cpuBaselineAtElapsedMs: Long = 0L

    // 整机 CPU 采样基线（/proc/stat 首行）。与进程级基线无关：
    // 服务端未运行时也要有读数，所以它不跟随服务端启停重置。
    @Volatile private var deviceCpuBaseline: DeviceCpuMath.Sample? = null

    // cpufreq 频率比读数的短缓存：同一帧/同一秒内的重复调用直接复用，避免反复遍历 sysfs。
    @Volatile private var cachedFreqCpuPercent: Int? = null
    @Volatile private var cachedFreqCpuAtMs: Long = 0L

    val consoleFlow: SharedFlow<String> get() = executor.consoleFlow

    /**
     * 应用侧日志文件（`home/logs/mineserve.log`）。
     *
     * ## 为什么需要它
     * [emitLog] 原先只把日志推给 [consoleFlow]（纯内存），**从不落盘**。
     * 于是启动前那一大段关键诊断——Java 路径解析、依赖安装、库体检、
     * 以及拼好的完整启动命令——只存在于内存里。一旦服务端启动失败，
     * `logs/latest.log` 里只有「MC 服务端自己输出的内容」，必然是空的，
     * 崩溃报告因此拿不出任何证据（实测出现过「最近日志 共 0 行」）。
     *
     * 现在所有 [emitLog] 同时追加到这里，即便服务端零输出，
     * 也能看到 Minexa 自己走到了哪一步、启动命令长什么样。
     */
    private val appLogFile: File
        get() = File(installer.rootDir, "home/logs/mineserve.log")

    /**
     * 日志落盘队列。
     *
     * [emitLog] 可能被高频调用（stdout 逐行、安装进度），**不能同步做 IO**，
     * 否则会拖慢启动。这里用无界队列 + 单线程消费：
     *   - `trySend` 是非阻塞的，调用方零等待
     *   - 只有 WARN/ERROR 级或有明确诊断价值的行才入队，过滤掉噪声
     *   - 队列上限保护：超过 [APP_LOG_QUEUE_LIMIT] 时丢弃，避免异常刷屏把内存撑爆
     */
    private val appLogQueue = java.util.concurrent.LinkedBlockingQueue<String>(APP_LOG_QUEUE_LIMIT)
    @Volatile private var appLogWriterStarted = false
    private val appLogLock = Any()

    /** 需要落盘的日志特征（其余高频噪声不入队，避免日志膨胀） */
    private val APP_LOG_KEEP_MARKERS = listOf(
        "[startMc]", "[bootstrap]", "[java]", "[crash]", "[restart]", "[core]",
        "[repair]", "[jvm]", "[proot]", "错误", "失败", "警告", "异常"
    )

    private fun persistAppLog(line: String) {
        // 只留诊断价值高的行；其余（例如进度百分比）不入队
        if (APP_LOG_KEEP_MARKERS.none { line.contains(it) }) return
        startAppLogWriterIfNeeded()
        // offer 不阻塞：队列满时直接丢弃，绝不反压调用方
        appLogQueue.offer(line)
    }

    private fun startAppLogWriterIfNeeded() {
        if (appLogWriterStarted) return
        synchronized(appLogLock) {
            if (appLogWriterStarted) return
            appLogWriterStarted = true
            Thread({
                while (true) {
                    val line = try {
                        // 带超时的取，便于在无日志时也能定期 flush
                        appLogQueue.poll(1, TimeUnit.SECONDS)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                    try {
                        if (line != null) {
                            val file = appLogFile
                            file.parentFile?.mkdirs()
                            // 单条即 flush：崩溃场景下缓冲区里的内容很容易随进程消失
                            file.appendText(line + "\n")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "写入 mineserve.log 失败: ${e.message}")
                    }
                }
            }, "mineserve-app-log").apply { isDaemon = true }.start()
        }
    }

    /**
     * 读取应用侧日志的尾部若干行，供崩溃报告补充「Minexa 视角」的上下文。
     * 文件不存在或为空时返回空列表（调用方据此区分「没有」和「空」）。
     */
    fun readAppLogTail(maxLines: Int = 400): List<String> {
        val file = appLogFile
        if (!file.isFile) return emptyList()
        return runCatching {
            file.useLines { lines -> lines.toList().takeLast(maxLines) }
        }.getOrDefault(emptyList())
    }

    /** 清空应用侧日志（避免无限增长）。 */
    fun clearAppLog() {
        runCatching { appLogFile.takeIf { it.isFile }?.writeText("") }
    }

    /** 设置日志回调，bootstrap 过程的日志会通过此回调输出 */
    fun setBootstrapLogCallback(cb: (String) -> Unit) {
        installer.onLog = cb
    }

    /** 向 consoleFlow 推送一条日志，并异步落盘到 mineserve.log */
    fun emitLog(line: String) {
        executor.emit(line)
        // 落盘是「尽力而为」：任何异常都不能影响主流程
        runCatching { persistAppLog(line) }
    }

    fun isReady(): Boolean = installer.isReady()

    /** 删除整个 Termux 运行环境；force=true 为强制彻底删除（不自动重新初始化） */
    suspend fun deleteBootstrap(force: Boolean = false) {
        stopMc()
        installer.deleteBootstrap(force)
    }

    /**
     * 多核心支持：按文件夹名获取对应核心的 jar 路径。
     *
     * 路径不再固定为 `<home>/servers/{dirName}/`：服务器可能位于新版统一存储目录
     * 或旧版私有目录，定位统一交给 [ServerStorage]（见其文档）。
     */
    fun serverJarFileFor(dirName: String): File =
        File(serverDirFor(dirName), "server.jar").apply { parentFile?.mkdirs() }

    /**
     * 多核心支持：按文件夹名获取对应核心的工作目录。
     *
     * `dirName` 不含位置信息，这里根据 [ServerStorage] 的解析结果落到正确根目录。
     *
     * **纯解析，绝不创建目录**。历史实现带着 `.apply { mkdirs() }`，于是任何一次
     * 「读」都变成了「写」：读日志、读 properties、刷新桌面组件、解析当前核心……
     * 只要某处传进一个已不存在的 dirName，磁盘上就会凭空多出一个空文件夹。
     * 这正是「有时启动应用会多出一个空文件夹」的来源：启动链路里到处都在解析目录名。
     *
     * 需要创建目录的地方（下载 / 导入 / 还原 / 首次启动）显式调用 [ensureServerDir]。
     */
    fun serverDirFor(dirName: String): File =
        ServerStorage.dirFor(appContext, dirName)

    /** 显式创建并返回服务器目录（下载 / 导入 / 还原等「要往里写文件」的路径专用）。 */
    fun ensureServerDir(dirName: String): File =
        ServerStorage.dirFor(appContext, dirName).apply { if (!isDirectory) mkdirs() }

    /**
     * 读取当前服务端日志文件的最近内容，供终端页快速恢复历史输出。
     *
     * @param fromOffset 只返回该字节位置**之后**的内容。终端页「清除」之后用它，
     *   否则清空的行会在下次进页面时被磁盘日志原样补回来 —— 用户会认为清除没生效。
     *   `0` 表示从头读（默认，保持既有语义）。
     */
    suspend fun readRecentMcLog(
        dirName: String,
        maxLines: Int = 300,
        fromOffset: Long = 0L
    ): List<String> = withContext(Dispatchers.IO) {
        // 优先读服务端自己维护的 latest.log（Paper/Spigot/Forge 由 log4j 写，内容是权威的）；
        // 只有它不存在或为空时才回退到应用侧捕获的控制台输出——
        // 例如 PocketMine、或核心在写出第一行日志前就崩了的情况。
        val source = mcLogFileFor(dirName) ?: return@withContext emptyList()
        if (fromOffset <= 0L) {
            return@withContext source.useLines(Charsets.UTF_8) { lines ->
                lines.toList().takeLast(maxLines)
            }
        }
        val length = source.length()
        // 文件比 offset 短：说明 latest.log 已被新一轮运行覆盖（或用户清过目录）。
        // 此时 offset 毫无意义，按「从头读」处理，否则会得到一整页空白。
        if (length <= fromOffset) return@withContext emptyList()
        // 只回读尾部窗口：增量可能积累到几十 MB，而界面只要最后 maxLines 行。
        val start = maxOf(fromOffset, length - LOG_TAIL_WINDOW_BYTES)
        val bytes = java.io.RandomAccessFile(source, "r").use { raf ->
            raf.seek(start)
            val size = (length - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            ByteArray(size).also { raf.readFully(it) }
        }
        val lines = String(bytes, Charsets.UTF_8).split('\n')
        // start > fromOffset 说明起点被尾部窗口截断，落在某一行中间 ——
        // 首行是残行，必须丢掉，否则终端会冒出一行半截日志。
        val body = if (start > fromOffset) lines.drop(1) else lines
        body.takeLast(maxLines).filter { it.isNotEmpty() }
    }

    /**
     * 当前可用于读取服务端输出的文件：优先服务端自己维护的 latest.log
     * （Paper/Spigot/Forge 由 log4j 写，内容权威），没有时回退到应用侧捕获的控制台输出。
     *
     * 调用方（含 MCP 的 get_console_logs / wait_for_log）**必须走这里**再去 tail：
     * 以前它们硬编码 logs/latest.log，而 PocketMine 根本不生成这个文件 ——
     * 于是这两个工具在 PocketMine 上恒返回空/永远超时（1.2.7 里是有数据的，属行为回归）。
     *
     * @return null 表示还没有任何输出可读
     */
    fun mcLogFileFor(dirName: String): File? {
        val serverDir = serverDirFor(dirName)
        File(serverDir, "logs/latest.log").takeIf { it.isFile && it.length() > 0L }?.let { return it }
        return File(serverDir, MC_CONSOLE_LOG_NAME).takeIf { it.isFile && it.length() > 0L }
    }

    /** 读取最近一次服务端运行从启动到退出产生的完整日志。 */
    suspend fun readLastMcRunLog(maxBytes: Long = 4L * 1024L * 1024L): String = withContext(Dispatchers.IO) {
        val file = lastMcLogFile ?: return@withContext ""
        if (!file.isFile) return@withContext ""
        val start = lastMcLogStartOffset.coerceAtMost(file.length())
        val end = file.length()
        val readStart = if (end - start > maxBytes) end - maxBytes else start
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(readStart)
            val length = (end - readStart).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val bytes = ByteArray(length)
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }

    /**
     * 准备**应用侧**的控制台捕获文件，并记住本次运行的起始偏移。
     *
     * 为什么不再直接写服务端的 `logs/latest.log`：那个文件是 Paper/Spigot/Forge 由 log4j
     * 自己维护的，应用再往里 append 一遍同样的 stdout，会让**每一行出现两次**、日志体积翻倍，
     * 还会让 [readRecentMcLog] 的 overlap 去重失效（终端页看到重复行）。
     * 现在两个文件各写各的：服务端写它的，应用写 [MC_CONSOLE_LOG_NAME]。
     *
     * `lastMcLog*` 指向这份捕获文件，所以 [readLastMcRunLog] 拿到的仍是
     * 「本次运行从启动到退出」的完整输出，语义没变。
     */
    private fun prepareMcLogFile(serverDir: File): File {
        val file = File(serverDir, MC_CONSOLE_LOG_NAME)
        file.parentFile?.mkdirs()
        file.createNewFile()
        lastMcLogFile = file
        lastMcLogStartOffset = file.length()
        return file
    }

    /**
     * 多核心基础目录 —— **新版统一存储目录**（`/storage/emulated/0/Minexa/Minecraftserver/`）。
     *
     * 新下载/导入的服务器一律落在这里；旧版私有目录只通过
     * [ServerStorage.legacyRoot] 读取，不再作为写入目标。
     */
    val serversDir: File get() = ServerStorage.serversDir(appContext)

    /**
     * 确保 java 命令可用（wrapper 脚本方案）。
     * 在 startMc 之前主动调用，避免每次启动都找不到 java。
     * 返回 java 命令路径，找不到返回 null。
     *
     * 关键：如果 $PREFIX/bin/java 是旧的 cp 复制的二进制（非 wrapper 脚本），
     * 需要删除并用 wrapper 脚本替换，否则会因 libjli.so 缺失而启动失败。
     */
    fun ensureJavaReady(): String? {
        val prefix = installer.rootDir.absolutePath
        val binJava = File(prefix, "bin/java")

        // 1. 检查是否已存在 wrapper 脚本
        if (binJava.exists() && binJava.canExecute()) {
            val isWrapper = try {
                FileInputStream(binJava).use { fis ->
                    val header = ByteArray(14)
                    val read = fis.read(header)
                    read >= 14 && String(header, Charsets.US_ASCII).startsWith("#!/system/bin/sh")
                }
            } catch (e: Exception) { false }
            if (isWrapper) {
                return binJava.absolutePath
            }
            // 旧版本 cp 复制的二进制，需替换为 wrapper 脚本
            Log.i(TAG, "ensureJavaReady: $prefix/bin/java is old binary, replacing with wrapper")
            binJava.delete()
        }

        // 2. 创建 wrapper 脚本
        fixJavaSymlinks()

        // 3. 修复后再次检查
        if (binJava.exists() && binJava.canExecute()) {
            return binJava.absolutePath
        }

        // 4. 探测实际路径
        val resolved = resolveJavaPath(prefix)
        return if (resolved != "java") resolved else null
    }

    /**
     * 指定来源下某版本是否已装好。
     *
     * 两套 JDK 的落点完全不同：Termux 侧在 `$PREFIX/lib/jvm/`（Java 8 还额外分到
     * proot 来宾根文件系统），FCL 侧在 `filesDir/jvm-fcl/`。判定「装没装」也必须
     * 按来源分开查，否则会出现「Termux 装了 Java 17 于是 FCL 的 Java 17 也显示已安装」
     * 这种一眼看不出问题、点下去才报错的错位。
     */
    fun isJavaInstalled(
        version: JavaVersion,
        vendor: JavaVendor = JavaVendor.Termux
    ): Boolean = when (vendor) {
        JavaVendor.Fcl -> fclJre.isInstalled(version)
        JavaVendor.Termux -> if (version == JavaVersion.Java8) {
            // 历史遗留：Termux 来源的 Java 8 一直跑在 focal 容器里。
            java8UbuntuReady()
        } else {
            javaCandidates(version).any {
                File(it, "bin/java").exists() && File(it, "bin/java").canExecute()
            }
        }
        // Proot 来源：**只要某个发行版里装好了这个版本**就算已安装。
        //
        // 这里刻意不接收 distro 参数：本方法回答的是「这个 (来源, 版本) 装没装」，
        // 而界面上的分组列表也按 (来源, 版本) 展示 —— 同一个 Java 17 装在
        // focal 还是 jammy 里，对用户而言都是「PRoot 的 Java 17 已装」。
        // 「当前选中的发行版里是否装了这个版本」是另一个问题，
        // 由 [isJavaReadyForDistro] 回答，启动校验走那条。
        JavaVendor.Proot -> ProotDistro.entries.any { distro ->
            distro.profileFor(version)?.let { isContainerJavaReady(it) } == true
        }
    }

    /**
     * 所选发行版里这个 Java 版本是否真的可用。
     *
     * 与 [isJavaInstalled] 的区别很关键：用户可能把 Java 17 装在 focal 里，
     * 却把当前发行版选成了 jammy —— 此时「已安装」为真，但**这次启动会失败**。
     * 启动前的校验必须用本方法，否则会出现「界面显示已安装，点启动报找不到 java」。
     *
     * @return 组合非法（该发行版不提供此版本）或未安装时为 false
     */
    fun isJavaReadyForDistro(version: JavaVersion, distro: ProotDistro): Boolean =
        distro.profileFor(version)?.let { isContainerJavaReady(it) } ?: false

    fun installedJavaVersions(): Set<JavaVersion> =
        JavaVersion.values().filter { isJavaInstalled(it, JavaVendor.Termux) }.toSet()

    /** 已安装的运行环境（来源 + 版本），供「依赖与环境管理」两处分组列表共用。 */
    fun installedJavaRuntimes(): Set<JavaRuntime> =
        JavaRuntime.all.filter { isJavaInstalled(it.version, it.vendor) }.toSet()

    /**
     * 删除指定运行环境的文件。
     *
     * 各来源的落点完全不同，且**只删自己那一份**，不碰其他来源：
     * - FCL：`filesDir/jvm-fcl/` 下的一棵子树
     * - Proot：进容器 `apt purge` 掉**该版本**的 JDK，保留 rootfs 与其他版本
     * - Termux Java 8：历史遗留的 focal 容器（与 Proot.Focal 是同一份文件）
     * - Termux 其他版本：`$PREFIX/lib/jvm/<dir>`
     *
     * 返回是否有文件被删除。
     *
     * ## 为什么是 suspend
     *
     * PRoot 来源的删除要进容器跑 apt（秒级到十秒级），必须挂起执行。
     * 其余来源都是本地文件操作，走同一条挂起签名即可 ——
     * 为它们在调用侧分叉出两条路径，只会让「忘了加 running 守卫」这类漏洞更容易出现。
     */
    suspend fun deleteJava(version: JavaVersion, vendor: JavaVendor = JavaVendor.Termux): Boolean {
        if (isMcRunning()) return false
        if (vendor == JavaVendor.Fcl) return fclJre.delete(version)

        // Proot 来源：**按版本**卸载，而不是删掉整个 rootfs。
        //
        // 一个 rootfs 里可以装多个 JDK（focal 有 8/11/17）。早先这里直接删 rootfs，
        // 用户在界面上只点了「卸载 Java 17」，实际却把 Java 8 和 11 一起清掉了 ——
        // 那是「删容器」的语义，不是「卸载这个运行环境」。现在改为 apt purge
        // 单个包，容器与其余版本都保留。
        if (vendor == JavaVendor.Proot) {
            val profile = awaitProotProfile(version)
            val ok = uninstallProotJava(profile)
            // 只有容器内确认卸载干净了，才重新评估「这个发行版还剩哪些版本」。
            // 若这是最后一个版本，rootfs 已无 JDK，标记留给用户主动删容器时清。
            return ok
        }

        // Termux 来源的 Java 8 与 Proot 来源的 focal 是**同一个** focal 容器，
        // 因此删除逻辑也复用同一个 helper，只是入口来源不同。
        // 单独写一份「删 rootfs + 删标记」很容易漏掉其中一项，
        // 留下「标记还在但 rootfs 没了」这种自相矛盾的状态。
        if (version == JavaVersion.Java8) {
            val removed = deleteProotContainer(ProotDistro.Focal)
            if (removed && installedJavaVersions().isEmpty()) {
                File(installer.rootDir, "bin/java").delete()
            }
            return removed
        }

        val roots: List<File> = javaCandidates(version).map(::File)
        var deleted = false
        roots.forEach { root ->
            if (root.exists()) deleted = root.deleteRecursively() || deleted
        }
        // 被删版本的 bin/java 包装器可能悬空：若无任何已安装版本则移除包装器
        if (deleted && installedJavaVersions().isEmpty()) {
            File(installer.rootDir, "bin/java").delete()
        }
        return deleted
    }

    /**
     * 在容器内用 apt 卸载**单个** JDK，保留 rootfs 与其他 Java 版本。
     *
     * ## 为什么需要它
     *
     * 一个发行版 rootfs 可以装多个 JDK（focal 支持 8/11/17）。早先「删除 Java 17」
     * 的实现是直接把整个 rootfs 删掉 —— 用户的 Java 8、11 会**一起消失**，
     * 而界面上他只点了 Java 17 那一个。现在改为进容器 apt remove，
     * 只清掉目标版本。
     *
     * ## 用 `apt-get purge` 而不是 `autoremove`
     *
     * `purge` 会连配置文件一起删；`autoremove` 则会连带清掉「不再被依赖」的包，
     * 在多版本共存时可能把别的 JDK 依赖的 `ca-certificates`、`fontconfig` 之类
     * 一并拔掉，导致剩余版本在下次启动时才出问题。因此只 purge 目标包本身，
     * 不做 autoremove。
     *
     * ## 为什么要 `-f install` 与 `dpkg --configure -a`
     *
     * 容器内的 apt 事务可能因为网络中断而停在半途（装机时常见）。
     * 直接 purge 会在 dpkg 锁或未配置包上失败，先跑一遍恢复能让卸载更稳。
     */
    private fun containerJavaUninstallCommand(profile: ProotContainerProfile): String {
        val pkg = profile.aptPackage
        val packages = profile.relatedAptPackages.joinToString(" ")
        // 校验用的候选路径必须非空。空列表会展开成 `if ; then`（空命令恒真），
        // 于是「卸载后仍检测到 java」的判定会永远成立、每次卸载都报失败。
        // 候选来自 ProotContainerProfile 的常量列表，正常不会为空；这里拦一道，
        // 让将来改动该列表的人立刻看到原因，而不是面对一个诡异的必失败。
        val binaryChecks = profile.guestJavaBinaryCandidates
            .joinToString(" || ") { "test -x $it" }
        check(binaryChecks.isNotBlank()) {
            "guestJavaBinaryCandidates 为空，卸载校验会退化成恒真；请检查 ProotContainerProfile"
        }
        return """
        echo "[proot] 正在恢复容器内未完成的软件包事务..."
        if ! dpkg --configure -a || ! apt-get -o DPkg::Lock::Timeout=60 -f install -y; then
          echo "[proot] dpkg 恢复失败，未继续卸载；请稍后重试"
          exit 1
        fi
        echo "[proot] 正在卸载 $packages"
        # 必须连 headless / jre-headless 一起 purge：只清 meta 包时它们仍是
        # installed，且与 JDK 共享同一个 /usr/lib/jvm 目录 —— 接着 rm 这个目录
        # 就会留下「dpkg 说装了、磁盘上没文件」的残状态，之后再装永远报「已是最新」。
        # 刻意不做 autoremove：多版本共存时它可能拔掉其他 JDK 仍需要的公共依赖。
        apt-get -o DPkg::Lock::Timeout=60 purge -y $packages || exit 1
        # purge 之后才清目录：apt 不跟踪的残留（手动塞进去的东西）顺手带走。
        rm -rf '${profile.javaHomeInGuest}'
        # 校验：所有已知 layout 下都不能再有可执行的 java，否则这次卸载算失败
        # （Java 8 的包把 JRE 放在 jre/bin/java，只查新 layout 会漏判）
        if $binaryChecks; then
          echo "[proot] $pkg 卸载后仍检测到 java，卸载未生效"
          exit 1
        fi
        exit 0
        """.trimIndent()
    }

    /**
     * 卸载容器内的单个 JDK（保留 rootfs 与其他 Java 版本）。
     *
     * @return 卸载是否成功（`true` 也包含「本来就没装」的情况）
     */
    suspend fun uninstallProotJava(profile: ProotContainerProfile): Boolean = withContext(Dispatchers.IO) {
        if (isMcRunning()) {
            emitLog("[proot] 服务端正在运行，无法卸载 ${profile.displayName}")
            return@withContext false
        }
        if (!isContainerJavaReady(profile)) {
            // 本来就没装：视为已达成目标，不必进容器跑一次 apt。
            emitLog("[proot] ${profile.displayName} 未安装，无需卸载")
            return@withContext true
        }
        if (!prepareUbuntuRuntime(profile)) {
            emitLog("[proot] ${profile.displayName} 容器无法启动，请先修复运行环境")
            return@withContext false
        }
        emitLog("[proot] 正在 ${profile.displayName} 内卸载 ${profile.aptPackage}")
        val code = runUbuntu(
            containerJavaUninstallCommand(profile),
            timeoutMs = 300_000,
            profile = profile
        )
        if (code != 0) {
            // 卸载失败时**不删除任何宿主文件**：容器内状态未知，
            // 贸然删标记会让用户看到「未安装」但磁盘上还留着一份完整 JDK。
            emitLog("[proot] ${profile.displayName} 卸载失败（exit=$code），容器状态未改动")
            return@withContext false
        }
        emitLog("[proot] ${profile.displayName} 已卸载")
        true
    }

    /**
     * 删除整个 PRoot 容器（不论里面装了哪些 Java 版本）。
     *
     * 与 [deleteJava] 的 Proot 分支是同一件事，但入口不同：容器页给出的是
     * 「删除这个容器」，而依赖页给的是「卸载这个运行环境」。两者都必须
     * 确认服务端没在跑 —— 正在用的 rootfs 被删掉，服务端会以奇怪的方式崩。
     *
     * @return 是否有文件被删除
     */
    fun deleteProotContainer(distro: ProotDistro): Boolean {
        if (isMcRunning()) return false
        val template = distro.availableJavaVersions.firstOrNull() ?: return false
        val profile = ProotContainerProfile(distro, template)
        var removed = false
        val rootfs = profile.rootfsDir(installer.rootDir)
        if (rootfs.exists()) removed = rootfs.deleteRecursively() || removed
        // 标记文件的存在与否不影响「删掉了没有」的语义，单独清掉即可
        profile.readyMarker(installer.rootDir).delete()
        // PRoot glue 文件（PROOT_TMP_DIR）也一并回收
        profile.prootTmpDir(installer.rootDir).deleteRecursively()
        // 该发行版的宿主 resolv.conf 与 rootfs 同生共死，否则会留下残骸
        profile.hostResolvConf(installer.rootDir).delete()
        return removed
    }

    /**
     * 容器 rootfs 占用的磁盘字节数（供「删除以回收空间」的提示使用）。
     *
     * ⚠️ 这是**重量级 IO**：`walkTopDown()` 要递归遍历整个 Ubuntu rootfs
     * （数万个文件），真机上足以造成明显掉帧。因此：
     * - 绝不能在 Composable 里直接调用（旧代码在 EnvManagerScreen 里这么做了，
     *   且 remember 的 key 含每次刷新都换新实例的 `installed`，
     *   结果进页面/切发行版都会重扫 rootfs → 卡顿）；
     * - 应通过 McViewModel 的异步入口在 IO 线程算完再回传。
     *
     * 保留本方法供后台线程与 MCP 工具同步调用。
     */
    fun prootContainerSizeBytes(distro: ProotDistro): Long {
        val template = distro.availableJavaVersions.firstOrNull() ?: return 0L
        val rootfs = ProotContainerProfile(distro, template).rootfsDir(installer.rootDir)
        if (!rootfs.isDirectory) return 0L
        return runCatching {
            rootfs.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    /**
     * 某个容器的分层状态，用于诊断页展示「到底卡在哪一层」。
     *
     * 容器链路有四层，每层都可能单独出问题，而用户看到的只有一句
     * 「已安装 / 未安装」。分层报告让「rootfs 没解压完」与
     * 「rootfs 好了但这个 Java 没装上」区分开 —— 前者要重新下载，
     * 后者只需重装一次 apt 包，代价差很多。
     */
    data class ProotContainerState(
        /** rootfs 目录存在 */
        val rootfsPresent: Boolean,
        /** rootfs 里有可用的 shell（解压完整） */
        val shellUsable: Boolean,
        /** 该发行版的就绪标记存在 */
        val readyMarkerPresent: Boolean,
        /** 该发行版下所有已装 Java 版本 */
        val installedVersions: List<JavaVersion>,
        /** rootfs 占用字节数 */
        val sizeBytes: Long
    )

    fun prootContainerState(distro: ProotDistro): ProotContainerState {
        val template = distro.availableJavaVersions.first()
        val profile = ProotContainerProfile(distro, template)
        val rootfs = profile.rootfsDir(installer.rootDir)
        return ProotContainerState(
            rootfsPresent = rootfs.isDirectory,
            shellUsable = hasUsableUbuntuShell(rootfs),
            readyMarkerPresent = profile.readyMarker(installer.rootDir).isFile,
            installedVersions = distro.availableJavaVersions.filter { isJavaReadyForDistro(it, distro) },
            sizeBytes = prootContainerSizeBytes(distro)
        )
    }

    // ── PocketMine-MP 专用 PHP 运行时 ─────────────────────────────
    //
    // PMMP 官方不发布 Linux ARM64 的 PHP 构建，首次启动时会下载社区 Android
    // 原生构建（ItzxDwi/AndroidPHP，aarch64，含 chunkutils2 / encoding /
    // leveldb / pmmpthread 等必需扩展）到共享目录，多个 PocketMine 服务器复用。
    //
    // 目录按版本区分（home/<PhpVersion.runtimeDirName>）。当前上游只发布一个
    // 版本，故只有一个目录；结构上预留多版本，无需改动调用方。

    /** 指定 PHP 版本的运行时目录：home/{runtimeDirName} */
    fun phpRuntimeDirFor(version: PhpVersion): File =
        File(installer.rootDir, "home/${version.runtimeDirName}")

    /** 指定 PHP 版本的可执行文件 */
    fun phpBinaryFor(version: PhpVersion): File = File(phpRuntimeDirFor(version), "php")

    /** 指定 PHP 版本是否已就绪 */
    fun isPhpInstalled(version: PhpVersion = PhpVersion.Default): Boolean =
        phpBinaryFor(version).let { it.isFile && it.canExecute() }

    /** 已安装的 PHP 版本集合（两处 UI 共用同一探测来源） */
    fun installedPhpVersions(): Set<PhpVersion> =
        PhpVersion.entries.filter { isPhpInstalled(it) }.toSet()

    /**
     * 下载并解压指定版本的 PHP 运行环境。
     *
     * 依次尝试多个 GitHub 镜像；成功后 chmod 755 并校验可执行位。
     * 返回是否安装成功，失败原因通过 [onLog] 输出。
     */
    fun installPhp(version: PhpVersion = PhpVersion.Default, onLog: (String) -> Unit = {}): Boolean {
        val target = phpBinaryFor(version)
        if (isPhpInstalled(version)) return true
        val runtimeDir = phpRuntimeDirFor(version).apply { mkdirs() }
        onLog("[php] 正在下载 ${version.displayName} 运行环境（Android 原生构建，约 10MB）...")
        val tarball = File(installer.rootDir, "tmp/php-${version.releaseTag}.tar.gz").apply { parentFile?.mkdirs() }
        val url = "https://github.com/ItzxDwi/AndroidPHP/releases/download/${version.releaseTag}/${version.tarballAsset}"
        // 只保留实测可用的反代（旧 mirror.ghproxy.com / github.moeyy.xyz 已失效，移除）；
        // 最后一项空串即 GitHub 直连兜底。
        val mirrors = listOf(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://cdn.gh-proxy.com/",
            "https://ghproxy.net/",
            ""
        ).map { it + url }
        // 期望摘要：上游用的是**滚动 tag**（`pm5-latest`），写死哈希会在上游发新版时直接失效、
        // 把正常更新误判成篡改。所以从 GitHub API 现取该 asset 的官方 digest——
        // api.github.com 与下面的镜像站是**不同主机**，镜像伪造不了它。
        val expectedSha = RemoteArtifact.githubAssetSha256(
            "ItzxDwi", "AndroidPHP", version.releaseTag, version.tarballAsset
        )
        if (expectedSha == null) {
            onLog("[php] 提示：拿不到官方校验值，本次只接受 GitHub 直连来源")
        }
        var downloaded = false
        for (mirror in mirrors) {
            if (!downloadFile(mirror, tarball)) {
                onLog("[php] 镜像下载失败，尝试下一个源...")
                continue
            }
            // 这几个来源全是第三方反代，对内容不作任何承诺。
            // 下载到的 tarball 会被直接解压、chmod 755、随后当作 PHP 解释器执行，
            // 所以"够大就放行"是绝对不能接受的判据。
            if (tarball.length() < 1_000_000L) {
                onLog("[php] 来源内容过小，已丢弃")
                tarball.delete()
                continue
            }
            if (expectedSha != null) {
                if (!RemoteArtifact.matches(tarball, expectedSha)) {
                    onLog("[php] 校验失败（内容与官方不符），已丢弃该来源")
                    tarball.delete()
                    continue
                }
            } else {
                // 拿不到官方摘要时，第三方反代的内容确实对不上账。但同网络下 github.com 直连
                // 本来就可能不通（之前全部拒绝会让 PHP 在国内彻底装不上，报错还是误导性的
                // 「请检查网络后重试」）。折中：反代来源必须额外通过**结构校验**——
                // 它是 gzip、且包内确实含一个 php 可执行文件；只看大小是不够的。
                // 伪造 byte-for-byte 相同结构的成本远高于改包的收益。
                if (RemoteArtifact.isDirectGitHubSource(mirror)) {
                    downloaded = true
                    break
                }
                val problem = phpTarballProblem(tarball)
                if (problem != null) {
                    onLog("[php] 第三方来源内容不符合预期（$problem），已丢弃")
                    tarball.delete()
                    continue
                }
                onLog("[php] 无官方校验值可用，已按包结构校验放行第三方来源")
                downloaded = true
                break
            }
            downloaded = true
            break
        }
        if (!downloaded) {
            tarball.delete()
            onLog("[php] ${version.displayName} 运行环境下载失败，请检查网络后重试")
            return false
        }
        try {
            extractTarGz(tarball, runtimeDir)
        } catch (e: Exception) {
            tarball.delete()
            onLog("[php] 解压失败: ${e.message}")
            return false
        }
        tarball.delete()
        execOnce("chmod", "-R", "755", runtimeDir.absolutePath)
        val ok = isPhpInstalled(version)
        onLog(
            if (ok) "[php] ${version.displayName} 运行环境就绪"
            else "[php] ${version.displayName} 运行环境不完整，请删除 ${version.runtimeDirName} 目录后重试"
        )
        return ok
    }

    /**
     * 第三方来源的 tarball 结构校验：返回 null 表示通过，否则返回问题描述。
     *
     * 只检查「它是我们期望形态的 PHP 运行环境」——是 gzip、里面有 php 可执行条目。
     * 这挡不住字节级伪造，但能挡住最常见的失败形态：反代返回 HTML 错误页、
     * 登录中转页、被截断的包（tar 提前结束）。
     */
    private fun phpTarballProblem(tarball: File): String? {
        return try {
            java.util.zip.GZIPInputStream(tarball.inputStream().buffered()).use { gzip ->
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzip).use { tar ->
                    var foundBinary = false
                    var entries = 0
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        entries++
                        if (entries > 4 && !foundBinary) return@use "包内前 $entries 个条目里没有 php 可执行文件"
                        if (!entry.isDirectory && (entry.name == "php" || entry.name.endsWith("/php"))) {
                            foundBinary = true
                            break
                        }
                    }
                    if (!foundBinary) "包内没有 php 可执行文件" else null
                }
            }
        } catch (e: Exception) {
            "无法作为 PHP 运行环境包解析（${e.message}）"
        }
    }

    /** 直接删除指定版本的 PHP 运行环境。返回是否有文件被删除。 */
    fun deletePhp(version: PhpVersion = PhpVersion.Default): Boolean {
        if (isMcRunning()) return false
        val dir = phpRuntimeDirFor(version)
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    /** 下载文件到指定路径（跟随重定向），返回是否成功且文件大于 1KB */
    private fun downloadFile(url: String, target: File): Boolean = runCatching {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Minexa/1.0 (Android)")
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        } finally {
            conn.disconnect()
        }
        target.length() > 1024
    }.getOrElse { false }

    /** 解压 tar.gz 到目标目录（防目录逃逸） */
    private fun extractTarGz(tarball: File, destDir: File) {
        java.util.zip.GZIPInputStream(tarball.inputStream().buffered()).use { gzip ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val out = File(destDir, entry.name)
                    if (!out.canonicalPath.startsWith(destDir.canonicalFile.path + File.separator) &&
                        out.canonicalPath != destDir.canonicalFile.path
                    ) continue
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { output -> tar.copyTo(output, 64 * 1024) }
                }
            }
        }
    }

    /** 直接删除依赖文件（wget/frpc/rclone/proot）。返回删除的组件数。 */
    fun deleteDependencies(): Int {        val prefix = installer.rootDir
        val targets = listOf(
            listOf("bin/wget", "usr/bin/wget", "data/data/com.termux/files/usr/bin/wget"),
            listOf("bin/frpc", "usr/bin/frpc", "data/data/com.termux/files/usr/bin/frpc"),
            listOf("bin/rclone", "usr/bin/rclone", "data/data/com.termux/files/usr/bin/rclone"),
            listOf("bin/proot", "usr/bin/proot")
        )
        var removed = 0
        targets.forEach { paths ->
            var hit = false
            paths.forEach { rel ->
                val f = File(prefix, rel)
                if (f.exists() && f.delete()) hit = true
            }
            if (hit) removed++
        }
        return removed
    }

    fun installedDependencySteps(): List<StepState> = InstallStep.values().map { step ->
        StepState(step, if (isDependencyInstalled(step)) StepStatus.Done else StepStatus.Wait)
    }

    fun isDependencyInstalled(step: InstallStep): Boolean {
        val prefix = installer.rootDir
        fun hasExecutable(vararg paths: String) = paths.any { path ->
            File(prefix, path).let { it.isFile && it.canExecute() }
        }
        return when (step) {
            InstallStep.Jdk -> isJavaInstalled(JavaVersion.Java17)
            InstallStep.Wget -> hasExecutable("bin/wget", "usr/bin/wget", "data/data/com.termux/files/usr/bin/wget") &&
                File(prefix, "etc/fonts/fonts.conf").isFile
            InstallStep.Rclone -> hasExecutable("bin/rclone", "usr/bin/rclone", "data/data/com.termux/files/usr/bin/rclone")
            InstallStep.Proot -> hasExecutable("bin/proot", "usr/bin/proot")
        }
    }

    fun isCommandInstalled(command: String): Boolean {
        val prefix = installer.rootDir
        return listOf(
            File(prefix, "bin/$command"),
            File(prefix, "usr/bin/$command"),
            File(prefix, "data/data/com.termux/files/usr/bin/$command")
        ).any { it.isFile && it.canExecute() }
    }

    /**
     * Read-only checks used by the dashboard diagnostic screen.
     *
     * FCL 来源只看它自己的落点：那套 JRE 是整包解压出来的完整运行时，不存在
     * 「装了但是缺件」这种半成品状态（安装是「校验通过 → 原子改名」，要么整棵都在，
     * 要么什么都没有），所以判定条件比 Termux 的 `isJavaComplete` 简单。
     */
    fun javaRuntimeDiagnostic(
        version: JavaVersion,
        vendor: JavaVendor = JavaVendor.Termux
    ): Pair<Boolean, String> = when (vendor) {
        JavaVendor.Fcl -> {
            val ready = fclJre.isInstalled(version)
            // 括号不能省：Kotlin 的 infix `to` 右操作数只吃 rangeExpression，
            // 直接写 `ready to if (...)` 是语法错误（if 是更高一层的 expression）。
            ready to (
                if (ready) "FCL ${version.displayName} runtime is executable"
                else "FCL ${version.displayName} runtime is not installed"
                )
        }
        JavaVendor.Termux -> when (version) {
            JavaVersion.Java8 -> {
                // 「Termux + Java 8」实质就是 focal 容器，与 Proot 来源走同一套判定。
                val profile = legacyJava8Profile
                val rootfsReady = hasUsableUbuntuShell(profile.rootfsDir(installer.rootDir))
                val ready = isContainerJavaReady(profile)
                when {
                    ready -> true to "Ubuntu ARM64 container and openjdk-8-jdk are ready"
                    !rootfsReady -> false to "Ubuntu ARM64 container shell is unavailable"
                    else -> false to "openjdk-8-jdk is incomplete or has not passed verification"
                }
            }
            else -> {
                val ready = isJavaInstalled(version) && isJavaComplete(version)
                ready to (if (ready) "${version.displayName} runtime is executable"
                else "${version.displayName} runtime is missing or incomplete")
            }
        }
        // PRoot 来源：这里刻意**不接收** distro 参数，因为诊断问的是
        // 「PRoot 的某个 Java 版本有没有可用的实现」——只要任意一个发行版
        // 提供且装好了，这条路就是通的。用户当前选中的发行版里到底装没装，
        // 是启动校验关心的事，由 [isJavaReadyForDistro] 回答。
        // 两者的区别很实际：Java 17 装在 focal、当前选 jammy 时，
        // 诊断说「可用」没错（换个发行版就能跑），但启动必须拦下来。
        JavaVendor.Proot -> {
            val candidates = ProotDistro.entries.mapNotNull { it.profileFor(version) }
            val readyProfile = candidates.firstOrNull { isContainerJavaReady(it) }
            when {
                candidates.isEmpty() -> false to
                    "No PRoot distro provides ${version.displayName}"
                readyProfile != null -> true to
                    "${readyProfile.displayName} container has ${readyProfile.aptPackage} ready"
                candidates.none { containerRootfsReady(it) } -> false to
                    "${candidates.first().distro.displayName} container rootfs is unavailable"
                else -> false to
                    "${candidates.first().aptPackage} is incomplete or has not passed verification"
            }
        }
    }

    /**
     * 某来源（含 Proot / Termux Java 8）的字体运行库是否就绪。
     *
     * 与 [fontRuntimeReady] 的区别是**接收 vendor 并自行分流**：
     * 来宾链路进容器检查，宿主链路查宿主文件。
     * 诊断页与任何「不知道当前是哪种链路」的调用方都应该用这个方法。
     */
    fun fontRuntimeDiagnostic(vendor: JavaVendor, version: JavaVersion): Boolean {
        if (isProotGuestRuntime(vendor, version)) {
            val profile = resolveProotProfile(vendor, version)
            if (!hasUsableUbuntuShell(profile.rootfsDir(installer.rootDir))) return false
            return runUbuntu(
                "test -f /etc/fonts/fonts.conf && test -x /usr/bin/fc-cache",
                15_000,
                profile = profile
            ) == 0
        }
        // FCL 的 JRE 是整包解压的完整运行时，字体随包附带，无 apt 补齐一说
        if (vendor == JavaVendor.Fcl) return true
        return fontRuntimeReady(version)
    }

    fun needsFontRuntime(core: ServerCore): Boolean = core == ServerCore.Forge || core == ServerCore.NeoForge

    /**
     * 宿主侧字体运行库是否就绪。
     *
     * ⚠️ **只对宿主链路（Termux 非 Java 8）有意义。**
     * 来宾容器链路的字体在容器内，必须用 `runUbuntu(...)` 进容器检查，
     * 否则会拿宿主的 `etc/fonts/fonts.conf` 去回答来宾的问题 ——
     * 容器缺字体时会误报「已就绪」，启动后 Fontconfig 才失败。
     *
     * 调用方若要判断「某来源（含 Proot）的字体是否就绪」，请用
     * [autoRepairRuntime]（它已按 [isProotGuestRuntime] 分流），
     * 或自行按 vendor 分支。这里保留 `require` 是为了挡住误用，
     * 因为这种误用不会崩，只会静默给出错误答案。
     */
    fun fontRuntimeReady(version: JavaVersion): Boolean {
        require(!isProotGuestRuntime(JavaVendor.Termux, version)) {
            "$version 走来宾容器链路，字体在容器内；请改用容器内的检查（见 autoRepairRuntime）"
        }
        return File(installer.rootDir, "etc/fonts/fonts.conf").isFile && isCommandInstalled("fc-cache")
    }

    /** Rebuild fontconfig with the resolved executable path rather than PATH lookup. */
    private fun rebuildTermuxFontCache(): Boolean {
        val prefix = installer.rootDir
        val cache = listOf(
            File(prefix, "bin/fc-cache"),
            File(prefix, "usr/bin/fc-cache"),
            File(prefix, "data/data/com.termux/files/usr/bin/fc-cache")
        ).firstOrNull { it.isFile && it.canExecute() } ?: run {
            emitLog("[repair] 字体缓存命令 fc-cache 未安装或不可执行")
            return false
        }
        val code = execOnce(cache.absolutePath, "-f")
        val ready = code == 0 && File(prefix, "etc/fonts/fonts.conf").isFile
        emitLog(if (ready) "[repair] 字体缓存已生成" else "[repair] 字体缓存生成失败(exit=$code)，请在运行诊断中查看命令状态")
        return ready
    }

    fun repairFontRuntime(): Boolean {
        if (!isReady()) return false
        val prefix = installer.rootDir
        if (!File(prefix, "etc/fonts/fonts.conf").isFile || !isCommandInstalled("fc-cache")) {
            emitLog("[repair] 正在补齐字体运行库...")
            if (!prepareAptPackages("fontconfig", "ttf-dejavu")) return false
            execOnce(
                "apt-get", "-o", "DPkg::Lock::Timeout=60", "install",
                "--allow-unauthenticated", "-y", "fontconfig", "ttf-dejavu"
            )
            repairInstalledCommands()
        }
        return rebuildTermuxFontCache()
    }

    /**
     * 安装指定来源的指定 Java 版本。
     *
     * [JavaVendor.Fcl] 走的是「下载 .ecpkg → 校验 → 解压到 filesDir/jvm-fcl/」，
     * 与 apt 无关；[JavaVendor.Termux] 走原有的 apt / Ubuntu 容器路径。
     * [onProgress] 取值 0–100，三种来源都有意义：
     * - FCL：按下载字节数算。
     * - PRoot：按阶段报（rootfs 下载解压 0–60、容器内 apt 安装 60–100）。
     *   apt 本身不给细粒度进度，但整段是数分钟的长任务，进度圈不动会被当成卡死。
     * - Termux 宿主：apt 同样无细粒度进度，只在开始/结束时回调。
     */
    suspend fun installJava(
        version: JavaVersion,
        vendor: JavaVendor = JavaVendor.Termux,
        onProgress: ((Int) -> Unit)? = null,
        distro: ProotDistro = ProotDistro.Default
    ): Boolean = withContext(Dispatchers.IO) {
        if (vendor == JavaVendor.Fcl) {
            // 刻意不检查 isReady()：这套 JRE 是 Android 原生二进制，落盘即可用，
            // 与 Termux 环境是否初始化无关（启动服务端时才需要 Termux 环境）。
            emitLog("[java] 正在安装 FCL ${version.displayName}（下载 .ecpkg 并校验）")
            return@withContext fclJre.install(
                version = version,
                onProgress = { done, total, _ ->
                    onProgress?.invoke((done * 100 / total.coerceAtLeast(1L)).toInt())
                },
                onLog = { emitLog(it) }
            )
        }
        // PRoot 来源：先确认「这个发行版确实提供这个版本」，再进容器装。
        //
        // 这一步不能省。用户完全可能把发行版设成 focal 而版本选成 Java 21 ——
        // 若不校验就直接进容器，apt 只会报一句 `Unable to locate package openjdk-21-jdk`，
        // 用户看到的是「安装失败」，而不是「这个组合不成立」。
        if (vendor == JavaVendor.Proot) {
            if (!isReady()) throw RuntimeException("Termux 环境未初始化")
            val profile = distro.profileFor(version)
            if (profile == null) {
                emitLog(
                    "[proot] ${distro.displayName} 不提供 ${version.displayName}" +
                        "（可选：${distro.availableJavaVersions.joinToString { it.displayName }}）"
                )
                return@withContext false
            }
            return@withContext installProotJava(profile, onProgress)
        }
        if (!isReady()) throw RuntimeException("Termux 环境未初始化")
        // 「Termux + Java 8」与「Proot + 任意版本」走**同一条**来宾安装路径。
        // 之前这里是单独的一次 installJava8Ubuntu() 调用，仅仅因为 Java 8 是
        // 最早唯一的容器用途；现在两者在语义上完全等价（都是 focal + Java 8），
        // 保留两条分支只会让「日志措辞不同、就绪判定不同」这类分歧有可乘之机。
        if (isProotGuestRuntime(vendor, version)) {
            val profile = resolveProotProfile(vendor, version)
            return@withContext installProotJava(profile, onProgress)
        }
        emitLog("[java] 正在安装 ${version.displayName}")
        emitLog("[java] ${version.displayName} 正在下载并配置，首次安装可能需要数分钟")
        if (version == JavaVersion.Java11 && !ensureTurRepo()) {
            emitLog("[java] ${version.displayName} 安装失败：TUR 社区软件源不可用，请检查网络后重试")
            return@withContext false
        }
        if (!prepareAptPackages(version.packageName)) {
            emitLog("[java] ${version.displayName} 安装失败：Termux 软件源或包索引不可用")
            return@withContext false
        }
        // 安装前再次校验 apt 可执行（防 dpkg 操作后权限被改）
        ensureAptWorking()
        val code = execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", version.packageName)
        if (code == 0) fixJavaSymlinks(version)
        val installed = code == 0 && isJavaInstalled(version)
        emitLog(if (installed) "[java] ${version.displayName} 安装完成" else "[java] ${version.displayName} 安装失败")
        installed
    }

    /**
     * TUR（Termux User Repository）提供 Termux 原生 openjdk-11；
     * tur-repo 包本身来自主仓库，安装后写入独立的 sources.list.d 条目。
     */
    private fun ensureTurRepo(): Boolean {
        val marker = File(installer.rootDir, "var/lib/dpkg/status")
        val hasTur = runCatching { marker.exists() && marker.useLines { lines ->
            lines.any { it == "Package: tur-repo" }
        } }.getOrDefault(false)
        if (hasTur) return true
        val code = execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", "tur-repo")
        if (code != 0) emitLog("[java] tur-repo 安装失败（TUR 软件源不可用）")
        return code == 0
    }

    /**
     * 在 PRoot 容器里用 apt 安装指定 Java 版本。
     *
     * 这是从 `installJava8Ubuntu()` 泛化来的：结构完全一致（恢复未完成事务 →
     * 逐个镜像试 apt 源 → 安装 → 校验），只是发行版代号、包名、JAVA_HOME
     * 与版本验证正则改由 [ProotContainerProfile] 推导。
     *
     * @return 是否安装并校验成功
     */
    /**
     * 安装容器内的一个 Java 版本。
     *
     * @param onProgress 0–100 的阶段进度。分两段：rootfs 下载解压（0–60）、
     *   容器内 apt 安装（60–100）。apt 本身不给细粒度进度，
     *   所以第二段只在开始与结束时各报一次 —— 但这两段合起来是数分钟的长任务，
     *   一个不动的进度圈会让用户以为卡死了。
     */
    private suspend fun installProotJava(
        profile: ProotContainerProfile,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        val marker = profile.readyMarker(installer.rootDir)
        if (isContainerJavaReady(profile)) {
            emitLog("[proot] ${profile.displayName} 已安装，跳过重复下载")
            onProgress?.invoke(100)
            return true
        }
        emitLog("[proot] 注意：正在 ${profile.distro.displayName} glibc 环境安装 ${profile.version.displayName}，非 Termux 官方源")
        emitLog("[proot] 该 JDK 仅在容器内运行，不会修改 Termux 里的其他 Java 版本")
        marker.delete()
        return try {
            onProgress?.invoke(0)
            if (!prepareUbuntuRuntime(profile) { pct ->
                    // rootfs 阶段映射到 0–60
                    onProgress?.invoke(pct * 60 / 100)
                }) {
                throw IllegalStateException("${profile.distro.displayName} 容器无法启动，请先修复运行环境")
            }
            onProgress?.invoke(60)
            // 空间预检：openjdk-21-jdk 解包后约 500MB，加 apt 缓存与依赖要 ~1.5GB。
            // 空间不够时 apt 会在解包中途失败，而 proot 往往先被拖崩，日志里只剩
            // 一句「signal 11」—— 让用户误以为是不兼容，实际是存储满了。
            val freeMb = installer.rootDir.usableSpace / (1024 * 1024)
            if (freeMb < CONTAINER_INSTALL_MIN_FREE_MB) {
                throw IllegalStateException(
                    "容器所在分区只剩 ${freeMb}MB，安装 ${profile.aptPackage} 需要约 " +
                        "${CONTAINER_INSTALL_MIN_FREE_MB}MB，请清理存储后重试"
                )
            }
            emitLog("[proot] 正在容器内安装 ${profile.aptPackage}，首次安装可能需要数分钟（可用空间 ${freeMb}MB）")
            val code = runUbuntuResilient(
                label = profile.displayName,
                command = containerJavaInstallCommand(profile),
                timeoutMs = 900_000,
                profile = profile
            )
            if (code != 0) {
                emitLog("[proot] 设备环境：内存页 ${pageSize()}（16KB 页设备上旧版 proot 兼容性更差）")
                throw IllegalStateException(
                    prootInstallFailureMessage(profile.displayName, code, executor.lastTail)
                )
            }
            marker.parentFile?.mkdirs()
            marker.writeText("${profile.distro.codename}-arm64-${profile.aptPackage}\n")
            onProgress?.invoke(100)
            emitLog("[proot] ${profile.displayName} 安装并校验完成")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消不是安装失败：吞掉它会让上层按失败弹错并进入重试，
            // 而实际的安装流程（runUbuntu 里的挂起调用）并没有被真正中止。
            throw e
        } catch (e: Exception) {
            marker.delete()
            emitLog("[proot] ${profile.displayName} 安装失败：${e.message}")
            false
        }
    }

    /**
     * 生成「在容器里安装 Java」的 shell 脚本。
     *
     * 脚本按镜像逐个尝试（清华 → 中科大）：容器 rootfs 里还没有 CA 证书，
     * 因此这一轮引导事务**只用国内 HTTP 源**，把 ca-certificates 和 JDK
     * 一起装上，后续才能用 HTTPS。
     *
     * 校验用 `java -version` 的版本号前缀而非仅看文件是否存在 ——
     * apt 事务中断时会留下一个半装的 JDK，只看文件会把这种情况判成成功。
     */
    private fun containerJavaInstallCommand(profile: ProotContainerProfile): String {
        val codename = profile.distro.codename
        val pkg = profile.aptPackage
        // 版本号前缀：Java 8 的 `java -version` 输出 `1.8.0_xxx`，9 以后是 `11.0.x`。
        // 用 grep 锚定就能区分「装上了」和「装错了版本」。
        val versionPattern = when (profile.version) {
            JavaVersion.Java8 -> "1\\.8\\."
            JavaVersion.Java11 -> "11\\."
            JavaVersion.Java17 -> "17\\."
            JavaVersion.Java21 -> "21\\."
            JavaVersion.Java25 -> "25\\."
        }
        val installFn = "install_jdk"
        val mirrorCalls = profile.distro.aptMirrors.joinToString(" || \\\n          ") {
            "$installFn $it"
        }
        // 校验用的 java 路径必须与「就绪判定」（[ProotContainerProfile.guestJavaBinaryCandidates]）
        // 用同一组候选，否则会出现「装好了但判定说没装」的不一致：
        // Java 8 的 Ubuntu 包把 JRE 放在 `jre/bin/java` 下，只查新 layout 会漏掉它。
        val javaCheck = profile.guestJavaBinaryCandidates.joinToString(" || ") {
            "test -x $it"
        }
        val javaVersionCheck = profile.guestJavaBinaryCandidates.first()
        // 一起装/一起清的包：meta 包只依赖 headless，真正装文件的是 headless。
        val packages = profile.relatedAptPackages.joinToString(" ")
        return """
        echo "[proot] 容器可用空间: ${'$'}(df -h / 2>/dev/null | tail -1)"
        echo "[proot] 正在恢复容器内未完成的软件包事务..."
        # 上一次安装被强杀（proot 崩溃 / 用户退出）会留下 dpkg 锁与半完成事务，
        # 不清掉就会一直卡在「另一个进程持有锁」，而日志里只会看到一条 apt 报错。
        rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/cache/apt/archives/lock 2>/dev/null
        if ! dpkg --configure -a; then
          echo "[proot] dpkg --configure -a 失败：容器内的软件包事务没能恢复"
          exit 11
        fi
        if ! apt-get -o DPkg::Lock::Timeout=60 -f install -y; then
          echo "[proot] apt-get -f install 失败，未继续切换软件源；请稍后重试运行环境修复"
          exit 12
        fi
        $installFn() {
          mirror="${'$'}1"
          echo "[proot] APT 源: ${'$'}mirror"
          printf '%s\n' \
            "deb ${'$'}mirror $codename main restricted universe multiverse" \
            "deb ${'$'}mirror $codename-updates main restricted universe multiverse" \
            "deb ${'$'}mirror $codename-security main restricted universe multiverse" \
            > /etc/apt/sources.list
          rm -rf /var/lib/apt/lists/*
          if ! apt-get update -o Acquire::Retries=2 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30; then
            echo "[proot] apt-get update 失败（源 ${'$'}mirror）"
            return 1
          fi
          # 关键分支：dpkg 说「已安装」但 java 不存在 ⇒ 文件层面残缺。
          # 这种状态 apt 只会跑 postinst，**永远不会**把文件补回来，
          # 于是每次重装都得到「0 newly installed / 0 upgraded」，然后校验失败。
          if ! ($javaCheck) && dpkg -s $pkg >/dev/null 2>&1; then
            echo "[proot] $pkg 记录为已安装但 java 文件缺失，强制重新解包"
            if ! apt-get install -y --no-install-recommends --reinstall $packages \
                ca-certificates fontconfig fonts-dejavu-core; then
              echo "[proot] 强制重装失败，改为清除后重装"
              apt-get -o DPkg::Lock::Timeout=60 purge -y $packages >/dev/null 2>&1 || true
              if ! apt-get install -y --no-install-recommends $packages \
                  ca-certificates fontconfig fonts-dejavu-core; then
                echo "[proot] 清除后重装仍失败（源 ${'$'}mirror）"
                return 1
              fi
            fi
          elif ! apt-get install -y --no-install-recommends $packages \
              ca-certificates fontconfig fonts-dejavu-core; then
            echo "[proot] apt-get install 失败（源 ${'$'}mirror）"
            return 1
          fi
          if ! ($javaCheck); then
            echo "[proot] 安装结束但找不到 java 可执行文件（$javaVersionCheck），容器内的包状态已损坏"
            return 1
          fi
          if ! $javaVersionCheck -version 2>&1 | grep -q '$versionPattern'; then
            echo "[proot] java -version 与期望版本不符："
            $javaVersionCheck -version 2>&1 | head -2
            return 1
          fi
          return 0
        }
        # 容器 rootfs 里可能还没有 CA 证书，因此这一轮引导只用国内 HTTP 源，
        # 把 ca-certificates 与 JDK 一起装上，后续才能走 HTTPS。
        $mirrorCalls || {
          echo "[proot] 全部软件源均安装失败，容器内可用的 Java 版本未发生变化"
          exit 13
        }
        """.trimIndent()
    }

    /**
     * 「Termux 来源 + Java 8」这条历史组合是否就绪。
     *
     * 它就是 focal + Java 8 的就绪判定，没有额外分支 ——
     * 与 Proot 来源共用同一套逻辑，避免两条链路对「什么叫装好了」出现分歧。
     */
    private fun java8UbuntuReady(): Boolean = isContainerJavaReady(legacyJava8Profile)

    /**
     * 准备 PRoot 容器运行时（与具体发行版无关的那部分）。
     *
     * 只做「容器能跑起来」的前置：proot 包、库修复、launcher、rootfs 可用性。
     * 具体在容器里装哪个发行版的 Java 由调用方另行处理。
     *
     * @param profile 目标画像；仅用于日志措辞与 rootfs 落点。
     */
    private suspend fun prepareUbuntuRuntime(
        profile: ProotContainerProfile = legacyJava8Profile,
        onRootfsProgress: ((Int) -> Unit)? = null
    ): Boolean {
        if (!prepareAptPackages("proot")) {
            emitLog("[proot] ${profile.displayName} 前置依赖 proot 无法从 Termux 软件源获取")
            return false
        }
        emitLog("[proot] 正在安装 proot 与容器胶水文件")
        var code = execOnce(
            "apt-get", "-o", "DPkg::Lock::Timeout=60", "install",
            "--allow-unauthenticated", "-y", "proot"
        )
        if (code != 0) return false
        fixUsrBin()
        fixScriptsOnce()
        ensureRootfsExecutable()
        repairProotLibraries()
        installProotLauncher()
        ensureRootfsExecutable()
        if (!verifyProot()) return false
        emitLog("[proot] proot 已就位")

        // 已有可用 rootfs 时才值得花钱自检：rootfs 根本不存在时这一步必然失败，
        // 白白等一个超时、还会在控制台刷一堆 proot 报错，让人以为出了大问题。
        val existingRootfs = containerRootfs(profile)
        if (hasUsableUbuntuShell(existingRootfs)) {
            emitLog("[proot] 正在自检已有的 ${profile.distro.displayName} 容器")
            val existingProbe = runUbuntu(SHELL_PROBE, ROOTFS_PROBE_TIMEOUT_MS, profile = profile)
            if (existingProbe == 0) {
                emitLog("[proot] ${profile.distro.displayName} 容器可用")
                onRootfsProgress?.invoke(100)
                return true
            }
            emitLog("[proot] 已有容器自检未通过（exit=$existingProbe），准备重建 rootfs")
        } else {
            emitLog("[proot] 尚未安装 ${profile.distro.displayName} 容器")
        }
        if (!installUbuntuBaseRootfs(profile, onRootfsProgress)) {
            emitLog("[proot] ${profile.distro.displayName} rootfs 无法重建；旧的容器和服务器数据未被修改")
            return false
        }
        emitLog("[proot] 正在启动新容器做首次自检（首次进入可能要一两分钟）")
        val probe = runUbuntu(SHELL_PROBE, ROOTFS_PROBE_TIMEOUT_MS, profile = profile)
        if (probe != 0) {
            emitLog(
                "[proot] 容器首次自检失败 exit=$probe" +
                    if (probe == 124) "（超时：容器启动过慢，请重试一次）" else ""
            )
            return false
        }
        emitLog("[proot] ${profile.distro.displayName} 容器已就绪")
        return true
    }

    /**
     * 下载并安装容器 rootfs。
     *
     * 不用 proot-distro 的 Docker Hub 镜像：移动网络下 Docker Hub 经常 429，
     * 而 Ubuntu 归档站是普通 HTTPS 端点，不需要账号、不限速。
     *
     * 三条约束：
     *  1. **多源**：镜像列表来自 [UbuntuImageSources]（6 个国内镜像），
     *     首选连不上/超时/返回错误页就自动换下一个，而不是直接判失败；
     *  2. **校验**：优先按同目录的 `SHA256SUMS` 核对整个文件 —— 错误页、
     *     被截断的下载都能在这一步挡住；取不到校验文件时退回「大小 + gzip 魔数」，
     *     不让一个次要请求把整个安装判死；
     *  3. **不污染**：只有校验通过才解压，解压前先清空本画像自己的 rootfs；
     *     任一环节失败都会把半成品清掉，避免留下「有目录没内容」的残状态。
     */
    private suspend fun installUbuntuBaseRootfs(
        profile: ProotContainerProfile = legacyJava8Profile,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        val rootfs = containerRootfs(profile)
        if (hasUsableUbuntuShell(rootfs)) {
            onProgress?.invoke(100)
            return true
        }

        val archive = File(installer.tmpDir, UbuntuImageSources.archiveName(profile.distro))
        val sources = UbuntuImageSources.archiveSources(profile.distro)
        return try {
            emitLog("[proot] 正在下载 ${profile.distro.displayName} ARM64 基础 rootfs（${sources.size} 个镜像可自动切换）")
            archive.delete()
            val expectedSha = fetchRootfsSha256(profile.distro)
            if (expectedSha == null) {
                emitLog("[proot] 各镜像的 SHA256SUMS 都取不到，改用大小与文件头校验")
            } else {
                emitLog("[proot] rootfs 目标 SHA-256: ${expectedSha.take(16)}…")
            }
            onProgress?.invoke(3)
            // 下载占 rootfs 阶段的 85%，解压/修复占剩余 15%：
            // 下载是几十兆的网络传输，解压是本地操作，这个比例贴近真实耗时。
            MultiSourceDownload.download(
                sources = sources,
                target = archive,
                onAttempt = { attempt ->
                    emitLog(
                        "[proot] rootfs 来源 ${attempt.label}: " +
                            if (attempt.ok) "成功" else "失败（${attempt.reason}）"
                    )
                },
                onProgress = { done, total, _ ->
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt() * 85 / 100
                        onProgress?.invoke(pct.coerceIn(3, 85))
                    }
                },
                validate = { file -> validateRootfsArchive(file, expectedSha) }
            )
            onProgress?.invoke(85)
            emitLog("[proot] 正在解压 rootfs 并修复权限，请稍候")
            // 只动本画像自己的 rootfs。绝不去碰旧版 proot-distro 容器、
            // 服务端文件、世界、插件或配置。
            rootfs.deleteRecursively()
            rootfs.mkdirs()
            extractUbuntuRootfs(archive, rootfs)
            repairUbuntuRootfs(profile)
            if (!hasUsableUbuntuShell(rootfs)) {
                throw IllegalStateException("rootfs 解压后没有可执行 shell，安装包可能已损坏")
            }
            onProgress?.invoke(100)
            emitLog("[proot] ${profile.distro.displayName} ARM64 rootfs 已就绪")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            rootfs.deleteRecursively()
            throw e
        } catch (e: Exception) {
            rootfs.deleteRecursively()
            emitLog("[proot] rootfs 安装失败: ${e.message}")
            false
        } finally {
            archive.delete()
        }
    }

    /**
     * 取 rootfs 的目标 SHA-256。
     *
     * 逐个镜像尝试 `SHA256SUMS`：Ubuntu Base 各个镜像的文件是同一份，
     * 因此任一镜像给出的值都可用于校验任意源下载下来的文件。
     * 全部取不到时返回 null（调用方退回结构性校验）。
     */
    private fun fetchRootfsSha256(distro: ProotDistro): String? {
        val archiveName = UbuntuImageSources.archiveName(distro)
        for (source in UbuntuImageSources.archiveSources(distro)) {
            val url = UbuntuImageSources.sha256SumsUrl(source)
            val text = try {
                httpGetText(url)
            } catch (e: Exception) {
                emitLog("[proot] SHA256SUMS 不可用（${source.label}）: ${e.message}")
                continue
            }
            UbuntuImageSources.expectedSha256(text, archiveName)?.let { return it }
            emitLog("[proot] ${source.label} 的 SHA256SUMS 里没有 $archiveName")
        }
        return null
    }

    /** rootfs 归档的内容校验：先看结构，再比哈希 */
    private fun validateRootfsArchive(file: File, expectedSha: String?): String? {
        if (!isPlausibleUbuntuArchive(file)) {
            return "不是有效的 Ubuntu Base 归档（可能是错误页或被截断）"
        }
        if (expectedSha == null) return null
        val actual = runCatching { MultiSourceDownload.sha256(file) }.getOrNull()
            ?: return "无法计算 SHA-256"
        if (!actual.equals(expectedSha, ignoreCase = true)) {
            return "SHA-256 不匹配（下载不完整或镜像文件已损坏）"
        }
        return null
    }

    private fun hasUsableUbuntuShell(rootfs: File): Boolean =
        listOf("bin/sh", "bin/bash", "usr/bin/bash")
            .map { File(rootfs, it) }
            .any { it.isFile && it.canExecute() }

    private fun isPlausibleUbuntuArchive(archive: File): Boolean {
        if (!archive.isFile || archive.length() < 20L * 1024 * 1024) return false
        return FileInputStream(archive).use { input ->
            input.read() == 0x1f && input.read() == 0x8b
        }
    }

    private fun extractUbuntuRootfs(archive: File, destination: File) {
        FileInputStream(archive).use { fileInput ->
            GzipCompressorInputStream(BufferedInputStream(fileInput)).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val output = File(destination, entry.name)
                        val rootPath = destination.canonicalPath + File.separator
                        if (!output.canonicalPath.startsWith(rootPath)) {
                            throw IllegalStateException("Unsafe Ubuntu rootfs path: ${entry.name}")
                        }
                        when {
                            entry.isDirectory -> output.mkdirs()
                            entry.isSymbolicLink -> {
                                output.parentFile?.mkdirs()
                                output.delete()
                                Os.symlink(entry.linkName, output.absolutePath)
                            }
                            entry.isLink -> {
                                val source = File(destination, entry.linkName)
                                output.parentFile?.mkdirs()
                                output.delete()
                                if (source.isFile) source.copyTo(output, overwrite = true)
                            }
                            else -> {
                                output.parentFile?.mkdirs()
                                FileOutputStream(output).use { tar.copyTo(it) }
                                if ((entry.mode and 0b001_001_001) != 0) output.setExecutable(true, false)
                            }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
    }

    private fun prootEnvironment(tuning: ProotLaunchTuning = ProotLaunchTuning()): Map<String, String> {
        val prefix = installer.rootDir.absolutePath
        val tmp = File(installer.rootDir, "tmp").apply { mkdirs() }.absolutePath
        return buildMap {
            put("PROOT_TMP_DIR", tmp)
            put("TMPDIR", tmp)
            put("TERMUX_PREFIX", prefix)
            put("TERMUX_HOME", "$prefix/home")
            put("TERMUX__PREFIX", prefix)
            put("TERMUX__HOME", "$prefix/home")
            put("TERMUX_APP_PACKAGE", "com.mineserve.mobile")
            put("TERMUX_APP__PACKAGE_NAME", "com.mineserve.mobile")
            put("PROOT_LOADER", "$prefix/libexec/proot/loader")
            put("PROOT_LOADER_32", "$prefix/libexec/proot/loader32")
            put("TERMUX_VERSION", "mineServe")
            // 关掉 proot 自带的 seccomp 加速器：部分厂商内核的 seccomp 过滤规则与
            // proot 的加速路径不兼容，proot 会在处理某个 syscall 时自己 SIGSEGV
            // （日志 `proot warning: signal 11 received from process -1`）。
            // 退化成纯 ptrace 模式后 syscall 逐个代理，慢一些但不会崩。
            if (tuning.noSeccomp) put("PROOT_NO_SECCOMP", "1")
        }
    }

    /**
     * proot 启动参数的可降级档位。
     *
     * 为什么需要一组档位而不是一个开关：`proot warning: signal 11` 是**设备相关**的
     * 段错误 —— 同一条命令在维护者机器上跑得好好的，换台厂商内核更激进（seccomp
     * 过滤更严、SYSV IPC 被裁掉）的设备就崩。能导致它的机制不止一种，所以按
     * 「先关加速、再关 SYSV IPC、最后放弃强杀 tracee」逐级退让，把兼容性换回来。
     */
    internal data class ProotLaunchTuning(
        val noSeccomp: Boolean = false,
        val sysvipc: Boolean = true,
        val killOnExit: Boolean = true,
        val label: String = "默认参数"
    ) {
        companion object {
            /** 依次尝试的档位：越往后越保守（更慢），但能过更多设备。 */
            val fallbacks: List<ProotLaunchTuning> = listOf(
                ProotLaunchTuning(),
                ProotLaunchTuning(noSeccomp = true, label = "关闭 seccomp 加速"),
                ProotLaunchTuning(
                    noSeccomp = true,
                    sysvipc = false,
                    label = "关闭 seccomp 加速 + 不映射 SYSV IPC"
                ),
                ProotLaunchTuning(
                    noSeccomp = true,
                    sysvipc = false,
                    killOnExit = false,
                    label = "关闭 seccomp 加速 + 不映射 SYSV IPC + 不强杀子进程"
                )
            )
        }
    }

    private fun runUbuntu(
        command: String,
        timeoutMs: Long = 120_000,
        extraBinds: List<String> = emptyList(),
        profile: ProotContainerProfile = legacyJava8Profile,
        tuning: ProotLaunchTuning = ProotLaunchTuning()
    ): Int =
        if (!repairUbuntuRootfs(profile)) {
            emitLog("[proot] ${profile.distro.displayName} rootfs 缺少可执行 shell，无法启动")
            126
        } else {
            val rootfs = containerRootfs(profile)
            val shm = File(rootfs, "tmp").apply { mkdirs() }
            val resolver = prepareUbuntuDns(
                rootfs,
                profile.hostResolvConf(installer.rootDir),
                "${profile.distro.dirName}-resolv.conf"
            )
            val args = mutableListOf("proot")
            if (tuning.killOnExit) args += "--kill-on-exit"
            args += "--link2symlink"
            if (tuning.sysvipc) args += "--sysvipc"
            args += listOf(
                "-L",
                "--change-id=0:0",
                "--rootfs=${rootfs.absolutePath}",
                "--cwd=/root",
                "--bind=/dev",
                "--bind=/proc",
                "--bind=/sys",
                "--bind=/dev/urandom:/dev/random",
                "--bind=${shm.absolutePath}:/dev/shm",
                "--bind=${resolver.absolutePath}:/etc/resolv.conf"
            )
            extraBinds.forEach { args += "--bind=$it" }
            args += listOf(
                "/usr/bin/env",
                "-i",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "HOME=/root",
                "TMPDIR=/tmp",
                "LANG=C.UTF-8",
                "DEBIAN_FRONTEND=noninteractive",
                "/bin/sh",
                "-c",
                command
            )
            executor.execOnceWithTimeoutCapture(
                timeoutMs, *args.toTypedArray(), env = prootEnvironment(tuning)
            ).code
        }

    /**
     * 带降级的 [runUbuntu]：只在**判定为 proot 自己崩溃**时换参数重试。
     *
     * 为什么不做「任何失败都重试」：apt 源 404、磁盘写满、校验不通过这类失败换
     * 参数也救不了，盲目重跑只会把 900 秒的超时乘以档位数，用户看到的是长时间
     * 无进展。只有崩溃（信号类退出码 / `signal 11` 字样）才值得换一档。
     */
    private fun runUbuntuResilient(
        label: String,
        command: String,
        timeoutMs: Long,
        profile: ProotContainerProfile,
        extraBinds: List<String> = emptyList()
    ): Int {
        var last = -1
        for ((index, tuning) in ProotLaunchTuning.fallbacks.withIndex()) {
            if (index > 0) {
                emitLog(
                    "[proot] proot 在容器里崩溃（signal 11），改用「${tuning.label}」重试" +
                        "（${index}/${ProotLaunchTuning.fallbacks.size - 1}）"
                )
            }
            last = runUbuntu(command, timeoutMs, extraBinds, profile, tuning)
            if (last == 0) return 0
            if (!isProotCrashSignal(last, executor.lastTail)) return last
        }
        emitLog("[proot] $label 在全部兼容档位下均崩溃，最近一次 exit=$last")
        return last
    }


    /** Give the isolated rootfs Android's active DNS servers. */
    private fun prepareUbuntuDns(
        rootfs: File,
        hostResolv: File? = null,
        fileName: String = "resolv.conf"
    ): File {
        val addresses = (1..4).mapNotNull { index ->
            readAndroidDns("net.dns$index").takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }
        }.distinct()
        val nameservers = if (addresses.isNotEmpty()) addresses else listOf("223.5.5.5", "119.29.29.29")
        val content = nameservers.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" }
        val resolver = writeResolverFile(resolverCandidates(hostResolv, fileName), content)

        // Ubuntu Base ships /etc/resolv.conf as a systemd-resolved symlink.
        // The target does not exist in this PRoot container, so replace it with
        // a regular file before binding the same resolver for every invocation.
        val guestResolver = File(rootfs, "etc/resolv.conf")
        guestResolver.delete()
        writeResolverFile(listOf(guestResolver), content)
        return resolver
    }

    /**
     * 写 resolv.conf，返回**真正写成功**的文件。
     *
     * 为什么不能只写一个固定路径就完事（2026-10-02 真机复现）：
     * 这一步在「用户点启动」的路径上，而在应用私有目录里创建文件并不能保证
     * 后续 open 一定成功 —— `mkdirs()` 刚返回 true，紧接着 `FileOutputStream`
     * 就在 open 阶段抛 `ENOENT`（目录在这两步之间被系统回收 / 存储受限）。
     * 单一落点一旦写不进去，用户看到的就是「点启动就崩」，且无路可走。
     *
     * 因此：**依次尝试多个候选落点，谁先写成就用谁**。候选按可靠性排序
     * （容器目录 > files 根 > cache），任何一个可用即可启动，不必因
     * 某个目录被回收而失败。全部失败时才报错，并把每个落点的失败原因
     * 一并列出 —— 这样真出了问题时，日志本身就能定位是哪种存储故障。
     */
    private fun writeResolverFile(candidates: List<File>, content: String): File {
        val failures = mutableListOf<String>()
        candidates.forEach { target ->
            val error = runCatching {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            }.exceptionOrNull()
            if (target.isFile && target.length() > 0) {
                if (failures.isNotEmpty()) {
                    emitLog("[proot] resolv.conf 改用备用落点：${target.absolutePath}")
                }
                return target
            }
            failures += "${target.absolutePath} -> ${error?.message ?: "写入后文件为空"}"
        }
        throw RuntimeException(
            "无法写入 DNS 配置，已尝试 ${candidates.size} 个落点均失败：" +
                failures.joinToString("；") +
                "。请确认设备存储空间充足后重试"
        )
    }

    /**
     * 宿主 resolv.conf 的候选落点，按可靠性从高到低。
     *
     * [preferred] 是容器目录（`var/lib/mineserve/`，与 rootfs 同级，
     * 生命周期一致）；后两个是私有目录下的兜底，用于容器目录意外不可写时
     * 仍能启动（PRoot 只要求这个文件**存在且可读**，位置不重要）。
     */
    private fun resolverCandidates(preferred: File?, fileName: String): List<File> {
        val filesDir = appContext.filesDir
        return listOfNotNull(
            preferred,
            File(filesDir, "mineserve-runtime/$fileName"),
            File(appContext.cacheDir, fileName)
        ).distinctBy { it.absolutePath }
    }

    private fun readAndroidDns(property: String): String = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", property)
            .redirectErrorStream(true)
            .start()
        val value = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        value
    }.getOrDefault("")

    /** Restore executable bits lost while a rootfs layer is applied. */
    private fun repairUbuntuRootfs(profile: ProotContainerProfile = legacyJava8Profile): Boolean {
        val rootfs = containerRootfs(profile)
        if (!rootfs.isDirectory) return false
        // 修过且 shell 仍可执行 → 直接放行。
        // 这段的成本是「遍历 + chmod -R 上万个小文件」，而 runUbuntu 在
        // 「容器自检 / 实测 box64 / 启动服务端」三条路径上都会调它 ——
        // 不记标记的话，每次启动服务端都要凭空多花十几秒到几十秒。
        if (repairMarker(rootfs).isFile && hasUsableUbuntuShell(rootfs)) return true
        var fixed = 0
        val visited = mutableSetOf<String>()
        val commandDirs = listOf("bin", "sbin", "usr/bin", "usr/sbin")
        commandDirs.forEach { relative ->
            File(rootfs, relative).listFiles()?.forEach { file ->
                if (!file.isFile || !visited.add(file.canonicalPath)) return@forEach
                if (!file.canExecute() && file.setExecutable(true, false)) fixed++
            }
        }
        val chmodTargets = commandDirs.map { File(rootfs, it) }.filter { it.exists() }
        if (chmodTargets.isNotEmpty()) {
            val targetArgs = chmodTargets.joinToString(" ") { "'${it.absolutePath.replace("'", "'\\''")}'" }
            runCatching {
                val chmod = ProcessBuilder("/system/bin/sh", "-c", "chmod -R 755 $targetArgs")
                    .redirectErrorStream(true)
                    .start()
                val output = chmod.inputStream.bufferedReader().use { it.readText() }
                if (chmod.waitFor(30, TimeUnit.SECONDS) && chmod.exitValue() == 0) {
                    if (output.isNotBlank()) Log.d(TAG, "repairUbuntuRootfs chmod: $output")
                } else {
                    chmod.destroyForcibly()
                    Log.w(TAG, "repairUbuntuRootfs: chmod failed")
                }
            }.onFailure { Log.w(TAG, "repairUbuntuRootfs: chmod exception: ${it.message}") }
        }
        listOf(
            "lib/ld-linux-aarch64.so.1",
            "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
        ).forEach { relative ->
            val loader = File(rootfs, relative)
            if (loader.isFile && !loader.canExecute() && loader.setExecutable(true, false)) fixed++
        }
        if (fixed > 0) emitLog("[bootstrap] 修复 $fixed 个 Ubuntu rootfs 命令可执行权限")
        val usable = listOf("bin/sh", "bin/bash", "usr/bin/bash")
            .map { File(rootfs, it) }
            .any { it.isFile && it.canExecute() }
        if (usable) runCatching { repairMarker(rootfs).writeText(profile.distro.dirName + "\n") }
        return usable
    }

    /** 「这个 rootfs 的可执行位已修过」的标记（放 rootfs 内，随 rootfs 一起重建） */
    private fun repairMarker(rootfs: File): File = File(rootfs, ".mineserve-exec-repaired")

    /**
     * 在容器里跑服务端自带的 installer，只把该服务端目录绑进去。
     *
     * @param profile 目标容器画像；默认历史遗留的 focal + Java 8。
     *   泛化后 JAVA_HOME 与 java 都可执行路径从画像推导，不再硬编码，
     *   否则在 jammy 里会去执行不存在的 `/usr/bin/java`（jammy 只有
     *   `/usr/lib/jvm/java-XX-openjdk-arm64/bin/java` 且不一定有 alternatives 链接）。
     */
    fun runProotInstaller(
        jarPath: String,
        serverDir: File,
        timeoutMs: Long = 900_000,
        profile: ProotContainerProfile = legacyJava8Profile
    ): Int {
        val guestDir = GUEST_SERVER_DIR
        val guestJar = "$guestDir/${File(jarPath).relativeTo(serverDir).invariantSeparatorsPath}"
        val command = "export JAVA_HOME=${profile.javaHomeInGuest}; " +
            "export PATH=\"${profile.javaHomeInGuest}/bin:\$PATH\"; " +
            "export TMPDIR=/tmp; export HOME=/root; cd '$guestDir' && " +
            "exec ${profile.guestJavaBinaryCandidates.first()} -Djava.io.tmpdir=/tmp -jar '$guestJar' " +
            "--installServer"
        return execProotBound(
            serverDir,
            command,
            timeoutMs,
            profile
        )
    }

    /** 兼容历史调用点：focal 容器里的 Java 8 installer。 */
    fun runJava8Installer(jarPath: String, serverDir: File, timeoutMs: Long = 900_000): Int =
        runProotInstaller(jarPath, serverDir, timeoutMs, legacyJava8Profile)

    /** 在容器里执行调用方给定的命令（用于 installer 后续步骤与探测）。 */
    fun runProotCommand(
        serverDir: File,
        command: String,
        timeoutMs: Long = 900_000,
        profile: ProotContainerProfile = legacyJava8Profile
    ): Int = execProotBound(serverDir, command, timeoutMs, profile)

    /** 兼容历史调用点。 */
    fun runJava8Command(serverDir: File, command: String, timeoutMs: Long = 900_000): Int =
        runProotCommand(serverDir, command, timeoutMs)

    private fun execProotBound(
        serverDir: File,
        command: String,
        timeoutMs: Long,
        profile: ProotContainerProfile = legacyJava8Profile
    ): Int {
        return runUbuntu(
            command,
            timeoutMs,
            extraBinds = listOf("${serverDir.absolutePath}:$GUEST_SERVER_DIR"),
            profile = profile
        )
    }

    suspend fun clearAndReinstallJava(): Boolean = withContext(Dispatchers.IO) {
        if (!isReady()) throw RuntimeException("Termux 环境未初始化")
        val versionsToRestore = installedJavaVersions()
        if (versionsToRestore.isEmpty()) return@withContext true
        emitLog("[java] 正在清除并重装 ${versionsToRestore.joinToString { it.displayName }}")
        versionsToRestore.forEach { version ->
            if (version != JavaVersion.Java8) {
                execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "remove", "-y", version.packageName)
            }
            javaCandidates(version).forEach { File(it).deleteRecursively() }
            if (version == JavaVersion.Java8) {
                // Java 8 只存在于容器里，且「删除该 Java」的语义就是删掉整个 focal
                // rootfs（首期不做容器内单个 JDK 的 apt 卸载）。
                deleteProotContainer(ProotDistro.Focal)
                File(installer.rootDir, "java-8-android-ready").delete()
                File(installer.rootDir, "lib/jvm/java-8-android").deleteRecursively()
            }
        }
        File(installer.rootDir, "bin/java").delete()
        versionsToRestore.sortedBy { it.ordinal }.all { installJava(it) }
    }

    private fun javaCandidates(version: JavaVersion): List<String> {
        val prefix = installer.rootDir.absolutePath
        if (version == JavaVersion.Java8) {
            return emptyList()
        }
        val candidates = mutableListOf(
            "$prefix/lib/jvm/${version.directoryName}",
            "$prefix/data/data/com.termux/files/usr/lib/jvm/${version.directoryName}"
        )
        return candidates
    }

    /**
     * Termux 侧被 apt/dpkg/proot 依赖的共享库清单。
     *
     * 每一项：库文件名（运行时名）→ 历史曾出现过的落点（按优先级）。
     * 前者是 Termux 标准库目录，后者是 dpkg-deb -x 解包时 compat 符号链接被覆盖后的实际落点。
     *
     * 清单由 [PACKAGED_LIBRARIES] 派生，保证「打包的库」与「修复时就位的库」永远一致，
     * 不会出现「打包了但没落盘」或「落盘了但没打包」的错位。
     *
     * `libtalloc.so.2` / `libandroid-shmem.so` 曾经因为「官方 rootfs 里没有」被排除在外，
     * 结果设备上 proot 直接链接失败、Ubuntu 容器与 Java 8 全线不可用。现在它们已随 APK
     * 打包（见 [PACKAGED_LIBRARIES] 第三批），因此这张表里也有它们 ——
     * 初始化时会照常打印「依赖已就绪: libtalloc.so.2」。
     */
    private val termuxLibraryCandidates: Map<String, List<String>> =
        PACKAGED_LIBRARIES.values.distinct().associateWith { name ->
            listOf(
                "usr/lib/$name",
                "data/data/com.termux/files/usr/lib/$name"
            )
        }

    /** 把随 APK 打包的共享库落到 Termux 能加载到的位置（幂等）。 */
    private fun provisionBundledLibraries(): List<String> {
        val prefix = installer.rootDir
        // BootstrapInstaller 会把 `data/data/com.termux/files/usr` 建成指向 rootDir 的
        // 符号链接（见 BootstrapInstaller 里创建兼容符号链接那段），于是它的 lib/
        // 就是 rootDir/lib —— 与第一个落点是同一个目录。
        //
        // 符号链接已存在时重复落盘毫无意义；而在符号链接建立**之前**执行的话，
        // 往这个路径写会把它从「链接」变成一个真实目录，回头 BootstrapInstaller
        // 还得先 deleteRecursively 再重建链接（它已经为此写了兜底逻辑）。
        // 所以：只有它确实是一个独立目录时才当作落点。
        val compatUsr = File(prefix, "data/data/com.termux/files/usr")
        val compatIsSymlink = runCatching {
            java.nio.file.Files.isSymbolicLink(compatUsr.toPath())
        }.getOrDefault(false)
        val targetDirs = buildList {
            add(File(prefix, "lib"))
            add(File(prefix, "usr/lib"))
            if (!compatIsSymlink) add(File(compatUsr, "lib"))
        }
        val written = NativeLibraryBundler.provision(bundledDir = bundledLibDir, targetDirs = targetDirs)
        // 打包的库带着 SONAME 名直接落盘，本不会缺别名；这里补的是**别处**（apt 装进来的 deb）
        // 留下的库 —— 它们的 `libX.so.2` 是符号链接，解包链路一旦丢掉链接，
        // 就会出现「apt 说装好了、命令还是 CANNOT LINK」这种最难查的状态。
        NativeLibraryBundler.ensureSonameAliases(targetDirs)
        return written
    }

    private fun repairProotLibraries(): Boolean {
        val prefix = installer.rootDir
        // 打包的库先落盘，再走下面统一的就位/汇报逻辑
        provisionBundledLibraries()

        val allReady = termuxLibraryCandidates.map { (name, relativeCandidates) ->
            val sources = relativeCandidates.map { File(prefix, it) }
            val target = File(File(prefix, "lib").apply { mkdirs() }, name)

            // 1. 先补 canonical 位置（proot 启动包装脚本的 LD_LIBRARY_PATH 首项）
            if (!target.isFile) {
                sources.firstOrNull { it.isFile }
                    ?.let { source -> runCatching { source.copyTo(target, overwrite = false) } }
            }
            // 2. 再把 canonical 副本回填到各历史落点，保证无论从哪条路径搜索都能命中
            if (target.isFile) {
                sources.filterNot { it.absolutePath == target.absolutePath }.forEach { dest ->
                    if (!dest.isFile) {
                        runCatching {
                            dest.parentFile?.mkdirs()
                            target.copyTo(dest, overwrite = false)
                        }
                    }
                }
            }

            val ready = target.isFile || sources.any { it.isFile }
            emitLog(
                if (ready) "[bootstrap] 依赖已就绪: $name"
                else "[bootstrap] 警告: 依赖 $name 未找到"
            )
            ready
        }.all { it }

        // apt-get 缺库是硬失败，必须探测出来而不是继续往下走流程
        auditTermuxLibraries()
        return allReady
    }

    /**
     * 对 Termux 侧关键命令做一次「缺库体检」。
     *
     * 背景：`F linker: CANNOT LINK EXECUTABLE ".../apt-get": library "libandroid-glob.so"
     * not found` 之后，既有兜底链路会把链接失败伪装成「权限问题」，反复输出
     * Permission denied / exec 探针 / 复制副本执行等无关诊断，把真因完全盖住。
     * 这里在 apt 真正动手之前，用 linker 自己把每个关键命令的依赖解析一遍，
     * 缺什么直接列出来 —— 结论明确，不再需要用户猜。
     *
     * ## 只警告，不拦截（重要）
     * 早期版本把这里的返回值当作**门禁**：一旦探测到任何「缺库」就 `return false`，
     * 直接放弃 apt 安装。这个设计是错的，理由是：
     *  1. 探测本身可能误报 —— 例如 bash 依赖 `libreadline.so.8`（仅 Tab 补全用），
     *     但 apt-get 根本不依赖 readline。把非关键命令的瑕疵当成致命错误，
     *     会让一个**完全正常的环境**被判为不可用。
     *  2. 探测是在 App 侧拼 LD_LIBRARY_PATH 做的静态解析，与 apt 实际执行时的
     *     环境（proot / 包装脚本 / shebang 解释器）并不完全一致，本就存在偏差。
     *  3. 代价极不对称：放行最多是 apt 自己报错（信息更准确）；拦错则整个初始化失败。
     *
     * 因此现在**永远返回 true**，仅把结果写进日志。真正的失败交给 apt 自己反馈。
     *
     * @return 恒为 true（保留返回值是为了兼容既有调用点）
     */
    private fun auditTermuxLibraries(): Boolean {
        val prefix = installer.rootDir
        val (blocking, advisory) = NativeLibraryBundler.auditIfEnabled(prefix, bundledLibDir)

        advisory.forEach { (binary, libs) ->
            // 非致命：这些命令即使缺库也不影响包管理
            emitLog("[bootstrap] 提示: $binary 依赖 ${libs.joinToString()}（非关键，不影响安装）")
        }
        blocking.forEach { (binary, libs) ->
            emitLog("[bootstrap] 警告: $binary 可能缺少 ${libs.joinToString()}")
        }
        if (blocking.isNotEmpty()) {
            emitLog("[bootstrap] 将继续尝试安装；若确实失败，请把上面的清单连同 apt 报错一起反馈")
        }
        return true
    }

    /**
     * 在 apt 确实失败后，给出「是否因为缺库」的明确结论。
     *
     * 与 [auditTermuxLibraries] 的区别：那个是**事前**猜测（只写日志），
     * 这个是**事后**归因 —— 已经确认失败了，此时把缺库清单摆出来才有意义，
     * 也不会再有机会误伤正常流程。
     */
    private fun reportLinkingFailure() {
        runCatching {
            val missing = NativeLibraryBundler.auditTermuxBinaries(installer.rootDir, bundledLibDir)
            if (missing.isEmpty()) return
            emitLog("[apt] 结论: 以下命令的依赖库在设备上找不到，这通常就是失败原因：")
            missing.forEach { (binary, libs) ->
                emitLog("[apt]   - $binary 需要 ${libs.joinToString()}")
            }
            emitLog("[apt] 这些是随 APK 打包的补充库；若清单里有打包清单未覆盖的项，请反馈这一行")
        }.onFailure { emitLog("[apt] 缺库归因失败: ${it.message}") }
    }

    /**
     * proot-distro sanitizes LD_LIBRARY_PATH before it invokes proot.  The app's
     * rootfs is relocated, so proot cannot rely on Termux's normal ELF rpath.
     * Keep the actual binary aside and expose a tiny launcher which restores the
     * required library path for both direct and proot-distro invocations.
     */
    private fun installProotLauncher() {
        val prefix = installer.rootDir.absolutePath
        val launcher = File(prefix, "bin/proot")
        val binary = File(prefix, "bin/proot.bin")
        if (!launcher.isFile && !launcher.exists()) return

        // A prior install may replace the launcher with the package's ELF again.
        // Rename only ELF files; never rename our shell launcher on repeated runs.
        val isElf = runCatching {
            launcher.inputStream().use { input ->
                val header = ByteArray(4)
                input.read(header) == 4 && header.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            }
        }.getOrDefault(false)
        if (isElf) {
            if (binary.exists()) binary.delete()
            if (!launcher.renameTo(binary)) {
                emitLog("[bootstrap] 警告: 无法创建 proot 启动包装")
                return
            }
        }
        if (!binary.isFile) return
        binary.setExecutable(true, false)
        val compatUsrLib = "$prefix/data/data/com.termux/files/usr/lib"
        // 打包的库目录一并注入：修复过旧版本的设备上 usr/lib 可能仍缺 libandroid-glob.so
        val libPath = listOfNotNull(
            "$prefix/lib",
            "$prefix/usr/lib",
            compatUsrLib,
            bundledLibDir,
            "/system/lib64"
        ).joinToString(":")
        launcher.writeText(
            "#!/system/bin/sh\n" +
                "export PROOT_TMP_DIR='$prefix/tmp'\n" +
                "export TMPDIR='$prefix/tmp'\n" +
                "export PROOT_LOADER='$prefix/libexec/proot/loader'\n" +
                "export PROOT_LOADER_32='$prefix/libexec/proot/loader32'\n" +
                "export LD_LIBRARY_PATH='$libPath'\n" +
                "exec '$binary' \"${'$'}@\"\n"
        )
        launcher.setExecutable(true, false)
        emitLog("[bootstrap] 已固定 proot 动态库启动环境")
    }

    private fun verifyProot(): Boolean {
        val code = execOnce("proot", "--version")
        if (code == 0) {
            emitLog("[bootstrap] proot 启动校验通过")
        }
        return code == 0
    }

    /**
     * 决定性测试：把系统原生二进制复制到 app 数据目录再 exec。
     *  - 复制副本可执行 → app_data 执行未被设备禁止，问题在 bootstrap ELF（页大小/加载器）；
     *  - 复制副本同样 EACCES → 设备禁止执行 app 数据目录原生程序（SELinux/策略层面，需换机制或 targetSdk）。
     */
    fun probeSystemCopyExec(): String {
        val src = File("/system/bin/toybox")
        if (!src.isFile) return "系统二进制 /system/bin/toybox 不存在"
        val copy = File(installer.rootDir, "tmp/system-toybox-copy")
        return try {
            copy.parentFile?.mkdirs()
            src.copyTo(copy, overwrite = true)
            copy.setExecutable(true, false)
            val pb = ProcessBuilder(copy.absolutePath, "--help").redirectErrorStream(true).start()
            val out = pb.inputStream.bufferedReader().use { it.readText() }.take(120)
            val done = pb.waitFor(3, TimeUnit.SECONDS)
            if (!done) pb.destroyForcibly()
            val tag = if (done && pb.exitValue() == 0) "可执行" else "启动异常(exit=${if (done) pb.exitValue() else "timeout"})"
            "系统二进制副本 $tag | $out"
        } catch (e: Exception) {
            "系统二进制副本 exec 失败: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { copy.delete() }
        }
    }

    /** 设备内存页大小（16KB 页设备无法执行 4KB 页对齐的旧 ELF） */
    fun pageSize(): String = try {
        val pb = ProcessBuilder("/system/bin/getconf", "PAGE_SIZE").redirectErrorStream(true).start()
        val out = pb.inputStream.bufferedReader().use { it.readText() }.trim()
        if (pb.waitFor(2, TimeUnit.SECONDS)) out else "未知"
    } catch (_: Exception) { "未知" }

    /**
     * 直接 exec 探测，捕获精确的 IOException 信息（含 errno），用于区分
     * Permission denied(13) / No such file(2) / Exec format(8) 等不同拒绝原因。
     */
    fun probeExecErrno(path: String): String {
        return try {
            val pb = ProcessBuilder(path, "--version").redirectErrorStream(true).start()
            val out = pb.inputStream.bufferedReader().use { it.readText() }.take(200)
            val code = pb.waitFor(3, TimeUnit.SECONDS)
            if (!code) pb.destroyForcibly()
            "exec 成功 (exit=${if (code) pb.exitValue() else "timeout"}) ${out.trim()}"
        } catch (e: Exception) {
            "exec 失败: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * 绕过脚本自身 exec 位/标签限制，直接用其 shebang 解释器执行（如 perl apt-get）。
     * 适用于脚本 exec 被拒（Permission denied）但解释器可正常执行的环境。
     */
    fun execScriptViaInterpreter(script: File, vararg args: String): Int {
        val head = try { script.bufferedReader().use { it.readLine() } } catch (_: Exception) { null } ?: return 1
        if (!head.startsWith("#!")) return 1
        val interpLine = head.removePrefix("#!").trim()
        val interpPath = interpLine.split(Regex("\\s+")).firstOrNull() ?: return 1
        val prefix = installer.rootDir.absolutePath
        val interp = when {
            interpPath.startsWith(prefix) -> File(interpPath)
            interpPath.startsWith("/") -> File(prefix, interpPath.removePrefix("/"))
            else -> File(prefix, "usr/bin/$interpPath")
        }
        if (interp.exists() && !interp.canExecute()) runCatching { interp.setExecutable(true, false) }
        if (interp.exists()) {
            emitLog("[apt] 改用解释器 ${interp.name} 直接执行 apt-get（绕过脚本 exec 限制）")
            return execOnce(interp.absolutePath, script.absolutePath, *args)
        }
        emitLog("[apt] 解释器 ${interpPath} 不存在，无法绕过执行")
        return 1
    }

    fun execOnce(vararg command: String, env: Map<String, String> = emptyMap()): Int {
        // Some older installations reached apt through a direct internal path
        // that did not carry -y. Keep the shared apt config non-interactive so
        // a closed stdin cannot turn a valid install into "Abort".
        if (command.firstOrNull()?.let { File(it).name } in setOf("apt", "apt-get", "pkg")) {
            ensureAptConfigs()
        }
        return executor.execOnce(*command, env = env)
    }

    fun execOnceWithTimeout(
        timeoutMs: Long,
        vararg command: String,
        env: Map<String, String> = emptyMap()
    ): Int = executor.execOnceWithTimeout(timeoutMs, *command, env = env)


    /**
     * apt-get 执行前强制自愈 + 校验（幂等，毫秒级）。
     *
     * 背景：安装 Java 等依赖时偶发 `/system/bin/sh: .../bin/apt-get: Permission denied`（退出码 126）。
     * 多为 bin/apt-get 链接目标丢失 exec 位、链接失效，或 shebang 解释器（apt 为 perl 脚本）
     * 缺失/无 exec 位；旧版本环境还可能是 shebang 仍指向 /data/data/com.termux 被系统拒绝。
     *
     * 修复：强制重跑 归位+exec位+shebang 改写 → 校验并重建 bin/apt-get 链接 →
     * 校验解释器存在且可执行 → 仍失败时输出 diagnoseCommand("apt-get") 便于定位。
     */
    fun ensureAptWorking(): Boolean {
        val prefix = installer.rootDir.absolutePath
        // 1. 强制自愈（幂等；顺序：先归位再补权限与 shebang）
        runCatching { fixUsrBin() }
        ensureRootfsExecutable()
        fixScriptsOnce()
        // 2. 校验并重建 bin/apt-get 链接（目标缺失时从 usr/bin 重建）
        val link = File(prefix, "bin/apt-get")
        val real = File(prefix, "usr/bin/apt-get")
        if (!link.exists() && real.isFile) {
            runCatching {
                java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get("../usr/bin/apt-get"))
                emitLog("[apt] 已重建 bin/apt-get 链接")
            }
        }
        // 3. 确保 apt-get 本体与链接目标可执行
        listOf(link, real).forEach { f ->
            if (f.exists() && !f.canExecute()) runCatching { f.setExecutable(true, false) }
        }
        // 4. 校验 shebang 解释器（缺失时尝试从 compat 路径补全）
        ensureScriptInterpreter(real)
        // 5. 先排掉「缺库」这个更靠前的原因：missing lib 会让二进制在任何权限检查前
        //    就被 linker 拒绝，现象酷似 Permission denied，会把后续兜底链路带偏
        val missingLibs = if (real.isFile) {
            NativeLibraryBundler.missingLibrariesFor(real, bundledLibDir)
        } else emptyList()
        // 6. 终检：跟随链接判定可执行
        val ok = link.canExecute()
        if (!ok) {
            if (missingLibs.isNotEmpty()) {
                emitLog("[apt] 根因: apt-get 缺少动态库 ${missingLibs.joinToString()}（非权限问题）")
            }
            emitLog("[apt] apt-get 仍不可执行，诊断如下：\n" + diagnoseCommand("apt-get"))
        } else if (missingLibs.isNotEmpty()) {
            // canExecute 只看权限位，缺库要到 exec 时才炸，这里提前告警
            emitLog("[apt] 警告: apt-get 权限正常但缺少动态库 ${missingLibs.joinToString()}，执行会失败")
        }
        return ok
    }

    /** 解析脚本 shebang 并确保解释器存在且可执行（缺失时尝试从 compat 路径补全到 usr/bin） */
    private fun ensureScriptInterpreter(script: File) {
        if (!script.exists() || !script.isFile) return
        val head = try { script.bufferedReader().use { it.readLine() } } catch (_: Exception) { null } ?: return
        if (!head.startsWith("#!")) return
        val interp = head.removePrefix("#!").trim().split(Regex("\\s+")).firstOrNull() ?: return
        val prefix = installer.rootDir.absolutePath
        val name = File(interp).name
        val candidates = listOfNotNull(
            if (interp.startsWith(prefix)) File(interp) else null,
            if (!interp.startsWith(prefix)) File(prefix, interp.removePrefix("/")) else null,
            File(prefix, "usr/bin/$name"),
            File(prefix, "bin/$name"),
            File(prefix, "data/data/com.termux/files/usr/bin/$name")
        ).distinct()
        val found = candidates.firstOrNull { it.exists() }
        if (found != null) {
            if (!found.canExecute()) runCatching { found.setExecutable(true, false) }
            return
        }
        // 解释器缺失：从 compat 补全到 usr/bin（保留 exec 位）
        val compat = File(prefix, "data/data/com.termux/files/usr/bin/$name")
        val dest = File(prefix, "usr/bin/$name")
        if (compat.isFile && !dest.exists()) {
            runCatching { compat.copyTo(dest); dest.setExecutable(true, false) }
        }
        if (!File(prefix, "usr/bin/$name").exists()) {
            emitLog("[apt] shebang 解释器 $interp 缺失（usr/bin/$name 不存在），apt-get 可能仍无法执行")
        }
    }

    /** Refresh the package index and verify every requested package before apt install. */
    fun prepareAptPackages(vararg packages: String): Boolean {
        if (!isReady()) return false
        repairInstalledCommands()
        // 缺库体检：只写日志，不拦截。
        // 早期版本这里会 return false 中止安装，导致「bash 缺 readline」这种
        // 无关紧要的瑕疵把整个初始化搞挂。现在先补库、再记录，然后一切照常往下走。
        auditTermuxLibraries()
        provisionBundledLibraries()
        // apt-get 偶发 Permission denied（126）：先强制自愈并校验 bin/usr/bin 下的可执行位与 shebang 解释器
        if (!ensureAptWorking()) return false
        var update = execOnce(
            "apt-get", "-o", "DPkg::Lock::Timeout=60", "update",
            "--allow-insecure-repositories", "-y"
        )
        if (update != 0) {
            // 自愈后重试（dpkg/软件源操作可能刚改动了命令权限）
            emitLog("[apt] 软件源更新失败，正在自愈后重试...")
            ensureRootfsExecutable()
            fixScriptsOnce()
            update = execOnce(
                "apt-get", "-o", "DPkg::Lock::Timeout=60", "update",
                "--allow-insecure-repositories", "-y"
            )
            if (update != 0) {
                // 兜底：绕过 bin/ 链接，直接用 usr/bin 真实路径执行（bin 链接异常时可恢复）
                emitLog("[apt] 仍失败，改用 usr/bin/apt-get 真实路径重试...")
                val real = java.io.File(installer.rootDir, "usr/bin/apt-get")
                if (real.isFile || real.canExecute()) {
                    update = execOnce(
                        real.absolutePath, "-o", "DPkg::Lock::Timeout=60", "update",
                        "--allow-insecure-repositories", "-y"
                    )
                }
            }
            if (update != 0) {
                // 兜底2：直接用 shebang 解释器（perl/bash 等）执行，绕过脚本 exec 位/SELinux 限制
                val real2 = java.io.File(installer.rootDir, "usr/bin/apt-get")
                if (real2.isFile) {
                    update = execScriptViaInterpreter(
                        real2, "-o", "DPkg::Lock::Timeout=60", "update",
                        "--allow-insecure-repositories", "-y"
                    )
                }
            }
            if (update != 0) {
                // 兜底3：精确探测 exec 失败原因（errno）
                emitLog("[apt] 探测: " + probeExecErrno(java.io.File(installer.rootDir, "bin/apt-get").absolutePath))
                // 兜底4：复制到全新 inode（新 SELinux 标签/去掉可能损坏的元数据）后执行
                val copy = java.io.File(installer.rootDir, "tmp/apt-get-copy")
                runCatching {
                    java.io.File(installer.rootDir, "usr/bin/apt-get").copyTo(copy, overwrite = true)
                    copy.setExecutable(true, false)
                }
                if (copy.isFile && copy.canExecute()) {
                    emitLog("[apt] 尝试复制副本执行...")
                    update = execOnce(
                        copy.absolutePath, "-o", "DPkg::Lock::Timeout=60", "update",
                        "--allow-insecure-repositories", "-y"
                    )
                }
            }
            if (update != 0) {
                // 兜底5：尝试 apt 原生二进制（apt-get 的兄弟命令）
                emitLog("[apt] 尝试 apt 命令...")
                update = execOnce(
                    "apt", "-o", "DPkg::Lock::Timeout=60", "update",
                    "--allow-insecure-repositories", "-y"
                )
            }
            if (update != 0) {
                // 决定性诊断：页大小 + 系统二进制副本测试（区分设备策略 vs bootstrap ELF 问题）
                emitLog("[apt] 内存页大小: " + pageSize() + " (16KB 页设备无法执行 4KB 对齐的旧版 Termux ELF)")
                emitLog("[apt] 系统二进制副本测试: " + probeSystemCopyExec())
            }
        }
        if (update != 0) {
            emitLog("[apt] 软件源更新失败，未继续安装；请切换软件源或检查网络后重试")
            emitLog(diagnoseCommand("apt-get"))
            // 链接失败（而非网络/权限）时，把缺失库清单直接摆在最后，避免被淹没在上面一长串兜底日志里
            reportLinkingFailure()
            return false
        }
        val missing = packages.filter {
            execOnce("apt-cache", "show", it) != 0
        }
        if (missing.isNotEmpty()) {
            emitLog("[apt] 软件源索引中找不到：${missing.joinToString()}；未继续安装，请切换软件源后重试")
            return false
        }
        return true
    }

    /**
     * 执行 Termux shell 命令，输出逐行回调（Termux 终端面板专用，不与 MC 日志混流）。
     * 命令通过 sh -c 执行（含 Termux 环境 PATH/LD_LIBRARY_PATH），阻塞至命令结束。
     *
     * 注意：内层解释器必须显式用 /system/bin/sh，不能依赖 PATH 解析 "sh"——
     * PATH 中的 sh 会命中 $PREFIX/bin/sh（Termux bash ELF），bash 依赖的共享库
     * （readline/ncurses 等）不在 LD_LIBRARY_PATH 内会导致启动失败（退出码 126）。
     * MC 服务器启动链（startMc）从不经过 Termux bash，因此能正常运行。
     */
    fun execTermux(command: String, onLine: (String) -> Unit): Int =
        executor.execWithOutput("/system/bin/sh", "-c", command, onLine = onLine)

    fun sendTermuxInput(input: String): Boolean = executor.sendInteractiveInput(input)

    /**
     * 修复 rootfs 命令可执行权限（幂等，毫秒级）。
     *
     * 背景：Termux bootstrap zip 解压时（extractZipToDir）只对 bin/、libexec/ 前缀及
     * 少量文件名设置 exec 位，usr/bin/ 下的真实脚本/二进制（pkg、apt 等）初始无 exec 位；
     * 依赖 bin/ 符号链接 chmod 跟随的链路在部分环境下失效，导致执行时报
     * "Permission denied"（退出码 126）。
     *
     * 修复：遍历 usr/bin、bin、libexec、lib/apt/methods 下所有文件直接 setExecutable。
     * 对 bin/ 下的符号链接，java.io.File.setExecutable 跟随链接修改目标文件权限。
     * 每次应用启动调用（幂等，已装环境也生效），不依赖重新初始化。
     *
     * @return 本次修复的文件数
     */
    fun ensureRootfsExecutable(): Int {
        val prefix = installer.rootDir.absolutePath
        if (!File(prefix).isDirectory) return 0
        val dirs = listOf("usr/bin", "bin", "libexec", "lib/apt/methods")
        var fixed = 0
        dirs.forEach { rel ->
            File(prefix, rel).listFiles()?.forEach { f ->
                if (f.isFile && !f.canExecute()) {
                    try {
                        if (f.setExecutable(true, false)) fixed++
                    } catch (e: Exception) {
                        Log.w(TAG, "ensureRootfsExecutable: chmod failed ${f.absolutePath}: ${e.message}")
                    }
                }
            }
        }
        if (fixed > 0) {
            Log.i(TAG, "ensureRootfsExecutable: fixed $fixed files")
            installer.onLog?.invoke("[bootstrap] 修复 $fixed 个命令可执行权限")
        }
        return fixed
    }

    /** 组合修复：命令可执行位 + 脚本路径（启动时幂等调用，每次启动全量自愈） */
    fun fixRootfsPermissions(): Int {
        // 顺序关键：先归位 compat（fixUsrBin）再重建 java wrapper（fixJavaSymlinks），
        // 否则 apt 装完 openjdk 后 wrapper 指向归位前的 jvm 路径 → libjli.so not found 崩溃；
        // ensureAptConfigs 放最后（归位可能动到 etc/，最后统一重建 apt 配置）
        val n = fixDpkgWrapper() + fixUsrBin() + ensureRootfsExecutable() +
            fixScriptsOnce() + fixAptSources() + ensureAptConfigs()
        try {
            fixJavaSymlinks()
        } catch (e: Exception) {
            Log.w(TAG, "fixRootfsPermissions: fixJavaSymlinks failed: ${e.message}")
        }
        return n
    }

    /** Repair commands unpacked by dpkg after the bootstrap startup pass. */
    fun repairInstalledCommands(): Int =
        fixUsrBin() + fixScriptsOnce() + ensureRootfsExecutable()

    /**
     * 升级已装环境的 dpkg 包装脚本到新版（幂等）。
     * 新版 wrapper 在解压后：归位 compat 链接 + 补 bin 链接 + --configure 时改写新装脚本路径。
     * 旧版（无 compat 归位逻辑）→ 用 ensureDpkgWrapper() 重写。
     */
    /**
     * 启动服务端前的幂等环境修复，只处理缺失或损坏的运行时文件。
     */
    fun autoRepairRuntime(
        javaVersion: JavaVersion,
        needsFonts: Boolean,
        vendor: JavaVendor = JavaVendor.Termux
    ): Int {
        // FCL 来源不需要也不能走这套修复：它的 JRE 是一整包解压出来的完整运行时，
        // 不存在「缺 libjli、缺字体缓存、靠 apt 补装」这些状态，跑一遍只会白折腾，
        // 更糟的是 apt 在服务端运行时还可能把 JVM 弄崩（apt 与 MC 共用 rootfs）。
        if (vendor == JavaVendor.Fcl) return 0
        if (!isReady()) return 0
        val prefix = installer.rootDir
        // 所有「来宾容器」链路（Termux+Java8、Proot 全部版本）都必须走容器内修复。
        //
        // 早先这里只判 `javaVersion == Java8`：选中 Proot 来源 + Java 17 时会掉进
        // 下面的宿主修复分支 —— 对着**宿主** prefix 跑 apt 补 fontconfig、
        // 检查宿主 `etc/fonts/fonts.conf`，而来宾用的是容器里的那一套。
        // 结果是容器缺字体时报告「已就绪」，或往宿主装一堆容器根本用不到的东西。
        if (isProotGuestRuntime(vendor, javaVersion)) {
            val profile = resolveProotProfile(vendor, javaVersion)
            val ready = isContainerJavaReady(profile) || runBlocking { installProotJava(profile) }
            if (!ready) {
                emitLog("[repair] ${profile.displayName} 运行环境不可用")
                return 0
            }
            val fontsReady = !needsFonts || runUbuntu(
                "test -f /etc/fonts/fonts.conf && test -x /usr/bin/fc-cache",
                60_000,
                profile = profile
            ) == 0
            if (!fontsReady) {
                emitLog("[repair] 正在补全 ${profile.displayName} 字体运行库...")
                val code = runUbuntu(
                    "export DEBIAN_FRONTEND=noninteractive; " +
                        "apt-get update -o Acquire::Retries=2 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 && " +
                        "apt-get install -y ca-certificates fontconfig fonts-dejavu-core && fc-cache -f",
                    300_000,
                    profile = profile
                )
                if (code != 0) emitLog("[repair] ${profile.displayName} 字体运行库修复失败，将继续尝试无图形模式启动")
            }
            return 0
        }
        listOf(
            File(prefix, "tmp"),
            File(prefix, "home"),
            File(prefix, "usr/bin"),
            File(prefix, "etc/fonts"),
            File(prefix, "var/lib/dpkg"),
            File(prefix, "var/cache/apt/archives")
        ).forEach { it.mkdirs() }

        var repaired = fixDpkgWrapper() + fixUsrBin() + ensureRootfsExecutable()
        repaired += fixScriptsOnce() + fixAptSources() + ensureAptConfigs()
        repaired += ensureJvmComplete(javaVersion)
        fixJavaSymlinks(javaVersion)

        if (needsFonts) {
            if (!fontRuntimeReady(javaVersion)) {
                emitLog("[repair] 正在补齐字体运行库...")
                if (!prepareAptPackages("fontconfig", "ttf-dejavu")) return repaired
                execOnce(
                    "apt-get", "-o", "DPkg::Lock::Timeout=60", "install",
                    "--allow-unauthenticated", "-y", "fontconfig", "ttf-dejavu"
                )
                repaired += fixUsrBin() + ensureRootfsExecutable()
            }
            if (repairFontRuntime()) repaired++
            else emitLog("[repair] 字体缓存生成失败，将继续使用无图形模式启动")
        }

        if (repaired > 0) emitLog("[repair] 自动修复完成，共处理 $repaired 项")
        return repaired
    }

    fun fixDpkgWrapper(): Int {
        val dpkg = File(installer.rootDir, "bin/dpkg")
        if (!dpkg.exists()) return 0
        val isV2 = try {
            dpkg.readText().contains("commands linked (fixUsrBin will relocate)")
        } catch (_: Exception) { false }
        return if (!isV2) {
            installer.ensureDpkgWrapper()
            Log.i(TAG, "fixDpkgWrapper: upgraded dpkg wrapper to v2")
            1
        } else 0
    }

    /**
     * 修复脚本 shebang 解释器路径 + 内容中硬编码的 Termux 绝对路径（幂等，单次遍历）。
     *
     * 背景：Termux 官方打包的脚本（pkg、apt-get、termux-* 等）shebang 与内容里都硬编码
     * /data/data/com.termux/files/usr/...（如 pkg 第 11 行调用 termux-setup-package-manager）。
     * 这些路径指向其他 app（com.termux）目录，Minexa 进程跨 app 执行被拒 →
     * "Permission denied"（退出码 126 / 1）。
     *
     * 修复：把 shebang（第一行）与内容中的 Termux 路径改写为自身 rootfs 路径 $PREFIX；
     * /usr/bin/env 形式兜底为自身 coreutils env。只处理以 #! 开头的文本脚本（跳过 ELF）。
     *
     * 性能：单次遍历 + 只读前 4KB 预检（已修复文件不命中即跳过全量 IO），
     * 替代此前 fixScriptShebangs + fixScriptPaths 两次遍历的重复读写。
     *
     * @return 本次修复的脚本数
     */
    fun fixScriptsOnce(): Int {
        val prefix = installer.rootDir.absolutePath
        if (!File(prefix).isDirectory) return 0
        val termuxUsr = "/data/data/com.termux/files/usr"
        var fixed = 0
        listOf(
            "usr/bin", "bin", "libexec", "lib/apt/methods",
            "usr/lib/apt/methods", "etc/profile.d", "etc/apt/apt.conf.d"
        ).forEach { rel ->
            File(prefix, rel).listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                try {
                    val len = f.length()
                    val buf = ByteArray(minOf(len, 4096L).toInt())
                    java.io.RandomAccessFile(f, "r").use { raf -> raf.readFully(buf) }
                    val preview = String(buf, Charsets.UTF_8)
                    val head = preview.substringBefore("\n")
                    if (!head.startsWith("#!")) return@forEach
                    // shebang 改写
                    var newHead = head
                    if (head.contains(termuxUsr)) {
                        newHead = head
                            .replace("$termuxUsr/bin/", "$prefix/usr/bin/")
                            .replace("$termuxUsr/lib/", "$prefix/usr/lib/")
                            .replace(termuxUsr, prefix)
                    }
                    if (newHead.contains("/usr/bin/env ")) {
                        newHead = newHead.replace("#!/usr/bin/env ", "#!$prefix/usr/bin/env ")
                    }
                    // 内容预检：前 4KB 是否含 Termux 路径（未命中跳过全量 IO）
                    val needFull = preview.contains(termuxUsr)
                    if (newHead != head || needFull) {
                        val content = f.readText()
                        var newContent = content
                        if (needFull) newContent = content.replace(termuxUsr, prefix)
                        if (newHead != head) newContent = newContent.replaceFirst(head, newHead)
                        if (newContent != content) {
                            f.writeText(newContent)
                            f.setExecutable(true, false)
                            fixed++
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        if (fixed > 0) {
            Log.i(TAG, "fixScriptsOnce: fixed $fixed scripts")
            installer.onLog?.invoke("[bootstrap] 修复 $fixed 个脚本路径")
        }
        return fixed
    }

    /**
     * 已装环境 apt 源 http → https（幂等）。
     * 安装时 sources.list 已用 https（postSetup 重写），此处兜底老环境：
     * 把 sources.list 中 deb http:// 全部替换为 deb https://。
     */
    fun fixAptSources(): Int {
        val prefix = installer.rootDir.absolutePath
        val f = File(prefix, "etc/apt/sources.list")
        if (!f.exists()) return 0
        return try {
            val content = f.readText()
            val newContent = content.replace("deb http://", "deb https://")
            if (newContent != content) {
                f.writeText(newContent)
                Log.i(TAG, "fixAptSources: http -> https")
                1
            } else 0
        } catch (e: Exception) {
            Log.w(TAG, "fixAptSources: ${e.message}")
            0
        }
    }

    /**
     * 应用下载源偏好（bootstrap rootfs 下载用）。
     *
     * 非 Auto 时该源会被优先尝试，其余按枚举顺序回退；下次 bootstrap 下载生效。
     * 真身存在 [BootstrapInstaller.preferredMirror]，这里只是给它一个带类型的方法入口。
     */
    fun setDownloadMirror(mirror: DownloadMirror) {
        installer.preferredMirror = mirror
    }

    /** Applies the selected domestic APT mirror immediately for later Java/dependency installs. */
    fun setAptMirror(mirror: AptMirror) {
        if (!installer.isReady()) return
        val effective = if (mirror == AptMirror.Official) AptMirror.Tuna else mirror
        File(installer.rootDir, "etc/apt/sources.list").apply {
            parentFile?.mkdirs()
            writeText("deb ${effective.url} stable main\n")
        }
        emitLog("[apt] 已切换至 ${effective.displayName}")
    }

    /**
     * 补全 usr/bin 缺失的解释器（幂等）。
     *
     * 背景：Termux bootstrap 解压后，bin/ 下脚本的 shebang 指向 $PREFIX/usr/bin/bash 等
     * 真实解释器；但部分环境下 usr/bin/ 真实文件缺失（usr/bin/pkg、usr/bin/bash 不存在），
     * 而真实文件落在 compat 路径 $PREFIX/data/data/com.termux/files/usr/bin/（Termux
     * 完整目录结构）——脚本 execve 解释器时 ENOENT → "No such file or directory"(126)。
     *
     * 修复：若 usr/bin 缺文件而 compat 路径有对应文件，复制补全（含 exec 位）。
     * 只补缺失文件，不覆盖已有内容。
     *
     * @return 本次补全的文件数
     */
    fun fixUsrBin(): Int {
        val prefix = installer.rootDir.absolutePath
        if (!File(prefix).isDirectory) return 0
        var fixed = 0
        val usrBin = File(prefix, "usr/bin")
        val binDir = File(prefix, "bin")
        val compatDir = File(prefix, "data/data/com.termux/files")
        val compatUsr = File(compatDir, "usr")
        val compatUsrBin = File(compatUsr, "bin")

        // 1. compat usr 若是真实目录（链接被 dpkg 覆盖/失效）→ 安全移动到 rootfs 对应位置
        //    再重建符号链接。用 rename（同分区元数据操作，毫秒级）而非复制。
        //    安全关键：必须用 NOFOLLOW_LINKS 判断目录（File.isDirectory/walkTopDown 会
        //    跟随符号链接，compat 若是链接会遍历整个 rootfs 并误删/误移文件——
        //    曾导致 etc/apt/apt.conf 等配置丢失、usr/bin 大量缺失）。
        val compatIsLink = try {
            java.nio.file.Files.isSymbolicLink(compatUsr.toPath())
        } catch (_: Exception) { false }
        if (compatUsr.exists() && !compatIsLink && isRealDir(compatUsr)) {
            Log.w(TAG, "fixUsrBin: compat usr is real dir, moving then relinking")
            // 归位前清理占用 jvm 的孤儿 MC 进程（服务器运行中杀后台/覆盖安装后，
            // 旧 java 进程仍 mmap jvm 文件，此时移动 jvm 会导致 libjli.so 缺失）
            killOrphanMcProcess(null)
            try {
                // 特殊处理 jvm：目标不存在**或损坏**（libjli.so/libjava.so 缺失）时，
                // 用 compat 的完整 jvm 整体原子替换（先删目标再 rename，绝不逐文件）；
                // 目标完整则跳过——避免反复移动 jvm 导致 libjli.so 缺失
                val compatJvm = File(compatUsr, "lib/jvm")
                val prefixJvm = File(prefix, "lib/jvm")
                if (isRealDir(compatJvm) && !jvmRootHasCompleteJava(prefixJvm)) {
                    prefixJvm.deleteRecursively()
                    prefixJvm.parentFile?.mkdirs()
                    runCatching { compatJvm.renameTo(prefixJvm) }
                        .onSuccess { Log.i(TAG, "fixUsrBin: jvm atomically replaced at $prefixJvm") }
                }
                // 只处理 compat 下的已知子目录，手动递归移动（不跟随链接）
                listOf("bin", "lib", "usr", "etc", "share", "include", "libexec", "opt", "var", "libexec").forEach { sub ->
                    val srcSub = File(compatUsr, sub)
                    if (isRealDir(srcSub)) {
                        moveTreeInto(srcSub, File(prefix, sub))
                    }
                }
                // 补可执行位（rename 不改变权限，dpkg-deb -x 解压文件权限来自默认）
                listOf("bin", "usr/bin", "libexec", "lib/apt/methods", "usr/lib/apt/methods").forEach { rel ->
                    File(prefix, rel).listFiles()?.forEach { it.setExecutable(true, false) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fixUsrBin: move compat tree failed: ${e.message}")
            }
            try {
                compatUsr.deleteRecursively()
                compatDir.mkdirs()
                android.system.Os.symlink(prefix, compatUsr.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "fixUsrBin: relink compat failed: ${e.message}")
            }
        }

        // 2+3. 同步 compat 缺失文件 + 补 bin 链接（不归位，命令前自愈可用）
        fixed += syncCompatAndLinks()

        if (fixed > 0) {
            installer.onLog?.invoke("[bootstrap] 补全 $fixed 个缺失命令/链接")
        }
        return fixed
    }

    /**
     * 轻量自愈（命令执行前调用）：从 compat 同步缺失命令 + 为 usr/bin 补 bin 链接。
     * **不触发归位**（不动 jvm/大目录）——避免服务器运行中移动 jvm 导致 libjli.so 缺失。
     */
    private fun syncCompatAndLinks(): Int {
        val prefix = installer.rootDir.absolutePath
        var fixed = 0
        val usrBin = File(prefix, "usr/bin")
        val binDir = File(prefix, "bin")
        val compatUsrBin = File(prefix, "data/data/com.termux/files/usr/bin")
        // 从 compat（链接 → usr/bin）同步缺失文件到 usr/bin（兜底，只补缺失不覆盖）
        usrBin.mkdirs()
        if (compatUsrBin.isDirectory) {
            compatUsrBin.listFiles()?.forEach { src ->
                val dst = File(usrBin, src.name)
                if (src.isFile && !dst.exists()) {
                    try {
                        src.copyTo(dst)
                        dst.setExecutable(true, false)
                        fixed++
                        Log.i(TAG, "syncCompatAndLinks: copied ${src.name} -> usr/bin/")
                    } catch (e: Exception) {
                        Log.w(TAG, "syncCompatAndLinks: copy ${src.name} failed: ${e.message}")
                    }
                }
            }
        }
        // 为 usr/bin 下每个命令补 bin/ 符号链接：dpkg-wrapper 跳过 configure，
        // postinst 未执行，apt 新装包（tree 等）没有 bin/ 链接 → PATH 找不到 → 127
        binDir.mkdirs()
        usrBin.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val link = File(binDir, f.name)
            try {
                val noFollowExists = java.nio.file.Files.exists(
                    link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS
                )
                if (noFollowExists) {
                    // 断链则删除重建
                    if (java.nio.file.Files.isSymbolicLink(link.toPath())) {
                        val targetOk = try {
                            val t = java.nio.file.Files.readSymbolicLink(link.toPath()).toString()
                            File(binDir, t).canonicalFile.exists()
                        } catch (_: Exception) { false }
                        if (!targetOk) {
                            link.delete()
                            android.system.Os.symlink("../usr/bin/${f.name}", link.absolutePath)
                            fixed++
                        }
                    }
                } else {
                    android.system.Os.symlink("../usr/bin/${f.name}", link.absolutePath)
                    fixed++
                    Log.i(TAG, "syncCompatAndLinks: created bin/${f.name} -> usr/bin/${f.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncCompatAndLinks: link bin/${f.name} failed: ${e.message}")
            }
        }
        return fixed
    }

    /** 用 NOFOLLOW_LINKS 判断是否为真实目录（不跟随符号链接，防遍历逃逸破坏 rootfs） */
    private fun isRealDir(f: File): Boolean = try {
        java.nio.file.Files.isDirectory(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) { false }

    /** 把 srcDir 的内容安全移动到 dstDir（递归合并，rename 同分区瞬时，不跟随符号链接） */
    private fun moveTreeInto(srcDir: File, dstDir: File) {
        dstDir.mkdirs()
        srcDir.listFiles()?.forEach { entry ->
            // 跳过符号链接条目：链接一般指向 rootfs 自身（如 etc/apt 等），
            // 移动链接并按 dst.deleteRecursively 处理会误删整个目标目录（apt.conf 丢失）
            val isLink = try {
                java.nio.file.Files.isSymbolicLink(entry.toPath())
            } catch (_: Exception) { false }
            if (isLink) return@forEach
            val dst = File(dstDir, entry.name)
            if (isRealDir(entry)) {
                // 目录：目标不存在时**整体 rename**（原子移动，防止 jvm 等几千文件
                // 逐文件移动中途中断导致部分归位 → libjli.so 缺失）；目标已存在才递归合并
                if (!dst.exists()) {
                    try {
                        if (entry.renameTo(dst)) return@forEach
                    } catch (_: Exception) {}
                }
                moveTreeInto(entry, dst)
            } else {
                // 文件：优先 rename 移动（同分区瞬时）；失败则复制兜底
                try {
                    if (dst.exists()) dst.deleteRecursively()
                    if (!entry.renameTo(dst)) {
                        entry.copyRecursively(dst, overwrite = true)
                        entry.deleteRecursively()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 重建缺失的 apt 关键配置（apt.conf / sources.list / dpkg status）。
     * 背景：fixUsrBin 误删曾导致 apt 报 "Unable to determine a suitable packaging system type"。
     * 幂等：仅当文件缺失或为空时重建。
     */
    fun ensureAptConfigs(): Int {
        val prefix = installer.rootDir.absolutePath
        var fixed = 0
        try {
            File(prefix, "etc/apt").mkdirs()
            File(prefix, "var/lib/dpkg").mkdirs()
            val aptConf = File(prefix, "etc/apt/apt.conf")
            if (!aptConf.exists() || aptConf.length() == 0L) {
                aptConf.writeText(buildString {
                    appendLine("Dir \"$prefix\";")
                    appendLine("Dir::Prefix \"$prefix\";")
                    appendLine("Dir::Etc \"$prefix/etc/apt\";")
                    appendLine("Dir::State \"$prefix/var\";")
                    appendLine("Dir::State::status \"$prefix/var/lib/dpkg/status\";")
                    appendLine("Dir::Cache \"$prefix/var/cache\";")
                    appendLine("Dir::Bin \"$prefix/bin\";")
                    appendLine("Dir::Bin::dpkg \"$prefix/bin/dpkg\";")
                    appendLine("DPkg \"$prefix/bin/dpkg\";")
                    appendLine("Acquire::AllowInsecureRepositories \"true\";")
                    appendLine("Acquire::https::Verify-Peer \"false\";")
                    appendLine("Acquire::https::Verify-Host \"false\";")
                    appendLine("APT::Get::AllowUnauthenticated \"true\";")
                    appendLine("APT::Get::Assume-Yes \"true\";")
                    appendLine("APT::Sandbox::User \"root\";")
                    appendLine("APT::Sandbox::Seccomp \"false\";")
                })
                fixed++
            }
            // Existing app versions may already have an apt.conf without the
            // non-interactive default. Add only the missing directive and
            // preserve all user/source settings.
            if (aptConf.isFile) {
                val content = aptConf.readText()
                if (!Regex("(?m)^\\s*APT::Get::Assume-Yes\\s+").containsMatchIn(content)) {
                    aptConf.appendText("\nAPT::Get::Assume-Yes \"true\";\n")
                    fixed++
                }
            }
            val sources = File(prefix, "etc/apt/sources.list")
            if (!sources.exists() || sources.length() == 0L) {
                sources.writeText("deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main\n")
                fixed++
            }
            val status = File(prefix, "var/lib/dpkg/status")
            if (!status.exists()) { status.writeText(""); fixed++ }
        } catch (e: Exception) {
            Log.w(TAG, "ensureAptConfigs: ${e.message}")
        }
        if (fixed > 0) installer.onLog?.invoke("[bootstrap] 重建 $fixed 个 apt 配置")
        return fixed
    }

    /**
     * Termux 会话命令执行前的快速自愈（幂等，毫秒级）：
     * syncCompatAndLinks（补 bin 链接 + compat 同步）+ fixScriptsOnce（新装脚本路径）。
     * 覆盖 apt/pkg 新装包命令立即可用，无需重启应用/服务器。
     * 注意：**不触发归位**（不动 jvm/大目录），避免服务器运行中移动 jvm 导致 libjli.so 缺失。
     */
    fun refreshTermux(): Int = syncCompatAndLinks() + fixScriptsOnce()

    /** 判断指定 Java 版本目录是否完整（bin/java + libjli.so 或 libjava.so 存在）。 */
    private fun isJavaComplete(version: JavaVersion): Boolean {
        if (version == JavaVersion.Java8) return java8UbuntuReady()
        return javaCandidates(version).any { path ->
        val jvmDir = File(path)
        val binJava = File(jvmDir, "bin/java")
        val libJli = File(jvmDir, "lib/jli/libjli.so")
        val libJava = File(jvmDir, "lib/libjava.so")
        binJava.isFile && (libJli.isFile || libJava.isFile)
        }
    }

    /** 用于 compat 归位：任意一个已知 JDK 完整即可避免移动整个 jvm 目录。 */
    private fun jvmRootHasCompleteJava(jvmRoot: File): Boolean =
        JavaVersion.values().filter { it != JavaVersion.Java8 }.any { version ->
            File(jvmRoot, version.directoryName).let { jvmDir ->
                File(jvmDir, "bin/java").isFile &&
                    (File(jvmDir, "lib/jli/libjli.so").isFile || File(jvmDir, "lib/libjava.so").isFile)
            }
        }

    /**
     * 启动时校验已安装的 JDK：不完整（如杀后台/覆盖安装后 libjli.so 缺失）时，
     * 仅重装对应版本。未安装的 JDK 不会被隐式安装。
     */
    fun ensureJvmComplete(version: JavaVersion): Int {
        var repaired = 0
        if (!isJavaInstalled(version) || isJavaComplete(version)) return 0

        installer.onLog?.invoke("[bootstrap] ${version.displayName} 运行环境不完整，正在修复...")
        if (version == JavaVersion.Java8) {
            val repairedJava8 = runBlocking { installProotJava(legacyJava8Profile) }
            if (repairedJava8) repaired++
            else installer.onLog?.invoke("[bootstrap] Java 8 Ubuntu ARM64 运行时自动修复失败")
            return repaired
        }
        if (!prepareAptPackages(version.packageName)) return 0
        val code = execOnce(
            "apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--reinstall",
            "--allow-unauthenticated", "-y", version.packageName
        )
        if (code == 0 && isJavaComplete(version)) {
            repaired++
            installer.onLog?.invoke("[bootstrap] ${version.displayName} 运行环境已修复")
        } else {
            installer.onLog?.invoke("[bootstrap] 警告: ${version.displayName} 运行环境修复失败")
        }
        fixJavaSymlinks()
        return repaired
    }

    /**
     * 诊断单个命令的执行环境（Termux 会话命令失败时输出，便于定位根因）。
     * 返回多行文本：环境就绪状态 + bin/usr/bin 下命令文件类型/exec位/shebang +
     * /data/data/com.termux（Termux app 数据目录）是否存在。
     */
    fun diagnoseCommand(cmd: String): String {
        val prefix = installer.rootDir.absolutePath
        val sb = StringBuilder()
        sb.append("[诊断] 环境就绪: ${installer.isReady()}")
        sb.append("\n  内存页大小: " + pageSize())
        val first = cmd.trim().split(Regex("\\s+")).firstOrNull { it.isNotEmpty() }
        if (first != null) {
            sb.append("\n  bin/$first: ${fileInfo(File(prefix, "bin/$first"))}")
            sb.append("\n  usr/bin/$first: ${fileInfo(File(prefix, "usr/bin/$first"))}")
        }
        // usr/bin 目录整体状态 + 关键解释器
        val usrBin = File(prefix, "usr/bin")
        val usrBinCount = usrBin.listFiles()?.size ?: -1
        sb.append("\n  usr/bin 文件数: ${if (usrBinCount < 0) "目录不存在" else usrBinCount}")
        listOf("bash", "sh", "perl", "python", "env", "apt-get").forEach { name ->
            val f = File(usrBin, name)
            if (f.exists()) {
                sb.append("\n  usr/bin/$name: ${fileInfo(f)}")
            } else {
                sb.append("\n  usr/bin/$name: 不存在")
            }
        }
        // compat 路径（Termux 结构真实落点）
        val compatUsrBin = File(prefix, "data/data/com.termux/files/usr/bin")
        val compatCount = compatUsrBin.listFiles()?.size ?: -1
        sb.append("\n  compat usr/bin 文件数: ${if (compatCount < 0) "不存在" else compatCount}")
        sb.append("\n  /data/data/com.termux: ${if (File("/data/data/com.termux").exists()) "存在" else "不存在"}")
        // SELinux 上下文探测（部分 ROM 对 app_data 执行限制/标签异常 → chmod 后仍 Permission denied）
        try {
            val lsZ = ProcessBuilder("/system/bin/ls", "-Z", File(prefix, "bin/apt-get").absolutePath, File(prefix, "usr/bin/apt-get").absolutePath)
                .redirectErrorStream(true).start()
            val zout = lsZ.inputStream.bufferedReader().use { it.readText() }.trim()
            if (lsZ.waitFor(3, TimeUnit.SECONDS) && zout.isNotBlank()) {
                sb.append("\n  SELinux: " + zout.replace("\n", "\n  SELinux: "))
            } else lsZ.destroyForcibly()
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun fileInfo(f: File): String {
        if (!f.exists()) return "不存在"
        val isLink = try { java.nio.file.Files.isSymbolicLink(f.toPath()) } catch (_: Exception) { false }
        val target = if (isLink) {
            " → " + (try { java.nio.file.Files.readSymbolicLink(f.toPath()).toString() } catch (_: Exception) { "?" })
        } else ""
        val exec = if (f.canExecute()) "可执行" else "无exec位"
        var shebang = ""
        // 跟随符号链接读取目标脚本首行（链接目标也是脚本时同样展示 shebang，便于定位解释器问题）
        if (f.isFile) {
            try {
                val head = f.bufferedReader().use { it.readLine() }?.take(120) ?: ""
                if (head.startsWith("#!")) shebang = " | shebang: $head"
            } catch (_: Exception) {}
        }
        val type = if (isLink) "符号链接" else if (f.isDirectory) "目录" else "文件"
        return "$type$target $exec$shebang"
    }

    fun execStream(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process =
        executor.execStream(tag, *command, env = env)

    /**
     * 启动长驻进程但不启动 reader 线程（调用方自行读取 stdout）。
     * 用于 TunnelManager 等需要解析进程输出（如提取公网 URL）的场景，
     * 避免 execStream 的 reader 线程与调用方同时读取同一 InputStream 导致数据竞争。
     */
    fun execRaw(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process =
        executor.execRaw(tag, *command, env = env)

    /**
     * 完整初始化流程：
     * 1. 下载 + 解压 Termux bootstrap rootfs
     * 2. apt-get install openjdk-25 wget curl（不再安装 tmux）
     * 3. 修复 openjdk 符号链接（dpkg-wrapper 跳过 configure 导致 post-install 未执行）
     *
     * 优化：环境已就绪时跳过依赖安装，避免后台重进应用时重复下载/安装。
     */
    suspend fun bootstrap(onProgress: (BootstrapInstaller.InstallPhase, Int) -> Unit): Boolean {
        // 记录调用前是否已就绪，用于判断是否需要重新安装依赖
        val wasReady = installer.isReady()
        val ok = installer.ensureInstalled(onProgress)
        if (!ok) return false

        // 环境之前已就绪（非首次安装），跳过依赖安装与符号链接修复
        if (wasReady) {
            installer.onLog?.invoke("[bootstrap] 环境已就绪，跳过依赖安装")
            onProgress(BootstrapInstaller.InstallPhase.DONE, 100)
            return true
        }

        onProgress(BootstrapInstaller.InstallPhase.POST_SETUP, 92)
        installer.onLog?.invoke("[bootstrap] 安装依赖包（JDK-25/wget）...")
        // 关键时序：必须先修复 rootfs 可执行权限与脚本 shebang——apt-get 是 perl 脚本，
        // shebang 硬编码指向 /data/data/com.termux/... 解释器，不修复则 apt-get 无法执行
        // （退出码 126），JDK 永远装不完，bootstrap 陷入死循环。
        fixRootfsPermissions()
        if (!prepareAptPackages("wget", "curl")) return false
        executor.execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", "wget", "curl")
        executor.execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "clean")

        // 修复 openjdk 符号链接：dpkg-wrapper 的 configure 是 no-op，
        // post-install 脚本未执行，导致 $PREFIX/bin/java 符号链接未创建
        fixJavaSymlinks()

        onProgress(BootstrapInstaller.InstallPhase.DONE, 100)
        return true
    }

    /**
     * 修复默认 OpenJDK 命令可用性（wrapper 脚本方案）。
     * Termux OpenJDK 实际安装在 $PREFIX/lib/jvm/java-xx-openjdk/，
     * 但 dpkg-wrapper 跳过了 configure，post-install 脚本未执行，
     * 需要手动在 $PREFIX/bin/ 下创建 java/javac/jar 等命令。
     *
     * 关键：不能直接 cp 复制 java 二进制到 $PREFIX/bin/，因为 java 依赖
     * libjli.so（在 jvm/lib/ 下），脱离原目录后动态链接器找不到该库。
     * 改用 wrapper 脚本：在脚本中设置 LD_LIBRARY_PATH 指向 jvm/lib/，
     * 然后 exec 原始 java 二进制，确保库依赖正确解析。
     *
     * 注意：由于 dpkg-deb -x 解压 deb 时 compat 符号链接被覆盖，
     * 文件实际落在了 $PREFIX/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/。
     * 所以需要从两个位置查找 java。默认 wrapper 选择已安装的最高版本。
     */
    fun fixJavaSymlinks(preferredVersion: JavaVersion? = null) {
        val prefix = installer.rootDir.absolutePath

        val selectedVersion = (preferredVersion?.takeIf(::isJavaComplete)
            ?: JavaVersion.values().sortedByDescending { it.ordinal }.firstOrNull(::isJavaComplete))
            ?: run {
                if (installedJavaVersions().isNotEmpty()) {
                    installer.onLog?.invoke("[bootstrap] 警告: 未找到完整的 OpenJDK 安装目录，跳过 wrapper 修复")
                }
                Log.w(TAG, "fixJavaSymlinks: no complete JDK found")
                return
            }
        if (selectedVersion == JavaVersion.Java8) {
            Log.i(TAG, "fixJavaSymlinks: Java 8 runs inside Ubuntu; no Termux wrapper needed")
            return
        }
        val jvmCandidates = javaCandidates(selectedVersion).map(::File)
        val jvmDir = jvmCandidates.firstOrNull { it.isDirectory }
        if (jvmDir == null) {
            installer.onLog?.invoke("[bootstrap] 警告: 未找到完整的 OpenJDK 安装目录，跳过 wrapper 修复")
            Log.w(TAG, "fixJavaSymlinks: jvmDir not found in candidates: ${jvmCandidates.map { it.absolutePath }}")
            return
        }
        Log.i(TAG, "fixJavaSymlinks: found jvmDir at $jvmDir")

        val jvmBinDir = File(jvmDir, "bin")
        if (!jvmBinDir.exists()) {
            installer.onLog?.invoke("[bootstrap] 警告: $jvmBinDir 不存在")
            return
        }
        val termuxBinDir = File(prefix, "bin").apply { mkdirs() }

        // 为 jvm/bin 下的命令创建 wrapper 脚本到 $PREFIX/bin/
        // 关键：不能用 cp 复制二进制！java 依赖 libjli.so（在 jvm/lib/ 下），
        // 复制后脱离原目录会导致动态链接器找不到 libjli.so。
        // 改用 wrapper 脚本：设置 LD_LIBRARY_PATH 后 exec 原始 java 二进制。
        val jvmLibDir = File(jvmDir, "lib")
        val compatUsrLib = "$prefix/data/data/com.termux/files/usr/lib"
        val libPathEntries = listOf(
            jvmLibDir.absolutePath,
            File(jvmLibDir, "server").absolutePath,
            File(jvmLibDir, "jli").absolutePath,
            compatUsrLib,
            "$prefix/lib",
            "$prefix/usr/lib",
            "/system/lib64"
        ).joinToString(":")
        val javaHome = jvmDir.absolutePath

        val commands = listOf("java", "javac", "jar", "jps", "keytool", "rmic", "rmiregistry")
        var created = 0
        val createdPaths = mutableListOf<String>()
        for (cmd in commands) {
            val target = File(jvmBinDir, cmd)
            val wrapper = File(termuxBinDir, cmd)
            if (!target.exists()) {
                Log.w(TAG, "fixJavaSymlinks: target not found: $target")
                continue
            }
            if (wrapper.exists()) {
                wrapper.delete()
            }
            try {
                val script = StringBuilder().apply {
                    append("#!/system/bin/sh\n")
                    append("export LD_LIBRARY_PATH='$libPathEntries'\n")
                    append("export JAVA_HOME='$javaHome'\n")
                    append("exec '$target' \"$@\"\n")
                }.toString()
                wrapper.writeText(script)
                createdPaths.add(wrapper.absolutePath)
                created++
            } catch (e: Exception) {
                installer.onLog?.invoke("[bootstrap] 警告: 创建 $cmd wrapper 异常: ${e.message}")
            }
        }
        // 一次性 chmod 所有 wrapper 脚本，避免逐个 fork-exec
        if (createdPaths.isNotEmpty()) {
            val chmodCmd = "chmod 755 ${createdPaths.joinToString(" ") { "'$it'" }}"
            val pb = ProcessBuilder("/system/bin/sh", "-c", chmodCmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "fixJavaSymlinks: created $created wrappers (batch chmod)")
            } else {
                installer.onLog?.invoke("[bootstrap] 警告: batch chmod 失败: $out")
                Log.w(TAG, "fixJavaSymlinks: batch chmod failed: $out")
            }
        }
        installer.onLog?.invoke("[bootstrap] ${selectedVersion.displayName} wrapper 脚本已创建完成: $created 个命令已就绪")
        Log.i(TAG, "fixJavaSymlinks: created $created wrappers in $termuxBinDir")
    }

    /**
     * 清理占用指定服务器目录的孤儿 java/MC 进程（app 重启后旧 MC 进程成为孤儿，
     * 仍占着 world/session.lock → 新实例启动报 "already locked"）。
     * 只处理同 uid 的 java 进程且 cmdline 匹配该服务器目录或 MC 启动参数。
     *
     * ## 绝不杀「我们自己正在跟踪的那个进程」
     *
     * 这个函数是给**孤儿**用的，但 [serverDir] 为 null 时的判据退化成
     * 「cmdline 含 java && 含 .jar && 含 nogui」——正在运行的服务端恰好完全命中，
     * 而 fixUsrBin()/ensureAptWorking() 等修复路径会在服务端运行期间被触发
     * （用户点修复、apt 自愈、MCP repair_runtime），于是「修复」把好好的服务端 SIGKILL 了。
     * 所以这里必须显式排除 [mcProcess]：它不可能是孤儿，它由本进程托管。
     */
    /**
     * 清理占用某个服务端目录的孤儿 java 进程。
     *
     * ## 为什么必须排除「当前托管的进程」整棵子树
     *
     * 被登记的 `mcProcess` 是外层 wrapper（PRoot 场景下就是 proot 追踪进程本身，
     * 因为 `/system/bin/sh -c "... exec proot ..."` 与 `bin/proot` 启动脚本
     * 都用 exec 替换了自己）。**真正跑服务端的 java 是它的子进程，pid 不同** ——
     * 只按 pid 排除 wrapper 的话，java 仍会落进下面的匹配，
     * 于是「修复运行环境」这类在服务端运行期间也会被触发的调用
     * （`fixUsrBin()` 走 `killOrphanMcProcess(null)`）会把好好的服务端 SIGKILL 掉，
     * 表现为「启动中突然秒退、没有任何报错」。
     *
     * 因此这里按「wrapper 及其全部后代」排除，而不是只比 pid。
     *
     * @param serverDir 目标服务端目录；为 null 时退化为「任意 MC java」匹配，
     *   调用方应尽量传真实目录，见下方的保守匹配说明。
     */
    private fun killOrphanMcProcess(serverDir: File?) {
        try {
            val target = serverDir?.absolutePath
            val trackedPid = synchronized(mcProcessLock) { if (mcProcess?.isAlive == true) mcPid else null }
            // 托管进程自身的 pid 可能取不到（Android 上 Process.pid() 未必可用），
            // 此时退回用 findServerPid 反查，避免漏保护。
            val protectedPids: Set<Int> = if (trackedPid != null) {
                descendantPids(trackedPid) + trackedPid
            } else {
                emptySet()
            }
            File("/proc").listFiles()?.forEach { f ->
                val name = f.name
                if (name.isEmpty() || !name.all { it.isDigit() }) return@forEach
                val pid = name.toIntOrNull() ?: return@forEach
                // 本进程托管的服务端及其后代不是孤儿，任何情况下都不动它。
                if (pid in protectedPids) return@forEach
                if (pid == android.os.Process.myPid()) return@forEach
                val cmdline = try {
                    File(f, "cmdline").readText().replace('\u0000', ' ')
                } catch (_: Exception) { return@forEach }
                val isJavaMc = cmdline.contains("java") && if (target != null) {
                    matchesManagedServer(cmdline, target)
                } else {
                    cmdline.contains(".jar") && cmdline.contains("nogui")
                }
                // BDS 的 argv 只有 `./bedrock_server`，路径不在 cmdline 里，
                // 只能靠工作目录（/proc/<pid>/cwd）判断归属。不补这一条的话，
                // 包装 shell 意外退出后残留的 BDS 永远清不掉，端口一直被占。
                val isBds = target != null &&
                    cmdline.contains(com.mineserve.mobile.server.BdsSupport.ENTRY_NAME) &&
                    runCatching { File(f, "cwd").canonicalPath == target }.getOrDefault(false)
                val isMc = isJavaMc || isBds
                // 兜底护栏：调用方没给目标目录、但此刻确实有一个服务端在跑时，
                // 只清「不属于当前服务端」的进程是无法判定的 —— `.jar && nogui`
                // 匹配不出归属。这种情形下宁可不杀：多实例场景里那些 java 很可能是
                // 另一个正常服务端，误杀等于无故停服。真正的孤儿会在下次
                // 带 serverDir 的启动/停止路径上被正确清理。
                val ambiguousWhenRunning = target == null &&
                    synchronized(mcProcessLock) { mcProcess?.isAlive == true }
                if (isMc && !ambiguousWhenRunning) {
                    Log.w(TAG, "killOrphanMcProcess: killing orphan pid=$pid (${cmdline.take(140)})")
                    emitLog("[startMc] 清理残留服务器进程 pid=$pid")
                    runCatching { android.os.Process.killProcess(pid) }
                    // 等待进程退出
                    runCatching {
                        val deadline = System.currentTimeMillis() + 3000
                        while (System.currentTimeMillis() < deadline) {
                            if (!File("/proc/$pid").exists()) break
                            Thread.sleep(100)
                        }
                    }
                } else if (isMc) {
                    Log.i(TAG, "killOrphanMcProcess: 服务端运行中且未指定目标目录，跳过 pid=$pid 以免误杀")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "killOrphanMcProcess: ${e.message}")
        }
    }

    /**
     * 判断一条 `/proc/<pid>/cmdline` 是否属于**指定的**那个服务端目录。
     *
     * 提到顶层是为了能被单测直接锁住：这段匹配逻辑决定「孤儿进程清不清得掉」
     * 与「会不会误杀别人的服务端」，但它的输入只有一个字符串，
     * 放在私有方法里就只能靠设备实测，出错代价又极高。
     *
     * ## 为什么必须同时接受两种路径
     *
     * PRoot 会改写路径：容器里的 java 通过 `--bind=<宿主 serverDir>:/srv/mineserve`
     * 看到的是**来宾路径**，而 Linux 的 `/proc/<pid>/cmdline` 记录的是进程收到的
     * 原始 argv。两种形态都真实存在，只认其中一种就会出现：
     * - 只认宿主路径 → 容器里的孤儿匹配不到，残留 `world/session.lock`，
     *   下次启动直接报 `already locked`；
     * - 只认来宾路径 → 宿主链路的残留清不掉。
     *
     * 注意 `/srv/mineserve` 是全局固定挂载点，**不区分**具体是哪个服务端；
     * 因此调用方在给出 `serverDir` 时，本判断只用于「是不是某个来宾服务端」，
     * 跨实例的精确归属仍依赖 `mcServerDir` 与进程树排除。
     */
    /**
     * 取某个 pid 的全部后代 pid（不含自身）。
     *
     * 用于把「托管的 wrapper + 它启动的 java」当成一个整体保护起来。
     * 深度上限 32 与 [findServerPid] 的遍历保持一致，避免 procfs 环路导致死循环。
     */
    private fun descendantPids(rootPid: Int): Set<Int> = runCatching {
        val parents = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            val raw = File(dir, "stat").takeIf { it.isFile }?.readText() ?: return@mapNotNull null
            val close = raw.lastIndexOf(')')
            if (close <= 0) return@mapNotNull null
            val parent = raw.substring(close + 1).trim().split(Regex("\\s+")).getOrNull(1)
                ?.toIntOrNull() ?: return@mapNotNull null
            pid to parent
        }.toMap()
        val result = mutableSetOf<Int>()
        var frontier = setOf(rootPid)
        repeat(32) {
            val next = parents.filterValues { it in frontier }.keys - result - rootPid
            if (next.isEmpty()) return@repeat
            result.addAll(next)
            frontier = next
        }
        result
    }.getOrDefault(emptySet())

    /**
     * 在 PRoot 容器里启动服务端（通用实现）。
     *
     * 由 `startMcInUbuntu` 泛化而来：PRoot 参数与启动流程**完全保留**，只是把
     * rootfs、JAVA_HOME、就绪判定改由 [ProotContainerProfile] 推导，
     * 从而支持多个发行版与多个 Java 版本。
     *
     * ## 绑定的意义
     *
     * `--bind=<宿主 serverDir>:/srv/mineserve` 让服务端目录**原地**进入容器，
     * 因此世界、插件、配置、日志仍躺在原来的宿主路径上，
     * `Worlds` / `BackupManager` / `PluginManager` / 文件管理 / FTP / MCP
     * 全部无需改动即可继续工作。这是整个 PRoot 方案能低风险落地的根本原因。
     *
     * @param javaVersion 来宾里要使用的 Java 版本
     * @param javaVendor 来源；Termux+Java8 与 Proot 都走来宾链路
     */
    private fun startMcInProot(
        javaVersion: JavaVersion,
        javaVendor: JavaVendor,
        jarPath: String,
        maxHeapMb: Int,
        serverDir: File,
        onExit: (Int) -> Unit,
        launchArgs: String?,
        logFile: File,
        appendNogui: Boolean
    ): Process {
        // 解析画像：历史遗留的「Termux + Java 8」固定用 focal；
        // Proot 来源则取决于用户当前选的发行版。
        val profile = if (javaVendor == JavaVendor.Proot) {
            awaitProotProfile(javaVersion)
        } else {
            legacyJava8Profile
        }
        if (!isContainerJavaReady(profile)) {
            emitLog("[startMc] ${profile.displayName} 运行环境未安装或不完整")
            throw RuntimeException("${profile.displayName} runtime is not ready")
        }
        val guestDir = GUEST_SERVER_DIR
        val guestJar = "$guestDir/${File(jarPath).relativeTo(serverDir).invariantSeparatorsPath}"
        val guestLaunchArgs = launchArgs?.replace(serverDir.absolutePath, guestDir)
        val isLegacyForgeLaunch = launchArgs?.trim()?.startsWith("-jar") == true &&
            launchArgs.contains("forge-")
        val launchesVerifiedLibraryJar = isLegacyForgeLaunch &&
            launchArgs?.contains("libraries/", ignoreCase = true) == true
        // 注意：不要用 libraries 里 find 到的第一个 jar 覆盖顶层启动 jar——
        // 1.16.5 的 libraries 第一个 jar 是无 Main-Class 的 -server.jar，
        // 而顶层 launcher（ServerMain）才是官方启动入口；launchArgs 指向的
        // jar 已在 App 侧验证过 Main-Class，这里直接使用即可。
        val forgeServerClasspath = if (launchesVerifiedLibraryJar) {
            "forge_jar=\"${'$'}(find libraries/net/minecraftforge/forge -type f -name 'forge-*.jar' -print -quit 2>/dev/null)\"; " +
                "if [ -z \"${'$'}forge_jar\" ]; then echo '[startMc] ${javaVersion.displayName} Forge: launch jar not found in libraries'; exit 1; fi; " +
                // 1.12- 的 universal 无 Main-Class，需要 classpath + relauncher 模式；
                // 1.13-1.16 的 server jar 自带 Main-Class（FMLServerTweaker）与 Class-Path 清单，直接 -jar。
                "if jar tf \"${'$'}forge_jar\" 2>/dev/null | grep -q 'net/minecraftforge/fml/relauncher/ServerLaunchWrapper.class'; then " +
                "forge_classpath=\"${'$'}(find libraries -type f -name '*.jar' -printf '%p:' 2>/dev/null)${'$'}(find . -maxdepth 1 -type f -name 'minecraft_server.*.jar' -printf '%p:' 2>/dev/null)\"; " +
                "LAUNCH_LEGACY_CP=1; else LAUNCH_LEGACY_CP=0; fi; "
        } else ""
        val forgeLibraryRepair = ""
        val javaArguments = if (launchesVerifiedLibraryJar) {
            "if [ \"${'$'}LAUNCH_LEGACY_CP\" = 1 ]; then " +
                "-cp \"${'$'}forge_classpath\" net.minecraftforge.fml.relauncher.ServerLaunchWrapper; " +
                "else -jar \"${'$'}forge_jar\"; fi"
        } else {
            guestLaunchArgs ?: "-jar '$guestJar'"
        }
        // 注意：此处**有意不使用** preloadEnv()。
        // proot 会把 rootfs 之外的路径映射掉，libheaptagfix.so 位于 App 的
        // nativeLibraryDir，在 guest 内没有对应的可访问路径。若强行设置
        // LD_PRELOAD，linker 找不到该库会直接导致 java 无法启动。
        // 该分支是 PRoot 来宾场景，本就不依赖这项优化，故留空。
        //
        // JAVA_HOME 与 java 路径都从画像推导：以前这里硬编码 ubuntuJava8Home
        // 和 `/usr/bin/java`，泛化后不能再用裸 `/usr/bin/java` —— 那是 apt 的
        // alternatives 符号链接，多版本共存时可能指向别的 JDK。
        val javaBin = profile.guestJavaBinaryCandidates.first()
        val command = "export JAVA_HOME=${profile.javaHomeInGuest}; " +
            "export PATH=\"${profile.javaHomeInGuest}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"; " +
            "export TMPDIR=/tmp; export HOME=/root; export FONTCONFIG_PATH=/etc/fonts; " +
            "cd '$guestDir' && $forgeLibraryRepair$forgeServerClasspath exec $javaBin " +
            "-Djava.io.tmpdir=/tmp " +
            baseJvmProperties(maxHeapMb) +
            "-Xmx${maxHeapMb}m $javaArguments" + if (appendNogui) " nogui" else ""
        val rootfs = containerRootfs(profile)
        // PRoot creates glue files outside the guest rootfs. Keep this path in
        // the app-owned prefix; Termux's compatibility /usr/tmp may be absent
        // or inaccessible on newer Android storage namespaces.
        val prootTmp = profile.prootTmpDir(installer.rootDir).apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        val sharedMemory = File(rootfs, "tmp").apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        val resolver = prepareUbuntuDns(
            rootfs,
            profile.hostResolvConf(installer.rootDir),
            "${profile.distro.dirName}-resolv.conf"
        )
        val proot = listOf(
            File(installer.rootDir, "bin/proot"),
            File(installer.rootDir, "usr/bin/proot")
        ).firstOrNull { it.isFile && it.canExecute() }
            ?: throw RuntimeException("proot is not available")
        val process = ProcessBuilder(
            "/system/bin/sh", "-c",
            "export PROOT_TMP_DIR='${prootTmp.absolutePath}'; " +
                "export TMPDIR='${prootTmp.absolutePath}'; exec " +
                listOf(
                    proot.absolutePath, "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "--change-id=0:0",
            "--rootfs=${rootfs.absolutePath}", "--cwd=/root",
            "--bind=/dev", "--bind=/proc", "--bind=/sys", "--bind=/dev/urandom:/dev/random",
            "--bind=${sharedMemory.absolutePath}:/dev/shm", "--bind=${resolver.absolutePath}:/etc/resolv.conf",
            "--bind=${serverDir.absolutePath}:$guestDir",
            "/usr/bin/env", "-i",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root", "TMPDIR=/tmp", "LANG=C.UTF-8", "DEBIAN_FRONTEND=noninteractive",
            // Android 无 /etc/localtime，容器内同样需要显式给时区
            (systemTimeZoneId()?.let { "TZ=$it" } ?: "TZ=UTC"),
            "/bin/sh", "-lc", command
                ).joinToString(" ") { shellQuote(it) }
        ).apply {
            redirectErrorStream(true)
            directory(serverDir)
            environment().putAll(executor.termuxEnv())
            environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
            environment()["TMPDIR"] = prootTmp.absolutePath
        }.start()
        emitLog("[startMc] ${profile.displayName} PRoot 临时目录: ${prootTmp.absolutePath}")
        Log.i(TAG, "startMc ${profile.displayName} command: $command")
        assertLaunchCommandComplete(command, "startMcInProot")
        if (isLegacyForgeLaunch) {
            emitLog("[startMc] ${javaVersion.displayName} Forge: validating launch jar from verified library")
        }
        if (launchesVerifiedLibraryJar) {
            emitLog("[startMc] ${javaVersion.displayName} Forge: using ServerLaunchWrapper classpath mode")
        }
        emitLog("[startMc] java 路径: ${profile.displayName} → $javaBin")
        emitLog("[startMc] 正在启动 ${profile.displayName} 服务端...")
        trackMcProcess(process, serverDir)
        Thread({
            // writer 必须声明在 try 之外：原先把声明放在 try 里，catch 分支拿不到它，
            // readLine() 一抛 IOException（进程被强杀）就漏掉一个 FileOutputStream 与一条管道，
            // 反复崩溃重启会把 FD 耗光。
            var writer: java.io.BufferedWriter? = null
            try {
                writer = java.io.BufferedWriter(java.io.OutputStreamWriter(
                    java.io.FileOutputStream(logFile, true), Charsets.UTF_8), 8192)
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                var lineCount = 0
                while (line != null) {
                    executor.emit(line)
                    writer.appendLine(line)
                    if (++lineCount % 50 == 0) writer.flush()
                    line = reader.readLine()
                }
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "${profile.distro.displayName} stdout reader error: ${e.message}")
            } finally {
                runCatching { writer?.close() }
            }
        }, "mc-ubuntu-stdout-reader").start()
        Thread({
            val code = process.waitFor()
            Log.w(TAG, "Ubuntu Java 8 MC process exited code=$code")
            emitLog("[startMc] Java 8 服务端已退出 (exit=$code)")
            if (clearMcProcessIfCurrent(process)) onExit(code)
        }, "mc-ubuntu-watch").start()
        return process
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    /**
     * 组装启动 JVM 前要注入的 `LD_PRELOAD` 片段（无可用库时返回空串）。
     *
     * 当前有两个预加载库，按**来源**（[vendor]）与 [jvmFlavor] 决定挂哪些：
     *
     *  1. `libheaptagfix.so`（[heapTagLibPath]，两套 JDK 都挂）
     *     Android 11+ 的 bionic 默认给堆分配打指针标签（TBI/MTE），指针高 8 位被
     *     用作元数据。JVM 内部会改写指针高位做标记，在带标签的堆上会导致：
     *       - 释放时标签校验失败 → SIGABRT（退出码 134）
     *       - 分配器无法复用被改写过的内存块 → 常驻内存显著升高
     *       - 每个分配多出标签元数据，JVM 启动期海量小对象分配累计开销可观
     *     后两条正是「同一个服务端、同一份 jar，内存却比别人高 100MB 左右」的原因。
     *     之所以必须用 LD_PRELOAD：`mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0)`
     *     只对调用它的进程生效、且必须在任何 malloc 之前执行，而服务端是独立进程，
     *     App 自己调用影响不到它。LD_PRELOAD 让 linker 在加载其他库之前先跑
     *     [libheaptagfix.so] 的构造函数，正好赶在 JVM 所有分配之前。
     *
     *  2. `libipv6fix.so`（[ipv6FixLibPath]，仅 **Termux 来源**且 [JvmFlavor.PatchedJdk] 挂）
     *     把 OpenJDK 判定 IPv6 用的那次 `fopen("/proc/net/if_inet6")` 重定向到非空
     *     文件（Android 上 App 读不到那个文件，于是 JVM 一律判「无 IPv6」，
     *     `server-ip=::` 一启动就崩）。库缺失时**不报错、不阻断启动**：只是这次的
     *     IPv6 判定照旧为 false，用户仍是 IPv4 监听。
     *
     *     [JavaVendor.Fcl] **刻意不挂**：那套 JRE 的 `libnet.so` 在编译期
     *     （Pojav 的 `26_skip_proc_net6_check.diff`）就跳过了同一处 `fopen`，`::`
     *     本就可用。再挂一层不仅多余，还会让「FCL 来源的 IPv6 到底靠谁生效」变得
     *     说不清 —— 出了故障要在两套机制之间猜。
     *
     * ## 为什么必须合并成一条 export
     *
     * `LD_PRELOAD` 是普通环境变量，**后一条 export 覆盖前一条而不是追加**。
     * 于是「先 heaptagfix、再 ipv6fix」写两遍的结果是只剩后者，前者被静默丢掉 ——
     * 而且丢掉的那一个没有任何报错，只能靠别人发现「内存怎么又高了」。
     * 所以这里统一收口：库列表进来、一条 export 出去（[preloadExportFragment]）。
     */
    private fun preloadEnv(jvmFlavor: JvmFlavor, vendor: JavaVendor): String {
        val libs = mutableListOf<String>()
        heapTagLibPath()?.let { libs += it }
        if (vendor == JavaVendor.Termux && jvmFlavor == JvmFlavor.PatchedJdk) {
            ipv6FixLibPath()?.let { libs += it }
        }
        return preloadExportFragment(libs)
    }

    /** `libheaptagfix.so` 在 nativeLibraryDir 下的绝对路径；缺失时返回 null。 */
    private fun heapTagLibPath(): String? {
        val lib = bundledLibDir?.let { File(it, HEAP_TAG_FIX_LIB) }
        if (lib == null || !lib.isFile) {
            // 只在首次缺失时提示一次，避免每条启动命令都刷日志
            if (heapTagFixWarned.compareAndSet(false, true)) {
                emitLog("[startMc] 提示: $HEAP_TAG_FIX_LIB 不可用，将不关闭堆指针标签（可能多占内存）")
            }
            return null
        }
        return lib.absolutePath
    }

    /** `libipv6fix.so` 在 nativeLibraryDir 下的绝对路径；未随 APK 打包时返回 null。 */
    private fun ipv6FixLibPath(): String? =
        bundledLibDir?.let { File(it, NativeLibraryBundler.IPV6_FIX_LIB) }
            ?.takeIf { it.isFile }
            ?.absolutePath
    /**
     * 启动命令完整性自检：确认拼出来的 shell 命令里**真的有一个要执行的程序**。
     *
     * ## 为什么需要这个
     * 这些命令是十几段字符串用 `+` 拼起来的，只要**漏掉一个 `+`**，Kotlin 就会把
     * 后半截当成独立语句（以运算符结尾的行会继续到下一行，编译器不报错），
     * 且后续字符串因为「无副作用的纯表达式」被优化阶段直接丢弃。
     *
     * 真实故障（1.2.8 及更早）：`heapTagPreloadEnv()` 后漏了 `+`，
     * 导致 `cd '<serverDir>' && exec '<javaPath>' ... -jar server.jar nogui`
     * **整段消失**。最终命令只剩一串 `export` 赋值：
     *   - `sh -c` 执行完这些赋值后脚本自然结束 → 退出码 0
     *   - java 从未运行 → stdout 为空 → latest.log 0 字节、控制台无输出
     *   - 表现为「点启动秒退、无任何日志」，极难排查
     *
     * 编译期无法发现这类错误，所以改为**运行期断言**：判定逻辑见顶层纯函数
     * [launchCommandProblems]，不满足就直接抛错，并把完整命令打进日志 ——
     * 宁可启动失败且留下明确原因，也不要静默秒退。
     *
     * ## ⚠️ 这条断言只允许对「真的被截断」报警
     * 1.2.6 曾因为判定过严，把两种**合法**启动方式误判成"命令不完整"，
     * 用户侧表现为点启动就弹异常、服务端起不来（回退 1.2.5 即正常）：
     *   1. Forge 1.17+ / NeoForge 用 `@libraries/.../unix_args.txt` 启动（java argfile 语法），
     *      命令里既没有 `-jar` 也没有 `-cp`，但这是官方启动方式；
     *   2. 「完全自定义启动命令」模式由用户自己写命令（如 `java -jar server.jar`），
     *      不会有 `exec `。
     * 因此前者增加 `@参数文件` 标记放行，后者整组检查关闭。
     *
     * @param command 拼好的完整 shell 命令
     * @param tag 调用方标记，用于日志定位
     * @param requireExecPrefix 是否要求执行段带 `exec `（自定义命令模式传 false）
     * @param requireLauncherMarker 是否要求出现启动器特征（自定义命令模式传 false）
     */
    private fun assertLaunchCommandComplete(
        command: String,
        tag: String,
        requireExecPrefix: Boolean = true,
        requireLauncherMarker: Boolean = true
    ) {
        val problems = launchCommandProblems(command, requireExecPrefix, requireLauncherMarker)
        if (problems.isEmpty()) return

        val detail = problems.joinToString("；")
        // 完整命令一并落盘，便于直接对比「应该是什么」和「拼出来是什么」
        emitLog("[startMc] 启动命令不完整（$tag）：$detail")
        emitLog("[startMc] 实际命令: $command")
        Log.e(TAG, "launch command incomplete ($tag): $detail\ncommand=$command")
        throw RuntimeException("启动命令不完整（$tag）：$detail。请反馈此问题，日志中已记录完整命令。")
    }

    /**
     * 组装所有 Java 服务器共用的 JVM 属性。
     *
     * 其中包含一组「对 Android/bionic 更友好」的 OSHI/JNA 属性，用于缓解 OSHI 系模组
     * 因 `libc.so.6` 缺失而崩服的问题。该组属性原由「模组兼容模式」开关控制，现改为
     * **无条件注入**：Android 本就没有 udev/systemd，关掉这些探测只有好处，无需用户判断。
     */
    private fun baseJvmProperties(maxHeapMb: Int): String = buildString {
        append("-Djava.awt.headless=true ")
        // Android 没有 /etc/localtime，且未设 TZ 时 JVM 默认时区会回退成 UTC，
        // 导致服务端日志时间戳比本地时间慢 8 小时（东八区）。
        // 显式注入系统时区，JVM 与服务器派生的子命令都能拿到正确时区。
        systemTimeZoneId()?.let { append("-Duser.timezone=$it ") }
        append("-Dio.netty.transport.noNative=true ")
        append("-Dio.netty.transport.epoll.enabled=false ")
        append("-Dio.netty.transport.kqueue.enabled=false ")
        /*
         * 关键修复：把 JLine 的 JNA provider 从候选列表里摘掉。
         *
         * 崩溃实测栈（Forge 服务端日志）：
         *   Launcher.<clinit>
         *     → LogManager.getLogger
         *       → LoggerContext.start → PatternParser.createConverter   ← 还在解析 log4j 日志格式串
         *         → ForgeHighlight.newInstance
         *           → TerminalConsoleAppender.initializeTerminal        ← Forge/NeoForge 自带的控制台组件
         *             → TerminalBuilder.doBuild → checkProvider
         *               → JnaTerminalProvider.<init>
         *                 → LinuxNativePty.<clinit>
         *                   → com.sun.jna.Native.<clinit>               ← 这里炸
         *
         * 也就是说：崩溃发生在「日志系统配置格式」阶段，模组一个都还没加载。
         * 因此删模组、换 Java 版本都无效。
         *
         * JLine 的 TerminalBuilder.checkProvider() 会按系统属性
         * `org.jline.terminal.providers` 过滤可用 provider（逗号分隔的 provider 名称）。
         * 把 `jna` 排除后就不会实例化 JnaTerminalProvider，也就不会触碰
         * com.sun.jna.Native —— 从根上绕开这个必然失败的初始化。
         *
         * 保留 exec / jni / ffi：
         *   - exec 是纯外部命令实现（stty），Android 上没有 stty 时会自行降级；
         *   - jni / ffi 在缺库时 JLine 内部按 provider 名加载失败也只是跳过，
         *     不会抛到调用方（与 JNA 的 <clinit> 硬失败不同）。
         * 我们的服务端一律以 nogui 运行，终端探测失败是安全的。
         */
        append("-Dorg.jline.terminal.providers=exec,jni,ffi ")
        append(memoryFootprintArgs(maxHeapMb))
        /*
         * 这里的取值来自 OSHI 的 GlobalConfig 常量表，只使用真实存在的键。
         *
         * 历史遗留的两个参数已移除，它们都是无效的：
         *   -Doshi.util.use.jna=false   → OSHI 从未定义过该键，纯死参数
         *   -Djna.nosys=true            → 禁止 JNA 加载「系统库」。libc 在 Android 上
         *                                 就是系统库 libc.so，被禁后 JNA 只能退回按
         *                                 Linux 习惯找 libc.so.6 → 必然失败。
         *                                 这条很可能是崩溃的助推因素，故一并去掉。
         */
        // 不加载 udev：Android 无 systemd/udev，探测只会白白触发原生库加载
        append("-Doshi.os.linux.allowudev=false ")
        // 不探测 systemd 会话：Android 没有
        append("-Doshi.os.linux.allowsystemd=false ")
        // 不做 NFS 可达性探测：默认开启且会发起 TCP:2049 连接、单次最长阻塞 2 秒
        append("-Doshi.os.linux.filesystem.checknfs=false ")
        // 不记录 /proc 读取警告：Android 上大量 /proc 节点受限，告警会刷屏
        append("-Doshi.os.linux.procfs.logwarning=false ")
        // 关掉 Memoizer 缓存：避免 OSHI 缓存住首次探测失败的结果后反复重试
        append("-Doshi.util.memoizer.expiration=0 ")
        /*
         * 历史遗留参数已移除（保留说明以免再次被加回来）：
         *
         *   -Doshi.util.use.jna=false
         *       OSHI 从未定义过该键，纯死参数。
         *
         *   -Djna.nosys=true
         *       禁止 JNA 加载「系统库」。libc 在 Android 上就是系统库 libc.so，
         *       被禁后 JNA 只能退回按 Linux 习惯找 libc.so.6 → 必然失败。
         *       这条是崩溃的助推因素，已去掉。
         *
         *   -Djna.nounpack=true   ← 本次移除
         *       禁止 JNA 从自己的 jar 里解包 libjnidispatch.so。但 jar 解包是
         *       JNA 三层加载顺序里唯一「还可能成功」的一层：
         *         1) jna.boot.library.path 目录
         *         2) 系统库路径（受 jna.nosys 控制）
         *         3) 从 jar 解包（受 jna.nounpack 控制）
         *       关掉它并不会让 JNA 变得可用，只会让失败点后移、报错更难定位。
         *       实测崩溃栈里的 /data/data/com.venti1112.edgecube/cache/jna*.tmp
         *       就是第 3 步解包出来的文件——这个文件至少能被 dlopen 尝试打开，
         *       关掉之后就只剩「找不到库」这种更含糊的报错。
         */
    }

    /**
     * 服务端运行期的临时目录（`<服务器目录>/.mineserve/tmp`）。
     *
     * 临时文件随服务器目录一起清理，不会污染 Termux 的公共 tmp。
     */
    private fun serverTmpDirFor(serverDir: File): File =
        File(serverDir, ".mineserve/tmp")

    /**
     * 降低常驻内存的 `-XX` 参数。
     *
     * 背景：原先一个 `-XX` 参数都没有，全部走 JVM 默认值。而移动端 JVM 的默认值
     * 是按「服务器/桌面」场景设计的，对手机来说普遍偏大。逐项说明：
     *
     * - `+UseSerialGC`：默认 GC 在 Android 上通常是 G1 或 Parallel。G1 会额外维护
     *   并发标记线程、Remembered Set（卡表）、以及约 10% 堆大小的保留区。手机端
     *   服务端通常玩家少、堆不大，Serial GC 单线程回收开销极小且无这些额外结构，
     *   实际表现往往更快也更省内存。
     *
     * - `MaxMetaspaceSize`：类元数据区。**必须跟随堆上限一起放宽**——
     *   Metaspace 是 native 内存、完全不占 `-Xmx`，所以「堆还剩一大半」和
     *   「Metaspace 已满」可以同时成立。
     *   ⚠️ 1.2.6 曾把它写死成 256m，大型 Forge 整合包（两百多个 mod，每个 mod
     *   一个类加载器）在 RegisterEvent 阶段必然撞上限，报
     *   `OutOfMemoryError: Metaspace`，而 `-Xmx` 明明没用完。用户只会看到
     *   「内存不足」，按提示去调 `-Xmx` 反而越调越糟。现改为
     *   `max(768m, -Xmx)`：模组包越大堆越大，比例关系天然成立，且仍有天花板
     *   避免无限增长拖死整机。
     *
     * - `ReservedCodeCacheSize=128m`：JIT 编译后的机器码缓存，默认约 240MB。
     *   手机端服务端长期运行的热点方法有限，砍到一半通常无感知。
     *
     * - `Xss768k`：每线程栈，arm64 默认 1MB。服务端网络/区块/IO 线程数量不少，
     *   每个省 256KB，几十个线程即可省下十几 MB。
     *   ⚠️ 不设得更小（如 512k）是因为部分模组递归较深，栈过小会 StackOverflow。
     *
     * 除 Metaspace 外其余参数都是「只减不增」的安全项：不改变服务端行为，
     * 只压缩 JVM 的预留与开销。
     */
    private fun memoryFootprintArgs(maxHeapMb: Int): String = buildString {
        append("-XX:+UseSerialGC ")
        // Metaspace 上限是「天花板」不是「预留」：调大本身不占内存，
        // 只在真的加载了那么多类时才有意义。因此下限取 768m 足够小机器开机，
        // 上限跟随堆上限让大整合包不至于先在类元数据上撞死。
        val metaspaceMb = maxOf(METASPACE_FLOOR_MB, maxHeapMb)
        append("-XX:MaxMetaspaceSize=${metaspaceMb}m ")
        append("-XX:ReservedCodeCacheSize=128m ")
        append("-Xss768k ")
    }

    /**
     * 当前设备时区（IANA 格式，如 `Asia/Shanghai`）；取不到时返回 null。
     *
     * Android 没有 /etc/localtime，JVM 无从推断时区，必须由 App 侧显式告知。
     */
    private fun systemTimeZoneId(): String? =
        runCatching { java.util.TimeZone.getDefault().id }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != "GMT" }

    /**
     * 直接用 ProcessBuilder 启动 MC 服务（不再依赖 tmux）。
     * stdout/stderr 实时推送到 consoleFlow，同时写入日志文件。
     * onExit 回调在 MC 进程退出时触发。
     */
    fun startMc(
        jarPath: String,
        maxHeapMb: Int,
        dirName: String,
        javaVersion: JavaVersion = JavaVersion.Java17,
        onExit: (Int) -> Unit,
        launchArgs: String? = null,
        appendNogui: Boolean = true,
        jvmFlavor: JvmFlavor = JvmFlavor.PatchedJdk,
        javaVendor: JavaVendor = JavaVendor.Termux
    ): Process = withLaunchLock {
        startMcImpl(jarPath, maxHeapMb, dirName, javaVersion, onExit, launchArgs, appendNogui, jvmFlavor, javaVendor)
    }

    /** [startMc] 的实际实现；由 [withLaunchLock] 持锁调用。 */
    private fun startMcImpl(
        jarPath: String,
        maxHeapMb: Int,
        dirName: String,
        javaVersion: JavaVersion,
        onExit: (Int) -> Unit,
        launchArgs: String?,
        appendNogui: Boolean,
        jvmFlavor: JvmFlavor,
        javaVendor: JavaVendor
    ): Process {
        Log.i(TAG, "startMc: jar=$jarPath heap=${maxHeapMb}m dirName=$dirName vendor=$javaVendor")

        // 已有存活进程就直接复用。「判断是否在跑」与 startMcImpl 末尾的 trackMcProcess
        // 现在被 withLaunchLock 串在同一把锁里，不再有"两边都读到 null 于是双开"的窗口。
        mcProcess?.let { if (it.isAlive) return it }

        val prefix = installer.rootDir.absolutePath
        val serverDir = serverDirFor(dirName)
        val logFile = prepareMcLogFile(serverDir)

        // 清理占用该服务器目录的孤儿 java 进程（app 重启后旧 MC 进程成孤儿，
        // 占着 world/session.lock → 新实例启动报 already locked）
        killOrphanMcProcess(serverDir)

        // 来宾链路（PRoot 容器）：PRoot 来源的全部版本，以及历史遗留的
        // 「Termux + Java 8」组合 —— 后者在 PRoot 通用化之前就走容器，
        // 老配置里存的就是这个组合，必须保持同样的行为。
        //
        // 判定统一走 isProotGuestRuntime()，与 McServerController 的 Forge 安装路径
        // 共用同一个谓词，避免「启动走容器、安装走宿主」这类错位。
        if (isProotGuestRuntime(javaVendor, javaVersion)) {
            // guest 里的 loader 是 glibc 的 ld.so，加载不了我们这套 bionic 构建的
            // 预加载库 ⇒ 兼容层对它无效，如实提示而不是让用户以为 IPv6 可用。
            if (jvmFlavor == JvmFlavor.PatchedJdk) {
                emitLog(
                    "[startMc] 提示: ${javaVersion.displayName} 运行在 proot 容器内，" +
                        "${NativeLibraryBundler.IPV6_FIX_LIB} 无法注入，IPv6 监听仍不可用"
                )
            }
            return startMcInProot(javaVersion, javaVendor, jarPath, maxHeapMb, serverDir, onExit, launchArgs, logFile, appendNogui)
        }

        // 其余组合（Termux 的 11/17/21/25 与 FCL 的 17/21/25）共用同一条 Termux 宿主启动路径。
        val javaPath = resolveJavaPath(prefix, javaVersion, javaVendor) ?: run {
            emitLog(
                "[startMc] 错误: 找不到 ${javaVendor.logName} 的 ${javaVersion.displayName}，" +
                    "请先在「依赖与环境管理」里安装该运行环境"
            )
            throw RuntimeException("java not found: vendor=$javaVendor version=$javaVersion")
        }
        Log.i(TAG, "startMc: resolved java path = $javaPath")
        emitLog("[startMc] java 路径: $javaPath")
        // 明确写出这次跑的是哪一套 JVM：IPv6 到底能不能用，只能靠这一行
        // 加上 libipv6fix 自己往 stderr 打的那行确认（崩起来的时候看不出来）。
        when (javaVendor) {
            JavaVendor.Fcl -> emitLog(
                "[startMc] JVM 实现: FCL 原生 JRE（自带 IPv6 支持，server-ip 可写 ::）"
            )
            JavaVendor.Termux -> when (jvmFlavor) {
                JvmFlavor.TermuxJdk -> emitLog(
                    "[startMc] JVM 实现: Termux JDK（IPv6 监听不可用，server-ip 请留空或 0.0.0.0）"
                )
                JvmFlavor.PatchedJdk -> emitLog(
                    if (ipv6FixLibPath() != null) {
                        "[startMc] JVM 实现: Termux JDK + IPv6 兼容层（${NativeLibraryBundler.IPV6_FIX_LIB} 已注入）"
                    } else {
                        "[startMc] 警告: 缺少 ${NativeLibraryBundler.IPV6_FIX_LIB}（未随 APK 打包），本次 IPv6 监听不可用"
                    }
                )
            }
            // 走到这里说明分派逻辑出了问题：PRoot 来源在上面就已经转入
            // startMcInProot 了，不该落到宿主 JVM 分支。显式列出而不是用 else，
            // 这样将来新增来源时编译器会强制在这里做决定。
            JavaVendor.Proot -> emitLog(
                "[startMc] 警告: PRoot 来源不应走宿主 JVM 分支，请检查启动分派"
            )
        }

        // 用 /system/bin/sh -c 启动 java，设置环境变量
        // 设置 PATH 包含 jvm 的 bin 目录，确保 java 能找到其依赖（如 jlink）
        val jvmBinDir = File(javaPath).parentFile?.absolutePath ?: "$prefix/bin"
        // compat 路径：dpkg-deb -x 解包时 compat 符号链接被覆盖，文件实际落在此处
        val compatUsr = "$prefix/data/data/com.termux/files/usr"
        // jvmLibDir：从 javaPath 推导其父目录的 lib 子目录（javaPath = .../bin/java → 父=bin → 父=jvm目录 → lib）
        val jvmLibDir = File(javaPath).parentFile?.parentFile?.let { File(it, "lib") }?.absolutePath
            ?: "$prefix/lib/jvm/java-25-openjdk/lib"
        // 库搜索路径。注意：这里**不再**把所有 Java 版本的 lib 目录都塞进 LD_LIBRARY_PATH。
        // 原因：LD_LIBRARY_PATH 越长，linker 在每次 dlopen 时就要按序查找越多目录，
        // 更重要的是可能命中「同名但属于另一个 JRE」的库，导致两份 JRE 原生库同时驻留，
        // 白白多吃几十 MB 常驻内存。当前进程只会用一个 JRE，因此只保留它的路径。
        //
        // 顺序对 FCL 来源要反过来：那套 JRE 只依赖系统库（libc/libz/libdl/libm），
        // 而 Termux 前缀里同样有 libz 等同名库，被先命中就会被换成另一份实现。
        // 因此把它自己的 lib 放最前，Termux 的目录退到后面当兜底。
        val libraryPath = if (javaVendor == JavaVendor.Fcl) {
            "$jvmLibDir:$jvmLibDir/server:$prefix/lib:$compatUsr/lib:$prefix/usr/lib:/system/lib64"
        } else {
            "$prefix/lib:$compatUsr/lib:$prefix/usr/lib:$jvmLibDir:$jvmLibDir/server:/system/lib64"
        }
        val nativeAccessArg = if (javaVersion == JavaVersion.Java25) "--enable-native-access=ALL-UNNAMED " else ""
        // JLine/JNA 会把原生库解包到 java.io.tmpdir；显式指向自己的目录，
        // 避免落到上一个宿主 App 的 cache（实测出现过 edgecube 的路径）。
        val jvmTmpDir = serverTmpDirFor(serverDir).apply { mkdirs() }
        val javaCmd = "export PATH='$jvmBinDir:$prefix/bin:$prefix/usr/bin:$compatUsr/bin:$prefix/bin/applets:$prefix/libexec:/system/bin:/system/xbin'; " +
            "export LD_LIBRARY_PATH='$libraryPath'; " +
            preloadEnv(jvmFlavor, javaVendor) +
            "export FONTCONFIG_PATH='$prefix/etc/fonts'; " +
            "export FONTCONFIG_FILE='$prefix/etc/fonts/fonts.conf'; " +
            "export PREFIX='$prefix'; " +
            "export HOME='$prefix/home'; " +
            "export TMPDIR='$jvmTmpDir'; " +
            "export JAVA_HOME='${File(javaPath).parentFile?.parent}'; " +            // Android 无 /etc/localtime，不设 TZ 时 JVM 与派生命令都会回退 UTC
            (systemTimeZoneId()?.let { "export TZ='$it'; " } ?: "") +
            "cd '$serverDir' && " +
            "exec '$javaPath' $nativeAccessArg-Djava.io.tmpdir='$jvmTmpDir' -Djna.tmpdir='$jvmTmpDir' " +
            baseJvmProperties(maxHeapMb) +
            "-Xmx${maxHeapMb}m " + (launchArgs ?: "-jar $jarPath") + if (appendNogui) " nogui" else ""

        Log.i(TAG, "startMc command: $javaCmd")
        assertLaunchCommandComplete(javaCmd, "startMc")

        val pb = ProcessBuilder("/system/bin/sh", "-c", javaCmd).apply {
            redirectErrorStream(true)
            directory(serverDir)
            // 环境变量
            environment().putAll(executor.termuxEnv())
        }
        val process = pb.start()
        trackMcProcess(process, serverDir)

        // 后台线程读取 stdout，推送到 consoleFlow 并写入日志文件
        Thread({
            // writer 必须声明在 try 之外：原先把声明放在 try 里，catch 分支拿不到它，
            // readLine() 一抛 IOException（进程被强杀）就漏掉一个 FileOutputStream 与一条管道，
            // 反复崩溃重启会把 FD 耗光。
            var writer: java.io.BufferedWriter? = null
            try {
                writer = java.io.BufferedWriter(java.io.OutputStreamWriter(
                    java.io.FileOutputStream(logFile, true), Charsets.UTF_8), 8192)
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                var lineCount = 0
                while (line != null) {
                    executor.emit(line)
                    writer.appendLine(line)
                    // 批量 flush：每 50 行或退出时写入磁盘，避免每行一次 IO
                    if (++lineCount % 50 == 0) writer.flush()
                    line = reader.readLine()
                }
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "mc stdout reader error: ${e.message}")
            } finally {
                runCatching { writer?.close() }
            }
        }, "mc-stdout-reader").start()

        // 后台线程等待进程退出
        Thread({
            val code = process.waitFor()
            Log.w(TAG, "MC process exited code=$code")
            if (clearMcProcessIfCurrent(process)) onExit(code)
        }, "mc-watch").start()

        return process
    }

    /**
     * 用 PocketMine 专用 PHP 运行 PocketMine-MP（PHP 为 Android 原生构建，
     * 含 PM5 全部必需扩展；php.ini 通过 PHPRC 环境变量显式指定）。
     * 进程管理与日志处理与 [startMc] 一致。
     */
    fun startPhp(
        phpBinaryPath: String,
        phpIniPath: String,
        pharPath: String,
        dirName: String,
        onExit: (Int) -> Unit
    ): Process = withLaunchLock {
        startPhpImpl(phpBinaryPath, phpIniPath, pharPath, dirName, onExit)
    }

    /** [startPhp] 的实际实现；由 [withLaunchLock] 持锁调用。 */
    private fun startPhpImpl(
        phpBinaryPath: String,
        phpIniPath: String,
        pharPath: String,
        dirName: String,
        onExit: (Int) -> Unit
    ): Process {
        Log.i(TAG, "startPhp: php=$phpBinaryPath phar=$pharPath dirName=$dirName")
        mcProcess?.let { if (it.isAlive) return it }

        val prefix = installer.rootDir.absolutePath
        val serverDir = serverDirFor(dirName)
        val logFile = prepareMcLogFile(serverDir)
        killOrphanMcProcess(serverDir)

        val compatUsr = "$prefix/data/data/com.termux/files/usr"
        val phpCmd = "export PATH='$prefix/bin:$prefix/usr/bin:$compatUsr/bin:/system/bin:/system/xbin'; " +
            "export HOME='$prefix/home'; " +
            "export TMPDIR='$prefix/tmp'; " +
            "cd '$serverDir' && exec '$phpBinaryPath' '${File(pharPath).name}' --no-wizard"

        Log.i(TAG, "startPhp command: $phpCmd")
        val pb = ProcessBuilder("/system/bin/sh", "-c", phpCmd).apply {
            redirectErrorStream(true)
            directory(serverDir)
            environment().putAll(executor.termuxEnv())
            environment()["PHPRC"] = phpIniPath
        }
        val process = pb.start()
        trackMcProcess(process, serverDir)

        Thread({
            // writer 必须声明在 try 之外：原先把声明放在 try 里，catch 分支拿不到它，
            // readLine() 一抛 IOException（进程被强杀）就漏掉一个 FileOutputStream 与一条管道，
            // 反复崩溃重启会把 FD 耗光。
            var writer: java.io.BufferedWriter? = null
            try {
                writer = java.io.BufferedWriter(java.io.OutputStreamWriter(
                    java.io.FileOutputStream(logFile, true), Charsets.UTF_8), 8192)
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                var lineCount = 0
                while (line != null) {
                    executor.emit(line)
                    writer.appendLine(line)
                    if (++lineCount % 50 == 0) writer.flush()
                    line = reader.readLine()
                }
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "mc stdout reader error: ${e.message}")
            } finally {
                runCatching { writer?.close() }
            }
        }, "mc-stdout-reader").start()

        Thread({
            val code = process.waitFor()
            Log.w(TAG, "MC process exited code=$code")
            if (clearMcProcessIfCurrent(process)) onExit(code)
        }, "mc-watch").start()
        return process
    }

    /**
     * 启动官方基岩版 BDS。
     *
     * 有两条完全不同的执行路径，由可执行文件的架构决定：
     *
     *  1. **原生**（目录里的 `bedrock_server` 就是 aarch64）：直接 `exec ./bedrock_server`，
     *     只需 `LD_LIBRARY_PATH=<serverDir>`。
     *  2. **box64 转译**（官方发行版，x86_64）：在 PRoot Ubuntu 容器里用 box64 执行
     *     私有目录里的运行副本（公共存储是 FUSE、noexec，不能就地执行）。
     *     见 [startBdsViaBox64]。
     *
     * 两条路径共用同一套进程托管（[trackMcProcess] / [prepareMcLogFile] / 退出回调），
     * 因为对上层来说它就是「一个子进程 + 日志落文件 + stdin 发控制台命令」。
     *
     * @param version 核心版本，用来定位按版本共享的可执行副本
     */
    fun startBds(dirName: String, version: String, onExit: (Int) -> Unit): Process = withLaunchLock {
        startBdsImpl(dirName, version, onExit)
    }

    private fun startBdsImpl(dirName: String, version: String, onExit: (Int) -> Unit): Process {
        Log.i(TAG, "startBds: dirName=$dirName version=$version")
        mcProcess?.let { if (it.isAlive) return it }

        val serverDir = serverDirFor(dirName)
        val logFile = prepareMcLogFile(serverDir)
        killOrphanMcProcess(serverDir)

        val entry = File(serverDir, com.mineserve.mobile.server.BdsSupport.ENTRY_NAME)
        if (!entry.isFile) throw RuntimeException("BDS 主程序缺失：${entry.absolutePath}")

        // 架构决定路径：x86_64 官方发行版必须走容器 + box64
        val check = com.mineserve.mobile.server.BdsSupport.checkLaunch(
            serverDir,
            android.os.Build.SUPPORTED_ABIS.toList()
        )
        if (check.needsBox64) {
            return startBdsViaBox64(dirName, version, serverDir, logFile, onExit)
        }

        val cmd = "export PATH='/system/bin:/system/xbin'; " +
            "export LD_LIBRARY_PATH='${serverDir.absolutePath}'; " +
            "export HOME='${installer.rootDir.absolutePath}/home'; " +
            "export TMPDIR='${installer.rootDir.absolutePath}/tmp'; " +
            "cd '${serverDir.absolutePath}' && exec './${entry.name}'"

        Log.i(TAG, "startBds command: $cmd")
        val pb = ProcessBuilder("/system/bin/sh", "-c", cmd).apply {
            redirectErrorStream(true)
            directory(serverDir)
            environment()["LD_LIBRARY_PATH"] = serverDir.absolutePath
        }
        val process = pb.start()
        trackMcProcess(process, serverDir)

        Thread({
            var writer: java.io.BufferedWriter? = null
            try {
                writer = java.io.BufferedWriter(java.io.OutputStreamWriter(
                    java.io.FileOutputStream(logFile, true), Charsets.UTF_8), 8192)
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                var lineCount = 0
                while (line != null) {
                    executor.emit(line)
                    writer.appendLine(line)
                    if (++lineCount % 50 == 0) writer.flush()
                    line = reader.readLine()
                }
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "bds stdout reader error: ${e.message}")
            } finally {
                runCatching { writer?.close() }
            }
        }, "bds-stdout-reader").start()

        Thread({
            val code = process.waitFor()
            Log.w(TAG, "BDS process exited code=$code")
            if (clearMcProcessIfCurrent(process)) onExit(code)
        }, "bds-watch").start()
        return process
    }

    // ── BDS：box64 转译路径（官方 x86_64 发行版） ────────────────

    /**
     * BDS 用的容器画像。
     *
     * **固定为 Jammy**，不跟随 [selectedProotDistro]。
     *
     * 以前这里跟随用户「给 Java 选的发行版」以共用同一个 rootfs（省掉一份 150MB 容器），
     * 但内置 box64 是预编译 ELF，写死了 `GLIBC_2.32`–`GLIBC_2.35` 符号版本；
     * 用户选 focal（glibc 2.31）时它连 main 都进不去，只会打印
     * `version GLIBC_2.3x not found (required by .../box64)`。
     *
     * 共用省下的 150MB 换不来「能启动」，且失败点在对用户完全不可见的 proot 输出里，
     * 所以这里改为硬约束：BDS 容器永远是 [BdsContainerDistro]。
     *
     * @see BdsBox64MinGlibc
     */
    fun bdsContainerProfile(): ProotContainerProfile = BdsContainerProfile

    /**
     * 准备 BDS 用的 glibc 容器（PRoot Ubuntu rootfs + proot 本身）。
     *
     * 只负责「容器能跑起来」，box64 的获取由
     * [com.mineserve.mobile.server.BdsEnvironmentManager] 负责 —— 那里的下载是
     * 多源 + 校验的，放进运行时类会把状态机和网络重试混在一起。
     *
     * @param onProgress 0..100，包含 rootfs 下载与解压
     * @return 容器是否可用
     */
    suspend fun prepareBdsContainer(
        profile: ProotContainerProfile,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        prepareUbuntuRuntime(profile) { pct -> onProgress(pct) }
    }

    /**
     * 初始化收尾的实测：在容器里真正跑一次 box64。
     *
     * 只看「文件存在、架构是 aarch64」是不够的 —— 用户装的可能是缺库的包、
     * 或者容器里 glibc 版本不满足要求，这些都会在第一次启动服务端时才炸，
     * 而那时候用户已经离开环境页面，只会看到「服务器启动失败」。
     *
     * @return null 表示可用；否则是给用户看的原因
     */
    fun verifyBdsBox64(profile: ProotContainerProfile, bdsRoot: File): String? {
        val guestRoot = com.mineserve.mobile.server.BdsRuntime.GUEST_ROOT
        val bind = "${bdsRoot.absolutePath}:$guestRoot"
        val command = "export BOX64_NOBANNER=1; '$guestRoot/box64' -v 2>&1 | grep -qi box64"
        // 120 秒：首次在容器里跑 box64 要先加载 glibc、建 DynaRec 缓存，
        // 慢设备上 60 秒会让「装好了但自检超时」误判成失败。
        val code = runUbuntu(command, 120_000, listOf(bind), profile)
        if (code == 0) return null
        val tail = executor.lastTail?.trim()?.takeLast(300)
        return "容器内执行 box64 失败（exit=$code）" + if (tail.isNullOrBlank()) "" else "：$tail"
    }

    /** 极简 GET，只用于取 box64 的 Packages 文本（10KB 级） */
    private fun httpGetText(url: String): String {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Minexa/1.0")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw RuntimeException("HTTP ${connection.responseCode}（$url）")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 在容器里用 box64 启动 BDS。
     *
     * 为什么不能 `cd 服务器目录 && ./bedrock_server`：公共存储是 FUSE 且带 `noexec`，
     * 244MB 的主程序既没有执行位、也无法被 mmap 成可执行段。因此：
     *
     *  - 主程序先复制到 `<私有目录>/var/lib/mineserve/bds/payload/<版本>/`（真实文件系统），
     *    同一个 BDS 版本只复制一次；
     *  - 每个服务器一个 run 目录，里面用**符号链接**指向那份共享副本，再额外铺
     *    `definitions` / `resource_packs` 等内容的回链（见 [BdsRuntime.prepareRunDir]）；
     *  - 工作目录仍是服务器目录，所以 `server.properties`、`worlds/`、`logs/`
     *    全部落在用户看得到的地方，备份/文件管理/端口同步一行都不用改。
     */
    private fun startBdsViaBox64(
        dirName: String,
        version: String,
        serverDir: File,
        logFile: File,
        onExit: (Int) -> Unit
    ): Process {
        val profile = bdsContainerProfile()
        val prefix = installer.rootDir
        val box64 = com.mineserve.mobile.server.BdsRuntime.box64Binary(prefix)
        if (!box64.isFile) {
            throw RuntimeException(
                "BDS 专属运行环境未初始化。请先到「依赖与环境管理 → BDS」点「初始化环境」"
            )
        }
        // 容器可能在初始化之后被单独删掉（例如用户清理存储，或换了发行版）。
        // 这时环境标记还在，但 proot 一定起不来 —— 提前说清楚，别让用户对着
        // 一句晦涩的 proot 报错自己猜。
        if (!containerRootfsReady(profile)) {
            throw RuntimeException(
                "BDS 运行环境所需的 ${profile.distro.displayName} 容器已不存在。" +
                    "请到「依赖与环境管理 → BDS」重新初始化"
            )
        }
        emitLog("[bds] 正在准备可执行副本（公共存储不可执行，需复制到应用私有目录）")
        var lastPercent = -1
        val payload = com.mineserve.mobile.server.BdsRuntime.stageEntry(
            serverDir = serverDir,
            version = version,
            payloadDir = com.mineserve.mobile.server.BdsRuntime.payloadDir(prefix, version)
        ) { done, total ->
            if (total > 0) {
                val percent = (done * 100 / total).toInt()
                if (percent / 10 != lastPercent / 10) {
                    lastPercent = percent
                    emitLog("[bds] 复制 bedrock_server $percent%")
                }
            }
        }
        val runDir = com.mineserve.mobile.server.BdsRuntime.runDir(prefix, dirName)
        com.mineserve.mobile.server.BdsRuntime.prepareRunDir(
            serverDir = serverDir,
            runDir = runDir,
            payloadEntry = payload,
            guestServerDir = GUEST_SERVER_DIR
        )

        val guestBox64 = "${com.mineserve.mobile.server.BdsRuntime.GUEST_ROOT}/box64"
        val guestEntry = "${com.mineserve.mobile.server.BdsRuntime.GUEST_ROOT}/run/$dirName/" +
            com.mineserve.mobile.server.BdsRuntime.ENTRY_NAME
        // BOX64_NOBANNER：BDS 的日志里不需要 box64 自己的横幅
        //
        // **BOX64_LD_LIBRARY_PATH 是关键**（不能写成 LD_LIBRARY_PATH）：
        //  - 官方 bedrock_server 是**动态链接**的 x86_64 程序，DT_NEEDED 里是
        //    libc.so.6 / libm.so.6 / libpthread.so.0 / libdl.so.2 / librt.so.1 /
        //    libgcc_s.so.1 / ld-linux-x86-64.so.2，全部是 x86_64 架构的 glibc 运行库；
        //    box64 只翻译指令，库要自己给，缺了就是
        //    `Error loading needed libs in elf .../bedrock_server` + exit=255。
        //  - 这些库**绝对不能**塞进给 box64 进程自己的 LD_LIBRARY_PATH：box64 是
        //    aarch64 glibc 程序，它的 libc.so.6 会被我们这份 x86_64 的顶掉，
        //    直接 `wrong ELF class` 起不来。BOX64_LD_LIBRARY_PATH 只影响它给
        //    被翻译程序找库的顺序，正是这里要的语义。
        //  - 顺序：内置 x86 库 → 服务器目录（老版本 BDS 把 libCrypto/libssl 放在
        //    服务端目录里）→ 运行目录（符号链接那一层）。
        val guestLibs = "${com.mineserve.mobile.server.BdsRuntime.GUEST_ROOT}/x86libs"
        // BOX64_RCFILE：box64 默认只读 /etc/box64.box64rc 与 ~/.box64rc，
        // 我们解包出来的那份在 bind 目录里，必须显式指路，否则分支调优全部不生效。
        val rcFile = com.mineserve.mobile.server.BdsRuntime.box64rcFile(prefix)
        val rcExport = if (rcFile.isFile) {
            "export BOX64_RCFILE='${com.mineserve.mobile.server.BdsRuntime.GUEST_ROOT}/${rcFile.name}'; "
        } else ""
        val libExport = "export BOX64_LD_LIBRARY_PATH='$guestLibs:$GUEST_SERVER_DIR:" +
            "${com.mineserve.mobile.server.BdsRuntime.GUEST_ROOT}/run/$dirName'; "
        val command = libExport +
            "export BOX64_NOBANNER=1; " +
            rcExport +
            "cd '$GUEST_SERVER_DIR' && exec '$guestBox64' '$guestEntry'"

        val rootfs = containerRootfs(profile)
        val prootTmp = profile.prootTmpDir(prefix).apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        val sharedMemory = File(rootfs, "tmp").apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        val resolver = prepareUbuntuDns(
            rootfs,
            profile.hostResolvConf(prefix),
            "${profile.distro.dirName}-resolv.conf"
        )
        /*
         * CA 根证书：Ubuntu Base 的最小 rootfs 常常没有 ca-certificates，
         * 在线模式（online-mode=true）要靠 libcurl 走 HTTPS 连微软认证服务，
         * 没根证书就校验不了 → 挂 60 秒后报 `Could not connect to Minecraft services`，
         * 与「被墙」的现象一模一样（2026-10-31 真机踩过）。
         */
        val caCount = com.mineserve.mobile.server.BdsRuntime.ensureGuestCaBundle(rootfs)
        when {
            caCount > 0 -> emitLog("[bds] 已为容器补入 $caCount 个 CA 根证书（在线模式需要）")
            caCount == 0 -> emitLog("[bds] 警告：容器缺 CA 根证书且未能补入，在线模式会连不上微软服务")
        }
        val caEnv = if (com.mineserve.mobile.server.BdsRuntime.guestCaBundle(rootfs).isFile) {
            listOf(
                // 显式指路：不同发行版 OpenSSL 编译期默认路径不一致，靠环境变量最稳
                "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt",
                "CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt",
                "SSL_CERT_DIR=/etc/ssl/certs"
            )
        } else emptyList()
        val proot = listOf(
            File(prefix, "bin/proot"),
            File(prefix, "usr/bin/proot")
        ).firstOrNull { it.isFile && it.canExecute() }
            ?: throw RuntimeException("proot is not available")
        val bdsRoot = com.mineserve.mobile.server.BdsRuntime.rootDir(prefix)
        val process = ProcessBuilder(
            "/system/bin/sh", "-c",
            "export PROOT_TMP_DIR='${prootTmp.absolutePath}'; " +
                "export TMPDIR='${prootTmp.absolutePath}'; exec " +
                listOf(
                    proot.absolutePath, "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "--change-id=0:0",
                    "--rootfs=${rootfs.absolutePath}", "--cwd=/root",
                    "--bind=/dev", "--bind=/proc", "--bind=/sys", "--bind=/dev/urandom:/dev/random",
                    "--bind=${sharedMemory.absolutePath}:/dev/shm",
                    "--bind=${resolver.absolutePath}:/etc/resolv.conf",
                    "--bind=${bdsRoot.absolutePath}:${com.mineserve.mobile.server.BdsRuntime.GUEST_ROOT}",
                    "--bind=${serverDir.absolutePath}:$GUEST_SERVER_DIR",
                    "/usr/bin/env", "-i",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "HOME=/root", "TMPDIR=/tmp", "LANG=C.UTF-8", "DEBIAN_FRONTEND=noninteractive",
                    *caEnv.toTypedArray(),
                    (systemTimeZoneId()?.let { "TZ=$it" } ?: "TZ=UTC"),
                    "/bin/sh", "-lc", command
                ).joinToString(" ") { shellQuote(it) }
        ).apply {
            redirectErrorStream(true)
            directory(serverDir)
            environment().putAll(executor.termuxEnv())
            environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
            environment()["TMPDIR"] = prootTmp.absolutePath
        }.start()
        // BDS 没有 -jar / -cp，只是「cd 到服务器目录后 exec 一个可执行文件」，
        // 所以关掉「能看出启动哪个服务端」的那条检查，其余（exec 前缀、不被截断）照旧。
        assertLaunchCommandComplete(command, "startBdsViaBox64", requireLauncherMarker = false)
        Log.i(TAG, "startBdsViaBox64 command: $command")
        emitLog("[bds] 正在以 box64 转译启动 BDS ${version}（首次启动较慢，转译执行性能低于原生）")
        // box64 在 preload 阶段会对个别可选库打印 "Error: Loading needed libs in elf ..."，
        // 只要后面出现 "Starting Server" 就是正常启动（这句话曾让用户以为启动失败）。
        emitLog("[bds] 若见到 box64 的 Loading needed libs 提示可忽略，以出现 Starting Server 为准")
        trackMcProcess(process, serverDir)

        Thread({
            var writer: java.io.BufferedWriter? = null
            try {
                writer = java.io.BufferedWriter(java.io.OutputStreamWriter(
                    java.io.FileOutputStream(logFile, true), Charsets.UTF_8), 8192)
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                var lineCount = 0
                while (line != null) {
                    executor.emit(line)
                    writer.appendLine(line)
                    if (++lineCount % 50 == 0) writer.flush()
                    line = reader.readLine()
                }
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "bds(box64) stdout reader error: ${e.message}")
            } finally {
                runCatching { writer?.close() }
            }
        }, "bds-box64-stdout-reader").start()

        Thread({
            val code = process.waitFor()
            Log.w(TAG, "BDS(box64) process exited code=$code")
            if (clearMcProcessIfCurrent(process)) onExit(code)
        }, "bds-box64-watch").start()
        return process
    }

    // ── BDS 网络自检（在线模式排障） ──────────────────────────────

    /**
     * 在 BDS 用的**同一个** PRoot 容器里做一次出网自检。
     *
     * 存在意义：`online-mode=true` 时 BDS 只给一句 `Could not connect to Minecraft
     * services`，看不出是 DNS 污染、TCP 被拦、还是容器缺 CA。这三段的判据完全不同，
     * 猜错方向会白折腾（用户开着代理却依然失败，就是分不清是哪一段断的）。
     *
     * 只依赖 Ubuntu Base 自带的工具（getent / coreutils timeout / bash 的 `/dev/tcp`），
     * 不假设容器里装过 curl 或 openssl。
     *
     * @return 逐行输出（调用方原样打进控制台）
     */
    suspend fun probeBdsNetwork(dirName: String?): List<String> = withContext(Dispatchers.IO) {
        val profile = bdsContainerProfile()
        val prefix = installer.rootDir
        val rootfs = containerRootfs(profile)
        if (!containerRootfsReady(profile)) {
            return@withContext listOf("容器未就绪：请先在「依赖与环境管理 → BDS」初始化环境")
        }
        val proot = listOf(File(prefix, "bin/proot"), File(prefix, "usr/bin/proot"))
            .firstOrNull { it.isFile && it.canExecute() }
            ?: return@withContext listOf("proot 不可用：请重新初始化 BDS 环境")

        val prootTmp = profile.prootTmpDir(prefix).apply { mkdirs() }
        val sharedMemory = File(rootfs, "tmp").apply { mkdirs() }
        val resolver = prepareUbuntuDns(
            rootfs,
            profile.hostResolvConf(prefix),
            "${profile.distro.dirName}-resolv.conf"
        )
        // 自检顺便把 CA 补上：真机实测容器里 ca-certificates.crt 不存在，
        // 而在线模式的 TLS 校验必须靠它（详见 ensureGuestCaBundle 的注释）。
        val caPems = com.mineserve.mobile.server.BdsRuntime.ensureGuestCaBundle(rootfs)

        // 用 IP 直连而不是域名：否则 `/dev/tcp` 会自己再解析一次，DNS 失败会
        // 被伪装成 TCP 失败，正好把要区分的东西混在一起。
        val script = """
            echo "容器时间: ${'$'}(date -u '+%Y-%m-%d %H:%M:%S UTC')"
            echo "DNS 配置:"
            sed 's/^/  /' /etc/resolv.conf 2>/dev/null || echo "  (读不到 /etc/resolv.conf)"
            if [ -s /etc/ssl/certs/ca-certificates.crt ]; then
              echo "CA 证书: ${'$'}(wc -c < /etc/ssl/certs/ca-certificates.crt) 字节"
            else
              echo "CA 证书: 缺失"
            fi
            for h in api.minecraftservices.com user.auth.xboxlive.com xsts.auth.xboxlive.com multiplayer.minecraft.net; do
              ip=${'$'}(getent ahostsv4 "${'$'}h" 2>/dev/null | awk 'NR==1{print ${'$'}1}')
              if [ -z "${'$'}ip" ]; then
                ip=${'$'}(getent hosts "${'$'}h" 2>/dev/null | awk 'NR==1{print ${'$'}1}')
              fi
              if [ -z "${'$'}ip" ]; then
                echo "DNS 失败: ${'$'}h"
                continue
              fi
              echo "DNS ok: ${'$'}h -> ${'$'}ip"
              if timeout 8 bash -c "exec 3<>/dev/tcp/${'$'}ip/443" 2>/dev/null; then
                echo "TCP ok: ${'$'}h:443"
              else
                echo "TCP 失败: ${'$'}h:443"
              fi
            done
        """.trimIndent()

        val args = mutableListOf(
            proot.absolutePath, "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "--change-id=0:0",
            "--rootfs=${rootfs.absolutePath}", "--cwd=/root",
            "--bind=/dev", "--bind=/proc", "--bind=/sys", "--bind=/dev/urandom:/dev/random",
            "--bind=${sharedMemory.absolutePath}:/dev/shm",
            "--bind=${resolver.absolutePath}:/etc/resolv.conf"
        )
        dirName?.let { name ->
            val serverDir = serverDirFor(name)
            if (serverDir.isDirectory) args += "--bind=${serverDir.absolutePath}:$GUEST_SERVER_DIR"
        }
        args += listOf(
            "/usr/bin/env", "-i",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root", "TMPDIR=/tmp", "LANG=C.UTF-8",
            "/bin/sh", "-lc", script
        )

        val process = try {
            ProcessBuilder(
                "/system/bin/sh", "-c",
                "export PROOT_TMP_DIR='${prootTmp.absolutePath}'; " +
                    "export TMPDIR='${prootTmp.absolutePath}'; exec " +
                    args.joinToString(" ") { shellQuote(it) }
            ).apply {
                redirectErrorStream(true)
                environment().putAll(executor.termuxEnv())
                environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
                environment()["TMPDIR"] = prootTmp.absolutePath
            }.start()
        } catch (e: Exception) {
            return@withContext listOf("自检启动失败：${e.message}")
        }

        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) lines.add(line.trim())
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = runCatching {
            process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroy() }
            lines.add("自检超时（120s），已中止")
        }
        reader.join(3000)
        val out = lines.toList().toMutableList()
        out += if (caPems > 0) {
            "CA 修复: 已从系统根证书补入 $caPems 个（容器原先缺失）"
        } else if (caPems == 0) {
            "CA 修复: 系统里也没找到根证书，无法补入"
        } else {
            "CA 修复: 已有可用 bundle，未改动"
        }
        /*
         * TLS 握手单独测一次（在 App 进程里用 JSSE）。
         *
         * 为什么 TCP 通了还要测：GFW 对部分域名是**放行 SYN、掐 TLS 的 SNI**，
         * `/dev/tcp` 只能看到 443 通，看不到后面被重置。这一条能把它区分出来。
         */
        for (host in TLS_PROBE_HOSTS) {
            val result = probeTlsHandshake(host)
            out += if (result == "ok") "TLS ok: $host" else "TLS 失败: $host（$result）"
        }
        out.toList()
    }

    /** 对单个域名做一次真实的 TLS 握手（带 SNI），返回 "ok" 或失败原因 */
    private fun probeTlsHandshake(host: String, timeoutMs: Int = 8000): String {
        val socket = try {
            (javax.net.ssl.SSLSocketFactory.getDefault().createSocket()) as javax.net.ssl.SSLSocket
        } catch (e: Exception) {
            return "无法创建 TLS 套接字：${e.message}"
        }
        return try {
            socket.soTimeout = timeoutMs
            socket.connect(java.net.InetSocketAddress(host, 443), timeoutMs)
            socket.sslParameters = socket.sslParameters.apply {
                serverNames = listOf(javax.net.ssl.SNIHostName(host))
            }
            socket.startHandshake()
            "ok"
        } catch (e: Exception) {
            (e.message ?: e.javaClass.simpleName).take(80)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 在线模式启动时 BDS 一定会碰的四个域名 */
    private val TLS_PROBE_HOSTS = listOf(
        "api.minecraftservices.com",
        "user.auth.xboxlive.com",
        "xsts.auth.xboxlive.com",
        "multiplayer.minecraft.net"
    )

    /**
     * 使用完全自定义命令启动 MC 服务端（高级选项：完全自定义启动命令模式）。
     * 直接执行用户提供的完整 shell 命令，不进行任何自动拼接。
     */
    fun startMcCustom(
        command: String,
        dirName: String,
        onExit: (Int) -> Unit,
        jvmFlavor: JvmFlavor = JvmFlavor.PatchedJdk,
        javaVendor: JavaVendor = JavaVendor.Termux
    ): Process = withLaunchLock {
        startMcCustomImpl(command, dirName, onExit, jvmFlavor, javaVendor)
    }

    /** [startMcCustom] 的实际实现；由 [withLaunchLock] 持锁调用。 */
    private fun startMcCustomImpl(
        command: String,
        dirName: String,
        onExit: (Int) -> Unit,
        jvmFlavor: JvmFlavor,
        javaVendor: JavaVendor
    ): Process {
        Log.i(TAG, "startMcCustom: command=$command dirName=$dirName vendor=$javaVendor")

        mcProcess?.let { if (it.isAlive) return it }

        val prefix = installer.rootDir.absolutePath
        val serverDir = serverDirFor(dirName)
        val logFile = prepareMcLogFile(serverDir)

        killOrphanMcProcess(serverDir)

        val compatUsr = "$prefix/data/data/com.termux/files/usr"
        // 自定义命令由用户自己写 java 路径，因此这里不解析 JDK 落点；只有预加载库
        // 需要按来源区分（FCL 的 JRE 自带 IPv6 支持，不需要兼容层）。
        val fullCmd = "export PATH='$prefix/bin:$prefix/usr/bin:$compatUsr/bin:$prefix/libexec:/system/bin:/system/xbin'; " +
            "export LD_LIBRARY_PATH='$prefix/lib:$compatUsr/lib:$prefix/usr/lib:/system/lib64'; " +
            preloadEnv(jvmFlavor, javaVendor) +
            "export FONTCONFIG_PATH='$prefix/etc/fonts'; " +
            "export FONTCONFIG_FILE='$prefix/etc/fonts/fonts.conf'; " +
            "export PREFIX='$prefix'; " +
            "export HOME='$prefix/home'; " +
            "export TMPDIR='$prefix/tmp'; " +
            "cd '$serverDir' && $command"

        Log.i(TAG, "startMcCustom full command: $fullCmd")
        // 自定义命令由用户自己写（如 `java -jar server.jar`），既不会有 `exec `，
        // 也不保证出现 `-jar`/`-cp` 标记 —— 只保留「执行段存在且非空」这一项检查，
        // 用来兜住「字符串拼接漏了 `+` 导致 `cd … && $command` 整段消失」。
        assertLaunchCommandComplete(
            fullCmd,
            "startMcCustom",
            requireExecPrefix = false,
            requireLauncherMarker = false
        )
        emitLog("[startMc] 自定义启动命令: $command")

        val pb = ProcessBuilder("/system/bin/sh", "-c", fullCmd).apply {
            redirectErrorStream(true)
            directory(serverDir)
            environment().putAll(executor.termuxEnv())
        }
        val process = pb.start()
        trackMcProcess(process, serverDir)

        Thread({
            // writer 必须声明在 try 之外：原先把声明放在 try 里，catch 分支拿不到它，
            // readLine() 一抛 IOException（进程被强杀）就漏掉一个 FileOutputStream 与一条管道，
            // 反复崩溃重启会把 FD 耗光。
            var writer: java.io.BufferedWriter? = null
            try {
                writer = java.io.BufferedWriter(java.io.OutputStreamWriter(
                    java.io.FileOutputStream(logFile, true), Charsets.UTF_8), 8192)
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                var lineCount = 0
                var quitGuardArmed = false
                while (line != null) {
                    executor.emit(line)
                    writer.appendLine(line)

                    // BDS 打完 "Quit correctly" 就 exit(0)。个别设备上 proot/box64 的
                    // 回收会卡住，进程挂着不退 —— 表现为服务端明明停了、UI 永远停在
                    // "启动中"（等 300s 超时）。这里兜一手：8 秒还活着就强制收尾。
                    if (!quitGuardArmed && line.contains("Quit correctly", ignoreCase = true)) {
                        quitGuardArmed = true
                        executor.emit("[bds] 服务端已正常退出")
                        Thread({
                            try {
                                Thread.sleep(8000)
                            } catch (e: InterruptedException) {
                                return@Thread
                            }
                            if (process.isAlive) {
                                runCatching { executor.emit("[bds] 进程未自行退出，已强制结束") }
                                runCatching { process.destroy() }
                            }
                        }, "bds-quit-guard").start()
                    }
                    if (++lineCount % 50 == 0) writer.flush()
                    line = reader.readLine()
                }
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "mc stdout reader error: ${e.message}")
            } finally {
                runCatching { writer?.close() }
            }
        }, "mc-stdout-reader").start()

        Thread({
            val code = process.waitFor()
            Log.w(TAG, "MC process exited code=$code")
            if (clearMcProcessIfCurrent(process)) onExit(code)
        }, "mc-watch").start()

        return process
    }

    /**
     * 最近一次「用户主动停止」请求的时间戳（0 = 从未请求过）。
     *
     * ## 为什么放在这里而不是 McServerController
     * 主动停止的入口不止一个：通知栏停止按钮、删除运行环境、备份前暂停……
     * 它们最终都汇聚到 [stopMc]。把标记打在**汇聚点**，就不会漏掉任何入口。
     *
     * 消费方是 `McServerController.createExitHandler`：它原本只凭
     * "退出发生在 20s 启动窗口内"就判异常退出，导致**用户在启动后 20 秒内
     * 点停止**时会误弹崩溃报告（报告里还会带上启动期间遗留的 JNA 报错日志，
     * 让人误以为是停止操作把服务端搞崩了）。
     */
    @Volatile
    var lastUserStopRequestAtMs: Long = 0L
        private set

    /** 标记"用户主动请求停止"，供崩溃判定区分「主动停」与「真崩溃」。 */
    fun markUserStopRequested() {
        lastUserStopRequestAtMs = System.currentTimeMillis()
        // 用户明确要停：立刻收起「启动在途」的保护，
        // 否则看门狗会因为保护期而迟迟不敢把状态切成已停止。
        clearStartInFlight()
    }

    /** 新一次启动时清掉标记，否则本轮真实崩溃会被误判成正常停止而漏报。 */
    fun clearUserStopRequest() {
        lastUserStopRequestAtMs = 0L
    }

    /**
     * 启动代际计数：每次「用户发起的启动」递增。
     *
     * ## 解决什么问题
     * `McServerController.start()` 的准备阶段可能长达数分钟（依赖安装、自动修复、
     * 下载核心）。这期间用户点「停止」时进程根本还不存在，`stopMcGraceful()` 只能
     * 打上停止标记就返回 true（UI 显示「已停止」）。但准备阶段并不会因此中断，
     * 它最终仍会走到 [launchMc] 并把服务端拉起来——于是「点了停止，服务器却启动了」，
     * 而且 [clearUserStopRequest] 还会顺手抹掉停止标记，连崩溃判定都失去依据。
     *
     * 现在的规则：
     *  - [beginStartRequest] 在**用户发起点**调用，返回本次启动的代际号；
     *  - [isStartCancelled] 在真正 spawn 进程之前调用，若期间用户请求过停止
     *    （或又发起了新的一次启动），本次启动即被放弃，不做任何启动动作。
     */
    @Volatile
    private var startGeneration: Long = 0L

    /**
     * 「启动在途」窗口起点（毫秒时间戳，0 表示没有启动在进行）。
     *
     * 为什么需要它：`McServerController.start()` 的准备阶段可能长达数分钟
     * （装依赖、修 rootfs、下载核心、切 Java），这期间**进程还没 spawn**，
     * [isMcRunning] 必然返回 false。前台服务的看门狗只会看 `/proc`，
     * 于是它按「进程不在 ⇒ 已停止」把 isRunning 改回 false ——
     * UI 上就出现「点启动后闪一下已停止，过一会儿又变回启动中」。
     *
     * 现在由 [markStartInFlight] / [clearStartInFlight] 圈出这段窗口，
     * 期间所有「进程不在就判停止」的逻辑都必须让位（见 McForegroundService 的看门狗）。
     */
    @Volatile
    private var startInFlightAtMs = 0L

    /**
     * 撤掉在途标记后的缓冲期终点。
     *
     * 进程刚 spawn 的几百毫秒里，[mcProcess] 可能还没登记完（另一条线程正在锁内 track），
     * 此刻探一次就是「不在」；给一小段缓冲期，避免这次误判把状态又打回已停止。
     */
    @Volatile
    private var startupGraceUntilMs = 0L

    /** 在途窗口的兜底上限：超过这么久还没 spawn，就不再拦着看门狗（多半是真卡死了）。 */
    private val START_IN_FLIGHT_MAX_MS = 15 * 60_000L

    /** 撤标记后的缓冲期。 */
    private val STARTUP_GRACE_MS = 6_000L

    /** 标记「一次启动正在进行中」；[McServerController.start] 进入准备阶段时调用。 */
    fun markStartInFlight() {
        startInFlightAtMs = System.currentTimeMillis()
        startupGraceUntilMs = 0L
    }

    /** 撤掉在途标记，同时开一小段缓冲期（见 [startupGraceUntilMs]）。 */
    fun clearStartInFlight() {
        if (startInFlightAtMs == 0L) return
        startInFlightAtMs = 0L
        startupGraceUntilMs = System.currentTimeMillis() + STARTUP_GRACE_MS
    }

    /**
     * 是否处在「启动尚未落地」的阶段：在途窗口内，或刚 spawn 完的缓冲期内。
     *
     * 处于该阶段时，**不能**因为「进程不在」就把服务器状态改成已停止。
     */
    fun isStartupPending(): Boolean {
        val startedAt = startInFlightAtMs
        if (startedAt != 0L && System.currentTimeMillis() - startedAt < START_IN_FLIGHT_MAX_MS) {
            return true
        }
        return System.currentTimeMillis() < startupGraceUntilMs
    }

    /** 用户发起的启动请求：递增代际并返回本次启动的代际号。 */
    fun beginStartRequest(): Long {
        // 新一次启动同时清掉停止标记：本轮之后真实崩溃必须能被判成异常退出。
        clearUserStopRequest()
        return ++startGeneration
    }

    /**
     * 本次启动是否已被取消（准备阶段期间用户点了停止，或有更新的一次启动接管）。
     *
     * @param startedAtGeneration [beginStartRequest] 返回的代际号
     */
    fun isStartCancelled(startedAtGeneration: Long): Boolean =
        startGeneration != startedAtGeneration || lastUserStopRequestAtMs != 0L

    /**
     * 停止 MC（兼容旧调用方）：优雅停止，最多等 5 秒，超时则强制 destroy。
     *
     * 备份 / 前台服务 / 计划任务等**无人值守**的路径继续用它 —— 那些场景里没人能
     * 再点一次按钮，必须自己兜底。用户手动点「停止」走的是 [stopMcGraceful]，
     * 强杀改由显式的「强制停止」按钮触发（见 [forceStopMc]）。
     */
    suspend fun stopMc(): Boolean =
        if (stopMcGraceful(STOP_GRACE_FALLBACK_MS)) true else forceStopMc()

    /**
     * 优雅停止：只向 stdin 发 `stop`，轮询等进程自己退出，**不做任何强杀**。
     *
     * 之所以把它和 [stopMc] 拆开：服务端收到 `stop` 后要先把世界落盘再退出，
     * 大世界实测可能要十几秒以上。旧行为是等 5 秒就 `destroyForcibly()`，
     * 世界没落完就被 SIGKILL —— 这正是「停止后丢存档」投诉的来源。
     * 现在改成：等多久由 [waitMs] 决定，超时也只是**如实返回失败**，
     * 由调用方（UI）告诉用户「还停不下来，可以点强制停止」，把是否承受数据风险
     * 的选择权交回给用户。
     *
     * @return true 表示进程确实已退出；false 表示超时仍未退出（进程仍在登记中，
     *         [forceStopMc] 仍能停掉它）。
     */
    suspend fun stopMcGraceful(waitMs: Long = STOP_GRACE_DEFAULT_MS): Boolean = withContext(Dispatchers.IO) {
        // 只要走到这里就一定是"主动停止"，先把标记打上，
        // 再发停止命令 —— 否则进程可能在标记写入前就退出了。
        markUserStopRequested()
        val proc = mcProcess ?: return@withContext true
        val serverDir = mcServerDir
        if (!proc.isAlive) {
            // The shell/PRoot wrapper may have exited while its Java child survived.
            // 只在"我们仍是当前登记进程"时才清孤儿——否则说明期间已有新进程接管。
            if (clearMcProcessIfCurrent(proc)) killOrphanMcProcess(serverDir)
            return@withContext true
        }
        try {
            mcStdin?.write("stop\n".toByteArray())
            mcStdin?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "stopMcGraceful: stdin write failed: ${e.message}")
        }
        // 轮询等待进程退出。这里刻意不设强杀兜底 —— 见方法注释。
        val deadline = System.currentTimeMillis() + waitMs
        while (proc.isAlive && System.currentTimeMillis() < deadline) {
            delay(100)
        }
        if (proc.isAlive) {
            Log.w(TAG, "stopMcGraceful: 进程在 ${waitMs}ms 内未退出，保持登记等待强制停止")
            emitLog("[stopMc] 服务端仍在关停（已等待 ${waitMs / 1000}s），可点「强制停止」立即结束")
            return@withContext false
        }
        // PRoot can leave its Java child alive after its outer process exits.
        // Clear it before returning so a subsequent start never doubles servers.
        //
        // 但必须先确认「当前登记的仍然是我们要停的那个进程」再清孤儿：
        // 用户在这段等待里点了「启动」的话，新进程的 cmdline 同样包含 serverDir，
        // 无条件 killOrphanMcProcess(serverDir) 会把刚起来的服务端直接杀掉——
        // 表现为「启动秒退、没有任何日志」，还会被上层误判成一次崩溃。
        if (clearMcProcessIfCurrent(proc)) {
            killOrphanMcProcess(serverDir)
        } else {
            Log.i(TAG, "stopMcGraceful: 停机期间已有新进程接管，跳过孤儿清理")
        }
        true
    }

    /**
     * 强制停止：立刻 `destroyForcibly()` 并清掉残留子进程，无任何等待门槛。
     *
     * 由用户显式确认后触发（UI 上必须先过一遍警告弹窗）。世界数据可能停在
     * 未落盘的状态 —— 这是用户已知并接受的风险，因此这里不做任何"温和"处理，
     * 也不加 5 秒等待，避免「点了强制停止还要再等」的二次困惑。
     */
    suspend fun forceStopMc(): Boolean = withContext(Dispatchers.IO) {
        markUserStopRequested()
        val proc = mcProcess
        val serverDir = mcServerDir
        if (proc != null && proc.isAlive) {
            Log.w(TAG, "forceStopMc: destroyForcibly")
            emitLog("[stopMc] 执行强制停止")
            runCatching { proc.destroyForcibly() }
            withTimeoutOrNull(STOP_FORCE_WAIT_MS) { while (proc.isAlive) delay(50) }
        }
        // 无论登记的是不是这个进程，都按目录清一遍孤儿：
        // 强制停止的语义就是「我现在就要它消失」，PRoot 残留的 Java 子进程同样要清。
        if (proc == null || clearMcProcessIfCurrent(proc)) {
            killOrphanMcProcess(serverDir)
        } else {
            Log.i(TAG, "forceStopMc: 停机期间已有新进程接管，跳过孤儿清理")
        }
        true
    }

    private fun trackMcProcess(process: Process, serverDir: File) {
        synchronized(mcProcessLock) {
            mcProcess = process
            mcPid = processPid(process) ?: findMcChildPid()
            mcServerPid = null
            mcServerDir = serverDir
            mcStdin = process.outputStream
        }
    }

    /** A late watcher from an older start must not clear a newer server process. */
    private fun clearMcProcessIfCurrent(process: Process): Boolean = synchronized(mcProcessLock) {
        if (mcProcess !== process) return@synchronized false
        mcProcess = null
        mcPid = null
        mcServerPid = null
        mcServerDir = null
        mcStdin = null
        true
    }

    /** 向 MC 控制台发指令（写入 stdin） */
    fun sendCommand(line: String) {
        val cmd = if (line.startsWith("/")) line.substring(1) else line
        try {
            mcStdin?.write("$cmd\n".toByteArray())
            mcStdin?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "sendCommand failed: ${e.message}")
        }
    }

    /** 检查 MC 进程是否存活 */
    /** 有 MC 进程在跑（不限是哪个实例）。 */
    fun isMcRunning(): Boolean {
        return mcProcess?.isAlive ?: false
    }

    /**
     * 指定服务器目录的服务端是否正在运行。
     *
     * [isMcRunning] 是**全局**判断：跑的是 A 实例时，对 B 实例的配置文件做「运行中禁止写入」
     * 会把无辜的 B 一起拦掉（PNX 切换世界就是这样必然失败的），而且报错文案还会把用户
     * 引导去查存储权限。这里给出按实例判定的版本。
     */
    fun isMcRunningIn(dirName: String): Boolean {
        if (mcProcess?.isAlive != true) return false
        return mcServerDir?.absolutePath?.let { serverDirFor(dirName).absolutePath == it } ?: false
    }

    /**
     * 读取 MC 进程当前真实内存占用（RSS，单位 MB）。
     * Android 的 java.lang.Process 无公开 pid()，改为遍历 /proc 查找
     * Java 子进程，读取其 stat 中 rss 字段（剥离 comm 后 0-based 索引 21）。
     * 常规权限即可读取（app 自有子进程），兼容性好。
     * @return MB 值；服务进程未创建或读取失败时返回 0
     */
    fun mcProcessMemoryMb(): Long {
        return try {
            // 启动器/安装器也可能是 Java 进程，不能在 MC 尚未创建时全局兜底扫描。
            if (mcProcess?.isAlive != true) return 0L
            processStat(mcProcess)?.let { stat ->
                val rssKb = procVmRssKb(statPid(stat))
                if (rssKb > 0L) return rssKb / 1024
                // /proc/<pid>/status 不可读时回退到 RSS 页数；页大小用系统值而非固定 4096。
                val rssPages = stat.getOrNull(21)?.toLongOrNull() ?: 0L
                if (rssPages > 0) {
                    val pageBytes = systemPageBytes()
                    return rssPages * pageBytes / (1024 * 1024)
                }
            }
            0L
        } catch (e: Exception) {
            0L
        }
    }

    /** 进程 CPU 使用率（%）：读取 MC Java 进程自身 utime+stime 增量，按可用核心数归一化到 0..100。
     *  服务器未运行或基线不可用返回 null。 */
    fun mcProcessCpuPercent(): Int? {
        val running = mcProcess?.isAlive == true
        if (!running) {
            // 进程已停止：把基线一并清空，避免下次启动复用陈旧基线算出离谱数字。
            cpuBaselinePid = null
            cpuBaselineAtElapsedMs = 0L
            cpuBaselineJiffies = 0L
            return null
        }
        return try {
            val stat = processStat(mcProcess) ?: return null
            val pid = statPid(stat)
            val jiffies = (stat.getOrNull(11)?.toLongOrNull() ?: 0L) +
                (stat.getOrNull(12)?.toLongOrNull() ?: 0L)
            val now = android.os.SystemClock.elapsedRealtime()
            val baselinePid = cpuBaselinePid
            /*
             * 判定是否需要重新建立基线。
             *
             * 注意这里**不能**用 `cpuBaselineJiffies <= 0L` 作为「基线缺失」的条件：
             * 进程刚启动时 utime+stime 真的可能是 0，把 0 当成「没基线」会让
             * 每次采样都重置基线、永远算不出读数（实测症状：CPU 一直显示 0%）。
             * 改为用 cpuBaselineAtElapsedMs 判断「是否曾建立过基线」。
             */
            if (baselinePid != pid || cpuBaselineAtElapsedMs <= 0L) {
                cpuBaselinePid = pid
                cpuBaselineJiffies = jiffies
                cpuBaselineAtElapsedMs = now
                return null
            }
            val deltaJiffies = jiffies - cpuBaselineJiffies
            val deltaMs = now - cpuBaselineAtElapsedMs
            val percent = McProcessCpuMath.percent(
                jiffiesNow = jiffies,
                jiffiesPrev = cpuBaselineJiffies,
                elapsedMs = deltaMs,
                tickHertz = clockTicksPerSecond(),
                cores = availableProcessorCount()
            )
            // 计数器回退或采样间隔无效时保留旧基线，等下一窗口恢复。
            if (percent == null && deltaJiffies >= 0L && deltaMs > 0L) {
                cpuBaselinePid = pid
                cpuBaselineJiffies = jiffies
                cpuBaselineAtElapsedMs = now
            }
            if (percent != null) {
                cpuBaselinePid = pid
                cpuBaselineJiffies = jiffies
                cpuBaselineAtElapsedMs = now
            }
            percent
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 整机 CPU 总使用率（%），0..100。
     *
     * ## 数据源优先级（2026-10 修正：主次曾经颠倒）
     * 1. **procfs 增量**（主）：读 `/proc/stat` 首行 `cpu` 的累计计数，
     *    与上一次采样做差算「非空闲增量 / 总增量」。这是与系统工具同源的口径，
     *    反映的是**真实调度结果**。
     * 2. **cpufreq 频率比**（回退）：仅在 /proc/stat 不可用（部分内核/沙箱不暴露）
     *    或首次采样尚无基线时使用。它读的是调频器**请求**的频率，与真实占用率只是
     *    相关而非等价：schedutil/boost 会让空闲时也维持高频率、`cpuinfo_max_freq`
     *    在很多设备上是 boost 上限、big.LITTLE 各核区间不同却被朴素平均——
     *    因此只能当作粗略代理，**不作为主数据源**。
     *
     * `/proc/stat` 首次调用只建立基线并返回 null（没有可比较的前值），
     * 此时会落到 cpufreq 先给一个粗略值，下一次采样起即由主路径接管。
     * 两条路径都拿不到值时返回 null，UI 显示 `--`。
     *
     * **不按核心数归一化** —— 整机口径本身就是 0..100（见 [DeviceCpuMath]）。
     * 与 [mcProcessCpuPercent] 不同：这里不关心服务端是否运行，未运行时也照常给出整机读数。
     */
    fun deviceCpuPercent(): Int? {
        // 主路径：/proc/stat 两次采样增量（与系统工具同源，反映真实调度结果）。
        runCatching { deviceCpuPercentFromProcStat() }.getOrNull()?.let { return it }
        // 回退路径：cpufreq 频率比，仅在无 /proc/stat 基线或读不到时提供一个粗略值。
        return runCatching { deviceCpuPercentFromFreqs() }.getOrNull()
    }

    /**
     * 用各 CPU 核心的 cpufreq 频率比估算整机使用率（**回退路径**，非主数据源）。
     *
     * 只读 sysfs（不涉及 procfs 权限），用于 /proc/stat 不可用或尚无基线时给一个粗略值。
     * 准确性有限：读的是调频器请求的频率而非真实占用（详见 [deviceCpuPercent] 注释）。
     * 读不到任何核心时返回 null。
     * 结果按 1 秒缓存：sysfs 是小文件但核心多、调用频繁时没必要每次都遍历，
     * 采样周期本身是 15s 级，1s 缓存完全够用且能挡住同一帧内的重复读取。
     */
    private fun deviceCpuPercentFromFreqs(): Int? {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = cachedFreqCpuPercent
        if (cached != null && now - cachedFreqCpuAtMs < FREQ_CPU_CACHE_MS) return cached
        val cpuBase = File("/sys/devices/system/cpu")
        val coreDirs = cpuBase.listFiles { f ->
            f.isDirectory && f.name.startsWith("cpu") &&
                f.name.length > 3 && f.name.substring(3).all { it.isDigit() }
        } ?: return null
        if (coreDirs.isEmpty()) return null
        val freqs = coreDirs.mapNotNull { dir ->
            val freqDir = File(dir, "cpufreq")
            val cur = readLongFromFile(File(freqDir, "scaling_cur_freq"))
            val min = readLongFromFile(File(freqDir, "cpuinfo_min_freq"))
            val max = readLongFromFile(File(freqDir, "cpuinfo_max_freq"))
            // 任一节点缺失（readLongFromFile 返回 -1）就跳过该核。
            if (cur < 0L || min < 0L || max < 0L) null
            else DeviceCpuMath.CoreFreq(curKhz = cur, minKhz = min, maxKhz = max)
        }
        val percent = DeviceCpuMath.percentFromFreqs(freqs) ?: return null
        cachedFreqCpuPercent = percent
        cachedFreqCpuAtMs = now
        return percent
    }

    /** 读 sysfs 中的整型数值；文件不存在或读取失败返回 -1。 */
    private fun readLongFromFile(file: File): Long = try {
        if (!file.isFile) -1L
        else file.bufferedReader().use { it.readLine()?.trim()?.toLongOrNull() } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    /** cpufreq 读数的复用窗口：采样周期是 15s 级，1s 内没必要重复遍历 sysfs。 */
    private val FREQ_CPU_CACHE_MS = 1_000L

    /**
     * 用 `/proc/stat` 首行的累计计数增量估算整机使用率（cpufreq 不可用时的回退）。
     *
     * - 首次调用只建立基线返回 null。
     * - 读取/解析失败返回 null，但**保留旧基线**，否则一次偶发失败会让下一次读数也被吞掉。
     */
    private fun deviceCpuPercentFromProcStat(): Int? {
        val statFile = File("/proc/stat")
        val firstLine: String? = if (statFile.isFile) statFile.bufferedReader().use { it.readLine() } else null
        val sample: DeviceCpuMath.Sample? = firstLine?.let { DeviceCpuMath.parse(it) }
        if (sample == null) {
            return null
        }
        val prev = deviceCpuBaseline
        deviceCpuBaseline = sample
        return if (prev == null) null else DeviceCpuMath.percent(sample, prev)
    }

    private var cachedCoreCount: Int = 0

    /** 进程可用的逻辑核心数（cpuset 限制内），至少为 1。 */
    private fun availableProcessorCount(): Int {
        if (cachedCoreCount <= 0) {
            cachedCoreCount = runCatching {
                Runtime.getRuntime().availableProcessors()
            }.getOrDefault(1).coerceAtLeast(1)
        }
        return cachedCoreCount
    }

    private fun statPid(stat: List<String>): Int =
        stat.getOrNull(0)?.toIntOrNull() ?: 0

    /** /proc/<pid>/status 的 VmRSS（kB），避免 16K 页设备上页数×4096 算错内存。 */
    private fun procVmRssKb(pid: Int): Long = runCatching {
        if (pid <= 0) return@runCatching 0L
        File("/proc/$pid/status").takeIf { it.isFile }?.readText()?.lineSequence()?.firstNotNullOfOrNull { line ->
            line.trim().takeIf { it.startsWith("VmRSS:") }?.substringAfter("VmRSS:")
                ?.substringBefore(" kB")?.trim()?.toLongOrNull()
        } ?: 0L
    }.getOrDefault(0L)

    /** 系统页大小（字节），默认按常见 4096 兜底。 */
    private fun systemPageBytes(): Long = runCatching {
        android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
    }.getOrDefault(4096L)

    /** 系统时钟频率（HZ），进程 stat 的 utime/stime 以其为计数单位。 */
    private fun clockTicksPerSecond(): Long = runCatching {
        android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
    }.getOrDefault(100L)

    /** Read the launched Java process, which may be several layers below shell/proot. */
    private fun processStat(process: Process?): List<String>? {
        if (process?.isAlive != true) return null
        return try {
            val knownServerPid = mcServerPid
            if (knownServerPid != null && File("/proc/$knownServerPid/stat").isFile) {
                readProcStat(knownServerPid)
            } else {
                val rootPid = mcPid ?: processPid(process)?.also { mcPid = it }
                    ?: findMcChildPid()?.also { mcPid = it }
                if (rootPid == null) {
                    null
                } else {
                    // Only measure the Java server descendant. The shell/PRoot wrapper
                    // is not the server and must not be reported as server memory.
                    val pid = findServerPid(rootPid)
                    if (pid == null) null else {
                        mcServerPid = pid
                        readProcStat(pid)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readProcStat(pid: Int): List<String>? {
        val stat = File("/proc/$pid/stat").readText()
        val end = stat.lastIndexOf(')')
        return if (end < 0) null else stat.substring(end + 1).trim().split(Regex("\\s+"))
    }

    /** MC 进程当前 PID（已缓存或现场解析），供 VM/UI 显示采样状态。 */
    fun currentMcPid(): Int? = runCatching {
        val stat = processStat(mcProcess) ?: return@runCatching null
        stat.getOrNull(0)?.toIntOrNull()
    }.getOrNull()

    private data class ProcEntry(val pid: Int, val parentPid: Int, val comm: String, val cmdline: String)

    private fun processPid(process: Process): Int? = runCatching {
        (process.javaClass.getMethod("pid").invoke(process) as? Long)?.toInt()
    }.getOrNull()

    /** Finds the Java descendant instead of measuring the shell or proot wrapper. */
    private fun findServerPid(rootPid: Int): Int? = runCatching {
        val entries = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            if (!dir.name.all(Char::isDigit)) return@mapNotNull null
            // 每条 /proc 记录单独兜异常：进程随时可能退出，stat/cmdline 会在
            // 读取途中消失（NoSuchFileException）。原先只要有一条读失败，
            // 整个 runCatching 就返回 null —— 表现为内存恒为 0、CPU 空白，
            // 而服务端其实跑得好好的，属于极难定位的显示回退。
            val raw = try {
                File(dir, "stat").takeIf { it.isFile }?.readText() ?: return@mapNotNull null
            } catch (_: Exception) { return@mapNotNull null }
            val open = raw.indexOf('(')
            val close = raw.lastIndexOf(')')
            if (open < 0 || close <= open) return@mapNotNull null
            val fields = raw.substring(close + 1).trim().split(Regex("\\s+"))
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            val parent = fields.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val cmdline = try {
                File(dir, "cmdline").readText()
            } catch (_: Exception) { return@mapNotNull null }
            ProcEntry(pid, parent, raw.substring(open + 1, close), cmdline)
        }
        val byPid = entries.associateBy { it.pid }
        fun isDescendant(pid: Int): Boolean {
            var current = byPid[pid]?.parentPid
            repeat(32) {
                if (current == rootPid) return true
                current = byPid[current]?.parentPid
            }
            return false
        }
        entries.filter { it.pid == rootPid || isDescendant(it.pid) }
            .filter { it.comm == "java" || it.cmdline.substringBefore('\u0000').substringAfterLast('/') == "java" }
            // 多个 java 后代时优先选「真正在跑服务端」的那个。
            // 来宾链路下 cmdline 里是 `/srv/mineserve` 或 rootfs 内路径，
            // 原先只匹配宿主 installer.rootDir 会让容器场景全部退化成等权，
            // 从而可能选中 installer 那个短命进程而不是服务端。
            .maxByOrNull {
                when {
                    it.cmdline.contains(".jar") -> 3
                    it.cmdline.contains(GUEST_SERVER_DIR) -> 2
                    it.cmdline.contains(installer.rootDir.absolutePath) -> 2
                    else -> 1
                }
            }
            ?.pid
    }.getOrNull()

    /** Android exposes our app's child relationship in procfs even though Process.pid() is absent. */
    private fun findMcChildPid(): Int? = try {
        val parentPid = android.os.Process.myPid().toString()
        File("/proc").listFiles()?.firstNotNullOfOrNull { dir ->
            if (!dir.name.all(Char::isDigit)) return@firstNotNullOfOrNull null
            val stat = File(dir, "stat").takeIf { it.isFile }?.readText() ?: return@firstNotNullOfOrNull null
            val end = stat.lastIndexOf(')').takeIf { it > 0 } ?: return@firstNotNullOfOrNull null
            val fields = stat.substring(end + 1).trim().split(Regex("\\s+"))
            if (fields.getOrNull(1) != parentPid) return@firstNotNullOfOrNull null
            val cmdline = File(dir, "cmdline").takeIf { it.isFile }?.readText().orEmpty()
            // 判据必须同时接受宿主 prefix 与来宾路径：PRoot 会改写 cmdline，
            // 容器里的 java 既可能显示 rootfs 下的绝对路径，也可能显示
            // `/srv/mineserve`，只认 installer.rootDir 会漏认（→ mcPid 为 null，
            // 进而内存/CPU/TPS 全部取不到）。GUEST_SERVER_DIR 是 `/srv/mineserve`，
            // 它不可能出现在非容器进程里，因此不会误认。
            if (cmdline.contains(installer.rootDir.absolutePath) ||
                cmdline.contains(GUEST_SERVER_DIR)
            ) dir.name.toIntOrNull() else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 探测 java 可执行文件路径。
     * 优先级：java-25 > java-21 > java-17，覆盖各 MC 版本需求。
     * 1. $PREFIX/bin/java（dpkg post-install 正常情况下存在）
     * 2. $PREFIX/lib/jvm/java-{25,21,17}-openjdk/bin/java
     * 3. $PREFIX/data/data/com.termux/files/usr/lib/jvm/java-{25,21,17}-openjdk/bin/java
     *    （dpkg-deb -x 解压 deb 包时，由于 compat 符号链接被覆盖，文件落在了此路径）
     * 4. 通过 find 命令查找
     * 找不到时返回 "java"，让 shell 报错（便于诊断）
     */
    /**
     * 解析某个运行环境的 `java` 可执行文件路径。
     *
     * [JavaVendor.Fcl] 与 Termux 来源的落点完全不同，必须分开解析（见 [JavaVendor]）：
     * FCL 那套是外部 JRE，只有 `filesDir/jvm-fcl/java-XX/bin/java` 一个位置，
     * 不存在 compat / 多候选路径这种事。`java.home` 由 JLI 从自身路径反推，
     * 所以「找对了 bin/java」就等于「JAVA_HOME 也对」，不需要额外传参。
     */
    private fun resolveJavaPath(
        prefix: String,
        version: JavaVersion? = null,
        vendor: JavaVendor = JavaVendor.Termux
    ): String? {
        if (vendor == JavaVendor.Fcl) {
            val ordered = if (version != null) listOf(version) else JavaVendor.Fcl.versions.reversed()
            return ordered.firstOrNull { fclJre.isInstalled(it) }?.let { fclJre.javaBinary(it).absolutePath }
        }
        // PRoot 来源的 java **不在宿主文件系统上**有可执行路径：它是 rootfs 里的
        // `/usr/lib/jvm/.../bin/java`，只能经 proot 进入容器执行。
        // 因此这里必须显式返回 null，而不是往下走去扫宿主候选路径 ——
        // 否则会命中一个「碰巧装过的 Termux JDK」，让宿主链路被无声启用，
        // 进而出现「选了 PRoot 却按宿主 JVM 跑」这种极难察觉的行为错位。
        if (vendor == JavaVendor.Proot) return null
        if (version != null) {
            return javaCandidates(version).map { "$it/bin/java" }
                .firstOrNull {
                    val file = File(it)
                    file.exists() && (version == JavaVersion.Java8 || file.canExecute())
                }
        }
        // 候选路径列表（含 compat 目录下的实际路径）
        val candidates = buildList {
            add("$prefix/bin/java")
            JavaVersion.values().sortedByDescending { it.ordinal }.forEach { supportedVersion ->
                addAll(javaCandidates(supportedVersion).map { "$it/bin/java" })
            }
            // 兼容旧环境中曾安装的 Java 21。
            add("$prefix/lib/jvm/java-21-openjdk/bin/java")
            add("$prefix/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/bin/java")
            add("/system/bin/java")
        }
        for (path in candidates) {
            val f = File(path)
            if (f.exists() && f.canExecute()) {
                Log.i(TAG, "resolveJavaPath: found at $path")
                return path
            }
            if (f.exists()) {
                Log.w(TAG, "resolveJavaPath: $path exists but not executable")
            }
        }

        // 通过 find 命令在整个 $PREFIX 下查找 java
        try {
            val pb = ProcessBuilder("/system/bin/sh", "-c",
                "find '$prefix' -name 'java' -type f 2>/dev/null | head -5")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            Log.i(TAG, "resolveJavaPath: find result = '$out'")
            if (out.isNotEmpty()) {
                val firstPath = out.lineSequence().firstOrNull { it.isNotEmpty() } ?: ""
                if (firstPath.isNotEmpty() && File(firstPath).exists()) {
                    return firstPath
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveJavaPath: find failed: ${e.message}")
        }

        // 兜底：返回 "java"，让 shell 报错
        Log.w(TAG, "resolveJavaPath: java not found in any candidate path, fallback to 'java'")
        emitLog("[startMc] 错误: java 未找到，请检查 openjdk-17 是否已安装")
        return candidates.firstOrNull { File(it).exists() && File(it).canExecute() }
    }

    /**
     * 创建 world 目录快照（zip 打包）。
     * 快照保存到 /home/snapshots/world_yyyyMMdd_HHmmss.zip
     * 创建后按 [maxSnapshots] 清理最旧的快照（0 表示不清理）。
     * 返回快照文件路径，失败返回 null。
     */
    fun createSnapshot(maxSnapshots: Int = 0, dirName: String = "default"): String? {
        val serverDir = serverDirFor(dirName)
        // MC 1.16+ 维度目录与 world 平级：world / world_nether / world_the_end
        val worlds = listOf("world", "world_nether", "world_the_end", "worlds")
            .map { File(serverDir, it) }
            .filter { it.isDirectory }
        val snapshotDir = File(installer.rootDir, "home/snapshots").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(snapshotDir, "world_$ts.zip")

        if (worlds.isEmpty()) {
            Log.w(TAG, "createSnapshot: world 目录不存在")
            return null
        }
        return try {
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                // 同步备份主世界、地狱、末地三个维度目录（存在的才打包，zip 保留目录层级）
                worlds.forEach { root ->
                    root.walkTopDown().forEach { file ->
                        val relPath = file.relativeTo(serverDir).path
                        if (file.isDirectory) {
                            zos.putNextEntry(ZipEntry("$relPath/"))
                            zos.closeEntry()
                        } else {
                            zos.putNextEntry(ZipEntry(relPath))
                            FileInputStream(file).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            Log.i(TAG, "快照已创建: ${outFile.absolutePath} (${outFile.length()} 字节)")
            // 按数量上限清理旧快照
            if (maxSnapshots > 0) {
                cleanupOldSnapshots(snapshotDir, maxSnapshots)
            }
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "createSnapshot failed: ${e.message}", e)
            // 失败必须删掉半截 zip：它的名字匹配 listSnapshots 的 *.zip 枚举，
            // 留着会被当成一份合法快照，直到还原时才炸。
            runCatching { outFile.delete() }
            null
        }
    }

    /**
     * 清理旧快照：按文件名时间戳倒序排列，保留最新的 [keepCount] 个，删除其余。
     * 删除失败时记录警告但不影响主流程。
     */
    private fun cleanupOldSnapshots(snapshotDir: File, keepCount: Int) {
        val files = snapshotDir.listFiles { f -> f.isFile && f.name.matches(Regex("world_\\d{8}_\\d{6}\\.zip")) }
            ?: return
        if (files.size <= keepCount) return
        // 按文件名时间戳倒序（最新在前）
        val sorted = files.sortedByDescending { it.name }
        val toDelete = sorted.drop(keepCount)
        var deleted = 0
        for (f in toDelete) {
            try {
                if (f.delete()) deleted++
                else Log.w(TAG, "cleanupOldSnapshots: 删除失败 ${f.name}")
            } catch (e: Exception) {
                Log.w(TAG, "cleanupOldSnapshots: 删除异常 ${f.name}: ${e.message}")
            }
        }
        if (deleted > 0) {
            Log.i(TAG, "cleanupOldSnapshots: 已清理 $deleted 个旧快照（保留 $keepCount 个）")
        }
    }

    companion object {
        private const val TAG = "TermuxRuntime"

        /**
         * `-XX:MaxMetaspaceSize` 的下限（MB）。
         *
         * 实际取值是 `max(768, -Xmx)`。设下限是因为：
         * 1. 原版/小型插件服只需要几十 MB，768m 只是天花板，不会被实际占用；
         * 2. 大型 Forge 整合包（200+ mod）实测需要 1GB 级别，256m 必崩。
         * 见 [memoryFootprintArgs] 的说明。**不要**再降回 256m。
         */
        private const val METASPACE_FLOOR_MB = 768

        /**
         * 宿主服务端目录在 PRoot 来宾里的挂载点。
         *
         * 整个方案的关键约定：`--bind=<宿主 serverDir>:/srv/mineserve`
         * 让服务端目录**原地**进入容器，因此世界、插件、配置、备份、日志
         * 都还在原本的宿主路径上，Worlds / BackupManager / PluginManager /
         * 文件管理 / FTP / MCP 全部无需改动。
         *
         * 提为常量是因为它被启动、installer、孤儿清理三处共用；
         * 任何一处写成别的字面量都会造成「孤儿进程匹配不到」这类静默问题。
         */
        const val GUEST_SERVER_DIR = "/srv/mineserve"

        /**
         * 应用侧捕获服务端控制台输出的文件（相对 serverDir）。
         *
         * 刻意不用 `logs/latest.log`：那个文件归服务端的 log4j 所有，
         * 应用往里 append 会出现每行两份。
         */
        private const val MC_CONSOLE_LOG_NAME = "logs/mineserve-console.log"

        /**
         * 按偏移增量读日志时最多回看的字节数。
         *
         * 「清除」之后可能隔很久才回到终端页，这段时间的增量可能有几十 MB，
         * 而界面只要最后几百行。窗口之外的内容读进来也会被 takeLast 丢掉，
         * 白白占一次 IO 与内存。
         */
        private const val LOG_TAIL_WINDOW_BYTES = 256L * 1024L

        /**
         * 用户手动点「停止」后等待服务端自行退出的时长。
         *
         * 取 3 分钟：远大于大世界落盘所需的十几秒，又不会让「停止中」无限转下去。
         * 超时不会强杀，而是把「要不要强制停止」交给用户（见 [stopMcGraceful]）。
         */
        private const val STOP_GRACE_DEFAULT_MS = 180_000L

        /** 无人值守路径（备份 / 前台服务）的兜底等待时长：超时就强杀，见 [stopMc]。 */
        private const val STOP_GRACE_FALLBACK_MS = 5_000L

        /** 强制停止后等待进程真的消失的时间；等不到也照常返回。 */
        private const val STOP_FORCE_WAIT_MS = 3_000L
    }
}

/**
 * java argfile 引用标记：`@` 后跟一个不含空白的路径。
 *
 * Forge 1.17+ / NeoForge 的官方启动方式是 `java @libraries/.../unix_args.txt`，
 * 命令里不会出现 `-jar` / `-cp`。识别这个标记是为了不把合法启动判成"命令不完整"。
 */
private val ARG_FILE_REFERENCE = Regex("""(^|\s)@[^\s'"]+""")

/**
 * 把若干预加载库拼成**一条** `export LD_PRELOAD='a:b'; ` 片段（空列表返回空串）。
 *
 * 为什么必须是一个函数而不是各写各的：`LD_PRELOAD` 是普通环境变量，
 * **后一条 export 覆盖前一条，而不是追加**。所以「先 heaptagfix 再 ipv6fix」
 * 写两遍 export 的结果是只剩最后一个，前一个被静默丢掉 —— 没有任何报错，
 * 只能靠「内存怎么又比别人高」这种间接现象事后发现。
 *
 * 纯函数，便于单元测试（见 `TermuxRuntimePreloadTest`）。
 */
internal fun preloadExportFragment(libs: List<String>): String {
    val cleaned = libs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (cleaned.isEmpty()) return ""
    return "export LD_PRELOAD='${cleaned.joinToString(":")}'; "
}

/**
 * 判断拼好的启动命令是否**完整**（纯函数，便于单元测试）；返回问题列表，空表示通过。
 *
 * 这些命令是十几段字符串用 `+` 拼出来的，漏掉一个 `+` 会让
 * `cd '<serverDir>' && exec '<java>' ... -jar server.jar nogui` **整段消失**，
 * 命令只剩一串 `export` 赋值 → shell 执行完自然退出、java 从未运行 →
 * 表现为「点启动秒退、无任何日志」。见 [TermuxRuntime.assertLaunchCommandComplete]。
 *
 * 判定只针对「被截断」这一种故障，必须放行所有合法启动方式：
 * `-jar` / `-cp` / `@参数文件`（Forge 1.17+、NeoForge）/ 自定义命令（由调用方关掉严格项）。
 *
 * @param command 拼好的完整 shell 命令
 * @param requireExecPrefix 是否要求执行段带 `exec `（自定义命令模式传 false）
 * @param requireLauncherMarker 是否要求出现启动器特征（自定义命令模式传 false）
 */
internal fun launchCommandProblems(
    command: String,
    requireExecPrefix: Boolean = true,
    requireLauncherMarker: Boolean = true
): List<String> {
    val problems = mutableListOf<String>()
    // 执行段 = 最后一个 `&&` 之后的内容。所有自动拼接的命令都以
    // `cd '<serverDir>' && <exec 启动程序>` 收尾；这段消失即为截断。
    val tail = command.substringAfterLast("&&").trim()
    if (!command.contains("&&") || tail.isEmpty()) {
        problems += "缺少 'cd … && …' 执行段（拼接时被截断，只剩下环境变量赋值）"
    } else if (requireExecPrefix && !tail.contains("exec ")) {
        problems += "缺少 'exec '（执行段没有要启动的程序，shell 会执行完赋值后直接退出）"
    }
    if (requireLauncherMarker) {
        val hasJar = command.contains("-jar ")
        val hasClasspath = command.contains(" -cp ") || command.contains(" -classpath ")
        val hasArgFile = ARG_FILE_REFERENCE.containsMatchIn(command)
        if (!hasJar && !hasClasspath && !hasArgFile) {
            problems += "缺少 '-jar' / '-cp' / '@参数文件'（看不出要启动哪个服务端）"
        }
    }
    return problems
}

/**
 * 判断一次容器命令的失败是否由 **proot 自己崩溃** 引起。
 *
 * 判据两条，任一条成立即认为可换参数重试：
 *  - 退出码是「被信号终止」（128+sig）：`proot warning: signal 11 received from
 *    process -1` 之后 proot 自己 SEGV，Java 侧看到的就是 139；OOM 被杀是 137。
 *  - 输出里出现 proot 的崩溃告警——脚本里的 `exit 11/12/13` 会把信号退出码盖掉，
 *    所以不能只信退出码。
 *
 * 超时（124）**不算**：换参数只会更慢。
 */
internal fun isProotCrashSignal(exitCode: Int, output: String?): Boolean {
    if (exitCode == 124) return false
    if (exitCode in PROOT_SIGNAL_EXIT_CODES) return true
    val text = output ?: return false
    return PROOT_CRASH_MARKERS.any { text.contains(it) }
}

/**
 * 被信号终止的退出码，只收「proot 自己崩」这一类：SEGV(11) / BUS(7) / ABRT(6) /
 * ILL(4) / TRAP(5)。
 *
 * **137（SIGKILL）刻意不收**：它几乎总是系统 OOM 或用户强杀，换 proot 参数救不了，
 * 应当提示清理内存/存储，而不是骗用户再等一轮 15 分钟的重试。
 */
private val PROOT_SIGNAL_EXIT_CODES = setOf(139, 135, 134, 133, 132)

/** proot 崩溃时打在 stderr 上的特征串（会随 redirectErrorStream 合并进输出）。 */
private val PROOT_CRASH_MARKERS = listOf(
    "signal 11 received from process",
    "signal 7 received from process",
    "signal 6 received from process",
    "Segmentation fault"
)

/**
 * 容器可用性探针。只判断「能进容器、shell 可执行」，不碰网络与 apt。
 */
private const val SHELL_PROBE = "test -x /bin/sh && exit 0"

/**
 * 探针的超时。
 *
 * 原来给 60 秒，在真机上太紧：刚解压出来的 rootfs 有上万个小文件，proot 首次
 * 进入要逐个处理 `--link2symlink` 与 ptrace 初始化，慢设备上超过一分钟很常见。
 * 一旦超时就会把**刚装好的** rootfs 判为不可用，于是重建、再超时、再重建 ——
 * 用户看到的就是「进度条停在部署阶段不动」。给到 3 分钟能覆盖真实设备。
 */
private const val ROOTFS_PROBE_TIMEOUT_MS = 180_000L

/** 容器内装 JDK 需要的最小可用空间（MB）：openjdk-21-jdk 解包 ~500MB + apt 缓存 + 依赖。 */
internal const val CONTAINER_INSTALL_MIN_FREE_MB = 1536

/**
 * BDS 运行环境需要的最小可用空间（MB）。
 *
 * box64 解包后约 30MB，`bedrock_server` 的运行副本约 244MB（1.21+ 是静态链接的
 * 单文件），再加压缩包与临时文件。空间不够时复制会在中途失败，
 * 用户只会看到「启动失败」，所以在开始前就拦下来。
 */
internal const val BDS_RUNTIME_MIN_FREE_MB = 600

/**
 * 把容器安装的退出码翻成用户能照做的提示。
 *
 * 为什么不能只显示 `exit=1`：apt 与 proot 的退出码语义完全不同 —— 脚本自己
 * `exit 11/12/13` 表示走到了第几步失败，139/137 是 proot 被信号杀掉。混成一句
 * 「校验失败」时，用户只能反复重试同一个注定失败的动作；分开讲清才能让他
 * 选对下一步（清存储 / 换 Pojav JRE / 修复容器 / 等网络）。
 */
internal fun prootInstallFailureMessage(displayName: String, exitCode: Int, tail: String?): String {
    if (isProotCrashSignal(exitCode, tail)) {
        return "$displayName 安装失败：proot 在容器里崩溃（signal 11，exit=$exitCode），" +
            "已自动尝试全部兼容模式仍失败。可改用 Pojav JRE 的同一版本，或更换发行版后重试"
    }
    // 退出码 13 涵盖的原因太多（索引下载失败 / 解包失败 / 装完仍缺文件），
    // 先让脚本自己报的那一句说话，实在认不出来才退回按码解释。
    val text = tail.orEmpty()
    val detail = when {
        text.contains("容器内的包状态已损坏") || text.contains("记录为已安装但 java 文件缺失") ->
            "$displayName 安装失败：容器里这个 JDK 的记录是「已安装」但文件缺失，" +
                "apt 反复重装都补不回来。请删除该容器后重新安装（服务器数据不受影响）"
        text.contains("apt-get update 失败") ->
            "$displayName 安装失败：软件源索引下载失败，请检查网络或稍后重试"
        text.contains("与期望版本不符") ->
            "$displayName 安装失败：装上的 java 版本与所选不符，请换一个 Java 版本或修复容器"
        else -> null
    }
    if (detail != null) return detail

    return when (exitCode) {
        11 -> "$displayName 安装失败：容器内的软件包事务停在半完成状态，无法自动恢复（exit=11）。" +
            "请到运行环境里执行一次修复/重置后重试"
        12 -> "$displayName 安装失败：容器内的依赖关系已损坏（exit=12），需要修复运行环境"
        13 -> "$displayName 安装失败：全部软件源均未能完成安装（exit=13），请检查网络后重试"
        124 -> "$displayName 安装失败：安装超时（超过 15 分钟），网络较慢时可稍后重试"
        137 -> "$displayName 安装失败：安装过程被系统终止（exit=137），多是内存/存储不足，清理后重试"
        126 -> "$displayName 无法启动：容器 rootfs 缺少可执行 shell，请修复运行环境"
        else -> "$displayName 校验失败（exit=$exitCode）"
    }
}
