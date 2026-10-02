package com.mineserve.mobile.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一个 MCP 工具的定义（协议数据，纯 JVM，可单测） */
data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

/** 工具执行结果：text 会作为 text content 返回给客户端 */
data class McpToolResult(val text: String, val isError: Boolean = false)

/** 一条 JSON-RPC 消息的处理结果 */
sealed class McpOutcome {
    /** 通知：无 id，无需响应体（HTTP 202） */
    object Notification : McpOutcome()

    /** 需要返回的 JSON 响应体（HTTP 200） */
    data class Response(val body: JsonObject) : McpOutcome()
}

/**
 * MCP 协议核心（Streamable HTTP 传输上的 JSON-RPC 2.0 分发）。
 * 纯 JVM 实现，不依赖 Android 类；工具执行通过回调注入，便于单元测试。
 */
object McpProtocol {

    const val SERVER_NAME = "Minexa"
    const val LATEST_PROTOCOL_VERSION = "2025-06-18"
    val SUPPORTED_PROTOCOL_VERSIONS = listOf("2024-11-05", "2025-03-26", "2025-06-18")

    private const val JSONRPC = "2.0"

    /**
     * 处理一条 JSON-RPC 消息文本。
     * @param executor tools/call 的执行回调（同步阻塞执行，由调用方决定线程模型）
     * @param tools 对外暴露的工具清单（tools/list 与 tools/call 校验都基于它）
     */
    fun handle(
        text: String,
        serverVersion: String,
        executor: (name: String, args: JsonObject?) -> McpToolResult,
        tools: List<McpToolDef> = McpToolCatalog.definitions()
    ): McpOutcome {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            return McpOutcome.Response(rpcError(null, -32700, "Parse error"))
        }
        val obj = root as? JsonObject
            ?: return McpOutcome.Response(rpcError(null, -32600, "Invalid Request"))
        val id = obj["id"]
        val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (method == null) {
            // 无 method：带 id 的是坏请求，否则当作未知通知忽略
            return if (id != null) McpOutcome.Response(rpcError(id, -32600, "Invalid Request"))
            else McpOutcome.Notification
        }
        if (id == null) return McpOutcome.Notification
        val params = obj["params"] as? JsonObject
        val result = when (method) {
            "initialize" -> initializeResult(params, serverVersion)
            "ping" -> buildJsonObject { }
            "tools/list" -> buildJsonObject {
                put("tools", JsonArray(tools.map { it.toJsonObject() }))
            }
            "tools/call" -> try {
                callTool(params, tools, executor)
            } catch (e: RpcException) {
                return McpOutcome.Response(rpcError(id, e.code, e.message ?: "Invalid params"))
            }
            "resources/list" -> buildJsonObject { put("resources", JsonArray(emptyList())) }
            "prompts/list" -> buildJsonObject { put("prompts", JsonArray(emptyList())) }
            else -> return McpOutcome.Response(rpcError(id, -32601, "Method not found: $method"))
        }
        return McpOutcome.Response(rpcResult(id, result))
    }

    /** initialize 版本协商：客户端请求的版本受支持则原样返回，否则返回最新版本 */
    fun initializeResult(params: JsonObject?, serverVersion: String): JsonObject {
        val requested = (params?.get("protocolVersion") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val version = if (requested != null && requested in SUPPORTED_PROTOCOL_VERSIONS) {
            requested
        } else {
            LATEST_PROTOCOL_VERSION
        }
        return buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject { })
                put("resources", buildJsonObject { })
                put("prompts", buildJsonObject { })
            })
            put("serverInfo", buildJsonObject {
                put("name", SERVER_NAME)
                put("version", serverVersion)
            })
        }
    }

    /** 协议级错误（区别于工具执行内的业务错误，后者通过 isError 在结果内表达） */
    private class RpcException(val code: Int, message: String) : Exception(message)

    private fun callTool(
        params: JsonObject?,
        tools: List<McpToolDef>,
        executor: (name: String, args: JsonObject?) -> McpToolResult
    ): JsonObject {
        val name = (params?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw RpcException(-32602, "Missing tool name")
        if (tools.none { it.name == name }) {
            throw RpcException(-32602, "Unknown tool: $name")
        }
        val args = params["arguments"] as? JsonObject
        val result = try {
            executor(name, args)
        } catch (e: Exception) {
            McpToolResult("Tool execution failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", result.text)
                })
            })
            if (result.isError) put("isError", true)
        }
    }

    private fun McpToolDef.toJsonObject(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", inputSchema)
    }

    private fun rpcResult(id: kotlinx.serialization.json.JsonElement, result: JsonObject): JsonObject =
        buildJsonObject {
            put("jsonrpc", JSONRPC)
            put("id", id)
            put("result", result)
        }

    private fun rpcError(id: kotlinx.serialization.json.JsonElement?, code: Int, message: String): JsonObject =
        buildJsonObject {
            put("jsonrpc", JSONRPC)
            if (id != null) put("id", id) else put("id", kotlinx.serialization.json.JsonNull)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }

    /** 解析整型参数（缺省返回 null） */
    fun intArg(args: JsonObject?, key: String): Int? =
        (args?.get(key) as? JsonPrimitive)?.content?.toIntOrNull()
}
