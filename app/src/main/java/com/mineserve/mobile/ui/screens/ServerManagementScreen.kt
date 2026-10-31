package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Line
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted

@Composable
fun ServerManagementScreen(
    vm: McViewModel,
    onPlugins: () -> Unit,
    onProperties: () -> Unit,
    onIcon: () -> Unit,
    onWorlds: () -> Unit
) {
    val config by vm.config.collectAsState()
    val state by vm.serverState.collectAsState()
    val active = config.installedCores.firstOrNull { it.name == config.activeCoreName }
    var showEditDialog by remember { mutableStateOf(false) }

    // 进入页面时扫描 servers 目录，自动登记 MT 管理器等外部方式直接复制进来的服务器文件夹
    LaunchedEffect(Unit) { vm.scanUnregisteredServers() }

    // 官方 BDS 的版本要问 Mojang：进页面检测一次（10 分钟内有缓存，不会每次都发请求）。
    // 切换服务器时也重新检测，否则换到另一台 BDS 会沿用上一台的版本结论。
    LaunchedEffect(active?.dirName, active?.core) {
        if (active?.core == com.mineserve.mobile.data.ServerCore.Bds) vm.checkBdsCoreUpdate()
    }

    Column(Modifier.fillMaxSize()) {
        // 与其它一级页面统一：同一个标题栏（eyebrow + 标题）承接状态栏避让，
        // 标题字号/位置与概览、设置等页面完全一致；正文改用统一页面边距。
        HeaderBlock(eyebrow = stringResource(R.string.sm_eyebrow), title = stringResource(R.string.sm_title))
        Column(
            Modifier
                .fillMaxSize()
                // 选中 BDS 核心时本页会多出一张版本卡片 + 基岩版提示，
                // 内容高度会超过一屏；没有这个滚动修饰符就完全滑不动，下面的入口点不到。
                .verticalScroll(rememberScrollState())
                .padding(horizontal = McSpacing.Page)
                .padding(top = McSpacing.CardGap),
            verticalArrangement = Arrangement.spacedBy(McSpacing.CardGap)
        ) {
            // 状态摘要：核心与运行状态用与全局一致的语义色（Coral / Mint），
            // 不再各自写一份硬编码的红绿。
            Text(
                active?.let { "${it.name} · ${it.core.displayName} ${it.version}" } ?: stringResource(R.string.sm_select_core_first),
                color = if (active == null) Coral else Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(McShape.Circle)
                        .background(if (state.isRunning) Mint else Muted)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    if (state.isRunning) stringResource(R.string.sm_running) else stringResource(R.string.sm_not_running),
                    color = if (state.isRunning) Mint else Muted,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(2.dp))
            // 官方 BDS 的核心版本与在线更新（Java 版核心没有这条通道，故只对 BDS 显示）
            if (active?.core == com.mineserve.mobile.data.ServerCore.Bds) {
                BdsCoreCard(vm)
            }
            ManagementEntry(stringResource(R.string.sm_entry_plugins), stringResource(R.string.sm_entry_plugins_desc), Icons.Outlined.Extension, onPlugins)
            // 世界（存档）是服务端目录下的实体，属服务器管理；原先藏在「更多」里，路径太长
            ManagementEntry(stringResource(R.string.sm_entry_worlds), stringResource(R.string.sm_entry_worlds_desc), Icons.Outlined.Map, onWorlds)
            ManagementEntry(stringResource(R.string.sm_entry_props), stringResource(R.string.sm_entry_props_desc), Icons.Outlined.Tune, onProperties)
            ManagementEntry(stringResource(R.string.sm_entry_icon), stringResource(R.string.sm_entry_icon_desc), Icons.Outlined.Image, onIcon)
            if (active != null) {
                ManagementEntry(stringResource(R.string.sm_entry_info), stringResource(R.string.sm_entry_info_desc), Icons.Outlined.Edit) {
                    showEditDialog = true
                }
            }
            if (active?.core?.isBedrock == true) {
                Text(
                    stringResource(R.string.sm_bedrock_icon_note),
                    color = Coral,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            // 底部留白：最后一张卡片贴着导航栏时滑动到底的手感很差
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showEditDialog && active != null) {
        EditServerInfoDialog(
            vm = vm,
            currentName = active.name,
            currentVersion = active.version,
            currentCore = active.core,
            onDismiss = { showEditDialog = false }
        )
    }
}

@Composable
private fun EditServerInfoDialog(
    vm: McViewModel,
    currentName: String,
    currentVersion: String,
    currentCore: com.mineserve.mobile.data.ServerCore,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    var version by remember { mutableStateOf(currentVersion) }
    var selectedCore by remember { mutableStateOf(currentCore) }
    var coreMenuExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sm_dlg_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.sm_dlg_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text(stringResource(R.string.sm_dlg_version)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = selectedCore.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.sm_dlg_core)) },
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = { coreMenuExpanded = !coreMenuExpanded }) {
                            Icon(
                                if (coreMenuExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = coreMenuExpanded,
                    onDismissRequest = { coreMenuExpanded = false }
                ) {
                    com.mineserve.mobile.data.ServerCore.values().forEach { core ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Text(
                                    core.displayName + if (core == currentCore) stringResource(R.string.sm_core_current) else "",
                                    fontWeight = if (core == selectedCore) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                selectedCore = core
                                coreMenuExpanded = false
                            }
                        )
                    }
                }
                Text(
                    stringResource(R.string.sm_dlg_name_note),
                    color = Coral,
                    fontSize = 11.sp
                )
                if (selectedCore != currentCore) {
                    Text(
                        stringResource(R.string.sm_dlg_core_note),
                        color = Coral,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                error?.let {
                    Text(it, color = Coral, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = vm.updateServerDisplayInfo(currentName, name, version, selectedCore)
                if (problem == null) onDismiss() else error = problem
            }) { Text(stringResource(R.string.s328), color = Indigo, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.s402)) }
        }
    )
}

@Composable
private fun ManagementEntry(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    // 与 McCard 同款外观：12dp 圆角 + 1dp 描边 + 极浅投影 + 统一内边距，
    // 这样本页的入口卡与其它页面的卡片是同一套视觉语言。
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = McShape.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(McSpacing.CardInner),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Indigo, modifier = Modifier.size(McIcon.Item))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}
