package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.data.BdsChannel
import com.mineserve.mobile.data.BdsUpdateProgress
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Line
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted

/**
 * 官方 BDS 的核心版本卡片：当前版本 / 最新稳定版 / Preview 版，以及在线更新入口。
 *
 * 为什么放在「服务器管理」里而不是「设置」：更新的是**这台服务器**的核心，
 * 会牵动它的世界与配置；放在全局设置里会让人以为是一次应用级操作。
 */
@Composable
fun BdsCoreCard(vm: McViewModel) {
    val status by vm.bdsCoreStatus.collectAsState()
    val progress by vm.bdsUpdateState.collectAsState()
    var pendingChannel by remember { mutableStateOf<BdsChannel?>(null) }
    val busy = progress is BdsUpdateProgress.Running

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = McShape.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, Line)
    ) {
        Column(
            Modifier.padding(McSpacing.CardInner),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.bds_core_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                when {
                    status.checking -> Text(stringResource(R.string.bds_core_checking), color = Muted, fontSize = 11.sp)
                    busy -> Unit
                    else -> TextButton(onClick = { vm.checkBdsCoreUpdate(force = true) }) {
                        Text(stringResource(R.string.bds_core_check), color = Indigo, fontSize = 11.sp)
                    }
                }
            }

            // 三个版本号横排：原先竖着三行会把卡片撑得又高又空，
            // 竖排容器里看起来"挤"主要就是它贡献的高度。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BdsVersionCell(R.string.bds_core_current, status.installedVersion, Modifier.weight(1f))
                BdsVersionCell(R.string.bds_core_stable, status.stable?.version, Modifier.weight(1f))
                BdsVersionCell(R.string.bds_core_preview, status.preview?.version, Modifier.weight(1f))
            }

            status.error?.let { Text(it, color = Coral, fontSize = 11.sp) }
            // 离线缓存必须讲清楚：否则「官方没发新版」和「没连上官方」看起来一样
            if (status.fromCache && status.error == null) {
                Text(stringResource(R.string.bds_core_offline), color = Coral, fontSize = 11.sp)
            }

            when (val state = progress) {
                is BdsUpdateProgress.Running -> {
                    Text(state.phase, color = Muted, fontSize = 11.sp)
                    val ratio = state.progress
                    if (ratio != null) {
                        LinearProgressIndicator(
                            progress = { ratio },
                            modifier = Modifier.fillMaxWidth(),
                            color = Indigo,
                            trackColor = IndigoSoft
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Indigo,
                            trackColor = IndigoSoft
                        )
                    }
                }

                is BdsUpdateProgress.Done -> {
                    Text(
                        stringResource(R.string.bds_update_done_title) + " · " + state.version,
                        color = Mint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.bds_update_backup_hint, state.backupName),
                        color = Muted,
                        fontSize = 11.sp
                    )
                    state.keptOldEntry?.let {
                        Text(stringResource(R.string.bds_update_old_entry_hint, it), color = Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { vm.dismissBdsUpdateState() }) {
                        Text(stringResource(R.string.bds_update_close), color = Indigo, fontSize = 11.sp)
                    }
                }

                is BdsUpdateProgress.Failed -> {
                    Text(
                        stringResource(R.string.bds_update_failed_title),
                        color = Coral,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(state.message, color = Coral, fontSize = 11.sp)
                    if (state.rolledBack) {
                        Text(stringResource(R.string.bds_update_rolled_back), color = Mint, fontSize = 11.sp)
                    }
                    TextButton(onClick = { vm.dismissBdsUpdateState() }) {
                        Text(stringResource(R.string.bds_update_close), color = Indigo, fontSize = 11.sp)
                    }
                }

                BdsUpdateProgress.Idle -> {
                    val stable = status.stable
                    val hasStableUpdate = status.hasUpdate(BdsChannel.Stable)
                    // 没拿到官方数据时不能说「已是最新」—— 那是把「没检测」说成了结论
                    val known = status.checked && stable != null
                    Text(
                        when {
                            !known -> stringResource(R.string.bds_core_not_checked)
                            hasStableUpdate -> stringResource(R.string.bds_core_has_update, stable!!.version)
                            else -> stringResource(R.string.bds_core_up_to_date)
                        },
                        color = if (known && hasStableUpdate) Coral else if (known) Mint else Muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    val preview = status.preview
                    val hasPreviewUpdate = status.hasUpdate(BdsChannel.Preview)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (stable != null && hasStableUpdate) {
                            Button(
                                onClick = { pendingChannel = BdsChannel.Stable },
                                colors = ButtonDefaults.buttonColors(containerColor = Indigo)
                            ) {
                                Text(stringResource(R.string.bds_core_update_to, stable.version))
                            }
                        }
                        if (preview != null && hasPreviewUpdate) {
                            TextButton(onClick = { pendingChannel = BdsChannel.Preview }) {
                                Text(
                                    stringResource(R.string.bds_core_update_to, preview.version),
                                    color = Indigo
                                )
                            }
                        }
                    }
                    Text(stringResource(R.string.bds_core_note), color = Muted, fontSize = 11.sp)
                }
            }
        }
    }

    // 更新前的确认框：把「会保留什么、会备份什么」逐条写清楚再让用户按下去
    val channel = pendingChannel
    val target = channel?.let { status.build(it) }
    if (channel != null && target != null) {
        val channelLabel = stringResource(
            if (channel == BdsChannel.Stable) R.string.bds_core_stable else R.string.bds_core_preview
        )
        val installed = status.installedVersion ?: stringResource(R.string.bds_core_unknown)
        AlertDialog(
            onDismissRequest = { pendingChannel = null },
            title = {
                Text(
                    stringResource(R.string.bds_core_confirm_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    stringResource(R.string.bds_core_confirm_msg, target.version, channelLabel, installed),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingChannel = null
                    vm.updateBdsCore(channel)
                }) { Text(stringResource(R.string.bds_core_confirm_ok), color = Indigo, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingChannel = null }) {
                    Text(stringResource(R.string.bds_core_cancel), color = Muted)
                }
            }
        )
    }
}

/** 版本卡片里的一格「标签 / 版本号」；版本未知时显示「未知」而不是留空 */
@Composable
private fun BdsVersionCell(labelRes: Int, version: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(McShape.ControlCompact)
            .background(IndigoSoft)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            stringResource(labelRes),
            color = Muted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            version ?: stringResource(R.string.bds_core_unknown),
            color = if (version == null) Muted else MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
