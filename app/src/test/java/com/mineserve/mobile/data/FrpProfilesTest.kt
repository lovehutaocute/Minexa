package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FrpProfiles] 的纯函数测试。
 *
 * 这些函数是「多份 FRP 配置」这个特性的全部逻辑：界面只负责收集输入，
 * 真正决定「用哪一份、投影成什么、能不能存」的都在这里。
 * 因此不需要 Android 环境，直接断言。
 */
class FrpProfilesTest {

    private fun profile(
        id: String,
        name: String,
        kind: FrpProfileKind = FrpProfileKind.CustomToml,
        configText: String = "",
        token: String = "",
        tunnelId: String = ""
    ) = FrpProfile(
        id = id,
        name = name,
        kind = kind,
        configText = configText,
        token = token,
        tunnelId = tunnelId
    )

    // ── 默认名 ────────────────────────────────────────────────

    @Test
    fun `默认名没被占用时直接用基名`() {
        assertEquals(
            "自建 frp 配置",
            FrpProfiles.defaultName(FrpProfileKind.CustomToml, emptyList())
        )
    }

    @Test
    fun `默认名被占用时递增而不是覆盖已有配置`() {
        val existing = listOf(
            profile("a", "SakuraFrp 配置"),
            profile("b", "SakuraFrp 配置 2")
        )
        // 关键：跳到 3，不能返回已被占用的 "SakuraFrp 配置" 或 "SakuraFrp 配置 2"，
        // 否则新建两份配置会直接变成同名。
        assertEquals(
            "SakuraFrp 配置 3",
            FrpProfiles.defaultName(FrpProfileKind.SakuraFrp, existing)
        )
    }

    @Test
    fun `默认名只在同类型内避让`() {
        val existing = listOf(profile("a", "SakuraFrp 配置"))
        // 不同类型的基名不同，互不干扰
        assertEquals(
            "ME Frp 配置",
            FrpProfiles.defaultName(FrpProfileKind.MeFrp, existing)
        )
    }

    // ── 同名提醒 ──────────────────────────────────────────────

    @Test
    fun `同名判断会排除自己`() {
        val existing = listOf(profile("a", "家里的服务器"), profile("b", "学校服务器"))
        // 编辑自己时不该报「已有同名」
        assertFalse(FrpProfiles.isNameTaken("家里的服务器", existing, selfId = "a"))
        assertTrue(FrpProfiles.isNameTaken("家里的服务器", existing, selfId = "b"))
    }

    @Test
    fun `空名称不算同名`() {
        assertFalse(FrpProfiles.isNameTaken("   ", emptyList(), selfId = "x"))
    }

    // ── 选中哪一份 ────────────────────────────────────────────

    @Test
    fun `active 返回 id 指向的那一份`() {
        val cfg = McConfig(
            frpProfiles = listOf(profile("a", "甲"), profile("b", "乙")),
            activeFrpProfileId = "b"
        )
        assertEquals("b", FrpProfiles.active(cfg)?.id)
    }

    @Test
    fun `activeFrpProfileId 失效时退回第一份`() {
        // 删掉当前配置后，activeFrpProfileId 可能还指向一个不存在的 id。
        // 此时必须退回列表第一份，否则会出现「列表里有配置，卡片却说没有」。
        val cfg = McConfig(
            frpProfiles = listOf(profile("a", "甲"), profile("b", "乙")),
            activeFrpProfileId = "已被删除的 id"
        )
        assertEquals("a", FrpProfiles.active(cfg)?.id)
    }

    @Test
    fun `没有配置时 active 为 null`() {
        assertNull(FrpProfiles.active(McConfig()))
    }

    // ── 投影到后端认识的老字段 ────────────────────────────────

    @Test
    fun `自建 toml 配置投影后后端读到 frpConfigText`() {
        val profile = profile("a", "甲", FrpProfileKind.CustomToml, configText = "[common]\nserver_addr=1.2.3.4")
        val projected = FrpProfiles.applyTo(profile, McConfig(tunnelType = TunnelType.Stun))

        assertEquals(TunnelType.Frp, projected.tunnelType)
        assertEquals("[common]\nserver_addr=1.2.3.4", projected.frpConfigText)
    }

    @Test
    fun `SakuraFrp 配置投影出 token 与隧道 id`() {
        val profile = profile(
            "a", "甲", FrpProfileKind.SakuraFrp,
            token = "sakura-token", tunnelId = "12345"
        )
        val projected = FrpProfiles.applyTo(profile, McConfig())

        assertEquals(TunnelType.SakuraFrp, projected.tunnelType)
        assertEquals("sakura-token", projected.sakuraToken)
        assertEquals("12345", projected.sakuraTunnelId)
    }

    @Test
    fun `ME Frp 配置投影出 token 与隧道 id`() {
        val profile = profile(
            "a", "甲", FrpProfileKind.MeFrp,
            token = "me-token", tunnelId = "67890"
        )
        val projected = FrpProfiles.applyTo(profile, McConfig())

        assertEquals(TunnelType.MeFrp, projected.tunnelType)
        assertEquals("me-token", projected.meFrpToken)
        assertEquals("67890", projected.meFrpTunnelId)
    }

    @Test
    fun `投影不碰其他字段`() {
        // 投影必须是「只写本类型用得到的字段」，端口、STUN 参数等一律原样透传，
        // 否则切换 FRP 配置会顺手把用户的其他设置改掉。
        val base = McConfig(localPort = 25566, stunMaxConnections = 64, stunServers = "stun.example.com")
        val projected = FrpProfiles.applyTo(
            profile("a", "甲", FrpProfileKind.MeFrp, token = "t", tunnelId = "1"),
            base
        )

        assertEquals(25566, projected.localPort)
        assertEquals(64, projected.stunMaxConnections)
        assertEquals("stun.example.com", projected.stunServers)
    }

    @Test
    fun `同一平台可以存多份不同 token 的配置`() {
        val a = profile("a", "小号", FrpProfileKind.SakuraFrp, token = "token-a", tunnelId = "1")
        val b = profile("b", "大号", FrpProfileKind.SakuraFrp, token = "token-b", tunnelId = "2")

        val projectedA = FrpProfiles.applyTo(a, McConfig())
        val projectedB = FrpProfiles.applyTo(b, McConfig())

        assertEquals("token-a", projectedA.sakuraToken)
        assertEquals("token-b", projectedB.sakuraToken)
        assertEquals("1", projectedA.sakuraTunnelId)
        assertEquals("2", projectedB.sakuraTunnelId)
    }

    // ── 保存前校验 ────────────────────────────────────────────

    @Test
    fun `自建 frp 必须填 toml`() {
        assertTrue(
            FrpProfiles.validationError(profile("a", "甲", FrpProfileKind.CustomToml, configText = " ")) != null
        )
        assertNull(
            FrpProfiles.validationError(
                profile("a", "甲", FrpProfileKind.CustomToml, configText = "[common]")
            )
        )
    }

    @Test
    fun `平台配置必须同时有 token 和隧道`() {
        for (kind in listOf(FrpProfileKind.SakuraFrp, FrpProfileKind.MeFrp)) {
            assertTrue(
                "$kind 缺 token 应报错",
                FrpProfiles.validationError(profile("a", "甲", kind, token = "", tunnelId = "1")) != null
            )
            assertTrue(
                "$kind 有 token 但没选隧道应报错 —— 启动时无从得知用哪条隧道",
                FrpProfiles.validationError(profile("a", "甲", kind, token = "t", tunnelId = "")) != null
            )
            assertNull(
                "$kind 字段齐全应通过",
                FrpProfiles.validationError(profile("a", "甲", kind, token = "t", tunnelId = "1"))
            )
        }
    }

    // ── 旧版单份配置的迁移 ────────────────────────────────────

    @Test
    fun `没有旧配置时不迁移`() {
        assertNull(FrpProfiles.migrateLegacy(McConfig()))
    }

    @Test
    fun `旧的自建 frp 配置会变成一份可用配置`() {
        // 老用户升级后原来能用的 FRP 必须还在，否则「改之前能用、改之后不能用」。
        val legacy = McConfig(
            tunnelType = TunnelType.Frp,
            frpConfigText = "[common]\nserver_addr=1.2.3.4\nserver_port=7000"
        )

        val migrated = FrpProfiles.migrateLegacy(legacy)

        assertNotNull(migrated)
        val profile = FrpProfiles.active(migrated!!)
        assertNotNull(profile)
        assertEquals(FrpProfileKind.CustomToml, profile!!.kind)
        assertEquals("[common]\nserver_addr=1.2.3.4\nserver_port=7000", profile.configText)
        // 迁移出来的配置必须能直接启动
        assertNull(FrpProfiles.validationError(profile))
    }

    @Test
    fun `旧的平台配置带令牌与隧道号才能迁移`() {
        // 旧版界面点一下隧道就把 token + 隧道号一起写进配置，
        // 所以「只用过平台」的用户迁移后应当原样可用。
        val usable = McConfig(
            tunnelType = TunnelType.SakuraFrp,
            sakuraToken = "sakura-token",
            sakuraTunnelId = "12345"
        )
        val usableProfile = FrpProfiles.active(FrpProfiles.migrateLegacy(usable)!!)!!
        assertEquals(FrpProfileKind.SakuraFrp, usableProfile.kind)
        assertEquals("sakura-token", usableProfile.token)
        assertEquals("12345", usableProfile.tunnelId)
        assertNull(FrpProfiles.validationError(usableProfile))

        // 只有令牌、没选隧道：本来也启动不起来，迁过来只会是死配置，不如不迁。
        val halfBaked = McConfig(sakuraToken = "sakura-token", sakuraTunnelId = "")
        assertNull(FrpProfiles.migrateLegacy(halfBaked))
    }

    @Test
    fun `多个平台的旧配置会被一起迁移并保留原来在用的那份`() {
        val legacy = McConfig(
            tunnelType = TunnelType.MeFrp,
            frpConfigText = "[common]\nserver_addr=1.2.3.4",
            meFrpToken = "me-token",
            meFrpTunnelId = "67890"
        )

        val migrated = FrpProfiles.migrateLegacy(legacy)!!

        assertEquals(2, migrated.frpProfiles.size)
        // 迁移前正在用的是 ME Frp，迁移后卡片上默认显示的还得是它
        assertEquals(FrpProfileKind.MeFrp, FrpProfiles.active(migrated)!!.kind)
        assertTrue(migrated.frpProfiles.any { it.kind == FrpProfileKind.CustomToml })
    }

    @Test
    fun `迁移会清空旧字段因而不会重复迁移`() {
        val legacy = McConfig(
            tunnelType = TunnelType.Frp,
            frpConfigText = "[common]\nserver_addr=1.2.3.4"
        )

        val once = FrpProfiles.migrateLegacy(legacy)!!
        // 旧字段清空了；用户之后把配置全删了也不会「自己长回来」
        assertEquals("", once.frpConfigText)
        assertNull(FrpProfiles.migrateLegacy(once))

        // 而且删光配置之后仍然不迁移
        assertNull(FrpProfiles.migrateLegacy(once.copy(frpProfiles = emptyList())))
    }

    @Test
    fun `已经有多份配置时不再迁移`() {
        val already = McConfig(
            frpConfigText = "[common]\nserver_addr=1.2.3.4",
            frpProfiles = listOf(profile("a", "我的配置", FrpProfileKind.CustomToml, configText = "x"))
        )
        assertNull(FrpProfiles.migrateLegacy(already))
    }

    // ── 展示文案 ──────────────────────────────────────────────

    @Test
    fun `卡片标题是名字加平台`() {
        assertEquals(
            "家里的服务器 · SakuraFrp",
            FrpProfiles.label(profile("a", "家里的服务器", FrpProfileKind.SakuraFrp))
        )
    }

    @Test
    fun `隧道摘要包含节点与远端端口`() {
        val label = FrpProfiles.tunnelLabel(
            nodeName = "3",
            type = "tcp",
            localIp = "127.0.0.1",
            localPort = 25565,
            remote = "frp.example.com:25565"
        )
        assertTrue(label.contains("节点 3"))
        assertTrue(label.contains("127.0.0.1:25565"))
        assertTrue(label.contains("frp.example.com:25565"))
    }

    @Test
    fun `隧道摘要没有远端时显示自动分配`() {
        val label = FrpProfiles.tunnelLabel("", "tcp", "127.0.0.1", 25565, "")
        assertTrue(label.contains("自动分配"))
    }
}
