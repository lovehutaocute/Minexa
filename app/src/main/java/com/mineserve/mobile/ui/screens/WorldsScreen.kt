package com.mineserve.mobile.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.server.Worlds
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest

/**
 * 世界（存档）管理页：列出服务端目录下的所有世界，支持切换、导出、导入、重置。
 *
 * 与「备份」页的分工：备份页负责整机数据的定期快照；本页负责单个世界的日常操作。
 */
@Composable
fun WorldsScreen(vm: McViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val worlds by vm.worlds.collectAsState()
    val loading by vm.worldsLoading.collectAsState()
    val activeName by vm.activeWorldName.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()

    // 待重置（二次确认）与待导出（选定 SAF 目标后执行）的世界
    var pendingReset by remember { mutableStateOf<Worlds.WorldInfo?>(null) }
    var pendingExport by remember { mutableStateOf<String?>(null) }

    // 键必须是 isBootstrapped：loadWorlds() 在未部署时直接 return，用 Unit 作键的话
    // 之后 isBootstrapped 变 true 也不会重跑，世界列表会一直空着。
    LaunchedEffect(isBootstrapped) { if (isBootstrapped) vm.loadWorlds() }
    LaunchedEffect(Unit) { vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) } }

    // 导出：先让用户挑一个保存位置，再打包写过去
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        val name = pendingExport
        if (uri != null && name != null) vm.exportWorldToUri(name, uri)
        pendingExport = null
    }

    // 导入：选一个 zip，用文件名（去掉扩展名）作为期望的世界名，重名会自动加后缀
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fallback = "导入世界_" + System.currentTimeMillis().toString().takeLast(5)
            val preferred = queryDisplayName(context, uri)?.let { stripZipExt(it) } ?: fallback
            vm.importWorldFromUri(uri, preferred)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            BackBar("世界管理", onBack)
            if (!isBootstrapped) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("请先在「下载」页完成服务端部署", color = Muted, fontSize = 13.sp)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 18.dp)
                ) {
                    McCard(title = "当前世界") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = Indigo, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                activeName.ifBlank { "world" },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "服务端启动时读取的世界。切换后需要重启服务端才会生效。",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }

                    McCard(
                        title = "世界列表",
                        trailing = {
                            if (loading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        }
                    ) {
                        if (worlds.isEmpty()) {
                            Text(
                                if (loading) "正在统计世界占用…" else "还没有生成任何世界。启动一次服务端后会在这里出现。",
                                color = Muted,
                                fontSize = 12.sp
                            )
                        } else {
                            worlds.forEachIndexed { index, world ->
                                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                                WorldRow(
                                    world = world,
                                    onActivate = { vm.setActiveWorld(world.name) },
                                    onExport = {
                                        pendingExport = world.name
                                        exportLauncher.launch("世界_${world.name}.zip")
                                    },
                                    onReset = { pendingReset = world }
                                )
                            }
                        }
                    }

                    McCard(title = "导入 / 维护") {
                        Text(
                            "导入支持本应用导出的存档，也支持原版 world 文件夹直接打包的 zip。",
                            color = Muted,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    importLauncher.launch(
                                        arrayOf("application/zip", "application/octet-stream", "*/*")
                                    )
                                },
                                shape = McShape.ControlCompact
                            ) {
                                Icon(Icons.Outlined.FileUpload, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("导入世界", fontSize = 13.sp)
                            }
                            OutlinedButton(onClick = { vm.clearWorldSeed() }, shape = McShape.ControlCompact) {
                                Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("清空种子", fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "清空种子后，服务端下次生成新地图时使用随机种子（已存在的世界不受影响）。",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }

                    McCard(title = "导出说明") {
                        Text(
                            "导出时会把世界连同它的下界、末地维度一起打包。除了另存到自选位置，导出的备份也会留在手机公共目录「备份/世界导出」下，卸载应用不会丢失。",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }

    // 重置确认：删除存档不可逆，这里明确告知会自动备份
    pendingReset?.let { world ->
        AlertDialog(
            onDismissRequest = { pendingReset = null },
            title = { Text("重置世界「${world.name}」？") },
            text = {
                Text(
                    "会先把这个世界完整导出为一份备份，然后删除目录，服务端下次启动会重新生成一张新地图。\n\n此操作不可撤销，请确认。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetWorld(world.name)
                    pendingReset = null
                }) { Text("备份并重置", color = Coral, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReset = null }) { Text("取消") }
            }
        )
    }
}

/** 单个世界：名称 + 类型/大小 + 当前生效标记 + 三个操作。 */
@Composable
private fun WorldRow(
    world: Worlds.WorldInfo,
    onActivate: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit
) {
    val kindLabel = when (world.kind) {
        Worlds.Kind.Java -> "Java"
        Worlds.Kind.Bedrock -> "基岩"
        Worlds.Kind.Unknown -> "未知"
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Map,
                null,
                tint = if (world.isActive) Indigo else Muted,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    world.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    buildString {
                        append(kindLabel)
                        append(" · ")
                        append(world.sizeText)
                        if (world.sizeTruncated) append("+")
                        if (world.isActive) append(" · 当前使用中")
                    },
                    fontSize = 11.sp,
                    color = Muted
                )
            }
            if (world.isActive) {
                Box(
                    modifier = Modifier
                        .clip(McShape.Row)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("使用中", fontSize = 10.sp, color = Indigo, fontWeight = FontWeight.SemiBold)
                }
            } else {
                TextButton(onClick = onActivate) {
                    Text("设为当前", fontSize = 12.sp, color = Indigo, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(icon = Icons.Outlined.FileDownload, label = "导出", onClick = onExport)
            SmallAction(icon = Icons.Outlined.DeleteOutline, label = "重置", tint = Coral, onClick = onReset)
        }
    }
}

/** 行内小按钮：图标 + 文字，弱化视觉以突出主操作。 */
@Composable
private fun SmallAction(icon: ImageVector, label: String, tint: Color = Indigo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(McShape.Row)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(McIcon.Inline))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = tint)
    }
}

/** 读取 SAF 文档的显示名（用于给导入的世界起一个有意义的名字）。 */
private fun queryDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
} catch (e: Exception) {
    null
}

private fun stripZipExt(name: String): String =
    name.removeSuffix(".zip").removeSuffix(".ZIP").ifBlank { "导入世界" }
