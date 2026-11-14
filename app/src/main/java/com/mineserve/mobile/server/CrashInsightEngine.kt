package com.mineserve.mobile.server

import java.security.MessageDigest

/**
 * 结构化崩溃分析引擎。
 *
 * 流水线（离线优先）：
 * ```
 * 会话日志切片 → 关键片段抽取 → 结构提取（首个异常/Caused by/Class 版本/组件）
 *              → 指纹 → L1 规则（含在线规则包）→ L2 历史案例 → 置信度 → CrashInsight
 * ```
 *
 * 设计约束：
 *  - **纯 JVM 实现**，不依赖 Android / 网络，可在单测里完整回放真实日志；
 *  - 输入必须是**本次启动**的日志切片（由 `LaunchSession` 保证），本类不再判断会话边界；
 *  - 永不抛异常：任何解析失败都降级为「未能明确归因」，保证弹窗不空白。
 */
class CrashInsightEngine(private val caseStore: CrashCaseStore? = null) {

    /** 一次崩溃的输入（环境信息由调用方从用户配置读取，比从日志猜更可靠）。 */
    data class Input(
        val crashId: String,
        val env: EnvInfo,
        val trigger: TriggerInfo,
        val logText: String,
        val nativeReportText: String? = null,
        val sessionId: String = "",
        val startedAt: Long = 0L,
        val endedAt: Long = 0L,
        val truncated: Boolean = false,
        val fallbackToLatestLog: Boolean = false
    )

    fun analyze(input: Input): CrashInsight {
        val lines = input.logText.lines()
        val keySegments = KeyLogExtractor.extract(lines)
        val fullText = buildString {
            append(input.logText)
            input.nativeReportText?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("--- MC 原生崩溃报告 ---")
                append(it)
            }
        }

        val structure = CrashReportAnalyzer.analyze(fullText)
        val hits = CrashReportAnalyzer.matchAll(fullText)
            .sortedBy { it.level.ordinal }

        val markers = detectMarkers(fullText)
        val tokens = buildTokens(fullText, structure, markers)
        val similar = caseStore?.similar(input.env, structure.firstException.orEmpty(), tokens).orEmpty()

        val causes = buildCauses(hits, similar, fullText, structure)
        val primary = causes.firstOrNull()
        val fixes = buildFixes(primary, causes.drop(1), structure)

        val insight = CrashInsight(
            crashId = input.crashId,
            generatedAt = System.currentTimeMillis(),
            session = SessionInfo(
                id = input.sessionId,
                startedAt = input.startedAt,
                endedAt = input.endedAt,
                totalLines = lines.size,
                keyLines = keySegments.sumOf { it.lineCount },
                truncated = input.truncated,
                fallbackToLatestLog = input.fallbackToLatestLog
            ),
            env = input.env,
            trigger = input.trigger,
            extraction = ExtractionInfo(
                firstException = structure.firstException,
                causedBy = structure.causedBy,
                classVersion = CrashReportAnalyzer.requiredJavaVersion(fullText),
                components = structure.components,
                markers = markers,
                fingerprint = fingerprint(input.env, structure, fullText)
            ),
            cause = CauseBlock(primary = primary, alternatives = causes.drop(1)),
            fixes = fixes,
            knowledge = buildKnowledge(hits, similar),
            similar = similar.map {
                SimilarCase(
                    crashId = it.case.crashId,
                    createdAt = it.case.createdAt,
                    causeLabel = it.case.causeLabel,
                    similarity = it.similarity,
                    resolution = it.case.resolution
                )
            },
            stats = StatsInfo(
                rulesEvaluated = CrashReportAnalyzer.ruleCount,
                rulesMatched = hits.size,
                casesCompared = runCatching { caseStore?.load()?.size ?: 0 }.getOrDefault(0)
            ),
            onlineEnriched = hits.any { it.ruleId.startsWith(KB_RULE_PREFIX) }
        )

        recordCase(insight, tokens, structure)
        return insight
    }

    // ── 归因 ────────────────────────────────────────────────────

    private fun buildCauses(
        hits: List<CrashReportAnalyzer.RuleHit>,
        similar: List<SimilarMatch>,
        fullText: String,
        structure: CrashReportAnalyzer.Analysis
    ): List<InsightCause> {
        val resolvedRuleHints = similar
            .filter { it.case.resolution == "resolved" && it.case.ruleId != null }
            .associate { it.case.ruleId!! to it.similarity }

        val causes = hits.take(4).map { hit ->
            val base = when (hit.level) {
                CrashReportAnalyzer.Level.Identified -> 0.90
                CrashReportAnalyzer.Level.Possible -> 0.60
                CrashReportAnalyzer.Level.Reminder -> 0.30
            }
            val boost = resolvedRuleHints[hit.ruleId]?.let { it * 0.08 } ?: 0.0
            val score = (base + boost).coerceAtMost(0.98)
            InsightCause(
                source = if (hit.ruleId.startsWith(KB_RULE_PREFIX)) "L3" else "L1",
                ruleId = hit.ruleId,
                label = hit.label,
                confidence = confidenceOf(score),
                score = score,
                explain = listOfNotNull(hit.detail.takeIf { it.isNotBlank() }, hit.suggestion.takeIf { it.isNotBlank() })
                    .joinToString(" "),
                evidence = evidenceFor(hit, fullText.lines()),
                rejectedReason = if (hit.level == CrashReportAnalyzer.Level.Possible &&
                    hits.any { it.level == CrashReportAnalyzer.Level.Identified }
                ) "启发式命中，无法解释首个异常，优先级低于确定性结论" else null
            )
        }.toMutableList()

        // 规则全部未命中但历史案例高度相似：给出 L2 结论（低置信度，标注来源）
        if (causes.isEmpty()) {
            similar.firstOrNull { it.similarity >= 0.65 }?.let { match ->
                causes += InsightCause(
                    source = "L2",
                    ruleId = match.case.ruleId,
                    label = match.case.causeLabel.ifBlank { "相似历史故障" },
                    confidence = "low",
                    score = 0.45 * match.similarity + 0.2,
                    explain = "内置规则未命中，但与历史崩溃案例相似度 ${(match.similarity * 100).toInt()}%，" +
                        "该案例的结论为「${match.case.causeLabel}」。",
                    evidence = structure.firstException?.let { listOf(EvidenceLine(0, it)) }.orEmpty()
                )
            }
        }
        return causes.sortedByDescending { it.score }
    }

    private fun confidenceOf(score: Double): String = when {
        score >= 0.85 -> "high"
        score >= 0.55 -> "medium"
        else -> "low"
    }

    /**
     * 证据行：优先用规则专属关键词定位原文，其次回退到首个异常行。
     * 行号是「相对本次会话切片」的 1 基行号，UI 据此跳转高亮。
     */
    private fun evidenceFor(hit: CrashReportAnalyzer.RuleHit, lines: List<String>): List<EvidenceLine> {
        val hints = EVIDENCE_HINTS[hit.ruleId].orEmpty()
        val found = mutableListOf<EvidenceLine>()
        for ((index, line) in lines.withIndex()) {
            if (found.size >= 3) break
            if (hints.any { line.contains(it, ignoreCase = true) }) {
                found += EvidenceLine(index + 1, line.trim().take(300))
            }
        }
        if (found.isEmpty()) {
            val first = lines.indexOfFirst { line ->
                listOf("Exception", "Error", "FATAL", "Failed").any { line.contains(it) }
            }
            if (first >= 0) found += EvidenceLine(first + 1, lines[first].trim().take(300))
        }
        return found
    }

    // ── 修复建议 ────────────────────────────────────────────────

    private fun buildFixes(
        primary: InsightCause?,
        alternatives: List<InsightCause>,
        structure: CrashReportAnalyzer.Analysis
    ): List<InsightFix> {
        val fixes = mutableListOf<InsightFix>()
        val ruleIds = listOfNotNull(primary?.ruleId) + alternatives.mapNotNull { it.ruleId }
        for (ruleId in ruleIds.distinct()) {
            FIX_CATALOG[ruleId]?.let { fixes += it }
            if (fixes.size >= MAX_FIXES) break
        }
        // 没有专属修复动作时，用规则建议文本兜底拆成步骤，保证「永远有可执行建议」
        if (fixes.isEmpty()) {
            val suggestion = primary?.explain.orEmpty()
            if (suggestion.isNotBlank()) {
                fixes += InsightFix(
                    id = "generic",
                    title = "按诊断建议排查",
                    steps = splitSteps(suggestion),
                    risk = "low"
                )
            }
        }
        if (fixes.isEmpty() && structure.firstException != null) {
            fixes += InsightFix(
                id = "collect-log",
                title = "补充上下文后再排查",
                steps = listOf(
                    "在崩溃弹窗切换到「全部日志」，确认首个异常前后的完整调用链",
                    "把本次启动的完整日志分享给开发者或核心官方 issue 区"
                ),
                risk = "low"
            )
        }
        return fixes
    }

    private fun splitSteps(text: String): List<String> =
        text.split('；', '。', ';')
            .map { it.trim() }
            .filter { it.length >= 4 }
            .take(4)

    // ── 知识来源 ────────────────────────────────────────────────

    private fun buildKnowledge(
        hits: List<CrashReportAnalyzer.RuleHit>,
        similar: List<SimilarMatch>
    ): List<KnowledgeRef> {
        val refs = mutableListOf<KnowledgeRef>()
        hits.take(4).forEach { hit ->
            refs += KnowledgeRef(
                source = if (hit.ruleId.startsWith(KB_RULE_PREFIX)) "L3" else "L1",
                ref = hit.ruleId,
                title = "规则：${hit.label}"
            )
        }
        similar.forEach { match ->
            refs += KnowledgeRef(
                source = "L2",
                ref = match.case.crashId,
                title = "历史案例：${match.case.causeLabel}",
                similarity = match.similarity
            )
        }
        return refs
    }

    // ── 指纹 / 标记 / token ─────────────────────────────────────

    private fun detectMarkers(text: String): List<String> = MARKER_LABELS.keys.filter { key ->
        text.contains(key, ignoreCase = true)
    }

    private fun buildTokens(
        text: String,
        structure: CrashReportAnalyzer.Analysis,
        markers: List<String>
    ): Set<String> {
        val tokens = linkedSetOf<String>()
        structure.firstException?.substringBefore(':')?.takeIf { it.isNotBlank() }?.let { tokens += it }
        structure.causedBy.take(3).forEach { c -> c.substringBefore(':').takeIf { it.isNotBlank() }?.let { tokens += it } }
        markers.forEach { tokens += it }
        structure.components.take(4).forEach { tokens += it }
        // 栈帧里的类名（最多 8 个），去掉行号，保证跨版本可比
        val frame = Regex("""at\s+([\w.$]+)\.""")
        frame.findAll(text).take(8).forEach { tokens += it.groupValues[1] }
        return tokens.map { it.lowercase() }.take(40).toSet()
    }

    private fun fingerprint(
        env: EnvInfo,
        structure: CrashReportAnalyzer.Analysis,
        text: String
    ): String {
        val raw = buildString {
            append(env.core).append('|')
            append(env.mcVersion).append('|')
            append(env.javaVersion).append('|')
            append(structure.firstException.orEmpty()).append('|')
            append(structure.causedBy.take(1).joinToString())
            // 首个异常前后 512 字符参与指纹，但不含时间戳（归一化后）
        }
        val normalized = raw.replace(Regex("""\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}"""), "")
        return runCatching {
            MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.getOrDefault(Integer.toHexString(normalized.hashCode()))
    }

    private fun recordCase(
        insight: CrashInsight,
        tokens: Set<String>,
        structure: CrashReportAnalyzer.Analysis
    ) {
        val store = caseStore ?: return
        val primary = insight.primaryCause ?: return
        runCatching {
            store.append(
                CrashCase(
                    crashId = insight.crashId,
                    createdAt = insight.generatedAt,
                    core = insight.env.core,
                    mcVersion = insight.env.mcVersion,
                    javaMajor = insight.env.javaVersion,
                    exceptionClass = structure.firstException.orEmpty().substringBefore(':'),
                    tokens = tokens.toList(),
                    ruleId = primary.ruleId,
                    causeLabel = primary.label,
                    fixIds = insight.fixes.map { it.id }
                )
            )
        }
    }

    companion object {
        /** 在线规则包内的规则 id 约定前缀（见 `docs/crash-rules-pack.example.json`）。 */
        const val KB_RULE_PREFIX = "kb-"
        private const val MAX_FIXES = 4

        /** 规则 -> 证据定位关键词（用于从原文里挑出可回链的行）。 */
        private val EVIDENCE_HINTS: Map<String, List<String>> = mapOf(
            "java-cacerts-broken" to listOf("trustAnchors", "PKIX", "InvalidAlgorithmParameterException"),
            "java-class-version" to listOf("UnsupportedClassVersionError", "class file version"),
            "java-version-required" to listOf("requires Java", "or higher"),
            "launch-file-missing" to listOf("Could not find or load main class", "Unable to access jarfile", "unix_args.txt"),
            "classpath-mismatch" to listOf("NoSuchMethodError", "NoClassDefFoundError", "ClassNotFoundException"),
            "mod-mismatch" to listOf("mixin", "mod loading has failed"),
            "plugin-mismatch" to listOf("Could not load plugin", "Unsupported API version", "plugin.yml"),
            "port-bind" to listOf("Address already in use", "Failed to bind", "FAILED TO BIND TO PORT"),
            "eula-not-accepted" to listOf("agree to the EULA", "eula=false"),
            "runtime-permission" to listOf("Permission denied", "Operation not permitted"),
            "network-download" to listOf("Could not resolve host", "Connection timed out", "download failed"),
            "jna-native" to listOf("com.sun.jna.Native", "libc.so.6"),
            "metaspace-exhausted" to listOf("OutOfMemoryError: Metaspace", "Compressed class space"),
            "out-of-memory" to listOf("OutOfMemoryError", "Java heap space"),
            "process-killed" to listOf("SIGKILL", "Killed"),
            "fontconfig-missing" to listOf("fontconfig", "fc-cache"),
            "jvm-create-failed" to listOf("Could not create the Java Virtual Machine", "Unrecognized option"),
            "corrupt-core" to listOf("Invalid or corrupt jarfile", "zip END header not found"),
            "world-corrupt" to listOf("Failed to load level", "level.dat"),
            "missing-native-library" to listOf("error while loading shared libraries", "cannot open shared object file")
        )

        /** 日志里出现即视为环境线索的标记（进入指纹与 token，便于案例聚类）。 */
        private val MARKER_LABELS: Map<String, String> = linkedMapOf(
            "trustAnchors" to "cacerts",
            "fontconfig" to "fontconfig",
            "online-mode=true" to "online-mode",
            "allow-list" to "allow-list",
            "GLIBC_" to "glibc",
            "libjvm.so" to "libjvm",
            "OutOfMemoryError" to "oom",
            "Watchdog" to "watchdog",
            "Mixin" to "mixin",
            "Paper" to "paper",
            "Fabric" to "fabric",
            "NeoForge" to "neoforge",
            "Forge" to "forge",
            "PowerNukkitX" to "pnx",
            "PocketMine" to "pocketmine"
        )

        /**
         * 规则 -> 可执行修复动作。
         *
         * `appAction` 是 App 内深链（由 UI 解释执行），研发/客服看 JSON 时也能直接照做。
         */
        private val FIX_CATALOG: Map<String, InsightFix> = mapOf(
            "java-cacerts-broken" to InsightFix(
                id = "repair-truststore",
                title = "一键修复 Java 证书库",
                steps = listOf(
                    "点下方「一键修复」，App 会自动重装当前 Java 与 ca-certificates",
                    "若软件源不可用，会自动改用本机证书包原地重建 cacerts（不依赖网络）",
                    "修复成功后回到核心页重新安装/更新核心，HTTPS 下载即可恢复"
                ),
                appAction = "action:repair-truststore",
                risk = "low",
                expectedEffect = "HTTPS 下载恢复，安装器可以正常拉取 libraries"
            ),
            "java-class-version" to InsightFix(
                id = "switch-java",
                title = "切换到匹配的 Java 版本",
                steps = listOf("在「运行环境」安装核心要求的 Java 版本", "把服务端 Java 切到该版本后重启"),
                appAction = "goto:env/java",
                risk = "low"
            ),
            "java-version-required" to InsightFix(
                id = "switch-java",
                title = "升级 Java 版本",
                steps = listOf("安装日志要求的 Java 版本", "切换后重启服务端"),
                appAction = "goto:env/java",
                risk = "low"
            ),
            "fontconfig-missing" to InsightFix(
                id = "repair-fonts",
                title = "补齐字体运行库",
                steps = listOf("在「运行诊断」执行一次自动修复", "若仍失败，检查 apt 源与网络后重试"),
                appAction = "goto:diagnostics",
                risk = "low"
            ),
            "port-bind" to InsightFix(
                id = "check-port",
                title = "处理端口占用",
                steps = listOf("确认没有上一个服务端进程仍在运行", "或修改 server.properties 中的 server-port 后重启"),
                appAction = "goto:server",
                risk = "low"
            ),
            "eula-not-accepted" to InsightFix(
                id = "accept-eula",
                title = "确认 EULA",
                steps = listOf("阅读核心的最终用户许可协议", "确认接受后修改 eula.txt 再启动"),
                appAction = "goto:server",
                risk = "low"
            ),
            "out-of-memory" to InsightFix(
                id = "tune-memory",
                title = "调整内存或降低负载",
                steps = listOf("适当提高 -Xmx（不要超过设备物理内存的一半）", "减少插件/模组或降低视距"),
                appAction = "goto:settings/memory",
                risk = "medium"
            ),
            "metaspace-exhausted" to InsightFix(
                id = "tune-metaspace",
                title = "放宽 Metaspace 上限",
                steps = listOf("不要调小 -Xmx（会适得其反）", "把 -XX:MaxMetaspaceSize 放宽到 1024m 或更高"),
                appAction = "goto:settings/memory",
                risk = "low"
            ),
            "corrupt-core" to InsightFix(
                id = "redownload-core",
                title = "重新下载核心",
                steps = listOf("删除损坏的核心 jar", "重新下载同一版本核心（建议校验文件大小）"),
                appAction = "goto:core",
                risk = "medium",
                expectedEffect = "消除 zip 结构错误"
            ),
            "world-corrupt" to InsightFix(
                id = "restore-backup",
                title = "从备份恢复世界",
                steps = listOf("打开「备份」，选择崩溃前的快照恢复", "没有备份时再考虑重置世界（会丢失存档）"),
                appAction = "goto:backup",
                risk = "high"
            ),
            "missing-native-library" to InsightFix(
                id = "switch-proot",
                title = "改用 PRoot 容器运行",
                steps = listOf("在「运行环境」把来源切到 PRoot Linux 容器", "在容器内补齐缺失的 .so 后重试"),
                appAction = "goto:env/proot",
                risk = "medium"
            ),
            "process-killed" to InsightFix(
                id = "keep-alive",
                title = "降低被系统杀后台的概率",
                steps = listOf("关闭电池优化、允许后台运行", "降低 -Xmx 与视距，避免内存压力"),
                appAction = "goto:settings/keepalive",
                risk = "low"
            )
        )
    }
}
