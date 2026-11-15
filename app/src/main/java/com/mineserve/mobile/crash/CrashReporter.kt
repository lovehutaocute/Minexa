package com.mineserve.mobile.crash

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 崩溃上报：捕获 → 落盘 → 下次启动补发 → 服务端去重。
 *
 * 为什么这么设计：
 *  - **崩溃那一刻进程马上要结束**，没时间做网络请求。所以崩溃时只做"写一个文件"这件快事，
 *    网络请求留到下次启动（由 [CrashUploadWorker] 负责）。这样没网也不丢。
 *  - **绝不吞异常**：抓到之后必须把原来系统的处理链继续走下去，
 *    否则 App 不闪退而是卡死，用户看到的是"点了没反应"，比崩溃更难排查。
 *    项目里 [com.mineserve.mobile.McApplication] 已有一个全局捕获（写 crash_log.txt），
 *    这里用"链式"接在它前面，而不是替换它。
 *  - **上传失败不影响用户**：断网、workers.dev 被墙、服务端挂了，都只是文件留着下次再发。
 *    本地积压超过 [MAX_PENDING_FILES] 条时丢最老的，防止占满存储。
 *
 * 隐私约定（改动时不要破坏）：
 *  - 只发：堆栈、App 版本、版本号、手机型号、系统版本、ABI、线程名。
 *  - 不发：服务器名、玩家 ID、世界路径、IP、任何文件内容。
 *  - 堆栈里可能出现本机绝对路径，发送前会做脱敏（见 [sanitizeStack]）。
 */
object CrashReporter {

    // ===================================================================================
    // 部署后要改的两个常量
    // ===================================================================================

    /**
     * 上报地址。
     *
     * 注意这里**不是** workers.dev 域名：实测 `minexa-crash.hvhvhhbv.workers.dev`
     * 在本地 DNS 被解析到 198.27.124.186（污染/黑洞地址），TCP 443 连不上。
     * 同一个 Worker 已绑定到自有域名 crash.xn--fjqu03a.top，实测可通（约 1 秒）。
     * 以后再遇到"上报全都没上来"，先测这个域名是否还通。
     */
    private const val ENDPOINT = "https://crash.xn--fjqu03a.top/report"

    /**
     * 与 Cloudflare 上 `wrangler secret put REPORT_KEY` 设置的值保持一致。
     *
     * 注意：这个值会随 APK 被反编译看到，这是公开密钥的固有属性，无法隐藏。
     * 影响上限是"别人能往你的库里灌垃圾数据"，拿不到你任何东西。
     * 真被灌了 → 换一个 secret、改这一行、重新发版即可。
     */
    private const val KEY = "9e031481aa4f3e073b6eccd25b9de29d"

    // ===================================================================================

    private const val PENDING_DIR = "crash/pending"
    private const val UPLOADED_DIR = "crash/uploaded"
    private const val MAX_PENDING_FILES = 50
    private const val MAX_STACK_CHARS = 16 * 1024
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val WORK_NAME = "crash_upload"

    /**
     * 安装全局异常捕获。必须在 Application.onCreate() 里尽早调用。
     *
     * 采用"链式"而不是"替换"：先把崩溃写进文件，再交回给原来的 handler
     * （通常是系统默认的，或者项目自己在 McApplication 里装的那个），
     * 保证 App 照常闪退、照常弹"应用已停止运行"。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 写文件要快，且绝不能再抛异常（否则会盖掉真正的崩溃）
            runCatching { persistSync(app, thread.name, throwable) }
            // 交回原处理链：这一步不能省，否则 App 不闪退
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 把上次没发出去的崩溃排进 WorkManager。
     *
     * 只做"排个任务"，真正的网络请求在后台线程做，所以调用者不必自己开线程。
     * 用 enqueueUniqueWork 保证重复调用不会堆一堆任务。
     */
    fun uploadPending(context: Context) {
        val app = context.applicationContext
        val request = OneTimeWorkRequestBuilder<CrashUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // 失败后退避重试：30 秒起、指数增长，最多几次。
            // 国内 workers.dev 时通时不通，退避能让"刚好没通"的那次在稍后补上。
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        runCatching {
            WorkManager.getInstance(app)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    // ===================================================================================
    // 内部：写文件
    // ===================================================================================

    /** 崩溃现场同步写盘。被 uncaught 处理器调用，必须短、必须不抛。 */
    private fun persistSync(app: Context, threadName: String, throwable: Throwable) {
        val dir = File(app.filesDir, PENDING_DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("version", appVersionName(app))
            put("versionCode", appVersionCode(app))
            put("model", Build.MODEL ?: "")
            put("android", Build.VERSION.RELEASE ?: "")
            put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
            put("thread", threadName)
            put("occurredAt", now)
            put("stack", sanitizeStack(stackToString(throwable), app))
        }
        File(dir, "$now.json").writeText(payload.toString(), Charsets.UTF_8)

        // 顺手清理：只保留最近的若干条，防止本地越堆越多
        trimPending(dir)
    }

    /** 把异常展开成纯文本堆栈（含 Caused by 链）。 */
    internal fun stackToString(throwable: Throwable): String {
        val sb = StringBuilder()
        sb.append(throwable.javaClass.name)
        throwable.message?.let { sb.append(": ").append(it) }
        sb.append('\n')
        throwable.stackTrace.take(30).forEach { sb.append("  at ").append(it.toString()).append('\n') }
        var cause = throwable.cause
        var guard = 0
        while (cause != null && guard++ < 5) {
            sb.append("Caused by: ").append(cause.javaClass.name)
            cause.message?.let { sb.append(": ").append(it) }
            sb.append('\n')
            cause.stackTrace.take(10).forEach { sb.append("  at ").append(it.toString()).append('\n') }
            cause = cause.cause
        }
        return sb.toString()
    }

    /**
     * 堆栈脱敏：把本机绝对路径换成占位符。
     *
     * 用户的服务器目录名、世界名可能出现在堆栈里（比如"打不开某个插件文件"），
     * 这些属于用户隐私，不该原样上传。替换成固定占位符不影响定位问题，
     * 因为崩溃一般发生在代码里，路径只用来看是哪个模块。
     */
    internal fun sanitizeStack(raw: String, app: Context): String {
        var s = raw
        // 1) 本 App 的私有目录 → <APP_DATA>
        s = s.replace(app.filesDir.absolutePath, "<APP_DATA>")
        s = s.replace(app.cacheDir.absolutePath, "<APP_CACHE>")
        // 2) 外部存储目录（用户自己的服务器目录通常在这里）→ <SDCARD>
        runCatching {
            app.getExternalFilesDir(null)?.absolutePath?.let { s = s.replace(it, "<APP_EXT>") }
        }
        s = s.replace("/storage/emulated/0", "<SDCARD>")
        s = s.replace("/sdcard", "<SDCARD>")
        // 3) 超长截断，避免一条记录把库撑爆
        if (s.length > MAX_STACK_CHARS) {
            s = s.take(MAX_STACK_CHARS) + "\n…(已截断)"
        }
        return s
    }

    private fun trimPending(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        if (files.size <= MAX_PENDING_FILES) return
        files.take(files.size - MAX_PENDING_FILES).forEach { runCatching { it.delete() } }
    }

    private fun appVersionName(app: Context): String {
        return runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        }.getOrDefault("")
    }

    private fun appVersionCode(app: Context): Int {
        return runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        }.getOrDefault(0)
    }

    // ===================================================================================
    // 内部：上传
    // ===================================================================================

    /** 待上传目录（供 Worker 使用） */
    internal fun pendingDir(app: Context): File = File(app.filesDir, PENDING_DIR).apply { mkdirs() }

    /** 已成功上传目录：保留原件一段时间，方便服务端挂了时人工补救 */
    private fun uploadedDir(app: Context): File = File(app.filesDir, UPLOADED_DIR).apply { mkdirs() }

    /**
     * 上传一条。返回值含义：
     *  - true  → 这条已经"处理完"（入库成功，或被服务端判为重复），可以移走
     *  - false → 网络/服务端问题，留着下次再试
     */
    internal fun uploadOne(app: Context, file: File): Boolean {
        if (ENDPOINT.isBlank() || KEY.isBlank()) {
            // 还没配置就什么都别做。通常是刚把代码加进来还没部署。
            android.util.Log.w("CrashReporter", "ENDPOINT 或 KEY 为空，跳过崩溃上报")
            return false
        }

        val body = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return true

        val conn = runCatching {
            (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Key", KEY)
            }
        }.getOrNull() ?: return false

        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            when {
                code in 200..299 -> true
                // 密钥错 / 请求体不合法：重试也不会变好，直接放弃这一条（但保留文件供排查）
                code == 400 || code == 403 -> {
                    android.util.Log.w("CrashReporter", "上报被拒绝($code)，保留文件不重试")
                    quarantine(app, file)
                    false
                }
                // 429 限流、5xx 服务端问题：留着下次
                else -> false
            }
        } catch (e: Exception) {
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun quarantine(app: Context, file: File) {
        runCatching { file.renameTo(File(uploadedDir(app), "rejected-${file.name}")) }
    }

    internal fun markUploaded(app: Context, file: File) {
        runCatching { file.renameTo(File(uploadedDir(app), file.name)) }
    }

    /** 清掉上传成功目录里太老的副本，只留 7 天 */
    internal fun cleanupUploaded(app: Context) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        uploadedDir(app).listFiles()?.forEach { f ->
            val stamp = f.name.substringBefore('-').takeWhile { it.isDigit() }.toLongOrNull()
            if (stamp != null && stamp < cutoff) runCatching { f.delete() }
        }
    }

    /** 文件名的日期，仅用于日志 */
    internal fun stampOf(file: File): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(file.lastModified()))
}

/**
 * 补发 Worker：在满足联网条件时把 files/crash/pending/ 里的记录逐条发出去。
 *
 * 单独成类是因为 WorkManager 要求 Worker 有无参构造（它自己反射创建），
 * 逻辑放在 [CrashReporter] 里是为了能单独测试。
 */
class CrashUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val dir = CrashReporter.pendingDir(app)
        val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) {
            CrashReporter.cleanupUploaded(app)
            return@withContext Result.success()
        }

        // 依次发。只要有一条因为网络失败，就停下来让 WorkManager 退避重试，
        // 不要反复撞墙浪费用户的电和流量。
        for (file in files) {
            val done = runCatching { CrashReporter.uploadOne(app, file) }.getOrDefault(false)
            if (done) {
                CrashReporter.markUploaded(app, file)
            } else {
                // 有东西没发出去，让 WorkManager 稍后重试
                return@withContext Result.retry()
            }
        }

        CrashReporter.cleanupUploaded(app)
        Result.success()
    }
}
