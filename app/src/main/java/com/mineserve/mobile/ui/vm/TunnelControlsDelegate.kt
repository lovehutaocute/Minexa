package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.FrpLocalTarget
import com.mineserve.mobile.data.FrpProfile
import com.mineserve.mobile.data.FrpProfileKind
import com.mineserve.mobile.data.FrpProfiles
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.server.TunnelManager
import com.mineserve.mobile.server.tunnel.easytier.EasyTierEngine
import com.mineserve.mobile.server.tunnel.easytier.EasyTierSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class TunnelControlsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val isBootstrapped: StateFlow<Boolean>,
    private val tunnelManager: TunnelManager,
    private val updateConfig: ((McConfig) -> McConfig) -> Unit,
    private val refreshServerPropertiesNow: suspend () -> Unit,
    private val realServerPort: () -> Int,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    /** 新增或就地更新一份 FRP 配置（按 id 判定是新增还是编辑）。 */
    fun saveFrpProfile(profile: FrpProfile) {
        updateConfig { cfg ->
            val exists = cfg.frpProfiles.any { it.id == profile.id }
            val list = if (exists) {
                cfg.frpProfiles.map { if (it.id == profile.id) profile else it }
            } else {
                cfg.frpProfiles + profile
            }
            cfg.copy(
                frpProfiles = list,
                // 第一份配置自动成为当前配置：省掉「存完还得自己再选一次」这一步。
                activeFrpProfileId = cfg.activeFrpProfileId.ifBlank { profile.id }
            )
        }
    }

    /** 删除一份 FRP 配置；删的正好是当前配置时自动切到剩下的第一份。 */
    fun deleteFrpProfile(id: String) {
        updateConfig { cfg ->
            val left = cfg.frpProfiles.filterNot { it.id == id }
            cfg.copy(
                frpProfiles = left,
                activeFrpProfileId =
                    if (cfg.activeFrpProfileId == id) left.firstOrNull()?.id.orEmpty()
                    else cfg.activeFrpProfileId
            )
        }
    }

    /** 切换当前 FRP 配置。 */
    fun setActiveFrpProfile(id: String) = updateConfig { it.copy(activeFrpProfileId = id) }

    /** 当前生效的 FRP 配置；列表为空时返回 null。 */
    fun activeFrpProfile(): FrpProfile? = FrpProfiles.active(config.value)

    // ── 启停 ─────────────────────────────────────────────────

    fun startFrp() {
        if (!isBootstrapped.value) {
            reportError(str(R.string.s192))
            return
        }
        if (tunnelManager.frpState.value.status == TunnelStatus.Starting) {
            reportError(str(R.string.s200))
            return
        }
        val cfg = config.value
        val profile = FrpProfiles.active(cfg)
        if (profile == null) {
            reportError(str(R.string.msg_frp_profile_required))
            return
        }
        FrpProfiles.validationError(profile)?.let {
            reportError(it)
            return
        }
        scope.launch {
            try {
                // 先把端口真源读回来：用户可能刚在配置文件里改了端口，
                // 不重读的话下面拿到的还是上一次的值（隧道会打到旧端口）。
                refreshServerPropertiesNow()
                val realPort = realServerPort()

                // 投影：把选中的配置摊进后端认识的老字段，后端一行都不用改。
                var runtime = FrpProfiles.applyTo(profile, cfg)
                if (profile.kind == FrpProfileKind.CustomToml) {
                    // 自建 frp 的转发端口写在用户粘的那份文本里，多半还停在改端口之前的旧值。
                    // 这里对齐**运行期副本**（保存的原文不动），否则隧道通了玩家也进不来。
                    val aligned = FrpLocalTarget.align(runtime.frpConfigText, realPort)
                    if (aligned.changed > 0) {
                        runtime = runtime.copy(frpConfigText = aligned.text)
                        tunnelManager.note(
                            "配置里的转发端口 ${aligned.from} 不是游戏真实端口 $realPort，" +
                                "本次已按 $realPort 启动（你的配置原文没有被改）"
                        )
                    }
                }
                tunnelManager.startFrp(runtime)
                reportStart(tunnelManager.frpState.value)
            } catch (e: Exception) {
                reportError(str(R.string.s206, e.message))
            }
        }
    }

    fun stopFrp() {
        scope.launch {
            try {
                tunnelManager.stopFrp()
                reportMessage(str(R.string.s207))
            } catch (e: Exception) {
                reportError(str(R.string.s208, e.message))
            }
        }
    }

    fun startStun() {
        if (!isBootstrapped.value) {
            reportError(str(R.string.s192))
            return
        }
        if (tunnelManager.stunState.value.status == TunnelStatus.Starting) {
            reportError(str(R.string.s200))
            return
        }
        scope.launch {
            try {
                // 端口真源：StunTunnelBackend 默认取 config.localPort（镜像字段），
                // 这里显式覆盖成真源 serverPort。否则配置文件被外部改动后，
                // STUN 会把玩家流量转到服务端并未监听的旧端口。
                //
                // 真源必须在取用**之前**读回来，而且要现算（[realServerPort]）：
                // 用户刚改完端口就来开穿透时，读与不读就是「能进」和「connection refused」的区别。
                //
                // tunnelType 也一并写成 Stun：界面上已经没有「穿透方式」单选了，
                // 不写的话它会停在默认的 Frp，配置本身自相矛盾（虽然
                // TunnelManager 现在按通道取后端、不再依赖这个字段）。
                refreshServerPropertiesNow()
                val targetPort = realServerPort()
                tunnelManager.startStun(
                    config.value.copy(localPort = targetPort, tunnelType = TunnelType.Stun)
                )
                reportStart(tunnelManager.stunState.value)
            } catch (e: Exception) {
                reportError(str(R.string.s206, e.message))
            }
        }
    }

    fun stopStun() {
        scope.launch {
            try {
                tunnelManager.stopStun()
                reportMessage(str(R.string.s207))
            } catch (e: Exception) {
                reportError(str(R.string.s208, e.message))
            }
        }
    }

    val easyTierAvailable: Boolean get() = EasyTierEngine.available

    /**
     * 补齐组网身份：网络名或密钥为空时自动生成一套。
     *
     * 「填个网络名就能分享给任何人」的前提是**用户不用自己想名字**。
     * 名字生成成 `mc-xxxxxx` 这种带随机后缀的形式，两个人同时第一次用也不会撞名。
     * 已有值的字段一律不覆盖 —— 用户改过的网络名不能被一次进入页面就冲掉。
     */
    fun ensureEasyTierIdentity() {
        val cfg = config.value
        if (cfg.easyTierNetworkName.isNotBlank() && cfg.easyTierNetworkSecret.isNotBlank()) return
        updateConfig {
            it.copy(
                easyTierNetworkName = it.easyTierNetworkName.ifBlank {
                    "mc-" + EasyTierSettings.generateSecret().take(6)
                },
                easyTierNetworkSecret = it.easyTierNetworkSecret.ifBlank {
                    EasyTierSettings.generateSecret()
                }
            )
        }
    }

    /**
     * 换一套网络密钥。
     *
     * 用途是「把之前那批人踢出去」：EasyTier 没有账号体系、没有 ACL，
     * 密钥是**唯一**的访问控制手段，泄漏了只能换。
     */
    fun regenerateEasyTierSecret() {
        updateConfig { it.copy(easyTierNetworkSecret = EasyTierSettings.generateSecret()) }
        reportMessage(str(R.string.easytier_secret_regenerated))
    }

    /**
     * 本机在虚拟网里的地址（`虚拟IP:转发端口`）。网络名为空时返回空串。
     *
     * 端口取的是**转发端口**而不是服务端端口：两个监听不能占同一个端口，
     * 谁后绑谁失败（见 `EasyTierSettings.toToml`）。服务端端口一变，
     * 转发目标跟着变，但玩家看到的端口固定是转发端口。
     */
    fun easyTierAddress(): String {
        val cfg = config.value
        val name = cfg.easyTierNetworkName.trim()
        val ip = cfg.easyTierVirtualIp.trim()
            .ifBlank { if (name.isBlank()) "" else EasyTierSettings.deriveVirtualIp(name) }
        return EasyTierSettings.playerAddress(ip, realServerPort())
    }

    /** 给玩家的整段说明。复制按钮与二维码用的是**同一份文本**，不允许各拼一套。 */
    fun easyTierShareText(): String {
        val cfg = config.value
        return str(
            R.string.easytier_share_text,
            cfg.easyTierNetworkName.trim(),
            cfg.easyTierNetworkSecret.trim(),
            easyTierAddress()
        )
    }

    fun startEasyTier() {
        if (!isBootstrapped.value) {
            reportError(str(R.string.s192))
            return
        }
        if (!EasyTierEngine.available) {
            reportError(str(R.string.easytier_engine_missing))
            return
        }
        if (tunnelManager.easyTierState.value.status == TunnelStatus.Starting) {
            reportError(str(R.string.s200))
            return
        }
        scope.launch {
            try {
                // 端口真源，理由同 STUN：配置文件被外部改动过时，
                // port_forward 会把虚拟网里的流量转到一个没人监听的端口上。
                refreshServerPropertiesNow()
                val targetPort = realServerPort()
                val result = tunnelManager.startEasyTier(
                    config.value.copy(localPort = targetPort, tunnelType = TunnelType.EasyTier)
                )
                val failure = result.exceptionOrNull()
                if (failure != null) {
                    reportError(failure.message ?: str(R.string.s204, ""))
                } else {
                    reportStart(tunnelManager.easyTierState.value)
                }
            } catch (e: Exception) {
                reportError(str(R.string.s206, e.message))
            }
        }
    }

    fun stopEasyTier() {
        scope.launch {
            try {
                tunnelManager.stopEasyTier()
                reportMessage(str(R.string.s207))
            } catch (e: Exception) {
                reportError(str(R.string.s208, e.message))
            }
        }
    }

    /**
     * 用户在系统 VPN 授权框上点了「拒绝」/直接返回。
     *
     * 必须单独提示：`establish()` 在无授权时只返回 null，日志里也只有一句
     * 「系统拒绝」，从那条信息看不出是用户自己关掉的还是系统拦的。
     */
    fun notifyEasyTierVpnDenied() {
        reportError(str(R.string.easytier_vpn_denied))
    }

    /** 复制给玩家的整段说明（组网卡片与引导页共用）。 */
    fun copyEasyTierGuide(context: android.content.Context) {
        val text = easyTierShareText()
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("EasyTier", text))
        reportMessage(str(R.string.easytier_copied))
    }

    /**
     * 启动调用返回后的统一提示。
     *
     * 注意：STUN 后端的 start() 是**异步就绪**的 —— 它立即返回，实际探测在后台
     * 进行，就绪/失败稍后经状态流反映。所以这里只能提示「正在启动」，
     * 不能按 Running 判定成功，否则网络慢时会误报失败。结果由界面观察状态流得出。
     */
    private fun reportStart(st: TunnelState) {
        when (st.status) {
            TunnelStatus.Running -> {
                val url = st.publicUrl
                reportMessage(
                    if (url.isNotBlank()) str(R.string.msg_tunnel_started_url, url)
                    else str(R.string.msg_tunnel_started)
                )
            }
            TunnelStatus.Starting -> reportMessage(str(R.string.s203))
            TunnelStatus.Failed -> reportError(str(R.string.s204, st.errorMessage))
            else -> reportMessage(str(R.string.s205))
        }
    }

    /** 复制任意公网地址（FRP 与 STUN 卡片各自传入自己那条通道的地址）。 */
    fun copyPublicUrl(context: android.content.Context, url: String) {
        if (url.isBlank()) {
            reportError(str(R.string.s209))
            return
        }
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Tunnel URL", url))
        reportMessage(str(R.string.s210, url))
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
