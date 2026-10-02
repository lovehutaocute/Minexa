package com.mineserve.mobile.server.tunnel

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.server.tunnel.frpc.FrpcProcessManager
import com.mineserve.mobile.server.tunnel.frpc.FrpcRuntime
import java.io.File

/**
 * ME Frp 隧道后端。
 *
 * 用用户令牌调 api.mefrp.com 拉取官方成品 TOML 配置，写入文件后交给内置引擎运行 ——
 * 不需要自建服务器，也不需要安装 ME Frp 的客户端。
 *
 * 与 SakuraFrp 后端同构，差别只在 API 客户端与地址解析方式。
 */
class MeFrpBackend(
    runtime: FrpcRuntime,
    processManager: FrpcProcessManager
) : FrpcBackend(runtime, processManager, TunnelType.MeFrp) {

    /**
     * 最近一次拉到的成品配置文本。
     *
     * [prepareConfig]（IO 线程）写、[onProcessState]（引擎输出线程）读，跨线程访问，
     * 必须 @Volatile 才有可见性。
     */
    @Volatile
    private var lastConfigText: String = ""

    @Volatile
    private var pendingPublicUrl: String? = null

    override fun prepareConfig(config: McConfig): File {
        val token = config.meFrpToken.trim()
        if (token.isBlank()) {
            throw IllegalArgumentException("请先填写 ME Frp 访问令牌（Token），可在 mefrp.com 用户面板获取")
        }
        val tunnelId = config.meFrpTunnelId.trim()
        if (tunnelId.isBlank()) {
            throw IllegalArgumentException("请先在隧道列表中选择要启动的隧道")
        }

        // 阻塞 HTTP；调用方（TunnelBackend.start）已在 IO 线程。
        val configText = MeFrpApi.proxyConfig(token, tunnelId)
        lastConfigText = configText

        val file = File(tunnelDir, "frpc-mefrp.toml")
        file.writeText(configText)

        // 公网地址优先取平台 API 的 remoteAddress。
        //
        // 曾经这里只从配置文本里正则抓 serverAddr + remotePort，结果是：
        // ME Frp 的 serverAddr 指向节点（如 frp.mefrp.com），而玩家该连的是平台
        // 分配的接入地址；域名类隧道还没有 remotePort，于是拼出 `addr:null`。
        // 平台 API 里本来就有算好的 remoteAddress，用它即可。
        pendingPublicUrl = resolvePublicUrl(token, tunnelId, configText)

        // 把配置逐行打进日志（值打码）。引擎遇到不认识的字段只报
        // `json: unknown field "xxx"`，不报行号也不回显配置；没有这段回显，
        // 用户看到「配置 343 字符」这一句根本无从判断是哪一行的写法有问题。
        dumpConfigMasked(configText)

        return file
    }

    /**
     * 解析玩家可用的公网地址。
     *
     * 优先查平台 API 的隧道对象（那里有平台算好的 `remoteAddress`），失败再退回
     * 从配置文本里解析。API 查询失败**不该**让隧道启动失败 —— 地址只是展示信息，
     * 拿不到顶多界面上少一行，隧道本身照样能通。
     */
    private fun resolvePublicUrl(token: String, tunnelId: String, configText: String): String? {
        val fromApi = runCatching {
            MeFrpApi.proxyList(token)
                .firstOrNull { it.id == tunnelId }
                ?.let { proxy ->
                    FrpAddress.build(
                        remoteAddress = proxy.remoteAddress,
                        nodeName = proxy.nodeName,
                        remotePort = proxy.remotePort
                    )
                }
        }.getOrNull()

        if (fromApi != null) return fromApi

        log("[mefrp] 未能从平台 API 取到公网地址，改用配置文本解析")
        return FrpAddress.fromConfigText(configText)
    }

    override fun onProcessState(status: TunnelStatus?, exitCode: Int?) {
        super.onProcessState(status, exitCode)
        if (status == TunnelStatus.Running) {
            updateState(TunnelStatus.Running, publicUrl = pendingPublicUrl)
        }
    }

    /**
     * 逐行回显配置，值替换成 `***(长度)`。
     *
     * 为什么打码：成品配置里带 `auth.token`（等同于账号凭据）。原文照打的话，
     * 用户把日志贴出来求助时会把 token 一起送出去。键名与长度保留，
     * 足以定位是哪一行、哪个字段、有没有被截断。
     */
    private fun dumpConfigMasked(configText: String) {
        log("── ME Frp 配置原文（值已打码）──")
        configText.lines().forEachIndexed { index, line ->
            log("  ${index + 1}: ${maskValue(line.trim())}")
        }
        log("── 配置原文结束 ──")
    }

    /** 保留键名，把值换成 `***(长度)`。 */
    private fun maskValue(line: String): String {
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) return line
        val eq = line.indexOf('=')
        if (eq < 0) return line
        return "${line.substring(0, eq).trim()} = ***(${line.substring(eq + 1).trim().length})"
    }

    override fun diagnoseFailure(exitCode: Int, output: String): String = when {
        output.contains("unknown field") ->
            "本地 frpc 引擎不认识平台下发的配置字段（见上方引擎日志里的字段名）。" +
                "请对照上方「ME Frp 配置原文」定位，并导入更新版本的 libfrpc.so。"
        output.contains("token") || output.contains("auth") ->
            "ME Frp 认证失败：请检查访问令牌是否正确或已失效"
        output.contains("not found") || output.contains("no proxy") ->
            "隧道不存在或已被删除，请重新拉取隧道列表"
        output.contains("connection refused") || output.contains("timeout") ->
            "无法连接 ME Frp 节点，请稍后重试或更换节点"
        else -> super.diagnoseFailure(exitCode, output)
    }
}
