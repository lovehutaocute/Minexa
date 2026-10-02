package com.mineserve.mobile.mcp

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [McpSkillDoc] 的导出内容测试。
 *
 * 重点盯两件事：**工具清单不能漂移**（它是给 AI 看的能力边界，缺了工具 AI 就不知道能用），
 * 以及**占位符不能漏替换**（我为了躲开 trimMargin 的公共缩进计算用了 `@@…@@` 槽位）。
 */
class McpSkillDocTest {

    private val endpoint = "http://192.168.1.99:8931/mcp"
    private val token = "059815834d211babcf72c6afac46492e"

    private fun tool(name: String) = McpToolDef(
        name = name,
        description = "描述 $name",
        inputSchema = buildJsonObject { }
    )

    @Test
    fun `导出文档带 frontmatter 与本机端点令牌`() {
        val doc = McpSkillDoc.build(endpoint, token, listOf(tool("a_tool")))
        assertTrue(doc.startsWith("---\nname: mineserve-mobile\n"))
        assertTrue(doc.contains("\"url\": \"$endpoint\""))
        assertTrue(doc.contains("\"Authorization\": \"Bearer $token\""))
        assertTrue(doc.contains("## 四、工具清单（1 个）"))
    }

    @Test
    fun `真实工具集里的每个工具都写进清单`() {
        val tools = McpToolCatalog.definitions()
        assertTrue("工具集不该是空的", tools.isNotEmpty())
        val doc = McpSkillDoc.build(endpoint, token, tools)
        tools.forEach { assertTrue("清单缺少工具 ${it.name}", doc.contains("- `${it.name}` —")) }
        assertTrue(doc.contains("## 四、工具清单（${tools.size} 个）"))
    }

    @Test
    fun `占位符全部被替换`() {
        val doc = McpSkillDoc.build(endpoint, token, listOf(tool("a_tool")))
        assertFalse("占位符残留：$doc", doc.contains("@@"))
    }

    @Test
    fun `令牌为空时提示尚未生成而不是空 Bearer`() {
        val doc = McpSkillDoc.build(endpoint, "", listOf(tool("a_tool")))
        assertTrue(doc.contains("Bearer 尚未生成"))
        // 空白令牌不能拼出 `Bearer \"` 这种会让客户端静默 401 的配置
        assertFalse(doc.contains("Bearer \""))
    }

    @Test
    fun `导出文件名是 SKILL_md`() {
        assertEquals("SKILL.md", McpSkillDoc.FILE_NAME)
    }

    /**
     * 正文提到的每个工具名都必须在 catalog 里。
     *
     * 工具清单做到了由 catalog 现算，但正文的「常用链路」是手写的：曾经写着
     * `list_crash_reports` / `analyze_logs` / `diagnose` 这些全仓不存在的名字，
     * AI 照着调用只会拿到 -32602。工具名清单换笔者会额外偷一次懒，所以这条单独看着。
     */
    @Test
    fun `正文里点名的工具都真实存在`() {
        val tools = McpToolCatalog.definitions()
        val known = tools.map { it.name }.toSet() + "a_tool"
        // 参数名（`pattern`、`lines`…）也写成行内代码，它们不是工具名。
        // 动态从 schema 里取，避免以后加参数又要手维护一份清单。
        val argNames = tools.flatMap { propNamesOf(it) }.toSet()
        val doc = McpSkillDoc.build(endpoint, token, listOf(tool("a_tool")))
        val mentioned = Regex("`([a-z][a-z0-9_]*)`")
            .findAll(doc)
            .map { it.groupValues[1] }
            .toSet()
        val unknown = mentioned.filter { it !in known && it !in argNames && it !in NON_TOOL_TOKENS }
        assertTrue("文档点名了不存在的工具：$unknown", unknown.isEmpty())
    }

    /** inputSchema 形如 `{"type":..., "properties":{...}, "required":[...]}`，参数名在 properties 里。 */
    private fun propNamesOf(tool: McpToolDef): List<String> {
        val props = tool.inputSchema["properties"] as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        return props.keys.toList()
    }

    /**
     * 行内代码里那些既不是工具名也不是**入参**的东西。
     *
     * 注意 `java`：它是 `get_runtime_info` 返回体里的字段名（键形如 `Termux.Java17`），
     * 不是任何工具的入参，也不该被当成工具名 —— 但单看正文无法区分，
     * 只能在这里显式登记。
     */
    private val NON_TOOL_TOKENS = setOf(
        "url", "type", "mcpServers", "headers", "Authorization", "true", "false",
        "java",
        "job", "N", "GET", "POST", "http", "https", "on", "off", "yes", "no"
    )
}
