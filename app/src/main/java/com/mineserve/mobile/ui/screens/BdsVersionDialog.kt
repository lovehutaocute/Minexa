package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mineserve.mobile.R
import com.mineserve.mobile.data.BdsChannel
import com.mineserve.mobile.data.BdsVersionEntry
import com.mineserve.mobile.data.BdsVersionListState
import com.mineserve.mobile.data.BdsVersions
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted

/**
 * 历史版本选择器：「把这台服务器换成另一个 BDS 版本」。
 *
 * 与下载页的版本抽屉不同，这里改的是**已经在跑的服务器**，因此每一行都要能看出
 * 三件事，否则用户不敢按：
 *  - 这一版是不是当前装着的（已安装标记）；
 *  - 这一版有没有权威摘要（SHA-256 硬校验 / 只有结构校验）；
 *  - 选择它是不是回退（回退要额外确认：世界存档已被新版本升级过，降级可能读不出来）。
 *
 * 列表用 [LazyColumn] 而不是整列渲染：内置清单 + 远端清单合计 150 项以上，
 * 一次性铺开会明显卡顿（这些行都带按钮）。
 */
@Composable
fun BdsVersionDialog(
    versions: List<BdsVersionEntry>,
    listState: BdsVersionListState,
    installedVersion: String?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onPick: (BdsVersionEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<BdsChannel?>(null) }
    var pendingDowngrade by remember { mutableStateOf<BdsVersionEntry?>(null) }

    val filtered = remember(versions, query, filter) {
        val keyword = query.trim()
        versions.filter { entry ->
            (filter == null || entry.channel == filter) &&
                (keyword.isEmpty() || entry.version.contains(keyword, ignoreCase = true))
        }
    }

    val pick: (BdsVersionEntry) -> Unit = { entry ->
        // 回退到更旧的版本必须二次确认：世界存档一旦被新版本升级，旧版核心打不开，
        // 玩家会看到「世界损坏」而不是「版本太旧」。
        if (installedVersion != null &&
            !BdsVersions.sameRelease(entry.version, installedVersion) &&
            BdsVersions.compare(entry.version, installedVersion) < 0
        ) {
            pendingDowngrade = entry
        } else {
            onPick(entry)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .heightIn(max = 620.dp),
            shape = McShape.Card,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(Modifier.padding(McSpacing.CardInner)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.bds_version_history),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.bds_version_count, versions.size) +
                                if (listState.fromCache) " · " + stringResource(R.string.bds_version_offline) else "",
                            color = if (listState.fromCache) Coral else Muted,
                            fontSize = 10.sp
                        )
                    }
                    if (listState.loading) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = onRefresh) {
                            Text(stringResource(R.string.bds_version_refresh), color = Indigo, fontSize = 11.sp)
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.bds_version_close), color = Muted, fontSize = 11.sp)
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    placeholder = { Text(stringResource(R.string.bds_version_search), fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BdsFilterPill(stringResource(R.string.bds_version_filter_all), filter == null) { filter = null }
                    BdsFilterPill(stringResource(R.string.bds_version_filter_stable), filter == BdsChannel.Stable) {
                        filter = BdsChannel.Stable
                    }
                    BdsFilterPill(stringResource(R.string.bds_core_preview), filter == BdsChannel.Preview) {
                        filter = BdsChannel.Preview
                    }
                }

                listState.error?.let { error ->
                    Text(error, color = Coral, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
                // 清单来源说明必须给：内置清单里的老版本没有官方摘要，
                // 而远端清单只覆盖近两年，用户需要知道「为什么这一版没有哈希」。
                Text(
                    stringResource(R.string.bds_version_note),
                    color = Muted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 6.dp),
                    lineHeight = 13.sp
                )

                if (filtered.isEmpty()) {
                    Text(
                        if (listState.loading) stringResource(R.string.bds_core_checking)
                        else stringResource(R.string.bds_version_empty),
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filtered, key = { it.channel.name + "|" + it.version }) { entry ->
                            val installed = installedVersion != null &&
                                BdsVersions.sameRelease(entry.version, installedVersion)
                            BdsVersionRow(
                                entry = entry,
                                installed = installed,
                                enabled = !busy,
                                onPick = { pick(entry) }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDowngrade?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDowngrade = null },
            title = {
                Text(stringResource(R.string.bds_version_downgrade_title), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    stringResource(
                        R.string.bds_version_downgrade_msg,
                        entry.version,
                        installedVersion ?: "?"
                    ),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDowngrade = null
                    onPick(entry)
                }) { Text(stringResource(R.string.bds_version_downgrade_ok), color = Coral, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDowngrade = null }) {
                    Text(stringResource(R.string.bds_version_close), color = Muted)
                }
            }
        )
    }
}

@Composable
private fun BdsVersionRow(
    entry: BdsVersionEntry,
    installed: Boolean,
    enabled: Boolean,
    onPick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(McShape.ControlCompact)
            .background(if (installed) IndigoSoft else MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled && !installed, onClick = onPick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.version,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entry.channel == BdsChannel.Preview) {
                    BdsBadge(stringResource(R.string.bds_core_preview), Coral)
                }
                // 摘要状态必须可见：它决定这次安装能不能发现「内容被换过」。
                // 远端清单的版本摘要要按需取，未取到时也算 SHA-256（见 digestExpected）。
                BdsBadge(
                    stringResource(
                        if (entry.strictDigest) R.string.bds_version_digest_sha
                        else R.string.bds_version_digest_structural
                    ),
                    if (entry.strictDigest) Mint else Muted
                )
            }
        }
        if (installed) {
            Text(stringResource(R.string.bds_version_installed), color = Indigo, fontSize = 11.sp)
        } else {
            Text(
                stringResource(R.string.bds_version_install),
                color = if (enabled) Indigo else Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun BdsBadge(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 9.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
private fun BdsFilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.White else Muted,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Indigo else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
