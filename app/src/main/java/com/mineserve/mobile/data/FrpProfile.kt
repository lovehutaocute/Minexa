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
     * 旧版「单份 FRP 配置」→ 多份配置列表的**一次性迁移**。
     *
     * ## 为什么必须有它
     *
     * 改成多份配置之前，用户的工作配置存在 [McConfig.frpConfigText] /
     * `sakuraToken` / `meFrpToken` 这几个字段里。列表初始为空，而
     * `McViewModel.startFrp()` 以「有配置」为前提 —— 不迁移的话，老用户一升级
     * 就会看到原来能用的 FRP 变成「还没有配置」，启动键直接点不动。
     *
     * ## 迁移规则
     *
     * 只迁**真用得起来**的旧配置：
     * - 有 [McConfig.frpConfigText] → 一份自建 frp 配置；
     * - 有 `sakuraToken` **且** `sakuraTunnelId` → 一份 SakuraFrp 配置
     *   （只有令牌、没选隧道本来也启动不起来，迁过来只会是死配置）；
     * - 有 `meFrpToken` **且** `meFrpTunnelId` → 一份 ME Frp 配置。
     *
     * 用户原来正在用的那一份（按 [McConfig.tunnelType] 匹配）排在最前并设为选中，
     * 迁移后卡片上显示的就还是他熟悉的那份。
     *
     * ## 幂等性
     *
     * 迁移结果会清空旧字段，所以落盘一次之后就再也不会命中（`frpProfiles`
     * 已有内容 → 直接返回 null）。这同时堵住了「用户把配置全删了，重启后
     * 旧字段又自己变出一份」这条路。id 也是固定的，即便迁移结果还没落盘
     * 就重启，重新推导出来的仍是同一份，不会每次启动换一个 id。
     *
     * @return 需要迁移时返回迁移后的配置；没有旧配置可迁时返回 null。
     */
    fun migrateLegacy(config: McConfig): McConfig? {
        if (config.frpProfiles.isNotEmpty()) return null

        val built = mutableListOf<FrpProfile>()
        if (config.frpConfigText.isNotBlank()) {
            built += FrpProfile(
                id = LEGACY_ID_TOML,
                name = defaultName(FrpProfileKind.CustomToml, built),
                kind = FrpProfileKind.CustomToml,
                configText = config.frpConfigText
            )
        }
        if (config.sakuraToken.isNotBlank() && config.sakuraTunnelId.isNotBlank()) {
            built += FrpProfile(
                id = LEGACY_ID_SAKURA,
                name = defaultName(FrpProfileKind.SakuraFrp, built),
                kind = FrpProfileKind.SakuraFrp,
                token = config.sakuraToken,
                tunnelId = config.sakuraTunnelId
            )
        }
        if (config.meFrpToken.isNotBlank() && config.meFrpTunnelId.isNotBlank()) {
            built += FrpProfile(
                id = LEGACY_ID_ME,
                name = defaultName(FrpProfileKind.MeFrp, built),
                kind = FrpProfileKind.MeFrp,
                token = config.meFrpToken,
                tunnelId = config.meFrpTunnelId
            )
        }
        if (built.isEmpty()) return null

        // 原来在用的那份提到最前。sortedByDescending 是稳定排序，
        // 匹配不上的那些保持原有顺序。
        val ordered = built.sortedByDescending { kindToTunnelType(it.kind) == config.tunnelType }
        val active = ordered.firstOrNull { kindToTunnelType(it.kind) == config.tunnelType }
            ?: ordered.first()

        return config.copy(
            frpProfiles = ordered,
            activeFrpProfileId = active.id,
            // 清空旧字段：这是「迁移只做一次」的凭据。
            frpConfigText = "",
            sakuraToken = "",
            sakuraTunnelId = "",
            meFrpToken = "",
            meFrpTunnelId = ""
        )
    }

    /** 迁移来源的固定 id（不作为用户可见内容）。 */
    const val LEGACY_ID_TOML = "legacy-toml"
    const val LEGACY_ID_SAKURA = "legacy-sakura"
    const val LEGACY_ID_ME = "legacy-me"

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
