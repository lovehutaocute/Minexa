package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Indigo
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

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("服务器管理", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            active?.let { "${it.name} · ${it.core.displayName} ${it.version}" } ?: "请先选择服务器核心",
            color = if (active == null) Color(0xFFD94B4B) else Muted,
            fontSize = 12.sp
        )
        Text(if (state.isRunning) "服务器运行中" else "服务器未运行", color = if (state.isRunning) Color(0xFF2E9B62) else Muted, fontSize = 12.sp)
        ManagementEntry("模组与插件", "安装、更新和管理服务器插件或模组", Icons.Outlined.Extension, onPlugins)
        // 世界（存档）是服务端目录下的实体，属服务器管理；原先藏在「更多」里，路径太长
        ManagementEntry("世界管理", "切换、导入、导出与重置世界存档", Icons.Outlined.Map, onWorlds)
        ManagementEntry("服务器配置", "编辑当前核心对应的配置文件", Icons.Outlined.Tune, onProperties)
        ManagementEntry("服务器图标", "更换 Java Edition 的 server-icon.png", Icons.Outlined.Image, onIcon)
        if (active != null) {
            ManagementEntry("修改服务器信息", "编辑当前服务器的显示名称与版本号", Icons.Outlined.Edit) {
                showEditDialog = true
            }
        }
        if (active?.core?.isBedrock == true) {
            Text("PowerNukkitX 暂不支持 Java Edition 的服务器图标。", color = Color(0xFFD94B4B), fontSize = 11.sp)
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
        title = { Text("修改服务器信息", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text("显示版本号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = selectedCore.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("核心类型") },
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
                                    core.displayName + if (core == currentCore) "（当前）" else "",
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
                    "名称与版本号仅修改显示信息，不会影响实际版本与核心。",
                    color = Color(0xFFD94B4B),
                    fontSize = 11.sp
                )
                if (selectedCore != currentCore) {
                    Text(
                        "修改核心类型会改变应用对该服务器的处理方式（启动参数、插件/模组目录等），请确保与实际核心一致。",
                        color = Color(0xFFD94B4B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                error?.let {
                    Text(it, color = Color(0xFFD94B4B), fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = vm.updateServerDisplayInfo(currentName, name, version, selectedCore)
                if (problem == null) onDismiss() else error = problem
            }) { Text("保存", color = Indigo, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ManagementEntry(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = Indigo)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, color = Muted, fontSize = 12.sp)
            }
        }
    }
}
