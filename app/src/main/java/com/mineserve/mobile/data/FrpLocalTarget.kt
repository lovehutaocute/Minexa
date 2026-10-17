package com.mineserve.mobile.data

/**
 * 「隧道到底要把玩家转给本机哪个端口」的对齐工具。
 *
 * ## 为什么需要它
 *
 * 自建 frp 的配置（frpc.toml）是用户自己粘贴的文本，里面的 `localPort` 是他
 * **当时**的游戏端口。用户之后在服务端配置文件里改了端口，这份文本不会跟着变；
 * 于是隧道把玩家送到一个没人监听的端口上。表现是「隧道看起来是通的，玩家却进不来」，
 * 而日志里只有一句 `connect: connection refused`，从字面上完全看不出是端口对不上。
 *
 * 服务端真实端口在本 App 里是可读的真源（见 [ServerPort]），所以启动前把配置文本里的
 * 转发端口对齐过去即可。**只改运行期这一份副本，用户保存的原文一个字节都不动** ——
 * 这样他手里的配置还是他自己的，而隧道又能打对地方。
 *
 * ## 什么情况下**不**动
 *
 * 宁可漏改，也不能改错。以下两种一律原样返回：
 *
 * 1. **指向别的机器**：配置里有 `localIP` / `local_ip` 且不在回环上（例如
 *    `192.168.1.5`）。那说明用户是故意把隧道转到局域网里另一台机器，不是「端口没跟上」，
 *    动它才是错。
 * 2. **多个代理各指不同端口**：例如顺带代理了一个面板。全部改成游戏端口会把那个服务
 *    也拉过来。
 */
object FrpLocalTarget {

    /**
     * 配置文本里「转发到哪里」的静态信息。
     */
    data class Target(val ports: List<Int>, val foreignHost: Boolean) {
        /** 所有出现处是否都指向同一个端口（一处都没写时也算，但 [only] 为 null）。 */
        val single: Boolean get() = ports.distinct().size <= 1

        /** 唯一的那个端口；一处都没写、或指向多个端口时为 null。 */
        val only: Int? get() = if (single) ports.firstOrNull() else null
    }

    /**
     * 匹配 `localPort = 25565` / `local_port=25565`。
     *
     * 大小写不敏感；允许 `=` 两侧任意空白；允许行尾注释（TOML 的 `#` 与 ini 的 `;`）。
     * 只认纯数字：写成 `"25565"`（带引号）属于非常规写法，这里宁可漏也不要猜错。
     */
    private val PORT_LINE = Regex("(?im)^(\\s*local_?port\\s*=\\s*)(\\d+)(\\s*(?:[#;].*)?)$")

    /** 匹配 `localIP = "127.0.0.1"`（值可带引号）。 */
    private val HOST_LINE = Regex("(?im)^\\s*local_?ip\\s*=\\s*[\"']?([^\"'\\s#;]+)")

    /** 视为「就是本机」的主机取值。 */
    private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0.0.0.0")

    /** 读出配置里的转发目标信息。 */
    fun target(text: String): Target {
        val hosts = HOST_LINE.findAll(text).map { it.groupValues[1].lowercase() }.toList()
        return Target(
            ports = PORT_LINE.findAll(text).mapNotNull { it.groupValues[2].toIntOrNull() }.toList(),
            foreignHost = hosts.any { it !in LOCAL_HOSTS }
        )
    }

    /**
     * 对齐结果。
     *
     * @param text 对齐后的文本；不需要对齐时原样返回
     * @param changed 实际改动的处数
     * @param from 被替换掉的旧端口；没有改动时为 null
     * @param refused 因为「多个代理各指不同端口」而拒绝改动
     */
    data class Aligned(
        val text: String,
        val changed: Int = 0,
        val from: Int? = null,
        val refused: Boolean = false
    )

    /**
     * 把 [text] 里的本地转发端口对齐到 [realPort]。
     *
     * @param realPort 服务端真正在监听的端口；非法值时不动文本
     */
    fun align(text: String, realPort: Int): Aligned {
        if (realPort !in ServerPort.VALID_RANGE) return Aligned(text)
        val t = target(text)
        if (t.foreignHost) return Aligned(text)
        if (t.ports.isEmpty()) return Aligned(text)
        if (!t.single) return Aligned(text, refused = true)
        val old = t.only ?: return Aligned(text)
        if (old == realPort) return Aligned(text)

        var count = 0
        val out = PORT_LINE.replace(text) { m ->
            count++
            m.groupValues[1] + realPort + m.groupValues[3]
        }
        return Aligned(out, changed = count, from = old)
    }
}
