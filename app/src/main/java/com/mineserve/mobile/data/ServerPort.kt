package com.mineserve.mobile.data

/**
 * 端口真源的解析与迁移规则。
 *
 * ## 为什么需要单独一段逻辑
 *
 * 应用里有两个「端口」，历史上互不同步：
 * - [McConfig.localPort]：App 侧配置。地址栏、穿透默认端口、诊断检查都读它；
 * - 服务端配置文件里的端口：Java 系是 `server.properties` 的 `server-port`，
 *   PowerNukkitX 是 `pnx.yml` 的 `settings.port`。这才是**服务端真正监听的端口**。
 *
 * 典型故障：用户在「服务器配置」页把端口改成 25562（只写进了 `server.properties`），
 * 地址栏仍按 `localPort` 显示 19132 —— 界面上给玩家的是连不上的地址。
 *
 * ## 修复后的约定
 *
 * **以服务端配置文件为准**：读到配置文件里的端口时，它就是真源，
 * [McConfig.localPort] 降级为它的镜像（供启动前覆写、穿透默认端口等场景使用）。
 * 配置文件尚未生成时（服务端一次都没启动过），真源退化为 [McConfig.localPort]。
 *
 * 本文件只放纯函数，不依赖 Android API，便于单元测试。
 */
object ServerPort {

    /** Java 系服务端的默认端口。 */
    const val DEFAULT_JAVA = 25565

    /** 基岩版（PNX / PocketMine / Allay）的默认端口，走 UDP。 */
    const val DEFAULT_BEDROCK = 19132

    /** 合法端口区间。与服务端及「服务器配置」页的输入校验保持一致。 */
    val VALID_RANGE = 1..65535

    /** 该核心的默认端口。 */
    fun defaultFor(core: ServerCore): Int =
        if (core.isBedrock) DEFAULT_BEDROCK else DEFAULT_JAVA

    /**
     * 解析配置文件里读出的端口文本。
     *
     * 越界、非数字、空值一律返回 null，交给调用方退回默认值 ——
     * 配置文件被手改坏时不能让界面显示一个非法端口。
     */
    fun parse(raw: String?): Int? =
        raw?.trim()?.toIntOrNull()?.takeIf { it in VALID_RANGE }

    /**
     * 解析「服务端真正监听的端口」。
     *
     * @param raw 配置文件里的端口文本；null 表示文件不存在或该键缺失。
     * @param core 当前核心，决定缺省值（Java 25565 / 基岩 19132）。
     * @param fallback 连核心都未知时的兜底值。
     */
    fun resolve(raw: String?, core: ServerCore?, fallback: Int = DEFAULT_JAVA): Int =
        parse(raw) ?: core?.let { defaultFor(it) } ?: fallback

    /**
     * 切换核心时的端口迁移。
     *
     * 只在 **Java 系 ↔ 基岩系** 之间迁移，且**只迁移默认值**：
     * - 25565 ⇄ 19132 对调，因为这两个数就是两侧的默认端口；
     * - 用户自定义的端口（如 25562）原样保留 —— 那是用户的决定，不该被覆盖；
     * - 同类核心之间（Paper → Purpur）不动端口。
     *
     * 旧实现是单向的 `if (localPort == 25565) 19132 else localPort`：只有「Java → 基岩」
     * 这一半，且不判断源核心类型。结果是 25565 一旦被换成 19132 就再也回不去
     * （19132 → 切回 Java 仍是 19132），而界面上当时已没有端口编辑框可以改回来。
     */
    fun migrateOnCoreSwitch(port: Int, from: ServerCore?, to: ServerCore): Int {
        val fromBedrock = from?.isBedrock ?: false
        if (fromBedrock == to.isBedrock) return port
        return when (port) {
            DEFAULT_JAVA -> DEFAULT_BEDROCK
            DEFAULT_BEDROCK -> DEFAULT_JAVA
            else -> port
        }
    }
}
