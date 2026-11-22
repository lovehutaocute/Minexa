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
    private val reportStart: (TunnelState) -> Unit,
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

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
