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
import com.mineserve.mobile.data.FileOps
import com.mineserve.mobile.data.BdsDigestStore
import com.mineserve.mobile.data.BdsVersionCatalogLoader
import com.mineserve.mobile.data.ProotDistro
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.ServerStorage
import com.mineserve.mobile.data.StorageLocation
import com.mineserve.mobile.data.MinecraftVersionNormalizer
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.StepStatus
import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.data.isProotGuestRuntime
import com.mineserve.mobile.data.provides
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
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
 * 把 PaperMC v3 的 `versions` 字段平铺成字符串列表。
 *
 * 同一个 API 在不同项目/不同时间返回过两种结构：
 * - 两层对象：`{"4.0.0":["4.3.2-SNAPSHOT","4.3.1"], "3.0.0":[...]}`（Paper、Velocity 现状）
 * - 扁平数组：`["1.20.4","1.20.2"]`
 *
 * 只认数组会让对象形态直接抛 IllegalArgumentException，整条版本链断掉
 * （历史事故：切到 Velocity 后版本列表永远为空）。两种都兼容，非原始值跳过。
 */
internal fun parseVersionGroups(versionsEl: JsonElement?): List<String> = when (versionsEl) {
    is JsonObject -> versionsEl.values.flatMap { sub ->
        (sub as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }
    is JsonArray -> versionsEl.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    else -> emptyList()
}

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
 * 由 MC 版本号推导 NeoForge 版本号的「段前缀」，**带尾点**以保证段边界。
 *
 * NeoForge 编号规则：MC `1.21.4` → NeoForge `21.4.x`（去掉开头的 `1.`）；
 * MC `26.2` → NeoForge `26.2.x`（新编号体系不再去前缀）。
 *
 * 返回的串一律带尾点（如 `1.21.1` → `["21.1."]`），供 `startsWith` 锁死段边界。
 *
 * 这里绝不能返回不带尾点的裸前缀：MC `1.21.1` 若生成 `21.1` 并参与 `startsWith`，
 * 会把 `21.11.45` 一起命中（`"21.11.45".startsWith("21.1") == true`），
 * 于是下载 1.21.1 的 NeoForge 时错误地选到 1.21.11 的包。
 */
internal fun buildNeoForgePrefixes(minecraftVersion: String): List<String> {
    val mc = minecraftVersion.trim().removePrefix("v").removePrefix("V")
    // NeoForge 侧编号：MC 1.x.y → x.y；MC 26.x → 26.x
    val nf = if (mc.startsWith("1.")) mc.removePrefix("1.") else mc
    return listOf("$nf.")
}

/**
 * 判断某个 NeoForge 版本号是否属于给定 MC 版本（严格点号段边界）。
 *
 * 两条规则，缺一不可：
 * 1. 段数相同 → 必须完全相等：`21.1 == 21.1` 命中，`21.11` / `21.1.4` 不命中 MC `1.21.1`；
 * 2. 段数更多 → 必须以 `nf + "."` 开头：`21.1.9` 命中 MC `1.21.1`，
 *    而 `21.11.45` 不以 `21.1.` 开头，故不命中。
 */
internal fun neoForgeVersionMatches(mcVersion: String, neoForgeVersion: String): Boolean {
    val mc = mcVersion.trim().removePrefix("v").removePrefix("V")
    val nf = if (mc.startsWith("1.")) mc.removePrefix("1.") else mc
    val clean = neoForgeVersion.split('-').first().trim()
    if (clean == nf) return true
    return clean.startsWith("$nf.")
}

internal fun selectNeoForgeVersion(minecraftVersion: String, versions: List<String>): String? {
    return versions.asSequence()
        .filter { full ->
            if (full.contains("-beta", ignoreCase = true)) return@filter false
            neoForgeVersionMatches(minecraftVersion, full)
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

    /**
     * Forge installer 的 maven 镜像（BMCLAPI，与官方仓同步）。
     *
     * Forge 的 installer 认 `--mirror <URL>` 并真正改写下载 URL（1.12.2 / 1.16.5
     * 实测也认，不只是 1.17+），镜像不可用时它自己回落官方源。国内直连
     * maven.minecraftforge.net / maven.neoforged.net 会被 Connection reset。
     *
     * ⚠ 仅对 Forge 生效。NeoForge 用的是 neoforged/LegacyInstaller，它虽然接受
     * `--mirror`，但那个参数只喂给赞助横幅（`MirrorData`），下载器根本不读 ——
     * 实测传 `http://127.0.0.1:9/` 仍照常访问 maven.neoforged.net。所以 NeoForge
     * 的调用点不传本值，改走 [preDownloadInstallerLibraries] 预下依赖库。
     */
    private val installerMavenMirror = "https://bmclapi2.bangbang93.com/maven/"

    /**
     * 单个依赖库的下载源序列（镜像在前、官方源兜底在后）。
     *
     * BMCLAPI 的 maven 映射（与官方 releases、libraries 两个仓库同步）：
     * - maven.neoforged.net/releases 下的路径 → bmclapi2.bangbang93.com/maven 下同路径
     * - libraries.minecraft.net 下的路径      → bmclapi2.bangbang93.com/maven 下同路径
     *
     * typetools、guava 这类库不在 NeoForged 自家仓库而在 Maven Central，
     * BMCLAPI 偶发限速/CDN 抖动时会拉不到（2026-11-20 真机 70 库挂 2 个），
     * 所以再加一道阿里云 public 仓（Maven Central 全量镜像，国内稳定）：
     * 没有该构件时返回 404，会顺位跳到下一个源，不会误命中。
     */
    private fun neoforgeLibraryMirrors(url: String): List<String> = buildList {
        if (url.startsWith("https://maven.neoforged.net/releases/")) {
            val path = url.removePrefix("https://maven.neoforged.net/releases/")
            add("https://bmclapi2.bangbang93.com/maven/$path")
            add("https://maven.aliyun.com/repository/public/$path")
        }
        if (url.startsWith("https://libraries.minecraft.net/")) {
            val path = url.removePrefix("https://libraries.minecraft.net/")
            add("https://bmclapi2.bangbang93.com/maven/$path")
        }
        add(url)
    }

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
        javaVendor: JavaVendor = JavaVendor.Termux,
        mirrorUrl: String? = null
    ): Int {
        val prootGuest = isProotGuestRuntime(javaVendor, javaVersion)
        // Installer runs before start(), so repair the selected JDK wrapper here as well.
        // 来宾链路没有宿主侧的 java 包装器可修，跳过（容器内的 JDK 由 apt 管）。
        // FCL 来源整包自带运行时，autoRepairRuntime 内部直接返回，不会碰 apt。
        // 只在循环外跑一次：这是自愈动作，重试第二轮再跑纯属浪费几十秒。
        if (!prootGuest) {
            termux.autoRepairRuntime(javaVersion, needsFonts = false, vendor = javaVendor)
        }
        // 先按 jar 本体探测参数能力，避免给老 installer 传它不认识的 --mirror：
        // Forge 1.7.10 会直接 UnrecognizedOptionException 抛栈退出（白跑一轮 + 吓人）。
        // 注意：只有 Forge（2.0 installer）的下载器真的读 --mirror；NeoForge 的
        // LegacyInstaller 只把它当赞助横幅，所以 NeoForge 调用点根本不传 mirrorUrl
        // （靠 preDownloadInstallerLibraries 预下依赖库），别在这里给它补。
        val probe = mirrorUrl?.let { InstallerCapabilities.probe(File(jarPath)) }
        val canMirror = probe?.supported == true
        probe?.readFailure?.let {
            termux.emitLog("[install] installer 包无法解析（$it），先按支持 --mirror 处理；若安装失败请重新下载核心")
        }
        if (mirrorUrl != null && probe != null && !probe.supported) {
            termux.emitLog("[install] ${File(jarPath).name} 未声明 --mirror 参数，直接使用官方源")
        }
        // 执行序列 M → N → M → M：只在**第 1 次失败后**摘掉镜像做一次能力探测，
        // 之后恒用镜像。不能单调降级（国内「带镜像失败」多半是 CDN 抖动，剩余重试
        // 全打在不可达的官方源上等于白烧配额），也不能反复探测（最后一发要留在镜像上）。
        var useMirror = canMirror
        var probedWithoutMirror = false
        val maxAttempts = if (canMirror) 4 else 3
        // 信任库属性整个方法只算一次：首次可能 fork 一个 JVM 做 keytool 校验（按 mtime 缓存）。
        var termuxProps: Array<String>? = null
        fun props(): Array<String> = termuxProps ?: termux.javaTrustStoreProps(javaVersion).also {
            termuxProps = it
        }
        var lastCode = 1
        for (attempt in 1..maxAttempts) {
            val effectiveMirror = if (useMirror) mirrorUrl else null
            // jopt 的 --mirror <URL>；镜像失效时 installer 自己会回落官方源。
            val mirrorArgs = effectiveMirror?.let { arrayOf("--mirror", it) } ?: emptyArray<String>()
            lastCode = when {
                prootGuest ->
                    termux.runProotInstaller(
                        jarPath,
                        serverDir,
                        profile = termux.resolveProotProfile(javaVendor, javaVersion),
                        mirrorUrl = effectiveMirror
                    )
                // FCL 的 java 不在 Termux PATH 里（在 filesDir/jvm-fcl），execOnce("java")
                // 只会 127；必须走带 Fcl 专用环境（LD_LIBRARY_PATH 不含 Termux 目录）的命令。
                // 信任库只在 FCL 自带 cacerts 不可读时才注入并集库：并集库可能缺新根证书
                // （ISRG/USERTrust 之类），拿它顶替 FCL 的完整 cacerts 反而更差。
                javaVendor == JavaVendor.Fcl -> {
                    val injected = if (termux.fclHasUsableCacerts(javaVersion)) {
                        emptyArray()
                    } else {
                        props().also {
                            if (attempt == 1 && it.isNotEmpty()) {
                                termux.emitLog("[install] Pojav JRE 自带证书库不可用，已注入并集信任库")
                            }
                        }
                    }
                    termux.runFclJavaCommand(
                        javaVersion, serverDir,
                        *injected,
                        "-jar", jarPath, "--installServer", serverDir.absolutePath, *mirrorArgs
                    )
                }
                else ->
                    // 显式喂一份「并集」信任库：installer 下载 libraries 走 HTTPS，
                    // JDK 自带 cacerts 缺失/残缺时它会全部握手失败（PKIX / trustAnchors）。
                    // 属性能给就给，给不了（库不够好）就退回默认行为。
                    termux.execOnce(
                        "java", "-Djava.io.tmpdir=${tempDir.absolutePath}",
                        *props(),
                        "-jar", jarPath, "--installServer", serverDir.absolutePath, *mirrorArgs
                    )
            }
            if (lastCode == 0) return 0
            if (attempt == 1 && canMirror) {
                // 第一次失败：可能是参数不被识别，也可能是网络抖动 —— 摘一次镜像来区分
                useMirror = false
                probedWithoutMirror = true
                termux.emitLog("[install] $label 带镜像安装失败（exit=$lastCode），去掉镜像验证是否是参数问题...")
            } else if (probedWithoutMirror) {
                // 探测轮也失败 → 不是参数问题，恢复镜像继续试
                probedWithoutMirror = false
                useMirror = canMirror
            }
            if (attempt < maxAttempts) {
                termux.emitLog("[install] $label 安装失败，准备第 ${attempt + 1}/$maxAttempts 次尝试...")
                delay(attempt * 2_000L)
            }
        }
        return lastCode
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析 installer jar 里的依赖清单；解析失败只记日志，不阻断安装。 */
    private fun installerLibraries(
        installerJar: File
    ): Pair<List<InstallerLibraryManifest.Entry>, String?> = try {
        InstallerLibraryManifest.libraries(installerJar) to null
    } catch (e: Exception) {
        // 异常类型要打出来：ZipException = jar 损坏（该重新下载），
        // 其它（JsonDecodingException 等）= 清单格式变了，两种故障方向不同。
        val detail = "${e.javaClass.simpleName}: ${e.message}"
        termux.emitLog("[install] 读取 installer 依赖清单失败（$detail），跳过依赖库预下载")
        emptyList<InstallerLibraryManifest.Entry>() to detail
    }

    /**
     * 预下载结果。[manifestError] 非空 = installer 包本身读不动（损坏/半下载），
     * 这是与「镜像拉不到库」完全不同的故障方向，必须带回调用方写进最终异常消息里 ——
     * 否则用户只看到 `exit=1`，不知道该重下核心还是该换网络。
     */
    private data class PreDownloadReport(
        val failed: Int = 0,
        val total: Int = 0,
        val manifestError: String? = null
    )

    /**
     * NeoForge 专用：跑 installer 前，把依赖库逐个预下载到 serverDir/libraries/。
     *
     * 为什么不能用 --mirror 解决：NeoForge 的 installer 是 neoforged/LegacyInstaller
     * （与 MinecraftForge installer 不同源）。它虽解析 --mirror，但 Mirror 只被拿来显示
     * 「赞助横幅」，**不重写任何下载 URL** —— 库照样从 maven.neoforged.net 拉，国内直接
     * Connection reset/timeout（2026-11-20 的用户日志即此）。而它的 Downloader 对
     * 「已存在且 sha1 匹配」的文件直接跳过网络，所以预下载是正解：下好之后 installer
     * 顺带替我们复检一遍校验和。
     *
     * 不抛异常：单库下载失败时 installer 仍会自己重试（官方源兜底）。
     * 安装重跑（auto-repair 路径）同样受益：已下好的库 sha1 匹配即跳过。
     */
    private suspend fun preDownloadInstallerLibraries(
        installerJar: File,
        serverDir: File,
        mirrorsFor: (String) -> List<String>
    ): PreDownloadReport {
        val (libraries, manifestError) = installerLibraries(installerJar)
        if (libraries.isEmpty()) {
            return PreDownloadReport(total = 0, manifestError = manifestError)
        }
        termux.emitLog("[install] 依赖库清单 ${libraries.size} 个（install_profile + version json 合并）")
        // 规范化根目录：库路径来自远端 jar 内的 JSON，必须挡住 ../ 穿越写到 libraries/ 之外
        val libsRoot = File(serverDir, "libraries").canonicalFile
        var ok = 0; var already = 0; var failed = 0
        for ((index, lib) in libraries.withIndex()) {
            currentCoroutineContext().ensureActive() // 用户点「取消安装」必须能立刻停下
            // 规范化后必须仍在 libraries/ 下：path 来自远端 jar 内的 JSON，挡住 ../ 穿越
            val target = InstallerLibraryManifest.resolveTarget(libsRoot, lib.path)
            if (target == null) {
                termux.emitLog("[install] 跳过越界的库路径：${lib.path}")
                continue
            }
            if (target.exists() && lib.sha1 != null && sha1Of(target) == lib.sha1) { already++; continue }
            var done = false
            // 两轮重试：BMCLAPI 的短时限速/CDN 抖动是真机实测过的失败模式
            // （70 个库挂 2 个），单个库多试一轮就能救回来。
            for (round in 0 until 2) {
                for (candidate in mirrorsFor(lib.url)) {
                    currentCoroutineContext().ensureActive()
                    if (!downloadSmallFile(candidate, target)) continue
                    if (lib.sha1 == null || sha1Of(target) == lib.sha1) { done = true; break }
                    target.delete() // 校验不过：换下一个源
                }
                if (done) break
                if (round == 0) delay(3_000)
            }
            if (done) ok++ else {
                failed++
                termux.emitLog("[install] 依赖库下载失败：${lib.path}")
            }
            if ((index + 1) % 10 == 0 || index + 1 == libraries.size) {
                termux.emitLog("[install] 依赖库预下载进度：${index + 1}/${libraries.size}")
            }
        }
        termux.emitLog(
            "[install] 依赖库预下载：新下 $ok、已存在 $already" +
                (if (failed > 0) "、失败 $failed（installer 会用官方源重试）" else "，全部就绪")
        )
        return PreDownloadReport(failed, libraries.size, manifestError)
    }

    private fun sha1Of(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** 小文件整档下载（库 jar 几百 KB～几 MB）：失败删临时文件返回 false，不抛异常。 */
    private fun downloadSmallFile(urlStr: String, target: File): Boolean {
        val tmp = File(target.parentFile, target.name + ".part")
        return try {
            target.parentFile?.mkdirs()
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "Minexa/1.0")
            conn.setRequestProperty("Accept-Encoding", "identity")
            try {
                if (conn.responseCode != 200) return false
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            } finally {
                conn.disconnect()
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

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

    /**
     * 崩溃自动重启的起点时刻（= 本次异常退出时刻），0 表示当前不是自动重启。
     *
     * 用来区分「停止请求发生在本轮崩溃之前」（历史标记，可以清）与
     * 「停止请求发生在自动重启的准备期间」（必须中止本次重启）。
     * 准备期里 `launchMc` 要跑 Java 自动切换、autoRepairRuntime、字体安装、
     * 旧版 Forge 重部署，可达数十秒到数分钟 —— 这段时间用户点停止，
     * 旧实现会把它当成历史标记清掉，然后把服务端照常拉起来。
     */
    @Volatile
    private var autoRestartSinceMs = 0L

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
            if (!termux.prepareAptPackages("fontconfig", "ttf-dejavu")) {
                return@withContext false
            }
            val steps = InstallStep.values().filter { it != InstallStep.Jdk }
            steps.forEachIndexed { idx, step ->
                repo.markStep(step, StepStatus.Active, idx * (100 / steps.size))
                val code = when (step) {
                    InstallStep.Jdk -> 0
                    // 名字虽叫「字体运行库」，装的确实是字体而不是下载工具：Forge/NeoForge
                    // 启动时 AWT 要 fontconfig 才能初始化。wget 不再捎带安装（应用内下载
                    // 全走 MultiThreadDownloader，wget 运行时零调用）。
                    InstallStep.Fonts -> termux.execOnce(
                        "apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y",
                        "fontconfig", "ttf-dejavu"
                    )
                    InstallStep.Proot -> if (termux.prepareAptPackages("proot")) termux.execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", "proot") else 1
                }
                if (code == 0) {
                    termux.repairInstalledCommands()
                    if (step == InstallStep.Fonts) {
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
        // 版本为空只可能来自「刚切过核心、还没重新选版本」。这里兜底拒绝：
        // 放行的话 resolveDownloadUrls 会拼出一个 404，报错位置离真正的原因很远。
        if (normalizedConfig.mcVersion.isBlank()) {
            throw RuntimeException("未选择版本号，请先在下载页选择要下载的版本")
        }
        // BDS 是唯一「下载的是 zip 而不是单个 jar」的核心：官方包里有主程序 + 一批
        // 动态库 + 默认配置，必须整体解压，不能塞进 server.jar 路径当 JAR 处理。
        if (normalizedConfig.selectedCore == ServerCore.Bds) {
            return@withContext downloadBds(customName, normalizedConfig.mcVersion, onProgress)
        }
        // 统一存储目录必须先可写：没有文件权限时 mkdirs 会静默失败，
        // 届时错误会推迟到几十秒后以「下载完成但找不到文件」的形式出现。
        ServerStorage.requireInternalWritable(repo.context)
        if (normalizedConfig.selectedCore.needsInstaller &&
            !termux.isJavaInstalled(normalizedConfig.selectedJavaVersion, normalizedConfig.selectedJavaVendor)
        ) {
            throw RuntimeException("${normalizedConfig.selectedJavaVersion.displayName} is not installed. Install and verify it before running ${normalizedConfig.selectedCore.displayName} installer.")
        }
        // 目录名统一自动生成（server-日期-核心-随机串），[customName] 只做显示名。
        // 去重仍然必要：随机串撞车或同一秒内并发创建时，追加 _2、_3 直到找到空目录。
        val dirName = uniqueDirNameIn(autoDirName(normalizedConfig.selectedCore))
        termux.emitLog("[install] 目录名：$dirName（显示名：$customName）")
        val jarPath = termux.serverJarFileFor(dirName).absolutePath
        downloadCoreTo(jarPath, normalizedConfig, dirName, onProgress)
        // 新核心目录下创建 eula.txt 和 plugins/ 目录。
        // 这里显式建目录（serverDirFor 已改成纯解析，不再顺手 mkdirs）。
        val serverDir = termux.ensureServerDir(dirName)
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
                        normalizedConfig.selectedJavaVersion, normalizedConfig.selectedJavaVendor,
                        installerMavenMirror
                    )
                    if (code != 0) {
                        // 半成品目录必须清掉：留着它，下次回前台 scanUnregisteredServers
                        // 会因「目录里有文件」把它登记成 Forge 核心，用户看到一台
                        // 点开就报「启动文件缺失」的幽灵服务器。BDS 路径一直这么做。
                        FileOps.deleteRecursively(serverDir)
                        throw RuntimeException("Forge installer 执行失败 (exit=$code)")
                    }
                }
                ServerCore.NeoForge -> {
                    // LegacyInstaller 不吃 --mirror（见 preDownloadInstallerLibraries 注释），
                    // 先把依赖库经 BMCLAPI 预下到位，installer 再离线校验。
                    val report = preDownloadInstallerLibraries(
                        File(jarPath), serverDir, ::neoforgeLibraryMirrors
                    )
                    if (report.failed > 0) {
                        termux.emitLog("[install] 有 ${report.failed} 个依赖库未预下载成功，installer 将尝试官方源")
                    }
                    val code = runInstallerWithRetry(
                        jarPath, serverDir, normalizedConfig.selectedCore.displayName, installerTempDir,
                        normalizedConfig.selectedJavaVersion, normalizedConfig.selectedJavaVendor
                    )
                    if (code != 0) {
                        FileOps.deleteRecursively(serverDir)
                        // 失败原因要写清方向：jar 损坏 → 重下核心；库拉不到 → 换网络/重试。
                        val hint = buildString {
                            append("NeoForge installer 执行失败 (exit=$code)")
                            if (report.manifestError != null) {
                                append("；installer 包无法解析（${report.manifestError}），请重新下载核心")
                            } else if (report.failed > 0) {
                                append("；${report.failed}/${report.total} 个依赖库未能预下载，多为镜像不可达，可换网络或重试")
                            }
                        }
                        throw RuntimeException(hint)
                    }
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
                    } else if (normalizedConfig.selectedJavaVendor == JavaVendor.Fcl) {
                        // FCL：java 不在 Termux PATH（filesDir/jvm-fcl），同 Forge/NeoForge
                        // 一样必须走 Fcl 专用命令；Quilt installer 参数与 Termux 路径一致。
                        termux.runFclJavaCommand(
                            normalizedConfig.selectedJavaVersion, serverDir,
                            "-jar", jarPath, "install", "server", normalizedConfig.mcVersion,
                            "--install-dir=${serverDir.absolutePath}", "--download-server"
                        )
                    } else {
                        termux.execOnce(
                            "java", *termux.javaTrustStoreProps(normalizedConfig.selectedJavaVersion),
                            "-jar", jarPath, "install", "server", normalizedConfig.mcVersion,
                            "--install-dir=${serverDir.absolutePath}", "--download-server"
                        )
                    }
                    if (code != 0) {
                        FileOps.deleteRecursively(serverDir)
                        throw RuntimeException("Quilt installer 执行失败 (exit=$code)")
                    }
                }
                else -> {}
            }
            // 产物复检失败同样是「装了一半」：不清理就会留下幽灵服务器
            runCatching { requireInstallerLaunchArtifacts(normalizedConfig.selectedCore, serverDir) }
                .onFailure { e ->
                    FileOps.deleteRecursively(serverDir)
                    throw e
                }
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
        registerDownloadedCore(
            customName = customName,
            core = normalizedConfig.selectedCore,
            version = normalizedConfig.mcVersion,
            dirName = dirName,
            serverFile = serverFileName
        )
        dirName
    }

    /**
     * 把一个新下载的核心登记进配置（原子读改写，绝不用调用方快照覆盖）。
     *
     * 抽出成函数是因为 BDS 走另一条下载/解压链路，登记语义必须完全一致：
     * 新核心一律落在 [StorageLocation.Internal]（统一存储目录）。
     */
    private fun registerDownloadedCore(
        customName: String,
        core: ServerCore,
        version: String,
        dirName: String,
        serverFile: String?
    ) {
        val newCore = InstalledCore(
            name = customName,
            core = core,
            version = version,
            dirName = dirName,
            serverFile = serverFile,
            location = StorageLocation.Internal
        )
        repo.updateAndSaveConfig { current ->
            current.copy(
                installedCores = current.installedCores.filter { it.dirName != dirName } + newCore,
                activeCoreName = customName
            )
        }
    }

    /**
     * 下载并安装官方 BDS（**支持指定任意历史版本**）。
     *
     * 与其它核心的差别（都是官方包的客观事实，不是实现选择）：
     *  - 下载物是 zip（含 bedrock_server + 动态库 + 默认配置），解压而不是改名；
     *  - 版本号来自 [com.mineserve.mobile.data.BdsVersionCatalogLoader]（官方接口 +
     *    内置历史清单 + 带摘要的远端清单），而不是只取「最新一条直链」；
     *  - 解压前先按包的真实结构生成安装计划（[BdsCompatibility]）：老版本带 `.so`、
     *    主程序小得多，新版本是静态单文件；
     *  - 下载完必须通过完整性校验才解压，失败时把这个服务器目录整个删掉
     *    （新装场景没有用户数据可保），绝不留半成品目录。
     */
    private suspend fun downloadBds(
        customName: String,
        version: String,
        onProgress: (Long, Long, Long) -> Unit
    ): String {
        ServerStorage.requireInternalWritable(repo.context)
        val requested = version.trim().ifBlank {
            throw RuntimeException("未选择 BDS 版本，请先在版本列表里选一个历史版本")
        }
        termux.emitLog("[bds] 正在解析 $requested 的下载信息…")
        val entry = BdsVersionCatalogLoader.resolve(repo.context, requested)
            .getOrElse { e ->
                throw RuntimeException("无法解析 BDS $requested：${e.message ?: "版本清单不可用"}", e)
            }
        termux.emitLog(
            "[bds] 目标版本 ${entry.version}" +
                "（${entry.channel.downloadType}，" +
                (if (entry.hasDigest) "清单提供 SHA-256" else "无清单摘要，将做结构校验") + "）"
        )
        // 与 Java 核心同一套命名规则，核心类型位固定为 bds。
        val dirName = uniqueDirNameIn(autoDirName(ServerCore.Bds))
        termux.emitLog("[bds] 目录名：$dirName（显示名：$customName）")
        val serverDir = termux.serverDirFor(dirName)
        val safeVersion = BdsPackageDownload.safeName(entry.version)
        val zip = File(termux.installer.tmpDir, "bds-$safeVersion.zip")
        zip.delete()
        try {
            val prepared = BdsPackageDownload.download(
                entry = entry,
                dest = zip,
                digests = BdsDigestStore(repo.context),
                onProgress = { downloaded, total, speed -> onProgress(downloaded, total, speed) },
                onAttempt = { attempt ->
                    termux.emitLog("[bds] 下载源 ${attempt.label}：" + if (attempt.ok) "成功" else (attempt.reason ?: "失败"))
                }
            )
            termux.emitLog(
                "[bds] 下载完成 ${prepared.report.bytes / (1024 * 1024)}MB，" +
                    "校验 ${prepared.levelLabel} 通过（sha256=${prepared.report.sha256.take(16)}…）"
            )

            val plan = BdsCompatibility.plan(zip, entry.version)
            plan.notes.forEach { termux.emitLog("[bds] $it") }
            val entries = BdsSupport.extractZip(zip, serverDir, plan)
            if (!plan.hasProperties) {
                File(serverDir, BdsSupport.PROPERTIES_NAME)
                    .writeText(BdsCompatibility.propertiesTemplate(port = 19132))
                termux.emitLog("[bds] 安装包未自带 ${BdsSupport.PROPERTIES_NAME}，已生成官方默认配置")
            }
            File(serverDir, "bds-version.txt").writeText(entry.version + "\n")
            termux.emitLog("[bds] 已解压 $entries 个文件到 ${serverDir.absolutePath}")
            BdsCompatibility.verifyInstalled(serverDir, plan, android.os.Build.SUPPORTED_ABIS.toList())
                ?.let { throw RuntimeException("安装包解压后不可用：$it") }
            val check = BdsSupport.checkLaunch(serverDir, android.os.Build.SUPPORTED_ABIS.toList())
            termux.emitLog(
                when {
                    !check.ok -> "[bds] 平台限制：${check.reason}"
                    check.needsBox64 -> "[bds] 官方发行版为 x86_64，首次启动时会自动安装 box64 转译环境"
                    else -> "[bds] 平台检查通过（原生执行）"
                }
            )
        } catch (e: Throwable) {
            // 新装失败必须把半成品目录清掉：否则列表里会出现一个「有目录、没核心」
            // 的幽灵服务器，启动时才报错，用户还得手动去文件管理里删。
            runCatching { FileOps.deleteRecursively(serverDir) }
            throw e
        } finally {
            zip.delete()
        }
        registerDownloadedCore(
            customName = customName,
            core = ServerCore.Bds,
            version = entry.version,
            dirName = dirName,
            serverFile = BdsSupport.ENTRY_NAME
        )
        return dirName
    }

    // ── BDS 在线更新 ────────────────────────────────────────────────
    //
    // 更新逻辑单独放在 BdsUpdater 里（备份 → 下载 → 暂存校验 → 安全替换 → 回滚），
    // 这里只做「当前活跃服务器」相关的入口与版本读取，避免把 700 行的安装细节
    // 塞进本来就巨大的 Controller。

    private val bdsUpdater by lazy { BdsUpdater(termux, repo) }

    /** 服务器目录里实际记录的 BDS 版本（`bds-version.txt`），用户自查的包可能为 null */
    fun bdsInstalledVersion(dirName: String): String? =
        bdsUpdater.installedVersion(termux.serverDirFor(dirName))

    /**
     * 把指定服务器的 BDS 核心换成 [target]（升级、降级、同版本重装都走这条）。
     *
     * 前置条件（由 [BdsUpdater] 校验并抛 [BdsUpdater.UpdateFailed]）：
     * 服务器已停止、目录是 BDS 布局、备份成功、安装包通过完整性校验。
     */
    suspend fun updateBdsCore(
        dirName: String,
        currentVersion: String?,
        target: com.mineserve.mobile.data.BdsVersionEntry,
        onEvent: (BdsUpdater.Event) -> Unit = {}
    ): BdsUpdater.Result = bdsUpdater.update(
        dirName = dirName,
        currentVersion = currentVersion,
        target = target,
        digests = BdsDigestStore(repo.context),
        onEvent = onEvent
    )

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
        // 判重必须跨两个根目录：`dirName` 是全应用定位服务器的键，
        // 若新旧目录各有一个同名目录，列表/备份/文件管理会指向模糊对象。
        taken += ServerStorage.takenDirNames(repo.context, taken)
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
            // BDS 走 downloadBds（zip + 解压），这里的解析只在兜底路径上用
            ServerCore.Bds -> resolveBdsUrl()
            ServerCore.Unknown -> throw IllegalArgumentException("未知核心类型，无法解析下载地址")
        }
    }

    /**
     * 官方 BDS 当前 Linux 构建的直链。
     *
     * 只作为兜底：正常下载走 [downloadBds]，版本由用户在历史版本列表里选定。
     * 这里保留是因为 `resolveDownloadUrls` 是所有核心的统一入口，
     * 未来若有「只要最新版直链」的调用方（例如 MCP 工具）仍能用。
     */
    private fun resolveBdsUrl(): String {
        val build = BdsSupport.parseLatestLinux(fetchText(BdsSupport.DOWNLOAD_LINKS_API))
            ?: throw IllegalStateException("无法解析官方 BDS 下载索引")
        return build.url
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
            // NeoForge 官方 maven（maven.neoforged.net）在国内常被 DNS 污染而不可达，
            // 用 BMCLAPI 的 maven 镜像兜底同一份 installer jar。
            ServerCore.NeoForge -> {
                val ver = official.substringAfter("/neoforge/").substringBefore("/")
                listOf("https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/$ver/neoforge-$ver-installer.jar")
            }
            // PocketMine（.phar）与 Allay（.jar）的下载地址都落在 GitHub Release 资产上，
            // 套一层反代提高可达性。
            ServerCore.PocketMine, ServerCore.Allay -> githubMirrors(official)
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
                githubMirrors(official)
            }
        } else (mirrors + official).distinct()
    }

    /**
     * 给 GitHub 直链套上**实测可用**的反代前缀，直连放最后兜底。
     *
     * 只保留 4 个 2026-10 实测可用的源：`ghfast.top`、`gh-proxy.com`、`cdn.gh-proxy.com`、
     * `ghproxy.net`。旧的 `mirror.ghproxy.com` / `github.moeyy.xyz` 已失效，套上去只会白跑。
     */
    private fun githubMirrors(url: String): List<String> = listOf(
        "https://ghfast.top/$url",
        "https://gh-proxy.com/$url",
        "https://cdn.gh-proxy.com/$url",
        "https://ghproxy.net/$url",
        url
    ).distinct()

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
        val xml = fetchNeoForgeMavenMetadata()
        val versions = Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1] }.toList()
        // 不再回退到 <release>：当所选 MC 版本没有对应 NeoForge 构建时，
        // 旧逻辑会静默下载到完全不相干的「最新版」（如 MC 1.20.1 → NeoForge 26.2.0.86），
        // 用户拿到的是一个装不上的 jar。现在直接报错，让用户看到真实原因。
        val ver = selectNeoForgeVersion(version, versions)
            ?: throw RuntimeException("NeoForge: 没有适配 MC $version 的构建，请改用其他 MC 版本")
        return "https://maven.neoforged.net/releases/net/neoforged/neoforge/$ver/neoforge-$ver-installer.jar"
    }

    /**
     * 读取 NeoForge 的 maven-metadata.xml。
     *
     * 官方 `maven.neoforged.net` 在部分国内网络下会被 DNS 污染而完全不可达（实测三次连接
     * 全部 20s 超时、零字节），所以 **BMCLAPI 镜像优先**，官方源退成兜底 ——
     * 反过来（官方优先）会让每次加载版本列表都先白等一个 15s 连接超时。
     */
    private fun fetchNeoForgeMavenMetadata(): String {
        val candidates = listOf(
            "https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/maven-metadata.xml",
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
        )
        var last: Exception? = null
        for (url in candidates) {
            try {
                return fetchText(url)
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "NeoForge 元数据源失败，尝试下一个: $url (${e.message})")
            }
        }
        throw last ?: RuntimeException("NeoForge: maven 元数据不可用")
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

    /**
     * 启动前以**配置文件**为准重读端口（端口的唯一真源）。
     *
     * 覆盖所有启动入口：界面按钮、MCP `start_server`、定时任务、桌面组件、崩溃自动重启。
     * 配置文件不存在（服务端一次都没启动过）或端口非法时原样返回 —— 此时由核心首次
     * 生成配置时的默认端口兜底，不能把 [McConfig.localPort] 当猜测值写进去。
     */
    private fun adoptConfigPort(config: McConfig, core: InstalledCore): McConfig {
        val fromFile = try {
            val raw = if (core.core == ServerCore.PowerNukkitX) {
                PowerNukkitXConfigManager(termux).read(core.dirName)["server-port"]
            } else {
                ServerPropertiesManager(termux).readProperties(core.dirName)["server-port"]
            }
            raw?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
        } catch (_: Exception) {
            null
        } ?: return config
        if (fromFile == config.localPort) return config
        termux.emitLog(
            "[startMc] 配置文件端口 $fromFile 与本地记录 ${config.localPort} 不一致，本次按配置文件启动"
        )
        return config.copy(localPort = fromFile)
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
            conn.setRequestProperty("User-Agent", "Minexa/1.0 (Android)")
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

    /**
     * BDS 的可选版本列表（全部历史版本）。
     *
     * 单独一个函数是因为它有两个额外消费者：下载页的版本抽屉、「服务器管理 → BDS
     * 核心版本」里的历史版本弹窗。两者都要「版本 + 是否带摘要」，而不是只要一个字符串。
     */
    suspend fun fetchBdsVersionOptions(): List<CoreVersionOption> = withContext(Dispatchers.IO) {
        val catalog = BdsVersionCatalogLoader.load(repo.context).getOrElse { e ->
            throw RuntimeException("无法获取 BDS 版本清单：${e.message ?: "网络不可用"}", e)
        }
        catalog.entries
            .filter { it.installable }
            .map { entry ->
                CoreVersionOption(
                    version = entry.version,
                    // 预览版在下拉里必须能区分出来：版本号长得很像，装错了会连不上正式服
                    supportedGameVersion = if (entry.channel == com.mineserve.mobile.data.BdsChannel.Preview) "Preview" else null,
                    assetUrl = entry.url,
                    assetDigest = entry.sha256
                )
            }
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
            // BDS：列出**全部可选版本**（内置历史存档清单 + 官方接口 + 带摘要的远端清单），
            // 用户在下拉里选哪个就下哪个。之前这里只返回「官方当前最新」一项，
            // 想装旧版本只能自己找包、自己解压。
            ServerCore.Bds -> fetchBdsVersionOptions()
            ServerCore.Unknown -> emptyList()
        }
    }

    private fun fetchPurpurVersions(): List<String> {
        // Purpur API：{versions: [...]}
        val resp = fetchJson("https://api.purpurmc.org/v2/purpur")
        return resp["versions"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.filter { !it.contains("rc") && !it.contains("pre") }
            // 必须语义排序：纯字符串 sortedDescending 会把 1.21.4 排在 1.21.10 前面，
            // 下拉列表顶部因此显示的未必是真正的最新版。
            ?.sortedWith(mcVersionComparator) ?: DEFAULT_MC_VERSIONS
    }

    private fun fetchLeavesVersions(): List<String> = fetchProjectVersions("https://api.leavesmc.org/v2/projects/leaves")

    private fun fetchLeafVersions(): List<String> = fetchProjectVersions("https://api.leafmc.one/v2/projects/leaf")

    private fun fetchProjectVersions(url: String): List<String> = runCatching {
        fetchJson(url)["versions"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.filter { it.isNotBlank() }
            // 先语义排序再截断：先 take 会在 API 返回顺序有偏差时丢掉最新的版本。
            ?.sortedWith(mcVersionComparator)
            ?.take(30)
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull() ?: DEFAULT_MC_VERSIONS

    /**
     * Velocity：PaperMC v3 的 `versions` 结构与 Paper 相同，是**两层对象**
     * `{"4.0.0":["4.3.2-SNAPSHOT","4.3.1"], "3.0.0":[...]}`，不是扁平数组。
     *
     * 旧实现直接 `resp["versions"]?.jsonArray`，对 JsonObject 取 jsonArray 会抛
     * IllegalArgumentException，整条版本链断掉 —— 表现为切到 Velocity 后版本列表
     * 一直是空的并弹「版本获取失败」。这里按类型分支，两种结构都能读。
     */
    private fun fetchVelocityVersions(): List<String> {
        val resp = fetchJson("https://fill.papermc.io/v3/projects/velocity")
        val all = parseVersionGroups(resp["versions"])
        // 快照版本（4.3.2-SNAPSHOT）不是可下载的发布版，留在列表里只会选到 404
        val stable = all.filter { it.isNotBlank() && !it.contains("SNAPSHOT", ignoreCase = true) }
        return stable.sortedWith(mcVersionComparator).ifEmpty { DEFAULT_MC_VERSIONS }
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
        // 直连优先（api.github.com 通常可直连且延迟最低；raw.githubusercontent.com 在国内可能不可达）。
        // 随后是实测可用的反代：cdn.gh-proxy.com 同时能代理 api.github.com 与 raw 内容；
        // ghfast.top / gh-proxy.com / ghproxy.net 只能代理 raw/release，对 api 请求会返回 403。
        val candidates = listOf(
            url,
            "https://cdn.gh-proxy.com/$url",
            "https://ghfast.top/$url",
            "https://gh-proxy.com/$url",
            "https://ghproxy.net/$url"
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
        val xml = fetchNeoForgeMavenMetadata()
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
        // 按语义排序后再截断，保证 60 条里包含的是真正最新的版本。
        versions.sortedWith(mcVersionComparator).take(60)
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MC_VERSIONS

    private fun fetchPaperVersions(): List<String> {
        // PaperMC v3 API：返回 {versions: {major: [subversions]}}，需平铺。
        // 结构若改回扁平数组，parseVersionGroups 同样能读，不至于整条版本链断掉。
        val resp = fetchJson("https://fill.papermc.io/v3/projects/paper")
        return parseVersionGroups(resp["versions"])
            // 过滤掉 rc/pre 等非正式版
            .filter { it.isNotBlank() && !it.contains("rc") && !it.contains("pre") }
            .sortedWith(mcVersionComparator)
            .ifEmpty { DEFAULT_MC_VERSIONS }
    }

    private fun fetchVanillaVersions(): List<String> {
        val manifest = fetchJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        val versions = manifest["versions"]?.jsonArray
            ?: return DEFAULT_MC_VERSIONS
        // manifest 本身按新→旧排列，但过滤掉快照/pre/rc 后必须重新语义排序再截断，
        // 否则「只取前 30 个」会把 1.21.x 系列里的新版本切掉。
        // snapshot 也要过滤：26.x 起快照版本号是 `26.4-snapshot-6`，不带 pre/rc 字样，
        // 漏掉会让列表顶部出现快照版，选中后下载地址是 404。
        return versions.map { it.jsonObject["id"]?.jsonPrimitive?.content ?: "" }
            .filter {
                it.isNotEmpty() &&
                    !it.contains("experimental") &&
                    !it.contains("snapshot", ignoreCase = true) &&
                    !it.contains("pre") &&
                    !it.contains("rc")
            }
            .filter { it.count { c -> c == '.' } >= 1 }
            .sortedWith(mcVersionComparator)
            .take(60)
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
        // 按语义排序后再截断，保证 60 条里包含的是真正最新的版本。
        versions.sortedWith(mcVersionComparator).take(60)
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
                conn.setRequestProperty("User-Agent", "Minexa/1.0")
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
        // 圈出「启动在途」窗口：这段里进程根本还不存在，看门狗不能据此判定已停止。
        // 无论本次启动成功、抛异常还是被取消，都在 finally 里撤掉（附带 6s 缓冲期）。
        termux.markStartInFlight()
        try {
            startInternal(config, startGeneration)
        } finally {
            termux.clearStartInFlight()
        }
    }

    /** [start] 的实际流程；由外层负责本轮的代际号与「启动在途」标记。 */
    private suspend fun startInternal(config: McConfig, startGeneration: Long) {
        // BDS 抢在依赖安装之前判定：它不跑 JVM/PHP，与 Termux rootfs 无关。
        // 平台不支持时必须**立刻**给出原因（而不是先装十分钟依赖再报错）。
        val bdsCore = config.installedCores.find { it.name == config.activeCoreName }
        // 还留在旧版私有目录的核心**不可启动**：新版只认统一存储目录。
        // 这里给的是动作指引，而不是让用户对着「找不到 jar」自己猜。
        if (bdsCore?.location == StorageLocation.Legacy) {
            throw RuntimeException(
                "「${bdsCore.name}」仍在旧版私有目录里，新版不会从那里启动。" +
                    "请在下一次启动时弹出的「迁移旧版服务器」提示中把它搬到统一存储目录后再启动"
            )
        }
        if (bdsCore?.core == ServerCore.Bds) {
            startBds(config, bdsCore, startGeneration)
            return
        }
        // 先确保 Termux 环境已初始化
        if (!termux.isReady()) {
            throw RuntimeException("Termux 环境未初始化，请等待初始化完成")
        }
        // 首次安装依赖
        if (!repo.serverState.value.isInstallComplete) {
            // 手动安装正在进行时，installDependencies() 会因为互斥直接返回 false。
            // 那不是「安装失败」，只是并发被挡了一下 —— 复用失败文案会把用户
            // 引向「再装一次」，而实际只需要等它跑完。
            if (isInstalling) {
                throw RuntimeException("依赖正在安装中，请等待安装完成后再启动服务器")
            }
            val ok = installDependencies()
            if (!ok) {
                if (isInstalling) {
                    throw RuntimeException("依赖正在安装中，请等待安装完成后再启动服务器")
                }
                throw RuntimeException("依赖安装失败，请先安装依赖后再启动服务器")
            }
        } else {
            termux.fixRootfsPermissions()
        }
        // 依赖安装/修复可能耗时很久：进入下一步前先确认用户没有取消这次启动。
        // 代际号为 0 表示非用户发起（崩溃自动重启等），不做取消检查。
        if (startGeneration != 0L && termux.isStartCancelled(startGeneration)) {
            termux.emitLog("[startMc] 准备阶段已收到停止请求，本次启动已取消")
            return
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
        val (baseConfig, launchCore) = migrateToAsciiServerDir(config, activeCore)
        // 端口真源在配置文件里（Java 系 server.properties 的 server-port，
        // PowerNukkitX 是 pnx.yml 的 settings.port）。用户完全可能在 App 之外改过它，
        // 所以**每次启动**都重读一次；否则 PNX / Allay 的启动链会用 McConfig 里的旧值
        // 把配置文件里的新端口覆盖掉（改了等于没改），地址与穿透也会指向没监听的端口。
        val launchConfig = adoptConfigPort(baseConfig, launchCore)
        // 目录按核心实际位置解析：老服务器仍在旧私有目录里，不能按新根目录拼
        val serverDir = termux.serverDirFor(launchCore.dirName)
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
     * 启动官方 BDS。
     *
     * 与 Java / PHP 链路的差异，都是 BDS 自身的事实：
     *  1. **不需要 JVM**。目录里的可执行文件是本机架构时直接 exec；
     *  2. 官方发行版是 Linux x86_64 + glibc，arm64 设备上必须走
     *     「PRoot 容器（glibc）+ box64 转译」，所以这里要先确保运行环境就绪；
     *  3. 真跑不了的组合（arm32 / x86 32 位）**直接拒绝启动**并说明替代方案，
     *     绝不拉起一个必然失败的进程再让用户看退出码。
     */
    private suspend fun startBds(config: McConfig, core: InstalledCore, startGeneration: Long) {
        val serverDir = termux.serverDirFor(core.dirName)
        val check = BdsSupport.checkLaunch(serverDir, android.os.Build.SUPPORTED_ABIS.toList())
        if (!check.ok) {
            termux.emitLog("[bds] 拒绝启动：${check.reason}")
            throw RuntimeException(check.reason)
        }
        if (check.needsBox64) {
            // 转译环境**只能**在「依赖与环境管理 → BDS」里由用户手动初始化：
            // 首次要下容器 + box64（几十兆、几分钟），放在启动路径里会让用户
            // 以为服务端卡死，失败时也没有地方显示原因。
            if (!BdsEnvironmentManager.isReady(termux.installer.rootDir)) {
                termux.emitLog("[bds] BDS 运行环境未初始化，拒绝启动")
                throw RuntimeException(
                    "BDS 专属运行环境尚未初始化。官方 BDS 是 x86_64 构建，" +
                        "需要先在「依赖与环境管理 → BDS」里点「初始化环境」（只需一次），" +
                        "完成后回到概览页启动即可"
                )
            }
        }
        syncBdsProperties(serverDir, config.localPort)
        restartAttempts = 0
        if (abortIfStartCancelled(startGeneration)) return
        termux.emitLog(
            if (check.needsBox64) {
                "[startMc] 启动官方 BDS ${core.version}（box64 转译，无 JVM）"
            } else {
                "[startMc] 启动官方 BDS ${core.version}（原生进程，无 JVM）"
            }
        )
        // 转译启动慢得多：244MB 主程序要经 box64 加载，冷启动常见几十秒到几分钟。
        // 只影响「多久没就绪算启动失败」，不影响已经跑起来的服务端。
        startupDeadlineMs = System.currentTimeMillis() + if (check.needsBox64) 300_000L else 60_000L
        processStartedAtMs = System.currentTimeMillis()
        termux.startBds(
            core.dirName,
            core.version,
            createExitHandler(config, core.dirName, BdsSupport.entryFile(serverDir).absolutePath)
        )
        repo.updateServerState { markRunningIfAlive(it) }
    }

    /**
     * 启动前确保 BDS 的 `server.properties` 存在且可跑。
     *
     * 三件事，缺一不可：
     *
     * 1. **文件必须存在**。BDS 1.21+ 按「可执行文件所在目录」找配置，而运行目录
     *    （`run/<服务器目录>`）里指向它的链接只有在服务器目录里已有该文件时才会建。
     *    所以必须先把它写到用户看得见的服务器目录里，运行目录那一层才链得上；
     *    否则 BDS 会在私有目录里自己生成一份，用户在文件管理里永远找不到。
     * 2. **`server-port` 跟 App 侧的端口同步**。BDS 运行期不重读该文件，写晚了等于没写。
     * 3. **`online-mode` 是用户的选择，不替用户改写**。只在「文件里没有这一项」时按
     *    离线补一个 false（首次生成配置的默认值）；已经是 true 就原样保留，仅打印警告。
     *
     *    警告的理由：正版验证要连微软的 Minecraft 服务，国内网络经常连不上 —— 那时
     *    BDS 会在启动约 75 秒后打印 `Could not connect to Minecraft services. This is
     *    required to accept connections in online mode.` 然后自己 `Server stop requested`，
     *    用户看到的就是「刚开就停」。这个判断交给用户，App 不再静默覆盖他的设置。
     */
    private fun syncBdsProperties(serverDir: File, port: Int) {
        try {
            val file = File(serverDir, BdsSupport.PROPERTIES_NAME)
            val before = if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
            var after = before
            if (after.isBlank()) {
                after = DEFAULT_BDS_PROPERTIES.replace("{{port}}", port.toString())
                termux.emitLog("[bds] 已生成 server.properties（离线模式 online-mode=false）")
            } else {
                after = setProperty(after, "server-port", port.toString())
                when (readProperty(after, "online-mode")) {
                    null -> {
                        after = setProperty(after, "online-mode", "false")
                        termux.emitLog("[bds] server.properties 缺少 online-mode，已补为 false（离线模式）")
                    }
                    "true" -> {
                        /*
                         * 不覆盖用户的设置。在线模式连不上微软服务时 BDS 会自停，
                         * 但那是用户在网络可用时的正常选择（正版服），App 无权替他决定。
                         * 这里只把后果写在控制台，让他看到现象时能立刻对应上原因。
                         */
                        termux.emitLog(
                            "[bds] 警告：online-mode=true（正版验证）。若启动约 75 秒后出现 " +
                                "\"Could not connect to Minecraft services\" 并自行停止，" +
                                "说明当前网络到不了微软认证服务（国内常见），" +
                                "请到属性页把「在线模式」关掉改用离线模式"
                        )
                    }
                }
            }
            /*
             * 离线模式 + 白名单是 BDS 明确**禁止**的组合：
             * ```
             * ERROR] Using an allowlist without online authentication can be
             *        dangerous and is not allowed.
             * ```
             * 1.26 的内置默认 `allow-list` 是 true，只要 properties 没被读到就会踩这条
             * （现象：加载完世界立刻 `Quit correctly`，UI 一直停在"启动中"）。
             * 所以离线模式下必须显式写 false —— 用户想要白名单只能开在线模式。
             */
            if (readProperty(after, "online-mode") == "false" &&
                readProperty(after, "allow-list") != "false"
            ) {
                after = setProperty(after, "allow-list", "false")
                termux.emitLog("[bds] 已关闭 allow-list：离线模式不允许启用白名单（BDS 会拒绝启动）")
            }
            if (after != before) file.writeText(after)
            // 这一行是排障用的锚点：能确认文件真的落在公共存储、以及 BDS 拿到的键值
            termux.emitLog(
                "[bds] server.properties ${file.length()} 字节：" +
                    "online-mode=${readProperty(after, "online-mode") ?: "-"}, " +
                    "allow-list=${readProperty(after, "allow-list") ?: "-"}, " +
                    "server-port=${readProperty(after, "server-port") ?: "-"}"
            )
        } catch (e: Exception) {
            termux.emitLog("[bds] 写入 server.properties 失败（沿用配置文件既有值）：${e.message}")
        }
    }

    /** 读 `key=value` 形态的 BDS 属性（缺失返回 null） */
    private fun readProperty(text: String, key: String): String? =
        Regex("(?m)^\\s*${Regex.escape(key)}\\s*=\\s*(.*)$")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** 写入/替换一条 `key=value`，其余行原样保留 */
    private fun setProperty(text: String, key: String, value: String): String {
        val pattern = Regex("(?m)^\\s*${Regex.escape(key)}\\s*=.*$")
        return when {
            pattern.containsMatchIn(text) -> text.replace(pattern, "$key=$value")
            text.isBlank() -> "$key=$value\n"
            else -> text.trimEnd() + "\n$key=$value\n"
        }
    }

    /** BDS 首次启动的默认配置（官方默认值，仅 online-mode 改为离线、端口由调用方填充） */
    private val DEFAULT_BDS_PROPERTIES = """
        server-name=Dedicated Server
        gamemode=survival
        force-gamemode=false
        difficulty=easy
        allow-cheats=false
        max-players=10
        online-mode=false
        allow-list=false
        server-port={{port}}
        server-portv6=19133
        enable-lan-visibility=true
        view-distance=32
        tick-distance=4
        player-idle-timeout=30
        max-threads=8
        level-name=Bedrock level
        level-seed=
        default-player-permission-level=member
        texturepack-required=false
        content-log-file-enabled=false
        compression-threshold=1
        compression-algorithm=zlib
        server-authoritative-movement=server-auth
        player-movement-score-threshold=20
        player-movement-distance-threshold=0.3
        player-movement-duration-threshold-in-ms=500
        correct-player-movement=false
        server-authoritative-block-breaking=false
    """.trimIndent() + "\n"

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
            //
            // 但**准备期间**（本次退出之后）用户点的停止不能当历史标记清掉：
            // 那会让「点了停止 → 服务端照样启动」，而且下一次真实崩溃还会被
            // 判成用户主动停止而漏报。
            val restartSince = autoRestartSinceMs
            val stopAt = userStopRequestedAtMs
            if (restartSince != 0L && stopAt != 0L && stopAt >= restartSince) {
                termux.emitLog("[startMc] 准备期间已收到停止请求，取消本次自动重启")
                repo.updateServerState {
                    it.copy(isRunning = false, runningSinceMs = 0L, startupPhase = StartupPhase.Idle)
                }
                return true
            }
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
        // 判据仍是 sanitize 幂等：合规的 ASCII 目录名一个字符都不会变，
        // 因此已经迁移过的服务器不会在每次启动时又被改名。
        if (sanitizeDirName(activeCore.dirName) == activeCore.dirName) return config to activeCore

        val source = termux.serverDirFor(activeCore.dirName)
        if (!source.isDirectory) throw RuntimeException("核心目录不存在：${source.absolutePath}")
        // 老的中文目录名：按统一规则重新生成（server-日期-核心-随机串），
        // 而不是清洗成 server-<sha256前12位> 这种无信息量的名字。
        // 去重必须看**同一父目录**：历史核心可能还在旧的私有目录下，
        // 拿统一存储根去判重会漏掉同目录里的同名目标。
        val migratedDirName = uniqueDirName(autoDirName(activeCore.core), emptySet(), source.parentFile!!)
        // 迁移必须留在**同一个根目录**内：跨根改名等于把老服务器的文件搬进新目录，
        // 违反「旧服务器原地保留」的约定，且外部存储与私有目录之间不是同一文件系统。
        val target = File(source.parentFile, migratedDirName)
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
            ServerCore.Forge -> forgeLaunchArguments(
                serverDir, effectiveLaunchJava, launchVendor, installerMavenMirror
            )
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
        // 进程已经确定退出：撤掉「启动在途」保护，让看门狗/UI 能立刻反映真实状态。
        termux.clearStartInFlight()
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
         * `Done (0.251s)!` 后 2 秒被 `Stopping the server`，Minexa 报
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
                                // 标记本轮自动重启的起点：launchMc 内部准备阶段很长，
                                // 期间用户点停止必须能中止（见 abortIfStartCancelled）
                                autoRestartSinceMs = exitAtMs
                                try {
                                    launchMc(config, dirName, jarPath)
                                } finally {
                                    autoRestartSinceMs = 0L
                                }
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
    private suspend fun forgeLaunchArguments(
        serverDir: File,
        javaVersion: JavaVersion,
        javaVendor: JavaVendor = JavaVendor.Termux,
        mirrorUrl: String? = null
    ): String {
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
                javaVersion,
                javaVendor,
                mirrorUrl
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

        /**
         * 默认 MC 版本列表（当 API 获取失败时回退使用）。
         *
         * ⚠️ 这是**网络失败时的兜底**，必须跟上真实版本进度：
         * 用户报「偶尔最高只显示到 1.21.4」，正是因为旧列表最高只到 1.21.4，
         * 一旦某个 API 超时/被墙走到这条回退，列表就被截断在 1.21.4。
         * 新增正式版时同步维护这里。
         */
        private val DEFAULT_MC_VERSIONS = listOf(
            // 26.x 是 2026 年起的年份式版本号（Paper / Mojang manifest 都已切到这套），
            // 兜底列表必须跟着走，否则 API 一挂用户就只能选到 1.21.x。
            "26.3", "26.2", "26.1",
            "1.21.11", "1.21.10", "1.21.9", "1.21.8", "1.21.7", "1.21.6",
            "1.21.5", "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21",
            "1.20.6", "1.20.5", "1.20.4", "1.20.3", "1.20.2", "1.20.1", "1.20",
            "1.19.4", "1.19.3", "1.19.2", "1.19.1", "1.19",
            "1.18.2", "1.18.1", "1.18",
            "1.17.1", "1.17",
            "1.16.5", "1.16.4", "1.16.3", "1.16.2", "1.16.1", "1.16"
        )

        private const val DIR_NAME_RANDOM_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

        /**
         * 自动生成的服务器目录名：`server-<yyyyMMdd>-<核心类型>-<4位随机串>`。
         *
         * 例：`server-20261031-paper-k3f9`。
         *
         * 为什么不再拿用户的显示名去清洗：显示名常常是中文，[sanitizeDirName] 会把它
         * 整串抹成下划线、再兜底成 `server` + 12 位 sha256，得到 `server-e73e38c1f65d`
         * 这种看不出任何信息、也无从排序的名字。自动命名一开始就用 ASCII 结构，
         * 日期 + 核心类型一眼可读，随机串只负责同一天建多个服时的区分。
         *
         * 显示名仍然是用户的原文（[InstalledCore.name]），只有磁盘目录走这套规则。
         */
        fun autoDirName(core: ServerCore, dateMs: Long = System.currentTimeMillis()): String =
            autoDirNameByKind(coreSlug(core), dateMs)

        /** 只给类型位、不给 [ServerCore] 的场景（如导入：核心类型此时还未知）。 */
        fun autoDirNameByKind(kind: String, dateMs: Long = System.currentTimeMillis()): String {
            val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(dateMs))
            return "server-$date-$kind-${randomSuffix()}"
        }

        /** 核心类型在目录名里的短标识（全小写 ASCII），如 `Paper` → `paper`。 */
        fun coreSlug(core: ServerCore): String =
            core.name.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

        /**
         * 用户给的名字 → 目录名（导入、MCP 建服等「名字由外部提供」的场景）。
         *
         *  - 纯 ASCII 且清洗后仍有内容：保留原样。这类名字有信息量（`paper-1.21.4`），
         *    属于用户输入而不是自动生成。
         *  - 其余（中文 / emoji / 只有符号）：按 [autoDirName] 生成可读结构。
         *    [kind] 用于覆盖类型位，例如导入时核心类型还未确定，传 `"imported"`。
         */
        fun dirNameForDisplayName(
            displayName: String,
            core: ServerCore = ServerCore.Unknown,
            kind: String? = null
        ): String {
            val usable = displayName.isNotBlank() &&
                displayName.all { it.code in 32..126 } &&
                displayName.none { it == '/' || it == '\\' }
            val sanitized = sanitizeDirName(displayName)
            return when {
                usable && sanitized != "server" -> sanitized
                kind != null -> autoDirNameByKind(kind)
                else -> autoDirName(core)
            }
        }

        private fun randomSuffix(length: Int = 4): String =
            (1..length).map { DIR_NAME_RANDOM_CHARS.random() }.joinToString("")

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
