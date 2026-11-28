package com.mineserve.mobile.data

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载源的健康度短期记忆。
 *
 * 为什么需要：核心下载的候选源是固定顺序（镜像在前、官方在后）。当某个镜像当天挂了，
 * 每次安装都要先在这个源上超时一轮（多线程探测 + 重试），用户体感是「装一次卡一次」。
 * 而失败原因和源本身高度相关 —— 同一个源在几分钟内几乎必然继续失败。
 *
 * 设计取舍：
 *  - **只降权不剔除**。源挂可能是临时的，也可能是我们误判（比如某个文件那条链路上
 *    恰好 404，换个文件就正常）。剔除会让「唯一可用的源」永远不被尝试。
 *  - **时间衰减**。10 分钟内失败权重最高，一天后基本归零，避免一次网络抖动永久影响排序。
 *  - **成功即清零**。同一个源一旦成功，说明它此刻可用，立刻恢复原顺序。
 *  - **落盘**。进程被杀/重启后顺序不该重置回「坏源优先」。
 */
object DownloadSourceHealth {

    private const val TAG = "DownloadSourceHealth"

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS

    /** 记录条数上限：URL 会带版本号，无限增长没有意义。 */
    private const val MAX_ENTRIES = 300

    data class Entry(val failures: Int, val lastFailureMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var storeFile: File? = null

    @Volatile
    private var loaded = false

    /**
     * 绑定落盘文件并载入历史。
     *
     * 幂等：重复调用只会载入一次（`attach` 在 Controller 初始化时调用，但对象是单例，
     * 多个入口调用不应互相覆盖内存态）。
     */
    fun attach(file: File) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            storeFile = file
            loaded = true
            runCatching {
                if (!file.isFile) return
                file.readLines().forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3) return@forEach
                    val failures = parts[1].toIntOrNull() ?: return@forEach
                    val last = parts[2].toLongOrNull() ?: return@forEach
                    if (parts[0].isNotBlank()) entries[parts[0]] = Entry(failures, last)
                }
            }.onFailure { Log.w(TAG, "载入源健康度失败: ${it.message}") }
        }
    }

    /**
     * 按健康度重排候选源。
     *
     * 用 `sortedBy` 保证稳定排序：同分的源保持调用方给的原始优先级（镜像 → 官方），
     * 这样「健康时行为完全不变」，只有确实失败过的源才会被挪后。
     */
    fun rank(urls: List<String>, now: Long = System.currentTimeMillis()): List<String> {
        if (entries.isEmpty() || urls.size < 2) return urls
        return urls.sortedBy { penaltyOf(entries[it], now) }
    }

    /**
     * 单个源当前的惩罚分：0 = 干净，越大越该往后放。
     *
     * 时间档位刻意做成台阶而不是线性衰减 —— 线性会让「11 分钟前失败」和
     * 「12 分钟前失败」几乎同分，但实际网络故障的恢复是成块的（限速结束、DNS 恢复）。
     */
    internal fun penaltyOf(entry: Entry?, now: Long): Long {
        if (entry == null) return 0
        val age = (now - entry.lastFailureMs).coerceAtLeast(0)
        val recency = when {
            age < 10 * MINUTE_MS -> 400L
            age < HOUR_MS -> 200L
            age < DAY_MS -> 80L
            // 一天前的失败直接遗忘：不能因为「失败过一次」就永久改变源顺序。
            else -> return 0
        }
        // 连续失败次数最多计 5 次：再多的失败不额外加权，避免一个源永久沉底。
        return recency + entry.failures.coerceIn(0, 5) * 10L
    }

    fun recordSuccess(url: String) {
        if (entries.remove(url) != null) persist()
    }

    fun recordFailure(url: String, now: Long = System.currentTimeMillis()) {
        entries.compute(url) { _, old ->
            Entry((old?.failures ?: 0) + 1, now)
        }
        if (entries.size > MAX_ENTRIES) {
            // 丢掉最久未失败的一批，只保留最近的 MAX_ENTRIES 条。
            entries.entries
                .sortedBy { it.value.lastFailureMs }
                .take(entries.size - MAX_ENTRIES)
                .forEach { entries.remove(it.key) }
        }
        persist()
    }

    /** 仅测试用：清空内存态。 */
    internal fun resetForTest() {
        entries.clear()
        loaded = false
        storeFile = null
    }

    private fun persist() {
        val file = storeFile ?: return
        runCatching {
            val text = entries.entries.joinToString("\n") { (url, e) ->
                "$url\t${e.failures}\t${e.lastFailureMs}"
            }
            // 临时文件 + 改名：多线程同时记录失败时，直接 writeText 会互相截断，
            // 可能留下半行导致下次载入把这批记录整体丢掉。
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "保存源健康度失败: ${it.message}") }
    }
}
