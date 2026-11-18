package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.data.DiagnosticCheck
import com.mineserve.mobile.data.DiagnosticStatus
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import com.mineserve.mobile.data.skin.SkinTileSlots
import com.mineserve.mobile.ui.components.skinTileOverlay
import com.mineserve.mobile.ui.components.skinButtonColors

@Composable
fun DiagnosticsScreen(vm: McViewModel, onBack: () -> Unit) {
    val report by vm.diagnosticReport.collectAsState()
    val isDiagnosing by vm.isDiagnosing.collectAsState()
    val isRepairing by vm.isRepairingRuntime.collectAsState()

    LaunchedEffect(Unit) { vm.runDiagnostics() }

    Column(Modifier.fillMaxSize()) {
        BackBar(title = stringResource(R.string.s404), onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(
                eyebrow = stringResource(R.string.diag_eyebrow),
                title = stringResource(R.string.diag_back_title),
                statusBarPadding = false
            )
            McCard(title = stringResource(R.string.diag_result_title), compact = true) {
                val summary = when {
                    isRepairing -> stringResource(R.string.diag_repairing)
                    isDiagnosing -> stringResource(R.string.diag_running)
                    report.generatedAtMs == 0L -> stringResource(R.string.diag_not_run)
                    report.issueCount == 0 -> stringResource(R.string.diag_pass)
                    else -> stringResource(R.string.diag_issues, report.issueCount)
                }
                Text(summary, color = if (report.issueCount == 0 && !isDiagnosing && !isRepairing) Mint else Coral, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.runDiagnostics() },
                        enabled = !isDiagnosing && !isRepairing,
                        modifier = Modifier.weight(1f), shape = McShape.ControlCompact
                    ) { Text(stringResource(R.string.diag_rerun), color = Indigo, fontSize = 12.sp) }
                    Button(
                        onClick = { vm.safeRepairRuntime() },
                        enabled = !isDiagnosing && !isRepairing,
                        modifier = Modifier.weight(1f).skinTileOverlay(SkinTileSlots.PRIMARY_BUTTON, McShape.ControlCompact), shape = McShape.ControlCompact,
                        colors = skinButtonColors(SkinTileSlots.PRIMARY_BUTTON, Indigo)) {
                        if (isRepairing) {
                            CircularProgressIndicator(Modifier.size(15.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(if (isRepairing) stringResource(R.string.diag_repairing_btn) else stringResource(R.string.diag_repair_btn), color = Color.White, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.diag_repair_hint), color = Muted, fontSize = 10.sp)
            }
            McCard(title = stringResource(R.string.diag_crash_title), compact = true) {
                var crashLog by remember { mutableStateOf(vm.appCrashLog()) }
                LaunchedEffect(Unit) { crashLog = vm.appCrashLog() }
                if (crashLog == null) {
                    Text(stringResource(R.string.diag_crash_empty), color = Muted, fontSize = 11.sp)
                } else {
                    var expanded by remember { mutableStateOf(false) }
                    val preview = if (expanded) crashLog!! else crashLog!!.lines().takeLast(6).joinToString("\n")
                    Text(preview, color = Color(0xFFE8E8E8), fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    // 清除崩溃日志的二次确认：这份日志是排查闪退的唯一现场，
                    // 清掉后无处找回，与其它删除类操作保持同一交互约定。
                    var confirmClearLog by remember { mutableStateOf(false) }
                    if (confirmClearLog) {
                        com.mineserve.mobile.ui.ConfirmDialog(
                            title = stringResource(R.string.diag_crash_clear),
                            message = stringResource(R.string.diag_crash_clear_confirm),
                            onDismiss = { confirmClearLog = false },
                            onConfirm = {
                                vm.clearAppCrashLog()
                                crashLog = null
                                confirmClearLog = false
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) stringResource(R.string.diag_crash_collapse) else stringResource(R.string.diag_crash_expand), color = Indigo, fontSize = 11.sp)
                        }
                        TextButton(onClick = { confirmClearLog = true }) {
                            Text(stringResource(R.string.diag_crash_clear), color = Coral, fontSize = 11.sp)
                        }
                    }
                }
            }
            report.checks.forEach { check -> DiagnosticCheckCard(check) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DiagnosticCheckCard(check: DiagnosticCheck) {
    val color = when (check.status) {
        DiagnosticStatus.Pass -> Mint
        DiagnosticStatus.Warning, DiagnosticStatus.Failed -> Coral
        DiagnosticStatus.Running -> Indigo
        DiagnosticStatus.NotApplicable -> Muted
    }
    McCard(title = check.title, compact = true) {
        Row(verticalAlignment = Alignment.Top) {
            Spacer(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(check.detail, color = Muted, fontSize = 11.sp)
                if (check.repairable) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.diag_repairable_hint), color = Indigo, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}