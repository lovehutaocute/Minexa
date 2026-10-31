package com.mineserve.mobile.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.data.InstalledCore
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.StorageLocation
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.FieldGray
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoDark
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Line
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.MintBright
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 服务端核心下载页（紧凑版）
 *
 * 版面目标：**一屏装完**。360×800 的机器上，页头、目标条、核心选择、版本+命名下载、
 * 已安装核心要能同时出现在一个屏幕里，不需要先滚半页才知道按钮在哪。
 *
 * 为此做了四件事：
 * 1. 核心不再一次性铺 18 个。改成「家族分段条 + 只显示当前家族的磁贴」，
 *    默认落在当前选中核心所属家族（Paper → 插件端 6 个，两列三行），
 *    其余家族 1~2 行 —— 这一步的高度从 9 行降到最多 3 行。
 * 2. 版本与命名下载合并成一步，同一张面板里「版本行 + 名称/下载同行」，
 *    少一层步骤头、少一层卡片内边距，动作集中在一处。
 * 3. 所有控件降一档：磁贴 36dp、分段条 30dp、变更 30dp、主按钮 42dp、行内动作 30dp。
 * 4. 顶部目标条由整块渐变摘要卡压成 42dp 细条，只留「核心 · 版本 · 环境状态」。
 */
private data class DownloadImport(val kind: String, val uri: Uri)

/** 核心家族：决定分段条、磁贴徽章与已安装列表字母块的配色。 */
private enum class CoreGroup(val labelRes: Int, val accent: Color) {
    Plugin(R.string.dl2_group_plugin, Color(0xFF3B4C9C)),
    Vanilla(R.string.dl2_group_vanilla, Color(0xFF5B6474)),
    Mod(R.string.dl2_group_mod, Color(0xFF1F8F66)),
    Proxy(R.string.dl2_group_proxy, Color(0xFF6355C0)),
    Bedrock(R.string.dl2_group_bedrock, Color(0xFFC4493F)),
    Other(R.string.dl2_group_other, Color(0xFF6B7385));

    companion object {
        fun of(core: ServerCore): CoreGroup = when (core) {
            ServerCore.Paper, ServerCore.Purpur, ServerCore.Leaves, ServerCore.Leaf,
            ServerCore.Spigot, ServerCore.CraftBukkit -> Plugin
            ServerCore.Vanilla -> Vanilla
            ServerCore.Fabric, ServerCore.Forge, ServerCore.NeoForge, ServerCore.Quilt -> Mod
            ServerCore.Velocity, ServerCore.BungeeCord -> Proxy
            ServerCore.PowerNukkitX, ServerCore.PocketMine, ServerCore.Allay, ServerCore.Bds -> Bedrock
            ServerCore.Unknown -> Other
        }
    }
}

/**
 * 小字号状态文字用的加深色：全局 Mint/Coral 在浅底上只有 2.5:1，
 * 9~10sp 的徽章与警告读起来发虚，这里按主题取加深/提亮的同色相值（≥4.5:1）。
 */
@Composable
private fun okInk(): Color = if (isSystemInDarkTheme()) Color(0xFF8CF0C4) else Color(0xFF1D7F5C)

@Composable
private fun warnInk(): Color = if (isSystemInDarkTheme()) Color(0xFFFFB4A8) else Color(0xFFB23A31)

@Composable
private fun okTint(): Color = if (isSystemInDarkTheme()) Color(0xFF17382C) else Color(0xFFE7F8F0)

@Composable
private fun warnTint(): Color = if (isSystemInDarkTheme()) Color(0xFF3D2424) else Color(0xFFFDEEEC)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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

    var customVersion by remember { mutableStateOf("") }
    var customCoreName by remember { mutableStateOf("") }
    var showVersionSheet by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<DownloadImport?>(null) }
    var importName by remember { mutableStateOf("") }
    var importObservedRunning by remember { mutableStateOf(false) }
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
        vm.loadVersions(config.selectedCore)
    }
    // 进入本页先与磁盘对齐一次：用户可能刚在文件管理器里删掉了某个核心目录，
    // 列表里那条记录此刻已经是幽灵条目（点进去空、启动报找不到 jar）。
    LaunchedEffect(Unit) {
        vm.syncInstalledCoresWithDisk()
    }

    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }

    val installed = config.installedCores.filter { it.location == StorageLocation.Internal }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            DownloadHeader(onShowDownloadHelp = onShowDownloadHelp)

            TargetStrip(
                core = config.selectedCore,
                version = config.mcVersion,
                ready = isBootstrapped,
                bedrock = config.selectedCore.isBedrock,
                detecting = isLoadingVersions
            )

            // ① 核心类型
            SpecStep(
                index = 1,
                title = stringResource(R.string.dl2_step_core),
                summary = config.selectedCore.displayName
            ) {
                CorePicker(selected = config.selectedCore, onPick = { vm.selectCore(it) })
                Spacer(Modifier.height(7.dp))
                CoreMeta(core = config.selectedCore, onImport = { showImportDialog = true })
            }

            // ② 版本 + 命名下载（合并为一步，动作集中）
            SpecStep(
                index = 2,
                title = stringResource(R.string.dl2_step_launch),
                summary = null
            ) {
                VersionLaunchPanel(
                    core = config.selectedCore,
                    version = config.mcVersion,
                    hints = versionHints,
                    loadingVersions = isLoadingVersions,
                    ready = isBootstrapped,
                    downloading = isDownloadingCore,
                    progress = downloadProgress,
                    consoleLines = consoleLines,
                    name = customCoreName,
                    onNameChange = { customCoreName = it },
                    onOpenVersionSheet = { showVersionSheet = true },
                    onDownload = { vm.downloadCore(customCoreName) }
                )
            }

            InstalledSection(
                installed = installed,
                activeName = config.activeCoreName,
                running = serverState.isRunning,
                hints = versionHints,
                onActivate = { core ->
                    vm.setActiveCore(core.name)
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.s376, core.name)) }
                },
                onVerify = { vm.verifyOrRepairCore(it.dirName) },
                onDelete = { pendingDeleteCore = it }
            )

            Spacer(Modifier.height(18.dp))
        }
    }

    if (showVersionSheet) {
        VersionSheet(
            versions = availableVersions,
            hints = versionHints,
            selected = config.mcVersion,
            usesCoreVersion = usesCoreVersion,
            loading = isLoadingVersions,
            customVersion = customVersion,
            onCustomVersionChange = { customVersion = it },
            onPick = { vm.setMcVersion(it) },
            onManual = { value ->
                val trimmed = value.trim()
                if (trimmed.isNotBlank()) {
                    vm.setMcVersion(trimmed)
                    customVersion = ""
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.s476, trimmed)) }
                }
            },
            onDismiss = { showVersionSheet = false }
        )
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
                }) { Text(stringResource(R.string.s339), color = warnInk(), fontWeight = FontWeight.SemiBold) }
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
                        ImportChip(stringResource(R.string.dlx_folder), !importingServer) { folderImportLauncher.launch(null) }
                        ImportChip(
                            stringResource(R.string.dlx_archive),
                            !importingServer
                        ) { archiveImportLauncher.launch(arrayOf("application/zip", "application/gzip", "application/x-tar", "application/octet-stream")) }
                        ImportChip("JAR", !importingServer) { jarImportLauncher.launch(arrayOf("application/java-archive", "application/octet-stream")) }
                        ImportChip(
                            stringResource(R.string.dlx_modpack),
                            !importingServer
                        ) { modpackImportLauncher.launch(arrayOf("application/octet-stream", "application/zip")) }
                    }
                    if (pendingImport != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = importName,
                            onValueChange = { importName = it },
                            label = { Text(stringResource(R.string.dlx_server_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (importingServer) {
                        Spacer(Modifier.height(10.dp))
                        SpecProgress(((importProgress ?: 0f) * 100).toInt())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.dlx_importing, ((importProgress ?: 0f) * 100).toInt()),
                            color = Muted, fontSize = 11.sp
                        )
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
                            else -> vm.importServerFromArchive(selected.uri, importName.trim())
                        }
                    }
                ) { Text(stringResource(R.string.dlx_start_import), color = Indigo) }
            },
            dismissButton = {
                TextButton(enabled = !importingServer, onClick = { showImportDialog = false; pendingImport = null }) {
                    Text(stringResource(R.string.s402), color = Muted)
                }
            }
        )
    }
}

// ── 页头 ─────────────────────────────────────────────────────────────

/** 页头：小字眉标 + 18sp 标题 + 一行副标题 + 右侧帮助。含状态栏约 92dp。 */
@Composable
private fun DownloadHeader(onShowDownloadHelp: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(start = McSpacing.Page, end = McSpacing.Page, top = 10.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.eyebrow_download),
                    color = Indigo,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.s450),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.dl2_subtitle),
                    color = Muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(IndigoSoft)
                    .clickable { onShowDownloadHelp() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = stringResource(R.string.eyebrow_help),
                    tint = Indigo,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── 目标细条 ─────────────────────────────────────────────────────────

/**
 * 目标条：42dp 的渐变细条，只放「核心 · 版本 · 环境状态」。
 * 替代原来 62dp 的整块摘要卡 —— 信息一样，高度少 20dp，也不抢核心选择的视线。
 *
 * [detecting] 为真时版本位显示省略号：此刻列表还在路上，配置里的版本号属于
 * 上一个核心，照原样显示会让人以为「版本检测出错」。
 */
@Composable
private fun TargetStrip(
    core: ServerCore,
    version: String,
    ready: Boolean,
    bedrock: Boolean,
    detecting: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = McSpacing.Page)
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(colors = listOf(Indigo, IndigoDark)))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                core.displayName.take(1).uppercase(),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            core.displayName,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        Text(
            (if (bedrock) "Bedrock" else "Java") + " · " + when {
                detecting -> "…"
                version.isBlank() -> stringResource(R.string.dl_version_unset)
                else -> version
            },
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (ready) MintBright else Color(0xFFFFC069))
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(if (ready) R.string.dl2_ready else R.string.dl2_not_ready),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 8.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

// ── 步骤标题 ─────────────────────────────────────────────────────────

@Composable
private fun SpecStep(
    index: Int,
    title: String,
    summary: String?,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = McSpacing.Page)
            .padding(top = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Indigo),
                contentAlignment = Alignment.Center
            ) {
                Text("$index", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(7.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (summary != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    summary,
                    color = Indigo,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // 内容不再缩进：与标题圆点、页头、已安装区块共用同一条 16dp 左边缘，
        // 整页只有一条主对齐线；代价是标题文字比圆点缩进 25dp，视觉上仍成一组。
        Column(modifier = Modifier.padding(top = 8.dp), content = content)
    }
}

// ── ① 核心选择 ───────────────────────────────────────────────────────

/**
 * 家族分段条 + 当前家族的磁贴网格。
 *
 * 关键取舍：不再一次列出 18 个核心。默认停在当前核心所属家族（Paper → 插件端），
 * 切家族才换内容 —— 这一步的高度因此从 9 行降到最多 3 行，整页才能一屏装下。
 */
@Composable
private fun CorePicker(selected: ServerCore, onPick: (ServerCore) -> Unit) {
    var tabName by rememberSaveable { mutableStateOf<String?>(null) }
    val current = tabName?.let { name -> CoreGroup.entries.firstOrNull { it.name == name } }
        ?: CoreGroup.of(selected)
    val scroll = rememberScrollState()

    Column {
        // 分段条是横向滚动的，但一屏里最多露出 4 个家族，右侧被切掉的第 5 个看起来
        // 更像「渲染出错」而不是「还能往右滑」。这里加三处提示：
        //   ① 右（左）边缘的渐隐遮罩，颜色与页面底色一致 → 明确表示内容还在延伸；
        //   ② 遮罩上叠一个小箭头，静态图也能读出可滑动方向；
        //   ③ 内容右侧留 26dp 空白，最后一个标签不会贴死屏幕边缘。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                CoreGroup.entries.forEach { group ->
                    GroupTab(
                        label = stringResource(group.labelRes),
                        accent = group.accent,
                        selected = group == current
                    ) { tabName = group.name }
                }
                // 末尾留白：滑到底时仍有「这边还有内容」的余量感
                Spacer(Modifier.width(26.dp))
            }
            if (scroll.canScrollBackward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(20.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(MaterialTheme.colorScheme.surface, Color.Transparent)
                            )
                        )
                )
            }
            if (scroll.canScrollForward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(44.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.surface)
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 1.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(IndigoSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = stringResource(R.string.dl_group_scroll_hint),
                        tint = Indigo,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        // Unknown 是解析兜底值，正常流程不该被主动挑中；只有它当前就是选中项时才显示
        val cores = ServerCore.entries
            .filter { CoreGroup.of(it) == current }
            .filter { it != ServerCore.Unknown || selected == ServerCore.Unknown }
        cores.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { core ->
                    CoreTile(
                        core = core,
                        selected = selected == core,
                        modifier = Modifier.weight(1f)
                    ) { onPick(core) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 家族分段：选中为家族色实心，未选为浅底 + 家族色圆点。高 30dp。 */
@Composable
private fun GroupTab(label: String, accent: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent else FieldGray)
            .clickable { onClick() }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!selected) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** 核心磁贴：36dp 高，家族色字母块 + 名称 +（选中）勾。 */
@Composable
private fun CoreTile(
    core: ServerCore,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = CoreGroup.of(core).accent
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) accent.copy(alpha = if (isSystemInDarkTheme()) 0.24f else 0.08f)
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accent else Line,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                core.displayName.take(1).uppercase(),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            core.displayName,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/** 选中核心的描述 + 能力徽章 + 导入入口（单块，约 52dp）。 */
@Composable
private fun CoreMeta(core: ServerCore, onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(FieldGray.copy(alpha = if (isSystemInDarkTheme()) 0.55f else 0.6f))
            .padding(start = 9.dp, end = 4.dp, top = 7.dp, bottom = 3.dp)
    ) {
        Text(
            stringResource(coreDescriptionRes(core)),
            color = Muted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 4.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapabilityChip(
                enabled = core.supportsPlugins,
                onRes = R.string.dl_supports_plugins,
                offRes = R.string.dl_no_plugins
            )
            Spacer(Modifier.width(5.dp))
            CapabilityChip(
                enabled = core.supportsMods,
                onRes = R.string.dl_supports_mods,
                offRes = R.string.dl_no_mods
            )
            if (core == ServerCore.Bds) {
                Spacer(Modifier.width(5.dp))
                Text(
                    stringResource(R.string.dl_bds_short_warning),
                    color = warnInk(),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                stringResource(R.string.dlx_import_server),
                color = Indigo,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .heightIn(min = 28.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onImport() }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            )
        }
    }
}

private fun coreDescriptionRes(core: ServerCore): Int = when (core) {
    ServerCore.Paper -> R.string.dl_desc_paper
    ServerCore.Purpur -> R.string.dl_desc_purpur
    ServerCore.Leaves -> R.string.dl_desc_leaves
    ServerCore.Leaf -> R.string.dl_desc_leaf
    ServerCore.Spigot -> R.string.dl_desc_spigot
    ServerCore.CraftBukkit -> R.string.dl_desc_craftbukkit
    ServerCore.Fabric -> R.string.dl_desc_fabric
    ServerCore.Forge -> R.string.dl_desc_forge
    ServerCore.NeoForge -> R.string.dl_desc_neoforge
    ServerCore.Quilt -> R.string.dl_desc_quilt
    ServerCore.Vanilla -> R.string.dl_desc_vanilla
    ServerCore.Velocity -> R.string.dl_desc_velocity
    ServerCore.BungeeCord -> R.string.dl_desc_bungeecord
    ServerCore.PowerNukkitX -> R.string.dl_desc_pnx
    ServerCore.PocketMine -> R.string.dl_desc_pocketmine
    ServerCore.Allay -> R.string.dl_desc_allay
    ServerCore.Bds -> R.string.dl_desc_bds
    ServerCore.Unknown -> R.string.dl_desc_unknown
}

/** 能力徽章：支持 = 绿底绿字，不支持 = 灰底灰字（不支持插件不是错误，不用红）。 */
@Composable
private fun CapabilityChip(enabled: Boolean, onRes: Int, offRes: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) okTint() else FieldGray)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            stringResource(if (enabled) onRes else offRes),
            color = if (enabled) okInk() else Muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── ② 版本 + 命名下载 ────────────────────────────────────────────────

/**
 * 面板：上半是版本行（大号版本号 + 变更），下半是名称 + 下载按钮，两者同行。
 *
 * 合并的理由：版本和名称都是「为这次下载设定参数」，拆成两步会多一层步骤头 +
 * 一层卡片内边距，而真正要按的按钮被推到屏幕外。
 */
@Composable
private fun VersionLaunchPanel(
    core: ServerCore,
    version: String,
    hints: Map<String, String?>,
    loadingVersions: Boolean,
    ready: Boolean,
    downloading: Boolean,
    progress: McViewModel.DownloadProgress,
    consoleLines: List<String>,
    name: String,
    onNameChange: (String) -> Unit,
    onOpenVersionSheet: () -> Unit,
    onDownload: () -> Unit
) {
    val isBds = core == ServerCore.Bds
    // 版本列表还在加载时不允许下载：此刻 config 里的版本号可能仍是上一个核心的，
    // 拿它去拼下载地址只会得到一个 404。
    // 版本为空表示「刚切过核心、还没重新选版本」，同样不允许下载 —— 见 selectCore()。
    val canDownload = ready && !downloading && !loadingVersions && name.isNotBlank() &&
        version.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Indigo.copy(alpha = if (downloading) 0.45f else 0.2f), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        // ── 版本行 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // 列表在路上时先不显示旧核心的版本号（配置里此刻可能还是上一个核心的值）；
                    // 空串表示刚切过核心、还没重新选版本，显示「请选择版本」而不是留一片空白。
                    when {
                        loadingVersions -> "…"
                        version.isBlank() -> stringResource(R.string.dl_version_unset)
                        else -> version
                    },
                    color = if (version.isBlank() && !loadingVersions) {
                        Muted
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                    fontSize = if (version.isBlank() && !loadingVersions) 15.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (core.isBedrock && version.isNotBlank()) {
                    Text(
                        stringResource(
                            R.string.dl_supported_game_colon,
                            hints[version] ?: stringResource(R.string.dl_unknown_official)
                        ),
                        color = if (hints[version] == null) Muted else warnInk(),
                        fontSize = 9.sp,
                        maxLines = 1
                    )
                }
            }
            // BDS 没有「可选版本」这回事：官方接口只提供当前最新 Linux 构建的直链
            if (!isBds) {
                Spacer(Modifier.width(8.dp))
                // 未选版本时这个按钮是唯一的下一步：用实心主色把它从「次要操作」提为主操作
                val pickFirst = version.isBlank() && !loadingVersions
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (pickFirst) Indigo else Color.Transparent)
                        .border(1.dp, if (pickFirst) Indigo else Line, RoundedCornerShape(16.dp))
                        .clickable { onOpenVersionSheet() }
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(if (pickFirst) R.string.dl_select_version else R.string.dl2_change),
                        color = if (pickFirst) Color.White else Indigo,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        if (loadingVersions) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(10.dp), color = Indigo, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.s472), color = Muted, fontSize = 9.5.sp)
            }
        }
        if (isBds) {
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(R.string.dl_bds_version_note),
                color = Muted,
                fontSize = 9.5.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        InstallerJavaNotice(core = core, minecraftVersion = version)

        if (core == ServerCore.PowerNukkitX) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.dl_pnx_udp_hint),
                color = warnInk(),
                fontSize = 9.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(9.dp))

        // ── 名称 + 下载（同行） ──
        if (downloading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.s485) + " " + progress.percent + "%",
                    color = Indigo,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(progress.speedText, color = Indigo, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            SpecProgress(progress.percent)
            Spacer(Modifier.height(3.dp))
            Text(
                progress.downloadedText + " / " + progress.totalText,
                color = Muted,
                fontSize = 9.sp
            )
            if (consoleLines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(FieldGray)
                        .padding(horizontal = 7.dp, vertical = 5.dp)
                ) {
                    consoleLines.takeLast(4).forEach { line ->
                        Text(
                            line,
                            color = Muted,
                            fontSize = 8.5.sp,
                            lineHeight = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 输入框必须和右侧按钮同高（42dp）。OutlinedTextField 有 56dp 的硬性最小高度，
                // 放进来会比按钮高出 14dp，「名称 → 下载」这一行就歪了，所以这里用自绘的
                // BasicTextField 包边框，高度由布局决定。
                CompactInput(
                    value = name,
                    onValueChange = onNameChange,
                    placeholder = stringResource(R.string.dl2_name_ph),
                    height = 42.dp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(7.dp))
                Row(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (canDownload) Brush.linearGradient(colors = listOf(Indigo, IndigoDark))
                            else SolidColor(FieldGray)
                        )
                        .clickable(enabled = canDownload) { onDownload() }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "↓",
                        color = if (canDownload) Color.White else Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.s486),
                        color = if (canDownload) Color.White else Muted,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
            if (!ready) {
                Spacer(Modifier.height(5.dp))
                Text(
                    stringResource(R.string.dl_env_init),
                    color = warnInk(),
                    fontSize = 9.5.sp,
                    lineHeight = 13.sp
                )
            }
        }
    }
}

// ── 已安装核心 ───────────────────────────────────────────────────────

@Composable
private fun InstalledSection(
    installed: List<InstalledCore>,
    activeName: String?,
    running: Boolean,
    hints: Map<String, String?>,
    onActivate: (InstalledCore) -> Unit,
    onVerify: (InstalledCore) -> Unit,
    onDelete: (InstalledCore) -> Unit
) {
    // 折叠态由区块自己持有：装了很多核心时，这一坨会占掉整屏，
    // 把上面的「下载」按钮顶到看不见的地方。默认展开（首次进入要能看到自己有什么）。
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = McSpacing.Page)
            .padding(top = 16.dp)
    ) {
        // 整行可点：文字 + 数量 + 箭头。箭头朝上=已展开（点它会收起），朝下=已收起。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.s451),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${installed.size}",
                color = Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.diag_crash_collapse else R.string.s623
                ),
                tint = Muted,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.height(6.dp))

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                if (installed.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(FieldGray.copy(alpha = 0.6f))
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.s452), color = Muted, fontSize = 10.sp)
                    }
                } else {
                    installed.forEach { core ->
                        InstalledRow(
                            core = core,
                            active = core.name == activeName,
                            running = running,
                            hint = if (core.core == ServerCore.PowerNukkitX) hints[core.version] else null,
                            onActivate = { onActivate(core) },
                            onVerify = { onVerify(core) },
                            onDelete = { onDelete(core) }
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }

                if (running) {
                    Text(
                        stringResource(R.string.dl_core_delete_running),
                        color = warnInk(),
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledRow(
    core: InstalledCore,
    active: Boolean,
    running: Boolean,
    hint: String?,
    onActivate: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = CoreGroup.of(core.core).accent
    // 副标题在组合体里先算好：stringResource 是 @Composable，不能塞进 buildString 的 lambda
    val supportedGame = if (core.core == ServerCore.PowerNukkitX) {
        stringResource(R.string.dl_supported_game, hint ?: stringResource(R.string.dl_official_unknown))
    } else {
        ""
    }
    val meta = core.core.displayName + " " + core.version + supportedGame +
        stringResource(R.string.dl_folder, core.dirName)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (active) accent.copy(alpha = 0.4f) else Line, RoundedCornerShape(16.dp))
            .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = if (isSystemInDarkTheme()) 0.28f else 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    core.core.displayName.take(1).uppercase(),
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        core.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (active) {
                        Spacer(Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(okTint())
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                stringResource(R.string.s453),
                                color = okInk(),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    meta,
                    color = Muted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowAction(
                icon = if (active) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                label = stringResource(R.string.s455),
                tint = if (active) okInk() else Indigo,
                enabled = !active,
                onClick = onActivate
            )
            // BDS 不提供「校验修复」：它的核心不是单个 JAR，而是官方 zip 解开后的
            // 主程序 + 一批 x86_64 动态库 + 默认配置。用「校验 jar 的 manifest」这套
            // 判据必然误判，重下也只会把用户改过的 server.properties 覆盖掉。
            // 这里直接不显示该入口（VM 侧 verifyOrRepairCore 也会拒绝）。
            if (core.core != ServerCore.Bds) {
                RowAction(
                    icon = Icons.Outlined.Build,
                    label = stringResource(R.string.dl_core_verify),
                    tint = Indigo,
                    enabled = true,
                    onClick = onVerify
                )
            }
            Spacer(Modifier.weight(1f))
            RowAction(
                icon = Icons.Outlined.DeleteOutline,
                label = stringResource(R.string.s339),
                tint = warnInk(),
                enabled = !running,
                onClick = onDelete
            )
        }
    }
}

/** 行内动作：图标 + 文字，30dp 高，浅底无描边。 */
@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val ink = if (enabled) tint else tint.copy(alpha = 0.35f)
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) tint.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, color = ink, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── 版本抽屉 ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun VersionSheet(
    versions: List<String>,
    hints: Map<String, String?>,
    selected: String,
    usesCoreVersion: Boolean,
    loading: Boolean,
    customVersion: String,
    onCustomVersionChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onManual: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // 先把下滑动画播完再移除组件：ModalBottomSheet 不会自己退场
    val close: () -> Unit = {
        scope.launch {
            runCatching { sheetState.hide() }
            onDismiss()
        }
    }
    val pick: (String) -> Unit = { ver ->
        onPick(ver)
        close()
    }

    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = McSpacing.Page)
                .padding(bottom = McSpacing.Page)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(if (usesCoreVersion) R.string.dl_select_version else R.string.dl2_step_version),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (loading) stringResource(R.string.s472)
                        else stringResource(R.string.dl2_versions_count, versions.size),
                        color = Muted,
                        fontSize = 9.5.sp
                    )
                }
                TextButton(onClick = close) {
                    Text(stringResource(R.string.s402), color = Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))

            if (versions.isEmpty() && !loading) {
                Text(stringResource(R.string.s473), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
            }

            versions.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { ver ->
                        VersionChip(
                            version = ver,
                            selected = ver == selected,
                            modifier = Modifier.weight(1f)
                        ) { pick(ver) }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (usesCoreVersion) {
                val hint = hints[selected]
                if (hint != null) {
                    Text(
                        stringResource(R.string.dl_supported_game_colon, hint),
                        color = warnInk(),
                        fontSize = 9.5.sp
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Line)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.dl_manual_version),
                color = Muted,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactInput(
                    value = customVersion,
                    onValueChange = onCustomVersionChange,
                    placeholder = stringResource(if (usesCoreVersion) R.string.dl_version_example else R.string.s475),
                    height = 46.dp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(7.dp))
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (customVersion.isBlank()) FieldGray else Indigo)
                        .clickable(enabled = customVersion.isNotBlank()) {
                            onManual(customVersion)
                            close()
                        }
                        .padding(horizontal = 15.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.s477),
                        color = if (customVersion.isBlank()) Muted else Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionChip(
    version: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Indigo else FieldGray)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            version,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── 通用零件 ─────────────────────────────────────────────────────────

/**
 * 紧凑输入框：高度由调用方决定（42/46dp），用来和同行的按钮对齐。
 *
 * Material3 的 OutlinedTextField 有 56dp 的硬性最小高度，跟 42dp 的按钮并排时
 * 整行会歪 14dp。这里用 BasicTextField + 自绘边框，聚焦态描边换成主色 1.5dp，
 * 其余观感与全局输入框一致。
 */
@Composable
private fun CompactInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 42.dp,
    enabled: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(height)
            .onFocusChanged { focused = it.isFocused },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.5.sp
        ),
        cursorBrush = SolidColor(Indigo),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(if (focused) Indigo.copy(alpha = 0.04f) else Color.Transparent)
                    .border(
                        width = if (focused) 1.5.dp else 1.dp,
                        color = if (focused) Indigo else Line,
                        shape = shape
                    )
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        color = Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                inner()
            }
        }
    )
}

@Composable
private fun SpecProgress(percent: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(FieldGray)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                .height(5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(colors = listOf(Indigo, Color(0xFF6E7FD6))))
        )
    }
}

@Composable
private fun ImportChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(McShape.ControlCompact)
            .border(1.dp, Line, McShape.ControlCompact)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) Indigo else Muted.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── Java 版本提示 ────────────────────────────────────────────────────

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

/**
 * installer 类核心（Forge / NeoForge / Quilt）对 Java 版本敏感：
 * 装错版本时 installer.jar 直接抛 UnsupportedClassVersionError，表现成「下载完就崩」。
 * 所以在下载之前按 MC 版本反推推荐 Java，而不是等崩了再解释。
 */
@Composable
private fun InstallerJavaNotice(core: ServerCore, minecraftVersion: String) {
    val requirement = installerJavaRequirement(core, minecraftVersion) ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(warnTint())
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            if (requirement.exactLegacyForgeRequirement) {
                stringResource(R.string.dl_forge_java8_hint)
            } else if (requirement.recommendedJava != null) {
                stringResource(R.string.dl_java_rec, core.displayName, minecraftVersion, requirement.recommendedJava.displayName)
            } else {
                stringResource(R.string.dl_java_need, core.displayName)
            },
            color = warnInk(),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 13.sp
        )
        Text(
            stringResource(R.string.dl_first_install_hint),
            color = warnInk().copy(alpha = 0.85f),
            fontSize = 9.sp,
            lineHeight = 13.sp
        )
    }
}
