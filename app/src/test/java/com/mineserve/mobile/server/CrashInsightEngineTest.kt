package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 结构化崩溃分析引擎的行为测试。
 *
 * 用 2026-11 真实用户日志回放：JDK cacerts 损坏导致 Forge/Fabric 安装器全部 HTTPS
 * 失败，旧版把它误诊成「字体运行库缺失」。这里断言新的三层分析链路能：
 *  1) 把 cacerts 规则排到主因；
 *  2) 给出可执行修复（重装 Java）；
 *  3) 证据能回链到具体行号；
 *  4) 相似历史案例与用户反馈能进入结论。
 */
class CrashInsightEngineTest {

    private fun ruleAsset(): File {
        val candidates = listOf(
            File("src/main/assets/rules/server_errors.json"),
            File("app/src/main/assets/rules/server_errors.json")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到规则文件：${candidates.joinToString { it.absolutePath }}")
    }

    @Before
    fun loadRules() {
        CrashReportAnalyzer.loadFromJson(ruleAsset().readText())
    }

    private val env = EnvInfo(
        core = "Fabric",
        coreVersion = "1.21.1",
        mcVersion = "1.21.1",
        javaVersion = "21",
        javaVendor = "Termux",
        heapMb = 2048,
        android = "14",
        abi = "arm64-v8a",
        device = "Test Device"
    )

    private val cacertsLog = """
        [12:00:00] [main/INFO]: Downloading Minecraft Server
        [12:00:01] [main/WARN]: fontconfig error: Cannot load default config file
        [12:00:05] [main/ERROR]: Exception in thread "main" java.lang.RuntimeException: Failed to setup fabric server
        at net.fabricmc.installer.ServerLauncher.main(ServerLauncher.java:56)
        Caused by: java.io.IOException: Request to https://launchermeta.mojang.com/mc/game/version_manifest_v2.json using DIRECT failed
        at net.fabricmc.installer.util.HttpClient.tryWithProxy(HttpClient.java:179)
        Caused by: javax.net.ssl.SSLException: [internal_error] Unexpected error: java.security.InvalidAlgorithmParameterException: the trustAnchors parameter must be non-empty
        at java.base/sun.security.ssl.X509TrustManagerImpl.checkServerTrusted(X509TrustManagerImpl.java:132)
        [12:00:06] [main/ERROR]: Server stop requested
    """.trimIndent()

    private fun input(logText: String) = CrashInsightEngine.Input(
        crashId = "crash_test_1",
        env = env,
        trigger = TriggerInfo(exitCode = 1),
        logText = logText,
        sessionId = "session-1",
        startedAt = 1_700_000_000_000,
        endedAt = 1_700_000_060_000
    )

    @Test
    fun `cacerts 损坏是主因且给出一键修复`() {
        val insight = CrashInsightEngine().analyze(input(cacertsLog))
        val primary = insight.primaryCause
        assertNotNull("应给出主因", primary)
        assertEquals("java-cacerts-broken", primary!!.ruleId)
        assertEquals("high", primary.confidence)
        assertTrue("主因必须带上证据：${primary.evidence}", primary.evidence.any { it.text.contains("trustAnchors") })
        assertTrue("证据行号必须有效：${primary.evidence.map { it.line }}", primary.evidence.all { it.line > 0 })

        val fix = insight.fixes.firstOrNull()
        assertNotNull("必须给出可执行修复", fix)
        // 一键修复：App 内直接执行重装/重建证书库，而不是把用户丢到环境页自己摸索。
        assertEquals("repair-truststore", fix!!.id)
        assertEquals("action:repair-truststore", fix.appAction)
        assertTrue("修复步骤不能为空", fix.steps.isNotEmpty())
    }

    @Test
    fun `fontconfig 命中只作为备选且标注被排除原因`() {
        val insight = CrashInsightEngine().analyze(input(cacertsLog))
        val fontAlt = insight.cause.alternatives.firstOrNull { it.ruleId == "fontconfig-missing" }
        assertNotNull("fontconfig 命中仍应展示，不静默丢弃", fontAlt)
        assertNotNull("备选需要说明为什么不作为主因", fontAlt!!.rejectedReason)
        assertTrue("备选不得排到主因之上", fontAlt.score < insight.primaryCause!!.score)
    }

    @Test
    fun `结构化字段与会话统计完整`() {
        val insight = CrashInsightEngine().analyze(input(cacertsLog))
        assertTrue(
            "首个异常应被提取：${insight.extraction.firstException}",
            insight.extraction.firstException!!.contains("Exception")
        )
        assertTrue(insight.extraction.causedBy.any { it.contains("trustAnchors") })
        assertTrue("应识别 cacerts 标记", insight.extraction.markers.contains("trustAnchors"))
        assertFalse(insight.extraction.fingerprint.isBlank())
        assertEquals(env, insight.env)
        assertEquals(1, insight.trigger.exitCode)
        assertTrue(insight.session.totalLines > 0)
        assertTrue("关键片段行数应大于 0", insight.session.keyLines > 0)
        assertEquals("session-1", insight.session.id)
    }

    @Test
    fun `Markdown 报告包含根因与修复`() {
        val md = CrashInsightEngine().analyze(input(cacertsLog)).toMarkdown()
        assertTrue(md.contains("Java 证书库缺失或损坏"))
        assertTrue(md.contains("修复建议"))
        assertTrue(md.contains("一键修复 Java 证书库"))
    }

    @Test
    fun `JSON 编解码可往返`() {
        val insight = CrashInsightEngine().analyze(input(cacertsLog))
        val decoded = CrashInsight.decode(CrashInsight.encode(insight))
        assertNotNull(decoded)
        assertEquals(insight.crashId, decoded!!.crashId)
        assertEquals(insight.primaryCause?.ruleId, decoded.primaryCause?.ruleId)
        assertEquals(insight.fixes.size, decoded.fixes.size)
    }

    @Test
    fun `历史案例写入后可被相似检索命中并在反馈后加权`() {
        val dir = Files.createTempDirectory("crash-cases").toFile().also { it.deleteRecursively() }
        val store = CrashCaseStore(dir)
        val engine = CrashInsightEngine(store)

        val first = engine.analyze(input(cacertsLog))
        assertEquals("首次分析应写入一条案例", 1, store.load().size)

        // 同一设备再次崩溃：结构字段一致 + 同一异常类 → 相似度必须过阈值
        val again = engine.analyze(input(cacertsLog).copy(crashId = "crash_test_2"))
        assertTrue("应命中相似历史案例：${again.similar}", again.similar.isNotEmpty())
        assertEquals("crash_test_1", again.similar.first().crashId)

        // 反馈后案例标记为已解决，并参与后续置信度加权
        assertTrue(store.recordFeedback(first.crashId, resolved = true))
        val resolved = store.load().first { it.crashId == first.crashId }
        assertEquals("resolved", resolved.resolution)
        dir.deleteRecursively()
    }

    @Test
    fun `无规则命中且无历史时导出空主因而不崩溃`() {
        val insight = CrashInsightEngine().analyze(input("一切正常，没有任何异常行。"))
        assertEquals(null, insight.primaryCause)
        assertTrue(insight.fixes.isNotEmpty() || insight.extraction.firstException == null)
    }
}
