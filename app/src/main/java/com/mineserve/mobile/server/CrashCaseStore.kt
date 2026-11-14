package com.mineserve.mobile.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地历史故障案例库（知识源 L2）。
 *
 * 每次崩溃生成结构化报告后落一条案例（指纹 + 根因 + 修复项），
 * 用户在分析页点「已解决 / 未解决」会回写 `resolution`：
 *  - 已解决：后续相似崩溃中该修复项权重更高；
 *  - 未解决：建议里会标注「上次无效」，避免反复推荐同一个无效操作。
 *
 * 存储：`filesDir/crash-cases/index.jsonl`（一行一条，损坏行跳过，容量 500 条 LRU）。
 * 纯 java.io 实现，可在 JVM 单测里直接跑。
 */
@Serializable
data class CrashCase(
    val crashId: String,
    val createdAt: Long,
    val core: String = "",
    val mcVersion: String = "",
    val javaMajor: String = "",
    val exceptionClass: String = "",
    val tokens: List<String> = emptyList(),
    val ruleId: String? = null,
    val causeLabel: String = "",
    val fixIds: List<String> = emptyList(),
    /** unknown | resolved | failed */
    val resolution: String = "unknown"
)

data class SimilarMatch(val case: CrashCase, val similarity: Double)

class CrashCaseStore(private val dir: File) {

    private val indexFile: File get() = File(dir, "index.jsonl")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): List<CrashCase> {
        val file = indexFile
        if (!file.isFile) return emptyList()
        return runCatching {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    if (line.isBlank()) null
                    else runCatching { json.decodeFromString(CrashCase.serializer(), line) }.getOrNull()
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun append(crashCase: CrashCase) {
        runCatching {
            dir.mkdirs()
            // 同一 crashId 重复生成（重新分析）时先剔除旧记录，避免反馈被旧条目覆盖。
            val existing = load().filterNot { it.crashId == crashCase.crashId }
            val merged = (existing + crashCase).takeLast(MAX_CASES)
            indexFile.writeText(merged.joinToString("\n") { json.encodeToString(CrashCase.serializer(), it) } + "\n")
        }
    }

    /** 用户反馈回写；返回是否找到对应案例。 */
    @Synchronized
    fun recordFeedback(crashId: String, resolved: Boolean): Boolean {
        val cases = load()
        val idx = cases.indexOfFirst { it.crashId == crashId }
        if (idx < 0) return false
        val updated = cases.toMutableList()
        updated[idx] = updated[idx].copy(resolution = if (resolved) "resolved" else "failed")
        runCatching {
            dir.mkdirs()
            indexFile.writeText(
                updated.joinToString("\n") { json.encodeToString(CrashCase.serializer(), it) } + "\n"
            )
        }
        return true
    }

    /**
     * 找相似历史案例。
     *
     * 评分：结构字段（核心/MC 版本/Java 大版本）0.45 + 异常 token Jaccard 0.35
     * + 首个异常类相同 0.20；阈值 0.55，取前 [k] 条。
     */
    fun similar(env: EnvInfo, exceptionClass: String, tokens: Set<String>, k: Int = 3): List<SimilarMatch> {
        val cases = load()
        if (cases.isEmpty()) return emptyList()
        return cases.map { case ->
            var score = 0.0
            if (case.core.isNotBlank() && case.core.equals(env.core, ignoreCase = true)) score += 0.20
            if (case.mcVersion.isNotBlank() && case.mcVersion == env.mcVersion) score += 0.10
            if (case.javaMajor.isNotBlank() && case.javaMajor == env.javaVersion) score += 0.15
            if (exceptionClass.isNotBlank() && case.exceptionClass == exceptionClass) score += 0.20
            val caseTokens = case.tokens.toSet()
            if (tokens.isNotEmpty() && caseTokens.isNotEmpty()) {
                val inter = tokens.count { it in caseTokens }.toDouble()
                val union = (tokens + caseTokens).size.toDouble()
                score += 0.35 * (inter / union)
            }
            SimilarMatch(case, score)
        }.filter { it.similarity >= MIN_SIMILARITY }
            .sortedByDescending { it.similarity }
            .take(k)
    }

    companion object {
        const val MAX_CASES = 500
        const val MIN_SIMILARITY = 0.55
    }
}
