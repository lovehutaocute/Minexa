package com.mineserve.mobile.server

import android.util.Log
import com.mineserve.mobile.runtime.TermuxRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃报告管理器：
 *  - captureCrash：MC 进程异常退出时收集最近日志，生成崩溃报告
 *  - listCrashReports / listNativeCrashReports：列出应用自建 / MC 原生崩溃报告
 *  - readCrashReport / deleteCrashReport / clearAllCrashReports：读取、删除、清空
 *
 * 报告目录说明：
 *  - 自建报告：home/crash-logs/crash_yyyyMMdd_HHmmss.txt（应用生成，含最近日志 + 原生报告）
 *  - MC 原生：home/servers/{dirName}/crash-reports/（Minecraft 服务端自动生成）
 */
class CrashReportManager(private val termux: TermuxRuntime) {

    private val TAG = "CrashReportManager"

    /** 崩溃报告信息 */
    data class CrashReport(
        val fileName: String,
        val path: String,
        val sizeBytes: Long,
        val sizeText: String,
        val createdTime: Long,
        val createdText: String,
        val preview: String,    // 头部 8 KB 归因预览
        /** 如果没有 `.json` sidecar，说明是旧版本报告（列表可标注「未分析」） */
        val hasInsight: Boolean = false,
        /** 结构化报告的主结论（有 sidecar 时优先用，回退到规则预览归因） */
        val primaryLabel: String = ""
    ) {
        /** crash_yyyyMMdd_HHmmss（去掉 .txt） */
        val crashId: String get() = fileName.removeSuffix(".txt")
    }

    /** 自建崩溃报告目录（home/crash-logs） */
    private val crashLogsDir: File
        get() = File(termux.installer.rootDir, "home/crash-logs").apply { mkdirs() }

    /** MC 原生崩溃报告目录（{serverDir}/crash-reports） */
    private fun nativeCrashReportsDir(dirName: String): File =
        File(termux.serverDirFor(dirName), "crash-reports")

    /** MC latest.log 路径 */
    private fun latestLogFile(dirName: String): File =
        File(termux.serverDirFor(dirName), "logs/latest.log")

    /**
     * 崩溃采集结果（会话范围）。
     *
     * [sessionLog] 就是「本次启动」的全量日志，直接喂给 `CrashInsightEngine`；
     * 报告文件与弹窗都从它取内容，**不再回读 latest.log**（除非捕获文件不可用）。
     */
    data class Capture(
        val reportPath: String?,
        val crashId: String,
        val sessionId: String?,
        val sessionLog: String,
        val totalLines: Int,
        val keyLines: Int,
        val truncated: Boolean,
        val fallbackToLatestLog: Boolean,
        val nativeReportText: String?,
        val sessionStartedAt: Long,
        val sessionEndedAt: Long
    )

    /**
     * 收集崩溃日志。
     *
     * 只使用**本次启动**的会话切片：`[LaunchSession.startOffset, 退出时冻结的 endOffset]`。
     * 上一次启动留在同一个文件里的内容被偏移天然排除；native 报告也按会话开始时间过滤。
     */
    fun captureCrashDetail(
        exitCode: Int,
        wasRunningBefore: Boolean,
        dirName: String,
        allowCleanExit: Boolean = false
    ): Capture? {
        // 只在之前在运行且非正常退出时生成报告（正常 stop 命令退出码通常是 0；
        // 启动后快速退出即使 exit=0 也按异常处理）
        if (!wasRunningBefore || (exitCode == 0 && !allowCleanExit)) {
            return null
        }

        val now = Date()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        val crashId = "crash_$ts"
        val reportFile = File(crashLogsDir, "$crashId.txt")

        return try {
            // 先冻结会话，保证之后读取的内容与「崩溃那一刻」一致（自动重启也不会漂移）。
            val session = termux.freezeLaunchSession()?.takeIf { it.dirName == dirName }
            val sessionSlice = session?.let { s ->
                SessionLogReader.read(
                    s.logFile,
                    s.startOffset,
                    s.effectiveEnd(runCatching { s.logFile.length() }.getOrDefault(0L)),
                    SESSION_LOG_MAX_BYTES
                )
            }
            var fallback = false
            var logText = sessionSlice?.text.orEmpty()
            if (logText.isBlank()) {
                // 捕获文件不可用（老版本残留 / 核心直写 stdout 被关）：回退 latest.log，
                // 但必须在报告与 UI 里明示，不能静默混入历史行。
                fallback = true
                logText = readLatestLogTailAsText(dirName)
            }
            val logLines = if (logText.isBlank()) emptyList() else logText.lines()

            val sb = StringBuilder()
            // ── 头部 ──
            sb.appendLine("====================================")
            sb.appendLine("Minexa 崩溃报告")
            sb.appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)}")
            sb.appendLine("会话: ${session?.id ?: "unknown"}")
            sb.appendLine("退出码: $exitCode")
            sb.appendLine("进程状态: 异常退出")
            if (exitCode == 137) {
                sb.appendLine("说明: exit=137 通常表示进程被 SIGKILL 终止，可能是 Android 内存压力、系统后台限制或外部强制停止；请结合下方首个 Java 异常判断根因。")
            }
            sb.appendLine("====================================")
            sb.appendLine()

            // ── Minexa 应用日志（启动链路诊断：java 路径、启动命令、缺库警告）──
            val appLogLines = termux.readAppLogTail()
            if (appLogLines.isNotEmpty()) {
                sb.appendLine("--- Minexa 应用日志 (最后 ${minOf(400, appLogLines.size)} 行，共 ${appLogLines.size} 行) ---")
                appLogLines.forEach { sb.appendLine(it) }
                sb.appendLine()
            }

            // ── 关键片段（从本次启动日志里抽取，带行号）──
            val segments = KeyLogExtractor.extract(logLines)
            if (segments.isNotEmpty()) {
                sb.appendLine("--- 关键片段 (异常栈 / Watchdog / 致命错误，共 ${segments.sumOf { it.lineCount }} 行) ---")
                segments.forEach { seg ->
                    sb.appendLine("[${reasonLabel(seg.reason)} · 第 ${seg.startLine}-${seg.endLine} 行]")
                    seg.lines.forEach { sb.appendLine(it) }
                }
                sb.appendLine()
            }

            // ── 本次启动全量日志 ──
            val scopeNote = if (fallback) "（回退 latest.log：可能含少量历史行）" else "（仅本次启动，已排除历史日志）"
            sb.appendLine("--- 本次启动日志 (共 ${logLines.size} 行) $scopeNote ---")
            if (sessionSlice?.truncated == true) {
                sb.appendLine("(说明: 日志超出 4MB 上限，仅保留尾部；完整内容见服务器目录日志文件)")
            }
            if (logLines.isEmpty()) {
                sb.appendLine("(说明: 本次启动没有产生任何输出 —— 进程很可能在写日志前就退出了；请结合上方应用日志判断)")
            } else {
                logLines.forEach { sb.appendLine(it) }
            }
            sb.appendLine()

            // ── 本次启动的 MC 原生崩溃报告（按会话开始时间过滤）──
            sb.appendLine("--- MC 原生崩溃报告 ---")
            val native = findNativeCrashOfSession(dirName, session?.startedAt ?: 0L)
            val nativeText = native?.let { runCatching { it.readText() }.getOrNull() }
            if (native != null && nativeText != null) {
                sb.appendLine("(来源: ${native.name})")
                sb.appendLine(nativeText)
            } else {
                sb.appendLine("(本次启动无 MC 原生崩溃报告)")
            }

            reportFile.writeText(sb.toString())
            // 会话日志 sidecar：崩溃当时冻结的「本次启动」日志。
            // App 重启后再打开这份报告，日志视图仍只显示那一次启动的内容。
            runCatching { sessionLogFile(crashId).writeText(logText) }
            Capture(
                reportPath = reportFile.absolutePath,
                crashId = crashId,
                sessionId = session?.id,
                sessionLog = logText,
                totalLines = logLines.size,
                keyLines = segments.sumOf { it.lineCount },
                truncated = sessionSlice?.truncated == true,
                fallbackToLatestLog = fallback,
                nativeReportText = nativeText,
                sessionStartedAt = session?.startedAt ?: now.time,
                sessionEndedAt = now.time
            )
        } catch (e: Exception) {
            // 不能静默吞掉：报告生成失败时用户看到的是「没有崩溃报告」，无从判断原因。
            // 历史上就是这里吞掉了 parseNativeFilename 抛出的 ParseException，
            // 掩盖了「有原生报告就再也不生成报告」这个 bug。
            Log.e(TAG, "生成崩溃报告失败（将返回 null，界面会显示无报告）", e)
            null
        }
    }

    /** 兼容旧调用方：只要报告路径。 */
    fun captureCrash(
        exitCode: Int,
        wasRunningBefore: Boolean,
        dirName: String,
        allowCleanExit: Boolean = false
    ): String? = captureCrashDetail(exitCode, wasRunningBefore, dirName, allowCleanExit)?.reportPath

    /** 关键片段的显示标签（报告是纯文本，这里用中文；UI 侧另有本地化映射）。 */
    private fun reasonLabel(reason: KeyLogExtractor.Reason): String = when (reason) {
        KeyLogExtractor.Reason.EXCEPTION -> "异常栈"
        KeyLogExtractor.Reason.WATCHDOG -> "Watchdog"
        KeyLogExtractor.Reason.SEVERE -> "致命错误"
        KeyLogExtractor.Reason.CRASH_TAIL -> "崩溃前上下文"
    }

    /** 会话结束时读取会话日志文本（供 VM 直接构建结构化报告）。 */
    fun sessionLogText(dirName: String, maxBytes: Long = SESSION_LOG_MAX_BYTES): String {
        val session = termux.currentLaunchSession()?.takeIf { it.dirName == dirName } ?: return ""
        return termux.readSessionLog(session, maxBytes)
    }

    /** 结构化报告 sidecar：`crash-logs/{crashId}.json`。 */
    fun insightFile(crashId: String): File = File(crashLogsDir, "$crashId.json")

    /** 会话日志 sidecar：`crash-logs/{crashId}.log`（本次启动全量日志，冻结快照）。 */
    fun sessionLogFile(crashId: String): File = File(crashLogsDir, "$crashId.log")

    fun readSessionLogSidecar(crashId: String): String {
        val file = sessionLogFile(crashId)
        if (!file.isFile) return ""
        return runCatching { file.readText() }.getOrDefault("")
    }

    fun writeInsight(crashId: String, json: String): Boolean = runCatching {
        insightFile(crashId).writeText(json)
        true
    }.getOrDefault(false)

    fun readInsight(crashId: String): CrashInsight? {
        val file = insightFile(crashId)
        if (!file.isFile) return null
        return CrashInsight.decode(runCatching { file.readText() }.getOrDefault(""))
    }

    // ── 会话范围下的回退 / 原生报告定位 ─────────────────────────

    /** 捕获文件不可用时：取 latest.log 的最后一段（明确标注为回退模式）。 */
    private fun readLatestLogTailAsText(dirName: String): String {
        val logFile = latestLogFile(dirName)
        if (!logFile.isFile) return ""
        return runCatching {
            val scan = scanLatestLog(logFile, TAIL_LINES_FALLBACK)
            scan.tailLines.joinToString("\n")
        }.getOrDefault("")
    }

    /**
     * 本次启动生成的 MC 原生报告：`lastModified >= 会话开始 - 2s`。
     * 早于会话开始的报告属于历史启动，一律不打包（旧实现只取「最新文件」）。
     */
    private fun findNativeCrashOfSession(dirName: String, sessionStartedAt: Long): File? {
        val dir = nativeCrashReportsDir(dirName)
        if (!dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return null
        val threshold = (sessionStartedAt - 2_000L).coerceAtLeast(0L)
        return files.filter { it.lastModified() >= threshold }
            .maxByOrNull { it.lastModified() }
    }

    private fun readInsightPreview(file: File): String =
        runCatching { CrashInsight.decode(file.readText())?.primaryCause?.label.orEmpty() }.getOrDefault("")

    /** 列出所有崩溃报告，按时间倒序 */
    fun listCrashReports(): List<CrashReport> {
        return listReportsFrom(crashLogsDir, isNative = false)
    }

    /** 读取完整崩溃报告内容 */
    fun readCrashReport(fileName: String): String? {
        val file = File(crashLogsDir, fileName)
        return if (file.exists() && file.isFile) {
            try { file.readText() } catch (e: Exception) { null }
        } else {
            null
        }
    }

    /** 删除崩溃报告（连同结构化报告与会话日志 sidecar 一起删，避免留下孤儿文件） */
    fun deleteCrashReport(fileName: String): Boolean {
        val file = File(crashLogsDir, fileName)
        val crashId = fileName.removeSuffix(".txt")
        runCatching { insightFile(crashId).takeIf { it.isFile }?.delete() }
        runCatching { sessionLogFile(crashId).takeIf { it.isFile }?.delete() }
        return if (file.exists() && file.isFile) {
            file.delete()
        } else {
            false
        }
    }

    /** 清空所有崩溃报告 */
    fun clearAllCrashReports(): Int {
        val dir = crashLogsDir
        if (!dir.exists() || !dir.isDirectory) return 0
        var count = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.delete()) count++
        }
        return count
    }

    /** 读取 MC 原生 crash-reports 目录下的报告（MC 自动生成的） */
    fun listNativeCrashReports(dirName: String): List<CrashReport> {
        return listReportsFrom(nativeCrashReportsDir(dirName), isNative = true)
    }

    // ── 内部工具 ──

    /**
     * 流式扫描结果：总行数 + 尾部若干行 + 关键片段。内存占用与文件大小无关（有界）。
     */
    private class LogScan(val totalLines: Int, val tailLines: List<String>, val highlights: List<String>)

    /**
     * 单遍流式扫描日志，同时完成原先「提取关键片段」与 `takeLast(n)` 两件事：
     *
     *  - 用 [ArrayDeque] 保留最后 [tailCount] 行（内存有界）；
     *  - 命中 [KEY_MARKERS] 的行连同随后的缩进栈行、命中 [SEVERE_MARKERS] 的行，
     *    按原顺序收集为关键片段（最多 [MAX_HIGHLIGHT_LINES] 行）；
     *  - 统计总行数供报告头使用。
     *
     * 保持与原先 `readLines() + 关键片段提取 + takeLast` 完全一致的输出语义，
     * 只是不再把整份文件读进内存。
     */
    private fun scanLatestLog(file: File, tailCount: Int): LogScan {
        val tail = ArrayDeque<String>(tailCount + 1)
        val highlights = mutableListOf<String>()
        var total = 0
        var stackBudget = 0
        var highlightChars = 0
        file.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                total++
                tail.addLast(line)
                if (tail.size > tailCount) tail.removeFirst()
                // 关键片段必须**边扫边截断**：刷屏日志里 `Could not`/`Caused by:` 命中率很高，
                // 攒到最后再 take(MAX) 意味着崩溃收集线程上可能先持有百万级字符串。
                // OOM 恰好会杀掉这次要保住的崩溃报告，所以这里按「行数 + 总字符数」双上限收紧。
                if (highlights.size < MAX_HIGHLIGHT_LINES && highlightChars < MAX_HIGHLIGHT_CHARS) {
                    when {
                        KEY_MARKERS.any { line.contains(it, ignoreCase = true) } -> {
                            highlights.add(line)
                            highlightChars += line.length
                            // 块起点：随后最多 60 行的缩进栈行一并保留
                            stackBudget = 60
                        }
                        stackBudget > 0 && isStackLine(line) -> {
                            highlights.add(line)
                            highlightChars += line.length
                            stackBudget--
                        }
                        else -> {
                            stackBudget = 0
                            if (SEVERE_MARKERS.any { line.contains(it, ignoreCase = true) }) {
                                highlights.add(line)
                                highlightChars += line.length
                            }
                        }
                    }
                }
                line = reader.readLine()
            }
        }
        return LogScan(total, tail.toList(), highlights.take(MAX_HIGHLIGHT_LINES))
    }

    /** 栈行/续行判定：以空白字符开头（Java 栈、at ... 等） */
    private fun isStackLine(line: String): Boolean =
        line.isNotEmpty() && (line[0] == '\t' || line[0] == ' ')

    /** 需要连同后续栈一起完整保留的标志行 */
    private val KEY_MARKERS = listOf(
        "Paper Watchdog Thread/ERROR",
        "The server has not responded",
        "Current Thread: Server thread",
        "Current Thread: \"Server thread\"",
        "Server thread dump",
        "Exception in thread",
        "Caused by:"
    )

    /** 单独成行的严重错误标志 */
    private val SEVERE_MARKERS = listOf(
        "OutOfMemoryError",
        "StackOverflowError",
        "NoSuchMethodError",
        "NoClassDefFoundError",
        "FATAL",
        "/WARN]: Failed to",
        "Could not",
        "Unable to"
    )

    private val MAX_HIGHLIGHT_LINES = 600

    /**
     * 关键片段的字符总量上限（约 256KB 文本）。
     * 只有行数上限是不够的——单行异常/路径拼接可以很长；
     * 而且这是崩溃回调线程，内存爆掉会把崩溃报告本身一起丢掉。
     */
    private val MAX_HIGHLIGHT_CHARS = 256 * 1024

    /** 扫描指定目录下的报告文件，解析文件名时间戳并按时间倒序排列 */
    private fun listReportsFrom(dir: File, isNative: Boolean): List<CrashReport> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?: return emptyList()
        return files.map { f ->
            val (createdTime, createdText) = parseReportTime(f, isNative)
            val sidecar = if (isNative) null else File(crashLogsDir, "${f.name.removeSuffix(".txt")}.json")
            val hasInsight = sidecar?.isFile == true
            CrashReport(
                fileName = f.name,
                path = f.absolutePath,
                sizeBytes = f.length(),
                sizeText = formatSize(f.length()),
                createdTime = createdTime,
                createdText = createdText,
                preview = readPreview(f),
                hasInsight = hasInsight,
                primaryLabel = sidecar?.takeIf { hasInsight }?.let { readInsightPreview(it) }.orEmpty()
            )
        }.sortedByDescending { it.createdTime }
    }

    /**
     * 读取文件头部作为归因用预览。
     *
     * 曾经只有 500 字符：真实异常链（如 cacerts 损坏时的
     * `trustAnchors parameter must be non-empty`）往往在第 500 字符之后，
     * 而恰好在头部的 fontconfig 启发式行会把归因带偏（误诊成「字体运行库缺失」）。
     * 列表页只用 preview 做归因摘要，不直接展示原文，放大到 8 KB 没有副作用。
     */
    private fun readPreview(file: File): String {
        return try {
            val text = file.readText()
            if (text.length <= 8000) text else text.substring(0, 8000)
        } catch (e: Exception) {
            ""
        }
    }

    /** 解析报告文件名中的时间戳；解析失败回退到文件最后修改时间 */
    private fun parseReportTime(file: File, isNative: Boolean): Pair<Long, String> {
        val fallback = file.lastModified()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val fallbackText = fmt.format(Date(fallback))
        return try {
            val timestamp = if (isNative) {
                parseNativeFilename(file.name) ?: fallback
            } else {
                parseOwnFilename(file.name) ?: fallback
            }
            if (timestamp == fallback) {
                Pair(fallback, fallbackText)
            } else {
                Pair(timestamp, fmt.format(Date(timestamp)))
            }
        } catch (e: Exception) {
            Pair(fallback, fallbackText)
        }
    }

    /** 解析自建报告文件名 crash_yyyyMMdd_HHmmss.txt */
    private fun parseOwnFilename(name: String): Long? {
        if (!name.startsWith("crash_") || !name.endsWith(".txt")) return null
        val core = name.removePrefix("crash_").removeSuffix(".txt") // yyyyMMdd_HHmmss
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(core)?.time
    }

    internal companion object {
        /** 会话日志单次读取上限（超出保留尾部，崩溃现场在尾部）。 */
        private const val SESSION_LOG_MAX_BYTES = 4L * 1024L * 1024L

        /** 回退 latest.log 时保留的尾部行数。 */
        private const val TAIL_LINES_FALLBACK = 800

        /** 解析 MC 原生报告文件名 crash-yyyy-MM-dd_HH.mm.ss-server.txt */
        fun parseNativeFilename(name: String): Long? {
            if (!name.startsWith("crash-") || !name.endsWith(".txt")) return null
            // 取 `crash-` 与后缀之间的**完整**时间戳。
            //
            // 旧实现是 `substringBefore("-")`，而时间戳本身是 `2024-01-02_12.34.56`，
            // 于是只截到 "2024"，SimpleDateFormat 按 "yyyy-MM-dd_HH.mm.ss" 解析 "2024"
            // 必抛 ParseException（实测：Unparseable date: "2024"）。
            // 该异常从 maxByOrNull 的 selector 里冒出去，被 captureCrash 的外层 catch 吞成 null，
            // 结果是**只要目录里存在原生崩溃报告，应用自己的崩溃报告就完全不会生成**。
            val core = name.removePrefix("crash-").removeSuffix(".txt").substringBefore("-server")
            if (core.isEmpty()) return null
            // 解析失败不能抛：调用点在 maxByOrNull 的 selector 里，异常会毁掉整次报告生成。
            return try {
                SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US).parse(core)?.time
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** 查找 MC 原生 crash-reports 目录下最新的报告文件 */
    private fun findLatestNativeCrashReport(dirName: String): File? {
        val dir = nativeCrashReportsDir(dirName)
        if (!dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?: return null
        if (files.isEmpty()) return null
        // 按解析出的时间戳排序，解析失败回退到 lastModified。
        // runCatching 作为兜底：任何意外文件名都不该让「生成崩溃报告」整体失败。
        return files.maxByOrNull { f ->
            runCatching { parseNativeFilename(f.name) }.getOrNull() ?: f.lastModified()
        }
    }

    /** 格式化文件大小为 KB/MB */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
