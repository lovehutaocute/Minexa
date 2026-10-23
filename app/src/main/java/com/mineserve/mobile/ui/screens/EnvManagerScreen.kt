package com.mineserve.mobile.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.data.JavaRuntime
import com.mineserve.mobile.data.JavaVendor
import com.mineserve.mobile.data.PhpVersion
import com.mineserve.mobile.data.StepStatus
import com.mineserve.mobile.server.tunnel.frpc.FrpcEngineConfig
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.HintNote
import com.mineserve.mobile.ui.JavaRuntimeGroupList
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.SegmentedTabs
import com.mineserve.mobile.ui.StepRow
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Tab 下标：崩溃页「前往安装」跳转到 Java 模块时使用 */
const val ENV_TAB_JAVA = 0
const val ENV_TAB_DEPS = 1
const val ENV_TAB_TERMUX = 2
const val ENV_TAB_PHP = 3

/**
 * 依赖与环境管理：Java 版本 / 依赖 / Termux 环境三组模块，
 * 每组提供 卸载（直接删除文件）/ 重装 / 安装 三个操作，状态实时反馈。
 */
@Composable
fun EnvManagerScreen(vm: McViewModel, initialTab: Int, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(initialTab.coerceIn(0, 3)) }
    var confirmText by remember { mutableStateOf<String?>(null) }
    var confirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.refreshPhp() }
    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }

    fun requestConfirm(text: String, action: () -> Unit) {
        confirmText = text
        confirmAction = action
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            BackBar(stringResource(R.string.env_title), onBack)
            val envTabs = listOf(
                stringResource(R.string.env_tab_java),
                stringResource(R.string.env_tab_deps),
                stringResource(R.string.env_tab_termux),
                stringResource(R.string.env_tab_php)
            )
            SegmentedTabs(
                options = envTabs,
                selectedIndex = tab,
                modifier = Modifier.padding(horizontal = McSpacing.Page, vertical = 6.dp),
                onSelect = { tab = it }
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                when (tab) {
                    ENV_TAB_JAVA -> JavaModule(vm, ::requestConfirm)
                    ENV_TAB_DEPS -> DependenciesModule(vm, ::requestConfirm)
                    ENV_TAB_PHP -> PhpModule(vm, ::requestConfirm)
                    else -> TermuxModule(vm, ::requestConfirm)
                }
            }
        }
    }

    confirmText?.let { text ->
        val action = confirmAction
        AlertDialog(
            onDismissRequest = { confirmText = null; confirmAction = null },
            title = { Text(stringResource(R.string.env_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(text, color = Muted, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmText = null
                    confirmAction?.invoke()
                    confirmAction = null
                }) { Text(stringResource(R.string.env_confirm_ok), color = Coral, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmText = null; confirmAction = null }) {
                    Text(stringResource(R.string.env_cancel), color = Muted)
                }
            }
        )
    }
}

/** 三键操作行：卸载 / 重装 / 安装，语义与配色固定，便于区分 */
@Composable
private fun ActionButtonsRow(
    uninstallEnabled: Boolean,
    reinstallEnabled: Boolean,
    installEnabled: Boolean,
    onUninstall: () -> Unit,
    onReinstall: () -> Unit,
    onInstall: () -> Unit,
    // 重装按钮文案可按模块定制（如 PHP 的重装实际只执行删除，需如实描述行为）
    reinstallLabelRes: Int = R.string.env_reinstall
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onUninstall,
            enabled = uninstallEnabled,
            shape = McShape.ControlCompact,
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.env_uninstall), color = Coral, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        OutlinedButton(
            onClick = onReinstall,
            enabled = reinstallEnabled,
            shape = McShape.ControlCompact,
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(reinstallLabelRes), color = Indigo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        Button(
            onClick = onInstall,
            enabled = installEnabled,
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            shape = McShape.ControlCompact,
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.env_install), color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun OperationBadge(operation: String?) {
    if (operation == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
        Spacer(Modifier.width(8.dp))
        Text(operation, color = Indigo, fontSize = 12.sp)
    }
}

// ── Java 版本管理 ─────────────────────────────────────────────

/**
 * Java 运行环境管理。
 *
 * 两套来源（Termux JDK / FCL JDK）各成一个分组，组内只显示纯版本号 ——
 * 版本号在两侧大量重合（17/21/25 都有），靠分组标题区分来源，
 * 列表本身与概览页「运行环境」弹窗共用 [JavaRuntimeGroupList]，
 * 两处不会各自长出一份清单。
 *
 * 「卸载 / 重装 / 安装」三键作用于**当前选中的那一份**（来源 + 版本），
 * 不是「全部 Java」。装完/删完的进度圈钉在对应的版本行上。
 */
@Composable
private fun JavaModule(vm: McViewModel, requestConfirm: (String, () -> Unit) -> Unit) {
    val installed by vm.installedJava.collectAsState()
    val operation by vm.javaOperation.collectAsState()
    val inFlight by vm.javaInFlight.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val config by vm.config.collectAsState()
    val selected = config.selectedJavaRuntime
    val busy = operation != null
    // 与依赖页其余模块一致：服务器运行中不允许改动运行时文件
    val running = serverState.isRunning
    val javaReinstallConfirmText = stringResource(R.string.env_java_reinstall_confirm)
    val javaUninstallConfirmText = stringResource(
        R.string.env_java_uninstall_confirm,
        selected.displayName
    )

    // 进页刷新一次：FCL 来源的 JRE 不依赖 Termux 环境，环境未就绪时也可能已经装好了
    LaunchedEffect(Unit) { vm.refreshJava() }
    // 容器占用要递归整个 rootfs，单独放后台算，避免阻塞首帧
    LaunchedEffect(Unit) { vm.refreshProotContainerSizes() }

    McCard(title = stringResource(R.string.env_tab_java)) {
        Text(stringResource(R.string.env_java_hint), color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        if (running) {
            Text(stringResource(R.string.env_java_running_block), color = Coral, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
        }
        OperationBadge(operation)
        // 三个来源默认全部折叠：共 11 条版本行，全展开会把下面「卸载 / 重装 / 安装」
        // 三键顶出屏幕，而这三个按钮才是本板块的主要操作。
        // 展开状态是纯界面状态，因此放在这里而不是 ViewModel。
        var expandedVendors by remember { mutableStateOf(emptySet<JavaVendor>()) }
        JavaRuntimeGroupList(
            installed = installed,
            selected = selected,
            onSelect = { vm.setJavaRuntime(it) },
            enabled = !busy && !running,
            inFlight = inFlight,
            expandedVendors = expandedVendors,
            onToggleVendor = { vendor ->
                expandedVendors = if (vendor in expandedVendors) {
                    expandedVendors - vendor
                } else {
                    expandedVendors + vendor
                }
            },
            // PRoot 分类要靠它判断哪些版本当前发行版提供不了（置灰）
            prootDistro = config.prootDistro,
            // 换发行版必须与版本一次写入：focal 与 jammy 的可用版本不同，
            // 沿用旧版本会写出该发行版装不上的组合。
            onDistroChange = { distro ->
                vm.applyRuntimeSelection(
                    kind = config.runtimeKind,
                    javaRuntime = JavaRuntime(JavaVendor.Proot, selected.version),
                    phpVersion = config.selectedPhpVersion,
                    prootDistro = distro
                )
            }
        )
        Spacer(Modifier.height(10.dp))
        ActionButtonsRow(
            uninstallEnabled = !busy && !running && selected in installed,
            reinstallEnabled = !busy && !running && selected in installed,
            installEnabled = !busy && !running && selected !in installed,
            onUninstall = {
                requestConfirm(javaUninstallConfirmText) { vm.deleteJava(selected) }
            },
            onReinstall = {
                // 按 (来源, 版本) 精确重装：只删这一份，不影响另一个来源与其他版本
                requestConfirm(javaReinstallConfirmText) { vm.reinstallJava(selected) }
            },
            onInstall = { vm.installJava(selected) }
        )

        // 「删除容器」是比「卸载某个 Java 版本」更粗粒度的回收操作，因此单独一行。
        //
        // 只在 PRoot 来源显示，且独立于所选版本 —— 因为容器是**发行版级**的：
        // 它装着该发行版下已安装的全部 JDK。把它混进上面那三个按钮，
        // 用户点「卸载 Java 17」就会连带清掉 Java 8/11，属于静默数据丢失。
        if (selected.vendor == JavaVendor.Proot) {
            val distro = config.prootDistro
            // 容器占用是重量级 IO（递归整个 rootfs），必须走 ViewModel 的异步 State，
            // 绝不能在 Composable 里同步调用 —— 那会让进页面/切发行版时掉帧。
            val sizes by vm.prootContainerSizes.collectAsState()
            val sizeText = formatBytes(sizes[distro] ?: 0L)
            val confirmText = stringResource(
                R.string.env_proot_delete_container_confirm,
                distro.displayName,
                sizeText
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { requestConfirm(confirmText) { vm.deleteProotContainer(distro) } },
                enabled = !busy && !running,
                shape = McShape.ControlCompact,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.env_proot_delete_container_action),
                    color = Coral,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ── 依赖管理 ──────────────────────────────────────────────────

@Composable
private fun DependenciesModule(vm: McViewModel, requestConfirm: (String, () -> Unit) -> Unit) {
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val isInstalling by vm.isInstalling.collectAsState()
    val installSpeed by vm.installSpeed.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val depsUninstallConfirmText = stringResource(R.string.env_deps_uninstall_confirm)
    val depsReinstallConfirmText = stringResource(R.string.env_deps_reinstall_confirm)
    // remember 住：installSteps 每次 serverState 变化都会换新 List 实例，
    // 不 remember 的话每次重组都要重新 filter + any 遍历一遍。
    val steps = remember(serverState.installSteps) {
        serverState.installSteps.filter { it.step != com.mineserve.mobile.data.InstallStep.Jdk }
    }
    val anyInstalled = remember(steps) { steps.any { it.status == StepStatus.Done } }

    McCard(title = stringResource(R.string.env_tab_deps)) {
        Text(stringResource(R.string.env_deps_hint), color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        if (isInstalling) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${stringResource(R.string.env_busy)} " +
                        if (installSpeed > 0) formatSpeed(installSpeed) else "",
                    color = Indigo, fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!isBootstrapped) {
            Text(stringResource(R.string.env_deps_need_bootstrap), color = Coral, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
        }
        steps.forEachIndexed { idx, step ->
            val tag = when (step.status) {
                StepStatus.Done -> stringResource(R.string.s361)
                StepStatus.Active -> stringResource(R.string.s362)
                StepStatus.Wait -> stringResource(R.string.s363)
            }
            StepRow(name = "${idx + 1}. ${step.step.label}", status = step.status, tag = tag)
        }
        if (steps.isNotEmpty() && !anyInstalled) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.env_deps_none_installed), color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        ActionButtonsRow(
            uninstallEnabled = !isInstalling && anyInstalled,
            reinstallEnabled = !isInstalling && isBootstrapped,
            installEnabled = !isInstalling && isBootstrapped,
            onUninstall = {
                requestConfirm(depsUninstallConfirmText) {
                    vm.deleteDependencies()
                }
            },
            onReinstall = {
                // reinstallDependencies 会先删除现有依赖文件，需二次确认
                requestConfirm(depsReinstallConfirmText) { vm.reinstallDependencies() }
            },
            onInstall = { vm.installDependencies() }
        )

        Spacer(Modifier.height(16.dp))
        FrpcEngineSection(vm = vm)
    }
}

// ── frpc 引擎（frp 系隧道共用）────────────────────────────────

/**
 * frpc 引擎的下载/导入/卸载入口。
 *
 * 放在依赖管理页而不是隧道页：引擎是 frp 系隧道能启动的前提，本质上是一份依赖，
 * 与 JDK、wget 同级。归在这里，"缺什么装什么"只有一个地方要看。
 *
 * 引擎不随 APK 内置 —— EdgeCube 的官方 Release 只发布普通 ELF 可执行文件，
 * 导出不了 `RunFrpc`，必须用它分发的那份 c-shared 构建，所以走联网下载。
 */
@Composable
private fun FrpcEngineSection(vm: McViewModel) {
    val engine by vm.frpcEngine.collectAsState()
    val message by vm.frpcEngineMessage.collectAsState()
    val download by vm.frpcDownload.collectAsState()

    // 进入页面刷新一次：引擎可能是在别处（重装应用后恢复）导入的。
    LaunchedEffect(Unit) { vm.refreshFrpcEngine() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::importFrpcEngine) }

    var confirmUninstall by remember { mutableStateOf(false) }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(stringResource(R.string.frpc_engine_uninstall)) },
            text = { Text(stringResource(R.string.frpc_engine_uninstall_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    vm.uninstallFrpcEngine()
                }) { Text(stringResource(R.string.env_uninstall), color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) {
                    Text(stringResource(R.string.env_cancel))
                }
            }
        )
    }

    McCard(title = stringResource(R.string.frpc_engine_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (engine.installed) Mint else Coral)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(
                    if (engine.installed) R.string.frpc_engine_installed
                    else R.string.frpc_engine_missing
                ),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            engine.version?.let { version ->
                Spacer(Modifier.size(6.dp))
                Text("v$version", color = Muted, fontSize = 11.sp)
            }
            engine.sizeBytes.takeIf { it > 0 }?.let { size ->
                Spacer(Modifier.size(6.dp))
                Text("${size / 1024 / 1024}MB", color = Muted, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.frpc_engine_hint), color = Muted, fontSize = 11.sp)

        engine.sha256?.let { sha ->
            Spacer(Modifier.height(4.dp))
            Text("SHA-256: ${sha.take(16)}…", color = Muted, fontSize = 10.sp)
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.frpcOnlineAvailable) {
                val downloading = download is McViewModel.DownloadState.Downloading
                Button(onClick = { vm.downloadFrpcEngine() }, enabled = !downloading, shape = McShape.ControlCompact) {
                    if (downloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            (download as? McViewModel.DownloadState.Downloading)
                                ?.fraction?.takeIf { it >= 0f }
                                ?.let { "${(it * 100).toInt()}%" }
                                ?: stringResource(R.string.frpc_engine_downloading),
                            fontSize = 12.sp
                        )
                    } else {
                        Text(
                            stringResource(
                                if (engine.installed) R.string.frpc_engine_reinstall
                                else R.string.frpc_engine_download
                            ),
                            fontSize = 12.sp
                        )
                    }
                }
            }
            // OpenDocument 而不是 GetContent：.so 没有注册的 MIME 类型，
            // GetContent 会因类型过滤而选不中文件，只能靠 */* 放开。
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, shape = McShape.ControlCompact) {
                Text(
                    stringResource(
                        if (engine.installed) R.string.frpc_engine_replace
                        else R.string.frpc_engine_import
                    ),
                    fontSize = 12.sp
                )
            }
        }

        if (engine.installed) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { confirmUninstall = true }) {
                Text(stringResource(R.string.frpc_engine_uninstall), fontSize = 12.sp, color = Coral)
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.frpc_engine_where), color = Muted, fontSize = 10.sp)

        // 在线下载失败时给一条能走通的落地路径：直接把人送到发行页，
        // 下回来的 .ecpkg 用上面的「导入引擎」即可（install 认这个容器）。
        val context = LocalContext.current
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(FrpcEngineConfig.RELEASE_PAGE))
                    )
                }
            },
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(
                stringResource(R.string.frpc_engine_open_release),
                color = Indigo,
                fontSize = 11.sp
            )
        }

        message?.let { text ->
            Spacer(Modifier.height(8.dp))
            HintNote(text)
            LaunchedEffect(text) {
                // 提示看过了就清掉，避免切页回来又弹一次。
                delay(4000)
                vm.clearFrpcEngineMessage()
            }
        }
    }
}

// ── PHP 运行环境（PocketMine-MP 专用）──────────────────────────

/**
 * PocketMine-MP 用 PHP 运行，不依赖 Java。
 *
 * PMMP 官方不发布 Linux ARM64 的 PHP 构建，这里统一管理社区 Android 原生构建
 * （aarch64 PM5，含 chunkutils2 / encoding / leveldb / pmmpthread 等必需扩展），
 * 下载到 home/php-pmmp/，多个 PocketMine 服务器共享同一份。
 */
@Composable
private fun PhpModule(vm: McViewModel, requestConfirm: (String, () -> Unit) -> Unit) {
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val isInstalling by vm.isInstalling.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val installedPhp by vm.installedPhp.collectAsState()
    val selectedPhpVersion by vm.selectedPhpVersion.collectAsState()
    val busy = isInstalling || serverState.isRunning
    val phpUninstallConfirm = stringResource(R.string.env_php_uninstall_confirm)

    LaunchedEffect(Unit) { vm.refreshPhp() }

    // ── 版本选择（PHP 分类内一行）：点开选择 PHP 版本 ──
    var showPhpVersionDialog by remember { mutableStateOf(false) }
    McCard(title = stringResource(R.string.env_php_version_title)) {
        Text(stringResource(R.string.env_php_version_desc), color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(McShape.Row)
                .border(1.dp, Indigo.copy(alpha = 0.5f), McShape.Row)
                .clickable { showPhpVersionDialog = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    selectedPhpVersion.displayName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Indigo
                )
                selectedPhpVersion.noteRes?.let { note ->
                    Text(stringResource(note), color = Muted, fontSize = 10.sp)
                }
            }
            Text(
                if (selectedPhpVersion in installedPhp) stringResource(R.string.env_php_installed)
                else stringResource(R.string.env_php_not_installed),
                color = if (selectedPhpVersion in installedPhp) Mint else Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = stringResource(R.string.env_php_version_title),
                tint = Indigo,
                modifier = Modifier.size(16.dp)
            )
        }
    }

    if (showPhpVersionDialog) {
        AlertDialog(
            onDismissRequest = { showPhpVersionDialog = false },
            title = { Text(stringResource(R.string.env_php_version_title), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(R.string.env_php_version_desc), color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    // 版本数据来自 PhpVersion 枚举，与启动控制卡片的运行环境列表共用同一份状态
                    PhpVersion.entries.forEach { version ->
                        val installed = version in installedPhp
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setPhpVersion(version)
                                    showPhpVersionDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    version.displayName,
                                    fontSize = 13.sp,
                                    fontWeight = if (version == selectedPhpVersion) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (version == selectedPhpVersion) Indigo else androidx.compose.ui.graphics.Color.Unspecified
                                )
                                version.noteRes?.let { note ->
                                    Text(stringResource(note), color = Muted, fontSize = 10.sp)
                                }
                            }
                            Text(
                                if (installed) stringResource(R.string.env_php_installed)
                                else stringResource(R.string.env_php_not_installed),
                                color = if (installed) Mint else Muted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPhpVersionDialog = false }) {
                    Text(stringResource(R.string.env_confirm_ok), color = Indigo)
                }
            }
        )
    }

    // ── PHP 运行环境本体：安装 / 卸载 / 重装 ──
    McCard(title = stringResource(R.string.env_php_title)) {
        Text(stringResource(R.string.env_php_hint), color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        if (serverState.isRunning) {
            Text(stringResource(R.string.env_php_running_block), color = Coral, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // 当前所选版本，与启动控制卡片的运行环境列表同源
                Text(
                    selectedPhpVersion.displayName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Indigo
                )
                selectedPhpVersion.noteRes?.let { note ->
                    Text(stringResource(note), color = Muted, fontSize = 10.sp)
                }
            }
            Text(
                if (selectedPhpVersion in installedPhp) stringResource(R.string.env_php_installed)
                else stringResource(R.string.env_php_not_installed),
                color = if (selectedPhpVersion in installedPhp) Mint else Muted,
                fontSize = 11.sp
            )
            if (isInstalling) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
            }
        }
        Spacer(Modifier.height(10.dp))
        ActionButtonsRow(
            uninstallEnabled = !busy && (selectedPhpVersion in installedPhp),
            reinstallEnabled = !busy && isBootstrapped && (selectedPhpVersion in installedPhp),
            installEnabled = !busy && isBootstrapped && (selectedPhpVersion !in installedPhp),
            onUninstall = {
                requestConfirm(phpUninstallConfirm) { vm.deletePhp(selectedPhpVersion) }
            },
            onReinstall = {
                // 实际行为与「卸载」相同（仅删除，下次启动自动重装），按钮文案如实描述该行为
                requestConfirm(phpUninstallConfirm) { vm.deletePhp(selectedPhpVersion) }
            },
            onInstall = { vm.installPhp(selectedPhpVersion) },
            reinstallLabelRes = R.string.env_php_delete_action
        )
    }
}

// ── Termux 环境管理 ───────────────────────────────────────────

@Composable
private fun TermuxModule(vm: McViewModel, requestConfirm: (String, () -> Unit) -> Unit) {
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val bootstrapError by vm.bootstrapError.collectAsState()
    val bootstrapSpeed by vm.bootstrapSpeed.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val termuxUninstallConfirmText = stringResource(R.string.env_termux_uninstall_confirm)
    val termuxReinstallConfirmText = stringResource(R.string.env_termux_reinstall_confirm)
    val progress = serverState.currentProgress
    val serverRunning = serverState.isRunning
    val busy = bootstrapError == null && !isBootstrapped && progress in 1..99

    McCard(title = stringResource(R.string.env_tab_termux)) {
        if (serverRunning) {
            Text(stringResource(R.string.env_running_block), color = Coral, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isBootstrapped) stringResource(R.string.env_termux_ready) else stringResource(R.string.env_termux_missing),
                color = if (isBootstrapped) Mint else Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
            }
        }
        if (!isBootstrapped && progress in 1..99) {
            Spacer(Modifier.height(6.dp))
            Text(
                "$progress%" + if (bootstrapSpeed > 0) " · ${formatSpeed(bootstrapSpeed)}" else "",
                color = Indigo, fontSize = 11.sp
            )
        }
        bootstrapError?.let {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.env_termux_error, it), color = Coral, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        ActionButtonsRow(
            uninstallEnabled = !serverRunning && !busy,
            reinstallEnabled = !serverRunning && !busy && isBootstrapped,
            installEnabled = !serverRunning && !busy && !isBootstrapped,
            onUninstall = {
                requestConfirm(termuxUninstallConfirmText) {
                    vm.forceDeleteBootstrap()
                }
            },
            onReinstall = {
                requestConfirm(termuxReinstallConfirmText) {
                    vm.deleteBootstrap()
                }
            },
            onInstall = { vm.retryBootstrap() }
        )
    }
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
    bytesPerSec >= 1024 -> "%.0f KB/s".format(bytesPerSec / 1024.0)
    else -> "$bytesPerSec B/s"
}

/**
 * 把字节数格式化成给用户看的容量文案。
 *
 * 容器是 300–600MB 量级，所以 MB 起步即可；小于 1MB 时不要显示成「0.0 MB」——
 * 那会让用户以为容器是空的，进而怀疑删除按钮是不是没作用。
 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
