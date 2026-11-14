package com.mineserve.mobile.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 结构化崩溃分析报告。
 *
 * 与「给人看的纯文本摘要」不同，这份 JSON 是**机器可读的唯一真相**：
 * 崩溃详情页、MCP 工具、后续的案例库聚类都以它为准。
 * 字段命名保持稳定（`schemaVersion`），升级时只加字段、不改语义。
 */
@Serializable
data class CrashInsight(
    val schemaVersion: Int = 1,
    val crashId: String,
    val generatedAt: Long,
    val session: SessionInfo,
    val env: EnvInfo,
    val trigger: TriggerInfo,
    val extraction: ExtractionInfo,
    val cause: CauseBlock,
    val fixes: List<InsightFix> = emptyList(),
    val knowledge: List<KnowledgeRef> = emptyList(),
    val similar: List<SimilarCase> = emptyList(),
    val stats: StatsInfo = StatsInfo(),
    /** 是否引入了在线知识库结果（离线时恒为 false，UI 据此标注「联网补充」） */
    val onlineEnriched: Boolean = false
) {
    val primaryCause: InsightCause? get() = cause.primary

    /** 人读 Markdown（分享/上报用；不替代日志原文）。 */
    fun toMarkdown(): String = buildString {
        appendLine("# Minexa 崩溃分析报告")
        appendLine()
        appendLine("- 崩溃 ID：`$crashId`")
        appendLine("- 环境：${env.core} ${env.coreVersion} / MC ${env.mcVersion} / Java ${env.javaVersion}（${env.javaVendor}）")
        appendLine("- 退出码：${trigger.exitCode}${if (trigger.userStop) "（用户主动停止）" else ""}")
        appendLine("- 分析置信度：${primaryCause?.confidence ?: "low"}")
        appendLine()
        primaryCause?.let { c ->
            appendLine("## 根因")
            appendLine("**${c.label}**（${c.ruleId ?: c.source}）")
            appendLine()
            appendLine(c.explain)
            appendLine()
            if (c.evidence.isNotEmpty()) {
                appendLine("### 证据")
                c.evidence.forEach { appendLine("- 第 ${it.line} 行：`${it.text}`") }
                appendLine()
            }
        }
        if (fixes.isNotEmpty()) {
            appendLine("## 修复建议")
            fixes.forEachIndexed { index, fix ->
                appendLine("${index + 1}. **${fix.title}**（风险：${fix.risk}）")
                fix.steps.forEach { appendLine("   - $it") }
                fix.expectedEffect?.takeIf { it.isNotBlank() }?.let { appendLine("   - 预期效果：$it") }
            }
            appendLine()
        }
        if (cause.alternatives.isNotEmpty()) {
            appendLine("## 其他可能")
            cause.alternatives.forEach { appendLine("- ${it.label}（${it.confidence}）：${it.explain}") }
            appendLine()
        }
        if (similar.isNotEmpty()) {
            appendLine("## 相似历史故障")
            similar.forEach {
                appendLine("- `$it.crashId` ${it.causeLabel}（相似度 ${(it.similarity * 100).toInt()}%，结果：${it.resolution}）")
            }
            appendLine()
        }
        appendLine("## 会话统计")
        appendLine("- 会话：`${session.id}`，本次启动共 ${session.totalLines} 行，关键片段 ${session.keyLines} 行" +
            if (session.truncated) "（日志已截断，仅保留尾部）" else "")
        if (knowledge.isNotEmpty()) {
            appendLine()
            appendLine("## 知识来源")
            knowledge.forEach { appendLine("- [${it.source}] ${it.title}${it.url?.let { u -> "：$u" } ?: ""}") }
        }
    }

    companion object {
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(insight: CrashInsight): String = json.encodeToString(serializer(), insight)

        fun decode(text: String): CrashInsight? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

@Serializable
data class SessionInfo(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val totalLines: Int,
    val keyLines: Int,
    val truncated: Boolean = false,
    /** 捕获文件不可用、回退 latest.log 时为 true（UI 必须提示可能含少量历史行） */
    val fallbackToLatestLog: Boolean = false
)

@Serializable
data class EnvInfo(
    val core: String = "unknown",
    val coreVersion: String = "",
    val mcVersion: String = "",
    val javaVersion: String = "",
    val javaVendor: String = "",
    val heapMb: Int = 0,
    val android: String = "",
    val abi: String = "",
    val device: String = ""
)

@Serializable
data class TriggerInfo(
    val exitCode: Int,
    val uptimeMs: Long = 0,
    val autoRestart: Boolean = false,
    val userStop: Boolean = false
)

@Serializable
data class ExtractionInfo(
    val firstException: String? = null,
    val causedBy: List<String> = emptyList(),
    val classVersion: Int? = null,
    val components: List<String> = emptyList(),
    val markers: List<String> = emptyList(),
    val fingerprint: String = ""
)

@Serializable
data class CauseBlock(
    val primary: InsightCause? = null,
    val alternatives: List<InsightCause> = emptyList()
)

@Serializable
data class InsightCause(
    /** L1 内置规则 / L2 历史案例 / L3 在线知识 */
    val source: String,
    val ruleId: String? = null,
    val label: String,
    /** high / medium / low —— 只给分级，不给伪精确小数 */
    val confidence: String,
    val score: Double,
    val explain: String,
    val evidence: List<EvidenceLine> = emptyList(),
    /** 被排除的原因（例如仅启发式命中，无法解释核心症状） */
    val rejectedReason: String? = null
)

@Serializable
data class EvidenceLine(val line: Int, val text: String)

@Serializable
data class InsightFix(
    val id: String,
    val title: String,
    val steps: List<String> = emptyList(),
    val appAction: String? = null,
    val risk: String = "low",
    val expectedEffect: String? = null
)

@Serializable
data class KnowledgeRef(
    val source: String,
    val ref: String,
    val title: String,
    val url: String? = null,
    val updatedAt: String? = null,
    val similarity: Double? = null
)

@Serializable
data class SimilarCase(
    val crashId: String,
    val createdAt: Long,
    val causeLabel: String,
    val similarity: Double,
    val resolution: String
)

@Serializable
data class StatsInfo(
    val rulesEvaluated: Int = 0,
    val rulesMatched: Int = 0,
    val casesCompared: Int = 0
)
