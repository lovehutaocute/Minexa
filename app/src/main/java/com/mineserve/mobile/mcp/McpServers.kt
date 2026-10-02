package com.mineserve.mobile.mcp

import com.mineserve.mobile.data.InstalledCore
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/**
 * MCP 工具的「目标服务器」解析。
 *
 * 抽成独立对象是为了让原有工具与新增工具共用同一份语义 —— 名称与目录名都能匹配、
 * 都不区分大小写、缺省都取活动服务器、找不到时都带上已安装列表（调用方据此自我纠正，
 * 而不是拿到一句无从下手的 "not found"）。
 */
internal object McpServers {

    /**
     * 从配置里挑出目标服务器。
     * @param wanted 名称或目录名；空白/缺省表示「当前活动服务器」
     * @throws IllegalArgumentException 未选择活动服务器，或 [wanted] 不存在
     */
    fun pick(config: McConfig, wanted: String?): InstalledCore {
        val needle = wanted?.trim().orEmpty()
        if (needle.isEmpty()) {
            return config.installedCores.firstOrNull { it.name == config.activeCoreName }
                ?: throw IllegalArgumentException(
                    "No active server selected. Installed servers: " + nameList(config) +
                        ". Use select_server, or pass the server argument."
                )
        }
        return config.installedCores.firstOrNull { it.name.equals(needle, ignoreCase = true) }
            ?: config.installedCores.firstOrNull { it.dirName.equals(needle, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Server '$needle' not found. Installed servers: " + nameList(config)
            )
    }

    /** 读配置 + 解析目标服务器，供大部分工具直接用。 */
    suspend fun resolve(
        repo: ServerRepository,
        args: JsonObject?,
        key: String = "server"
    ): Pair<McConfig, InstalledCore> {
        val config = repo.configFlow.first()
        return config to pick(config, McpArgs.string(args, key))
    }

    /** 已安装服务器名列表，用于错误提示 */
    fun nameList(config: McConfig): String =
        config.installedCores.joinToString(", ") { it.name }.ifEmpty { "(none)" }
}
