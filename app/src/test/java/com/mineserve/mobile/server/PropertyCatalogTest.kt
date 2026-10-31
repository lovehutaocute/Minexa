package com.mineserve.mobile.server

import com.mineserve.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置项字典与动态表单模型的行为约束。
 *
 * 这些用例守的是「换核心也不丢配置项」这条底线：排序错了只是不好用，但
 * **未知键被丢掉**、**枚举写错值**、**只改一项却把整份配置提交**会直接改坏
 * 用户的服务器配置，因此逐条钉住。
 */
class PropertyCatalogTest {

    @Test
    fun `字典内没有重复 key`() {
        // 重复 key 会让 associateBy 静默丢掉先写入的那条 —— 分组、控件类型、
        // 取值范围全都会变成「最后一次出现的那个」，且编译期毫无提示。
        val keys = PropertyCatalog.byKey.keys.size
        val declared = PropertyCatalog.size
        assertTrue(
            "声明 $declared 条但去重后只剩 $keys 条，存在重复 key",
            declared == keys
        )
    }

    @Test
    fun `按分组优先级与字母序排序`() {
        val rows = PropertyCatalog.buildRows(
            mapOf(
                "motd" to "hi",
                "view-distance" to "10",
                "debug" to "false",
                "level-seed" to "42",
                "zzz-unknown" to "1"
            ),
            PropertyFlavor.Java
        )
        // P0(motd, view-distance) → P1(level-seed) → P2(debug) → P3(zzz-unknown)
        assertEquals(listOf("motd", "view-distance", "level-seed", "debug", "zzz-unknown"), rows.map { it.key })
        // 组内字母序：motd < view-distance
        assertTrue(rows[0].key < rows[1].key)
    }

    @Test
    fun `未收录的配置项不会被丢弃`() {
        val rows = PropertyCatalog.buildRows(
            mapOf("my-mod-flag" to "true", "tps-limit" to "20", "custom-thing" to "abc"),
            PropertyFlavor.Java
        )
        assertEquals(3, rows.size)
        assertTrue(rows.all { !it.known })
        assertTrue(rows.all { it.meta.group == PropertyGroup.P3 })
        // 未收录项的标题回退到原始 key（titleRes == 0）
        assertTrue(rows.all { it.meta.titleRes == 0 })
    }

    @Test
    fun `未收录项按值推断控件类型`() {
        val rows = PropertyCatalog.buildRows(
            mapOf(
                "mod-enable-x" to "true",
                "mod-toggle-y" to "off",
                "extra-port" to "25599",
                "mod-count" to "7",
                "mod-ratio" to "0.35"
            ),
            PropertyFlavor.Java
        ).associateBy { it.key }

        assertEquals(PropertyUiType.Toggle, rows.getValue("mod-enable-x").uiType)
        assertEquals(PropertyUiType.Toggle, rows.getValue("mod-toggle-y").uiType)
        assertEquals(PropertyUiType.Port, rows.getValue("extra-port").uiType)
        assertEquals(PropertyUiType.Number, rows.getValue("mod-count").uiType)
        // 浮点值不能猜成整数输入（按整数过滤会把 0.35 截成 0）
        assertEquals(PropertyUiType.Text, rows.getValue("mod-ratio").uiType)
    }

    @Test
    fun `gamemode 在基岩版不给旁观者选项`() {
        val java = PropertyCatalog.resolve("gamemode", "survival", PropertyFlavor.Java)
        val bedrock = PropertyCatalog.resolve("gamemode", "survival", PropertyFlavor.Bedrock)
        assertEquals(4, java.options.size)
        assertEquals(3, bedrock.options.size)
        assertTrue(bedrock.options.none { it.value == "spectator" })
    }

    @Test
    fun `难度与游戏模式兼容数字写法`() {
        val difficulty = PropertyCatalog.resolve("difficulty", "2", PropertyFlavor.Java)
        val normal = difficulty.options.first { it.matches("2") }
        assertEquals("normal", normal.value)

        val gamemode = PropertyCatalog.resolve("gamemode", "1", PropertyFlavor.Bedrock)
        assertEquals("creative", gamemode.options.first { it.matches("1") }.value)

        // 单词写法同样要命中
        assertTrue(difficulty.options.first { it.value == "normal" }.matches("NORMAL"))
    }

    @Test
    fun `视距滑块范围按核心收敛`() {
        val java = PropertyCatalog.resolve("view-distance", "10", PropertyFlavor.Java)
        assertEquals(PropertyUiType.Slider, java.uiType)
        assertEquals(2..32, java.range)

        val bedrock = PropertyCatalog.resolve("view-distance", "10", PropertyFlavor.Bedrock)
        assertEquals(5..32, bedrock.range)

        val spawn = PropertyCatalog.resolve("spawn-protection", "16", PropertyFlavor.Java)
        assertEquals(0..64, spawn.range)
    }

    @Test
    fun `端口类配置限定 1 到 65535`() {
        listOf("server-port", "server-portv6", "query.port", "rcon.port").forEach { key ->
            val row = PropertyCatalog.resolve(key, "25565", PropertyFlavor.Java)
            assertEquals(key, PropertyUiType.Port, row.uiType)
            assertEquals(key, 1..65535, row.range)
        }
    }

    @Test
    fun `BDS 特有配置项已收录`() {
        listOf(
            "server-name", "allow-cheats", "server-portv6", "default-player-permission-level",
            "texturepack-required", "content-log-file-enabled", "allow-list", "tick-distance"
        ).forEach { key ->
            assertNotNull("BDS 键 $key 未收录", PropertyCatalog.byKey[key])
        }
        val perm = PropertyCatalog.resolve("default-player-permission-level", "member", PropertyFlavor.Bedrock)
        assertEquals(PropertyUiType.Segmented, perm.uiType)
        assertEquals(listOf("visitor", "member", "operator"), perm.options.map { it.value })
    }

    @Test
    fun `Java 版常见配置项已收录`() {
        listOf(
            "motd", "server-port", "max-players", "online-mode", "gamemode", "difficulty",
            "view-distance", "simulation-distance", "white-list", "pvp", "allow-flight",
            "spawn-protection", "level-name", "level-seed", "enable-command-block",
            "enforce-secure-profile"
        ).forEach { key ->
            assertNotNull("Java 键 $key 未收录", PropertyCatalog.byKey[key])
        }
    }

    @Test
    fun `差值只包含真正变过的键`() {
        val original = mapOf("motd" to "A", "pvp" to "true", "server-port" to "25565")
        val current = mapOf("motd" to "A", "pvp" to "false", "server-port" to "25565")
        val changed = PropertyCatalog.diff(current, original)
        assertEquals(mapOf("pvp" to "false"), changed)
    }

    @Test
    fun `新增的键也算改动`() {
        val changed = PropertyCatalog.diff(mapOf("motd" to "A", "pvp" to "false"), mapOf("motd" to "A"))
        assertEquals(setOf("pvp"), changed.keys)
    }

    @Test
    fun `展示信息键与分组列表分离`() {
        val rows = PropertyCatalog.buildRows(
            mapOf("motd" to "A", "server-name" to "B", "max-players" to "20"),
            PropertyFlavor.Bedrock
        )
        assertEquals(setOf("motd", "server-name"), PropertyCatalog.heroRows(rows).map { it.key }.toSet())
        assertEquals(listOf("max-players"), PropertyCatalog.groupedRows(rows).map { it.key })
    }

    @Test
    fun `空值与空 key 不会生成行`() {
        val rows = PropertyCatalog.buildRows(mapOf("" to "x", "  " to "y", "pvp" to ""), PropertyFlavor.Java)
        assertEquals(1, rows.size)
        assertEquals("pvp", rows.first().key)
    }

    @Test
    fun `字典里每个预定义项都有标题资源`() {
        PropertyCatalog.byKey.values.forEach { meta ->
            assertFalse("${meta.key} 缺少标题资源", meta.titleRes == 0)
        }
        // 抽查两条说明确实存在（说明缺失时页面只剩标题，用户看不懂在改什么）
        assertTrue(PropertyCatalog.byKey.getValue("view-distance").descRes != 0)
        assertTrue(PropertyCatalog.byKey.getValue("difficulty").descRes != 0)
    }

    @Test
    fun `枚举选项的标题资源均可用`() {
        PropertyCatalog.byKey.values.forEach { meta ->
            meta.options.forEach { option ->
                assertTrue("${meta.key} 的选项 ${option.value} 缺少文案", option.labelRes != 0)
            }
        }
        assertEquals(R.string.s281, PropertyCatalog.byKey.getValue("gamemode").options.first().labelRes)
    }

    // ── BDS 官方配置全量对齐（以官方 server.properties 为基准） ──────────

    @Test
    fun `BDS 官方配置项全部已收录`() {
        // 这份清单来自官方基岩版服务端自带的 server.properties（1.21.5x）
        listOf(
            "server-name", "gamemode", "force-gamemode", "difficulty", "allow-cheats",
            "max-players", "online-mode", "allow-list", "server-port", "server-portv6",
            "enable-lan-visibility", "view-distance", "tick-distance", "player-idle-timeout",
            "level-name", "level-seed", "default-player-permission-level", "texturepack-required",
            "content-log-file-enabled", "compression-threshold", "server-authoritative-movement",
            "player-movement-score-threshold", "player-movement-action-direction-threshold",
            "player-movement-distance-threshold", "player-movement-duration-threshold-in-ms",
            "correct-player-movement", "server-authoritative-block-breaking", "chat-restriction",
            "disable-player-interaction", "client-side-chunk-generation-enabled",
            "block-network-ids-are-hashes", "emit-server-telemetry",
            // 1.21.5x 新增
            "server-udp-ports", "transport", "persistent-ticking-area-max-count",
            "persistent-ticking-area-max-chunks", "transient-ticking-area-max-chunks",
            "content-log-console-output-enabled", "content-log-level", "compression-algorithm",
            "server-authoritative-movement-strict", "server-authoritative-dismount-strict",
            "server-authoritative-entity-interactions-strict",
            "server-authoritative-entity-attack-request-validation",
            "server-authoritative-entity-attack-range-validation",
            "server-authoritative-entity-attack-range-tolerance",
            "player-position-acceptance-threshold",
            "server-authoritative-block-breaking-pick-range-scalar",
            "disable-persona", "disable-custom-skins", "server-build-radius-ratio",
            "allow-outbound-script-debugging", "allow-inbound-script-debugging",
            "script-debugger-auto-attach"
        ).forEach { key ->
            assertNotNull("BDS 键 $key 未收录", PropertyCatalog.byKey[key])
        }
    }

    @Test
    fun `BDS 新键的控件类型与官方取值一致`() {
        val transport = PropertyCatalog.resolve("transport", "raknet", PropertyFlavor.Bedrock)
        assertEquals(PropertyUiType.Segmented, transport.uiType)
        assertEquals(listOf("raknet", "nethernet"), transport.options.map { it.value })

        val attacks = PropertyCatalog.resolve(
            "server-authoritative-entity-attack-request-validation", "observe", PropertyFlavor.Bedrock
        )
        assertEquals(listOf("disabled", "observe", "enforce"), attacks.options.map { it.value })

        val attach = PropertyCatalog.resolve("script-debugger-auto-attach", "connect", PropertyFlavor.Bedrock)
        assertEquals(listOf("disabled", "connect", "listen"), attach.options.map { it.value })

        // 这三个键官方允许 unlimited，必须是文本框：数字框会把 unlimited 过滤成空
        listOf(
            "persistent-ticking-area-max-count",
            "persistent-ticking-area-max-chunks",
            "transient-ticking-area-max-chunks"
        ).forEach { key ->
            val row = PropertyCatalog.resolve(key, "unlimited", PropertyFlavor.Bedrock)
            assertEquals(key, PropertyUiType.Text, row.uiType)
            assertEquals(key, "unlimited", row.value)
        }

        // 浮点阈值必须标 decimal，否则输入框只能敲整数
        assertTrue(
            PropertyCatalog.resolve("player-position-acceptance-threshold", "0.5", PropertyFlavor.Bedrock).decimal
        )
        assertTrue(
            PropertyCatalog.resolve(
                "server-authoritative-entity-attack-range-tolerance", "0.5", PropertyFlavor.Bedrock
            ).decimal
        )
    }
}
