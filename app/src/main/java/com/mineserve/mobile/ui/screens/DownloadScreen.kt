package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.mineserve.mobile.R

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.InstalledCore
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.SegPill
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private data class DownloadImport(val kind: String, val uri: Uri)

/**
 * 下载页：选择服务端核心 + 选择游戏版本 + 下载服务端 JAR
 * 核心类型和版本均从官方 API 动态获取，确保下载源正确
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadScreen(vm: McViewModel, onShowDownloadHelp: () -> Unit = {}) {
    val context = LocalContext.current
    val config by vm.config.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val availableVersions by vm.availableVersions.collectAsState()
    val versionHints by vm.versionHints.collectAsState()
    val isLoadingVersions by vm.isLoadingVersions.collectAsState()
    val isDownloadingCore by vm.isDownloadingCore.collectAsState()
    val downloadProgress by vm.downloadProgress.collectAsState()
    val consoleLines by vm.consolePreviewLines.collectAsState()
    val importingServer by vm.isImportingServer.collectAsState()
    val importProgress by vm.importProgress.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val usesCoreVersion = config.selectedCore.isBedrock

    // 自定义版本输入框
    var customVersion by remember { mutableStateOf("") }
    // 自定义核心名称
    var customCoreName by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<DownloadImport?>(null) }
    var importName by remember { mutableStateOf("") }
    var importObservedRunning by remember { mutableStateOf(false) }
    var showAllVersions by remember { mutableStateOf(false) }
    // 待删除的已安装核心（二次确认；删除会连同该目录下的世界、插件与配置一起移除）
    var pendingDeleteCore by remember { mutableStateOf<InstalledCore?>(null) }
    val folderImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { pendingImport = DownloadImport("folder", it); showImportDialog = true }
    }
    val archiveImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pendingImport = DownloadImport("archive", it); showImportDialog = true }
    }
    val jarImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pendingImport = DownloadImport("jar", it); showImportDialog = true }
    }
    val modpackImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pendingImport = DownloadImport("modpack", it); showImportDialog = true }
    }
    LaunchedEffect(pendingImport) {
        pendingImport?.let { importName = vm.proposeImportName(it.kind, it.uri).orEmpty() }
    }
    LaunchedEffect(importingServer) {
        if (importingServer) {
            importObservedRunning = true
        } else if (importObservedRunning) {
            showImportDialog = false
            pendingImport = null
            importObservedRunning = false
        }
    }
    // 切换核心时自动加载版本列表
    LaunchedEffect(config.selectedCore) {
        showAllVersions = false
        vm.loadVersions(config.selectedCore)
    }

    // 收集消息
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = stringResource(R.string.eyebrow_download), title = stringResource(R.string.s450))

            // 当前下载状态（已安装核心列表，默认折叠）。折叠状态用 rememberSaveable 保存，
            // 避免在组合体内同步读 SharedPreferences 造成首帧主线程读盘。
            var installedExpanded by rememberSaveable { mutableStateOf(false) }
            McCard(
                title = stringResource(R.string.s451),
                compact = true,
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { installedExpanded = !installedExpanded }
                            .padding(horizontal = 8.dp, vertical = 14.dp)
                    ) {
                        Text(
                            stringResource(
                                if (installedExpanded) R.string.dl_installed_collapse
                                else R.string.dl_installed_expand
                            ),
                            color = Muted,
                            fontSize = 9.sp
                        )
                        Icon(
                            if (installedExpanded) Icons.Outlined.KeyboardArrowUp
                            else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Muted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            ) {
                val installed = config.installedCores
                androidx.compose.animation.AnimatedVisibility(visible = installedExpanded) {
                    Column {
                        if (installed.isEmpty()) {
                            Text(
                                stringResource(R.string.s452),
                                color = Muted,
                                fontSize = 13.sp
                            )
                        } else {
                            installed.forEach { core ->
                        val versionText = "${core.core.displayName} ${core.version}" +
                            (if (core.core == ServerCore.PowerNukkitX) {
                                stringResource(R.string.dl_supported_game, versionHints[core.version] ?: stringResource(R.string.dl_official_unknown))
                            } else "") + stringResource(R.string.dl_folder, core.dirName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        core.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (core.name == config.activeCoreName) {
                                        Spacer(Modifier.size(6.dp))
                                        Text(
                                            stringResource(R.string.s453),
                                            color = Mint,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    versionText,
                                    color = Muted,
                                    fontSize = 11.sp
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    vm.setActiveCore(core.name)
                                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.s376, core.name)) }
                                },
                                enabled = core.name != config.activeCoreName,
                                shape = McShape.Control,
                                border = BorderStroke(1.dp, Indigo),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text(stringResource(R.string.s455), color = Indigo, fontSize = 11.sp) }
                            Spacer(Modifier.size(6.dp))
                            OutlinedButton(
                                onClick = { vm.verifyOrRepairCore(core.dirName) },
                                shape = McShape.Control,
                                border = BorderStroke(1.dp, Mint),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text(stringResource(R.string.dl_core_verify), color = Mint, fontSize = 11.sp) }
                            Spacer(Modifier.size(6.dp))
                            OutlinedButton(
                                // 运行中禁止删除：先置 pendingDeleteCore，弹二次确认后再真正删除
                                onClick = { pendingDeleteCore = core },
                                enabled = !serverState.isRunning,
                                shape = McShape.Control,
                                border = BorderStroke(1.dp, Coral),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text(stringResource(R.string.s339), color = Coral, fontSize = 11.sp) }
                        }
                    }
                    }
                }
                if (serverState.isRunning) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.dl_core_delete_running), color = Coral, fontSize = 10.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.s456),
                    color = Muted,
                    fontSize = 11.sp
                )
            }
}

            // 选择核心类型
            McCard(
                title = stringResource(R.string.s457),
                compact = true,
                trailing = {
                    Text(
                        stringResource(R.string.dlx_supports_import),
                        color = Muted,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .clickable { showImportDialog = true }
                            .padding(horizontal = 8.dp, vertical = 14.dp)
                    )
                }
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ServerCore.values().forEach { core ->
                        SegPill(
                            text = core.displayName,
                            selected = config.selectedCore == core,
                            compact = true,
                            unselectedBackground = if (core.isBedrock) Coral.copy(alpha = 0.16f) else com.mineserve.mobile.ui.theme.FieldGray,
                            onClick = { vm.selectCore(core) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when (config.selectedCore) {
                        ServerCore.Paper -> stringResource(R.string.dl_desc_paper)
                        ServerCore.Purpur -> stringResource(R.string.dl_desc_purpur)
                        ServerCore.Leaves -> stringResource(R.string.dl_desc_leaves)
                        ServerCore.Leaf -> stringResource(R.string.dl_desc_leaf)
                        ServerCore.Spigot -> stringResource(R.string.dl_desc_spigot)
                        ServerCore.CraftBukkit -> stringResource(R.string.dl_desc_craftbukkit)
                        ServerCore.Fabric -> stringResource(R.string.dl_desc_fabric)
                        ServerCore.Forge -> stringResource(R.string.dl_desc_forge)
                        ServerCore.NeoForge -> stringResource(R.string.dl_desc_neoforge)
                        ServerCore.Quilt -> stringResource(R.string.dl_desc_quilt)
                        ServerCore.Vanilla -> stringResource(R.string.dl_desc_vanilla)
                        ServerCore.Velocity -> stringResource(R.string.dl_desc_velocity)
                        ServerCore.BungeeCord -> stringResource(R.string.dl_desc_bungeecord)
                        ServerCore.PowerNukkitX -> stringResource(R.string.dl_desc_pnx)
                        ServerCore.PocketMine -> stringResource(R.string.dl_desc_pocketmine)
                        ServerCore.Allay -> stringResource(R.string.dl_desc_allay)
                        ServerCore.Unknown -> stringResource(R.string.dl_desc_unknown)
                    },
                    color = Muted,
                    fontSize = 11.sp
                )
                // 当前核心插件/模组支持彩色提示
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        if (config.selectedCore.supportsPlugins) stringResource(R.string.dl_supports_plugins) else stringResource(R.string.dl_no_plugins),
                        color = if (config.selectedCore.supportsPlugins) Mint else Coral,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (config.selectedCore.supportsMods) stringResource(R.string.dl_supports_mods) else stringResource(R.string.dl_no_mods),
                        color = if (config.selectedCore.supportsMods) Mint else Coral,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = McShape.ControlCompact
                ) { Text(stringResource(R.string.dlx_import_server), color = Indigo, fontSize = 12.sp) }
            }

            McCard(title = if (usesCoreVersion) stringResource(R.string.dl_select_version) else stringResource(R.string.s471), compact = true) {
                if (isLoadingVersions) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Indigo,
                            strokeWidth = 2.dp
                        )
                        Text(stringResource(R.string.s472), color = Muted, fontSize = 12.sp)
                    }
                } else if (availableVersions.isEmpty()) {
                    Text(stringResource(R.string.s473), color = Muted, fontSize = 12.sp)
                } else {
                    val shownVersions = if (showAllVersions) availableVersions else availableVersions.take(8)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shownVersions.forEach { ver ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                SegPill(
                                    text = ver,
                                    selected = config.mcVersion == ver,
                                    onClick = { vm.setMcVersion(ver) }
                                )
                                if (usesCoreVersion) {
                                    Text(
                                        stringResource(R.string.dl_supported_game_colon, versionHints[ver] ?: stringResource(R.string.dl_unknown_official)),
                                        color = if (versionHints[ver] == null) Muted else Coral,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                    if (availableVersions.size > shownVersions.size) {
                        TextButton(onClick = { showAllVersions = true }) {
                            Text(stringResource(R.string.dlx_show_all, availableVersions.size), color = Indigo, fontSize = 12.sp)
                        }
                    } else if (showAllVersions && availableVersions.size > 8) {
                        TextButton(onClick = { showAllVersions = false }) {
                            Text(stringResource(R.string.dlx_show_common), color = Indigo, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(if (usesCoreVersion) stringResource(R.string.dl_manual_version) else stringResource(R.string.s474), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customVersion,
                        onValueChange = { customVersion = it },
                        placeholder = { Text(if (usesCoreVersion) stringResource(R.string.dl_version_example) else stringResource(R.string.s475), fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (customVersion.isNotBlank()) {
                                vm.setMcVersion(customVersion.trim())
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.s476, customVersion.trim()))
                                }
                            }
                        },
                        enabled = customVersion.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = McShape.Control
                    ) {
                        Text(stringResource(R.string.s477), color = Color.White, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (usesCoreVersion) stringResource(R.string.dl_current_version, config.mcVersion) else stringResource(R.string.s478, config.mcVersion),
                    color = Indigo,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (usesCoreVersion) {
                    Text(
                        stringResource(R.string.dl_supported_game_colon, versionHints[config.mcVersion] ?: stringResource(R.string.dl_unknown_official)),
                        color = if (versionHints[config.mcVersion] == null) Muted else Coral,
                        fontSize = 10.sp
                    )
                }
                InstallerJavaNotice(
                    core = config.selectedCore,
                    minecraftVersion = config.mcVersion
                )
                if (usesCoreVersion) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.dl_pnx_protocol_hint),
                        color = Coral, fontSize = 11.sp
                    )
                }
            }

            // 下载服务端
            McCard(title = stringResource(R.string.s479), compact = true) {
                // 下载提示行（卡片内顶部，含实时速度）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isDownloadingCore) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Indigo,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "${downloadProgress.speedText} · ${downloadProgress.percent}%  ",
                            color = Indigo,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(McShape.Small)
                                .background(Indigo.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("↓", color = Indigo, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.s480),
                            color = Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        stringResource(R.string.s403),
                        color = Indigo,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onShowDownloadHelp() }
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (!isBootstrapped) {
                    Text(
                        stringResource(R.string.dl_env_init),
                        color = Coral,
                        fontSize = 12.sp
                    )
                } else {
                    Text(
                        if (usesCoreVersion) {
                            stringResource(R.string.dl_preparing, config.selectedCore.displayName, config.mcVersion)
                        } else stringResource(R.string.s482, config.selectedCore.displayName, config.mcVersion),
                        color = Muted,
                    fontSize = 11.sp
                )
                if (config.selectedCore == ServerCore.PowerNukkitX) {
                    Text(
                        stringResource(R.string.dl_pnx_udp_hint),
                        color = Coral,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                    Spacer(Modifier.height(10.dp))
                    // 自定义名称输入
                    Text(stringResource(R.string.s483), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customCoreName,
                        onValueChange = { customCoreName = it },
                        placeholder = { Text(stringResource(R.string.s484), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.downloadCore(customCoreName) },
                        enabled = !isDownloadingCore && customCoreName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = McShape.Control,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isDownloadingCore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(
                            if (isDownloadingCore) stringResource(R.string.s485) else stringResource(R.string.s486),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 下载日志预览
                    if (isDownloadingCore || consoleLines.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.s487), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        consoleLines.takeLast(8).forEach { line ->
                            Text(
                                line,
                                color = Muted.copy(alpha = 0.7f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
            // 删除核心二次确认：VM 内是整目录 deleteRecursively，会连同世界存档、插件与配置一起永久删除
            pendingDeleteCore?.let { core ->
                AlertDialog(
                    onDismissRequest = { pendingDeleteCore = null },
                    title = { Text(stringResource(R.string.dl_core_delete_title), fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            stringResource(R.string.dl_core_delete_content, core.name),
                            color = Muted, fontSize = 12.sp
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingDeleteCore = null
                            vm.deleteCore(core.name)
                        }) { Text(stringResource(R.string.s339), color = Coral, fontWeight = FontWeight.SemiBold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDeleteCore = null }) {
                            Text(stringResource(R.string.s402), color = Muted)
                        }
                    }
                )
            }
            if (showImportDialog) {
                AlertDialog(
                    onDismissRequest = { if (!importingServer) { showImportDialog = false; pendingImport = null } },
                    title = { Text(stringResource(R.string.dlx_import_server)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.dlx_import_desc), color = Muted, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { folderImportLauncher.launch(null) }, enabled = !importingServer, shape = McShape.ControlCompact) { Text(stringResource(R.string.dlx_folder), fontSize = 11.sp) }
                                OutlinedButton(onClick = { archiveImportLauncher.launch(arrayOf("application/zip", "application/gzip", "application/x-tar", "application/octet-stream")) }, enabled = !importingServer, shape = McShape.ControlCompact) { Text(stringResource(R.string.dlx_archive), fontSize = 11.sp) }
                                OutlinedButton(onClick = { jarImportLauncher.launch(arrayOf("application/java-archive", "application/octet-stream")) }, enabled = !importingServer, shape = McShape.ControlCompact) { Text("JAR", fontSize = 11.sp) }
                                OutlinedButton(onClick = { modpackImportLauncher.launch(arrayOf("application/octet-stream", "application/zip")) }, enabled = !importingServer, shape = McShape.ControlCompact) { Text(stringResource(R.string.dlx_modpack), fontSize = 11.sp) }
                            }
                            if (pendingImport != null) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = importName, onValueChange = { importName = it }, label = { Text(stringResource(R.string.dlx_server_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                            if (importingServer) {
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(progress = { importProgress ?: 0f }, modifier = Modifier.fillMaxWidth())
                                Text(stringResource(R.string.dlx_importing, ((importProgress ?: 0f) * 100).toInt()), color = Muted, fontSize = 11.sp)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = pendingImport != null && importName.isNotBlank() && !importingServer,
                            onClick = {
                                val selected = pendingImport ?: return@TextButton
                                when (selected.kind) {
                                    "folder" -> vm.importServerFromFolder(selected.uri, importName.trim())
                                    "jar" -> vm.importServerFromJar(selected.uri, importName.trim())
                                    "modpack" -> vm.importServerFromArchive(selected.uri, importName.trim())
                                    else -> vm.importServerFromArchive(selected.uri, importName.trim())
                                }
                            }
                        ) { Text(stringResource(R.string.dlx_start_import), color = Indigo) }
                    },
                    dismissButton = { TextButton(enabled = !importingServer, onClick = { showImportDialog = false; pendingImport = null }) { Text(stringResource(R.string.s402), color = Muted) } }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private data class InstallerJavaRequirement(
    val recommendedJava: JavaVersion?,
    val exactLegacyForgeRequirement: Boolean
)

private fun installerJavaRequirement(
    core: ServerCore,
    minecraftVersion: String
): InstallerJavaRequirement? {
    if (!core.needsInstaller) return null

    val versionParts = minecraftVersion
        .trim()
        .removePrefix("v")
        .split('.')
        .mapNotNull { it.toIntOrNull() }
    val major = versionParts.getOrNull(0)
    val minor = versionParts.getOrNull(1)
    val patch = versionParts.getOrNull(2)
    if (major != 1 || minor == null) return InstallerJavaRequirement(null, false)

    val java = when {
        minor <= 16 -> JavaVersion.Java11
        minor > 20 || (minor == 20 && (patch ?: 0) >= 5) -> JavaVersion.Java25
        else -> JavaVersion.Java17
    }
    return InstallerJavaRequirement(
        recommendedJava = java,
        exactLegacyForgeRequirement = core == ServerCore.Forge && minecraftVersion == "1.12.2"
    )
}

@Composable
private fun InstallerJavaNotice(core: ServerCore, minecraftVersion: String) {
    val requirement = installerJavaRequirement(core, minecraftVersion) ?: return
    Spacer(Modifier.height(10.dp))
    Text(
        if (requirement.exactLegacyForgeRequirement) {
            stringResource(R.string.dl_forge_java8_hint)
        } else if (requirement.recommendedJava != null) {
            stringResource(R.string.dl_java_rec, core.displayName, minecraftVersion, requirement.recommendedJava.displayName)
        } else {
            stringResource(R.string.dl_java_need, core.displayName)
        },
        color = Coral,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(3.dp))
    Text(
        stringResource(R.string.dl_first_install_hint),
        color = Coral,
        fontSize = 11.sp
    )
    Spacer(Modifier.height(3.dp))
    Text(
        stringResource(R.string.dl_java_disclaimer),
        color = Coral,
        fontSize = 11.sp
    )
}
