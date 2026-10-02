package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.server.CrashReportAnalyzer
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Muted

@Composable
fun CrashReportsScreen(vm: McViewModel, onBack: () -> Unit, onGoToJavaInstall: () -> Unit) {
    val reports by vm.crashReports.collectAsState()
    val content by vm.currentCrashContent.collectAsState()
    val analysis by vm.currentCrashAnalysis.collectAsState()
    LaunchedEffect(Unit) { vm.loadCrashReports() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.crash_back)) }
            Text(stringResource(R.string.crash_title), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        if (reports.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.crash_empty), color = Muted) }
        else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(reports, key = { it.path }, contentType = { "crash-report" }) { report ->
                val reportAnalysis = remember(report.preview) { CrashReportAnalyzer.analyze(report.preview) }
                val label = reportAnalysis.primaryLabel
                // Java 版本问题（未安装/版本不满足）→ 提供前往安装的直达入口
                val javaIssue = reportAnalysis.findings.any { it.label.startsWith("Java") }
                ListItem(
                    headlineContent = { Text(report.fileName, maxLines = 1) },
                    supportingContent = { Text("$label | ${report.createdText} | ${report.sizeText}", fontSize = 11.sp) },
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

private enum class CrashViewMode { Summary, RawReport, RunLog }

@Composable
fun CrashReportDialog(
    content: String,
    analysis: CrashReportAnalyzer.Analysis,
    runLog: String,
    onDismiss: () -> Unit,
    onGoToJavaInstall: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(CrashViewMode.Summary) }
    val javaIssue = analysis.findings.any { it.label.startsWith("Java") }
    val summary = buildString {
        appendLine(analysis.title)
        analysis.exitCode?.let { appendLine(stringResource(R.string.startup_report_exitcode, it)) }
        analysis.causedBy.forEach { appendLine("Caused by: $it") }
        analysis.findings.forEach {
            appendLine(stringResource(R.string.crash_finding, it.label, it.detail))
            appendLine(stringResource(R.string.crash_suggestion, it.suggestion))
        }
    }
    val displayedText = when (mode) {
        CrashViewMode.Summary -> summary
        CrashViewMode.RawReport -> content
        CrashViewMode.RunLog -> runLog.ifBlank { stringResource(R.string.crash_run_log_empty) }
    }
    val title = when (mode) {
        CrashViewMode.Summary -> stringResource(R.string.crash_analysis_title)
        CrashViewMode.RawReport -> stringResource(R.string.crash_raw_title)
        CrashViewMode.RunLog -> stringResource(R.string.crash_run_log_title)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            // 固定提示：请求帮助时应发送完整日志而非摘要，放在内容区顶部保证任何模式下都可见。
            Text(
                stringResource(R.string.crash_share_hint),
                color = Coral,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == CrashViewMode.Summary, onClick = { mode = CrashViewMode.Summary }, label = { Text(stringResource(R.string.crash_summary)) })
                FilterChip(selected = mode == CrashViewMode.RawReport, onClick = { mode = CrashViewMode.RawReport }, label = { Text(stringResource(R.string.crash_raw)) })
                FilterChip(selected = mode == CrashViewMode.RunLog, onClick = { mode = CrashViewMode.RunLog }, label = { Text(stringResource(R.string.crash_run_log)) })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                displayedText,
                fontFamily = if (mode == CrashViewMode.Summary) FontFamily.Default else FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
            )
        }
    }, confirmButton = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (javaIssue && onGoToJavaInstall != null) {
                TextButton(onClick = onGoToJavaInstall) {
                    Text(stringResource(R.string.env_go_install), color = Indigo, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = { copy(context, displayedText) }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.crash_copy), tint = Indigo) }
            // 分享的**始终是完整日志**：摘要只对当前分析有效，别人拿到摘要无法排查。
            // 完整文本 = 崩溃报告全文 + 本次运行完整日志（有则拼接）。
            val shareTitle = stringResource(R.string.crash_share)
            val fullText = buildString {
                appendLine(content)
                if (runLog.isNotBlank()) {
                    appendLine()
                    appendLine("--- ${stringResource(R.string.crash_run_log_title)} ---")
                    append(runLog)
                }
            }
            IconButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, fullText)
                }, shareTitle))
            }) { Icon(Icons.Outlined.IosShare, stringResource(R.string.crash_share_full), tint = Indigo) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.s620)) }
        }
    })
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("Crash report", text))
}
