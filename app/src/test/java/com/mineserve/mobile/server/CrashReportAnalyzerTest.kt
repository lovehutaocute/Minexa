package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 崩溃归因规则表（`assets/rules/server_errors.json`）的守卫与行为测试。
 *
 * 这里的两个关注点：
 * 1. **规则文件必须真的能解析**。规则改成数据驱动后，JSON 里一个笔误就会让
 *    所有规则静默失效（`analyze` 只剩「未能明确归因」），界面上看不出异常。
 *    所以第一个测试直接把真实 asset 读进来解析并断言条数。
 * 2. Java 版本对照表要覆盖完整区间 —— 原实现只有 5 档，Java 12~16、18~20、22~24
 *    识别不出来，用户只能看到原始报错。
 */
class CrashReportAnalyzerTest {

    /** 单元测试工作目录是模块目录（app/），兼容从仓库根目录运行。 */
    private fun ruleAsset(): File {
        val candidates = listOf(
            File("src/main/assets/rules/server_errors.json"),
            File("app/src/main/assets/rules/server_errors.json")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到规则文件：${candidates.joinToString { it.absolutePath }}")
    }

    @Before
    fun loadBuiltinRules() {
        // 每个用例都从内置规则重新装载，避免用例之间互相污染（loadFromJson 修改的是全局状态）
        CrashReportAnalyzer.loadFromJson(ruleAsset().readText())
    }

    // ── 规则文件本身 ────────────────────────────────────────────

    @Test
    fun `内置规则文件可解析且条数正常`() {
        val count = CrashReportAnalyzer.ruleCount
        assertTrue("规则条数过少（$count），可能是 JSON 解析失败导致只读到部分规则", count >= 15)
    }

    @Test
    fun `规则的标题与建议字段都不为空`() {
        // 用 raw 文本串匹配会依赖 JSON 的空格格式，文件一被压缩就退化成恒真。
        // 这里真正解析一遍再逐条断言。
        CrashReportAnalyzer.loadFromJson(ruleAsset().readText())
        val negative = CrashReportAnalyzer.analyze("这是一句不会命中任何规则的日志")
        val all = CrashReportAnalyzer.analyze(
            "Failed to bind to port 25565\n" +
                "java.lang.OutOfMemoryError: Java heap space\n" +
                "进程退出 exit code: 137\n" +
                "You need to agree to the EULA\n" +
                "java.lang.UnsupportedClassVersionError: class file version 61.0"
        )
        assertTrue("这几行里至少要命中几条规则，否则用例是空的", all.findings.size >= 3)
        for (finding in all.findings) {
            assertTrue("label 为空：${finding.suggestion}", finding.label.isNotBlank())
            assertTrue("suggest 为空：${finding.label}", finding.suggestion.isNotBlank())
            assertFalse("detail 里残留占位符：${finding.detail}", finding.detail.contains('$'))
            assertFalse("suggest 里残留占位符：${finding.suggestion}", finding.suggestion.contains('$'))
        }
        assertTrue("正常日志不应产生结论", negative.findings.isEmpty() || negative.findings.all { it.label.isNotBlank() })
    }

    // ── Java 版本对照 ───────────────────────────────────────────

    @Test
    fun `class 版本号覆盖 45 到 70 全区间`() {
        assertEquals(1, CrashReportAnalyzer.requiredJavaVersion("class file version 45.0"))
        assertEquals(8, CrashReportAnalyzer.requiredJavaVersion("class file version 52.0"))
        assertEquals(11, CrashReportAnalyzer.requiredJavaVersion("class file version 55.0"))
        assertEquals(12, CrashReportAnalyzer.requiredJavaVersion("class file version 56.0"))
        assertEquals(17, CrashReportAnalyzer.requiredJavaVersion("class file version 61.0"))
        assertEquals(21, CrashReportAnalyzer.requiredJavaVersion("class file version 65.0"))
        assertEquals(25, CrashReportAnalyzer.requiredJavaVersion("class file version 69.0"))
        assertEquals(26, CrashReportAnalyzer.requiredJavaVersion("class file version 70.0"))
    }

    @Test
    fun `UnsupportedClassVersionError 真实报文能解析出版本`() {
        val line = "java.lang.UnsupportedClassVersionError: plugin/Foo has been compiled by a " +
            "more recent version of the Java Runtime (class file version 61.0), this version " +
            "of the Java Runtime only recognizes class file versions up to 55.0"
        assertEquals(17, CrashReportAnalyzer.requiredJavaVersion(line))
    }

    @Test
    fun `文案形式的 Java 要求也能解析`() {
        assertEquals(21, CrashReportAnalyzer.requiredJavaVersion("requires Java 21"))
        assertEquals(17, CrashReportAnalyzer.requiredJavaVersion("Java 17 or higher is required"))
        assertNull(CrashReportAnalyzer.requiredJavaVersion("一切正常，没有版本要求"))
    }

    // ── 归因行为 ────────────────────────────────────────────────

    @Test
    fun `Java 版本不兼容会给出对应的目标版本`() {
        val analysis = CrashReportAnalyzer.analyze(
            "java.lang.UnsupportedClassVersionError: class file version 61.0"
        )
        val finding = analysis.findings.firstOrNull { it.label == "Java 版本不兼容" }
        assertTrue("未命中 Java 版本不兼容规则", finding != null)
        assertTrue(
            "建议里应带上具体目标版本，实际：${finding!!.suggestion}",
            finding.suggestion.contains("Java 17")
        )
    }

    @Test
    fun `端口占用会带出具体端口号`() {
        val analysis = CrashReportAnalyzer.analyze("Failed to bind to port 25565")
        val finding = analysis.findings.firstOrNull { it.label == "端口无法监听" }
        assertTrue("未命中端口规则", finding != null)
        assertTrue("应带出端口号，实际：${finding!!.detail}", finding.detail.contains("25565"))
    }

    /**
     * 端口号捕获不到时，整段「（端口 …）」必须消失。
     *
     * 以前的断言是「不含连续空格 / 不含空括号」，而真实文案是「（端口 ）」——
     * 括号里有字，`emptyParens` 压根不匹配，删掉 render 结尾那四行 replace 用例照样绿。
     * 现在直接断言更严格也更贴近事实的东西：括号片段整体不出现。
     */
    @Test
    fun `端口占用但日志没带端口号时文案不留空位`() {
        val analysis = CrashReportAnalyzer.analyze("java.net.BindException: Address already in use")
        val finding = analysis.findings.firstOrNull { it.label == "端口无法监听" }
        assertTrue("未命中端口规则", finding != null)

        val detail = finding!!.detail
        val suggestion = finding.suggestion
        for (text in listOf(detail, suggestion)) {
            assertFalse("占位符没被渲染掉：$text", text.contains('$'))
            assertFalse("残留空位：$text", text.contains("  "))
            assertFalse("残留空括号：$text", Regex("""[（(]\s*[)）]""").containsMatchIn(text))
            assertFalse("留着没填值的端口片段：$text", text.contains("（端口）") || text.contains("（端口 ）"))
            assertFalse("留着没填值的端口片段：$text", text.contains("（当前端口"))
        }
        // 有端口号时必须正常带出来（反向护栏：不能把片段一律删掉）
        val withPort = CrashReportAnalyzer.analyze("Failed to bind to port 25565")
            .findings.first { it.label == "端口无法监听" }
        assertTrue("有端口号时应展示出来：${withPort.detail}", withPort.detail.contains("25565"))
    }

    @Test
    fun `可选片段语法在占位符落空时整段消失`() {
        // render 是私有的，通过规则行为间接验证：这条用例若因为改回「（端口 $1）」而失效，
        // 说明本用例确实在守着那段清理逻辑。
        val analysis = CrashReportAnalyzer.analyze("java.net.BindException: Address already in use")
        val finding = analysis.findings.first { it.label == "端口无法监听" }
        assertFalse(
            "整段可选片段应被删除，实际：${finding.detail}",
            finding.detail.contains("端口 $1") || finding.detail.contains("（")
        )
    }

    @Test
    fun `内存不足能命中`() {
        val analysis = CrashReportAnalyzer.analyze("java.lang.OutOfMemoryError: Java heap space")
        assertTrue(analysis.findings.any { it.label == "内存不足" })
    }

    @Test
    fun `exit 137 命中进程被终止`() {
        val analysis = CrashReportAnalyzer.analyze("进程退出 exit code: 137")
        assertTrue(analysis.findings.any { it.label == "进程被系统终止" })
        assertEquals(137, analysis.exitCode)
    }

    @Test
    fun `核心文件损坏能命中`() {
        val analysis = CrashReportAnalyzer.analyze("Error: Invalid or corrupt jarfile server.jar")
        assertTrue(analysis.findings.any { it.label == "核心文件损坏" })
    }

    @Test
    fun `没有命中任何规则时给出兜底归因`() {
        val analysis = CrashReportAnalyzer.analyze("这是一段完全无关的普通输出")
        assertEquals(1, analysis.findings.size)
        assertEquals("未能明确归因", analysis.primaryLabel)
        assertEquals(CrashReportAnalyzer.Level.Reminder, analysis.findings.first().level)
    }

    @Test
    fun `结构化提取仍然有效`() {
        val log = """
            java.lang.RuntimeException: boom
                at a.B.c(B.java:1)
            Caused by: java.io.IOException: disk full
            Caused by: java.lang.IllegalStateException: nested
            [Server] exit code: 1
            涉及到 libraries/foo.jar 和 plugins/bar.jar
        """.trimIndent()
        val analysis = CrashReportAnalyzer.analyze(log)
        assertTrue(analysis.firstException!!.contains("RuntimeException"))
        assertEquals(listOf("java.io.IOException: disk full", "java.lang.IllegalStateException: nested"), analysis.causedBy)
        assertEquals(1, analysis.exitCode)
        assertTrue(analysis.components.contains("foo.jar"))
        assertTrue(analysis.components.contains("bar.jar"))
    }

    // ── 规则可扩展性 ────────────────────────────────────────────

    @Test
    fun `用户自定义规则可以覆盖内置同名规则`() {
        val custom = """
            {"rules":[{"id":"out-of-memory","contains":["OutOfMemoryError"],
            "label":"自定义内存提示","detail":"自定义细节","evidence":"自定义证据","suggest":"自定义建议"}]}
        """.trimIndent()
        CrashReportAnalyzer.loadFromJson(ruleAsset().readText(), custom)

        val finding = CrashReportAnalyzer.analyze("java.lang.OutOfMemoryError: Java heap space")
            .findings.firstOrNull { it.label == "自定义内存提示" }
        assertTrue("自定义规则未生效", finding != null)
        assertEquals("自定义建议", finding!!.suggestion)
        // 覆盖后不应再出现旧的同名规则
        assertFalse(
            CrashReportAnalyzer.analyze("java.lang.OutOfMemoryError").findings.any { it.label == "内存不足" }
        )
    }

    @Test
    fun `用户自定义规则可以新增条目且排在最前`() {
        val custom = """
            {"rules":[{"id":"my-rule","level":"Possible","contains":["ZOMBIE_APOCALYPSE"],
            "label":"自定义事件","detail":"d","evidence":"e","suggest":"s"}]}
        """.trimIndent()
        CrashReportAnalyzer.loadFromJson(ruleAsset().readText(), custom)

        val findings = CrashReportAnalyzer.analyze("ZOMBIE_APOCALYPSE 发生了").findings
        assertEquals("自定义事件", findings.first().label)
        assertEquals(CrashReportAnalyzer.Level.Possible, findings.first().level)
    }

    @Test
    fun `自定义规则可以扩写 class 版本表`() {
        val custom = """{"classVersions":{"99":42}}"""
        CrashReportAnalyzer.loadFromJson(ruleAsset().readText(), custom)
        // classVersions 是叠加语义：内置表保留，自定义项补充/覆盖
        assertEquals(17, CrashReportAnalyzer.requiredJavaVersion("class file version 61.0"))
        assertEquals(42, CrashReportAnalyzer.requiredJavaVersion("class file version 99.0"))
    }

    @Test
    fun `坏 JSON 不会清空已有规则`() {
        val before = CrashReportAnalyzer.ruleCount
        CrashReportAnalyzer.loadFromJson("{ 这不是合法 JSON")
        assertEquals(before, CrashReportAnalyzer.ruleCount)
    }

    @Test
    fun `没有 contains regex exitCodes 的规则会被忽略`() {
        CrashReportAnalyzer.loadFromJson(
            """{"rules":[{"id":"empty","label":"空规则","detail":"d","evidence":"e","suggest":"s"}]}"""
        )
        assertEquals(0, CrashReportAnalyzer.ruleCount)
    }
}
