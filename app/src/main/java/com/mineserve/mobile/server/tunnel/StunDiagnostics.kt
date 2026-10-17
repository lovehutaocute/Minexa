package com.mineserve.mobile.server.tunnel

/**
 * STUN 直连的可用性结论。
 *
 * 之所以要有这个东西：此前探测失败时界面只能显示一句"隧道启动失败"，
 * 用户无从判断是自己网络的问题、服务器列表的问题，还是根本不该用 STUN。
 * 把结论做成枚举后，界面可以给出「为什么不行 + 该换什么」的确定说法。
 */
enum class StunVerdict {
    /** 尚未探测过。 */
    Unknown,

    /** 探测成功，且映射地址是公网地址 —— 全锥形 NAT 场景下最理想的结果。 */
    PublicDirect,

    /** 探测到了映射，但地址是内网 / CGNAT —— 外网必然连不进来，直连不可用。 */
    NonPublicAddress,

    /** 所有 STUN 服务器都不可用（连不上、不回包、被拒）。 */
    Unreachable,

    /** 拿到了公网映射，但没能在该端口上建立监听，该地址实际不可用。 */
    BindFailed
}

/**
 * 探测失败的原因分类。
 *
 * 同类失败应当有同样的处置方式：例如 [Unreachable] 值得重试（可能是网络瞬断），
 * 而 [NonPublicAddress] 是确定性结论，重试 3 轮只是白等 45 秒。
 */
enum class StunFailCategory {
    /** 未失败。 */
    None,

    /** TCP 都连不上：DNS 失败、被防火墙拦、服务器下线。 */
    Unreachable,

    /** TCP 能连上但不回 STUN 响应 —— 典型的「只支持 UDP 的 STUN 服务器」。 */
    NoResponse,

    /** 服务器回了 ERROR-CODE。 */
    ServerRejected,

    /** 响应里没有可用的映射地址。 */
    NoMapping,

    /** 有响应，但地址不是公网地址（内网 / CGNAT / 保留段）。 */
    NonPublicAddress,

    /** 映射端口无法监听。 */
    BindFailed
}

/**
 * STUN 直连的运行期诊断快照，供「STUN 直连」卡片展示。
 *
 * 全部字段都由 [StunTunnelBackend] 在运行中填充；界面只做展示与文案拼装，
 * 不在这里做任何网络判定，避免「后端说通、界面说不行」这类自相矛盾。
 */
data class StunDiagnostics(
    /** 当前结论。 */
    val verdict: StunVerdict = StunVerdict.Unknown,

    /** 最近一次探测失败的原因分类（成功时为 [StunFailCategory.None]）。 */
    val failCategory: StunFailCategory = StunFailCategory.None,

    /** 探测到的公网映射地址 `host:port`；未成功时为空。 */
    val publicAddress: String = "",

    /** 映射端口（NAT 内侧端点，也是监听端口）。 */
    val localPort: Int = 0,

    /**
     * 探测到但**不是公网**的地址。用于把「为什么不行」说具体：
     * 「运营商给了 100.64.3.7:51234，这是 CGNAT 段」比「不可用」有用得多。
     */
    val nonPublicAddress: String = "",

    /** 最近一次探测耗时（毫秒）。并行探测后这一项应当明显下降。 */
    val probeDurationMs: Long = 0,

    /** 最近一次探测尝试的服务器台数。 */
    val serversTried: Int = 0,

    /** 其中 TCP 连接成功（不代表取到了映射）的台数。 */
    val serversConnected: Int = 0,

    /** 保活期间公网地址是否稳定（端口未变）。 */
    val portStable: Boolean = true,

    /** 本次会话内公网地址变化的累计次数。 */
    val addressChanges: Int = 0,

    /** 最近一次地址变化的时刻（毫秒时间戳），0 表示从未变化。 */
    val lastAddressChangeAt: Long = 0,

    /** 因「入站后迟迟不发数据」被回收的连接数（S0.1 的防护效果）。 */
    val idleTimeouts: Int = 0,

    /** 因达到并发上限被拒绝的连接数。 */
    val limitRejects: Int = 0,

    /** 当前活跃连接数。 */
    val activeConnections: Int = 0
) {
    /** 是否有可供界面展示的结论（未探测时为 false）。 */
    val hasResult: Boolean get() = verdict != StunVerdict.Unknown
}
