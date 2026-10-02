package com.mineserve.mobile.server.tunnel

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.runtime.TermuxRuntime
import java.io.File

/**
 * ME Frp 隧道后端。
 *
 * 与 SakuraFrp 后端同构：用用户令牌调 api.mefrp.com 拉取官方成品 TOML 配置，
 * 写入文件后交给本应用自带的上游原版 frpc 运行 —— 无需自建服务器，也不需要
 * 安装各家的魔改客户端。
 */
class MeFrpBackend(
    termux: TermuxRuntime,
    binaryManager: BinaryManager
) : TermuxBackend(termux, binaryManager, TunnelType.MeFrp) {

    private val tunnelDir: File
        get() = File(termux.installer.rootDir, "home/tunnel").apply { mkdirs() }

    /**
     * 最近一次拉到的成品配置文本。
     *
     * 由 [buildArgs]（Dispatchers.IO）写、[onProcessStarted]（隧道监控线程）读，
     * 跨线程访问，必须加 @Volatile 才有可见性保证。
     */
    @Volatile
    private var lastConfigText: String = ""

    override suspend fun ensureBinary(): String? = binaryManager.ensureFrp()

    override fun buildArgs(config: McConfig, binary: String): List<String> {
        val token = config.meFrpToken.trim()
        if (token.isBlank()) {
            throw IllegalArgumentException("请先填写 ME Frp 访问令牌（Token），可在 mefrp.com 用户面板获取")
        }
        val tunnelId = config.meFrpTunnelId.trim()
        if (tunnelId.isBlank()) {
            throw IllegalArgumentException("请先在隧道列表中选择要启动的隧道")
        }
        // 拉取官方成品配置（阻塞 HTTP，调用方已在 IO 线程）
        val configText = ensureConsoleLog(MeFrpApi.proxyConfig(token, tunnelId))
        lastConfigText = configText
        val configFile = File(tunnelDir, "frpc-mefrp.toml")
        configFile.writeText(configText)
        log("ME Frp 配置已写入 (${configText.length} 字符)")
        return listOf("-c", configFile.absolutePath)
    }

    override fun parsePublicUrl(line: String): String? = null

    /** 从配置文本解析 serverAddr + remotePort 作为公网地址显示 */
    override fun onProcessStarted(config: McConfig) {
        val toml = lastConfigText
        val addr = Regex("""serverAddr\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1)
        val port = Regex("""remotePort\s*=\s*(\d+)""").find(toml)?.groupValues?.get(1)
            ?: config.localPort.toString()
        if (addr != null) {
            updateState(TunnelStatus.Running, publicUrl = "$addr:$port")
        } else {
            updateState(TunnelStatus.Running)
        }
    }

    override fun killProcess() {
        termux.execOnce("pkill", "-f", "frpc")
    }

    override fun diagnoseFailure(exitCode: Int, output: String): String {
        return when {
            output.contains("token") || output.contains("auth") || output.contains("登录") ->
                "ME Frp 认证失败：请检查访问令牌是否正确或已失效"
            output.contains("not found") || output.contains("不存在") || output.contains("no proxy") ->
                "隧道不存在或已被删除，请重新拉取隧道列表"
            output.contains("connection refused") || output.contains("timeout") ->
                "无法连接 ME Frp 节点，请稍后重试或更换节点"
            output.contains("port") && output.contains("already") ->
                "远程端口已被占用，请在 ME Frp 面板更换远程端口"
            else -> super.diagnoseFailure(exitCode, output)
        }
    }

    /** 成品配置若未指定日志输出，在顶层补 `log.to = "console"`（须在第一个 [[proxies]] 之前）。 */
    private fun ensureConsoleLog(config: String): String {
        if (config.contains("log.to")) return config
        val index = config.indexOf("[[")
        if (index < 0) return "$config\nlog.to = \"console\"\n"
        return config.substring(0, index) + "log.to = \"console\"\n" + config.substring(index)
    }
}
