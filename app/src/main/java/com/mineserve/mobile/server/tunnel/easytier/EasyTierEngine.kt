package com.mineserve.mobile.server.tunnel.easytier

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

/**
 * EasyTier 组网的门面：UI 层只跟它打交道，不直接碰 JNI 或 Service。
 *
 * 拆出来的原因是**授权流程**：`VpnService.establish()` 只有在
 * `VpnService.prepare()` 已被用户同意（或系统已记住该应用的 VPN 授权）之后才会成功，
 * 否则返回 null —— 而 `establish()` 返回 null 时既没有异常也没有错误码，只在日志里留一句
 * 「系统拒绝」。把「先要权限、再启动」这条顺序收在一处，UI 就不会漏掉。
 */
object EasyTierEngine {

    /** 当前是否已在运行（供卡片显示）。 */
    val state: EasyTierVpnService.State
        get() = EasyTierVpnService.state

    /** 引擎库是否可用（未下载时整块功能应当禁用，而不是点了报错）。 */
    val available: Boolean
        get() = com.easytier.jni.EasyTierJNI.ensureLoaded()

    /**
     * 需要用户授权时返回要启动的 Intent，已授权返回 null。
     *
     * 调用方必须在 Activity 里 `startActivity(intent)`（或用 ActivityResult 契约），
     * 用户点「确定」后系统记住该应用，之后 [start] 才能建起 TUN。
     */
    fun prepareIntent(context: Context): Intent? = runCatching { VpnService.prepare(context) }.getOrNull()

    /**
     * 启动组网。
     *
     * 用 `startForegroundService`：VpnService 属于前台服务，Android 8+ 用普通
     * `startService` 在后台会抛 `IllegalStateException`。
     */
    fun start(context: Context, settings: EasyTierSettings) {
        ContextCompat.startForegroundService(context, EasyTierVpnIntents.start(context, settings))
    }

    /** 停止组网。 */
    fun stop(context: Context) {
        runCatching { context.startService(EasyTierVpnIntents.stop(context)) }
    }
}
