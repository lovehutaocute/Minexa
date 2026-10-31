package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.data.BdsEnvError
import com.mineserve.mobile.data.BdsEnvStage
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted

/**
 * 官方 BDS 的运行环境（PRoot 容器 + box64）。
 *
 * 这一栏**不属于 Java 环境**：它没有 JDK、不参与「运行环境」选择，
 * 也不会出现在 Termux JDK / Pojav JRE 的版本列表里。之所以单独成栏，
 * 是因为官方 BDS 是 x86_64 + glibc 构建，arm64 设备必须靠容器 + 转译才能跑，
 * 与「选哪个 Java」是两件互不相干的事。
 *
 * 初始化由用户在这里点一次，之后长期有效；启动服务端时不再临时安装。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BdsEnvironmentModule(
    vm: McViewModel,
    requestConfirm: (String, () -> Unit) -> Unit
) {
    val status by vm.bdsEnvStatus.collectAsState()
    val probeRunning by vm.bdsProbeRunning.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val running = serverState.isRunning
    // 进页读一次磁盘：用户可能在别处删过文件、或刚装完容器
    LaunchedEffect(Unit) { vm.refreshBdsEnvironment() }

    McCard(title = stringResource(R.string.bds_env_title)) {
        // 「仅用于官方基岩版」——把这栏和 Java 运行环境明确切开
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(McShape.ControlCompact)
                    .background(Coral.copy(alpha = 0.12f))
                    .padding(horizontal = McSpacing.Row, vertical = 3.dp)
            ) {
                Text(
                    stringResource(R.string.bds_env_badge),
                    color = Coral,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(McSpacing.Row))

        // ── 状态行 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            status.installing -> Indigo
                            status.installed -> Mint
                            status.error != null -> Coral
                            else -> Muted
                        }
                    )
            )
            Spacer(Modifier.width(McSpacing.Row))
            Text(
                stringResource(
                    when {
                        status.installing -> R.string.bds_env_status_installing
                        status.installed -> R.string.bds_env_status_ready
                        status.error != null -> R.string.bds_env_status_failed
                        else -> R.string.bds_env_status_missing
                    }
                ),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    status.installing -> Indigo
                    status.installed -> Mint
                    status.error != null -> Coral
                    else -> Muted
                }
            )
            Spacer(Modifier.weight(1f))
            if (status.installing) {
                Text("${status.percent}%", color = Indigo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(McSpacing.Row))
        Text(stringResource(R.string.bds_env_hint), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)

        // ── 组件清单：容器 / box64 ──
        Spacer(Modifier.height(McSpacing.CardHeader))
        ComponentRow(
            label = stringResource(R.string.bds_env_component_container),
            value = status.distroLabel ?: "—"
        )
        ComponentRow(
            label = stringResource(R.string.bds_env_component_box64),
            value = status.box64Version ?: "—"
        )

        // ── 进度 ──
        if (status.installing) {
            Spacer(Modifier.height(McSpacing.CardHeader))
            LinearProgressIndicator(
                progress = { status.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = Indigo,
                trackColor = Indigo.copy(alpha = 0.15f)
            )
            Spacer(Modifier.height(McSpacing.CardGapCompact))
            Text(stageLabel(status.stage), color = Indigo, fontSize = 11.sp)
            status.source?.let { source ->
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.bds_env_source, source),
                    color = Muted,
                    fontSize = 10.sp
                )
            }
        }

        // ── 失败原因 ──
        status.error?.let { error ->
            Spacer(Modifier.height(McSpacing.CardHeader))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(McShape.ControlCompact)
                    .background(Coral.copy(alpha = 0.08f))
                    .padding(McSpacing.CardHeader)
            ) {
                Column {
                    Text(
                        errorLabel(error),
                        color = Coral,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    status.detail?.let { detail ->
                        Spacer(Modifier.height(3.dp))
                        Text(detail, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    status.rawDetail?.let { raw ->
                        Spacer(Modifier.height(3.dp))
                        Text(raw.take(400), color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    if (status.attempts.isNotEmpty()) {
                        Spacer(Modifier.height(McSpacing.CardGapCompact))
                        Text(
                            stringResource(R.string.bds_env_attempts),
                            color = Muted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        status.attempts.forEach { line ->
                            Text("· $line", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }

        // ── 就绪 / 未初始化提示 ──
        Spacer(Modifier.height(McSpacing.Row))
        Text(
            stringResource(if (status.installed) R.string.bds_env_ready_hint else R.string.bds_env_missing_hint),
            color = if (status.installed) Mint else Muted,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )

        if (running) {
            Spacer(Modifier.height(McSpacing.CardGapCompact))
            Text(stringResource(R.string.env_java_running_block), color = Coral, fontSize = 11.sp)
        }

        // ── 操作 ──
        Spacer(Modifier.height(McSpacing.CardHeader))
        when {
            status.installing -> {
                OutlinedButton(
                    onClick = { vm.cancelBdsEnvironment() },
                    shape = McShape.ControlCompact,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.bds_env_cancel),
                        color = Coral,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            else -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(McSpacing.Row),
                    verticalArrangement = Arrangement.spacedBy(McSpacing.Row)
                ) {
                    Button(
                        onClick = { vm.initializeBdsEnvironment() },
                        enabled = !running,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = McShape.ControlCompact
                    ) {
                        Text(
                            stringResource(
                                when {
                                    status.installed || status.error != null -> R.string.bds_env_reinit
                                    else -> R.string.bds_env_init
                                }
                            ),
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (status.installed) {
                        // 在线模式（正版验证）出网自检：BDS 只会给一句 "Could not connect"，
                        // 分不清是 DNS / TCP / 证书哪一段断的，这里替他拆开测。
                        OutlinedButton(
                            onClick = { vm.probeBdsNetwork() },
                            enabled = !running && !probeRunning,
                            shape = McShape.ControlCompact
                        ) {
                            Text(
                                stringResource(R.string.bds_env_probe),
                                color = Indigo,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        val removeConfirm = stringResource(R.string.bds_env_remove_confirm)
                        OutlinedButton(
                            onClick = { requestConfirm(removeConfirm) { vm.removeBdsBox64() } },
                            enabled = !running,
                            shape = McShape.ControlCompact
                        ) {
                            Text(
                                stringResource(R.string.bds_env_remove),
                                color = Coral,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 组件一行：左侧名称，右侧当前值（版本号或「—」） */
@Composable
private fun ComponentRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = Indigo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun stageLabel(stage: BdsEnvStage?): String = stringResource(
    when (stage) {
        BdsEnvStage.Container -> R.string.bds_env_stage_container
        BdsEnvStage.Box64 -> R.string.bds_env_stage_box64
        BdsEnvStage.Verify -> R.string.bds_env_stage_verify
        null -> R.string.bds_env_status_installing
    }
)

@Composable
private fun errorLabel(error: BdsEnvError): String = stringResource(
    when (error) {
        BdsEnvError.Space -> R.string.bds_env_error_space
        BdsEnvError.Container -> R.string.bds_env_error_container
        BdsEnvError.Download -> R.string.bds_env_error_download
        BdsEnvError.Corrupt -> R.string.bds_env_error_corrupt
        BdsEnvError.Unpack -> R.string.bds_env_error_unpack
        BdsEnvError.Verify -> R.string.bds_env_error_verify
        BdsEnvError.Unknown -> R.string.bds_env_error_unknown
    }
)
