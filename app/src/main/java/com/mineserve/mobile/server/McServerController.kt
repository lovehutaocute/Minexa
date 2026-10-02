package com.mineserve.mobile.server

import android.util.Log
import com.mineserve.mobile.R
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.data.ServerEventNotifier
import com.mineserve.mobile.data.InstallStep
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.JavaVendor
import com.mineserve.mobile.data.JvmFlavor
import com.mineserve.mobile.data.InstalledCore
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.RuntimeKind
import com.mineserve.mobile.data.MultiThreadDownloader
import com.mineserve.mobile.data.ProotDistro
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.MinecraftVersionNormalizer
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.StepStatus
import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.data.isProotGuestRuntime
import com.mineserve.mobile.data.provides
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.Locale

/**
 * MC 版本号降序比较器：按数字段逐级比较，保证 `26.2 > 1.21.11 > 1.21.4 > 1.20.6` 这类
 * 跨编号体系的排序正确（纯字符串比较会把 `1.21.4` 排到 `26.2` 前面）。
 *
 * 正则必须是常量：这个比较器被 `sortedWith` 使用（版本列表），排序要做 O(n log n) 次比较，
 * 每次比较都新建两个 Regex 的话，几百个版本就是上万次正则编译。
 */
private val VERSION_DIGITS = Regex("\\d+")

/**
 * Maven metadata / GitHub release 里的 `<version>` 取值。
 *
 * 提到文件级常量：这两个正则原先分别写在 `.findAll` 与 `.map` 的位置，
 * 版本条目上百个时每次拉版本列表都会编译上百次。
 */
private val PNX_METADATA_VERSION = Regex("<version>\\s*([^<]+?)\\s*</version>")
private val PNX_VERSION_PREFIX = Regex("^([0-9]+(?:\\.[0-9]+){1,3})(?:-r\\d+|-PNX)?$")

internal val mcVersionComparator: Comparator<String> = Comparator { left, right ->
    val leftParts = VERSION_DIGITS.findAll(left).map { it.value.toInt() }.toList()
    val rightParts = VERSION_DIGITS.findAll(right).map { it.value.toInt() }.toList()
    leftParts.zip(rightParts).firstOrNull { it.first != it.second }
        ?.let { (a, b) -> b.compareTo(a) }
        ?: rightParts.size.compareTo(leftParts.size).takeIf { it != 0 }
        ?: right.compareTo(left)
}

/**
 * 由 MC 版本号推导 NeoForge 版本号前缀，保证「点号段」严格对齐。
 *
 * NeoForge 编号规则：MC `1.21.4` → NeoForge `21.4.x`（去掉开头的 `1.`）；
 * MC `26.2` → NeoForge `26.2.x`（新编号体系不再去前缀）。
 *
 * 返回的前缀带尾部点号（除精确等于的情况），例如：
 * - `26.2`   → `["26.2", "26.2."]`
 * - `1.21.4` → `["21.4", "21.4."]`
 * - `1.21`   → `["21", "21."]` —— 只允许 `21.4.157` 这类两段以上？不，需排除 `21.11.45`。
 *   因此对两段式 MC 版本额外限定：NeoForge 号去掉补丁段后必须与 MC 号完全相等。
 */
internal fun buildNeoForgePrefixes(minecraftVersion: String): List<String> {
    val mc = minecraftVersion.trim().removePrefix("v").removePrefix("V")
    // NeoForge 侧编号：MC 1.x.y → x.y；MC 26.x → 26.x
    val nf = if (mc.startsWith("1.")) mc.removePrefix("1.") else mc
    return listOf(nf, "$nf.")
}

internal fun selectNeoForgeVersion(minecraftVersion: String, versions: List<String>): String? {
    val mc = minecraftVersion.trim()
    // 严格按「点号段」匹配，避免前缀碰撞：
    // 旧实现用 startsWith("21.")，导致 MC 1.21 会把 21.1.x / 21.11.x 全部命中并选出 21.11.45。
    // 现在要求段数与 MC 版本严格对齐 —— 26.2 → "26.2"，21.4 → "21.4."，21.4.157 合法，21.11.45 不再命中。
    val candidatePrefixes = buildNeoForgePrefixes(mc)
    return versions.asSequence()
        .filter { full ->
            if (full.contains("-beta", ignoreCase = true)) return@filter false
            val clean = full.split('-').first()
            candidatePrefixes.any { prefix -> clean == prefix.trimEnd('.') || clean.startsWith(prefix) }
        }
        .maxWithOrNull(Comparator { left, right ->
            val leftParts = VERSION_DIGITS.findAll(left).map { it.value.toInt() }.toList()
            val rightParts = VERSION_DIGITS.findAll(right).map { it.value.toInt() }.toList()
            leftParts.zip(rightParts).firstOrNull { it.first != it.second }
                ?.let { (a, b) -> a.compareTo(b) }
                ?: leftParts.size.compareTo(rightParts.size).takeIf { it != 0 }
                ?: left.compareTo(right)
        })
}

/**
 * Resolve the actual PNX entry point instead of assuming every release is legacy Nukkit.
 *
 * 探测顺序很关键：新版 PNX 把入口放在 `JarStart`，而 `Server` 只是普通业务类
 * （不含 `public static void main`）。若先命中 `Server` 并以它作为主类启动，
 * JVM 会报 "Main method not found in class org.powernukkitx.Server" 并以退出码 1 结束。
 *
 * 因此按「JarStart 优先」排序，并逐个校验候选类确实声明了 main 方法：
 * 1. 有 main → 立即采用；
 * 2. 无 main → 跳过，继续探测下一个候选；
 * 3. 全部无 main → 回退到首个存在的候选类（保持与旧行为兼容，交由 JVM 报错）。
 */
internal fun powerNukkitXMainClass(jarFile: File): String? = runCatching {
    JarFile(jarFile).use { jar ->
        val candidates = listOf(
            "org/powernukkitx/JarStart.class" to "org.powernukkitx.JarStart",
            "org/powernukkitx/Server.class" to "org.powernukkitx.Server",
            "cn/nukkit/JarStart.class" to "cn.nukkit.JarStart",
            "cn/nukkit/Nukkit.class" to "cn.nukkit.Nukkit"
        ).filter { (entry, _) -> jar.getEntry(entry) != null }

        if (candidates.isEmpty()) return@use null

        candidates.firstOrNull { (entry, _) -> jarDeclaresMainMethod(jar, entry) }?.second
            ?: candidates.first().second
    }
}.getOrNull()

/**
 * 判断 jar 内某个 class 条目是否声明了 `public static void main(String[])`。
 *
 * 采用轻量字节码扫描（解析常量池中方法名与方法描述符）而非加载类，
 * 避免在探测阶段触发类初始化或依赖缺失。
 */
private fun jarDeclaresMainMethod(jar: JarFile, classEntry: String): Boolean = runCatching {
    val entry = jar.getEntry(classEntry) ?: return false
    val bytes = jar.getInputStream(entry).use { it.readBytes() }
    // 在常量池中查找"方法名字符串 main"与"方法描述符 ([Ljava/lang/String;)V"同时出现。
    val hasMainName = bytes.containsAscii("main")
    if (!hasMainName) return false
    val hasMainDescriptor = bytes.containsAscii("([Ljava/lang/String;)V")
    // ACC_PUBLIC(0x0001) + ACC_STATIC(0x0009) 的组合在高位字节表现为 0x0009；
    // 精确校验方式访问标志存在，避免把实例方法 main 误判为入口。
    hasMainDescriptor && bytes.containsAscii("\u0000\u0009")
}.getOrDefault(false)

/** 字节序列中是否包含给定 ASCII 串（用于 class 常量池的轻量匹配）。 */
private fun ByteArray.containsAscii(value: String): Boolean {
    val target = value.toByteArray(Charsets.US_ASCII)
    if (target.isEmpty() || target.size > size) return false
    outer@ for (i in 0..(size - target.size)) {
        for (j in target.indices) {
            if (this[i + j] != target[j]) continue@outer
        }
        return true
    }
    return false
}

/** Uses imported Bukkit dependencies when the core JAR is not self-contained. */
internal fun bukkitLaunchArguments(serverDir: File, coreJar: File): String? {
    fun hasClass(jar: File, entry: String): Boolean = runCatching {
        JarFile(jar).use { it.getEntry(entry) != null }
    }.getOrDefault(false)

    if (hasClass(coreJar, "joptsimple/OptionException.class")) return null

    val libraries = listOf("libraries", "lib", "libs")
        .map { File(serverDir, it) }
        .filter(File::isDirectory)
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension.equals("jar", true) }.toList() }
        .distinct()
    if (libraries.none { hasClass(it, "joptsimple/OptionException.class") }) {
        throw RuntimeException("导入的 ${coreJar.name} 缺少 jopt-simple 依赖；请导入包含 libraries 或 lib 目录的完整服务端文件夹")
    }
    val mainClass = runCatching {
        JarFile(coreJar).use { it.manifest?.mainAttributes?.getValue("Main-Class") }
    }.getOrNull()?.takeIf { it.startsWith("org.bukkit.craftbukkit.") }
        ?: "org.bukkit.craftbukkit.Main".takeIf { hasClass(coreJar, "org/bukkit/craftbukkit/Main.class") }
        ?: throw RuntimeException("导入的 ${coreJar.name} 没有可识别的 CraftBukkit 启动入口")
    fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
    return "-cp ${listOf(coreJar).plus(libraries).joinToString(":") { quote(it.absolutePath) }} $mainClass"
}

/**
 * MC 服务控制器（生产化）：
 *  - 一键安装依赖（JDK/tmux/wget 等）
 *  - 4 种服务端核心下载（Paper/Fabric/Forge/Vanilla），动态解析最新版本
 *  - 启停控制
 */
class McServerController(
    private val termux: TermuxRuntime,
    private val repo: ServerRepository
) {
    data class StartupFailure(val code: Int, val reportPath: String?, val detail: String)
    private val _startupFailures = MutableSharedFlow<StartupFailure>(extraBufferCapacity = 8)
    val startupFailures = _startupFailures.asSharedFlow()

    private val powerNukkitXRepos = listOf(
        "PowerNukkitX/PowerNukkitX",
        "PowerNukkitX/PowerNukkitX-Legacy"
    )

    @Volatile
    private var powerNukkitXVersionCache: Map<String, CoreVersionOption> = emptyMap()

    data class CoreVersionOption(
        val version: String,
        val supportedGameVersion: String? = null,
        val sourceRepository: String? = null,
        val releaseTag: String? = null,
        val assetUrl: String? = null,
        val assetDigest: String? = null,
        val publishedAt: String = ""
    )

    private suspend fun runInstallerWithRetry(
        jarPath: String,
        serverDir: File,
        label: String,
        tempDir: File,
        javaVersion: JavaVersion,
        javaVendor: JavaVendor = JavaVendor.Termux
    ): Int {
        var lastCode = 1
        repeat(3) { attempt ->
            // Installer runs before start(), so repair the selected JDK wrapper here as well.
            // 来宾链路没有宿主侧的 java 包装器可修，跳过（容器内的 JDK 由 apt 管）。
            if (!isProotGuestRuntime(javaVendor, javaVersion)) {
                termux.autoRepairRuntime(javaVersion, needsFonts = false)
            }
            lastCode = if (isProotGuestRuntime(javaVendor, javaVersion)) {
                termux.runProotInstaller(
                    jarPath,
                    serverDir,
                    profile = termux.resolveProotProfile(javaVendor, javaVersion)
                )
            } else {
                termux.execOnce(
                    "java", "-Djava.io.tmpdir=${tempDir.absolutePath}",
                    "-jar", jarPath, "--installServer", serverDir.absolutePath
                )
            }
            if (lastCode == 0) return 0
            if (attempt < 2) {
                termux.emitLog("[install] $label 安装失败，${attempt + 2}/3 次重试前等待网络恢复...")
                delay((attempt + 1) * 2000L)
            }
        }
        return lastCode
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 崩溃报告管理器：MC 进程异常退出时捕获日志生成报告 */
    private val crashReportManager = CrashReportManager(termux)

    @Volatile
    private var isInstalling = false

    /** 崩溃自动重启的最大重试次数，防止无限循环 */
    private val maxRestartAttempts = 3
    @Volatile
    private var restartAttempts = 0

    /**
     * 崩溃重启的退避基准间隔：3s → 6s → 12s → 24s，封顶 60s。
     *
     * 固定 3s 间隔时，配置错误导致的"秒退"会形成启动风暴（每 3 秒拉起一次，
     * 每次都写日志、打包崩溃报告），既拖慢机器又淹没用户判断。
     * 指数退避让连续失败的重启成本迅速上升，同时保持首次重启依然很快。
     */
    private fun restartBackoffMs(attempt: Int): Long {
        val base = 3_000L
        // attempt 从 1 开始计数：1→3s, 2→6s, 3→12s
        val shift = (attempt - 1).coerceIn(0, 5)
        return (base shl shift).coerceAtMost(MAX_RESTART_BACKOFF_MS)
    }

    /** 进程存活超过该时长即视为"启动成功"，清零退避计数，避免下次崩溃从长间隔开始。 */
    private fun resetRestartBackoffIfStable() {
        val uptime = System.currentTimeMillis() - processStartedAtMs
        if (restartAttempts != 0 && uptime >= RESTART_BACKOFF_RESET_MS) {
            Log.i(TAG, "服务端已稳定运行 ${uptime / 1000}s，清零崩溃重启计数")
            restartAttempts = 0
        }
    }

    @Volatile
    private var startupDeadlineMs = 0L
    @Volatile
    private var processStartedAtMs = 0L

    /**
     * 用户主动请求停止的时间戳。
     *
     * ⚠️ 唯一真源在 [TermuxRuntime.lastUserStopRequestAtMs] —— 主动停止的
     * 入口有多个（App 内停止、通知栏按钮、备份前暂停、删除运行环境），
     * 它们最终都汇聚到 `TermuxRuntime.stopMc()`，标记打在那里才不会漏。
     */
    private val userStopRequestedAtMs: Long
        get() = termux.lastUserStopRequestAtMs

    @Volatile
    private var lastStartupFailure: StartupFailure? = null
    @Volatile
    private var lastStartupFailureAtMs = 0L

    /** 最近一次非零退出的失败信息：App 重启/ViewModel 重建后仍可补弹，maxAgeMs 内有效。 */
    fun recentStartupFailure(maxAgeMs: Long = 5 * 60_000L): StartupFailure? {
        val failure = lastStartupFailure ?: return null
        return if (System.currentTimeMillis() - lastStartupFailureAtMs <= maxAgeMs) failure else null
    }

    /**
     * 一键安装依赖
     * @param onSpeed apt 下载速度回调（bytes/s）
     */
    suspend fun installDependencies(onSpeed: ((Long) -> Unit)? = null) = withContext(Dispatchers.IO) {
        // 防止并发调用（ViewModel 和 start() 可能同时调用）
        if (isInstalling) return@withContext false
        isInstalling = true
        // 监控 apt 缓存目录大小变化，计算下载速度
        val aptCacheDir = File(termux.installer.rootDir, "var/cache/apt/archives")
        var monitoring = true
        val monitorJob = launch {
            var lastSize = aptCacheDir.getSizeBytes()
            var lastTime = System.currentTimeMillis()
            while (monitoring) {
                delay(500)
                val nowSize = aptCacheDir.getSizeBytes()
                val now = System.currentTimeMillis()
                val elapsedSec = (now - lastTime) / 1000.0
                val speed = if (elapsedSec > 0) ((nowSize - lastSize) / elapsedSec).toLong() else 0L
                onSpeed?.invoke(speed.coerceAtLeast(0))
                lastSize = nowSize
                lastTime = now
            }
        }
        try {
            // 先确保 Termux 环境已初始化
            if (!termux.isReady()) {
                throw RuntimeException("Termux 环境未初始化，请等待初始化完成")
            }
            termux.fixRootfsPermissions()
            if (!termux.prepareAptPackages("wget", "fontconfig", "ttf-dejavu")) {
                return@withContext false
            }
            val steps = InstallStep.values().filter { it != InstallStep.Jdk }
            steps.forEachIndexed { idx, step ->
                repo.markStep(step, StepStatus.Active, idx * (100 / steps.size))
                val code = when (step) {
                    InstallStep.Jdk -> 0
                    InstallStep.Wget -> termux.execOnce(
                        "apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y",
                        "wget", "fontconfig", "ttf-dejavu"
                    )
                    InstallStep.Frp -> termux.execOnce("/system/bin/sh", "-c",
                        "which frpc >/dev/null 2>&1 || (apt-cache show frp >/dev/null 2>&1 && apt-get -o DPkg::Lock::Timeout=60 install --allow-unauthenticated -y frp)")
                    InstallStep.Rclone -> if (termux.prepareAptPackages("rclone")) termux.execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", "rclone") else 1
                    InstallStep.Proot -> if (termux.prepareAptPackages("proot")) termux.execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", "proot") else 1
                }
                if (code == 0) {
                    termux.repairInstalledCommands()
                    if (step == InstallStep.Wget) {
                        termux.repairFontRuntime()
                    }
                }
                if (code == 0 && termux.isDependencyInstalled(step)) {
                    repo.markStep(step, StepStatus.Done, (idx + 1) * (100 / steps.size))
                } else {
                    if (code == 0) termux.emitLog("[install] ${step.label} verification failed; the dependency card will remain visible")
                    repo.markStep(step, StepStatus.Wait, idx * (100 / steps.size))
                    return@withContext false
                }
            }
            true
        } finally {
            monitoring = false
            monitorJob.cancel()
            onSpeed?.invoke(0L)
            isInstalling = false
        }
    }

    /** 递归计算目录总大小（bytes） */
    private fun File.getSizeBytes(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        var total = 0L
        listFiles()?.forEach { total += it.getSizeBytes() }
        return total
    }

    /**
     * 下载服务端核心到指定自定义名称的独立目录。
     * 下载到 home/servers/{dirName}/server.jar，使用 Java HTTP 直下载，不依赖 wget。
     * 下载成功后自动将核心信息添加到 config.installedCores 并设为 activeCore。
     *
     * @param customName 用户自定义名称（显示用），如 "生存服-1.20.4"
     * @return 生成的 dirName（文件夹名）
     */
    suspend fun downloadCore(config: McConfig, customName: String) = withContext(Dispatchers.IO) {
        downloadCore(config, customName) { _, _, _ -> }
    }

    /**
     * 下载服务端核心（带进度回调版本）。
     * @param onProgress 回调参数：(已下载字节, 总字节, 速度 bytes/s)，总字节为 -1 表示未知
     */
    suspend fun downloadCore(config: McConfig, customName: String, onProgress: (Long, Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val normalizedConfig = config.copy(
            mcVersion = MinecraftVersionNormalizer.forCore(config.selectedCore, config.mcVersion)
        )
        if (normalizedConfig.mcVersion != config.mcVersion) {
            termux.emitLog("[version] Minecraft target normalized from ${config.mcVersion} to ${normalizedConfig.mcVersion}")
        }
        if (normalizedConfig.selectedCore.needsInstaller &&
            !termux.isJavaInstalled(normalizedConfig.selectedJavaVersion, normalizedConfig.selectedJavaVendor)
        ) {
            throw RuntimeException("${normalizedConfig.selectedJavaVersion.displayName} is not installed. Install and verify it before running ${normalizedConfig.selectedCore.displayName} installer.")
        }
        // 目录名去重：文件夹已存在说明别的服务器已占用该目录（例如两个核心都叫「111」，
        // 直接落盘会互相覆盖 server.jar/world）。追加 _2、_3 直到找到空目录。
        val dirName = uniqueDirNameIn(sanitizeDirName(customName))
        val jarPath = termux.serverJarFileFor(dirName).absolutePath
        downloadCoreTo(jarPath, normalizedConfig, dirName, onProgress)
        // 在新核心目录下创建 eula.txt 和 plugins/ 目录
        val serverDir = termux.serverDirFor(dirName)
        if (normalizedConfig.selectedCore == ServerCore.PowerNukkitX) {
            val properties = File(serverDir, "server.properties")
            val current = if (properties.exists()) properties.readText() else ""
            properties.writeText(updatePowerNukkitXProperties(current, normalizedConfig.localPort))
            PowerNukkitXConfigManager(termux).updatePort(dirName, normalizedConfig.localPort)
        }
        // NeoForge/Quilt：下载的是 installer.jar，执行安装命令生成启动环境（首次需下载依赖）
        if (normalizedConfig.selectedCore.needsInstaller) {
            termux.emitLog("[install] 正在执行 ${config.selectedCore.displayName} installer，首次安装需下载依赖，请耐心等待...")
            val installerTempDir = File(termux.installer.rootDir, "tmp").apply { mkdirs() }
            when (normalizedConfig.selectedCore) {
                ServerCore.Forge -> {
                    val code = runInstallerWithRetry(
                        jarPath, serverDir, normalizedConfig.selectedCore.displayName, installerTempDir,
                        normalizedConfig.selectedJavaVersion
                    )
                    if (code != 0) throw RuntimeException("Forge installer 执行失败 (exit=$code)")
                }
                ServerCore.NeoForge -> {
                    val code = runInstallerWithRetry(
                        jarPath, serverDir, normalizedConfig.selectedCore.displayName, installerTempDir,
                        normalizedConfig.selectedJavaVersion
                    )
                    if (code != 0) throw RuntimeException("NeoForge installer 执行失败 (exit=$code)")
                }
                ServerCore.Quilt -> {
                    // 来宾链路（PRoot）与宿主链路二选一。判定统一走
                    // isProotGuestRuntime：以前这里只认 `selectedJavaVersion == Java8`，
                    // 新增 Proot 来源后若不同步，会出现「启动走容器、installer 走宿主」
                    // 的错位 —— 而 Quilt 的 installer 恰恰依赖容器内的 java。
                    val code = if (isProotGuestRuntime(
                            normalizedConfig.selectedJavaVendor,
                            normalizedConfig.selectedJavaVersion
                        )
                    ) {
                        val profile = termux.resolveProotProfile(
                            normalizedConfig.selectedJavaVendor,
                            normalizedConfig.selectedJavaVersion
                        )
                        val guestJar = "${TermuxRuntime.GUEST_SERVER_DIR}/${File(jarPath).relativeTo(serverDir).invariantSeparatorsPath}"
                        termux.runProotCommand(
                            serverDir,
                            "export JAVA_HOME=${profile.javaHomeInGuest}; " +
                                "export PATH=\"${profile.javaHomeInGuest}/bin:\$PATH\"; " +
                                "cd ${TermuxRuntime.GUEST_SERVER_DIR} && " +
                                "exec ${profile.guestJavaBinaryCandidates.first()} " +
                                "-Djava.io.tmpdir=/tmp -jar '$guestJar' " +
                                "install server '${normalizedConfig.mcVersion}' --install-dir=${TermuxRuntime.GUEST_SERVER_DIR} --download-server",
                            profile = profile
                        )
                    } else {
                        termux.execOnce(
                            "java", "-jar", jarPath, "install", "server", normalizedConfig.mcVersion,
                            "--install-dir=${serverDir.absolutePath}", "--download-server"
                        )
                    }
                    if (code != 0) throw RuntimeException("Quilt installer 执行失败 (exit=$code)")
                }
                else -> {}
            }
            requireInstallerLaunchArtifacts(normalizedConfig.selectedCore, serverDir)
            termux.emitLog("[install] ${normalizedConfig.selectedCore.displayName} 安装完成")
        }
        val eula = File(serverDir, "eula.txt")
        if (!eula.exists()) eula.writeText("eula=true\n")
        File(serverDir, "plugins").mkdirs()
        // PocketMine 的核心是 .phar（PHP 运行），Allay 保持 server.jar（Java 运行）
        val serverFileName = if (normalizedConfig.selectedCore == ServerCore.PocketMine) {
            val phar = File(serverDir, "PocketMine-MP.phar")
            File(jarPath).renameTo(phar)
            phar.name
        } else "server.jar"
        // 添加到已安装列表。
        //
        // 注意：这里刻意**不使用调用方传入的 [config]** 来拼接新列表。
        // [config] 往往是 UI 侧的快照，而下载过程可能持续几十秒甚至更久，
        // 期间用户完全可能删除/新增别的核心。若以那份陈旧快照为基准写回，
        // 会把用户在下载期间做的其他改动整体覆盖（症状：刚下的核心不在列表里、
        // 已删的核心又出现）。改为在仓库的权威内存态上原子读改写。
        val newCore = InstalledCore(
            name = customName,
            core = normalizedConfig.selectedCore,
            version = normalizedConfig.mcVersion,
            dirName = dirName,
            serverFile = serverFileName
        )
        repo.updateAndSaveConfig { current ->
            current.copy(
                installedCores = current.installedCores.filter { it.dirName != dirName } + newCore,
                activeCoreName = customName
            )
        }
        dirName
    }

    /**
     * 在 servers 目录下为 [base] 找一个未被占用的目录名。
     *
     * 判重同时看磁盘目录与已登记核心（config.installedCores），避免出现
     * "目录空但已被登记"或"目录已被别的服务器占用"两种半边状态。
     * 冲突时依次尝试 `base_2`、`base_3`…
     *
     * 注意：读取 config 需要挂起，请在协程中调用。
     */
    suspend fun uniqueDirNameIn(base: String): String {
        val taken = runCatching { repo.configFlow.first() }.getOrNull()
            ?.installedCores?.map { it.dirName }?.toHashSet() ?: hashSetOf()
        return uniqueDirName(base, taken, termux.serversDir)
    }

    /**
     * 该显示名是否与已登记的服务器重名（用于下载前拦截）。
     *
     * 返回重名的核心名，未重名返回 null。目录名不同但显示名相同同样算重名：
     * 用户在列表里看到的就是显示名，两个「111」无法区分。
     */
    fun duplicateNameOf(customName: String, config: McConfig): String? {
        val name = customName.trim()
        if (name.isEmpty()) return null
        return config.installedCores.firstOrNull { it.name.trim() == name }?.name
    }

    /**
     * 动态解析下载 URL（在 APP 层用 Java HTTP + JSON 解析）。
     * 修复点：
     *  - Paper: 调用 PaperMC v2 API 获取最新 SUCCESS build 号
     *  - Vanilla: 调用 Mojang version_manifest_v2.json → 版本元数据 JSON → server.jar URL
     *  - Fabric: 调用 Fabric meta API 获取最新 loader/installer 版本
     *  - Forge: 调用 Forge maven-metadata.xml 获取推荐版本
     */
    private fun resolveDownloadUrl(core: ServerCore, version: String): String {
        return when (core) {
            ServerCore.Paper -> resolvePaperUrl(version)
            ServerCore.Purpur -> resolvePurpurUrl(version)
            ServerCore.Leaves -> resolveLeavesUrl(version)
            ServerCore.Leaf -> resolveLeafUrl(version)
            ServerCore.Spigot -> "https://cdn.getbukkit.org/spigot/spigot-$version.jar"
            ServerCore.CraftBukkit -> "https://cdn.getbukkit.org/craftbukkit/craftbukkit-$version.jar"
            ServerCore.Fabric -> resolveFabricUrl(version)
            ServerCore.Forge -> resolveForgeUrl(version)
            ServerCore.NeoForge -> resolveNeoForgeUrl(version)
            ServerCore.Quilt -> resolveQuiltUrl(version)
            ServerCore.Vanilla -> resolveVanillaUrl(version)
            ServerCore.Velocity -> resolveVelocityUrl(version)
            ServerCore.BungeeCord -> resolveBungeeUrl()
            ServerCore.PowerNukkitX -> resolvePowerNukkitXUrl(version)
            ServerCore.PocketMine -> resolvePocketMineUrl(version)
            ServerCore.Allay -> resolveAllayUrl(version)
            ServerCore.Unknown -> throw IllegalArgumentException("未知核心类型，无法解析下载地址")
        }
    }

    private fun resolveDownloadUrls(core: ServerCore, version: String): List<String> {
        val official = resolveDownloadUrl(core, version)
        val mirrors = when (core) {
            ServerCore.Vanilla -> listOf("https://bmclapi2.bangbang93.com/version/$version/server")
            ServerCore.Forge -> {
                val coordinate = official.substringAfter("/forge/").substringBefore("/")
                val forgeVersion = coordinate.removePrefix("$version-")
                listOf("https://bmclapi2.bangbang93.com/forge/download?mcversion=$version&version=$forgeVersion&category=installer&format=jar")
            }
            ServerCore.Fabric -> listOf(official.replace("https://meta.fabricmc.net/", "https://bmclapi2.bangbang93.com/fabric-meta/"))
            else -> emptyList()
        }
        return if (core == ServerCore.PowerNukkitX) {
            if (official.contains("maven.org") || official.contains("maven.aliyun.com") || official.contains("huaweicloud.com")) {
                listOf(
                    official,
                    official.replace("https://repo1.maven.org/maven2", "https://maven.aliyun.com/repository/central"),
                    official.replace("https://repo1.maven.org/maven2", "https://repo.huaweicloud.com/repository/maven")
                ).distinct()
            } else {
                listOf(
                    "https://ghfast.top/$official",
                    "https://gh-proxy.com/$official",
                    "https://mirror.ghproxy.com/$official",
                    "https://ghproxy.net/$official",
                    "https://github.moeyy.xyz/$official",
                    official
                ).distinct()
            }
        } else (mirrors + official).distinct()
    }

    // ── PaperMC v3 API（fill.papermc.io）：动态获取最新 STABLE build 号 ───

    private fun resolvePaperUrl(version: String): String {
        // v3 API：返回 builds 数组，每个 build 有 channel（STABLE/ALPHA/BETA/RECOMMENDED）和 downloads.server:default.url
        val buildsUrl = "https://fill.papermc.io/v3/projects/paper/versions/$version/builds"
        val builds = fetchJsonElement(buildsUrl).jsonArray
        if (builds.isEmpty()) {
            throw RuntimeException("PaperMC v3: no builds for version $version")
        }

        // 优先级：STABLE > RECOMMENDED > BETA > ALPHA
        // 在同一 channel 中选 id 最大的（最新）
        val channelPriority = mapOf("STABLE" to 4, "RECOMMENDED" to 3, "BETA" to 2, "ALPHA" to 1)
        var bestEntry: JsonObject? = null
        var bestPriority = 0
        var bestId = -1

        for (entry in builds) {
            val obj = entry.jsonObject
            val channel = obj["channel"]?.jsonPrimitive?.content ?: continue
            val id = obj["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
            val priority = channelPriority[channel] ?: 0
            if (priority > bestPriority || (priority == bestPriority && id > bestId)) {
                // 确保有 server:default 下载
                val hasServer = obj["downloads"]?.jsonObject
                    ?.get("server:default")?.jsonObject != null
                if (hasServer) {
                    bestPriority = priority
                    bestId = id
                    bestEntry = obj
                }
            }
        }

        if (bestEntry == null) {
            // 兜底：取数组第一个
            bestEntry = builds.first().jsonObject
        }

        val downloadUrl = bestEntry!!["downloads"]?.jsonObject
            ?.get("server:default")?.jsonObject
            ?.get("url")?.jsonPrimitive?.content
            ?: throw RuntimeException("PaperMC v3: no server download URL for version $version")

        val buildId = bestEntry["id"]?.jsonPrimitive?.content ?: "?"
        val channel = bestEntry["channel"]?.jsonPrimitive?.content ?: "?"
        Log.i(TAG, "PaperMC v3: selected build $buildId (channel=$channel) for $version")
        return downloadUrl
    }

    // ── Mojang Vanilla：version_manifest_v2.json → 版本 JSON → server.jar URL ─

    private fun resolveVanillaUrl(version: String): String {
        // 1. 获取版本清单
        val manifest = fetchJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        val versions = manifest["versions"]?.jsonArray
            ?: throw RuntimeException("Mojang: no versions in manifest")

        // 2. 找到目标版本
        var versionMetaUrl: String? = null
        for (v in versions) {
            val obj = v.jsonObject
            if (obj["id"]?.jsonPrimitive?.content == version) {
                versionMetaUrl = obj["url"]?.jsonPrimitive?.content
                break
            }
        }
        if (versionMetaUrl == null) {
            throw RuntimeException("Mojang: version $version not found in manifest")
        }

        // 3. 获取版本元数据，提取 server.jar 下载 URL
        val versionMeta = fetchJson(versionMetaUrl)
        val serverUrl = versionMeta["downloads"]?.jsonObject
            ?.get("server")?.jsonObject
            ?.get("url")?.jsonPrimitive?.content
            ?: throw RuntimeException("Mojang: no server.jar URL for version $version")
        return serverUrl
    }

    // ── Fabric：动态获取最新 loader + installer 版本 ────────────────

    private fun resolveFabricUrl(version: String): String {
        // Fabric loader API 返回 JSON 数组，需用 fetchJsonElement
        val loaderResp = fetchJsonElement("https://meta.fabricmc.net/v2/versions/loader")
        val loaders = loaderResp.jsonArray
        val loaderVer = loaders.firstOrNull()
            ?.jsonObject?.get("version")?.jsonPrimitive?.content
            ?: "0.16.5"

        // 获取最新 installer 版本
        val installerResp = fetchJsonElement("https://meta.fabricmc.net/v2/versions/installer")
        val installers = installerResp.jsonArray
        val installerVer = installers.firstOrNull()
            ?.jsonObject?.get("version")?.jsonPrimitive?.content
            ?: "0.11.2"

        return "https://meta.fabricmc.net/v2/versions/loader/$version/$loaderVer/$installerVer/server/jar"
    }

    // ── Forge：从 maven-metadata.xml 获取推荐版本号 ──────────────────

    private fun resolveForgeUrl(version: String): String {
        // Forge 没有标准 JSON API，用 maven-metadata.xml 解析
        // 简化：使用 Forge 官方 promotions API 获取推荐版本
        val promotionsUrl = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
        val resp = fetchJson(promotionsUrl)
        val promos = resp["promos"]?.jsonObject
            ?: throw RuntimeException("Forge: no promotions found")

        // 查找 "{version}-recommended" 或 "{version}-latest"
        val recommendedKey = "$version-recommended"
        val latestKey = "$version-latest"
        val forgeVer = promos[recommendedKey]?.jsonPrimitive?.content
            ?: promos[latestKey]?.jsonPrimitive?.content
            ?: throw RuntimeException("Forge: no build for MC $version")

        return "https://maven.minecraftforge.net/net/minecraftforge/forge/$version-$forgeVer/forge-$version-$forgeVer-installer.jar"
    }

    // ── Purpur：官方 API 直链（latest build 302 重定向到实际 jar） ──

    private fun resolvePurpurUrl(version: String): String {
        return "https://api.purpurmc.org/v2/purpur/$version/latest/download"
    }

    private fun resolveLeavesUrl(version: String): String {
        val build = fetchJson("https://api.leavesmc.org/v2/projects/leaves/versions/$version/builds/latest")
        val buildNumber = build["build"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Leaves: no build for $version")
        val name = build["downloads"]?.jsonObject?.get("application")?.jsonObject
            ?.get("name")?.jsonPrimitive?.content
            ?: throw RuntimeException("Leaves: no application download for $version")
        return "https://api.leavesmc.org/v2/projects/leaves/versions/$version/builds/$buildNumber/downloads/$name"
    }

    private fun resolveLeafUrl(version: String): String {
        val versions = fetchJson("https://api.leafmc.one/v2/projects/leaf/versions/$version")
        val build = versions["builds"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
            ?.maxOrNull()
            ?: throw RuntimeException("Leaf: no build for $version")
        val buildJson = fetchJson("https://api.leafmc.one/v2/projects/leaf/versions/$version/builds/$build")
        val name = buildJson["downloads"]?.jsonObject?.get("primary")?.jsonObject
            ?.get("name")?.jsonPrimitive?.content
            ?: throw RuntimeException("Leaf: no primary download for $version")
        return "https://api.leafmc.one/v2/projects/leaf/versions/$version/builds/$build/downloads/$name"
    }

    // ── Velocity：PaperMC v3 API（取最新 build 的 application 下载） ──

    private fun resolveVelocityUrl(version: String): String {
        val buildsUrl = "https://fill.papermc.io/v3/projects/velocity/versions/$version/builds"
        val builds = fetchJsonElement(buildsUrl).jsonObject["builds"]?.jsonArray
            ?: throw RuntimeException("Velocity: no builds for version $version")
        val best = builds.lastOrNull()?.jsonObject
            ?: throw RuntimeException("Velocity: no build for version $version")
        return best["downloads"]?.jsonObject
            ?.get("application")?.jsonObject
            ?.get("url")?.jsonPrimitive?.content
            ?: throw RuntimeException("Velocity: no application download for version $version")
    }

    // ── BungeeCord：md-5 Jenkins 直链（无版本概念，取最新构建） ──

    private fun resolveBungeeUrl(): String {
        return "https://ci.md-5.net/job/BungeeCord/lastSuccessfulBuild/artifact/bootstrap/target/BungeeCord.jar"
    }

    // ── PocketMine-MP：pmmp update 通道 JSON → download_url（.phar） ──

    private fun resolvePocketMineUrl(version: String): String {
        val body = fetchGithubJsonElement(
            "https://raw.githubusercontent.com/pmmp/update.pmmp.io/master/channels/$version.json"
        )
        val url = (body as? JsonObject)?.get("download_url")?.jsonPrimitive?.content
            ?: throw RuntimeException("PocketMine-MP $version: 通道信息中没有 download_url")
        return url
    }

    // ── Allay：GitHub Release 资产（优先 allay* 命名，带 sha256 校验值） ──

    private fun resolveAllayUrl(version: String): String {
        val release = fetchGithubJsonElement(
            "https://api.github.com/repos/AllayMC/Allay/releases/tags/$version"
        ).jsonObject
        val assets = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (assets.isEmpty()) throw RuntimeException("Allay $version: release has no assets")
        val asset = assets.firstOrNull {
            it["name"]?.jsonPrimitive?.content?.startsWith("allay", ignoreCase = true) == true
        } ?: assets.first()
        return asset["browser_download_url"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Allay $version: asset has no download url")
    }

    private fun resolvePowerNukkitXUrl(version: String): String {
        powerNukkitXVersionCache[version.trim().removePrefix("v").removePrefix("V").lowercase(Locale.ROOT)]
            ?.assetUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val normalized = version.trim().removePrefix("v").removePrefix("V")
        if (normalized.contains("-r") || normalized.endsWith("-PNX", ignoreCase = true)) {
            return "https://repo1.maven.org/maven2/cn/powernukkitx/powernukkitx/$normalized/powernukkitx-$normalized-shaded.jar"
        }
        val release = findPowerNukkitXRelease(version)
        return powerNukkitXAsset(release)
            ?.get("browser_download_url")?.jsonPrimitive?.content
            ?: throw RuntimeException("PowerNukkitX $version: release has no powernukkitx.jar asset")
    }

    // ── NeoForge：maven-metadata 按所选 MC 版本匹配 NeoForge 版本 installer ──

    private fun resolveNeoForgeUrl(version: String): String {
        val xml = fetchText("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
        val versions = Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1] }.toList()
        // 不再回退到 <release>：当所选 MC 版本没有对应 NeoForge 构建时，
        // 旧逻辑会静默下载到完全不相干的「最新版」（如 MC 1.20.1 → NeoForge 26.2.0.86），
        // 用户拿到的是一个装不上的 jar。现在直接报错，让用户看到真实原因。
        val ver = selectNeoForgeVersion(version, versions)
            ?: throw RuntimeException("NeoForge: 没有适配 MC $version 的构建，请改用其他 MC 版本")
        return "https://maven.neoforged.net/releases/net/neoforged/neoforge/$ver/neoforge-$ver-installer.jar"
    }

    private fun updatePowerNukkitXProperties(current: String, port: Int): String {
        fun setProperty(text: String, key: String, value: String): String {
            val line = "$key=$value"
            val pattern = Regex("(?m)^${Regex.escape(key)}=.*$")
            return if (pattern.containsMatchIn(text)) pattern.replace(text, line)
            else "$text${if (text.isNotEmpty() && !text.endsWith("\n")) "\n" else ""}$line\n"
        }
        return setProperty(setProperty(current, "server-port", port.toString()), "allow-shaded", "true")
    }

    // ── Quilt：meta API 取 installer 版本（quilt-installer 独立版本号，与 loader 不同） ──

    private fun resolveQuiltUrl(version: String): String {
        val resp = fetchJson("https://meta.quiltmc.org/v3/versions/installer")
        val installerVer = resp.jsonArray.firstOrNull()?.jsonObject
            ?.get("version")?.jsonPrimitive?.content
            ?: throw RuntimeException("Quilt: no installer version")
        return "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/$installerVer/quilt-installer-$installerVer.jar"
    }

    /** 读取原始文本（用于 XML 等非 JSON 接口） */
    private fun fetchText(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "MineServeMobile/1.0 (Android)")
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ── JSON HTTP 工具 ──────────────────────────────────────────────

    private fun fetchJson(urlStr: String): JsonObject = fetchJsonElement(urlStr).jsonObject

    /**
     * 获取指定核心的可用版本列表（供 DownloadScreen 选择）。
     * Paper: 从 PaperMC API 获取该核心支持的版本
     * Vanilla: 从 Mojang manifest 获取所有版本
     * Fabric: 返回主流预设版本（Fabric loader 不依赖具体 MC 版本列表 API）
     * Forge: 从 Forge maven-metadata.xml 获取
     */
    suspend fun fetchVersions(core: ServerCore): List<String> = withContext(Dispatchers.IO) {
        fetchVersionOptions(core).map { it.version }
    }

    suspend fun fetchVersionOptions(core: ServerCore): List<CoreVersionOption> = withContext(Dispatchers.IO) {
        when (core) {
            ServerCore.Paper -> fetchPaperVersions().map(::CoreVersionOption)
            ServerCore.Purpur -> fetchPurpurVersions().map(::CoreVersionOption)
            ServerCore.Leaves -> fetchLeavesVersions().map(::CoreVersionOption)
            ServerCore.Leaf -> fetchLeafVersions().map(::CoreVersionOption)
            ServerCore.Spigot, ServerCore.CraftBukkit -> DEFAULT_MC_VERSIONS.map(::CoreVersionOption)
            ServerCore.Vanilla -> fetchVanillaVersions().map(::CoreVersionOption)
            ServerCore.Fabric -> fetchFabricVersions().map(::CoreVersionOption)
            ServerCore.Forge -> fetchForgeVersions().map(::CoreVersionOption)
            ServerCore.NeoForge -> fetchNeoForgeVersions().map(::CoreVersionOption)
            ServerCore.Quilt -> fetchQuiltVersions().map(::CoreVersionOption)
            ServerCore.Velocity -> fetchVelocityVersions().map(::CoreVersionOption)
            ServerCore.BungeeCord -> fetchBungeeVersions().map(::CoreVersionOption)
            ServerCore.PowerNukkitX -> fetchPowerNukkitXVersionOptions()
            ServerCore.PocketMine -> fetchPocketMineVersions()
            ServerCore.Allay -> fetchAllayVersionOptions()
            ServerCore.Unknown -> emptyList()
        }
    }

    private fun fetchPurpurVersions(): List<String> {
        // Purpur API：{versions: [...]}
        val resp = fetchJson("https://api.purpurmc.org/v2/purpur")
        return resp["versions"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.filter { !it.contains("rc") && !it.contains("pre") }
            ?.sortedDescending() ?: DEFAULT_MC_VERSIONS
    }

    private fun fetchLeavesVersions(): List<String> = fetchProjectVersions("https://api.leavesmc.org/v2/projects/leaves")

    private fun fetchLeafVersions(): List<String> = fetchProjectVersions("https://api.leafmc.one/v2/projects/leaf")

    private fun fetchProjectVersions(url: String): List<String> = runCatching {
        fetchJson(url)["versions"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.filter { it.isNotBlank() }
            ?.sortedDescending()
            ?.take(30)
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull() ?: DEFAULT_MC_VERSIONS

    private fun fetchVelocityVersions(): List<String> {
        // PaperMC v3：velocity versions 数组
        val resp = fetchJson("https://fill.papermc.io/v3/projects/velocity")
        return resp["versions"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.sortedDescending() ?: DEFAULT_MC_VERSIONS
    }

    private fun fetchBungeeVersions(): List<String> = listOf("latest")

    /**
     * PocketMine-MP：pmmp update 通道目录（5 / 5.44 / 5.25.0），仅保留 5.x 稳定版本，按版本号降序。
     * 过滤掉高于 [PMMP_MAX_COMPAT_VERSION] 的通道：Android PHP 运行时内置的
     * pmmp/ext-encoding 为 0.4.x，PocketMine 5.34.0 起要求 ~1.0.0（2025-09 起），
     * 新版本核心会因扩展版本不满足而拒绝启动。
     */
    private fun fetchPocketMineVersions(): List<CoreVersionOption> = runCatching {
        val pattern = Regex("^5(\\.\\d+)*$")
        fetchGithubJsonElement("https://api.github.com/repos/pmmp/update.pmmp.io/contents/channels")
            .jsonArray
            .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .filter { pattern.matches(it) }
            .filter { versionAtMost(it, PMMP_MAX_COMPAT_VERSION) }
            .sortedWith(
                Comparator { a, b ->
                    val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
                    val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
                    for (i in 0 until maxOf(pa.size, pb.size)) {
                        val va = pa.getOrElse(i) { 0 }
                        val vb = pb.getOrElse(i) { 0 }
                        if (va != vb) return@Comparator vb - va
                    }
                    0
                }
            )
            .map(::CoreVersionOption)
    }.getOrElse {
        termux.emitLog("[download] PocketMine-MP 版本源失败: ${it.message ?: "unknown"}")
        listOf(CoreVersionOption(PMMP_MAX_COMPAT_VERSION, "当前 PHP 运行时兼容的最新版本"))
    }

    /** 版本号逐段数值比较：a 是否 ≤ b（如 "5.33.1" ≤ "5.33.1"，"5.9" ≤ "5.33"） */
    private fun versionAtMost(a: String, b: String): Boolean {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va < vb
        }
        return true
    }

    /** Allay：GitHub Releases 标签列表（排除 draft/prerelease），按发布时间降序 */
    private fun fetchAllayVersionOptions(): List<CoreVersionOption> = runCatching {
        fetchGithubJsonElement("https://api.github.com/repos/AllayMC/Allay/releases?per_page=30")
            .jsonArray
            .map { it.jsonObject }
            .filter {
                !(it["draft"]?.jsonPrimitive?.boolean ?: false) &&
                    !(it["prerelease"]?.jsonPrimitive?.boolean ?: false)
            }
            .sortedByDescending { it["published_at"]?.jsonPrimitive?.content.orEmpty() }
            .mapNotNull { release ->
                val tag = release["tag_name"]?.jsonPrimitive?.content?.trim()?.removePrefix("v")
                    ?: return@mapNotNull null
                CoreVersionOption(tag, publishedAt = release["published_at"]?.jsonPrimitive?.content.orEmpty())
            }
    }.getOrElse {
        termux.emitLog("[download] Allay 版本源失败: ${it.message ?: "unknown"}")
        listOf(CoreVersionOption("latest", "官方最新版"))
    }

    private fun fetchPowerNukkitXVersionOptions(): List<CoreVersionOption> {
        val releaseObjects = powerNukkitXRepos.flatMap { repoName ->
            runCatching {
                fetchGithubJsonElement("https://api.github.com/repos/$repoName/releases?per_page=100")
                    .jsonArray.map { it.jsonObject }
            }.getOrElse { e ->
                termux.emitLog("[download] PowerNukkitX 版本源失败: $repoName (${e.message ?: "unknown"})")
                emptyList()
            }
        }
        val releaseOptions = releaseObjects
            .filter {
                !(it["draft"]?.jsonPrimitive?.boolean ?: false) &&
                    !(it["prerelease"]?.jsonPrimitive?.boolean ?: false)
            }
            .mapNotNull { release ->
                val rawTag = release["tag_name"]?.jsonPrimitive?.content?.trim() ?: return@mapNotNull null
                val tag = rawTag.removePrefix("v").removePrefix("V").trim()
                val assets = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
                val asset = assets.firstOrNull { it["name"]?.jsonPrimitive?.content.equals("powernukkitx.jar", true) }
                    ?: assets.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".jar", true) == true }
                val body = release["body"]?.jsonPrimitive?.content.orEmpty()
                val name = release["name"]?.jsonPrimitive?.content.orEmpty()
                val assetNames = assets.mapNotNull { it["name"]?.jsonPrimitive?.content }
                CoreVersionOption(
                    tag,
                    parsePowerNukkitXGameVersion("$name\n$body", assetNames),
                    sourceRepository = release["html_url"]?.jsonPrimitive?.content
                        ?.removePrefix("https://github.com/")?.substringBefore("/releases"),
                    releaseTag = rawTag,
                    assetUrl = asset?.get("browser_download_url")?.jsonPrimitive?.content,
                    assetDigest = asset?.get("digest")?.jsonPrimitive?.content?.removePrefix("sha256:"),
                    publishedAt = release["published_at"]?.jsonPrimitive?.content.orEmpty()
                )
            }
        // PowerNukkitX-Legacy has no GitHub Releases; its historical cores are published to Maven Central.
        val legacyMavenOptions = fetchPowerNukkitXLegacyMavenOptions()
        val options = (releaseOptions + legacyMavenOptions)
            .distinctBy { it.version.lowercase(Locale.ROOT) }
            .sortedWith(compareByDescending<CoreVersionOption> { it.publishedAt }.thenBy { it.version })
        if (options.isNotEmpty()) {
            powerNukkitXVersionCache = options.associateBy { it.version.lowercase(Locale.ROOT) }
            return options
        }
        powerNukkitXVersionCache = emptyMap()
        termux.emitLog("[download] PowerNukkitX 核心版本信息获取失败，使用 latest 兜底")
        return listOf(CoreVersionOption("latest", "官方未标注"))
    }

    private fun fetchPowerNukkitXLegacyMavenOptions(): List<CoreVersionOption> {
        val metadataUrls = listOf(
            "https://repo1.maven.org/maven2/cn/powernukkitx/powernukkitx/maven-metadata.xml",
            "https://maven.aliyun.com/repository/central/cn/powernukkitx/powernukkitx/maven-metadata.xml",
            "https://repo.huaweicloud.com/repository/maven/cn/powernukkitx/powernukkitx/maven-metadata.xml"
        )
        val xml = metadataUrls.asSequence().mapNotNull { url -> runCatching { fetchText(url) }.getOrNull() }.firstOrNull()
            ?: return emptyList()
        return PNX_METADATA_VERSION
            .findAll(xml)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .map { version ->
                CoreVersionOption(
                    version = version,
                    supportedGameVersion = PNX_VERSION_PREFIX.find(version)?.groupValues?.get(1),
                    sourceRepository = "PowerNukkitX/PowerNukkitX-Legacy",
                    releaseTag = version,
                    assetUrl = "https://repo1.maven.org/maven2/cn/powernukkitx/powernukkitx/$version/powernukkitx-$version-shaded.jar"
                )
            }
            .toList()
            .also { termux.emitLog("[download] PowerNukkitX-Legacy Maven 版本: ${it.size} 个") }
    }

    private fun parsePowerNukkitXGameVersion(body: String, assetNames: List<String> = emptyList()): String? {
        val patterns = listOf(
            Regex("(?i)(?:support(?:s|ed)?|compatible with)\\s*(?:bedrock|minecraft)?\\s*(?:version|v)?\\s*[:：-]?\\s*([0-9]+(?:\\.[0-9]+)+)"),
            Regex("(?i)update\\s+to\\s+(?:bedrock\\s+)?([0-9]+(?:\\.[0-9]+)+)"),
            Regex("(?i)(?:bedrock|minecraft)\\s*(?:version|v)?\\s*[|:：-]?\\s*([0-9]+(?:\\.[0-9]+)+)")
        )
        val text = if (assetNames.isEmpty()) body else body + "\n" + assetNames.joinToString("\n")
        return patterns.firstNotNullOfOrNull { pattern -> pattern.find(text)?.groupValues?.get(1) }
    }

    private fun resolvePowerNukkitXDigest(version: String): String? {
        val cached = powerNukkitXVersionCache[version.trim().removePrefix("v").removePrefix("V").lowercase(Locale.ROOT)]
        if (cached != null) return cached.assetDigest?.takeIf { it.isNotBlank() }
        return powerNukkitXAsset(findPowerNukkitXRelease(version))
            ?.get("digest")?.jsonPrimitive?.content
            ?.removePrefix("sha256:")
    }

    private fun findPowerNukkitXRelease(version: String): JsonObject {
        var last: Exception? = null
        for (repoName in powerNukkitXRepos) {
            try {
                val normalized = version.trim().removePrefix("v").removePrefix("V")
                val endpoint = if (normalized == "latest") {
                    "https://api.github.com/repos/$repoName/releases/latest"
                } else {
                    val tag = java.net.URLEncoder.encode(normalized, "UTF-8")
                    "https://api.github.com/repos/$repoName/releases/tags/$tag"
                }
                val release = runCatching { fetchGithubJson(endpoint) }.getOrNull()
                    ?: if (normalized == "latest") null else {
                        val tag = java.net.URLEncoder.encode("v$normalized", "UTF-8")
                        runCatching { fetchGithubJson("https://api.github.com/repos/$repoName/releases/tags/$tag") }.getOrNull()
                    }
                if (release != null && powerNukkitXAsset(release) != null) return release
            } catch (e: Exception) {
                last = e
                termux.emitLog("[download] PowerNukkitX 仓库回退: $repoName")
            }
        }
        throw last ?: RuntimeException("PowerNukkitX release unavailable")
    }

    private fun powerNukkitXAsset(release: JsonObject): JsonObject? = release["assets"]?.jsonArray
        ?.map { it.jsonObject }
        ?.firstOrNull { it["name"]?.jsonPrimitive?.content.equals("powernukkitx.jar", true) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetchGithubJson(url: String): JsonObject = fetchGithubJsonElement(url).jsonObject

    private fun fetchGithubJsonElement(url: String): JsonElement {
        val candidates = listOf(
            url,
            "https://ghfast.top/$url",
            "https://gh-proxy.com/$url",
            "https://mirror.ghproxy.com/$url",
            "https://ghproxy.net/$url",
            "https://github.moeyy.xyz/$url"
        ).distinct()
        var last: Exception? = null
        for (candidate in candidates) {
            try {
                return fetchJsonElement(candidate)
            } catch (e: Exception) {
                last = e
                termux.emitLog("[download] GitHub 版本源失败，尝试下一个镜像: $candidate")
            }
        }
        throw last ?: RuntimeException("GitHub API unavailable")
    }

    /**
     * NeoForge：从 maven-metadata.xml 反推可用的 MC 版本列表。
     *
     * NeoForge 的版本号形如 `26.2.0.86` → 对应 MC `26.2`；`21.4.157` → 对应 MC `1.21.4`。
     * 解析规则：取前三段（若只有三段则取前两段）作为 MC 版本号。
     * 过滤掉 `-beta` 预发布版本，避免把测试版混进正式列表。
     *
     * 例如 `26.2.0.86` → `26.2.0`（去掉末段补丁号），`21.4.157` → `21.4` → 补回 `1.` 前缀 → `1.21.4`。
     */
    private fun fetchNeoForgeVersions(): List<String> = runCatching {
        val xml = fetchText("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
        val all = Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1] }.toList()
        val mcVersions = LinkedHashSet<String>()
        for (full in all) {
            // 跳过 beta / rc 等预发布版本，官方列表只展示稳定版
            if (full.contains("-beta", ignoreCase = true) || full.contains("-rc", ignoreCase = true)) continue
            val clean = full.split('-').first()
            val parts = clean.split('.')
            if (parts.size < 2) continue
            val mc = if (parts.size >= 4) {
                // 26.2.0.86 → 26.2.0 → 去掉末段（NeoForge 补丁号）后即 MC 版本
                parts.subList(0, 3).joinToString(".")
            } else {
                // 21.4.157 → 21.4
                parts.subList(0, 2).joinToString(".")
            }
            mcVersions.add(mc)
        }
        if (mcVersions.isEmpty()) return@runCatching DEFAULT_MC_VERSIONS
        // 补回 MC 的 `1.` 前缀：NeoForge 的 21.4 对应 MC 1.21.4；26.x 属于新的版本编号体系，保持原样。
        mcVersions.map { mc ->
            if (mc.startsWith("21.") || mc.startsWith("20.") ||
                mc.startsWith("19.") || mc.startsWith("18.") ||
                mc.startsWith("17.") || mc.startsWith("16.")
            ) "1.$mc" else mc
        }.sortedWith(mcVersionComparator)
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MC_VERSIONS

    /**
     * Quilt：从 meta API 获取官方支持的 MC 版本列表。
     *
     * Quilt 的 `/v3/versions/game` 返回按新→旧排序的 MC 版本数组，语义与 Fabric 一致。
     */
    private fun fetchQuiltVersions(): List<String> = runCatching {
        val resp = fetchJsonElement("https://meta.quiltmc.org/v3/versions/game")
        val versions = resp.jsonArray
            // 该数组同时包含 snapshot / pre 版本（436 条里大部分是快照），
            // 必须用 stable 标志过滤，否则列表顶部全是 26.3-pre-2 这类版本。
            .filter { it.jsonObject["stable"]?.jsonPrimitive?.content == "true" }
            .mapNotNull { it.jsonObject["version"]?.jsonPrimitive?.content }
            .filter { it.isNotBlank() }
        if (versions.isEmpty()) return@runCatching DEFAULT_MC_VERSIONS
        versions.take(60)
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MC_VERSIONS

    private fun fetchPaperVersions(): List<String> {
        // PaperMC v3 API：返回 {versions: {major: [subversions]}}，需平铺
        val resp = fetchJson("https://fill.papermc.io/v3/projects/paper")
        val versionsObj = resp["versions"]?.jsonObject
            ?: return DEFAULT_MC_VERSIONS
        val allVersions = mutableListOf<String>()
        versionsObj.forEach { (_, subVersions) ->
            subVersions.jsonArray.forEach { v ->
                val verStr = v.jsonPrimitive.content
                // 过滤掉 rc/pre 等非正式版
                if (verStr.isNotEmpty() && !verStr.contains("rc") && !verStr.contains("pre")) {
                    allVersions.add(verStr)
                }
            }
        }
        return allVersions.sortedDescending()
    }

    private fun fetchVanillaVersions(): List<String> {
        val manifest = fetchJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        val versions = manifest["versions"]?.jsonArray
            ?: return DEFAULT_MC_VERSIONS
        return versions.map { it.jsonObject["id"]?.jsonPrimitive?.content ?: "" }
            .filter { it.isNotEmpty() && !it.contains("experimental") && !it.contains("pre") && !it.contains("rc") }
            .take(30)  // 只取前30个正式版
    }

    /**
     * Fabric：从 meta API 获取官方支持的 MC 版本列表。
     *
     * `https://meta.fabricmc.net/v2/versions/game` 返回 `[{version, stable}, ...]`，
     * 按新→旧排序。只保留 `stable == true` 的正式版，避免把快照混进列表。
     */
    private fun fetchFabricVersions(): List<String> = runCatching {
        val resp = fetchJsonElement("https://meta.fabricmc.net/v2/versions/game")
        val versions = resp.jsonArray
            .filter { it.jsonObject["stable"]?.jsonPrimitive?.boolean ?: false }
            .mapNotNull { it.jsonObject["version"]?.jsonPrimitive?.content }
            .filter { it.isNotBlank() }
        if (versions.isEmpty()) return@runCatching DEFAULT_MC_VERSIONS
        versions.take(60)
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MC_VERSIONS

    /**
     * Forge：从 promotions_slim.json 提取所有可用的 MC 版本。
     *
     * 该接口返回 `{promos: {"1.21.4-recommended": "65.1.0", "1.21.4-latest": "65.1.3", ...}}`，
     * 键去掉 `-recommended` / `-latest` 后缀即为 MC 版本。共 76 个版本。
     */
    private fun fetchForgeVersions(): List<String> = runCatching {
        val resp = fetchJson("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")
        val promos = resp["promos"]?.jsonObject ?: return@runCatching DEFAULT_MC_VERSIONS
        val mcVersions = promos.keys
            .map { it.substringBeforeLast('-') }
            .filter { it.isNotBlank() && it.count { c -> c == '.' } >= 1 }
            .toSet()
        if (mcVersions.isEmpty()) return@runCatching DEFAULT_MC_VERSIONS
        mcVersions.sortedWith(mcVersionComparator)
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MC_VERSIONS

    /**
     * 下载服务端核心到指定路径（独立方法，供 DownloadScreen 调用）。
     * 使用 Java HTTP 直接下载，不依赖 Termux 的 wget 命令。
     * @param dirName 核心文件夹名（仅用于日志）
     */
    suspend fun downloadCoreTo(jarPath: String, config: McConfig, dirName: String = "default") = withContext(Dispatchers.IO) {
        downloadCoreTo(jarPath, config, dirName) { _, _, _ -> }
    }

    /**
     * 下载服务端核心到指定路径（带进度回调版本）。
     * 使用 Java HTTP 直接下载，不依赖 Termux 的 wget 命令。
     * @param dirName 核心文件夹名（仅用于日志）
     * @param onProgress 回调参数：(已下载字节, 总字节, 速度 bytes/s)，总字节为 -1 表示未知
     */
    suspend fun downloadCoreTo(jarPath: String, config: McConfig, dirName: String = "default", onProgress: (Long, Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val urls = resolveDownloadUrls(config.selectedCore, config.mcVersion)
        val expectedDigest = if (config.selectedCore == ServerCore.PowerNukkitX) {
            runCatching { resolvePowerNukkitXDigest(config.mcVersion) }.getOrNull()
        } else null
        Log.i(TAG, "downloadCoreTo: core=${config.selectedCore}, version=${config.mcVersion}, urls=$urls")
        termux.emitLog("[download] 开始下载 ${config.selectedCore.displayName} ${config.mcVersion}")
        termux.emitLog("[download] 保存路径: $jarPath")

        val outFile = File(jarPath)
        outFile.parentFile?.mkdirs()

        var lastError: Exception? = null
        // Every candidate source is tried once before retrying the first source.
        val maxAttempts = maxOf(3, urls.size)
        repeat(maxAttempts) { attempt ->
            val url = urls[attempt % urls.size]
            try {
                termux.emitLog("[download] 通道 ${attempt + 1}/${urls.size}: $url")
                MultiThreadDownloader.download(
                    url = url,
                    target = outFile,
                    onProgress = { downloaded, totalBytes, speedBytesPerSec ->
                        onProgress(downloaded, totalBytes, speedBytesPerSec)
                    },
                    onLog = { msg -> termux.emitLog("[download] $msg") }
                )

                // 校验文件大小
                val fileSize = outFile.length()
                if (fileSize < 1024) {
                    throw RuntimeException("下载文件过小 ($fileSize 字节)，可能下载失败")
                }
                if (config.selectedCore == ServerCore.PowerNukkitX) {
                    JarFile(outFile).use { jar ->
                        check(
                            jar.getEntry("org/powernukkitx/Server.class") != null ||
                                jar.getEntry("org/powernukkitx/JarStart.class") != null ||
                                jar.getEntry("cn/nukkit/Server.class") != null
                        ) {
                            "PowerNukkitX 下载内容不是有效核心 JAR"
                        }
                    }
                    if (expectedDigest != null) {
                        check(sha256(outFile).equals(expectedDigest, ignoreCase = true)) {
                            "PowerNukkitX SHA-256 校验失败"
                        }
                    }
                }
                termux.emitLog("[download] 下载完成: ${fileSize / 1024 / 1024}MB")
                Log.i(TAG, "downloadCoreTo: success, saved to $jarPath (${fileSize} bytes)")
                return@withContext} catch (e: Exception) {
                Log.w(TAG, "downloadCoreTo attempt ${attempt + 1} failed: ${e.message}")
                termux.emitLog("[download] 第 ${attempt + 1} 次失败: ${e.message}")
                lastError = e
                outFile.delete()
                if (attempt < 2) {
                    termux.emitLog("[download] 等待 ${1500L * (attempt + 1)}ms 后重试...")
                    delay(1500L * (attempt + 1))
                }
            }
        }
        throw RuntimeException("下载失败（已重试3次）: ${lastError?.message}")
    }

    private fun fetchJsonElement(urlStr: String): JsonElement {
        var lastError: Exception? = null
        // 重试 3 次，每次增加超时时间
        repeat(3) { attempt ->
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000 + attempt * 10_000
                conn.readTimeout = 30_000 + attempt * 10_000
                conn.setRequestProperty("User-Agent", "MineServeMobile/1.0 (https://github.com/MineServe-Mobile)")
                conn.setRequestProperty("Accept", "application/json")
                conn.instanceFollowRedirects = true

                val code = conn.responseCode
                if (code != 200) {
                    val errBody = try { conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) } catch (_: Exception) { null }
                    Log.w(TAG, "fetchJsonElement attempt ${attempt + 1}: HTTP $code for $urlStr, body=$errBody")
                    throw RuntimeException("HTTP $code: ${conn.responseMessage}")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                return json.parseToJsonElement(body)
            } catch (e: Exception) {
                Log.w(TAG, "fetchJsonElement attempt ${attempt + 1} failed: ${e.message}")
                lastError = e
                if (attempt < 2) {
                    try { Thread.sleep(1500L * (attempt + 1)) } catch (_: InterruptedException) {}
                }
            } finally {
                conn.disconnect()
            }
        }
        throw lastError ?: RuntimeException("fetchJsonElement failed: $urlStr")
    }

    /**
     * 启动 MC 服务
     * - 从 config.activeCoreName 找到对应的已安装核心
     * - 如果核心不存在或 jar 缺失，抛出异常
     * - 支持崩溃自动重启（config.autoRestartOnCrash）
     *
     * @param userInitiated true 表示由用户点击「启动」发起。只有这种启动才生成新的
     *        代际号并在准备阶段响应「用户点了停止」；崩溃自动重启走 false，
     *        避免把「用户此前某次停止」的历史标记当成取消依据。
     */
    suspend fun start(config: McConfig, userInitiated: Boolean = true) = withContext(Dispatchers.IO) {
        // 记录本次启动的代际：准备阶段（可能数分钟）里用户若点了停止，
        // 代际会失效，launchMc 在真正 spawn 之前就会放弃，而不是照旧把服务端拉起来。
        val startGeneration = if (userInitiated) termux.beginStartRequest() else 0L
        // 先确保 Termux 环境已初始化
        if (!termux.isReady()) {
            throw RuntimeException("Termux 环境未初始化，请等待初始化完成")
        }
        // 首次安装依赖
        if (!repo.serverState.value.isInstallComplete) {
            val ok = installDependencies()
            if (!ok) {
                throw RuntimeException("依赖安装失败，请先安装依赖后再启动服务器")
            }
        } else {
            termux.fixRootfsPermissions()
        }
        // 依赖安装/修复可能耗时很久：进入下一步前先确认用户没有取消这次启动。
        if (userInitiated && termux.isStartCancelled(startGeneration)) {
            termux.emitLog("[startMc] 准备阶段已收到停止请求，本次启动已取消")
            return@withContext
        }
        // 找到当前选用的核心
        val activeCore = config.installedCores.find { it.name == config.activeCoreName }
            ?: throw RuntimeException("未选择要启动的服务端核心，请先在「下载」Tab 下载或选择")
        // PocketMine 用 PHP 运行（PHP 在启动时按需安装），跳过 Java 检查
        if (activeCore.core != ServerCore.PocketMine &&
            !termux.isJavaInstalled(config.selectedJavaVersion, config.selectedJavaVendor)
        ) {
            throw RuntimeException("${config.selectedJavaVersion.displayName} 未安装，请先在 Java 管理卡片中安装")
        }
        val (launchConfig, launchCore) = migrateToAsciiServerDir(config, activeCore)
        val serverDir = File(termux.serversDir, launchCore.dirName)
        val configuredEntry = launchCore.serverFile
            ?.trim()
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
        val jarFile = configuredEntry
            ?.let { File(serverDir, it).takeIf(File::isFile) }
            ?: ServerCoreDetector.detect(serverDir).serverFile
                ?.let { File(serverDir, it).takeIf(File::isFile) }
        if (jarFile == null && launchCore.core !in setOf(ServerCore.Forge, ServerCore.NeoForge)) {
            throw RuntimeException("核心 ${launchCore.name} 未找到可启动的 JAR；导入内容未被修改，请在服务器目录中补充入口文件")
        }
        if (launchCore.core == ServerCore.PowerNukkitX && isLegacyPowerNukkitXVersion(launchCore.version)) {
            validateLegacyPowerNukkitXJar(jarFile ?: error("PowerNukkitX 核心入口缺失"))
        }
        // 启动时重置崩溃重试计数
        restartAttempts = 0
        launchMc(launchConfig, launchCore.dirName, jarFile?.absolutePath, startGeneration)
    }

    /**
     * 真正 spawn 进程之前统一检查「本次启动是否已被用户取消」。
     *
     * @return true 表示已取消，调用方应立即 return，**不得**启动服务端。
     */
    private suspend fun abortIfStartCancelled(startGeneration: Long): Boolean {
        if (startGeneration == 0L) {
            // 非用户发起（崩溃自动重启 / 定时任务）：沿用原先的行为，在本轮启动前
            // 清掉历史上的「用户已请求停止」标记 —— 否则本轮的真实崩溃会被
            // createExitHandler 判成「用户主动停止」而漏报、也不自动重启。
            // 用户发起的启动在 beginStartRequest() 里已经清过一次。
            termux.clearUserStopRequest()
            return false
        }
        if (!termux.isStartCancelled(startGeneration)) return false
        termux.emitLog("[startMc] 已收到停止请求，本次启动已在拉起进程前取消")
        // 把状态复位回「未运行」，否则 UI 会一直停在启动中。
        repo.updateServerState {
            it.copy(isRunning = false, runningSinceMs = 0L, startupPhase = StartupPhase.Idle)
        }
        return true
    }

    /** Forge's ZipFileSystem cannot parse percent-encoded non-ASCII working paths on Java 25. */
    private suspend fun migrateToAsciiServerDir(
        config: McConfig,
        activeCore: InstalledCore
    ): Pair<McConfig, InstalledCore> {
        val migratedDirName = sanitizeDirName(activeCore.dirName)
        if (migratedDirName == activeCore.dirName) return config to activeCore

        val source = File(termux.serversDir, activeCore.dirName)
        val target = File(termux.serversDir, migratedDirName)
        if (!source.isDirectory) throw RuntimeException("核心目录不存在：${source.absolutePath}")
        if (target.exists()) throw RuntimeException("无法迁移核心目录，目标目录已存在：${target.name}")
        if (!source.renameTo(target)) throw RuntimeException("无法迁移核心目录，请检查存储空间后重试")

        val migratedCore = activeCore.copy(dirName = migratedDirName)
        // 只改 installedCores 这一个字段，基于权威内存态原子完成；
        // 传整份 config 会把并发期间别的修改一起覆盖掉。
        val migratedConfig = repo.updateAndSaveConfig { current ->
            current.copy(
                installedCores = current.installedCores.map {
                    if (it.name == activeCore.name && it.dirName == activeCore.dirName) migratedCore else it
                }
            )
        }
        termux.emitLog("[startMc] 已迁移核心目录到 ASCII 名称：$migratedDirName")
        return migratedConfig to migratedCore
    }

    /**
     * 实际启动 MC 进程（内部方法，供 start 和崩溃重启调用）
     */
    /**
     * 按 Minecraft 版本推荐最低 Java 版本（服务端启动用，不覆盖基岩版）。
     *  - MC 26.1+ → Java 25（Paper 官方要求）
     *  - MC 1.21+ → Java 21；MC 1.17–1.20.4 → Java 17；MC ≤ 1.16 → Java 8
     * 解析失败返回 null（沿用用户选择）。
     */
    private fun recommendedJavaForMinecraft(mcVersion: String, core: ServerCore): JavaVersion? {
        if (core == ServerCore.PowerNukkitX || core == ServerCore.Unknown) return null
        val parts = mcVersion.trim().removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        val major = parts[0]
        val minor = parts.getOrNull(1)
        return when {
            major >= 26 -> JavaVersion.Java25
            major == 1 && minor != null && minor >= 21 -> JavaVersion.Java21
            major == 1 && minor != null && minor >= 17 -> JavaVersion.Java17
            major == 1 && minor != null -> JavaVersion.Java8
            else -> null
        }
    }

    /** Forge 1.16 及更早版本：FML 只支持 Java 8/11，Termux 无 Java 11，只能用 Java 8。 */
    private fun isLegacyForgeMcVersion(mcVersion: String): Boolean {
        val parts = mcVersion.trim().removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrNull(0) ?: return false
        val minor = parts.getOrNull(1) ?: return false
        return major == 1 && minor <= 16
    }

    private fun isLegacyPowerNukkitXVersion(version: String): Boolean =
        version.trim().removePrefix("v").removePrefix("V").contains("-r", ignoreCase = true) ||
            version.trim().endsWith("-PNX", ignoreCase = true)

    /** Legacy Maven also publishes an unbundled ~7 MB JAR; it cannot run alone. */
    private fun validateLegacyPowerNukkitXJar(jarFile: File) {
        try {
            JarFile(jarFile).use { jar ->
                val hasEntryPoint = jar.getEntry("cn/nukkit/JarStart.class") != null
                val hasBundledDependency = jar.getEntry("io/netty/channel/Channel.class") != null ||
                    jar.getEntry("com/google/gson/Gson.class") != null
                check(hasEntryPoint && hasBundledDependency) {
                    "PowerNukkitX Legacy 当前是未打包依赖的普通 JAR（约 7MB），请删除后重新下载；应用将下载 shaded 核心（约 83MB）"
                }
            }
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException("无法校验 PowerNukkitX Legacy 核心：${e.message}", e)
        }
    }

    /**
     * @param startGeneration 本次启动的代际号（非用户发起时为 0，表示不做取消检查）
     */
    private suspend fun launchMc(config: McConfig, dirName: String, jarPath: String?, startGeneration: Long = 0L) {
        val serverDir = termux.serverDirFor(dirName)
        val activeCore = config.installedCores.find { it.name == config.activeCoreName }
        val coreType = activeCore?.core ?: config.selectedCore

        // ── 完全自定义启动命令模式 ──
        if (config.advancedCustomCommandEnabled && config.advancedCustomCommand.isNotBlank()) {
            if (abortIfStartCancelled(startGeneration)) return
            termux.emitLog("[startMc] 使用完全自定义启动命令")
            startupDeadlineMs = System.currentTimeMillis() + 20_000L
            processStartedAtMs = System.currentTimeMillis()
            termux.startMcCustom(
                command = config.advancedCustomCommand,
                dirName = dirName,
                jvmFlavor = config.jvmFlavor,
                javaVendor = config.selectedJavaVendor,
                onExit = createExitHandler(config, dirName, jarPath)
            )
            repo.updateServerState { markRunningIfAlive(it) }
            return
        }

        // 运行时以**用户选择的运行模式**（config.runtimeKind）为准，核心只决定启动哪些文件。
        // 用户选 PHP 就走 PHP 路径，选 Java 就走 JVM 路径；组合不成立时在这里明确报错，
        // 而不是替用户改掉选择。
        val usePhp = config.runtimeKind == RuntimeKind.Php
        if (usePhp && coreType != ServerCore.PocketMine) {
            throw RuntimeException(
                "${coreType.displayName} 核心无法用 PHP 运行，请在「运行环境」中切换为 Java，或改用 PocketMine-MP 核心"
            )
        }
        if (!usePhp && coreType == ServerCore.PocketMine) {
            throw RuntimeException(
                "PocketMine-MP 核心需要 PHP 运行环境，请在「运行环境」中切换为 PHP 后重试"
            )
        }

        if (coreType == ServerCore.PowerNukkitX) {
            val properties = File(serverDir, "server.properties")
            val current = if (properties.exists()) properties.readText() else ""
            properties.writeText(updatePowerNukkitXProperties(current, config.localPort))
        }
        // Allay：默认同时绑定 IPv6（::），而 Termux 的 openjdk 判定「本机无 IPv6」
        // ⇒ 绑定 :: 会抛 UnsupportedAddressTypeException，网络接口起不来。
        // 所以：
        // 但现在这条判定已被绕过（Termux 靠兼容层、FCL 那套 JRE 本身就不查），
        // 因此只在**确实绑不上**时才强制关（见 McConfig.ipv6ListenAvailable）：
        //   - 能绑：首次生成配置写 enablev6=true，且**不再改写**已有配置 ——
        //     改不改由用户在 Allay 配置里自己决定；
        //   - 绑不上：沿用老行为，写 false 并每次启动纠正。
        if (coreType == ServerCore.Allay) {
            val settings = File(serverDir, "server-settings.yml")
            val forceDisableIpv6 = !config.ipv6ListenAvailable
            if (!settings.exists()) {
                settings.writeText(
                    "network-settings:\n" +
                        "  ip: 0.0.0.0\n" +
                        "  port: ${config.localPort}\n" +
                        "  enablev6: ${if (forceDisableIpv6) "false" else "true"}\n"
                )
                termux.emitLog(
                    if (forceDisableIpv6) {
                        "[startMc] 已生成 Allay 配置（禁用 IPv6 绑定，端口 ${config.localPort}）"
                    } else {
                        "[startMc] 已生成 Allay 配置（当前 JVM 支持 IPv6，保留 :: 绑定，端口 ${config.localPort}）"
                    }
                )
            } else {
                val current = settings.readText()
                var updated = if (forceDisableIpv6) {
                    current.replace(Regex("enablev6: *true"), "enablev6: false")
                } else {
                    current
                }
                updated = updated.replace(Regex("(\n  port: *)\\d+"), "$1${config.localPort}")
                if (updated != current) {
                    settings.writeText(updated)
                    termux.emitLog("[startMc] 已修正 Allay 配置（端口 ${config.localPort}）")
                }
            }
        }
        // PocketMine：需要官方定制 PHP（含 chunkutils2/encoding/leveldb/pmmpthread 等扩展）。
        // PMMP 官方不发布 Linux ARM64 PHP，使用社区 Android 原生编译版（ItzxDwi/AndroidPHP，aarch64），
        // 首次启动时下载到共享运行时目录，多服务器复用。运行时版本取用户所选 selectedPhpVersion。
        if (coreType == ServerCore.PocketMine) {
            val phpVersion = config.selectedPhpVersion
            if (!termux.isPhpInstalled(phpVersion) &&
                !termux.installPhp(phpVersion) { line -> termux.emitLog(line) }
            ) {
                throw RuntimeException("${phpVersion.displayName} 运行环境下载失败，请检查网络后重试")
            }
            val phpBin = termux.phpBinaryFor(phpVersion)
            if (!phpBin.isFile || !phpBin.canExecute()) {
                throw RuntimeException("${phpVersion.displayName} 运行环境不完整，请删除 ${phpVersion.runtimeDirName} 目录后重试")
            }
            val phar = serverDir.listFiles { f -> f.isFile && f.name.endsWith(".phar", ignoreCase = true) }
                ?.maxByOrNull { it.lastModified() }
                ?: throw RuntimeException("未找到 PocketMine-MP.phar，请重新下载核心")
            // PHP 可能是首次下载安装（耗时较长），spawn 之前再确认一次用户没有取消。
            if (abortIfStartCancelled(startGeneration)) return
            startupDeadlineMs = System.currentTimeMillis() + 20_000L
            processStartedAtMs = System.currentTimeMillis()
            termux.startPhp(phpBin.absolutePath, File(phpBin.parentFile, "php.ini").absolutePath, phar.absolutePath, dirName, createExitHandler(config, dirName, phar.absolutePath))
            repo.updateServerState { markRunningIfAlive(it) }
            return
        }
        // 按 MC 版本自动匹配所需 Java（如 Paper 26.1+ 要求 Java 25）：当前选择不足时自动升级并安装
        val mcVersion = activeCore?.version?.takeIf { it.isNotBlank() } ?: config.mcVersion
        val recommended = recommendedJavaForMinecraft(mcVersion, coreType)
        // 启动用的来源 = 用户在「运行环境」里选的那个，**全程不变**。
        // 自动匹配只调版本，见 requireVersionInVendor 的说明。
        val launchVendor = config.selectedJavaVendor
        /**
         * 自动切换 Java 版本时的来源策略：**来源永远停在用户选的那一个**。
         *
         * 早先这里会在所选来源不提供目标版本时，回退到「第一个提供该版本的来源」。
         * 后果是：用户在「运行环境」里明确选了 PRoot 容器，启动 Paper 26.1 时
         * 被静默改成 **Termux + Java 25** —— 而且这个改动会 `updateAndSaveConfig`
         * 落盘，用户的显式选择被永久覆盖，下次启动直接换了链路。
         *
         * 这不是「自动修复」，是把用户的显式选择当成可以推翻的建议。运行环境是
         * 用户唯一能指定「用哪个 glibc/bionic 世界跑服务端」的入口，换掉它就等于
         * 换掉整个运行前提（PRoot 的存在意义正是脱离 bionic）。
         *
         * 现在只允许在**同一来源内**调整版本；来源确实提供不了时明确报错，
         * 把「换来源还是换版本」的决定权交还给用户。
         */
        fun requireVersionInVendor(version: JavaVersion, reason: String): JavaVersion {
            val distro = config.prootDistro
            if (launchVendor.provides(version, distro)) return version
            val label = if (launchVendor == JavaVendor.Proot) {
                "${launchVendor.logName} 容器的 ${distro.displayName}"
            } else {
                launchVendor.logName
            }
            val available = launchVendor.availableVersions(distro).joinToString(" / ") { it.displayName }
            // 如果换个发行版就能装，直接把话说到底 —— 这才是这条错误最常见的解法，
            // 而「改用其他来源」对只想留在 PRoot 里的用户并不是想要的建议。
            val otherDistro = ProotDistro.entries.firstOrNull { distro2 ->
                distro2 != distro && version in launchVendor.availableVersions(distro2)
            }
            val advice = when {
                launchVendor == JavaVendor.Proot && otherDistro != null ->
                    "把「${distro.shortName}」切换到「${otherDistro.shortName}」即可使用 " +
                        "${version.displayName}（切发行版不会删除已装的其他 Java 版本）"
                else ->
                    "请在「运行环境」里改用其他来源，或换一个该来源支持的版本"
            }
            throw RuntimeException(
                "$reason，但当前来源「$label」不提供 ${version.displayName}" +
                    "（该来源可用：$available）。$advice；" +
                    "为避免换掉你选择的运行链路，这里不会自动切换来源。"
            )
        }
        val launchJava = if (recommended != null && config.selectedJavaVersion.ordinal < recommended.ordinal) {
            val target = requireVersionInVendor(
                recommended,
                "$mcVersion 需要 Java ${recommended.displayName} 及以上"
            )
            termux.emitLog("[startMc] $mcVersion 需要 Java ${target.displayName} 及以上（当前选择 ${config.selectedJavaVersion.displayName}），已自动切换")
            // 只改版本，**不动来源** —— 来源是用户的显式选择。
            runCatching {
                repo.updateAndSaveConfig { it.copy(selectedJavaVersion = target) }
            }
            target
        } else config.selectedJavaVersion
        // Forge ≤1.16 的 FML 只支持 Java 8/11。实测 Termux Bionic 版 openjdk-11（TUR）
        // 在 modlauncher 的 Worker-Bootstrap 线程上 libjvm.so 段错误（SIGSEGV），
        // 因此优先 Ubuntu glibc 的 Java 8；Java 11 仅作最后备选并在日志注明风险。
        val effectiveLaunchJava = if (coreType == ServerCore.Forge && isLegacyForgeMcVersion(mcVersion) &&
            launchJava != JavaVersion.Java8 && launchJava != JavaVersion.Java11
        ) {
            // 候选按「用户所选来源内可装」过滤，而不是只看装没装：
            // 来源不允许的版本即使装在别处也取不到 java。
            val distro = config.prootDistro
            val candidates = listOf(JavaVersion.Java8, JavaVersion.Java11)
                .filter { launchVendor.provides(it, distro) && termux.isJavaInstalled(it, launchVendor) }
            val fallback = candidates.firstOrNull()
                ?: throw RuntimeException(
                    "Forge $mcVersion 需要 Java 8 或 Java 11 运行（不支持 ${launchJava.displayName}），" +
                        "而当前来源「${launchVendor.logName}」" +
                        if (launchVendor.provides(JavaVersion.Java11, distro)) {
                            "尚未安装 Java 8 / Java 11。请在「Java 管理」卡片安装 Java 8（推荐，Ubuntu 环境稳定）后重试"
                        } else {
                            "不提供 Java 8 / Java 11。请在「运行环境」里改用 PRoot 容器或 Termux Java（二者都提供 Java 8/11）后重试"
                        }
                )
            if (fallback == JavaVersion.Java11) {
                termux.emitLog("[startMc] 警告：TUR Java 11 为 Bionic 构建，运行 Forge ≤1.16 可能触发 libjvm 段错误；建议安装 Java 8（Ubuntu）")
            }
            termux.emitLog("[startMc] Forge $mcVersion 不支持 ${launchJava.displayName} 运行，已自动切换 ${fallback.displayName}")
            // 同样是只改版本、不改来源：以前这里无条件写 launchVendor = Termux，
            // 会把用户选的 PRoot 容器换掉，甚至在 Java 8 已归 PRoot 后写出
            // 「Termux + Java 8」这种列表里不存在的组合。
            runCatching {
                repo.updateAndSaveConfig { it.copy(selectedJavaVersion = fallback) }
            }
            fallback
        } else launchJava
        // Allay 官方要求 Java 21+：低于 21 自动切换，未安装则明确报错
        val finalLaunchJava = if (coreType == ServerCore.Allay && effectiveLaunchJava.ordinal < JavaVersion.Java21.ordinal) {
            requireVersionInVendor(JavaVersion.Java21, "Allay 需要 Java 21 运行（当前 ${effectiveLaunchJava.displayName} 未满足）")
            if (!termux.isJavaInstalled(JavaVersion.Java21, launchVendor)) {
                throw RuntimeException("Allay 需要 Java 21 运行（当前 ${effectiveLaunchJava.displayName} 未满足），请先安装 Java 21 后重试")
            }
            termux.emitLog("[startMc] Allay 需要 Java 21 及以上（当前 ${effectiveLaunchJava.displayName}），已自动切换")
            runCatching {
                repo.updateAndSaveConfig { it.copy(selectedJavaVersion = JavaVersion.Java21) }
            }
            JavaVersion.Java21
        } else effectiveLaunchJava
        // 只对 Termux 来源做运行时修复：FCL 的 JRE 是整包解压出来的完整运行时，
        // 不存在「缺件、靠 apt 补装」这种状态，跑 apt 修复只会白折腾。
        termux.autoRepairRuntime(
            javaVersion = finalLaunchJava,
            needsFonts = coreType == ServerCore.Forge || coreType == ServerCore.NeoForge,
            vendor = launchVendor
        )
        if (coreType == ServerCore.NeoForge && config.selectedJavaVersion != JavaVersion.Java8) {
            termux.emitLog("[startMc] NeoForge 提示：Android 无 glibc 的 libc.so.6，依赖 OSHI/JNA 的模组可能崩服；已自动注入 Android 友好的 OSHI/JNA 属性")
            termux.emitLog("[startMc] ReferenceOpenHashSet 或 DistanceManager 异常属于 NeoForge/Minecraft 运行期崩溃，请以 crash-reports 的首个异常为准")
        }
        if ((coreType == ServerCore.Forge || coreType == ServerCore.NeoForge) &&
            launchVendor == JavaVendor.Termux &&
            effectiveLaunchJava != JavaVersion.Java8 && !termux.repairFontRuntime()) {
            // Forge/NeoForge 初始化 Minecraft 字体配置；旧环境可能在安装依赖前已下载核心。
            termux.emitLog("[startMc] 字体运行库仍未通过校验；已保留无图形模式启动参数")
        }
        val launchArgs = when (coreType) {
            ServerCore.Spigot, ServerCore.CraftBukkit -> {
                val coreJar = jarPath?.let(::File)
                    ?: throw RuntimeException("${coreType.displayName} 核心入口缺失，请检查导入目录")
                bukkitLaunchArguments(serverDir, coreJar)
            }
            ServerCore.Forge -> forgeLaunchArguments(serverDir, effectiveLaunchJava)
            ServerCore.NeoForge -> File(serverDir, "libraries/net/neoforged/neoforge")
                .walkTopDown().firstOrNull { it.name == "unix_args.txt" }
                ?.let { "@${it.absolutePath}" }
                ?: throw RuntimeException("NeoForge 启动文件缺失，请重新下载安装核心")
            ServerCore.Quilt -> {
                val launchJar = File(serverDir, "quilt-server-launch.jar")
                if (launchJar.exists()) "-jar ${launchJar.absolutePath}"
                else throw RuntimeException("Quilt 启动文件缺失，请重新下载安装核心")
            }
            ServerCore.PowerNukkitX -> {
                val coreJar = jarPath?.let(::File)
                    ?: throw RuntimeException("PowerNukkitX 核心入口缺失，请检查导入目录")
                val mainClass = powerNukkitXMainClass(coreJar)
                    ?: throw RuntimeException("PowerNukkitX 核心缺少可识别的启动入口（org.powernukkitx.Server/cn.nukkit.Nukkit），请重新下载")
                val languageArg = if (mainClass == "cn.nukkit.Nukkit") " --language=chs" else ""
                "--add-opens=java.base/java.lang=ALL-UNNAMED -cp '${coreJar.absolutePath}:${File(serverDir, "libs").absolutePath}/*' $mainClass$languageArg"
            }
            else -> null
        }
        if (launchArgs == null && jarPath == null) {
            throw RuntimeException("未找到可启动的 JAR；导入内容未被修改，请检查服务器目录")
        }
        // 真正 spawn 之前的最后一道取消检查：Java 自动切换、autoRepairRuntime、
        // 字体库安装等都可能耗时很久，用户完全可能在这期间点了「停止」。
        if (abortIfStartCancelled(startGeneration)) return
        startupDeadlineMs = System.currentTimeMillis() + 20_000L
        processStartedAtMs = System.currentTimeMillis()
        termux.startMc(
            jarPath = jarPath.orEmpty(),
            maxHeapMb = config.maxHeapMb,
            dirName = dirName,
            javaVersion = finalLaunchJava,
            launchArgs = launchArgs,
            appendNogui = coreType !in setOf(ServerCore.PowerNukkitX, ServerCore.Allay),
            jvmFlavor = config.jvmFlavor,
            javaVendor = launchVendor,
            onExit = createExitHandler(config, dirName, jarPath)
        )
        repo.updateServerState { markRunningIfAlive(it) }
    }

    /**
     * 启动返回的瞬间进程可能已退出：此时退出回调的停止/失败状态优先，
     * 不能在这里把 ServerState 覆盖回“运行中”。
     */
    private fun markRunningIfAlive(state: com.mineserve.mobile.data.ServerState): com.mineserve.mobile.data.ServerState {
        if (!termux.isMcRunning()) return state
        return if (state.startupPhase.progress <= StartupPhase.PreparingEnvironment.progress) {
            state.copy(isRunning = true, runningSinceMs = 0L, startupPhase = StartupPhase.PreparingEnvironment)
        } else state.copy(isRunning = true, runningSinceMs = 0L)
    }

    /** 提取 onExit 处理器，供 launchMc 和 startMcCustom 共用。 */
    private fun createExitHandler(config: McConfig, dirName: String, jarPath: String?): (Int) -> Unit = { code ->
        val uptimeMs = System.currentTimeMillis() - processStartedAtMs
        val failedDuringStartup = System.currentTimeMillis() <= startupDeadlineMs
        // 稳定运行过就先清零退避计数，让"偶发崩溃"和"持续崩溃"区别对待
        resetRestartBackoffIfStable()
        repo.updateServerState {
            it.copy(
                isRunning = false,
                startupPhase = if (code == 0) StartupPhase.Idle else StartupPhase.Failed
            )
        }
        Log.w(TAG, "MC process exited code=$code, uptime=${uptimeMs}ms, autoRestart=${config.autoRestartOnCrash}")
        /*
         * 判定"是否属于异常退出"。
         *
         * 历史逻辑是 `code == 0 && uptime < 5000` 才算异常。这条规则有个副作用：
         * 服务端**确实正常退出**（exit=0）但运行不足 5 秒时，会被判成崩溃，
         * 于是生成崩溃报告 + 弹通知 + 自动重启。实测日志里出现过
         * `Done (0.251s)!` 后 2 秒被 `Stopping the server`，MineServe 报
         * `[crash] 检测到异常退出(exit=0)` —— 服务端其实是被正常停掉的。
         *
         * 第二版判定：只有「**启动窗口内**就退出」才按异常处理
         *   - failedDuringStartup：20s 启动窗口还没过（进程根本没起来）
         *   - code != 0：非零退出码，本来就是异常
         *   - exit=0 且已过启动窗口 → 视为用户主动停止，走正常停止路径
         *
         * 第二版仍有一个副作用（用户实测反馈）：
         * **在启动后 20 秒内点「停止」**，服务端几秒后以 exit=0 正常关停，
         * 但此时启动窗口还没过 → failedDuringStartup 为 true → 误弹崩溃报告。
         * 报告里那条 `NoClassDefFoundError: com.sun.jna.Native` 是启动过程中
         * 某个 OSHI/JNA 模组留下的历史日志，被 captureCrash 当作"最近日志"
         * 一并打包，与"停止"这个动作毫无关系，却极容易被误读成停止导致的崩溃。
         *
         * 第三版（当前）：显式记录用户的停止请求 —— 只要在退出前有过一次
         * 主动停止请求，就**一律按正常停止处理**，不再生成崩溃报告。
         * 理由：用户已经表达了"我要停"，此时再弹崩溃报告属于纯粹的噪音。
         */
        val userStoppedRecently = userStopRequestedAtMs != 0L &&
            System.currentTimeMillis() - userStopRequestedAtMs <= USER_STOP_GRACE_MS
        val abnormalExit = !userStoppedRecently && (code != 0 || failedDuringStartup)
        if (userStoppedRecently) {
            Log.i(
                TAG,
                "exit=$code 发生在用户主动停止请求后 " +
                    "${System.currentTimeMillis() - userStopRequestedAtMs}ms，按正常停止处理，跳过崩溃判定"
            )
        }
        if (!abnormalExit) {
            val app = McApplication.get()
            ServerEventNotifier.notify(
                app,
                app.getString(R.string.notif_server_stopped_title),
                app.getString(R.string.notif_server_stopped_text),
                ServerEventNotifier.ID_STOPPED, 1
            )
        }
        if (abnormalExit) {
            // stdout writer 在进程退出哨兵后完成 flush，稍候再读文件避免报告缺失尾部。
            try { Thread.sleep(200) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            var reportPath: String? = null
            try {
                reportPath = crashReportManager.captureCrash(
                    code, wasRunningBefore = true, dirName = dirName, allowCleanExit = code == 0
                )
                if (reportPath != null) {
                    Log.i(TAG, "崩溃报告已保存: $reportPath")
                    termux.emitLog("[crash] 检测到异常退出(exit=$code)，崩溃报告已保存: ${File(reportPath).name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "捕获崩溃报告失败: ${e.message}", e)
            }
            val willAutoRestart = config.autoRestartOnCrash && restartAttempts < maxRestartAttempts
            val detail = if (code != 0 && failedDuringStartup) {
                "服务端启动后立即退出 (exit=$code)"
            } else if (code == 0) {
                // 启动窗口内 exit=0：测试核心、缺 EULA、配置错误等常见表现
                "服务端启动阶段即退出 (exit=$code)，请检查配置或核心是否匹配"
            } else if (willAutoRestart) {
                "服务端异常退出 (exit=$code)，稍后自动重启"
            } else {
                "服务端异常退出 (exit=$code)"
            }
            val failure = StartupFailure(code, reportPath, detail)
            lastStartupFailure = failure
            lastStartupFailureAtMs = System.currentTimeMillis()
            _startupFailures.tryEmit(failure)
            val app = McApplication.get()
            ServerEventNotifier.notify(
                app,
                app.getString(R.string.notif_server_crashed_title),
                app.getString(R.string.notif_server_crashed_text, code),
                ServerEventNotifier.ID_CRASH, 1
            )
        }
        if (config.autoRestartOnCrash && abnormalExit) {
            if (restartAttempts < maxRestartAttempts) {
                restartAttempts++
                // 指数退避：连续失败的重启间隔逐次翻倍，避免"秒退"形成启动风暴
                val backoffMs = restartBackoffMs(restartAttempts)
                Log.i(
                    TAG,
                    "崩溃自动重启中... (attempt $restartAttempts/$maxRestartAttempts, " +
                        "退避 ${backoffMs / 1000}s)"
                )
                termux.emitLog("[restart] 服务端异常退出，${backoffMs / 1000}s 后自动重启（第 $restartAttempts/$maxRestartAttempts 次）")
                // 记下本次异常退出的时刻：退避窗口内用户点「停止」的话，停止标记会晚于它。
                val exitAtMs = System.currentTimeMillis()
                Thread {
                    try {
                        Thread.sleep(backoffMs)
                        // 退避期间收到过停止请求就放弃这次重启。
                        // 旧代码是无条件 launchMc()，而 launchMc 内部还会 clearUserStopRequest()：
                        // 用户明确点了停止、并且已经看到"已停服"，退避到点后服务端却被重新拉起，
                        // 停止标记也被清掉，下一轮崩溃还会再弹一次崩溃通知。
                        val stopAt = userStopRequestedAtMs
                        if (stopAt != 0L && stopAt >= exitAtMs) {
                            Log.i(TAG, "退避期间收到停止请求，取消本次自动重启")
                            termux.emitLog("[restart] 已收到停止请求，取消自动重启")
                        } else {
                            kotlinx.coroutines.runBlocking {
                                launchMc(config, dirName, jarPath)
                                repo.updateServerState { markRunningIfAlive(it) }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "崩溃重启失败: ${e.message}", e)
                    }
                }.start()
            } else {
                Log.e(TAG, "已达到最大重试次数($maxRestartAttempts)，停止重启")
                termux.emitLog("[restart] 已连续失败 $maxRestartAttempts 次，停止自动重启，请检查服务端配置")
            }
        }
    }

    /**
     * Forge 1.17+ writes unix_args.txt, while Forge 1.12.2 and older writes a
     * top-level forge-*.jar.  The installer jar itself remains server.jar.
     */
    private suspend fun forgeLaunchArguments(serverDir: File, javaVersion: JavaVersion): String {
        // 来宾判定统一走谓词：以前这里只有 `javaVersion == Java8` 一处判断，
        // 新增 Proot 来源后必须同步，否则会出现「启动走容器、路径按宿主拼」。
        val isGuest = isProotGuestRuntime(termux.selectedProotVendor, javaVersion)
        serverDir.walkTopDown()
            .firstOrNull { it.name == "unix_args.txt" }
            ?.let {
                termux.emitLog("[startMc] Forge 启动方式: unix_args.txt")
                return "@${it.absolutePath}"
            }

        // Forge 1.12-1.16 installers leave the launchable jar at the top level
        // (a ServerLaunchWrapper with a Class-Path manifest) or inside
        // libraries/net/minecraftforge/forge/**, while server.jar stays the
        // installer. Search both so a completed install is recognized.
        findLegacyForgeServerJar(serverDir, javaVersion)?.let { jar ->
            termux.emitLog("[startMc] Forge 旧版启动方式: ${jar.name}")
            // 来宾链路里服务端在 `cd /srv/mineserve` 之后启动。这里必须传**相对路径**，
            // 否则 Android 宿主绝对路径会指向 bind 挂载之外的文件，容器内解析不到。
            return if (isGuest) {
                "-jar '${jar.relativeTo(serverDir).invariantSeparatorsPath}'"
            } else {
                "-jar ${jar.absolutePath}"
            }
        }

        // 来宾链路的下载可能把 installer 留在原始的 forge-*.jar 名字下。
        // 宿主链路（Java 17/25）保持原有的 server.jar-only 恢复行为。
        val installerCandidates = if (isGuest) {
            buildList {
                add(File(serverDir, "server.jar"))
                serverDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("forge-") && it.name.endsWith(".jar") }
                    ?.forEach(::add)
            }
        } else {
            listOf(File(serverDir, "server.jar"))
        }
        val installerJar = installerCandidates.firstOrNull {
            isForgeInstallerJar(it, serverDir, javaVersion)
        }
        if (installerJar != null) {
            termux.emitLog("[startMc] Forge 旧版安装产物不完整，正在重新部署服务端文件...")
            val code = runInstallerWithRetry(
                installerJar.absolutePath,
                serverDir,
                "Forge",
                File(termux.installer.rootDir, "tmp").apply { mkdirs() },
                javaVersion
            )
            if (code == 0) {
                findLegacyForgeServerJar(serverDir, javaVersion)?.let { jar ->
                    termux.emitLog("[startMc] Forge 旧版服务端文件已修复: ${jar.name}")
                    return if (javaVersion == JavaVersion.Java8) {
                        "-jar '${jar.relativeTo(serverDir).invariantSeparatorsPath}'"
                    } else {
                        "-jar ${jar.absolutePath}"
                    }
                }
            }
            throw RuntimeException("Forge 旧版服务端部署不完整，请检查网络后重新下载安装核心")
        }

        throw RuntimeException("Forge 启动文件缺失：未找到 unix_args.txt 或 forge-*.jar，请重新下载安装核心")
    }

    private fun requireInstallerLaunchArtifacts(core: ServerCore, serverDir: File) {
        val ready = when (core) {
            ServerCore.Forge -> serverDir.walkTopDown().any { it.name == "unix_args.txt" } ||
                serverDir.walkTopDown().any { it.isFile && it.name.startsWith("forge-") && it.name.endsWith(".jar") && !hasForgeInstallerMainClass(it) }
            ServerCore.NeoForge -> File(serverDir, "libraries/net/neoforged/neoforge")
                .walkTopDown().any { it.name == "unix_args.txt" }
            ServerCore.Quilt -> File(serverDir, "quilt-server-launch.jar").isFile
            else -> true
        }
        check(ready) { "${core.displayName} installer 未生成启动文件，请检查安装日志和网络后重试" }
    }

    /**
     * 旧版 Forge（≤1.16）的可启动 jar：顶层 wrapper（Main-Class=ServerLaunchWrapper，
     * 通过 Class-Path 清单加载 libraries）或 libraries 下的 forge-*-server.jar。
     * Forge 1.16.5 的安装产物在 libraries 子目录，顶层查找会漏判。
     */
    private fun findLegacyForgeServerJar(serverDir: File, javaVersion: JavaVersion): File? {
        val topLevel = serverDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val libraries = File(serverDir, "libraries/net/minecraftforge/forge")
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile }
            ?: emptySequence()
        return (topLevel.asSequence() + libraries)
            .filter { file ->
                file.name.startsWith("forge-") && file.name.endsWith(".jar") &&
                    isLegacyForgeServerJar(file, serverDir, javaVersion)
            }
            .maxByOrNull { it.lastModified() }
    }

    private fun isLegacyForgeServerJar(file: File, serverDir: File, javaVersion: JavaVersion): Boolean {
        val mainClass = runCatching {
            JarFile(file).use { jar ->
                jar.manifest?.mainAttributes?.getValue("Main-Class")
            }
        }.getOrNull() ?: return false
        // 安装器入口是 SimpleInstaller。可启动入口按版本分四类：
        // 1.12- 的 universal 是 fml.relauncher.ServerLaunchWrapper；
        // 1.16 顶层 wrapper 与安装器同包（installer.ServerLaunchWrapper）；
        // 1.13-1.16 新版安装器的 forge-*-server.jar 入口是 fml.loading.FMLServerTweaker（无 Main-Class 的变体也存在）；
        // 1.16.5（36.x）的顶层 launcher 是 net.minecraftforge.server.ServerMain（设备 MANIFEST 实测）。
        if (mainClass.contains("SimpleInstaller", ignoreCase = true)) return false
        return mainClass.contains("ServerLaunchWrapper", ignoreCase = true) ||
            mainClass.contains("FMLServerTweaker", ignoreCase = true) ||
            mainClass.contains("net.minecraftforge.server.ServerMain", ignoreCase = true)
    }

    private fun isForgeInstallerJar(file: File, serverDir: File, javaVersion: JavaVersion): Boolean {
        if (javaVersion == JavaVersion.Java8) {
            if (hasForgeInstallerMainClass(file)) return true
            val guestJar = "${TermuxRuntime.GUEST_SERVER_DIR}/${file.relativeTo(serverDir).invariantSeparatorsPath}"
            return termux.runJava8Command(
                serverDir,
                "jar tf '$guestJar' 2>/dev/null | grep -q 'net/minecraftforge/installer/SimpleInstaller.class'",
                60_000
            ) == 0
        }
        return hasForgeInstallerMainClass(file)
    }

    private fun hasForgeInstallerMainClass(file: File): Boolean = runCatching {
        JarFile(file).use { jar ->
            // 安装器的入口精确为 SimpleInstaller；1.16 顶层 wrapper 与安装器同包
            // （net.minecraftforge.installer.ServerLaunchWrapper），不能按包名误判。
            jar.manifest?.mainAttributes?.getValue("Main-Class")
                ?.contains("SimpleInstaller", ignoreCase = true) == true
        }
    }.getOrDefault(false)

    suspend fun stop() = withContext(Dispatchers.IO) {
        // 标记由 TermuxRuntime.stopMc() 内部统一打上（见那里的说明），
        // 这里只需发出停止请求即可。
        termux.stopMc()
        repo.updateServerState {
            it.copy(isRunning = false, runningSinceMs = 0L, startupPhase = StartupPhase.Idle)
        }
    }

    /**
     * 优雅停止（用户点「停止」按钮走这条）。
     *
     * 与 [stop] 的区别：**没有强制兜底**。发完 `stop` 一直等到服务端自己退出；
     * 超时也不强杀，而是返回 false 并保持 `isRunning = true` ——
     * 此时界面上仍是运行态，用户可以点「强制停止」显式承担数据风险。
     *
     * 这样做的原因见 [TermuxRuntime.stopMcGraceful]：旧行为 5 秒强杀会在
     * 大世界落盘中途 SIGKILL，直接表现为「停止后存档回档」。
     *
     * @return true 表示服务端已退出；false 表示仍在关停中。
     */
    suspend fun stopGracefully(): Boolean = withContext(Dispatchers.IO) {
        val stopped = termux.stopMcGraceful()
        if (stopped) {
            repo.updateServerState {
                it.copy(isRunning = false, runningSinceMs = 0L, startupPhase = StartupPhase.Idle)
            }
        }
        stopped
    }

    /**
     * 强制停止：立即结束进程，不等待落盘。
     *
     * 调用方（UI）必须已经拿到用户的显式确认 —— 这里不做任何二次询问。
     */
    suspend fun forceStop() = withContext(Dispatchers.IO) {
        termux.forceStopMc()
        repo.updateServerState {
            it.copy(isRunning = false, runningSinceMs = 0L, startupPhase = StartupPhase.Idle)
        }
    }


    fun sendCommand(line: String) {
        termux.sendCommand(if (line.startsWith("/")) line.substring(1) else line)
    }

    companion object {
        private const val TAG = "McServerController"

        /** 崩溃重启退避的上限（60s），避免长间隔让用户误以为不再重启。 */
        private const val MAX_RESTART_BACKOFF_MS = 60_000L

        /**
         * 「用户已请求停止」标记的有效期。
         *
         * 点了停止之后，服务端要先跑完 `stop` 命令、落盘、再退出，实测可能
         * 花十几秒（大世界尤其慢）。这个宽限窗口必须覆盖这段延迟，否则
         * 服务端退出时标记已过期，又会被判成异常退出。
         *
         * 取 5 分钟：远大于正常关停耗时，同时保证"停完之后过很久才发生的
         * 真实崩溃"仍能被正确判定为异常（那种情况标记早该失效了）。
         */
        private const val USER_STOP_GRACE_MS = 5 * 60_000L

        /** 进程存活超过 5 分钟即视为启动成功，下次崩溃重启从最短间隔重新开始。 */
        private const val RESTART_BACKOFF_RESET_MS = 5 * 60_000L

        /**
         * 该 PHP 构建内置 pmmp/ext-encoding 0.4.x，PocketMine 5.34.0 起要求 ~1.0.0，
         * 因此可兼容的最高 PocketMine 版本为 5.33.1（实测其 composer 要求）。
         */
        private const val PMMP_MAX_COMPAT_VERSION = "5.33.1"

        /**
         * 在 servers 目录下为 [base] 找一个未被占用的目录名（静态版，需自行传入已占用集合）。
         *
         * 冲突时依次尝试 `base_2`、`base_3`… 判重同时看磁盘目录与 [taken]（已登记的核心）。
         */
        fun uniqueDirName(base: String, taken: Set<String>, serversDir: File): String {
            var candidate = base
            var i = 2
            while (candidate in taken || File(serversDir, candidate).exists()) {
                candidate = "${base}_$i"
                i++
            }
            return candidate
        }

        /** 默认 MC 版本列表（当 API 获取失败时回退使用） */
        private val DEFAULT_MC_VERSIONS = listOf(
            "1.21.4", "1.21", "1.20.6", "1.20.4", "1.20.1",
            "1.19.4", "1.19.2", "1.18.2", "1.17.1", "1.16.5"
        )

        /** 服务端工作目录必须是 ASCII，避免 Forge/Java 25 解析 file URI 时出现 Bad escape。 */
        fun sanitizeDirName(name: String): String {
            val normalized = name
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .replace(Regex("_+"), "_")
                .trimEnd('_')
                .ifEmpty { "server" }
            return if (normalized == name) normalized else {
                val suffix = MessageDigest.getInstance("SHA-256")
                    .digest(name.toByteArray(Charsets.UTF_8))
                    .take(6)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                "$normalized-$suffix"
            }
        }
    }
}
