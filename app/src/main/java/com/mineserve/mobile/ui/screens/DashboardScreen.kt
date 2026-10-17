package com.mineserve.mobile.ui.screens

// 性能修改理由：资源订阅保持局部化，输入框使用本地编辑状态，并让概览内容避让输入法。
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.data.JavaRuntime
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.JavaVendor
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.PhpVersion
import com.mineserve.mobile.data.ProotDistro
import com.mineserve.mobile.data.RuntimeKind
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.data.isProotGuestRuntime
import com.mineserve.mobile.ui.DebouncedTextField
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.HeroBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.ProgressTrack
import com.mineserve.mobile.ui.QqGroupDialog
import com.mineserve.mobile.ui.StepRow
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.CoralSoft
import com.mineserve.mobile.ui.theme.FieldGray
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 概览页：完全对齐参考界面 hero + 安装步骤 + 核心选择 + 启停按钮
 * 插件与端口字段拆到对应 Tab，但概览页提供入口按钮（参考界面把插件/端口也放在首页）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    vm: McViewModel,
    onShowDownloadHelp: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onEnvManager: (Int) -> Unit
) {
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showStartSettings by remember { mutableStateOf(false) }
    // QQ 群弹窗（顶部企鹅图标触发）
    var showQqDialog by remember { mutableStateOf(false) }
    // Snackbar 文案需在 Composable 上下文取值（onClick 内不可调用 @Composable）
    val mirrorSwitchMsg = stringResource(R.string.mirror_switch_requested)

    // 收集 errorFlow 和 messageFlow，显示 Snackbar
    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }
    // 首屏优先完成布局；各功能卡片自行订阅并刷新局部状态。

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            item {
                HeaderBlock(
                    eyebrow = stringResource(R.string.eyebrow_dashboard),
                    title = stringResource(R.string.s340),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // QQ 群入口：企鹅图标，点开弹窗显示群号 / 复制 / 加群
                            IconButton(
                                onClick = { showQqDialog = true },
                                modifier = Modifier.size(McIcon.TouchCompact)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_qq_penguin),
                                    contentDescription = stringResource(R.string.s1051),
                                    tint = Indigo,
                                    modifier = Modifier.size(McIcon.Display)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            // 视觉突出的「依赖与环境管理」入口（靛蓝填充 + 图标，高对比）
                            Button(
                                onClick = { onEnvManager(0) },
                                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                                shape = McShape.ControlCompact,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Construction,
                                    contentDescription = stringResource(R.string.env_entry_desc),
                                    tint = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.size(McIcon.Inline)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.env_title),
                                    color = androidx.compose.ui.graphics.Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                )
            }

            item {
                DashboardHeroBlock(
                    vm = vm,
                    onRefresh = { vm.refreshServerStatus() }
                )
            }

            item {
                // Java 运行环境未安装时的初始入口（安装成功后自动隐藏）
                DashboardJavaInstallCard(vm = vm)
            }

            // 一卡一 item：恢复 LazyColumn 虚拟化。状态刷新只 invalidate 单卡布局，
            // 首屏组合分摊、视口外卡片延迟组合，避免滚动帧与整树重布局冲突（高压环境掉帧根因之一）。
            // 原「服务端资源」卡片已删除（其内存信息并入 Hero 卡片，诊断入口移到启动卡片）；
            // 直接移除该 item，下方卡片由 LazyColumn 自动上移，不留空白。
            item {
                // ── MC 终端入口（设备状态卡片下方） ──
                DashboardBootstrapCard(
                    vm = vm,
                    onShowHelp = onShowDownloadHelp,
                    onShowMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                )
            }

            item {
                // 一键安装依赖（依赖未装齐时在页面显眼位置展示；装齐后移到底部）
                DashboardDependenciesCard(vm = vm, onShowHelp = onShowDownloadHelp)
            }

            item {
                // 「启动服务端」卡片：核心选择 + 运行环境摘要 + 底部启停按钮（含启动设置抽屉入口）
                DashboardCoreSelectionCard(
                    vm = vm,
                    isBootstrapped = isBootstrapped,
                    onShowSettings = { showStartSettings = true },
                    onShowDiagnostics = onShowDiagnostics,
                    onShowMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                )
            }

            item {
                DashboardAddressCard(vm = vm)
            }

            item {
                DashboardPluginsCard(vm = vm)
            }
        }

        // QQ 交流群弹窗
        if (showQqDialog) {
            QqGroupDialog(onDismiss = { showQqDialog = false })
        }

        // 服务器启动设置抽屉（底部滑出）
        if (showStartSettings) {
            StartSettingsSheet(
                vm = vm,
                onDismiss = { showStartSettings = false }
            )
        }
    }
}

/**
 * Java 运行环境初始入口卡片：仅在所选 Java 版本缺失时显示，安装成功后自动隐藏。
 * 完整的 Java 版本管理（卸载/重装/多版本）在「依赖与环境管理」页。
 */
@Composable
private fun DashboardJavaInstallCard(vm: McViewModel) {
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val installed by vm.installedJava.collectAsState()
    val config by vm.config.collectAsState()
    val isInstalling by vm.isInstalling.collectAsState()
    val operation by vm.javaOperation.collectAsState()
    val selected = config.selectedJavaRuntime

    // 进入页面与环境就绪时刷新已装 Java 列表，保证卡片随安装结果实时隐藏。
    // 不按 isBootstrapped 设门槛：FCL 来源的 JRE 不依赖 Termux 前缀。
    LaunchedEffect(isBootstrapped) { vm.refreshJava() }

    // FCL 来源是 Android 原生二进制，环境没初始化也能下载安装；
    // Termux 来源必须先有环境（apt 装在里面）。
    val canInstall = selected.vendor == JavaVendor.Fcl || isBootstrapped
    if (!canInstall || selected in installed) return
    val op = operation

    McCard(title = stringResource(R.string.dash_java_title), compact = true) {
        Text(
            stringResource(R.string.env_java_need, selected.displayName),
            color = Coral,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (selected.vendor == JavaVendor.Fcl) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.java_group_fcl_note), color = Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (op != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
                Spacer(Modifier.width(8.dp))
                Text(op, color = Indigo, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { vm.installJava(selected) },
            enabled = !isInstalling,
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            shape = McShape.Control,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.env_java_install_btn), color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * 「启动服务端」卡片：核心选择 + 运行环境摘要 + 底部启停长条按钮。
 *
 * 右上角的齿轮是**启动设置抽屉**的唯一入口（运行环境 / 各来源版本 / 自定义启动命令 /
 * 崩溃自动重启 / 跳过启动中进度）。原先散在「服务控制」卡片里的启停按钮与
 * 「高级启动选项」卡片里的自定义命令，现在都收进这一张卡片与抽屉里。
 */
@Composable
private fun DashboardCoreSelectionCard(
    vm: McViewModel,
    isBootstrapped: Boolean,
    onShowSettings: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val installedCores by vm.config
        .map { it.installedCores }
        .distinctUntilChanged()
        .collectAsState(initial = emptyList())
    val activeCoreName by vm.config
        .map { it.activeCoreName }
        .distinctUntilChanged()
        .collectAsState(initial = null)
    val runtimeKind by vm.config
        .map { it.runtimeKind }
        .distinctUntilChanged()
        .collectAsState(initial = RuntimeKind.Java)
    val selectedJavaRuntime by vm.config
        .map { it.selectedJavaRuntime }
        .distinctUntilChanged()
        .collectAsState(initial = JavaRuntime.Default)
    val selectedJavaVersion by vm.config
        .map { it.selectedJavaVersion }
        .distinctUntilChanged()
        .collectAsState(initial = JavaVersion.Java17)
    val selectedPhpVersion by vm.config
        .map { it.selectedPhpVersion }
        .distinctUntilChanged()
        .collectAsState(initial = PhpVersion.Default)
    val installedPhp by vm.installedPhp.collectAsState()
    val isRunning by vm.serverState
        .map { it.isRunning }
        .distinctUntilChanged()
        .collectAsState(initial = false)
    val stopInFlight by vm.stopInFlight.collectAsState()
    val forceStopInFlight by vm.forceStopInFlight.collectAsState()
    var showCoreDropdown by remember { mutableStateOf(false) }
    var showForceStopConfirm by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val activeCore = installedCores.find { it.name == activeCoreName }
    val phpInstalled = selectedPhpVersion in installedPhp

    if (showForceStopConfirm) {
        AlertDialog(
            onDismissRequest = { showForceStopConfirm = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = Coral, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.dash_force_stop_confirm_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Text(stringResource(R.string.dash_force_stop_confirm_msg), fontSize = 12.sp, color = Muted)
            },
            confirmButton = {
                TextButton(onClick = {
                    showForceStopConfirm = false
                    vm.forceStopServer()
                }) {
                    Text(stringResource(R.string.dash_force_stop_confirm_ok), color = Coral, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForceStopConfirm = false }) {
                    Text(stringResource(R.string.env_cancel), color = Muted)
                }
            }
        )
    }

    // 「运行诊断」入口原在已删除的「服务端资源」卡片右上角，现移到本卡片齿轮左侧。
    // 颜色/文案语义保持不变：修复中/诊断中=Indigo，未诊断=Muted，通过=Mint，有问题=Coral，
    // 结论文案挂 contentDescription，读屏仍能获知诊断结果。
    val diagnosticReport by vm.diagnosticReport.collectAsState()
    val isDiagnosing by vm.isDiagnosing.collectAsState()
    val isRepairingRuntime by vm.isRepairingRuntime.collectAsState()
    val diagnosticIssues = diagnosticReport.issueCount
    val diagnosticSummary = when {
        isRepairingRuntime -> stringResource(R.string.dash_diag_repairing)
        isDiagnosing -> stringResource(R.string.dash_diag_running)
        diagnosticReport.generatedAtMs == 0L -> stringResource(R.string.dash_diag_not_run)
        diagnosticIssues == 0 -> stringResource(R.string.dash_diag_pass)
        else -> stringResource(R.string.dash_diag_issues, diagnosticIssues)
    }
    val diagnosticTint = when {
        isRepairingRuntime || isDiagnosing -> Indigo
        diagnosticReport.generatedAtMs == 0L -> Muted
        diagnosticIssues == 0 -> Mint
        else -> Coral
    }

    McCard(
        title = stringResource(R.string.s368),
        compact = true,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 诊断入口（警示图标）排在齿轮左侧；命中区与图标沿用原资源卡片中的收敛尺寸，
                // 但为避免与 40dp 齿轮并排时命中区过小，这里统一到 36dp / 18dp。
                IconButton(
                    onClick = onShowDiagnostics,
                    modifier = Modifier.size(McIcon.TouchCompact)
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = diagnosticSummary,
                        tint = diagnosticTint,
                        modifier = Modifier.size(McIcon.Action)
                    )
                }
                // 较大的齿轮入口：命中区 40dp、图标 24dp，与标题行 14sp 字重相称。
                IconButton(
                    onClick = onShowSettings,
                    modifier = Modifier.size(McIcon.Touch)
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.s378),
                        tint = Indigo,
                        modifier = Modifier.size(McIcon.Display)
                    )
                }
            }
        }
    ) {
        if (installedCores.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(IndigoSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Text("↓", color = Indigo, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.s369), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.s370), color = Muted, fontSize = 11.sp)
                }
            }
        } else {
            Text(stringResource(R.string.s371), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (activeCore != null) {
                    stringResource(R.string.s372, activeCore.name, activeCore.core.displayName, activeCore.version)
                } else {
                    stringResource(R.string.s373)
                },
                color = if (activeCore != null) Mint else Coral,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { showCoreDropdown = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (activeCore != null) IndigoSoft else Indigo,
                        contentColor = if (activeCore != null) Indigo else Color.White
                    ),
                    shape = McShape.Control,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (activeCore != null) stringResource(R.string.s374, activeCore.name)
                        else stringResource(R.string.s375),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                DropdownMenu(
                    expanded = showCoreDropdown,
                    onDismissRequest = { showCoreDropdown = false }
                ) {
                    installedCores.forEach { core ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(core.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${core.core.displayName} ${core.version}", color = Muted, fontSize = 11.sp)
                                    if (core.core.isBedrock) {
                                        Text(
                                            stringResource(R.string.dash_bedrock_udp),
                                            color = Coral,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            },
                            onClick = {
                                vm.setActiveCore(core.name)
                                showCoreDropdown = false
                                onShowMessage(context.getString(R.string.s376, core.name))
                            }
                        )
                    }
                }
            }
        }

        // ── 当前运行环境摘要（真正的选择在齿轮抽屉里）──
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.dash_current_env),
                color = Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(6.dp))
            Text(
                when (runtimeKind) {
                    RuntimeKind.Java -> javaRuntimeLabel(selectedJavaRuntime)
                    RuntimeKind.Php -> stringResource(R.string.dash_env_php) + " · " + selectedPhpVersion.displayName
                },
                color = Indigo,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (activeCore?.core == ServerCore.PowerNukkitX &&
            (selectedJavaVersion == JavaVersion.Java8 || selectedJavaVersion == JavaVersion.Java17)) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.dash_pnx_java_note), color = Coral, fontSize = 11.sp)
        }
        if (runtimeKind == RuntimeKind.Php && !phpInstalled) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.dash_php_not_installed_note), color = Coral, fontSize = 11.sp)
        }
        if (!isRunning) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.s385), color = Muted, fontSize = 10.sp)
        }

        // ── 底部启停长条：未运行 = 一整条「启动」；运行中 = 水滴分裂成「强制停止 | 停止」──
        Spacer(Modifier.height(10.dp))
        StartStopBar(
            isRunning = isRunning,
            startEnabled = isBootstrapped,
            stopInFlight = stopInFlight,
            forceStopInFlight = forceStopInFlight,
            onStart = { vm.startServer() },
            onStop = { vm.stopServer() },
            onForceStop = { showForceStopConfirm = true }
        )
    }
}

@Composable
private fun DashboardDependenciesCard(
    vm: McViewModel,
    onShowHelp: () -> Unit
) {
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val isInstalling by vm.isInstalling.collectAsState()
    val installSpeed by vm.installSpeed.collectAsState()
    val dependencySteps by vm.serverState
        .map { state -> state.installSteps.filter { it.step != com.mineserve.mobile.data.InstallStep.Jdk } }
        .distinctUntilChanged()
        .collectAsState(initial = emptyList())
    val progress by vm.serverState
        .map { it.currentProgress }
        .distinctUntilChanged()
        .collectAsState(initial = 0)
    val depsInstalled = dependencySteps.isNotEmpty() && dependencySteps.all {
        it.status == com.mineserve.mobile.data.StepStatus.Done
    }
    // frpc 引擎也纳入首启检测。
    //
    // 它不在 InstallStep 里（不经 Termux apt，而是联网下载独立引擎），但同样是
    // frp 系隧道能启动的前提。首启时不提示，用户配好隧道点启动才会撞上
    // 「引擎未安装」，那时他已经离开了这个页面。
    val engine by vm.frpcEngine.collectAsState()
    val download by vm.frpcDownload.collectAsState()
    LaunchedEffect(Unit) { vm.refreshFrpcEngine() }
    if (!depsInstalled || !engine.installed) {
        McCard(
            title = stringResource(R.string.s357),
            compact = true,
            trailing = {
                Text(
                    stringResource(R.string.s358),
                    color = Indigo,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        ) {
            DownloadHintHeader(
                isBusy = isInstalling,
                busyText = stringResource(R.string.s359),
                idleText = stringResource(R.string.s360),
                speedBps = installSpeed,
                onShowHelp = onShowHelp
            )
            Spacer(Modifier.height(8.dp))
            dependencySteps.forEachIndexed { idx, step ->
                val tag = when (step.status) {
                    com.mineserve.mobile.data.StepStatus.Done -> stringResource(R.string.s361)
                    com.mineserve.mobile.data.StepStatus.Active -> stringResource(R.string.s362)
                    com.mineserve.mobile.data.StepStatus.Wait -> stringResource(R.string.s363)
                }
                StepRow(name = "${idx + 1}. ${step.step.label}", status = step.status, tag = tag)
            }
            // 引擎排在依赖之后，同样用 StepRow 保持视觉一致。
            StepRow(
                name = "${dependencySteps.size + 1}. ${stringResource(R.string.frpc_engine_title)}",
                status = if (engine.installed) com.mineserve.mobile.data.StepStatus.Done
                else com.mineserve.mobile.data.StepStatus.Wait,
                tag = stringResource(
                    if (engine.installed) R.string.s361 else R.string.s363
                )
            )
            Spacer(Modifier.height(10.dp))
            ProgressTrack(percent = progress)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.installDependencies() },
                enabled = !isInstalling && isBootstrapped,
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = McShape.Control,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isInstalling) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    when {
                        !isBootstrapped -> stringResource(R.string.s364)
                        isInstalling -> stringResource(R.string.s365)
                        else -> stringResource(R.string.s366)
                    },
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // 引擎独立于 apt 依赖，单独一个按钮：它走联网下载，与上面那条
            // Termux 安装流程不是一回事，混在一个按钮里无法表达"只缺引擎"。
            if (!engine.installed) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.downloadFrpcEngine() },
                    enabled = download !is McViewModel.DownloadState.Downloading && vm.frpcOnlineAvailable,
                    shape = McShape.Control,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val downloading = download is McViewModel.DownloadState.Downloading
                    if (downloading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        (download as? McViewModel.DownloadState.Downloading)
                            ?.fraction?.takeIf { it >= 0f }
                            ?.let { "${stringResource(R.string.frpc_engine_downloading)} ${(it * 100).toInt()}%" }
                            ?: stringResource(R.string.frpc_engine_download),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardBootstrapCard(
    vm: McViewModel,
    onShowHelp: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    if (isBootstrapped) return

    val bootstrapError by vm.bootstrapError.collectAsState()
    val bootstrapSpeed by vm.bootstrapSpeed.collectAsState()
    val currentMirrorIndex by vm.currentMirrorIndex.collectAsState()
    val progress by vm.serverState
        .map { it.currentProgress }
        .distinctUntilChanged()
        .collectAsState(initial = 0)
    var switchInProgress by remember { mutableStateOf(false) }
    var switchingFromMirror by remember { mutableStateOf(-1) }
    LaunchedEffect(currentMirrorIndex) {
        if (switchInProgress && (currentMirrorIndex == -1 || currentMirrorIndex != switchingFromMirror)) {
            switchInProgress = false
        }
    }
    val mirrorSwitchMsg = stringResource(R.string.mirror_switch_requested)
    McCard(title = stringResource(R.string.s351), compact = true) {
        DownloadHintHeader(
            isBusy = true,
            busyText = stringResource(R.string.s352, progress),
            idleText = stringResource(R.string.s353),
            speedBps = bootstrapSpeed,
            onShowHelp = onShowHelp
        )
        Spacer(Modifier.height(8.dp))
        if (bootstrapError != null) {
            Text(stringResource(R.string.s354), color = Coral, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(bootstrapError!!, color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            DashboardConsolePreview(vm = vm, maxLines = 3)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.retryBootstrap() },
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = McShape.Control,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.s355), color = Color.White, fontWeight = FontWeight.SemiBold) }
        } else {
            Text(stringResource(R.string.s356), color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = Indigo,
                trackColor = IndigoSoft
            )
            Spacer(Modifier.height(4.dp))
            Text("${progress}%", color = Muted, fontSize = 10.sp)
            if (currentMirrorIndex >= 0) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.s1057, vm.mirrorSources.getOrElse(currentMirrorIndex) { "" }),
                        color = Muted,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            if (switchInProgress) return@OutlinedButton
                            switchingFromMirror = currentMirrorIndex
                            switchInProgress = true
                            vm.switchBootstrapMirror()
                            onShowMessage(mirrorSwitchMsg)
                        },
                        enabled = !switchInProgress,
                        shape = McShape.ControlCompact,
                        // 原 48dp 的高度把这行撑得比上面的进度文字高出一倍多，
                        // 收到 36dp 与其它行内紧凑按钮一致。
                        modifier = Modifier.height(36.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(if (switchInProgress) R.string.mirror_switching else R.string.s1058), fontSize = 11.sp, color = Indigo)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            DashboardConsolePreview(vm = vm, maxLines = 5)
        }
    }
}

@Composable
private fun DashboardHeroBlock(
    vm: McViewModel,
    onRefresh: () -> Unit
) {
    val installedCores by vm.config.map { it.installedCores }.distinctUntilChanged().collectAsState(initial = emptyList())
    val activeCoreName by vm.config.map { it.activeCoreName }.distinctUntilChanged().collectAsState(initial = null)
    val selectedCore by vm.config.map { it.selectedCore }.distinctUntilChanged().collectAsState(initial = ServerCore.Paper)
    val mcVersion by vm.config.map { it.mcVersion }.distinctUntilChanged().collectAsState(initial = "")
    val activeCore = installedCores.find { it.name == activeCoreName }
    val coreLabel = activeCore?.let { "${it.name} (${it.core.displayName} ${it.version})" }
        ?: "${selectedCore.displayName} ${mcVersion}"
    val heroState by vm.serverState
        .map { state ->
            ServerState(
                isRunning = state.isRunning,
                tps = state.tps,
                onlinePlayers = state.onlinePlayers,
                maxPlayers = state.maxPlayers,
                usedMemoryMb = state.usedMemoryMb,
                runningSinceMs = state.runningSinceMs,
                startupPhase = state.startupPhase
            )
        }
        .distinctUntilChanged()
        .collectAsState(initial = ServerState())
    val resources by vm.serverResources.collectAsState()
    val isRefreshingStatus by vm.isRefreshingStatus.collectAsState()
    val lastRefreshAtMs by vm.lastStatusRefreshAtMs.collectAsState()
    val skipStartupProgress by vm.config
        .map { it.skipStartupProgress }
        .distinctUntilChanged()
        .collectAsState(initial = false)
    val maxHeapMb by vm.config
        .map { it.maxHeapMb }
        .distinctUntilChanged()
        .collectAsState(initial = 1024)
    val serverProperties by vm.serverProperties.collectAsState()
    val onlineModeEnabled = serverProperties["online-mode"]
        ?.trim()
        ?.equals("true", ignoreCase = true) == true
    HeroBlock(
        state = heroState,
        coreLabel = coreLabel,
        cpuPercent = resources.deviceCpuPercent,
        maxHeapMb = maxHeapMb,
        onlineModeEnabled = onlineModeEnabled,
        isRefreshing = isRefreshingStatus,
        lastRefreshAtMs = lastRefreshAtMs,
        skipStartupProgress = skipStartupProgress,
        onRefresh = onRefresh
    )
}

@Composable
private fun DashboardAddressCard(vm: McViewModel) {
    // 端口取真源（服务端配置文件里的 server-port），与「服务器配置」页和诊断同源。
    val localPort by vm.serverPort.collectAsState()
    val lanIp by vm.lanIp.collectAsState()
    val frpState by vm.frpState.collectAsState()
    val stunState by vm.stunState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { vm.refreshLanIp() }
    McCard(title = stringResource(R.string.s386), compact = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.s387), color = Muted, fontSize = 10.sp)
                Text(
                    "${lanIp}:${localPort}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 命中区统一到 40dp、图标 18dp：原先默认 48dp 的命中区会把这一行撑高，
            // 与卡片里 13sp 的正文不协调。
            IconButton(onClick = { vm.refreshLanIp() }, modifier = Modifier.size(McIcon.Touch)) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.s333), tint = Indigo, modifier = Modifier.size(McIcon.Action))
            }
            IconButton(
                onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("", "${lanIp}:${localPort}"))
                },
                modifier = Modifier.size(McIcon.Touch)
            ) {
                Icon(Icons.Outlined.ContentCopy, stringResource(R.string.s388), tint = Indigo, modifier = Modifier.size(McIcon.Action))
            }
        }
        // 两条通道各自的公网地址。
        //
        // 只显示聚合值是不够的：FRP 与 STUN 现在可以同时在跑，聚合值只能表达其中一条，
        // 另一条的地址就永远看不到。所以这里按通道各来一行，谁在跑显示谁。
        DashboardTunnelAddressRow(
            label = stringResource(R.string.net_dash_frp_address),
            url = frpState.publicUrl.takeIf { frpState.status == TunnelStatus.Running }.orEmpty(),
            onCopy = { vm.copyPublicUrl(context, it) }
        )
        DashboardTunnelAddressRow(
            label = stringResource(R.string.net_dash_stun_address),
            url = stunState.publicUrl.takeIf { stunState.status == TunnelStatus.Running }.orEmpty(),
            onCopy = { vm.copyPublicUrl(context, it) }
        )
    }
}

/**
 * 概览页的一条隧道公网地址。地址为空（该通道没在跑）时整行不渲染 ——
 * 停止后地址即失效，留在界面上会被当成可用地址发给玩家。
 */
@Composable
private fun DashboardTunnelAddressRow(label: String, url: String, onCopy: (String) -> Unit) {
    if (url.isBlank()) return
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Muted, fontSize = 10.sp)
            Text(
                url,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Indigo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = { onCopy(url) }, modifier = Modifier.size(McIcon.Touch)) {
            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.s388), tint = Indigo, modifier = Modifier.size(McIcon.Action))
        }
    }
}

@Composable
private fun DashboardPluginsCard(vm: McViewModel) {
    val installedPlugins by vm.installedPlugins.collectAsState()
    val pluginsDescription = if (installedPlugins.isEmpty()) {
        stringResource(R.string.s391)
    } else {
        stringResource(R.string.s392, installedPlugins.size)
    }
    McCard(title = stringResource(R.string.s390), compact = true) {
        Column(
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = pluginsDescription
            }
        ) {
            if (installedPlugins.isEmpty()) {
                Text(stringResource(R.string.s391), color = Muted, fontSize = 11.sp)
            } else {
                Text(stringResource(R.string.s392, installedPlugins.size), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DashboardConsolePreview(vm: McViewModel, maxLines: Int) {
    val consoleLines by vm.consolePreviewLines.collectAsState()
    val visibleLines = consoleLines.takeLast(maxLines)
    Column(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = visibleLines.joinToString(", ")
        }
    ) {
        visibleLines.forEach { line ->
            Text(
                line,
                color = Muted.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun DownloadHintHeader(
    isBusy: Boolean,
    busyText: String,
    idleText: String,
    speedBps: Long = 0L,
    onShowHelp: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：状态图标 + 文字 + 速度
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = Indigo,
                strokeWidth = 2.dp
            )
        } else {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Indigo.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("↓", color = Indigo, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            if (isBusy) busyText else idleText,
            color = if (isBusy) Indigo else Muted,
            fontSize = 11.sp,
            fontWeight = if (isBusy) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        // 速度显示（仅忙碌且有速度时显示）
        if (isBusy && speedBps > 0) {
            Text(
                formatSpeedShort(speedBps),
                color = Indigo,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.size(8.dp))
        }
        // 右侧：下载慢?查看解决方式
        Text(
            stringResource(R.string.s403),
            color = Indigo,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onShowHelp() }
        )
    }
}

/** 格式化速度为简短字符串（如 2.3 MB/s, 456 KB/s） */
private fun formatSpeedShort(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }
}

/**
 * 「来源 · 版本」形式的短标签，用在空间有限、又必须区分来源的地方
 * （启动卡片上的运行环境按钮）。
 *
 * 两套 JDK 的版本号完全重合（都有 17/21/25），只写「Java 21」等于没说是哪一套。
 * 弹窗与依赖页都按来源分组、靠分组标题区分，所以那边不用这个函数。
 */
@Composable
private fun javaRuntimeLabel(runtime: JavaRuntime): String {
    val vendor = stringResource(
        when (runtime.vendor) {
            JavaVendor.Termux -> R.string.java_vendor_termux
            JavaVendor.Fcl -> R.string.java_vendor_fcl
            JavaVendor.Proot -> R.string.java_vendor_proot
        }
    )
    return "$vendor · ${runtime.displayName}"
}

// ── 启动区域：长条启停按钮 + 启动设置抽屉 ──────────────────────────────

/**
 * 卡片底部的启停长条。
 *
 * 未运行时是一整条「启动」；运行中则以「水滴分裂」的方式分成左右两个按钮
 * （左：强制停止，右：停止）。
 *
 * 分裂用一个 0f→1f 的 [animateFloatAsState] 驱动：
 * 左按钮宽度由整宽收缩到半宽、右按钮宽度由 0 长到半宽、中间间隙同步张开。
 * 三者的宽度之和恒等于容器宽度，所以整条按钮的外框在任何一帧都不会跳动；
 * 颜色（填充绿 → 描边红 / 填充红）也用 [animateColorAsState] 插值，
 * 避免中途替换控件造成颜色瞬变。
 */
@Composable
private fun StartStopBar(
    isRunning: Boolean,
    startEnabled: Boolean,
    stopInFlight: Boolean,
    forceStopInFlight: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onForceStop: () -> Unit
) {
    val split by animateFloatAsState(
        targetValue = if (isRunning) 1f else 0f,
        animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
        label = "startStopSplit"
    )
    val leftContainer by animateColorAsState(
        targetValue = if (isRunning) Color.Transparent else Mint,
        animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
        label = "startStopLeftContainer"
    )
    val leftContent by animateColorAsState(
        targetValue = if (isRunning) Coral else Color.White,
        animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
        label = "startStopLeftContent"
    )
    val leftBorder by animateColorAsState(
        targetValue = if (isRunning) Coral else Color.Transparent,
        animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
        label = "startStopLeftBorder"
    )
    val shape = McShape.Control

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
    ) {
        val full = maxWidth
        val gap = 10.dp
        val half = (full - gap) / 2
        val leftWidth = full * (1f - split) + half * split
        val rightWidth = half * split

        // ── 左：启动（未运行）→ 强制停止（运行中）──
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(leftWidth)
                .fillMaxHeight()
                .alpha(if (!isRunning && !startEnabled) 0.45f else 1f)
                .clip(shape)
                .background(leftContainer)
                .border(BorderStroke(1.5.dp, leftBorder), shape)
                .clickable(
                    enabled = if (isRunning) !forceStopInFlight else startEnabled,
                    onClick = { if (isRunning) onForceStop() else onStart() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isRunning) Icons.Outlined.Warning else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = leftContent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Crossfade(
                    targetState = isRunning,
                    animationSpec = tween(durationMillis = 220),
                    label = "startStopLeftLabel"
                ) { running ->
                    Text(
                        if (running) stringResource(R.string.dash_force_stop)
                        else stringResource(R.string.s380),
                        color = leftContent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // ── 右：停止（随分裂进度从右边缘长出来并淡入）──
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(rightWidth)
                .fillMaxHeight()
                .alpha(split)
                .clip(shape)
                .background(Coral)
                .clickable(enabled = split > 0.6f && !stopInFlight, onClick = onStop)
        ) {
            // 内层固定半宽并贴右：外层宽度增长时文字原地不动、由左向右被"揭开"，
            // 不会随着宽度变化左右滑动。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(half)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (stopInFlight) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (stopInFlight) stringResource(R.string.s381) else stringResource(R.string.s382),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/** 抽屉下拉框的一项：key 是写回配置用的稳定标识，label/note 只用于展示。 */
private data class SheetOption(val key: String, val label: String, val note: String? = null)

private const val ENV_TERMUX_JAVA = "termux_java"
private const val ENV_FCL_JAVA = "fcl_java"
private const val ENV_PROOT_JAVA = "proot_java"
private const val ENV_PHP = "php"

/**
 * 通用下拉行：灰底胶囊 + 右侧箭头，点开 [DropdownMenu]。
 *
 * 与卡片里核心选择的行内样式同源（FieldGray + Indigo 文字），保持视觉一致。
 */
@Composable
private fun SheetDropdownRow(
    label: String,
    selectedLabel: String,
    options: List<SheetOption>,
    selectedKey: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FieldGray)
                    .alpha(if (enabled) 1f else 0.5f)
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    selectedLabel,
                    color = Indigo,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Indigo,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    option.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (option.key == selectedKey) Indigo
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                option.note?.let { Text(it, color = Muted, fontSize = 10.sp) }
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(option.key)
                        }
                    )
                }
            }
        }
    }
}

/** 抽屉里的开关行：左标题 + 说明，右开关。 */
@Composable
private fun SheetSwitchRow(
    title: String,
    note: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(note, color = Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * 启动设置抽屉（Bottom Sheet）。
 *
 * 承载原先「服务控制」卡片里的运行环境选择与「高级启动选项」卡片里的自定义启动命令，
 * 外加运行模式、崩溃自动重启、跳过启动中进度。顺序固定为：
 * ① 运行环境 → ② 版本 → ③ 自定义启动命令 → ④ 崩溃自动重启 → ⑤ 跳过启动中进度。
 *
 * 版本选择按运行环境**互不混用**：选 Termux Java 只列 Termux 的版本，选 FCL Java
 * 只列 FCL 已发布的版本（上游没有 8/11），选 PHP 只列 PHP。改动一律走
 * [McViewModel.applyRuntimeSelection] 一次原子写入 —— 拆成多次 set 会被 config 的
 * 300ms 落盘 debounce 互相覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartSettingsSheet(
    vm: McViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // 必须先把下滑动画播完再移除组件：ModalBottomSheet 不会自己退场，
    // 直接 onDismiss() 会让抽屉"啪"地消失。
    val closeSheet: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    val runtimeKind by vm.config.map { it.runtimeKind }.distinctUntilChanged().collectAsState(initial = RuntimeKind.Java)
    val selectedJavaRuntime by vm.config.map { it.selectedJavaRuntime }.distinctUntilChanged().collectAsState(initial = JavaRuntime.Default)
    val selectedPhpVersion by vm.config.map { it.selectedPhpVersion }.distinctUntilChanged().collectAsState(initial = PhpVersion.Default)
    val installedJava by vm.installedJava.collectAsState()
    val installedPhp by vm.installedPhp.collectAsState()
    val prootDistro by vm.config.map { it.prootDistro }.distinctUntilChanged().collectAsState(initial = ProotDistro.Default)
    val customCommandEnabled by vm.config.map { it.advancedCustomCommandEnabled }.distinctUntilChanged().collectAsState(initial = false)
    val customCommand by vm.config.map { it.advancedCustomCommand }.distinctUntilChanged().collectAsState(initial = "")
    val autoRestartOnCrash by vm.config.map { it.autoRestartOnCrash }.distinctUntilChanged().collectAsState(initial = false)
    val skipStartupProgress by vm.config.map { it.skipStartupProgress }.distinctUntilChanged().collectAsState(initial = false)
    val maxHeapMb by vm.config.map { it.maxHeapMb }.distinctUntilChanged().collectAsState(initial = 1024)
    val isRunning by vm.serverState.map { it.isRunning }.distinctUntilChanged().collectAsState(initial = false)

    val installedLabel = stringResource(R.string.dash_java_installed)
    val notInstalledLabel = stringResource(R.string.dash_java_not_installed)
    // 打开抽屉时刷新一次已装列表：版本下拉里的「已安装/未安装」必须是最新的，
    // 否则刚在依赖页装完的版本会显示成未安装。
    LaunchedEffect(Unit) {
        vm.refreshJava()
        vm.refreshPhp()
    }
    // 运行环境与启动命令只在启动那一刻读一次 config，运行中改动无法生效，
    // 因此运行中一律置灰（而不是骗用户"改了就好"）。
    val editable = !isRunning

    val envKey = when {
        runtimeKind == RuntimeKind.Php -> ENV_PHP
        selectedJavaRuntime.vendor == JavaVendor.Fcl -> ENV_FCL_JAVA
        // Proot 来源必须单独成一项。此前没有这一支，于是选了 Proot 的用户
        // 打开抽屉会看到「Termux Java」被选中，而版本列表又是 Termux 的那一份 ——
        // 界面显示的来源与实际运行的来源不一致。
        selectedJavaRuntime.vendor == JavaVendor.Proot -> ENV_PROOT_JAVA
        // 历史配置可能是 Termux + Java 8（它实际跑在容器里）。这里按实际链路
        // 归到 Proot 那一项，而不是让用户在「Termux Java」下看到一个
        // 已经不再列出的版本。
        isProotGuestRuntime(selectedJavaRuntime.vendor, selectedJavaRuntime.version) -> ENV_PROOT_JAVA
        else -> ENV_TERMUX_JAVA
    }

    val envOptions = listOf(
        SheetOption(ENV_TERMUX_JAVA, stringResource(R.string.dash_env_termux_java)),
        SheetOption(ENV_FCL_JAVA, stringResource(R.string.dash_env_fcl_java)),
        SheetOption(ENV_PROOT_JAVA, stringResource(R.string.dash_env_proot_java)),
        SheetOption(ENV_PHP, stringResource(R.string.dash_env_php))
    )

    val versionLabel: String
    val versionOptions: List<SheetOption>
    val versionKey: String
    when (envKey) {
        ENV_PHP -> {
            versionLabel = stringResource(R.string.dash_env_version_php)
            versionKey = selectedPhpVersion.name
            versionOptions = PhpVersion.entries.map { version ->
                SheetOption(
                    key = version.name,
                    label = version.displayName,
                    note = if (version in installedPhp) installedLabel else notInstalledLabel
                )
            }
        }
        ENV_FCL_JAVA -> {
            versionLabel = stringResource(R.string.dash_env_version_fcl_java)
            versionKey = selectedJavaRuntime.version.name
            versionOptions = JavaVendor.Fcl.versions.map { version ->
                SheetOption(
                    key = version.name,
                    label = version.displayName,
                    note = if (JavaRuntime(JavaVendor.Fcl, version) in installedJava) installedLabel else notInstalledLabel
                )
            }
        }
        ENV_PROOT_JAVA -> {
            versionLabel = stringResource(R.string.dash_env_version_proot_java)
            versionKey = selectedJavaRuntime.version.name
            // 只列**当前发行版确实提供**的版本。列并集会出现「看得到但装不上」的项
            // （focal 没有 Java 21、jammy 没有 Java 8），点下去 apt 只会报
            // 「Unable to locate package」，用户无法理解为什么。
            versionOptions = prootDistro.availableJavaVersions.map { version ->
                SheetOption(
                    key = version.name,
                    label = version.displayName,
                    note = if (JavaRuntime(JavaVendor.Proot, version) in installedJava) {
                        installedLabel
                    } else {
                        notInstalledLabel
                    }
                )
            }
        }
        else -> {
            versionLabel = stringResource(R.string.dash_env_version_termux_java)
            versionKey = selectedJavaRuntime.version.name
            versionOptions = JavaVendor.Termux.versions.map { version ->
                SheetOption(
                    key = version.name,
                    label = version.displayName,
                    note = if (JavaRuntime(JavaVendor.Termux, version) in installedJava) installedLabel else notInstalledLabel
                )
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        // 明显的暗色遮罩：抽屉是"浮在原页面之上"的一层，顶部留出的那部分原页面必须被压暗。
        scrimColor = Color.Black.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = McSpacing.Page)
                .padding(bottom = McSpacing.Page)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.s395),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.dash_start_settings_subtitle),
                        color = Muted,
                        fontSize = 10.sp
                    )
                }
                TextButton(onClick = closeSheet) {
                    Text(stringResource(R.string.s73), color = Indigo, fontWeight = FontWeight.Bold)
                }
            }

            if (isRunning) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.env_java_running_block), color = Coral, fontSize = 10.sp)
            }

            Spacer(Modifier.height(10.dp))

            // ① 运行环境
            SheetDropdownRow(
                label = stringResource(R.string.dash_runtime_env),
                selectedLabel = envOptions.first { it.key == envKey }.label,
                options = envOptions,
                selectedKey = envKey,
                enabled = editable
            ) { key ->
                val php = selectedPhpVersion
                when (key) {
                    ENV_PHP -> vm.applyRuntimeSelection(RuntimeKind.Php, selectedJavaRuntime, php)
                    ENV_FCL_JAVA -> vm.applyRuntimeSelection(
                        RuntimeKind.Java, JavaRuntime(JavaVendor.Fcl, selectedJavaRuntime.version), php
                    )
                    // 切到 Proot 时把版本对齐到当前发行版：从 FCL/Termux 切过来时
                    // 手上可能是 Java 25（两个发行版都没有），必须落到一个装得上的版本。
                    ENV_PROOT_JAVA -> vm.applyRuntimeSelection(
                        RuntimeKind.Java,
                        JavaRuntime(JavaVendor.Proot, selectedJavaRuntime.version),
                        php,
                        prootDistro = prootDistro
                    )
                    else -> vm.applyRuntimeSelection(
                        RuntimeKind.Java, JavaRuntime(JavaVendor.Termux, selectedJavaRuntime.version), php
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ①b 发行版：只在选了 PRoot 容器时出现。
            // 放在版本之前，因为它决定版本列表 —— 顺序反了会让人以为「换了发行版
            // 版本却没变」是 bug（实际是被自动对齐到了一个该发行版提供的版本）。
            if (envKey == ENV_PROOT_JAVA) {
                SheetDropdownRow(
                    label = stringResource(R.string.dash_env_distro_label),
                    selectedLabel = prootDistro.displayName,
                    options = ProotDistro.entries.map { SheetOption(it.name, it.displayName) },
                    selectedKey = prootDistro.name,
                    enabled = editable
                ) { key ->
                    val picked = ProotDistro.fromName(key) ?: return@SheetDropdownRow
                    // 换发行版必须同时重新挑版本：focal 与 jammy 的可用版本不同，
                    // 沿用旧版本会写出一个该发行版装不上的组合。
                    vm.applyRuntimeSelection(
                        RuntimeKind.Java,
                        JavaRuntime(JavaVendor.Proot, selectedJavaRuntime.version),
                        selectedPhpVersion,
                        prootDistro = picked
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ② 版本（随运行环境切换，三者互不混用）
            SheetDropdownRow(
                label = versionLabel,
                selectedLabel = versionOptions.firstOrNull { it.key == versionKey }?.label ?: "",
                options = versionOptions,
                selectedKey = versionKey,
                enabled = editable
            ) { key ->
                val php = selectedPhpVersion
                when (envKey) {
                    ENV_PHP -> PhpVersion.fromName(key)?.let { picked ->
                        vm.applyRuntimeSelection(RuntimeKind.Php, selectedJavaRuntime, picked)
                    }
                    ENV_FCL_JAVA -> JavaVersion.entries.firstOrNull { it.name == key }?.let { picked ->
                        vm.applyRuntimeSelection(
                            RuntimeKind.Java,
                            JavaRuntime(JavaVendor.Fcl, picked),
                            php
                        )
                    }
                    ENV_PROOT_JAVA -> JavaVersion.entries.firstOrNull { it.name == key }?.let { picked ->
                        vm.applyRuntimeSelection(
                            RuntimeKind.Java,
                            JavaRuntime(JavaVendor.Proot, picked),
                            php,
                            prootDistro = prootDistro
                        )
                    }
                    else -> JavaVersion.entries.firstOrNull { it.name == key }?.let { picked ->
                        vm.applyRuntimeSelection(
                            RuntimeKind.Java,
                            JavaRuntime(JavaVendor.Termux, picked),
                            php
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ③ 自定义启动命令
            SheetSwitchRow(
                title = stringResource(R.string.dash_custom_command_title),
                note = stringResource(R.string.dash_custom_command_note),
                checked = customCommandEnabled,
                enabled = editable,
                onCheckedChange = { enabled ->
                    vm.updateConfig { it.copy(advancedCustomCommandEnabled = enabled) }
                }
            )
            AnimatedVisibility(
                visible = customCommandEnabled,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    DebouncedTextField(
                        value = customCommand,
                        onValueChange = { value ->
                            vm.updateConfig { it.copy(advancedCustomCommand = value) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dash_custom_command_label), fontSize = 11.sp) },
                        placeholder = {
                            Text(
                                stringResource(R.string.dash_custom_command_placeholder),
                                fontSize = 11.sp,
                                color = Muted
                            )
                        },
                        minLines = 3,
                        maxLines = 6,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ④ 崩溃自动重启
            SheetSwitchRow(
                title = stringResource(R.string.s396),
                note = stringResource(R.string.s397),
                checked = autoRestartOnCrash,
                onCheckedChange = { vm.setAutoRestart(it) }
            )

            Spacer(Modifier.height(6.dp))

            // ⑤ 跳过启动中进度
            SheetSwitchRow(
                title = stringResource(R.string.dash_skip_startup_progress),
                note = stringResource(R.string.dash_skip_startup_progress_note),
                checked = skipStartupProgress,
                onCheckedChange = { vm.setSkipStartupProgress(it) }
            )

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.s398, maxHeapMb),
                color = Muted,
                fontSize = 10.sp
            )
        }
    }
}
