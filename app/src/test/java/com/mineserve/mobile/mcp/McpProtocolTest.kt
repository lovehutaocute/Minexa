package com.mineserve.mobile.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {

    private val executorCalls = mutableListOf<Pair<String, JsonObject?>>()

    private val okExecutor: (String, JsonObject?) -> McpToolResult = { name, args ->
        executorCalls.add(name to args)
        McpToolResult("ok:$name")
    }

    private fun handle(text: String, executor: (String, JsonObject?) -> McpToolResult = okExecutor): McpOutcome =
        McpProtocol.handle(text, serverVersion = "1.0.0-test", executor = executor)

    private fun responseOf(text: String): JsonObject {
        val outcome = handle(text)
        assertTrue("expected Response but was $outcome", outcome is McpOutcome.Response)
        return (outcome as McpOutcome.Response).body
    }

    @Test
    fun parseErrorReturnsMinus32700WithNullId() {
        val body = responseOf("{not json")
        assertEquals(-32700, body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["id"] is kotlinx.serialization.json.JsonNull)
        assertEquals("2.0", body["jsonrpc"]!!.jsonPrimitive.content)
    }

    @Test
    fun notificationReturnsNoBody() {
        val outcome = handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(McpOutcome.Notification, outcome)
    }

    @Test
    fun initializeEchoesSupportedProtocolVersion() {
        val body = responseOf(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}"""
        )
        assertEquals("2025-03-26", body["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        val serverInfo = body["result"]!!.jsonObject["serverInfo"]!!.jsonObject
        assertEquals("MineServe Mobile", serverInfo["name"]!!.jsonPrimitive.content)
        assertEquals("1.0.0-test", serverInfo["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun initializeFallsBackToLatestForUnknownVersion() {
        val body = responseOf("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")
        assertEquals(McpProtocol.LATEST_PROTOCOL_VERSION, body["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsListContainsAllBuiltinTools() {
        val body = responseOf("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        val tools = body["result"]!!.jsonObject["tools"] as JsonArray
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf(
                // 服务器管理 / 控制台 / 文件 / 模组（原有）
                "get_server_status", "list_servers", "select_server", "start_server", "stop_server",
                "send_command", "get_console_logs", "run_termux_command",
                "list_files", "read_file", "write_file", "delete_file", "rename_file", "make_dir",
                "upload_file", "extract_archive", "import_server",
                "list_mods", "search_mods", "install_mod",
                // 运行环境与依赖
                "get_runtime_info", "install_java", "remove_java", "reinstall_java",
                "install_php", "remove_php", "install_dependencies", "remove_dependencies",
                "install_apt_package", "repair_runtime", "check_command",
                "set_download_mirror", "set_apt_mirror",
                // 核心与实例
                "list_core_versions", "create_server", "delete_server",
                // 应用配置
                "get_config", "set_config",
                // 快照
                "list_snapshots", "create_snapshot", "restore_snapshot", "delete_snapshot",
                // 插件
                "list_plugins", "install_plugin", "toggle_plugin", "delete_plugin",
                // 日志与诊断
                "get_app_log", "clear_app_log", "get_last_run_log", "get_device_info",
                "check_port", "http_probe",
                // 自动化测试辅助
                "wait_for_server", "wait_for_log", "file_hash",
                // 后台任务
                "get_job", "list_jobs"
            ),
            names
        )
        // 每个工具都有 object 类型的 inputSchema
        tools.forEach { assertTrue(it.jsonObject["inputSchema"] is JsonObject) }
    }

    @Test
    fun everyToolHasNonEmptyDescriptionAndObjectSchema() {
        val body = responseOf("""{"jsonrpc":"2.0","id":20,"method":"tools/list"}""")
        val tools = (body["result"]!!.jsonObject["tools"] as JsonArray).map { it.jsonObject }
        assertTrue(tools.isNotEmpty())
        tools.forEach { tool ->
            val name = tool["name"]!!.jsonPrimitive.content
            val description = tool["description"]!!.jsonPrimitive.content
            assertTrue("tool '$name' has no description", description.isNotBlank())
            val schema = tool["inputSchema"]!!.jsonObject
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        }
        // 工具名不允许重复（重复会让 tools/call 的分发产生歧义）
        val names = tools.map { it["name"]!!.jsonPrimitive.content }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun toolsCallPassesArgumentsAndWrapsTextContent() {
        val body = responseOf(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"send_command","arguments":{"command":"say hi"}}}"""
        )
        assertEquals(1, executorCalls.size)
        assertEquals("send_command", executorCalls[0].first)
        assertEquals("say hi", executorCalls[0].second?.get("command")?.jsonPrimitive?.content)
        val result = body["result"]!!.jsonObject
        val content = result["content"] as JsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("ok:send_command", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("isError"))
    }

    @Test
    fun toolsCallMarksErrorResults() {
        val body = McpProtocol.handle(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_console_logs","arguments":{"lines":"80"}}}""",
            serverVersion = "1.0.0-test",
            executor = { _, _ -> McpToolResult("boom", isError = true) }
        )
        val result = (body as McpOutcome.Response).body["result"]!!.jsonObject
        assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals("boom", (result["content"] as JsonArray)[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownToolIsMinus32602() {
        val body = responseOf("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nope"}}""")
        assertEquals(-32602, body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("nope"))
    }

    @Test
    fun unknownMethodIsMinus32601() {
        val body = responseOf("""{"jsonrpc":"2.0","id":6,"method":"bogus/method"}""")
        assertEquals(-32601, body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun pingReturnsEmptyResult() {
        val body = responseOf("""{"jsonrpc":"2.0","id":7,"method":"ping"}""")
        assertTrue(body["result"]!!.jsonObject.isEmpty())
    }

    @Test
    fun requestWithoutMethodAndIdIsInvalidRequest() {
        val body = responseOf("""{"jsonrpc":"2.0","id":8,"params":{}}""")
        assertEquals(-32600, body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun intArgParsesNumericStrings() {
        val args = Json.parseToJsonElement("""{"lines": "120"}""").jsonObject
        assertEquals(120, McpProtocol.intArg(args, "lines"))
        assertNull(McpProtocol.intArg(args, "missing"))
    }
}
