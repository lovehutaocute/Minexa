package com.mineserve.mobile.server.tunnel

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.server.tunnel.frpc.FrpcProcessManager
import com.mineserve.mobile.server.tunnel.frpc.FrpcRuntime
import java.io.File

/**
 * SakuraFrp 隧道后端。
 *
 * 用访问密钥调 api.natfrp.com 拉取成品 TOML 配置，写入文件后交给内置引擎运行。
 * 与 [MeFrpBackend] 同构，差别只在 API 客户端。
 */
class SakuraFrpBackend(
    runtime: FrpcRuntime,
    processManager: FrpcProcessManager
) : FrpcBackend(runtime, processManager, TunnelType.SakuraFrp) {

    @Volatile
    private var pendingPublicUrl: String? = null

    override fun prepareConfig(config: McConfig): File {
        val token = config.sakuraToken.trim()
        if (token.isBlank()) {
            throw IllegalArgumentException("请先在 sakurafrp.com 用户面板获取访问密钥并填写")
        }
        val tunnelId = config.sakuraTunnelId.trim()
        if (tunnelId.isBlank()) {
            throw IllegalArgumentException("请先在隧道列表中选择要启动的隧道")
        }

        val configText = SakuraFrpApi.tunnelConfig(token, tunnelId)
        val file = File(tunnelDir, "frpc-sakura.toml")
        file.writeText(ensureConsoleLog(configText))

        // 同 MeFrpBackend：优先用平台 API 算好的 remoteAddress，配置文本只作兜底。
        pendingPublicUrl = resolvePublicUrl(token, tunnelId, configText)

        return file
    }

    /**
     * 解析玩家可用的公网地址。
     *
     * 优先查平台 API 的隧道对象（SakuraFrp 的 `remote` 字段已含 `host:port`），
     * 失败再退回配置文本解析。API 查询失败不该让隧道启动失败 —— 地址只是展示信息。
     */
    private fun resolvePublicUrl(token: String, tunnelId: String, configText: String): String? {
        val fromApi = runCatching {
            SakuraFrpApi.tunnelList(token)
                .firstOrNull { it.id == tunnelId }
                ?.let { tunnel ->
                    FrpAddress.build(
                        remoteAddress = tunnel.remoteAddress,
                        nodeName = null,
                        remotePort = tunnel.remotePort
                    )
                }
        }.getOrNull()

        if (fromApi != null) return fromApi

        log("[sakura] 未能从平台 API 取到公网地址，改用配置文本解析")
        return FrpAddress.fromConfigText(configText)
    }

    override fun onProcessState(status: TunnelStatus?, exitCode: Int?) {
        super.onProcessState(status, exitCode)
        if (status == TunnelStatus.Running) {
            updateState(TunnelStatus.Running, publicUrl = pendingPublicUrl)
        }
    }

    /**
     * 成品配置若未指定日志输出，在顶层补 `log.to = "console"`。
     *
     * 不补的话引擎默认把日志写进文件而不是 stdout，应用就读不到任何输出 ——
     * 既解析不出「启动成功」，出问题时也看不到错误原因。
     *
     * 必须插在第一个 `[[proxies]]` 之前：顶层键写到表头之后就变成那条代理的字段了。
     * 已经指定过日志输出（`log.to` 或整张 `log` 内联表）时一律不动，
     * 让引擎按平台给的设置走。
     */
    private fun ensureConsoleLog(config: String): String {
        if (config.contains("log.to") || config.contains("log =")) return config
        val index = config.indexOf("[[")
        if (index < 0) return "$config\nlog.to = \"console\"\n"
        return config.substring(0, index) + "log.to = \"console\"\n" + config.substring(index)
    }

    override fun diagnoseFailure(exitCode: Int, output: String): String = when {
        output.contains("token") || output.contains("auth") || output.contains("认证") ->
            "SakuraFrp 认证失败：请检查访问密钥是否正确或已失效"
        output.contains("not found") || output.contains("隧道不存在") ->
            "隧道不存在或已被删除，请重新拉取隧道列表"
        output.contains("connection refused") || output.contains("timeout") ->
            "无法连接 SakuraFrp 节点，请稍后重试或更换节点"
        else -> super.diagnoseFailure(exitCode, output)
    }
}
