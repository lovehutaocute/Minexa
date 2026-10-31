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
        val preview: String    // 前 500 字符预览
    )

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
     * 收集崩溃日志。
     * 当 MC 进程异常退出时调用，收集最近的日志写入报告文件。
     * @param exitCode 进程退出码
     * @param wasRunningBefore 是否之前在运行（区分正常停止和崩溃）
     * @return 生成的报告路径，失败返回 null
     */
    fun captureCrash(exitCode: Int, wasRunningBefore: Boolean, dirName: String, allowCleanExit: Boolean = false): String? {
        // 只在之前在运行且非正常退出时生成报告（正常 stop 命令退出码通常是 0；
        // 启动后快速退出即使 exit=0 也按异常处理）
        if (!wasRunningBefore || (exitCode == 0 && !allowCleanExit)) {
            return null
        }

        val now = Date()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        val reportFile = File(crashLogsDir, "crash_$ts.txt")

        return try {
            val sb = StringBuilder()
            // ── 头部 ──
            sb.appendLine("====================================")
            sb.appendLine("Minexa 崩溃报告")
            sb.appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)}")
            sb.appendLine("退出码: $exitCode")
            sb.appendLine("进程状态: 异常退出")
            if (exitCode == 137) {
                sb.appendLine("说明: exit=137 通常表示进程被 SIGKILL 终止，可能是 Android 内存压力、系统后台限制或外部强制停止；请结合下方首个 Java 异常判断根因。")
            }
            sb.appendLine("====================================")
            sb.appendLine()

            // ── 最近日志 ──
            // Paper 的 Watchdog 转储一次就有几百行线程栈，单纯取"最后 N 行"会把
            // 最关键的「Server thread 卡在哪」整段截掉。这里改为：
            //   1) 先按关键词提取重点段落（Watchdog / Server thread / 异常栈等）完整保留
            //   2) 再补上最后 N 行作为上下文
            //   3) 两者去重后按原顺序输出
            val logFile = latestLogFile(dirName)
            val logExists = logFile.exists()
            val tailCount = 800
            // 单遍流式扫描：latest.log 可达数十至上百 MB，readLines() 会把整份文件载入
            // List<String> 造成 OOM（且这里运行在崩溃退出的回调线程上）。流式处理只保留
            // 尾部 tailCount 行与命中的关键片段，同时统计总行数（报告头需要「共 N 行」）。
            val scan: LogScan = if (logExists) {
                try {
                    scanLatestLog(logFile, tailCount)
                } catch (e: Exception) {
                    LogScan(1, listOf("(读取 latest.log 失败: ${e.message})"), emptyList())
                }
            } else {
                LogScan(1, listOf("(latest.log 不存在)"), emptyList())
            }
            val allLines = scan.tailLines

            // latest.log 是空的 / 不存在时，补上应用侧日志。
            //
            // 背景：latest.log 只装「MC 服务端自己输出的内容」。一旦服务端在启动阶段
            // 就失败（例如启动命令不完整、Java 缺失、核心不匹配），它一个字节都不会输出，
            // 报告就只剩「共 0 行」——没有任何诊断价值。而真正的原因往往记录在
            // Minexa 自己的日志里（java 路径解析、启动命令、缺库警告）。
            //
            // 因此这里把 app 侧日志作为独立小节补出来，并明确标注状态，
            // 避免读者再对着一个空文件猜。
            val appLogLines = termux.readAppLogTail()
            if (appLogLines.isNotEmpty()) {
                sb.appendLine("--- Minexa 应用日志 (最后 ${minOf(400, appLogLines.size)} 行，共 ${appLogLines.size} 行) ---")
                appLogLines.forEach { sb.appendLine(it) }
                sb.appendLine()
            }

            val highlights = scan.highlights
            if (highlights.isNotEmpty()) {
                sb.appendLine("--- 关键片段 (Watchdog / 异常栈 / 致命错误) ---")
                highlights.forEach { sb.appendLine(it) }
                sb.appendLine()
            }

            // 明确区分三种状态，不再输出无信息量的「共 0 行」
            val logState = when {
                !logExists -> "latest.log 不存在（服务端目录可能还没建好）"
                scan.totalLines == 0 -> "latest.log 存在但为空 —— 服务端进程从未产生任何输出，" +
                    "说明它很可能根本没被启动起来；请结合上方「Minexa 应用日志」判断"
                else -> null
            }
            sb.appendLine("--- 最近日志 (最后 ${minOf(tailCount, scan.totalLines)} 行，共 ${scan.totalLines} 行) ---")
            if (logState != null) {
                sb.appendLine("(说明: $logState)")
            }
            allLines.forEach { sb.appendLine(it) }
            sb.appendLine()

            // ── MC 原生崩溃报告 ──
            sb.appendLine("--- MC 原生崩溃报告 ---")
            val nativeLatest = findLatestNativeCrashReport(dirName)
            if (nativeLatest != null) {
                sb.appendLine("(来源: ${nativeLatest.name})")
                try {
                    sb.appendLine(nativeLatest.readText())
                } catch (e: Exception) {
                    sb.appendLine("(读取原生崩溃报告失败: ${e.message})")
                }
            } else {
                sb.appendLine("(无 MC 原生崩溃报告)")
            }

            reportFile.writeText(sb.toString())
            reportFile.absolutePath
        } catch (e: Exception) {
            // 不能静默吞掉：报告生成失败时用户看到的是「没有崩溃报告」，无从判断原因。
            // 历史上就是这里吞掉了 parseNativeFilename 抛出的 ParseException，
            // 掩盖了「有原生报告就再也不生成报告」这个 bug。
            Log.e(TAG, "生成崩溃报告失败（将返回 null，界面会显示无报告）", e)
            null
        }
    }

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

    /** 删除崩溃报告 */
    fun deleteCrashReport(fileName: String): Boolean {
        val file = File(crashLogsDir, fileName)
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
            CrashReport(
                fileName = f.name,
                path = f.absolutePath,
                sizeBytes = f.length(),
                sizeText = formatSize(f.length()),
                createdTime = createdTime,
                createdText = createdText,
                preview = readPreview(f)
            )
        }.sortedByDescending { it.createdTime }
    }

    /** 读取文件前 500 字符作为预览 */
    private fun readPreview(file: File): String {
        return try {
            val text = file.readText()
            if (text.length <= 500) text else text.substring(0, 500)
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
