package com.mineserve.mobile.server.tunnel.easytier

import kotlin.math.absoluteValue

/**
 * 一次 EasyTier 组网的完整参数。
 *
 * 全部字段都由 App 生成或用户填写，**不含设备相关状态** —— 这样 [toToml] 是纯函数，
 * 可以在单测里直接断言生成的 TOML，而不必起进程或真机。
 *
 * @param instanceName 实例名。必须与交给 [EasyTierJni.setTunFd] 的名字一致，
 *   否则 TUN fd 会挂到一个不存在的实例上（失败信息只有一行 `-1`，极难排查）。
 * @param virtualIp 本节点在虚拟网里的地址，不带掩码（掩码固定 /24，与 TUN 一致）。
 * @param networkName / networkSecret 等价于「网络名 + 密码」，玩家填同一组才能互通。
 *     @param serverPort 本机 MC 端口，port_forward 的转发目标。
     * @param peers 引荐节点（共享节点或已知节点），为空时由 EasyTier 的公共发现兜底。
     */
    data class EasyTierSettings(
        val instanceName: String = "minexa",
        val hostname: String = "minexa",
        val virtualIp: String,
        val networkName: String,
        val networkSecret: String,
        val serverPort: Int = 25565,
        val peers: List<String> = DEFAULT_PEERS
    ) {
    /**
     * 生成 EasyTier 的 TOML 配置。
     *
     * 三处都是真机实测踩出来的，改之前先看理由：
     *
     * 1. **`no_tun = true`** —— 不是"不要 TUN"，而是"**core 别自己建** TUN"。
     *    TUN 由 `VpnService` 建、fd 交给 core（`setTunFd`）。反过来的话 core 会去建
     *    一个它没权限创建的设备，实例启动直接失败。
     * 2. **`ipv4` 不带 `/24`** —— 带掩码时 core 解析出的地址找不对接口，
     *    日志是 `can not find interface name by ip local_ip=…`。
     *    掩码交给 `VpnService.Builder.addAddress(ip, 24)`，两边不要各写一份。
     * 3. **`bind_addr` 必须和 MC 服务端用不同的端口**（见 [forwardPort]）。
     *    2026-10-24 用户真机报 `TUN fd 被拒绝（-1）`，两次都栽在这一个端口号上：
     *
     *    - 绑 `0.0.0.0:<服务端端口>`：与 Java 版服务端自己的监听（`server-ip` 留空时
     *      也是 `0.0.0.0:<端口>`）**完全撞车**，两者只能活一个 ——
     *      先开组网 → 服务端绑不上端口、起不来；先开服务端 → port_forward 绑不上，
     *      实例启动后立刻失败，`setTunFd` 返回 `-1` 且**不写任何错误信息**
     *      （v2.7.0 的 FFI 在 `attach_tun_fd` 失败分支里只 `return -1`）。
     *    - 改绑虚拟 IP `10.144.144.x:<端口>` 也不行：**通配地址与具体地址在同一端口上
     *      同样冲突**。真机实测（EasyTier 占着 10.144.144.79:25565 时另起进程绑
     *      0.0.0.0:25565）：
     *      `nc: bind: Address already in use` —— 等于把"服务端起不来"换了个方向。
     *    - 所以只能**换个端口**：转发监听吃 [forwardPort]，服务端继续用原端口，
     *      两者互不相干。玩家看到的地址因此是 `<虚拟IP>:25566`。
     */
    fun toToml(): String = buildString {
        appendLine("instance_name = \"$instanceName\"")
        appendLine("hostname = \"$hostname\"")
        // 不带掩码：掩码由 VpnService.addAddress 提供，两边各写一份必然不一致
        appendLine("ipv4 = \"$virtualIp\"")
        appendLine()
        appendLine("network_identity = { network_name = \"$networkName\", network_secret = \"$networkSecret\" }")
        appendLine()
        peers.filter { it.isNotBlank() }.forEach { peer ->
            appendLine("[[peer]]")
            appendLine("uri = \"$peer\"")
        }
        appendLine()
        appendLine("[[port_forward]]")
        // 必须换端口：同一个端口上一旦有服务端在监听，这里 bind 就会失败
        appendLine("bind_addr = \"0.0.0.0:$forwardPort\"")
        appendLine("dst_addr = \"127.0.0.1:$serverPort\"")
        appendLine("proto = \"tcp\"")
        appendLine()
        appendLine("[flags]")
        // 不是"不要 TUN"，是"core 别自己建" —— TUN 由 VpnService 建、fd 交进来
        appendLine("no_tun = true")
    }

    /**
     * 玩家实际要连的端口：**不是**服务端端口。
     *
     * 原因见 [toToml] 的第 3 条 —— 同一台机器上两个监听不能占同一个端口，
     * 而 25565 归 MC 服务端，转发只能另开一个。
     */
    val forwardPort: Int get() = forwardPortFor(serverPort)

    /** VpnService 需要的地址/掩码对（TUN 与 TOML 必须一致，否则 core 认为虚拟 IP 不可用）。 */
    val tunPrefixLength: Int get() = 24

    companion object {
        /**
         * 默认引荐节点（第一个）。
         *
         * **官方公共节点已经死了**：`tcp://public.easytier.top:11010` 自 2026-02 起社区
         * 普遍反映无响应（域名能解析但 TCP 不通，2026-10-24 实测 timeout），
         * `public.easytier.cn` 则直接 NXDOMAIN。官方没发公告，也没换新域名。
         * 所以默认值换成了社区维护的公共节点（2026-10-24 逐个 TCP 连通性实测通过）：
         *
         * - `et.gbc.moe` / `easytier.weiai.org.cn` / `boi.de5.net`（来源：社区节点列表）
         *
         * 社区节点随时可能变动 —— 再出现"全网连不上"时，先怀疑这里的列表过期，
         * 去 `https://easytier.gd.nkbpal.cn/`（社区公共服务器列表）换新的。
         */
        const val DEFAULT_PUBLIC_PEER = "tcp://et.gbc.moe:11010"

        /** 备用引荐节点。只用来提高"第一次相遇"的成功率，不承担转发。 */
        val FALLBACK_PUBLIC_PEERS = listOf(
            "tcp://easytier.weiai.org.cn:11010",
            "tcp://boi.de5.net:11010"
        )

        /** 默认 peer 列表：主节点 + 备用。 */
        val DEFAULT_PEERS: List<String> = listOf(DEFAULT_PUBLIC_PEER) + FALLBACK_PUBLIC_PEERS

        /**
         * 转发监听的起始端口。
         *
         * 选 25566 而不是 25565 是因为 25565 一定归 MC 服务端 —— 同一台机器上两个监听
         * 占同一个端口必然有一个 bind 失败（通配地址与具体地址同样冲突，真机验过）。
         * 见 [EasyTierSettings.toToml] 第 3 条。
         */
        const val FORWARD_PORT = 25566

        /**
         * 玩家要连的端口。
         *
         * 只在服务端自己就占了 [FORWARD_PORT] 时才顺延一位 —— 那种情况下没有别的选择，
         * 而"顺延一位"至少保证不会和服务端撞车。
         */
        fun forwardPortFor(serverPort: Int): Int =
            if (serverPort == FORWARD_PORT) FORWARD_PORT + 1 else FORWARD_PORT

        /** 玩家地址：`虚拟IP:转发端口`。四处界面/日志都用它，不允许各拼一套。 */
        fun playerAddress(virtualIp: String, serverPort: Int): String {
            if (virtualIp.isBlank()) return ""
            return "$virtualIp:${forwardPortFor(serverPort)}"
        }

        /**
         * 生成网络密钥。
         *
         * 32 位十六进制：EasyTier 对 secret 长度没有硬要求，但它是**唯一**的访问控制手段
         * （没有账号体系、没有 ACL），太短等于没有。
         */
        fun generateSecret(random: kotlin.random.Random = kotlin.random.Random.Default): String =
            (1..32).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")

        /**
         * 按网络名派生本机虚拟 IP（`10.126.126.20`–`10.126.126.219`）。
         *
         * 为什么派生而不是随机：**玩家拿到的说明文字里带着这个地址**。随机的话每次重装/重进
         * 都会变，玩家抄过的地址立刻失效；按网络名派生则同一个网络永远同一个地址。
         *
         * 为什么是 `10.126.126.0/24`：这是 EasyTier **DHCP 的默认池**。2026-10-24 真机实测，
         * 玩家端保持 DHCP 时拿到的就是 `10.126.126.185/24`；而我们最早写死的是
         * `10.144.144.x` —— 结果两边各在一个网段，玩家虚拟网卡只路由 10.126.126.0/24，
         * 发往服务端 10.144.144.x 的包根本进不了隧道，游戏表现就是"连接超时"，
         * 尽管 EasyTier 层面两边早已 p2p 连上（Peer Info 能看到对端）。
         * 服务端的静态 IP 必须落在玩家的 DHCP 池里，两边才有共同语言。
         *
         * 取值范围避开 .1（EasyTier 常把 .1 留给首个节点）与 .2xx 后的段，
         * 也避开 .0/.255 这两个网络/广播地址。DHCP 分配时会避开已在网节点的 IP。
         */
        fun deriveVirtualIp(networkName: String): String {
            var hash = 0
            networkName.forEach { hash = hash * 31 + it.code }
            val last = 20 + (hash.absoluteValue % 200)
            return "10.126.126.$last"
        }
    }
}
