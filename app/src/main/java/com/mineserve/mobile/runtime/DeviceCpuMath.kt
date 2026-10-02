package com.mineserve.mobile.runtime

/**
 * 整机 CPU 总使用率计算的纯逻辑（无 Android 依赖，便于 JVM 单元测试）。
 *
 * 与 [McProcessCpuMath] 的区别：
 *  - [McProcessCpuMath] 算的是**单个进程**的 CPU，并且要按核心数归一化（多线程可跑满多核）。
 *  - 本类算的是**整机所有核心**的总占用率，天然就是 0..100，**不做**核数归一化。
 *
 * 数据来源是 `/proc/stat` 第一行 `cpu`:
 * ```
 * cpu  user nice system idle iowait irq softirq steal guest guest_nice
 *     (1)  (2)   (3)    (4)   (5)    (6)  (7)     (8)   (9)    (10)
 * ```
 * 使用率 = (总增量 - idle 增量) / 总增量。
 *
 * ## 两个必须注意的坑
 *
 * **1. guest / guest_nice 不能计入总和。** 内核 `kernel/sched/cputime.c` 的
 * `account_guest_time()` 把 guest 时间**同时**记进 `CPUTIME_USER` 与 `CPUTIME_GUEST`
 * （guest_nice 同理记进 NICE 与 GUEST_NICE），即 `guest ⊂ user`、`guest_nice ⊂ nice`。
 * 因此只对**前 8 列**求和；把十列直接 `sum()` 会重复计数，分母偏大、读数偏低。
 *
 * **2. idle 只取 `idle` 一列。** `iowait` 虽然进程没在跑，但它表示 CPU 在等 IO，
 * 不属于「空闲」，算进 idle 会让磁盘繁忙时读数偏低（Android 上很常见）。
 * 注：man7 `proc_stat(5)` 也明确写 iowait「is not reliable」。
 */
internal object DeviceCpuMath {

    /**
     * 参与求和的字段个数。
     *
     * `/proc/stat` 的 `cpu` 行最多十列，后两列 guest / guest_nice 已被计入 user / nice
     * （见类注释），只取前八列：user nice system idle iowait irq softirq steal。
     */
    private const val COUNTED_FIELDS = 8

    /** `/proc/stat` 中一行的累计计数（jiffies）。 */
    data class Sample(val total: Long, val idle: Long)

    /**
     * 解析 `/proc/stat` 第一行 `cpu` 的计数字段。
     *
     * 入参可以是整行文本（含开头的 `cpu `），返回 [Sample]；解析不到足够字段时返回 null。
     */
    fun parse(line: String): Sample? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("cpu")) return null
        // "cpu  1 2 3" 里第一个 token 是标签，其后的都是计数。
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.firstOrNull() != "cpu") return null
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        // 至少要有 user/nice/system/idle 四列才可用。
        if (values.size < 4) return null
        val idle = values[3]
        // 只累加前八列：guest/guest_nice 已包含在 user/nice 里，再算一遍会让分母偏大。
        val total = values.take(COUNTED_FIELDS).sum()
        if (idle < 0L || total <= 0L || idle > total) return null
        return Sample(total = total, idle = idle)
    }

    /**
     * 计算两个采样点之间整机 CPU 总使用率（%）。
     *
     * 计数器回退、总增量非正（两次采样间隔太短或数据异常）时返回 null。
     * 返回值限制在 0..100。**不按核心数归一化**——它本来就是整机口径。
     */
    fun percent(now: Sample, prev: Sample): Int? {
        val totalDelta = now.total - prev.total
        if (totalDelta <= 0L) return null
        val idleDelta = (now.idle - prev.idle).coerceIn(0L, totalDelta)
        val busyDelta = totalDelta - idleDelta
        return (busyDelta.toDouble() * 100.0 / totalDelta.toDouble())
            .toInt()
            .coerceIn(0, 100)
    }

    // ── cpufreq 频率比方案 ──────────────────────────────────────

    /**
     * 单核的当前/最小/最大频率（kHz）。
     *
     * 频率比为 `(cur - min) / (max - min)`：CPU 空载时调频器把频率降到最低，
     * 满载时升到最高，因此这个比值可以作为「该核当前的忙碌程度」。
     */
    data class CoreFreq(val curKhz: Long, val minKhz: Long, val maxKhz: Long)

    /**
     * 由多个核心的频率比求整机 CPU 使用率（%）。
     *
     * 为什么用它作为主数据源：**单次读取即可出值**，不像 `/proc/stat` 需要两次采样的
     * 基线（首次必然为 null，UI 启动后会显示一段时间的 `--`）。
     *
     * - 只统计 [CoreFreq] 合法的核心（min <= cur <= max、max > 0）。
     * - `max == min`（固定频率核）时按 cur >= max 记 100%，否则 0%。
     * - 没有任何合法核心时返回 null，交给调用方回退到 `/proc/stat`。
     */
    fun percentFromFreqs(cores: List<CoreFreq>): Int? {
        var sum = 0.0
        var valid = 0
        for (core in cores) {
            if (core.maxKhz <= 0L || core.minKhz < 0L || core.curKhz <= 0L) continue
            if (core.minKhz > core.maxKhz) continue
            val ratio = if (core.maxKhz > core.minKhz) {
                (core.curKhz - core.minKhz).toDouble() / (core.maxKhz - core.minKhz).toDouble()
            } else {
                if (core.curKhz >= core.maxKhz) 1.0 else 0.0
            }
            sum += ratio.coerceIn(0.0, 1.0)
            valid++
        }
        if (valid == 0) return null
        return ((sum / valid) * 100.0).toInt().coerceIn(0, 100)
    }
}
