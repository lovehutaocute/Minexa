package com.mineserve.mobile.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MCP 新增基础设施的纯 JVM 单测。
 *
 * 重点覆盖参数解析与任务注册表 —— 这两处是「客户端传的东西不可信」和
 * 「长任务跨线程读写」的集中点，出错时表现为难查的行为漂移（参数被静默忽略、
 * 任务状态永远 running 等），比编译错误更值得用测试钉住。
 */
class McpArgsAndJobsTest {

    // ── McpArgs ────────────────────────────────────────────────

    private fun obj(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun stringTreatsMissingAndJsonNullAlike() {
        assertNull(McpArgs.string(null, "path"))
        assertNull(McpArgs.string(obj(), "path"))
        // 客户端显式传 null 与不传，对业务代码而言都应该是「没给值」，
        // 否则会出现 "null" 这个字面量被当成合法路径的经典事故
        assertNull(McpArgs.string(obj("path" to JsonNull), "path"))
        assertEquals("world", McpArgs.string(obj("path" to JsonPrimitive("world")), "path"))
        // 数字也要能取出来（客户端可能把端口写成裸数字）
        assertEquals("25565", McpArgs.string(obj("port" to JsonPrimitive(25565)), "port"))
    }

    @Test
    fun intParsesOnlyRealIntegers() {
        assertEquals(25565, McpArgs.int(obj("port" to JsonPrimitive(25565)), "port"))
        assertEquals(25565, McpArgs.int(obj("port" to JsonPrimitive("25565")), "port"))
        assertEquals(-1, McpArgs.int(obj("port" to JsonPrimitive("-1")), "port"))
        assertNull(McpArgs.int(obj("port" to JsonPrimitive("25565abc")), "port"))
        assertNull(McpArgs.int(obj("port" to JsonPrimitive("")), "port"))
        assertNull(McpArgs.int(obj(), "port"))
    }

    @Test
    fun boolAcceptsCommonSpellingsButRejectsGarbage() {
        listOf("true", "TRUE", "1", "yes", "on").forEach {
            assertEquals(true, McpArgs.bool(obj("confirm" to JsonPrimitive(it)), "confirm"), "for '$it'")
        }
        listOf("false", "FALSE", "0", "no", "off").forEach {
            assertEquals(false, McpArgs.bool(obj("confirm" to JsonPrimitive(it)), "confirm"), "for '$it'")
        }
        // 关键：无法识别时必须是 null，不能默认成 false ——
        // 「没传 confirm」和「confirm=false」在删除类工具上语义完全不同
        assertNull(McpArgs.bool(obj("confirm" to JsonPrimitive("maybe")), "confirm"))
        assertNull(McpArgs.bool(obj(), "confirm"))
        assertNull(McpArgs.bool(obj("confirm" to JsonNull), "confirm"))
    }

    @Test
    fun stringListHandlesArraySingleAndCommaForms() {
        assertEquals(
            listOf("curl", "unzip"),
            McpArgs.stringList(obj("packages" to buildJsonArray {
                add(JsonPrimitive("curl")); add(JsonPrimitive("unzip"))
            }), "packages")
        )
        assertEquals(listOf("curl"), McpArgs.stringList(obj("packages" to JsonPrimitive("curl")), "packages"))
        assertEquals(
            listOf("curl", "unzip", "jq"),
            McpArgs.stringList(obj("packages" to JsonPrimitive("curl, unzip ,jq")), "packages")
        )
        // 空白与空元素要被剔除，否则会拼出空的 apt 参数
        assertEquals(
            listOf("curl"),
            McpArgs.stringList(obj("packages" to buildJsonArray {
                add(JsonPrimitive("  ")); add(JsonPrimitive("curl")); add(JsonNull)
            }), "packages")
        )
        assertTrue(McpArgs.stringList(obj(), "packages").isEmpty())
    }

    // ── McpJobRegistry ─────────────────────────────────────────

    private fun await(job: McpJobRegistry.Job, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!job.isFinished && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(job.isFinished, "job ${job.id} did not finish within ${timeoutMs}ms")
    }

    @Test
    fun jobRunsInBackgroundAndRecordsSummaryAndLog() {
        val registry = McpJobRegistry()
        val job = registry.submit("demo") { j ->
            j.log("step 1")
            j.log("step 2")
            "all good"
        }
        await(job)
        assertEquals(McpJobRegistry.State.Succeeded, job.state)
        assertEquals("all good", job.summary)
        assertEquals(listOf("step 1", "step 2"), job.tailLines(10))
        assertEquals("job-1", job.id)
        // 提交后立刻可查，保证调用方拿到 id 就能轮询
        assertNotNull(registry.get(job.id))
    }

    @Test
    fun failingJobIsCapturedAsFailedNotEmptyThread() {
        val registry = McpJobRegistry()
        val failing = registry.submit("boom") { throw IllegalStateException("apt lock held") }
        await(failing)
        assertEquals(McpJobRegistry.State.Failed, failing.state)
        assertTrue(failing.summary.contains("apt lock held"), "summary was '${failing.summary}'")
        assertTrue(failing.tailLines(10).any { it.contains("[error]") })

        // 执行器线程必须还活着 —— 如果异常逃逸出去，后续任务会永远卡在 running
        val next = registry.submit("after") { "ok" }
        await(next)
        assertEquals(McpJobRegistry.State.Succeeded, next.state)
    }

    @Test
    fun jobLogKeepsOnlyTheTail() {
        val registry = McpJobRegistry()
        val job = registry.submit("chatty") { j ->
            repeat(1200) { j.log("line $it") }
            "done"
        }
        await(job)
        val tail = job.tailLines(5000)
        assertTrue(tail.size <= 500, "log grew to ${tail.size} lines")
        assertEquals("line 1199", tail.last())
        // 环形保留的是尾部：最早的行应该已被丢弃
        assertFalse(tail.contains("line 0"))
    }

    @Test
    fun unknownJobIdReturnsNullInsteadOfThrowing() {
        assertNull(McpJobRegistry().get("job-99"))
    }

    @Test
    fun listJobsReturnsNewestFirst() {
        val registry = McpJobRegistry()
        val first = registry.submit("a") { "1" }
        val second = registry.submit("b") { "2" }
        await(first); await(second)
        assertEquals(listOf("job-2", "job-1"), registry.listNewestFirst().map { it.id })
    }

    // ── McpFileUtils 新增辅助 ───────────────────────────────────

    @Test
    fun safeRemoteFileNameStripsPathTraversal() {
        // %2F 解码后必须只剩最后一段：否则可以写到目标目录之外
        assertEquals("evil.jar", McpFileUtils.safeRemoteFileName("https://x/y/%2e%2e%2fevil.jar", "s"))
        assertEquals("mod.jar", McpFileUtils.safeRemoteFileName("https://x/dir/mod.jar", "s"))
        // 解码后是 `..` 时不能直接用，必须回退到净化过的默认名
        assertEquals("fallback.jar", McpFileUtils.safeRemoteFileName("https://x/%2e%2e", "fallback"))
        assertEquals("download.jar", McpFileUtils.safeRemoteFileName("https://x/%2e%2e", "../.."))
    }

    @Test
    fun digestMatchesKnownVectorAndRejectsUnknownAlgorithm() {
        val dir = Files.createTempDirectory("mcp-digest").toFile()
        try {
            val file = File(dir, "abc.txt").apply { writeText("abc") }
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                McpFileUtils.digestHex(file, "sha256")
            )
            assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", McpFileUtils.digestHex(file, "SHA1"))
            assertNull(McpFileUtils.digestHex(file, "crc32"))
            assertFalse(McpFileUtils.supportsDigest("crc32"))
            assertTrue(McpFileUtils.supportsDigest("md5"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
