package com.mineserve.mobile.runtime

import android.content.Context
import android.util.Log
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.MultiThreadDownloader
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 一个可下载的 FCL JRE 包（aarch64）。
 *
 * 这些包由 EdgeCube 从 PojavLauncherTeam/android-openjdk-build-multiarch 的产物
 * 重新打包成 `.ecpkg`（本质是 ZIP：`edgecube-package.json` + `aarch64/` 目录树）。
 *
 * [urls] 按优先级排列：Gitee 在国内直连很快（实测 45MB 约 10 秒），GitHub 作为兜底。
 * 两者都是同一份文件，[sha256] 相同，所以换源重试不需要改校验值。
 */
data class FclJrePackage(
    val version: JavaVersion,
    /** 上游发布批次，只用于日志与排错（上游换批次时连同 sha256 一起更新）。 */
    val releaseTag: String,
    val sizeBytes: Long,
    val sha256: String,
    val urls: List<String>
) {
    /** 上游包文件名，形如 `jre21-aarch64.ecpkg`。 */
    val fileName: String get() = "jre${majorVersion}-aarch64.ecpkg"

    /** 主版本号数字，用于拼包名与目录名。 */
    val majorVersion: Int get() = version.name.removePrefix("Java").toInt()
}

/**
 * FCL JRE 包清单。
 *
 * ## 为什么写死在代码里，而不是运行时去拉元数据
 *
 * 上游的 `jreXX.json` 元数据托管在 `edgecubemc.com`（第三方站点）。一旦那个站点改版或
 * 下线，应用内下载就整个失效 —— 而一份仅 45MB、变更频率以「月」计的静态文件，
 * 不值得为它引入一个运行时硬依赖。这里内置的是**实测校验过**的值（size 与 sha256
 * 都对着官方元数据核过，并实际下载比对过大小）。
 *
 * 后果与出路：上游若换批次，下载会在 sha256 校验这一步明确失败（不会静默装上错的东西），
 * 此时更新这张表即可。刻意不做「校验失败就放行」的降级 —— 那等于把一个可能被篡改或
 * 半截的 JDK 装进设备并拿去跑服务端。
 */
object FclJrePackages {

    private const val TAG_RELEASE = "2026-08-07"
    private const val GITEE = "https://gitee.com/venti1112/EdgeCubePackage-JRE/releases/download"
    private const val GITHUB = "https://github.com/venti1112/EdgeCubePackage-JRE/releases/download"

    private fun urlsFor(id: String): List<String> = listOf(
        // Gitee 优先（国内直连稳定，且是上游发布的主站）；GitHub 直连及其反代兜底。
        // 反代只保留 2026-10 实测可用的三个；所有源都指向同一个文件名，SHA256 校验不变。
        "$GITEE/$TAG_RELEASE/$id-aarch64.ecpkg",
        "$GITHUB/$TAG_RELEASE/$id-aarch64.ecpkg",
        "https://ghfast.top/$GITHUB/$TAG_RELEASE/$id-aarch64.ecpkg",
        "https://gh-proxy.com/$GITHUB/$TAG_RELEASE/$id-aarch64.ecpkg",
        "https://cdn.gh-proxy.com/$GITHUB/$TAG_RELEASE/$id-aarch64.ecpkg"
    )

    val all: List<FclJrePackage> = listOf(
        FclJrePackage(
            version = JavaVersion.Java17,
            releaseTag = TAG_RELEASE,
            sizeBytes = 37_218_898L,
            sha256 = "95e03b5cc2237d21be92e8434ac64d7ae50dc02c8ba89b78040bd45e6effbe3b",
            urls = urlsFor("jre17")
        ),
        FclJrePackage(
            version = JavaVersion.Java21,
            releaseTag = TAG_RELEASE,
            sizeBytes = 45_100_821L,
            sha256 = "c7f4ad8249091f69995f444b5b330252bd3f1b7ed7ac17794b629300e0008f27",
            urls = urlsFor("jre21")
        ),
        FclJrePackage(
            version = JavaVersion.Java25,
            releaseTag = TAG_RELEASE,
            sizeBytes = 62_557_078L,
            sha256 = "ed6da3924ec56da07fd48325455a2fd54040ef6848f8687a74d49712b21fcf57",
            urls = urlsFor("jre25")
        )
    )

    /** 该版本是否有可下载的 FCL 包（Java 8/11 没有 —— 上游只发布 17/21/25）。 */
    fun forVersion(version: JavaVersion): FclJrePackage? = all.firstOrNull { it.version == version }
}

/**
 * 把 `.ecpkg` 里的条目名映射成运行时目录里的相对路径。
 *
 * 包里所有 JDK 内容都在 `aarch64/` 这一层之下（顶层还有 `edgecube-package.json` 与
 * 上游自己的 `META-INF/`，那些都不能落进运行时目录 —— `META-INF` 里带着签名清单，
 * 混进 JDK 根目录会让 `java.home` 下多出一堆无关文件）。**剥前缀这一步必须严格按
 * 目录成分判断**，不能写成 `startsWith("aarch64")`：后者会把 `aarch64-old/x` 这类
 * 兄弟目录也当成运行时内容放进去。
 *
 * @return 目标相对路径（如 `bin/java`）；不属于运行时目录时返回 null（调用方跳过）。
 */
internal fun fclRuntimeRelativePath(entryName: String): String? {
    val normalized = entryName.trimStart('/')
    if (!normalized.startsWith("$FCL_RUNTIME_ROOT/")) return null
    val rest = normalized.removePrefix("$FCL_RUNTIME_ROOT/")
    return rest.ifBlank { null }
}

/** `.ecpkg` 内 JDK 内容所在的那一层目录名。 */
internal const val FCL_RUNTIME_ROOT = "aarch64"

/**
 * 解压后需要可执行位的文件（相对运行时根目录）。
 *
 * 不必按 ZIP 的 unix mode 还原：`java.util.zip.ZipEntry` 不暴露它。这里按 JDK 自身的
 * 约定列出真正需要执行位的少数几个文件 —— 少了它们，`java` 起不来（`bin/` 里的启动器）
 * 或 JVM 起子进程时失败（`lib/jexec`、`lib/jspawnhelper`）；`.so` 不需要。
 */
private val EXECUTABLE_ENTRIES = setOf(
    "lib/jexec",
    "lib/jspawnhelper"
)

/**
 * FCL（FoldCraftLauncher 血统）Android 原生 JRE 的安装器。
 *
 * ## 装在哪儿，为什么
 *
 * 装到 `filesDir/jvm-fcl/`，与 Termux 环境的根目录 `filesDir/home/` **同级**，
 * 而不是 `home/lib/jvm/` 下面。两个原因：
 *
 *  1. `fixUsrBin` 在修复 compat 符号链接时，会把 `home/lib/jvm` 整个目录当成
 *     「可能损坏的 jvm」原子替换掉（删掉再 rename）。把 60MB 的外部 JRE 放进去，
 *     等于把它的生死交给一段与它无关的修复逻辑。
 *  2. 重装 Termux 环境（`home/` 整个重建）时，FCL JRE 应当存活 —— 它跟 Termux 的
 *     apt 生态没有任何关系，重新下载 60MB 是纯粹的浪费。
 *
 * ## 与 Termux 来源的差别（决定了启动时挂不挂兼容层）
 *
 * 这套 JRE 是 bionic 构建（`interp = /system/bin/linker64`）、只依赖系统库
 * （`libc/libz/libdl/libm`），并且它的 `libnet.so` 编译期就跳过了
 * `fopen("/proc/net/if_inet6")` 检查 —— 也就是 `server-ip=::` 天生可用，
 * 不需要 [NativeLibraryBundler.IPV6_FIX_LIB] 那层劫持。
 */
class FclJreInstaller(context: Context) {

    private val appContext = context.applicationContext

    /** 安装根目录（`filesDir/jvm-fcl`）。 */
    val rootDir: File get() = File(appContext.filesDir, DIR_NAME)

    /** 某个版本的运行时目录（`jvm-fcl/java-21`）。 */
    fun runtimeDir(version: JavaVersion): File = File(rootDir, "java-${majorVersionOf(version)}")

    /** 该版本的 `java` 可执行文件路径。 */
    fun javaBinary(version: JavaVersion): File = File(runtimeDir(version), "bin/java")

    fun isInstalled(version: JavaVersion): Boolean = javaBinary(version).let {
        it.isFile && it.canExecute()
    }

    /**
     * 下载并安装指定版本。返回是否装好（装好后 [isInstalled] 必为 true）。
     *
     * 整个过程在「临时目录 + 原子改名」下完成：解压出来的半成品不会出现在
     * [runtimeDir] 里，所以中途失败不会被 [isInstalled] 误判为已安装。
     */
    suspend fun install(
        version: JavaVersion,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> },
        onLog: ((String) -> Unit)? = null
    ): Boolean {
        val pkg = FclJrePackages.forVersion(version)
        if (pkg == null) {
            onLog?.invoke("[fcl-jre] ${version.displayName} 没有对应的 FCL 包（上游只发布 17/21/25）")
            return false
        }
        val workDir = File(rootDir, ".staging").apply { mkdirs() }
        val archive = File(workDir, pkg.fileName)
        val unpacked = File(workDir, "unpack")
        try {
            // ── 1. 下载（按 urls 顺序换源重试）──
            archive.delete()
            var downloaded = false
            var lastError: String? = null
            pkg.urls.forEachIndexed { index, url ->
                if (downloaded) return@forEachIndexed
                onLog?.invoke(
                    "[fcl-jre] 开始下载 ${pkg.fileName}（${pkg.sizeBytes / 1024 / 1024} MB）" +
                        if (index == 0) "" else "，换源重试 ${index + 1}"
                )
                runCatching {
                    MultiThreadDownloader.download(
                        url = url,
                        target = archive,
                        onProgress = { done, total, speed ->
                            // 下载阶段占总进度的前 80%
                            val expected = if (total > 0L) total else pkg.sizeBytes
                            onProgress(done * 80 / expected.coerceAtLeast(1L), 100L, speed)
                        },
                        onLog = onLog
                    )
                }.onSuccess { downloaded = true }
                    .onFailure { lastError = it.message ?: it.javaClass.simpleName }
            }
            if (!downloaded || !archive.isFile) {
                onLog?.invoke("[fcl-jre] 下载失败：${lastError ?: "所有下载源都不可用"}")
                return false
            }

            // ── 2. 校验：长度 + SHA-256 ──
            // 不做「校验不过就放行」的降级：装一个来路不明的 JDK 去跑服务端，
            // 比直接失败糟糕得多。
            if (archive.length() != pkg.sizeBytes) {
                onLog?.invoke(
                    "[fcl-jre] 包大小不符：期望 ${pkg.sizeBytes} 实际 ${archive.length()}，" +
                        "上游可能已更新该包（需同步更新内置清单）"
                )
                return false
            }
            val actual = sha256Of(archive)
            if (!actual.equals(pkg.sha256, ignoreCase = true)) {
                onLog?.invoke("[fcl-jre] 校验失败：期望 ${pkg.sha256.take(12)}… 实际 ${actual.take(12)}…")
                return false
            }
            onLog?.invoke("[fcl-jre] 校验通过（sha256 ${actual.take(12)}…），开始解压")
            onProgress(80L, 100L, 0L)

            // ── 3. 解压到临时目录（剥掉 aarch64 那一层）──
            unpacked.deleteRecursively()
            val entryTotal = unzipRuntimeTree(archive, unpacked)
            if (entryTotal <= 0) {
                onLog?.invoke("[fcl-jre] 解压失败：包内没有 $FCL_RUNTIME_ROOT/ 目录树")
                return false
            }
            if (!File(unpacked, "bin/java").isFile) {
                onLog?.invoke("[fcl-jre] 解压结果不完整：缺少 bin/java")
                return false
            }
            onProgress(95L, 100L, 0L)

            // ── 4. 原子落位 ──
            val target = runtimeDir(version)
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!unpacked.renameTo(target)) {
                onLog?.invoke("[fcl-jre] 落位失败：无法把解压结果移动到 ${target.absolutePath}")
                return false
            }
            // rename 不改权限，可执行位要显式补（否则 java 起不来）
            javaBinary(version).setExecutable(true, false)
            EXECUTABLE_ENTRIES.forEach { File(target, it).takeIf { f -> f.isFile }?.setExecutable(true, false) }
            onProgress(100L, 100L, 0L)
            onLog?.invoke("[fcl-jre] ${version.displayName} 安装完成 → ${target.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "install ${version.displayName} failed", e)
            onLog?.invoke("[fcl-jre] 安装失败：${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            // 半成品与安装包都不留：安装包 37–62MB，留在设备上毫无用处
            runCatching { archive.delete() }
            runCatching { unpacked.deleteRecursively() }
            runCatching { if (workDir.list()?.isEmpty() == true) workDir.delete() }
        }
    }

    /** 删除已安装的该版本。返回是否有文件被真正删掉。 */
    fun delete(version: JavaVersion): Boolean {
        val dir = runtimeDir(version)
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    private fun majorVersionOf(version: JavaVersion): Int =
        version.name.removePrefix("Java").toInt()

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 把包内 `aarch64/` 下的内容解到 [dest]（不含那一层目录）。返回写出的文件数。
     *
     * 只认文件与目录两种条目：包里没有符号链接（已核对过 308 个条目），因此不需要
     * 处理链接 —— 真要出现链接条目，也会被当作普通文件写出，而不是静默跳过。
     */
    private fun unzipRuntimeTree(archive: File, dest: File): Int {
        var count = 0
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val relative = fclRuntimeRelativePath(entry.name) ?: return@forEach
                val out = File(dest, relative)
                // ZIP 条目名可能带 `..`；防目录穿越（包来源虽是固定地址，但校验过后
                // 仍不该让包内容决定写到哪里）
                if (!out.canonicalPath.startsWith(dest.canonicalPath + File.separator)) return@forEach
                if (entry.isDirectory) {
                    out.mkdirs()
                    return@forEach
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output, 64 * 1024) }
                }
                count++
            }
        }
        return count
    }

    private companion object {
        const val TAG = "FclJreInstaller"

        /** 安装根目录名（与 `home` 同级）。 */
        const val DIR_NAME = "jvm-fcl"
    }
}
