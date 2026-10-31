package com.mineserve.mobile.server

import com.mineserve.mobile.R
import com.mineserve.mobile.data.ServerCore

/**
 * server.properties / pnx.yml 的**配置项元数据字典**。
 *
 * 为什么需要它：属性页原先是一张写死的表单，只认几十个已知 key。换到 Paper、
 * Purpur、Fabric、BDS 之后，文件里真实存在的 key 与写死的表对不上 —— 多出来的
 * 配置项看不到（用户只能退出 App 手改文件），写死的项又可能根本不存在于该核心
 * （误改会写进一条服务端看不懂的行）。
 *
 * 现在的做法是：**以文件的真实内容为准**生成表单，字典只负责「怎么显示这一行」
 * —— 中文名、说明、分组、控件类型、取值范围。字典里没有的 key 不会被丢弃，
 * 而是落到 [PropertyGroup.P3] 兜底区，按原始 key 渲染，保证任何核心的任意
 * 配置项都能读、能改。
 *
 * 本文件不依赖 Android 运行时（只用 R 里的整数 id），可直接单元测试。
 */

// ── 分组（优先级从高到低） ────────────────────────────────────────

/**
 * 配置项分组，同时是**渲染优先级**。
 *
 * 顺序即页面顺序：高频项在最上面，生僻项压到最后；未收录项单独一档，
 * 避免这类条目挤占前面高频设置的位置。
 */
enum class PropertyGroup(val labelRes: Int) {
    /** 核心高频项：开服必调的那些。 */
    P0(R.string.prop_ui_grp_p0),

    /** 世界与玩法项：种子、刷怪、资源包、权限等级。 */
    P1(R.string.prop_ui_grp_p1),

    /** 网络与性能项：端口、压缩、线程、校验参数。 */
    P2(R.string.prop_ui_grp_p2),

    /** 字典未收录的兜底项：按原始 key 渲染。 */
    P3(R.string.prop_ui_grp_p3)
}

// ── 控件类型 ──────────────────────────────────────────────────────

/** 单个配置项用哪种控件渲染。 */
enum class PropertyUiType {
    /** 布尔开关（`true`/`false`，兼容 `on`/`off`）。 */
    Toggle,

    /** 枚举分段选择（`gamemode`、`difficulty`、权限等级…）。 */
    Segmented,

    /** 滑块 + 数值显示（视距、模拟距离、出生点保护）。 */
    Slider,

    /** 端口数字输入，范围 1..65535 并做校验。 */
    Port,

    /** 整数输入。 */
    Number,

    /** 单行文本。 */
    Text,

    /** 多行文本（MOTD、过滤配置等）。 */
    Multiline
}

/**
 * 运行平台风味。
 *
 * 同一个 key 在不同核心下取值集合并不一致：`gamemode` 在 Java 版有 `spectator`，
 * BDS 只认 `survival`/`creative`/`adventure`；`view-distance` 在 Java 版可低到 2，
 * BDS 的下限是 5。字典里为这类差异保留 [PropertyMeta.bedrockOptions] /
 * [PropertyMeta.bedrockRange] 两个覆盖位，由本枚举决定取哪一份。
 */
enum class PropertyFlavor {
    /** Java 版系（Paper/Purpur/Fabric/Vanilla…）与 PocketMine 等以 Java 版语义为主的实现。 */
    Java,

    /** 官方基岩版专用服务器（BDS）。 */
    Bedrock,

    /** PowerNukkitX（配置落在 pnx.yml，键名与 Java 版大体一致）。 */
    Pnx,

    /** 拿不到核心类型时的兜底，按 Java 版渲染。 */
    Unknown;

    companion object {
        fun of(core: ServerCore?): PropertyFlavor = when (core) {
            null -> Unknown
            ServerCore.Bds -> Bedrock
            ServerCore.PowerNukkitX -> Pnx
            ServerCore.Allay, ServerCore.Unknown -> Unknown
            // PocketMine 虽是基岩核心，但它的 server.properties 语义更接近 Java 版
            // （difficulty/gamemode 同时接受单词与数字），按 Java 渲染不会漏项，
            // 这里不为它单独开一档，避免多一份需要维护的取值表。
            else -> Java
        }
    }
}

// ── 元数据模型 ────────────────────────────────────────────────────

/**
 * 枚举型配置的一个候选值。
 *
 * [aliases] 是为了兼容「同一含义的多种写法」：`difficulty=2` 与
 * `difficulty=normal` 在 Java 版与 BDS 上都合法，历史配置文件里两种写法都存在。
 * 匹配时 aliases 也参与，写回时统一写成 [value] —— 界面不会因为用户没动它
 * 就把文件里的数字偷偷改成单词（未修改的项根本不会被提交，见 [diff]）。
 */
data class PropertyOption(
    val value: String,
    val labelRes: Int,
    val aliases: List<String> = emptyList()
) {
    fun matches(raw: String): Boolean {
        val v = raw.trim()
        if (v.equals(value, ignoreCase = true)) return true
        return aliases.any { it.equals(v, ignoreCase = true) }
    }
}

/**
 * 一个配置项的展示元数据。
 *
 * [titleRes]/[descRes] 为 0 表示没有对应文案，UI 侧回退到原始 key。
 * [defaultValue] 只在「该项缺失、需要给控件一个初始值」时使用 —— 本页不会把
 * 未在文件中出现的项写进文件。
 */
data class PropertyMeta(
    val key: String,
    val titleRes: Int,
    val descRes: Int = 0,
    val group: PropertyGroup = PropertyGroup.P2,
    val uiType: PropertyUiType = PropertyUiType.Text,
    val defaultValue: String = "",
    val options: List<PropertyOption> = emptyList(),
    val range: IntRange? = null,
    /** 是否允许小数（部分阈值项是 `0.3` 这种浮点，按整数过滤会把值截坏）。 */
    val decimal: Boolean = false,
    /** 基岩版（BDS）覆盖用：null 表示沿用 [options]。 */
    val bedrockOptions: List<PropertyOption>? = null,
    /** 基岩版（BDS）覆盖用：null 表示沿用 [range]。 */
    val bedrockRange: IntRange? = null
)

/** 解析结果：字典命中与否、当前值、按风味收敛后的取值表 / 取值范围。 */
data class ResolvedProperty(
    val key: String,
    val meta: PropertyMeta,
    val value: String,
    /** 是否命中字典；false 即 P3 兜底项（标题用原始 key）。 */
    val known: Boolean,
    val options: List<PropertyOption>,
    val range: IntRange?,
    val uiType: PropertyUiType
) {
    /** 数字输入是否允许小数。 */
    val decimal: Boolean get() = meta.decimal
}

// ── 字典 ──────────────────────────────────────────────────────────

object PropertyCatalog {

    /**
     * 顶部「展示信息」卡片的专属键。
     *
     * 这几个键不走分组列表：MOTD 是玩家在服务器列表里唯一能看到的字，
     * 位置固定在最上面比混在列表里更合理。
     */
    val heroKeys: Set<String> = setOf("motd", "server-name", "sub-motd")

    private val list: List<PropertyMeta> = buildList {
        // ══ P0 核心高频项 ══════════════════════════════════════════
        add(text("motd", R.string.s861, R.string.s862, PropertyGroup.P0, "A Minecraft Server"))
        add(text("server-name", R.string.prop_bds_server_name, R.string.prop_bds_server_name_desc, PropertyGroup.P0, "Dedicated Server"))
        add(text("sub-motd", R.string.pnx_sub_motd, 0, PropertyGroup.P0, "powernukkitx.org"))
        add(port("server-port", R.string.s857, R.string.s858, PropertyGroup.P0, "25565"))
        add(number("max-players", R.string.s855, R.string.s856, PropertyGroup.P0, "20"))
        add(toggle("online-mode", R.string.s851, R.string.s852, PropertyGroup.P0, "true"))
        add(
            seg(
                "gamemode", R.string.s730, R.string.s849, PropertyGroup.P0, "survival",
                listOf(
                    option("survival", R.string.s281, "0"),
                    option("creative", R.string.s282, "1"),
                    option("adventure", R.string.s283, "2"),
                    option("spectator", R.string.s284, "3")
                ),
                bedrockOptions = listOf(
                    option("survival", R.string.s281, "0"),
                    option("creative", R.string.s282, "1"),
                    option("adventure", R.string.s283, "2")
                )
            )
        )
        add(
            seg(
                "difficulty", R.string.s843, R.string.s844, PropertyGroup.P0, "easy",
                listOf(
                    option("peaceful", R.string.s845, "0"),
                    option("easy", R.string.s846, "1"),
                    option("normal", R.string.s847, "2"),
                    option("hard", R.string.s848, "3")
                )
            )
        )
        add(toggle("pvp", R.string.prop_pvp, R.string.s850, PropertyGroup.P0, "true"))
        add(toggle("white-list", R.string.s659, R.string.s703, PropertyGroup.P0))
        add(toggle("allow-list", R.string.prop_bds_allow_list, R.string.prop_bds_allow_list_desc, PropertyGroup.P0))
        add(toggle("allow-cheats", R.string.prop_bds_allow_cheats, R.string.prop_bds_allow_cheats_desc, PropertyGroup.P0))
        add(slider("view-distance", R.string.s863, R.string.s864, PropertyGroup.P0, 2, 32, "10", bedrockRange = 5..32))
        add(slider("simulation-distance", R.string.s865, R.string.s866, PropertyGroup.P0, 2, 32, "10"))
        add(slider("tick-distance", R.string.prop_bds_tick_distance, R.string.prop_bds_tick_distance_desc, PropertyGroup.P0, 4, 12, "4"))
        add(slider("spawn-protection", R.string.s869, R.string.s870, PropertyGroup.P0, 0, 64, "16"))
        add(text("level-name", R.string.s887, R.string.s888, PropertyGroup.P0, "world"))
        add(
            seg(
                "default-player-permission-level",
                R.string.prop_bds_perm_level, R.string.prop_bds_perm_level_desc, PropertyGroup.P0, "member",
                listOf(
                    option("visitor", R.string.prop_opt_visitor),
                    option("member", R.string.prop_opt_member),
                    option("operator", R.string.prop_opt_operator)
                )
            )
        )
        add(toggle("enforce-secure-profile", R.string.prop_java_secure_profile, R.string.prop_java_secure_profile_desc, PropertyGroup.P0))
        add(toggle("allow-flight", R.string.s875, R.string.s876, PropertyGroup.P0))
        add(toggle("hardcore", R.string.s871, R.string.s872, PropertyGroup.P0))
        add(toggle("enable-command-block", R.string.s873, R.string.s874, PropertyGroup.P0))
        add(toggle("allow-nether", R.string.s877, R.string.s878, PropertyGroup.P0, "true"))
        add(toggle("force-gamemode", R.string.s897, R.string.s898, PropertyGroup.P0))
        add(text("server-ip", R.string.s859, R.string.s860, PropertyGroup.P0))

        // ══ P1 世界与玩法项 ════════════════════════════════════════
        add(text("level-seed", R.string.s889, R.string.s890, PropertyGroup.P1))
        add(
            seg(
                "level-type", R.string.s891, R.string.s892, PropertyGroup.P1, "minecraft:normal",
                listOf(
                    option("minecraft:normal", R.string.s847),
                    option("minecraft:flat", R.string.s893),
                    option("minecraft:large_biomes", R.string.s894),
                    option("minecraft:amplified", R.string.s895),
                    option("minecraft:single_biome_surface", R.string.s896)
                )
            )
        )
        add(toggle("generate-structures", R.string.s885, R.string.s886, PropertyGroup.P1, "true"))
        add(toggle("spawn-animals", R.string.s879, R.string.s880, PropertyGroup.P1, "true"))
        add(toggle("spawn-monsters", R.string.s881, R.string.s882, PropertyGroup.P1, "true"))
        add(toggle("spawn-npcs", R.string.s883, R.string.s884, PropertyGroup.P1, "true"))
        add(toggle("allow-the-end", R.string.pnx_allow_end, 0, PropertyGroup.P1, "true"))
        add(toggle("weather-cycle", R.string.pnx_weather, 0, PropertyGroup.P1, "true"))
        add(toggle("daylight-cycle", R.string.pnx_daylight, 0, PropertyGroup.P1, "true"))
        add(toggle("hunger", R.string.pnx_hunger, 0, PropertyGroup.P1, "true"))
        add(toggle("auto-save", R.string.pnx_auto_save, 0, PropertyGroup.P1, "true"))
        add(text("language", R.string.pnx_language, 0, PropertyGroup.P1, "eng"))
        add(number("max-world-size", R.string.s867, R.string.s868, PropertyGroup.P1, "29999984"))
        add(text("generator-settings", R.string.s945, R.string.s946, PropertyGroup.P1, "{}"))
        add(text("initial-enabled-packs", R.string.s949, R.string.s950, PropertyGroup.P1, "vanilla"))
        add(text("initial-disabled-packs", R.string.s947, R.string.s948, PropertyGroup.P1))
        add(toggle("require-resource-pack", R.string.s911, R.string.s912, PropertyGroup.P1))
        add(text("resource-pack", R.string.s913, R.string.s914, PropertyGroup.P1))
        add(text("resource-pack-id", R.string.s981, R.string.s982, PropertyGroup.P1))
        add(text("resource-pack-sha1", R.string.s985, R.string.s986, PropertyGroup.P1))
        add(multiline("resource-pack-prompt", R.string.s983, R.string.s984, PropertyGroup.P1))
        add(toggle("texturepack-required", R.string.prop_bds_texturepack, R.string.prop_bds_texturepack_desc, PropertyGroup.P1))
        add(toggle("disable-player-interaction", R.string.prop_bds_disable_interaction, R.string.prop_bds_disable_interaction_desc, PropertyGroup.P1))
        add(toggle("enforce-whitelist", R.string.s853, R.string.s854, PropertyGroup.P1))
        add(number("op-permission-level", R.string.s905, R.string.s906, PropertyGroup.P1, "4", 0..4))
        add(number("function-permission-level", R.string.s907, R.string.s908, PropertyGroup.P1, "2", 1..4))
        add(toggle("hide-online-players", R.string.s903, R.string.s904, PropertyGroup.P1))
        add(toggle("broadcast-console-to-ops", R.string.s923, R.string.s924, PropertyGroup.P1, "true"))
        add(toggle("broadcast-rcon-to-ops", R.string.s925, R.string.s926, PropertyGroup.P1, "true"))
        add(
            seg(
                "chat-restriction", R.string.prop_bds_chat_restriction,
                R.string.prop_bds_chat_restriction_desc, PropertyGroup.P1, "None",
                listOf(
                    option("None", R.string.prop_opt_none),
                    option("Dropped", R.string.prop_opt_dropped),
                    option("Disabled", R.string.prop_opt_disabled)
                )
            )
        )
        add(toggle("correct-player-movement", R.string.prop_bds_correct_movement, R.string.prop_bds_correct_movement_desc, PropertyGroup.P1))
        add(toggle("server-authoritative-block-breaking", R.string.prop_bds_block_breaking, R.string.prop_bds_block_breaking_desc, PropertyGroup.P1))

        // ══ P2 网络与性能项 ════════════════════════════════════════
        add(port("server-portv6", R.string.prop_bds_portv6, R.string.prop_bds_portv6_desc, PropertyGroup.P2, "19133"))
        add(port("query.port", R.string.s973, R.string.s974, PropertyGroup.P2, "25565"))
        add(port("rcon.port", R.string.s977, R.string.s978, PropertyGroup.P2, "25575"))
        add(text("rcon.password", R.string.s975, R.string.s976, PropertyGroup.P2))
        add(toggle("enable-query", R.string.s939, R.string.s940, PropertyGroup.P2))
        add(toggle("enable-rcon", R.string.s941, R.string.s942, PropertyGroup.P2))
        add(toggle("enable-status", R.string.s901, R.string.s902, PropertyGroup.P2, "true"))
        add(number("network-compression-threshold", R.string.s917, R.string.s918, PropertyGroup.P2, "256"))
        add(number("compression-threshold", R.string.prop_bds_compression, R.string.prop_bds_compression_desc, PropertyGroup.P2, "1"))
        add(number("rate-limit", R.string.s909, R.string.s910, PropertyGroup.P2))
        add(number("max-tick-time", R.string.s899, R.string.s900, PropertyGroup.P2, "60000"))
        add(number("max-chained-neighbor-updates", R.string.s919, R.string.s920, PropertyGroup.P2, "1000000"))
        add(number("entity-broadcast-range-percentage", R.string.s943, R.string.s944, PropertyGroup.P2, "100", 10..100))
        add(number("max-threads", R.string.prop_bds_max_threads, R.string.prop_bds_max_threads_desc, PropertyGroup.P2, "0"))
        add(number("player-idle-timeout", R.string.s915, R.string.s916, PropertyGroup.P2))
        add(number("status-heartbeat-interval", R.string.s987, R.string.s988, PropertyGroup.P2))
        add(number("base-tps", R.string.pnx_base_tps, 0, PropertyGroup.P2, "20"))
        add(
            seg(
                "server-authoritative-movement", R.string.prop_bds_movement,
                R.string.prop_bds_movement_desc, PropertyGroup.P2, "server-auth",
                listOf(
                    option("server-auth", R.string.prop_opt_server_auth),
                    option("client-auth", R.string.prop_opt_client_auth),
                    option("server-auth-with-rewind", R.string.prop_opt_rewind)
                )
            )
        )
        add(number("player-movement-score-threshold", R.string.prop_bds_mv_score, R.string.prop_bds_mv_score_desc, PropertyGroup.P2, "20"))
        add(number("player-movement-distance-threshold", R.string.prop_bds_mv_distance, R.string.prop_bds_mv_distance_desc, PropertyGroup.P2, "0.3", decimal = true))
        add(number("player-movement-duration-threshold-in-ms", R.string.prop_bds_mv_duration, R.string.prop_bds_mv_duration_desc, PropertyGroup.P2, "500"))
        add(number("player-movement-action-direction-threshold", R.string.prop_bds_mv_direction, R.string.prop_bds_mv_direction_desc, PropertyGroup.P2, "0.85", decimal = true))
        add(toggle("sync-chunk-writes", R.string.s989, R.string.s990, PropertyGroup.P2, "true"))
        add(toggle("use-native-transport", R.string.s995, R.string.s996, PropertyGroup.P2, "true"))
        add(toggle("prevent-proxy-connections", R.string.s971, R.string.s972, PropertyGroup.P2))
        add(toggle("log-ips", R.string.s951, R.string.s952, PropertyGroup.P2, "true"))
        add(text("region-file-compression", R.string.s979, R.string.s980, PropertyGroup.P2, "deflate"))
        add(toggle("debug", R.string.s933, R.string.s934, PropertyGroup.P2))
        add(toggle("enable-jmx-monitoring", R.string.s937, R.string.s938, PropertyGroup.P2))
        add(toggle("accepts-transfers", R.string.s921, R.string.s922, PropertyGroup.P2))
        add(text("bug-report-link", R.string.s927, R.string.s928, PropertyGroup.P2))
        add(toggle("enable-code-of-conduct", R.string.s935, R.string.s936, PropertyGroup.P2))
        add(multiline("text-filtering-config", R.string.s991, R.string.s992, PropertyGroup.P2))
        add(number("text-filtering-version", R.string.s993, R.string.s994, PropertyGroup.P2))
        add(number("pause-when-empty-seconds", R.string.s969, R.string.s970, PropertyGroup.P2, "-1"))
        add(number("chat-spam-threshold-seconds", R.string.s929, R.string.s930, PropertyGroup.P2, "10"))
        add(number("command-spam-threshold-seconds", R.string.s931, R.string.s932, PropertyGroup.P2, "10"))
        add(toggle("content-log-file-enabled", R.string.prop_bds_content_log, R.string.prop_bds_content_log_desc, PropertyGroup.P2))
        add(toggle("emit-server-telemetry", R.string.prop_bds_telemetry, R.string.prop_bds_telemetry_desc, PropertyGroup.P2))
        add(toggle("block-network-ids-are-hashes", R.string.prop_bds_block_hash, R.string.prop_bds_block_hash_desc, PropertyGroup.P2, "true"))
        add(toggle("client-side-chunk-generation-enabled", R.string.prop_bds_client_chunk, R.string.prop_bds_client_chunk_desc, PropertyGroup.P2, "true"))
        add(toggle("enable-lan-visibility", R.string.prop_bds_lan_visibility, R.string.prop_bds_lan_visibility_desc, PropertyGroup.P2))
        add(toggle("management-server-enabled", R.string.s955, R.string.s956, PropertyGroup.P2))
        add(text("management-server-host", R.string.s957, R.string.s958, PropertyGroup.P2, "localhost"))
        add(port("management-server-port", R.string.s959, R.string.s960, PropertyGroup.P2))
        add(text("management-server-secret", R.string.s961, R.string.s962, PropertyGroup.P2))
        add(text("management-server-allowed-origins", R.string.s953, R.string.s954, PropertyGroup.P2))
        add(toggle("management-server-tls-enabled", R.string.s963, R.string.s964, PropertyGroup.P2, "true"))
        add(text("management-server-tls-keystore", R.string.s965, R.string.s966, PropertyGroup.P2))
        add(text("management-server-tls-keystore-password", R.string.s967, R.string.s968, PropertyGroup.P2))

        // ══ BDS 1.21.5x 新增项（以官方 server.properties 全量对齐）═══════
        // 这一批来自官方基岩版服务端自带的 server.properties，之前只收了其中
        // 一半。官方注释里标明的取值集合都在这里落成枚举或开关，避免用户对着
        // "observe / enforce" 这种词猜含义。
        add(
            toggle(
                "disable-custom-skins", R.string.prop_bds_disable_skins,
                R.string.prop_bds_disable_skins_desc, PropertyGroup.P1
            )
        )
        add(
            toggle(
                "disable-persona", R.string.prop_bds_disable_persona,
                R.string.prop_bds_disable_persona_desc, PropertyGroup.P1
            )
        )
        add(
            seg(
                "transport", R.string.prop_bds_transport, R.string.prop_bds_transport_desc,
                PropertyGroup.P2, "raknet",
                listOf(
                    option("raknet", R.string.prop_opt_raknet),
                    option("nethernet", R.string.prop_opt_nethernet)
                )
            )
        )
        add(
            text(
                "server-udp-ports", R.string.prop_bds_udp_ports,
                R.string.prop_bds_udp_ports_desc, PropertyGroup.P2
            )
        )
        add(
            text(
                "persistent-ticking-area-max-count", R.string.prop_bds_ticking_count,
                R.string.prop_bds_ticking_count_desc, PropertyGroup.P2
            )
        )
        add(
            text(
                "persistent-ticking-area-max-chunks", R.string.prop_bds_ticking_chunks,
                R.string.prop_bds_ticking_chunks_desc, PropertyGroup.P2
            )
        )
        add(
            text(
                "transient-ticking-area-max-chunks", R.string.prop_bds_transient_chunks,
                R.string.prop_bds_transient_chunks_desc, PropertyGroup.P2
            )
        )
        add(
            toggle(
                "content-log-console-output-enabled", R.string.prop_bds_content_log_console,
                R.string.prop_bds_content_log_console_desc, PropertyGroup.P2
            )
        )
        add(
            seg(
                "content-log-level", R.string.prop_bds_content_log_level,
                R.string.prop_bds_content_log_level_desc, PropertyGroup.P2, "info",
                listOf(
                    option("error", R.string.prop_opt_log_error),
                    option("warning", R.string.prop_opt_log_warn),
                    option("info", R.string.prop_opt_log_info),
                    option("verbose", R.string.prop_opt_log_verbose)
                )
            )
        )
        add(
            seg(
                "compression-algorithm", R.string.prop_bds_compression_algo,
                R.string.prop_bds_compression_algo_desc, PropertyGroup.P2, "zlib",
                listOf(
                    option("zlib", R.string.prop_opt_zlib),
                    option("snappy", R.string.prop_opt_snappy)
                )
            )
        )
        add(
            toggle(
                "server-authoritative-movement-strict", R.string.prop_bds_mv_strict,
                R.string.prop_bds_mv_strict_desc, PropertyGroup.P2
            )
        )
        add(
            toggle(
                "server-authoritative-dismount-strict", R.string.prop_bds_dismount_strict,
                R.string.prop_bds_dismount_strict_desc, PropertyGroup.P2
            )
        )
        add(
            toggle(
                "server-authoritative-entity-interactions-strict", R.string.prop_bds_entity_strict,
                R.string.prop_bds_entity_strict_desc, PropertyGroup.P2
            )
        )
        add(
            seg(
                "server-authoritative-entity-attack-request-validation",
                R.string.prop_bds_attack_request, R.string.prop_bds_attack_request_desc,
                PropertyGroup.P2, "disabled",
                listOf(
                    option("disabled", R.string.prop_opt_disabled),
                    option("observe", R.string.prop_opt_observe),
                    option("enforce", R.string.prop_opt_enforce)
                )
            )
        )
        add(
            seg(
                "server-authoritative-entity-attack-range-validation",
                R.string.prop_bds_attack_range, R.string.prop_bds_attack_range_desc,
                PropertyGroup.P2, "disabled",
                listOf(
                    option("disabled", R.string.prop_opt_disabled),
                    option("observe", R.string.prop_opt_observe),
                    option("enforce", R.string.prop_opt_enforce)
                )
            )
        )
        add(
            number(
                "server-authoritative-entity-attack-range-tolerance",
                R.string.prop_bds_attack_tolerance, R.string.prop_bds_attack_tolerance_desc,
                PropertyGroup.P2, "0.5", decimal = true
            )
        )
        add(
            number(
                "player-position-acceptance-threshold", R.string.prop_bds_position_threshold,
                R.string.prop_bds_position_threshold_desc, PropertyGroup.P2, "0.5", decimal = true
            )
        )
        add(
            number(
                "server-authoritative-block-breaking-pick-range-scalar",
                R.string.prop_bds_pick_range, R.string.prop_bds_pick_range_desc,
                PropertyGroup.P2, "1.5", decimal = true
            )
        )
        add(
            text(
                "server-build-radius-ratio", R.string.prop_bds_build_radius,
                R.string.prop_bds_build_radius_desc, PropertyGroup.P2, "Disabled"
            )
        )
        add(
            toggle(
                "allow-outbound-script-debugging", R.string.prop_bds_script_out,
                R.string.prop_bds_script_out_desc, PropertyGroup.P2
            )
        )
        add(
            toggle(
                "allow-inbound-script-debugging", R.string.prop_bds_script_in,
                R.string.prop_bds_script_in_desc, PropertyGroup.P2
            )
        )
        add(
            seg(
                "script-debugger-auto-attach", R.string.prop_bds_script_attach,
                R.string.prop_bds_script_attach_desc, PropertyGroup.P2, "disabled",
                listOf(
                    option("disabled", R.string.prop_opt_disabled),
                    option("connect", R.string.prop_opt_connect),
                    option("listen", R.string.prop_opt_listen)
                )
            )
        )
    }

    /** key → 元数据。重复 key 以先写入的为准（本表内无重复，构建期即可发现）。 */
    val byKey: Map<String, PropertyMeta> = list.associateBy { it.key }

    val size: Int get() = list.size

    /** 字典中是否收录该 key。 */
    fun contains(key: String): Boolean = byKey.containsKey(key)

    /**
     * 解析一行配置：命中字典就用字典，否则按值推断控件类型后落进 P3。
     *
     * @param rawValue 文件里的原始值（未 trim 的判定在 [ServerProperties] 已完成）
     * @param flavor 当前核心风味，决定枚举取值表与滑块范围取哪一份
     */
    fun resolve(key: String, rawValue: String, flavor: PropertyFlavor): ResolvedProperty {
        val meta = byKey[key]
        if (meta != null) {
            val bed = flavor == PropertyFlavor.Bedrock
            val options = if (bed) meta.bedrockOptions ?: meta.options else meta.options
            val range = if (bed) meta.bedrockRange ?: meta.range else meta.range
            // 声明成 Slider 但没给范围时不猜：退回数字输入框，避免出现 0..0 的死滑块。
            val ui = if (meta.uiType == PropertyUiType.Slider && range == null) PropertyUiType.Number else meta.uiType
            return ResolvedProperty(
                key = key,
                meta = meta,
                value = rawValue,
                known = true,
                options = options,
                range = range,
                uiType = ui
            )
        }
        val inferred = inferUiType(key, rawValue)
        val meta3 = PropertyMeta(
            key = key,
            titleRes = 0,
            descRes = 0,
            group = PropertyGroup.P3,
            uiType = inferred,
            range = if (inferred == PropertyUiType.Port) 1..65535 else null
        )
        return ResolvedProperty(
            key = key,
            meta = meta3,
            value = rawValue,
            known = false,
            options = emptyList(),
            range = meta3.range,
            uiType = inferred
        )
    }

    /**
     * 按「文件真实内容」生成页面行，已按优先级 → key 字母序排好。
     *
     * @param values 文件里实际存在的 key/value（顺序不重要，这里会重排）
     */
    fun buildRows(values: Map<String, String>, flavor: PropertyFlavor): List<ResolvedProperty> =
        values.entries
            .asSequence()
            .filter { it.key.isNotBlank() && it.key.trim() == it.key }
            .map { resolve(it.key, it.value, flavor) }
            .sortedWith(compareBy({ it.meta.group.ordinal }, { it.key }))
            .toList()

    /** 顶部展示信息卡要渲染的键（只包含文件里真实存在的）。 */
    fun heroRows(rows: List<ResolvedProperty>): List<ResolvedProperty> =
        rows.filter { it.key in heroKeys }

    /** 分组列表用的键（去掉顶部展示信息键）。 */
    fun groupedRows(rows: List<ResolvedProperty>): List<ResolvedProperty> =
        rows.filter { it.key !in heroKeys }

    /**
     * 计算需要写回文件的改动：只保留值与原始值不同的键。
     *
     * 这是「无损保存」的一半：整份提交会让服务端把 UI 没覆盖到的键（比如某个
     * 插件注册的键）按 UI 的认知重写一遍；只提交差值则连注释与行序都不会动
     * （合并逻辑见 `ServerPropertiesManager.writeProperties`）。
     */
    fun diff(current: Map<String, String>, original: Map<String, String>): Map<String, String> =
        current.filter { (key, value) -> original[key] != value }

    /**
     * 未收录项的控件推断。
     *
     * 保守优先：只在**确有把握**时才给开关/端口/数字，其余一律文本 —— 猜错的
     * 开关会把 `foo=0.3` 这类值写成 `false`，那是真正的数据损坏。
     */
    fun inferUiType(key: String, rawValue: String): PropertyUiType {
        val v = rawValue.trim()
        val lowered = v.lowercase()
        if (lowered == "true" || lowered == "false" || lowered == "on" || lowered == "off") {
            return PropertyUiType.Toggle
        }
        val name = key.lowercase()
        if (name == "port" || name.endsWith("-port") || name.endsWith("port") ||
            name.contains("portv6") || name.endsWith(".port")
        ) {
            return PropertyUiType.Port
        }
        if (v.toIntOrNull() != null) return PropertyUiType.Number
        return PropertyUiType.Text
    }
}

// ── 构造辅助 ──────────────────────────────────────────────────────

private fun option(value: String, labelRes: Int, vararg aliases: String) =
    PropertyOption(value = value, labelRes = labelRes, aliases = aliases.toList())

private fun toggle(
    key: String,
    titleRes: Int,
    descRes: Int,
    group: PropertyGroup,
    default: String = "false"
) = PropertyMeta(key, titleRes, descRes, group, PropertyUiType.Toggle, default)

private fun text(
    key: String,
    titleRes: Int,
    descRes: Int,
    group: PropertyGroup,
    default: String = ""
) = PropertyMeta(key, titleRes, descRes, group, PropertyUiType.Text, default)

private fun multiline(
    key: String,
    titleRes: Int,
    descRes: Int,
    group: PropertyGroup
) = PropertyMeta(key, titleRes, descRes, group, PropertyUiType.Multiline)

private fun number(
    key: String,
    titleRes: Int,
    descRes: Int,
    group: PropertyGroup,
    default: String = "",
    range: IntRange? = null,
    decimal: Boolean = false
) = PropertyMeta(
    key, titleRes, descRes, group, PropertyUiType.Number, default,
    range = range, decimal = decimal
)

private fun port(
    key: String,
    titleRes: Int,
    descRes: Int,
    group: PropertyGroup,
    default: String = ""
) = PropertyMeta(key, titleRes, descRes, group, PropertyUiType.Port, default, range = 1..65535)

private fun slider(
    key: String,
    titleRes: Int,
    descRes: Int,
    group: PropertyGroup,
    min: Int,
    max: Int,
    default: String,
    bedrockRange: IntRange? = null
) = PropertyMeta(
    key, titleRes, descRes, group, PropertyUiType.Slider, default,
    range = min..max, bedrockRange = bedrockRange
)

/**
 * 枚举项。
 *
 * `uiType` 参数存在只为 [PropertyMeta] 字段齐全；所有枚举都是分段选择。
 */
private fun seg(
    key: String,
    titleRes: Int,
    descRes: Int,
    group: PropertyGroup,
    default: String,
    options: List<PropertyOption>,
    bedrockOptions: List<PropertyOption>? = null
) = PropertyMeta(
    key = key,
    titleRes = titleRes,
    descRes = descRes,
    group = group,
    uiType = PropertyUiType.Segmented,
    defaultValue = default,
    options = options,
    bedrockOptions = bedrockOptions
)
