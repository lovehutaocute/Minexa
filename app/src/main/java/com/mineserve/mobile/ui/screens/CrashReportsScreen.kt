package com.mineserve.mobile.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.server.CrashInsight
import com.mineserve.mobile.server.CrashLogView
import com.mineserve.mobile.server.CrashReportAnalyzer
import com.mineserve.mobile.server.KeyLogExtractor
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.SegmentedTabs
import com.mineserve.mobile.ui.TrustStoreRepairUi
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.launch
import com.mineserve.mobile.data.skin.SkinTileSlots
import com.mineserve.mobile.ui.components.skinTileOverlay
import com.mineserve.mobile.ui.components.skinButtonColors
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.components.AppDialog

@Composable
fun CrashReportsScreen(vm: McViewModel, onBack: () -> Unit, onGoToJavaInstall: () -> Unit) {
    val reports by vm.crashReports.collectAsState()
    val content by vm.currentCrashContent.collectAsState()
    val analysis by vm.currentCrashAnalysis.collectAsState()
    LaunchedEffect(Unit) { vm.loadCrashReports() }
    Column(Modifier.fillMaxSize()) {
        // 统一返回栏：标题字号/位置与其它子页面一致，不再自绘一套 20sp 粗体标题
        BackBar(stringResource(R.string.crash_title), onBack)
        if (reports.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.crash_empty), color = Muted) }
        else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(reports, key = { it.path }, contentType = { "crash-report" }) { report ->
                val reportAnalysis = remember(report.preview) { CrashReportAnalyzer.analyze(report.preview) }
                // 结构化分析已生成时优先展示 sidecar 的主结论
                val label = report.primaryLabel.ifBlank { reportAnalysis.primaryLabel }
                // Java 版本问题（未安装/版本不满足）→ 提供前往安装的直达入口
                val javaIssue = reportAnalysis.findings.any { it.label.startsWith("Java") }
                ListItem(
                    headlineContent = { Text(report.fileName, maxLines = 1) },
                    supportingContent = {
                        Text(
                            buildString {
                                append(label)
                                if (!report.hasInsight) append(" · ").append(stringResource(R.string.crash_unanalyzed))
                                append(" | ").append(report.createdText).append(" | ").append(report.sizeText)
                            },
                            fontSize = 11.sp
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Warning, null, tint = Coral) },
                    trailingContent = {
                        if (javaIssue) {
                            TextButton(onClick = onGoToJavaInstall) {
                                Text(stringResource(R.string.env_go_install), color = Indigo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    },
                    modifier = Modifier.clickable { vm.readCrashReport(report.fileName) }
                )
                HorizontalDivider()
            }
        }
    }
}

private enum class CrashViewMode { Analysis, KeyLog, FullLog }

@Composable
fun CrashReportDialog(
    content: String,
    analysis: CrashReportAnalyzer.Analysis,
    insight: CrashInsight?,
    logView: CrashLogView?,
    onDismiss: () -> Unit,
    onGoToJavaInstall: (() -> Unit)? = null,
    onFixAction: (String) -> Unit = {},
    onFeedback: ((Boolean) -> Unit)? = null,
    repairState: TrustStoreRepairUi = TrustStoreRepairUi(),
    onRepairTrustStore: () -> Unit = {}
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(CrashViewMode.Analysis) }
    // 从「分析/关键日志」跳到「全部日志」的目标行（0 基）
    var scrollTarget by remember { mutableStateOf<Int?>(null) }
    var feedbackSent by remember { mutableStateOf<Boolean?>(null) }
    val javaIssue = analysis.findings.any { it.label.startsWith("Java") }
    // 文案必须在组合期解析：onClick 是非组合 lambda，里面不能调 stringResource。
    val shareTitle = stringResource(R.string.crash_share)

    val summary = buildString {
        appendLine(analysis.title)
        analysis.exitCode?.let { appendLine(stringResource(R.string.startup_report_exitcode, it)) }
        analysis.causedBy.forEach { appendLine("Caused by: $it") }
        analysis.findings.forEach {
            appendLine(stringResource(R.string.crash_finding, it.label, it.detail))
            appendLine(stringResource(R.string.crash_suggestion, it.suggestion))
        }
    }

    /** 分享的永远是完整包：结构化分析 + 本次启动全量日志（不含任何历史启动内容）。 */
    val shareText = buildString {
        appendLine(insight?.toMarkdown() ?: summary)
        appendLine()
        appendLine("--- ${stringResource(R.string.crash_tab_full_log)} ---")
        if (logView != null && logView.lines.isNotEmpty()) {
            logView.lines.forEach { appendLine(it) }
        } else {
            appendLine(content)
        }
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.crash_analysis_title))
                insight?.env?.let { env ->
                    Text(
                        "${env.core} ${env.coreVersion} · MC ${env.mcVersion} · Java ${env.javaVersion}".trim(),
                        fontSize = 11.sp,
                        color = Muted,
                        maxLines = 1
                    )
                }
            }
        },
        text = {
            // 高度按屏幕比例给：固定 480dp 在高屏上显得局促（日志只露出三四行），
            // 但小屏上又不能无限长，所以取 74% 屏高并夹在 480–620dp 之间 ——
            // 大屏变大，小屏维持原样，不会把底部按钮顶出对话框。
            val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
            val maxHeight = (screenH * 0.74f).dp.coerceIn(480.dp, 620.dp)
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                // 固定提示：请求帮助时应发送完整日志而非摘要
                Text(
                    stringResource(R.string.crash_share_hint),
                    color = Coral,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                SegmentedTabs(
                    options = listOf(
                        stringResource(R.string.crash_tab_analysis),
                        stringResource(R.string.crash_tab_key_log),
                        stringResource(R.string.crash_tab_full_log)
                    ),
                    selectedIndex = mode.ordinal,
                    onSelect = { index ->
                        CrashViewMode.entries.getOrNull(index)?.let { mode = it }
                    }
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f)) {
                    when (mode) {
                        CrashViewMode.Analysis -> AnalysisTab(
                            analysis = analysis,
                            insight = insight,
                            onJumpToLine = { line ->
                                scrollTarget = line
                                mode = CrashViewMode.FullLog
                            },
                            onFixAction = onFixAction,
                            repairState = repairState,
                            onRepairTrustStore = onRepairTrustStore,
                            feedbackSent = feedbackSent,
                            onFeedback = { resolved ->
                                feedbackSent = resolved
                                onFeedback?.invoke(resolved)
                            }
                        )
                        CrashViewMode.KeyLog -> KeyLogTab(
                            logView = logView,
                            onJumpToLine = { line ->
                                scrollTarget = line
                                mode = CrashViewMode.FullLog
                            }
                        )
                        CrashViewMode.FullLog -> FullLogTab(
                            logView = logView,
                            scrollTarget = scrollTarget,
                            onScrolled = { scrollTarget = null },
                            fallbackText = content
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (javaIssue && onGoToJavaInstall != null) {
                    TextButton(onClick = onGoToJavaInstall) {
                        Text(stringResource(R.string.env_go_install), color = Indigo, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                val copyText = when (mode) {
                    CrashViewMode.Analysis -> insight?.toMarkdown() ?: summary
                    CrashViewMode.KeyLog -> keyLogToText(logView)
                    CrashViewMode.FullLog -> logView?.lines?.joinToString("\n").orEmpty().ifBlank { content }
                }
                IconButton(onClick = { copy(context, copyText) }, modifier = Modifier.size(McIcon.Touch)) {
                    Icon(Icons.Outlined.ContentCopy, stringResource(R.string.crash_copy), tint = Indigo, modifier = Modifier.size(McIcon.Item))
                }
                IconButton(
                    onClick = {
                        context.startActivity(
                            Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }, shareTitle)
                        )
                    },
                    modifier = Modifier.size(McIcon.Touch)
                ) {
                    Icon(Icons.Outlined.IosShare, stringResource(R.string.crash_share_full), tint = Indigo, modifier = Modifier.size(McIcon.Item))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.s620)) }
            }
        }
    )
}

// ── 分析页 ──────────────────────────────────────────────────────

@Composable
private fun AnalysisTab(
    analysis: CrashReportAnalyzer.Analysis,
    insight: CrashInsight?,
    onJumpToLine: (Int) -> Unit,
    onFixAction: (String) -> Unit,
    repairState: TrustStoreRepairUi,
    onRepairTrustStore: () -> Unit,
    feedbackSent: Boolean?,
    onFeedback: (Boolean) -> Unit
) {
    // 证书库损坏是唯一定性明确、且能 App 内一键自愈的问题，单独给一张高亮卡。
    // 旧报告没有 insight，按规则文案兜底，避免老用户看不到入口。
    val cacertsIssue = insight?.let {
        it.primaryCause?.ruleId == "java-cacerts-broken" || it.fixes.any { fix -> fix.id == "repair-truststore" }
    } ?: analysis.findings.any { it.label.contains("证书") }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (insight == null) {
            item {
                Text(stringResource(R.string.crash_analysis_pending), fontSize = 12.sp, color = Muted)
            }
            if (cacertsIssue) {
                item { TrustStoreRepairCard(repairState, onRepairTrustStore) }
            }
            items(analysis.findings) { finding ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(finding.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        if (finding.detail.isNotBlank()) {
                            Text(finding.detail, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        if (finding.suggestion.isNotBlank()) {
                            Text(
                                stringResource(R.string.crash_suggestion, finding.suggestion),
                                fontSize = 12.sp,
                                color = Indigo,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
            return@LazyColumn
        }

        // 结论卡
        item {
            val cause = insight.primaryCause
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            cause?.label ?: stringResource(R.string.crash_unknown_cause),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = if (cause == null) Muted else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        cause?.let { ConfidenceBadge(it.confidence) }
                    }
                    cause?.explain?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    Text(
                        stringResource(
                            R.string.crash_scope_current,
                            formatTime(insight.session.startedAt),
                            insight.session.totalLines
                        ),
                        fontSize = 10.sp,
                        color = Muted,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (insight.session.fallbackToLatestLog) {
                        Text(stringResource(R.string.crash_scope_fallback), fontSize = 10.sp, color = Coral)
                    }
                    if (insight.onlineEnriched) {
                        Text(stringResource(R.string.crash_online_enriched), fontSize = 10.sp, color = Indigo)
                    }
                }
            }
        }

        // 一键修复：证书库损坏时最显眼的位置，先给能点的动作，再讲细节
        if (cacertsIssue) {
            item { TrustStoreRepairCard(repairState, onRepairTrustStore) }
        }

        // 证据（可回链）
        val evidence = insight.primaryCause?.evidence.orEmpty()
        if (evidence.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.crash_evidence_title)) }
            items(evidence, key = { "ev-${it.line}-${it.text.take(12)}" }) { ev ->
                Card(
                    Modifier.fillMaxWidth().clickable { onJumpToLine((ev.line - 1).coerceAtLeast(0)) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("L${ev.line}", fontSize = 10.sp, color = Indigo, fontFamily = FontFamily.Monospace)
                        Text(
                            ev.text,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 3,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        // 修复步骤
        if (insight.fixes.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.crash_fixes_title)) }
            items(insight.fixes, key = { "fix-${it.id}" }) { fix ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fix.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (fix.risk != "low") {
                                Text(
                                    stringResource(R.string.crash_fix_risk, fix.risk),
                                    fontSize = 10.sp,
                                    color = if (fix.risk == "high") Coral else Muted
                                )
                            }
                        }
                        fix.steps.forEachIndexed { index, step ->
                            Text("${index + 1}. $step", fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        fix.expectedEffect?.takeIf { it.isNotBlank() }?.let {
                            Text(stringResource(R.string.crash_fix_effect, it), fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                        }
                        fix.appAction?.let { action ->
                            TextButton(onClick = { onFixAction(action) }, contentPadding = PaddingValues(0.dp)) {
                                Text(stringResource(R.string.crash_fix_go), fontSize = 12.sp, color = Indigo, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        // 其他可能（并列展示，不静默丢弃）
        if (insight.cause.alternatives.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.crash_alternatives_title)) }
            items(insight.cause.alternatives, key = { "alt-${it.ruleId}-${it.label}" }) { alt ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "${alt.label} · ${confidenceText(alt.confidence)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    alt.rejectedReason?.let { Text(it, fontSize = 11.sp, color = Muted) }
                }
            }
        }

        // 相似历史
        if (insight.similar.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.crash_similar_title)) }
            items(insight.similar, key = { "sim-${it.crashId}" }) { sim ->
                Text(
                    stringResource(
                        R.string.crash_similar_item,
                        sim.causeLabel,
                        (sim.similarity * 100).toInt(),
                        resolutionText(sim.resolution)
                    ),
                    fontSize = 11.sp,
                    color = Muted
                )
            }
        }

        // 知识来源
        if (insight.knowledge.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.crash_knowledge_title)) }
            items(insight.knowledge, key = { "kb-${it.source}-${it.ref}" }) { ref ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text("[${knowledgeSourceText(ref.source)}]", fontSize = 10.sp, color = Indigo)
                    Text(ref.title, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        // 反馈闭环
        item {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                if (feedbackSent == null) {
                    Text(stringResource(R.string.crash_feedback_prompt), fontSize = 12.sp)
                    Row {
                        TextButton(onClick = { onFeedback(true) }) {
                            Text(stringResource(R.string.crash_feedback_yes), color = Indigo, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onFeedback(false) }) {
                            Text(stringResource(R.string.crash_feedback_no), color = Muted, fontSize = 12.sp)
                        }
                    }
                } else {
                    Text(stringResource(R.string.crash_feedback_thanks), fontSize = 11.sp, color = Muted)
                }
            }
        }
    }
}

// ── 一键修复卡（Java 证书库） ───────────────────────────────────

/**
 * 崩溃弹窗里的「一键修复」入口。
 *
 * 之所以做成卡片而不是只给一条文字建议：这个问题用户自己重装/换核心/补字体都没用
 * （2026-11 有用户反复重装应用仍失败），必须有一个按钮当场把证书库修好。
 */
@Composable
private fun TrustStoreRepairCard(state: TrustStoreRepairUi, onRepair: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, Indigo),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.crash_truststore_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                if (state.ok == true) {
                    Text(stringResource(R.string.crash_truststore_done), fontSize = 10.sp, color = Indigo, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                stringResource(R.string.crash_truststore_desc),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Muted,
                modifier = Modifier.padding(top = 4.dp)
            )
            state.message?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = if (state.ok == true) Indigo else Coral,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (state.running) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.crash_truststore_running), fontSize = 12.sp)
                }
            } else {
                Button(
                    onClick = onRepair,
                    modifier = Modifier.padding(top = 10.dp).skinTileOverlay(SkinTileSlots.PRIMARY_BUTTON, McShape.Row),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    colors = skinButtonColors(SkinTileSlots.PRIMARY_BUTTON, Indigo)) {
                    Text(
                        stringResource(
                            when (state.ok) {
                                null -> R.string.crash_truststore_action
                                true -> R.string.crash_truststore_again
                                false -> R.string.crash_truststore_retry
                            }
                        ),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// ── 关键日志页 ──────────────────────────────────────────────────

@Composable
private fun KeyLogTab(logView: CrashLogView?, onJumpToLine: (Int) -> Unit) {
    if (logView == null || logView.segments.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.crash_keylog_empty), color = Muted, fontSize = 12.sp)
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(
                stringResource(R.string.crash_keylog_summary, logView.segments.size, logView.keyLineCount),
                fontSize = 10.sp,
                color = Muted
            )
        }
        items(logView.segments, key = { "seg-${it.startLine}-${it.reason}" }) { seg ->
            Card(
                Modifier.fillMaxWidth().clickable { onJumpToLine((seg.startLine - 1).coerceAtLeast(0)) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(reasonText(seg.reason), fontSize = 10.sp, color = Indigo, fontWeight = FontWeight.SemiBold)
                        Text("  L${seg.startLine}-${seg.endLine}", fontSize = 10.sp, color = Muted, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.crash_keylog_locate), fontSize = 10.sp, color = Indigo)
                    }
                    seg.lines.take(24).forEach { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            maxLines = 3
                        )
                    }
                    if (seg.lines.size > 24) {
                        Text("…", fontSize = 10.sp, color = Muted)
                    }
                }
            }
        }
    }
}

// ── 全部日志页 ──────────────────────────────────────────────────

@Composable
private fun FullLogTab(
    logView: CrashLogView?,
    scrollTarget: Int?,
    onScrolled: () -> Unit,
    fallbackText: String
) {
    val lines = logView?.lines ?: fallbackText.lines()
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.crash_full_log_empty), color = Muted, fontSize = 12.sp)
        }
        return
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val matches = remember(query, lines) {
        if (query.isBlank()) emptyList() else lines.indices.filter { lines[it].contains(query, ignoreCase = true) }
    }
    var matchCursor by remember { mutableStateOf(0) }

    LaunchedEffect(scrollTarget) {
        scrollTarget?.let {
            state.scrollToItem(it.coerceIn(0, (lines.size - 1).coerceAtLeast(0)))
            onScrolled()
        }
    }
    LaunchedEffect(matches) {
        if (matches.isNotEmpty()) {
            matchCursor = 0
            state.scrollToItem(matches[0])
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 范围条：本次启动的唯一可见承诺
        Text(
            buildString {
                if (logView == null) {
                    // 旧版本报告没有会话日志 sidecar：不能假装是「本次启动」，必须明示。
                    append(stringResource(R.string.crash_scope_legacy))
                } else {
                    append(
                        stringResource(
                            R.string.crash_scope_current,
                            formatTime(logView.startedAt),
                            lines.size
                        )
                    )
                    if (logView.truncated) append(" · ").append(stringResource(R.string.crash_scope_truncated))
                    if (logView.fallbackToLatestLog) append(" · ").append(stringResource(R.string.crash_scope_fallback))
                }
            },
            fontSize = 10.sp,
            color = if (logView == null || logView.fallbackToLatestLog) Coral else Muted
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            placeholder = { Text(stringResource(R.string.crash_log_search_hint), fontSize = 12.sp) },
            trailingIcon = if (query.isBlank()) null else {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (matches.isEmpty()) stringResource(R.string.crash_log_no_match)
                            else stringResource(R.string.crash_log_matches, matches.size),
                            fontSize = 10.sp,
                            color = Muted
                        )
                        if (matches.isNotEmpty()) {
                            TextButton(onClick = {
                                matchCursor = (matchCursor + 1) % matches.size
                                scope.launch { state.animateScrollToItem(matches[matchCursor]) }
                            }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text("↓", fontSize = 12.sp, color = Indigo)
                            }
                        }
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { })
        )
        LazyColumn(state = state, modifier = Modifier.weight(1f)) {
            itemsIndexed(lines) { index, line ->
                val isMatch = query.isNotBlank() && line.contains(query, ignoreCase = true)
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isMatch) Indigo.copy(alpha = 0.10f) else Color.Transparent)
                        .padding(vertical = 1.dp)
                ) {
                    Text(
                        "${index + 1}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Muted,
                        modifier = Modifier.width(44.dp),
                        maxLines = 1
                    )
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = if (line.contains("Exception") || line.contains("ERROR") || line.contains("FATAL")) Coral
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ── 小组件 ──────────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Muted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ConfidenceBadge(confidence: String) {
    val color = when (confidence) {
        "high" -> Indigo
        "medium" -> Muted
        else -> Coral
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
        Text(
            stringResource(R.string.crash_confidence_label, confidenceText(confidence)),
            fontSize = 10.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun confidenceText(confidence: String): String = stringResource(
    when (confidence) {
        "high" -> R.string.crash_confidence_high
        "medium" -> R.string.crash_confidence_medium
        else -> R.string.crash_confidence_low
    }
)

@Composable
private fun reasonText(reason: KeyLogExtractor.Reason): String = stringResource(
    when (reason) {
        KeyLogExtractor.Reason.EXCEPTION -> R.string.crash_keylog_reason_exception
        KeyLogExtractor.Reason.WATCHDOG -> R.string.crash_keylog_reason_watchdog
        KeyLogExtractor.Reason.SEVERE -> R.string.crash_keylog_reason_severe
        KeyLogExtractor.Reason.CRASH_TAIL -> R.string.crash_keylog_reason_tail
    }
)

@Composable
private fun knowledgeSourceText(source: String): String = stringResource(
    when (source) {
        "L2" -> R.string.crash_knowledge_l2
        "L3" -> R.string.crash_knowledge_l3
        else -> R.string.crash_knowledge_l1
    }
)

@Composable
private fun resolutionText(resolution: String): String = stringResource(
    when (resolution) {
        "resolved" -> R.string.crash_feedback_yes
        "failed" -> R.string.crash_feedback_no
        else -> R.string.crash_unknown_cause
    }
)

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return "--:--"
    return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(millis))
}

private fun keyLogToText(logView: CrashLogView?): String {
    val view = logView ?: return ""
    return view.segments.joinToString("\n\n") { seg ->
        "[L${seg.startLine}-${seg.endLine}] " + seg.lines.joinToString("\n")
    }
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("Crash report", text))
}
