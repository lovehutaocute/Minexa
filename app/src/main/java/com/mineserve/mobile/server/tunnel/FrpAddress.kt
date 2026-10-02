package com.mineserve.mobile.server.tunnel

/**
 * frp 系隧道的公网访问地址解析。
 *
 * ## 为什么需要单独一段逻辑
 *
 * frpc 的启动日志里**没有**"你可以通过 xxx 访问"这种话 —— 端口映射结果只存在于
 * 平台的 API 响应里。所以地址只能从两处拿：平台 API 的隧道对象，或下发的配置文本。
 * 前者权威，后者兜底。
 *
 * ## 关键规则：没有端口就不要拼端口
 *
 * 常见错误是把 `serverAddr` 与 `remotePort` 直接拼成 `"$addr:$port"`。两个坑：
 *
 * 1. `serverAddr` 是 **frps 节点地址**，不一定是给玩家连的地址。用自定义域名或
 *    HTTP 类隧道的平台，两者完全不同 —— 玩家拿节点地址连不上。
 * 2. `remotePort` 可能为 null（域名映射、HTTP 隧道）。此时 `"$addr:$null"` 会拼出
 *    字面量 `"null"`，界面上显示成 `frp.example.com:null`。
 *
 * 因此优先级是：**平台给的完整地址 > 节点名+端口 > 节点名**，任一步缺关键字段就
 * 退回上一级，绝不硬拼。
 *
 * 这套优先级与 EdgeCube 的 `SavedFrpTunnel.displayAddress` 一致。
 */
object FrpAddress {

    /**
     * 组装公网地址。
     *
     * @param remoteAddress 平台 API 返回的完整地址（`host:port` 或域名）。**最高优先** ——
     *   它是平台按隧道实际映射算出来的，不必我们猜。
     * @param nodeName 节点名/主机名，作为 [remoteAddress] 缺失时的拼接材料。
     * @param remotePort 远端端口；为 null 表示该隧道没有独立端口（域名映射等）。
     */
    fun build(
        remoteAddress: String?,
        nodeName: String?,
        remotePort: Int?
    ): String? {
        // ① 平台直接给的地址最可信，原样使用。
        remoteAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        val node = nodeName?.trim()?.takeIf { it.isNotEmpty() }

        // ② 有节点名也有端口：拼。
        if (node != null && remotePort != null && remotePort > 0) {
            return "$node:$remotePort"
        }

        // ③ 只有节点名：就这样给出。缺端口时宁可给一半，也不拼出 ":null"。
        return node
    }

    /**
     * 从 frpc 配置文本里兜底解析地址。
     *
     * 只在平台 API 拿不到隧道对象时使用（例如用户手动粘贴配置、或 API 临时失败）。
     * 解析出的 `serverAddr` 只是节点地址，可能不是最终访问地址 —— 这一点无法从
     * 配置文本里判断，所以它排在 API 之后。
     *
     * 支持 toml / yaml / json 三种写法（frpc 都接受）：
     * - toml/yaml: `serverAddr = "x"` / `serverAddr: x`
     * - json:      `"serverAddr": "x"`
     */
    fun fromConfigText(configText: String): String? {
        val addr = findString(configText, "serverAddr")
            ?: findString(configText, "server_addr")
            ?: return null

        // 端口：顶层 serverPort 与代理级 remotePort 都可能存在。
        // 代理的 remotePort 才是玩家实际要连的端口，优先取它。
        val port = findInt(configText, "remotePort")
            ?: findInt(configText, "remote_port")

        return build(remoteAddress = null, nodeName = addr, remotePort = port)
    }

    /**
     * 取字符串字段值。
     *
     * 值的引号可选：toml 习惯写 `serverAddr = "x"`，但 yaml 常写 `server_addr: x`
     * （不加引号也合法）。强制要引号会让 yaml 配置一个字段都解析不出来。
     *
     * 用 `[^"'\n]+` 而不是 `.*`：配置里同一个键可能出现多次（多个 `[[proxies]]`
     * 各有 `name`），`.` 会跨行吃掉后面的内容、把不同字段的值拼到一起。
     */
    private fun findString(text: String, key: String): String? =
        Regex("""["']?""" + Regex.escape(key) + """["']?\s*[:=]\s*["']?([^"'\n\r\s,}]+)""")
            .find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun findInt(text: String, key: String): Int? =
        Regex("""["']?""" + Regex.escape(key) + """["']?\s*[:=]\s*(\d+)""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
}
