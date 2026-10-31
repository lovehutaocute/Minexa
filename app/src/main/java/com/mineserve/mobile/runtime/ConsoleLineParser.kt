package com.mineserve.mobile.runtime

import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.data.startupPhaseForLog
import com.mineserve.mobile.server.CrashReportAnalyzer

/**
 * 控制台单行日志的**纯解析结果**。
 *
 * 设计意图：把"从一行日志里能提取出什么"与"提取后要做什么"彻底分开。
 * 前者是无副作用的纯函数，可以被任意多个消费者复用而无需重复跑正则。
 */
data class ConsoleSignals(
    /** 本行是否透露了启动阶段（含 Ready）。 */
    val startupPhase: StartupPhase? = null,
    /** `There are N of a max of M players online` 的解析结果。 */
    val players: PlayerCount? = null,
    /** `TPS from last 1m` 的解析结果。 */
    val tps: Double? = null,
    /** 日志暗示核心需要的 Java 版本（用于不兼容预警）。 */
    val requiredJavaVersion: Int? = null,
    /** 本行是否包含"服务端已就绪"的信号。 */
    val isReady: Boolean = false
) {
    /** 一行都没命中时可以直接跳过后续处理，这是绝大多数行的实际情况。 */
    val isEmpty: Boolean
        get() {
            if (startupPhase != null) return false
            if (players != null) return false
            if (tps != null) return false
            if (requiredJavaVersion != null) return false
            return !isReady
        }

    data class PlayerCount(val online: Int, val max: Int)
}

/**
 * 控制台日志解析器（无状态、线程安全）。
 *
 * 收敛目的：原先 [com.mineserve.mobile.ui.McViewModel] 与
 * [com.mineserve.mobile.service.McForegroundService] **各自订阅一遍** consoleFlow
 * 并各自跑一套正则，App 在前台时同一条日志被解析两次，CPU 开销翻倍。
 * 抽出本类后两侧共用同一份解析结果，正则只跑一次。
 *
 * 性能约定：
 *  - 先用 [String.contains] 做廉价预筛，只有命中的行才进入正则；
 *  - 所有正则均为预编译常量，避免每行重新构造 Pattern。
 */
object ConsoleLineParser {

    /**
     * 玩家数：Java 版与官方 BDS 的措辞不同，同一条正则一起收。
     *  - Java：`There are 3 of a max of 20 players online:`
     *  - BDS ：`There are 3/10 players online:`
     */
    private val PLAYERS_REGEX = Regex("There are (\\d+)(?: of a max of |\\s*/\\s*)(\\d+) players online")
    private val TPS_REGEX = Regex("TPS from last 1m.*?:\\s*([\\d.]+)")

    /** 命中玩家数/就绪/TPS 判定所必需的廉价子串。 */
    private const val KEY_PLAYERS = "players online"
    private const val KEY_TPS = "TPS from last 1m"

    /**
     * 单行解析结果的有界缓存（按最近使用淘汰）。
     *
     * consoleFlow 的每个订阅者（[com.mineserve.mobile.ui.McViewModel] 与
     * [com.mineserve.mobile.service.McForegroundService]）都会对**同一条**日志各调一次
     * [parse]；App 在前台时两者同时订阅，同一行被解析两遍，正则开销翻倍。
     * [parse] 只依赖入参字符串、无副作用，因此在这里按行缓存即可让第二个消费者直接命中，
     * 不再重跑正则。容量很小，命中失败也不影响正确性。
     */
    private const val CACHE_MAX = 64
    private val cache = object : LinkedHashMap<String, ConsoleSignals>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ConsoleSignals>): Boolean =
            size > CACHE_MAX
    }

    /**
     * 解析一行控制台输出（带重复行缓存）。
     *
     * 注意：不包含玩家进出服、聊天等需要玩家名的事件——那些需要 PlayerManager
     * 与通知/记录副作用，仍由各自的消费者处理，避免把 UI 语义塞进纯解析层。
     */
    fun parse(line: String): ConsoleSignals {
        synchronized(cache) { cache[line]?.let { return it } }
        val result = parseUncached(line)
        synchronized(cache) { cache[line] = result }
        return result
    }

    private fun parseUncached(line: String): ConsoleSignals {
        var phase: StartupPhase? = null
        var players: ConsoleSignals.PlayerCount? = null
        var tps: Double? = null
        var requiredJava: Int? = null

        // 启动阶段：日志量最大，但该函数本身就是一串 contains，开销可接受
        phase = startupPhaseForLog(line)

        if (line.contains(KEY_PLAYERS)) {
            PLAYERS_REGEX.find(line)?.let { m ->
                val online = m.groupValues[1].toIntOrNull()
                val max = m.groupValues[2].toIntOrNull()
                if (online != null && max != null) players = ConsoleSignals.PlayerCount(online, max)
            }
        }

        if (line.contains(KEY_TPS)) {
            TPS_REGEX.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { tps = it }
        }

        // Java 兼容性只在日志提到 class file version / requires Java 时才做正则
        if (line.contains("UnsupportedClassVersionError") ||
            line.contains("class file version") ||
            line.contains("requires Java") ||
            line.contains("Java 8 or higher") ||
            line.contains("or higher")
        ) {
            requiredJava = CrashReportAnalyzer.requiredJavaVersion(line)
        }

        return ConsoleSignals(
            startupPhase = phase,
            players = players,
            tps = tps,
            requiredJavaVersion = requiredJava,
            isReady = phase == StartupPhase.Ready
        )
    }
}
