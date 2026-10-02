package com.mineserve.mobile.server.tunnel

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.server.tunnel.frpc.FrpcProcessManager
import com.mineserve.mobile.server.tunnel.frpc.FrpcRuntime
import java.io.File

/**
 * frp 隧道后端 —— 自建 frps 服务器。
 *
 * 配置直接由用户提供（粘贴 TOML 或导入文件），不做任何平台 API 调用。
 */
class FrpBackend(
    runtime: FrpcRuntime,
    processManager: FrpcProcessManager
) : FrpcBackend(runtime, processManager, TunnelType.Frp) {

    override fun prepareConfig(config: McConfig): File {
        val raw = config.frpConfigText.trim()
        if (raw.isBlank()) {
            throw IllegalArgumentException("请粘贴完整的 frpc.toml 配置文本，或导入配置文件")
        }

        val toml = sanitize(raw)
        val file = File(tunnelDir, "frpc.toml")
        file.writeText(toml)
        log("配置 ${toml.length} 字符")

        // 公网地址从用户配置里取：自建 frps 没有平台 API 可以问。
        // 走共享解析器而不是就地拼字符串 —— 缺 remotePort 时不应拼出 `addr:null`。
        pendingPublicUrl = FrpAddress.fromConfigText(toml)

        return file
    }

    @Volatile
    private var pendingPublicUrl: String? = null

    override fun onProcessStarted(config: McConfig) {
        // 地址在配置里就已经确定，不必等引擎输出；但状态推进仍交给
        // 「login to server success」那一行，避免进程活着就误报隧道通了。
    }

    override fun onProcessState(status: com.mineserve.mobile.data.TunnelStatus?, exitCode: Int?) {
        super.onProcessState(status, exitCode)
        if (status == com.mineserve.mobile.data.TunnelStatus.Running) {
            updateState(com.mineserve.mobile.data.TunnelStatus.Running, publicUrl = pendingPublicUrl)
        }
    }

    /**
     * 移除 `autoTLS` 字段。
     *
     * frp 上游已废弃该字段，带它的配置会让新版引擎直接
     * `json: unknown field "autoTLS"` 退出；而它又常出现在各类教程与旧配置里，
     * 用户粘贴进来是常态。静默删掉比报错让用户自己找更合适 —— 删掉它不影响
     * 任何实际行为（TLS 由 `transport.tls.enable` 控制）。
     */
    private fun sanitize(toml: String): String {
        val lines = toml.lines()
        val kept = lines.filterNot { line ->
            line.trim().substringBefore('=').trim().equals("autoTLS", ignoreCase = true)
        }
        if (kept.size != lines.size) log("已自动移除废弃字段 autoTLS")
        return kept.joinToString("\n")
    }
}
