package com.mineserve.mobile.data

/**
 * 「切换核心之后，当前选中的版本还要不要保留」的判定逻辑。
 *
 * 抽成纯函数的原因：这段规则有四个触发条件、互有重叠，埋在 ViewModel 里既没法单测、
 * 也容易被后来的人改坏。规则本身只依赖「版本编号体系 + 版本列表 + 是否本次进程首次加载」，
 * 与 Android 无关。
 *
 * 背景：各核心的版本号并不是一套编号。
 * - 原版 / 插件端 / 模组端（Paper、Purpur、Fabric …）都是 Minecraft 版本号（1.20.4），
 *   互相之间可以沿用；
 * - Velocity 按 MC 版本发版，也可以沿用；
 * - BungeeCord 官方只提供 `latest` 一个值；
 * - 基岩各发行版（PNX / PocketMine / Allay / BDS）各有一套编号（PNX 版本、5.x、官方 build 号），
 *   彼此之间以及与 Java 系之间都不能沿用。
 *
 * 沿用一个不属于目标核心的版本号，直接后果是：界面上版本号显示成一个不在列表里的值、
 * 版本抽屉里没有任何一项高亮、点下载拼出一个 404 的地址。
 */
object VersionSelection {

    /** 版本「待定」哨兵：跨体系切核心时先置成它，等列表回来再校正。 */
    const val LATEST = "latest"

    /**
     * 该核心的版本编号体系。同一体系内版本号可以沿用，跨体系必须重选。
     */
    fun familyOf(core: ServerCore): String = when (core) {
        ServerCore.Velocity -> "velocity"
        ServerCore.BungeeCord -> "bungee"
        ServerCore.PowerNukkitX -> "pnx"
        ServerCore.PocketMine -> "pocketmine"
        ServerCore.Allay -> "allay"
        ServerCore.Bds -> "bds"
        ServerCore.Unknown -> "unknown"
        else -> "mc-java"
    }

    /**
     * 计算出校正后的版本号；`null` 表示保持用户当前的选择不动。
     *
     * @param current 当前配置里的版本号
     * @param versions 目标核心的可用版本（已按新旧排序，第一项即最新）
     * @param familyChanged 本次加载前刚发生了「跨版本体系」的核心切换
     * @param firstLoadInProcess 本次进程内第一次加载该核心的版本（冷启动）
     */
    fun resolve(
        current: String,
        versions: List<String>,
        familyChanged: Boolean,
        firstLoadInProcess: Boolean
    ): String? {
        if (versions.isEmpty()) return null
        // 空版本 = 用户刚点过核心、被显式清空，等着自己挑一个（见 McViewModel.selectCore）。
        // 这里**不能**替他选第一项：那样「切换核心后必须重新选版本」就形同虚设，
        // 而且用户还没看到列表就已经背上了一个具体版本号。
        if (current.isBlank()) return null
        val missing = current !in versions
        val stale = (firstLoadInProcess && missing) ||
            (familyChanged && missing) ||
            (current == LATEST && LATEST !in versions)
        if (!stale) return null
        val fallback = versions.first()
        return if (fallback == current) null else fallback
    }
}
