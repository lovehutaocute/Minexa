package com.mineserve.mobile.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 一份 FRP 配置的类型。
 *
 * 三种类型对应三个**已经存在**的后端（[TunnelType.Frp] / [TunnelType.SakuraFrp] /
 * [TunnelType.MeFrp]），本类型只决定「要往哪个后端送、送哪些字段」，
 * 不新增任何运行逻辑。
 */
@Serializable
enum class FrpProfileKind(val displayName: String) {
    /** 自建 frp 服务端：用户自己粘贴 frpc.toml。 */
    CustomToml("自建 frp"),

    /** SakuraFrp 平台：填访问令牌，隧道从平台拉取。 */
    SakuraFrp("SakuraFrp"),

    /** ME Frp 平台：填访问令牌，隧道从平台拉取。 */
    MeFrp("ME Frp")
}

/**
 * 一份可保存、可切换的 FRP 配置。
 *
 * ## 为什么不复用 [McConfig] 里那几个散字段
 *
 * 历史上 FRP 只能存**一份**（`frpConfigText` / `sakuraToken` / `meFrpToken`），
 * 换平台就等于把上一份覆盖掉。要支持「存多份、同一平台存多个令牌」，
 * 必须有独立的列表模型；而运行期的三个后端仍然只认 [McConfig] 的老字段，
 * 所以这里提供 [FrpProfiles.applyTo] 做一次「配置 → 老字段」的投影，
 * 后端一行都不用改。
 *
 * ## 区分方式
 *
 * 靠 [name]（用户自定义）。**允许重名**——重名只在编辑页给一句提醒，
 * 不拦截：用户可能有两台机器叫同一个名字，强行拦截只会让人编名字。
 * 真正唯一的是 [id]。
 */
@Serializable
data class FrpProfile(
    /** 稳定唯一标识（UUID），删除/改名都不影响它。 */
    val id: String,
    /** 用户自定义名称，列表里靠它区分。 */
    val name: String,
    val kind: FrpProfileKind,
    /** [FrpProfileKind.CustomToml]：完整 frpc.toml 文本。 */
    val configText: String = "",
    /** [FrpProfileKind.SakuraFrp] / [FrpProfileKind.MeFrp]：访问令牌。 */
    val token: String = "",
    /** [FrpProfileKind.SakuraFrp] / [FrpProfileKind.MeFrp]：选中的隧道 ID。 */
    val tunnelId: String = "",
    /**
     * 平台隧道的可读描述（如「节点 3 · tcp · 25565」），**仅供列表展示**。
     * 不参与启动，拉取隧道列表时会刷新。
     */
    val tunnelLabel: String = ""
)

/**
 * [FrpProfile] 的纯函数集合：不依赖 Android，便于单元测试。
 */
object FrpProfiles {

    /** 新建一个空配置（[name] 已按类型给出不重名的默认值）。 */
    fun create(
        kind: FrpProfileKind,
        existing: List<FrpProfile>,
        id: String = UUID.randomUUID().toString()
    ): FrpProfile = FrpProfile(id = id, name = defaultName(kind, existing), kind = kind)

    /** 该类型对应的运行后端。 */
    fun kindToTunnelType(kind: FrpProfileKind): TunnelType = when (kind) {
        FrpProfileKind.CustomToml -> TunnelType.Frp
        FrpProfileKind.SakuraFrp -> TunnelType.SakuraFrp
        FrpProfileKind.MeFrp -> TunnelType.MeFrp
    }

    /**
     * 当前生效的配置。
     *
     * [McConfig.activeFrpProfileId] 指向的配置不存在时（被删掉、老配置残留），
     * 退回列表第一份——否则会出现「列表里有配置，卡片却说没有」。
     */
    fun active(config: McConfig): FrpProfile? =
        config.frpProfiles.firstOrNull { it.id == config.activeFrpProfileId }
            ?: config.frpProfiles.firstOrNull()

    /**
     * 把一份配置投影成后端认识的老字段。
     *
     * 这是本特性**唯一**与运行期对接的地方：后端仍然只读 [McConfig] 的老字段，
     * 因此 `FrpBackend` / `SakuraFrpBackend` / `MeFrpBackend` 无需任何改动。
     * 投影是**纯替换**：只写本类型用得到的字段，其余字段原样透传。
     */
    fun applyTo(profile: FrpProfile, base: McConfig): McConfig = when (profile.kind) {
        FrpProfileKind.CustomToml -> base.copy(
            tunnelType = TunnelType.Frp,
            frpConfigText = profile.configText
        )

        FrpProfileKind.SakuraFrp -> base.copy(
            tunnelType = TunnelType.SakuraFrp,
            sakuraToken = profile.token,
            sakuraTunnelId = profile.tunnelId
        )

        FrpProfileKind.MeFrp -> base.copy(
            tunnelType = TunnelType.MeFrp,
            meFrpToken = profile.token,
            meFrpTunnelId = profile.tunnelId
        )
    }

    /** 卡片与列表里显示的标题：「名字 · 平台」。 */
    fun label(profile: FrpProfile): String = "${profile.name} · ${profile.kind.displayName}"

    /** 该类型下不重名的默认名称：`SakuraFrp 配置`、`SakuraFrp 配置 2`…… */
    fun defaultName(kind: FrpProfileKind, existing: List<FrpProfile>): String {
        val base = when (kind) {
            FrpProfileKind.CustomToml -> "自建 frp 配置"
            FrpProfileKind.SakuraFrp -> "SakuraFrp 配置"
            FrpProfileKind.MeFrp -> "ME Frp 配置"
        }
        if (existing.none { it.name == base }) return base
        var n = 2
        while (existing.any { it.name == "$base $n" }) n++
        return "$base $n"
    }

    /** 除自己以外是否已有同名配置（编辑页给提醒用，不拦截保存）。 */
    fun isNameTaken(name: String, existing: List<FrpProfile>, selfId: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return existing.any { it.id != selfId && it.name == trimmed }
    }

    /** 保存前的完整性检查，返回 null 表示可以保存。 */
    fun validationError(profile: FrpProfile): String? = when (profile.kind) {
        FrpProfileKind.CustomToml ->
            if (profile.configText.isBlank()) "请先填写 frpc.toml 内容" else null

        FrpProfileKind.SakuraFrp, FrpProfileKind.MeFrp -> when {
            profile.token.isBlank() -> "请先填写访问令牌（Token）"
            profile.tunnelId.isBlank() -> "请先拉取并选中一条隧道"
            else -> null
        }
    }

    /**
     * 把平台隧道对象压成一行可读描述（列表展示用）。
     *
     * 放在这里而不是界面里，是为了让「列表里看到的东西」和「启动时用的东西」
     * 出自同一份数据，避免界面显示 A、实际启动 B。
     */
    fun tunnelLabel(
        nodeName: String?,
        type: String,
        localIp: String,
        localPort: Int,
        remote: String
    ): String = buildString {
        if (!nodeName.isNullOrBlank()) append("节点 $nodeName · ")
        append("$type · 本地 $localIp:$localPort · ")
        append(remote.ifBlank { "自动分配" })
    }
}
