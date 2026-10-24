package com.mineserve.mobile.server.tunnel.easytier

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.easytier.jni.EasyTierJNI
import com.mineserve.mobile.R

/**
 * EasyTier 的 TUN 承载服务。
 *
 * ## 为什么整个启动过程都塞在这个 Service 里
 *
 * 启动有三个强先后关系，缺一个就会失败且报错很含糊：
 *  1. `runNetworkInstance(toml)` —— 实例先起来，此刻**还不能** bind 虚拟 IP；
 *  2. `Builder().establish()` —— 建 TUN，虚拟 IP 才真正出现在系统里；
 *  3. `setTunFd(instance, fd)` —— 把 TUN 交给实例，`port_forward` 才能 bind 成功。
 *
 * 如果第 1、3 步分散在别处（例如 ViewModel），中间任何一次重建 Activity、进程被回收，
 * 都会留下「实例在跑但没有 TUN」的半死状态。放在 Service 里，生命周期与 VPN 接口一致。
 *
 * ## 必须 `addDisallowedApplication(自己)`
 *
 * 不排除自己，EasyTier 自己的 UDP 打洞流量会被刚建好的 TUN 捕获，形成回环 ——
 * 表现为「能起来、对端显示已连接、但一个包都过不去」，且日志里看不到任何错误。
 */
class EasyTierVpnService : VpnService() {

    companion object {
        private const val TAG = "EasyTierVpn"

        const val ACTION_START = "com.mineserve.mobile.easytier.START"
        const val ACTION_STOP = "com.mineserve.mobile.easytier.STOP"

        const val EXTRA_TOML = "toml"
        const val EXTRA_INSTANCE = "instance"
        const val EXTRA_IPV4 = "ipv4"
        const val EXTRA_PREFIX = "prefix"
        const val EXTRA_PORT = "port"

        private const val NOTIF_ID = 0xE7

        /**
         * 上一次启动/运行的结果。
         *
         * 用伴生对象而不是 Intent 回传：`establish()` 的成败只有 Service 自己知道，
         * 而 UI 侧只需要「现在能不能用 + 为什么不能用」，轮询一个字段比搭一套 bind/callback 划算。
         */
        @Volatile
        var state: State = State.Stopped
            private set

        @Volatile
        var lastError: String? = null
            private set

    }

    /**
     * 运行状态。放在类内（不是 companion 内）是有意的：`EasyTierEngine.state`
     * 需要从外部引用这个类型，而嵌套在 companion 里时外部解析不到。
     */
    sealed interface State {
        data object Stopped : State
        data object Running : State

        /** 已建 TUN、但实例没起来或被系统拒绝。 */
        data class Failed(val reason: String) : State
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "收到停止指令")
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        // targetSdk 28：两参 startForeground 就够；先调再干活，避免 ANR 窗口内被系统判定超时
        runCatching { startForeground(NOTIF_ID, buildNotification()) }

        if (!EasyTierJNI.ensureLoaded()) {
            fail("引擎未下载：请到「依赖与环境管理 → 依赖」下载 EasyTier 组网引擎")
            return START_NOT_STICKY
        }

        val toml = intent?.getStringExtra(EXTRA_TOML)
        val instance = intent?.getStringExtra(EXTRA_INSTANCE)
        val ipv4 = intent?.getStringExtra(EXTRA_IPV4)
        if (toml.isNullOrBlank() || instance.isNullOrBlank() || ipv4.isNullOrBlank()) {
            fail("启动参数不完整")
            return START_NOT_STICKY
        }
        val prefix = intent.getIntExtra(EXTRA_PREFIX, 24)

        // 1) 实例先起来（不建 TUN —— no_tun，core 只把 port_forward 绑到转发端口上，
        //    该端口不等于服务端端口，所以此时不需要虚拟 IP 已经存在）
        val code = runCatching { EasyTierJNI.runNetworkInstance(toml) }.getOrElse { e ->
            fail("实例启动异常：${e.message}")
            return START_NOT_STICKY
        }
        if (code != 0) {
            fail("实例启动失败（$code）：${EasyTierJNI.getLastError() ?: "无更多信息"}")
            return START_NOT_STICKY
        }
        Log.i(TAG, "EasyTier 实例已启动：$instance")

        // 2) 建 TUN
        val pfd = runCatching { buildTun(ipv4, prefix) }.getOrElse { e ->
            EasyTierJNI.stopAllInstances()
            fail("创建 TUN 失败：${e.message}")
            return START_NOT_STICKY
        }
        if (pfd == null) {
            EasyTierJNI.stopAllInstances()
            fail("创建 TUN 失败：系统拒绝（VPN 未授权或已被其他应用占用）")
            return START_NOT_STICKY
        }
        vpnInterface = pfd

        // 3) 交给实例
        val fdResult = runCatching { EasyTierJNI.setTunFd(instance, pfd.fd) }.getOrElse { e ->
            shutdown()
            fail("TUN fd 传递异常：${e.message}")
            return START_NOT_STICKY
        }
        if (fdResult != 0) {
            // v2.7.0 的 FFI 在这个分支**不写错误信息**，所以这里必须自己给出可操作的猜测，
            // 否则用户只能看到一句「无更多信息」。实例启动是异步的：port_forward 绑定失败
            // 发生在 runNetworkInstance 返回之后，只能在这里才暴露出来。
            val instanceInfo = runCatching {
                EasyTierJNI.collectNetworkInfos(4096)?.takeIf { it.isNotBlank() }
            }.getOrNull()
            if (instanceInfo != null) Log.w(TAG, "实例信息：$instanceInfo")
            shutdown()
            fail(
                "TUN fd 被拒绝（$fdResult）：虚拟网卡没能交给 EasyTier。" +
                    "最常见的原因是转发端口已被占用（先确认没有别的程序在用它），" +
                    "或者上一次组网没停干净 —— 先点「停止组网」再重试"
            )
            return START_NOT_STICKY
        }

        state = State.Running
        lastError = null
        Log.i(TAG, "EasyTier 就绪：$ipv4/$prefix，TUN fd=${pfd.fd}")
        return START_STICKY
    }

    private fun buildTun(ipv4: String, prefix: Int): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Minexa EasyTier")
            .addAddress(ipv4, prefix)
            .addDnsServer("223.5.5.5")
            .addDnsServer("114.114.114.114")
            // 只把虚拟网段路由进 TUN：本机走 LAN/移动数据都不受影响，
            // 也就不会把 App 自己的流量绕进去（那是另一类回环）。
            .addRoute(networkOf(ipv4), prefix)

        // 关键：排除自己，否则 EasyTier 的出站 UDP 会被 TUN 捕获成回环
        runCatching { builder.addDisallowedApplication(packageName) }

        return builder.establish()
    }

    /** `10.144.144.7/24` → `10.144.144.0`（addRoute 要网络地址，不是主机地址）。 */
    private fun networkOf(ipv4: String): String {
        val parts = ipv4.split(".")
        if (parts.size != 4) return ipv4
        val octets = parts.map { it.toIntOrNull() ?: 0 }
        return "${octets[0]}.${octets[1]}.${octets[2]}.0"
    }

    private fun fail(reason: String) {
        Log.e(TAG, reason)
        lastError = reason
        state = State.Failed(reason)
        shutdown()
    }

    private fun shutdown() {
        // 顺序有意为之：先关 TUN 接口，再停 core。
        // 反过来的话 core 仍持有 fd 副本，接口不会消失 —— 实测 `am stop-service` 之后
        // `ip addr` 里 tun0 还挂着（10.144.144.7/24），只有 force-stop 进程才清掉。
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        runCatching { EasyTierJNI.stopAllInstances() }
        if (state !is State.Failed) state = State.Stopped
        runCatching { stopForeground(true) }
    }

    override fun onRevoke() {
        // 用户在系统设置里关掉 VPN，或另一个 VPN 抢了前台：必须真的停下来，
        // 否则 EasyTier 会以为 TUN 还在，继续往一个已关闭的 fd 写。
        Log.w(TAG, "VPN 授权被系统回收")
        fail("VPN 授权被系统回收")
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.easytier_notif_title))
            .setContentText(getString(R.string.easytier_notif_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}

/** 便于 UI 层发起启动/停止，不必自己拼 Intent。 */
internal object EasyTierVpnIntents {
    fun start(context: Context, settings: EasyTierSettings): Intent =
        Intent(context, EasyTierVpnService::class.java).apply {
            action = EasyTierVpnService.ACTION_START
            putExtra(EasyTierVpnService.EXTRA_TOML, settings.toToml())
            putExtra(EasyTierVpnService.EXTRA_INSTANCE, settings.instanceName)
            putExtra(EasyTierVpnService.EXTRA_IPV4, settings.virtualIp)
            putExtra(EasyTierVpnService.EXTRA_PREFIX, settings.tunPrefixLength)
            putExtra(EasyTierVpnService.EXTRA_PORT, settings.serverPort)
        }

    fun stop(context: Context): Intent =
        Intent(context, EasyTierVpnService::class.java).apply {
            action = EasyTierVpnService.ACTION_STOP
        }
}
