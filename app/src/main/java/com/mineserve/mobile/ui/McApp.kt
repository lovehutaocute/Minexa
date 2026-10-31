package com.mineserve.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.data.NetworkState
import com.mineserve.mobile.ui.screens.BackupScreen
import com.mineserve.mobile.ui.screens.DashboardScreen
import com.mineserve.mobile.ui.screens.DiagnosticsScreen
import com.mineserve.mobile.ui.screens.DownloadHelpScreen
import com.mineserve.mobile.ui.screens.DownloadScreen
import com.mineserve.mobile.ui.screens.FileManagerScreen
import com.mineserve.mobile.ui.screens.KeepAliveScreen
import com.mineserve.mobile.ui.screens.EasyTierGuideScreen
import com.mineserve.mobile.ui.screens.MtGuideScreen
import com.mineserve.mobile.ui.screens.OpLevelGuideScreen
import com.mineserve.mobile.ui.screens.LogsScreen
import com.mineserve.mobile.ui.screens.FrpProfileEditorScreen
import com.mineserve.mobile.ui.screens.NetworkScreen
import com.mineserve.mobile.ui.screens.PlayersScreen
import com.mineserve.mobile.ui.screens.PluginsScreen
import com.mineserve.mobile.ui.screens.UpdateDialog
import com.mineserve.mobile.ui.screens.PropertiesScreen
import com.mineserve.mobile.ui.screens.SettingsScreen
import com.mineserve.mobile.ui.screens.ServerManagementScreen
import com.mineserve.mobile.ui.screens.ServerIconScreen
import com.mineserve.mobile.ui.screens.TerminalScreen
import com.mineserve.mobile.ui.screens.EnvManagerScreen
import com.mineserve.mobile.ui.screens.FtpScreen
import com.mineserve.mobile.ui.screens.MoreScreen
import com.mineserve.mobile.ui.screens.AutomationScreen
import com.mineserve.mobile.ui.screens.McpScreen
import com.mineserve.mobile.ui.screens.WidgetSettingsScreen
import com.mineserve.mobile.ui.screens.CrashReportsScreen
import com.mineserve.mobile.ui.screens.CrashReportDialog
import com.mineserve.mobile.ui.screens.WorldsScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Muted

/**
 * 应用根布局：底部 Tab（一级页面）+ 二级页面返回栈。
 *
 * 导航模型（三者互斥且有先后）：
 * 1. 底部 Tab —— 一级页面，底部导航栏可见；
 * 2. [stack] —— 二级及更深的页面，底部导航栏隐藏、左上角悬浮返回键可见；
 * 3. 日志页 —— 概览页的下钻页面，同样按二级页处理。
 *
 * 所有「进入 / 返回」都必须走 [navigate] / [goBack]：栈只有一处真相，
 * 页面自己不再各自记住「我是从哪进来的」。
 */
@Composable
fun McApp() {
    val vm: McViewModel = viewModel(factory = McViewModel.Factory)
    val currentCrashContent by vm.currentCrashContent.collectAsState()
    val currentCrashAnalysis by vm.currentCrashAnalysis.collectAsState()
    val lastRunLog by vm.lastRunLog.collectAsState()

    // ── 导航状态 ────────────────────────────────────────────────
    var tab by rememberSaveable { mutableStateOf(McTab.Dashboard) }
    // 日志页没有做进 SubPage 枚举（它只能从概览页进、且不属于设置类子页），单独一个开关
    var showLogs by rememberSaveable { mutableStateOf(false) }
    // 二级页面返回栈：栈底是「当前 Tab」，每进一层压一条，返回就弹一条
    var stack by rememberSaveable(stateSaver = NavStackSaver) { mutableStateOf(emptyList<NavEntry>()) }

    val current = stack.lastOrNull()
    /**
     * 是否处于二级及更深的层级：底部导航栏的显隐、内容区底部留白、悬浮返回键都由它决定。
     *
     * 栈里的「一级页面」（目前只有「更多」）不算深：它和 Tab 页同级，底栏照常显示。
     * 从「更多」再往下钻（如 更多 → 网络）才算二级，那时栈顶是普通子页。
     */
    val deepLevel = showLogs || (current != null && !current.isRootLevel)

    /**
     * 进入下一级页面。
     *
     * 同页同参连点不重复压栈（避免返回要点两次才有效）；参数不同则视为不同页面，
     * 例如从崩溃报告进「Java 安装」与从概览进「依赖管理」，各自保留自己的选中模块。
     */
    fun navigate(page: SubPage, arg: String = "") {
        val top = stack.lastOrNull()
        if (top != null && top.page == page && top.arg == arg) return
        stack = stack + NavEntry(page, arg)
        showLogs = false
    }

    /**
     * 返回上一级：先收二级页，再回到概览 Tab。
     *
     * 栈为空且已在概览时什么都不做 —— 返回键不会退出应用（沿用原有约定，
     * 避免在概览页误触返回直接关掉正在跑服务器的界面）。
     */
    fun goBack() {
        when {
            showLogs -> showLogs = false
            stack.isNotEmpty() -> stack = stack.dropLast(1)
            tab != McTab.Dashboard -> tab = McTab.Dashboard
        }
    }

    /** 切 Tab 等于「回到一级页面」：栈必须清空，否则返回键会把人送回刚才那个 Tab 的子页。 */
    fun selectTab(target: McTab) {
        tab = target
        stack = emptyList()
        showLogs = false
    }

    // 悬浮返回键不复刻一遍返回逻辑，而是直接派发系统返回事件：
    // 这样各页面自己注册的 BackHandler（如属性页「未保存改动」拦截）对按钮同样生效，
    // 按钮和物理返回键才是真正的同一条通路。
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val invokeBack: () -> Unit = {
        // onBackPressed() 在部分 activity 版本返回 Boolean，这里只当语句用，不接返回值
        backDispatcher?.onBackPressed()
        if (backDispatcher == null) goBack()
    }

    // 把 Activity 的前台/后台状态显式推给 VM。
    // 各页面用 collectAsState（非生命周期感知）订阅日志流：Activity 停止后 Compose 组合不销毁、
    // subscriptionCount 恒 > 0，VM 仅凭订阅数无法判断界面是否可见，后台降频会失效。
    LifecycleResumeEffect(Unit) {
        vm.setUiVisible(true)
        // 回到前台先与磁盘对齐：核心目录可能已被文件管理器删掉，
        // 列表里那条登记此刻是幽灵条目（点进去空、启动报找不到 jar）。
        vm.syncInstalledCoresWithDisk()
        onPauseOrDispose { vm.setUiVisible(false) }
    }

    // 启动自动检查更新（后台执行，发现新版发通知）
    LaunchedEffect(Unit) {
        // Let the first screen settle before starting a best-effort network request.
        delay(1_000)
        // 无网络时跳过：不做注定失败的 12 秒连接尝试（手动检查不受此约束）。
        if (NetworkState.isOnline(McApplication.get())) {
            vm.checkForUpdate(manual = false)
        }
    }
    // 文件管理权限：每次冷启动主动提示一次（用户拒绝后下次启动仍会提示），
    // 但同一次运行内不再重复弹 —— 反复弹会把用户挡在「设置页 ↔ 应用」的循环里。
    // 判定与去重状态都在 StorageAccess 里，这里只管把结果呈现出来。
    val appContext = androidx.compose.ui.platform.LocalContext.current
    var showStoragePermissionDialog by remember { mutableStateOf(false) }
    val requestStoragePermission = com.mineserve.mobile.ui.components.rememberStoragePermissionRequest { granted ->
        if (granted) vm.onStoragePermissionGranted()
    }
    LaunchedEffect(Unit) {
        // 判定里含一次真实落盘探测（目录 + 探针文件），放 IO 线程避免首帧主线程写盘
        val needPrompt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.mineserve.mobile.data.StorageAccess.consumeAutoPrompt(appContext)
        }
        if (needPrompt) {
            showStoragePermissionDialog = true
        }
    }
    if (showStoragePermissionDialog) {
        com.mineserve.mobile.ui.components.StoragePermissionDialog(
            onDismiss = { showStoragePermissionDialog = false },
            onConfirm = {
                showStoragePermissionDialog = false
                requestStoragePermission()
            }
        )
    }

    // 旧版私有目录迁移：每次冷启动都扫一次（用户要求「二次启动后依旧提示」）。
    // 只读扫描，不搬任何文件；搬不搬由下面这个对话框决定。
    val legacyMigration by vm.legacyMigration.collectAsState()
    val storageReadyForMigration by vm.storageGranted.collectAsState()
    LaunchedEffect(Unit) {
        vm.checkLegacyMigration()
    }
    // 没有文件权限时不弹迁移框：迁移必然写不进统一目录，而且会和授权对话框叠在一起。
    com.mineserve.mobile.ui.components.LegacyMigrationDialog(
        candidates = if (storageReadyForMigration) legacyMigration.candidates else emptyList(),
        running = legacyMigration.running,
        progressDone = legacyMigration.progressDone,
        progressTotal = legacyMigration.progressTotal,
        progressName = legacyMigration.progressName,
        onMigrate = { vm.runLegacyMigration() },
        onLater = { vm.dismissLegacyMigration() }
    )
    // 更新通知点击 → 打开更新对话框
    LaunchedEffect(McApplication.get().openUpdateRequest.value) {
        if (McApplication.get().openUpdateRequest.value) {
            vm.showUpdateDialog()
            McApplication.get().consumeOpenUpdateRequest()
        }
    }

    // 系统返回键 / 侧滑返回：与悬浮返回键共用 [goBack] 的层级规则。
    // 逐级返回，只有在一级页面（概览）时这才交给系统退出应用。
    BackHandler { goBack() }

    // 页面状态容器：每个页面一个 key，离开组合时 Compose 会把它的
    // rememberSaveable（滚动位置、列表位置、选中项）存起来，回来时原样恢复。
    val pageStates = rememberSaveableStateHolder()

    Scaffold(
        // 全屏展示：内容延伸到状态栏，顶部状态栏区域由各页面首个组件（HeaderBlock/返回栏）
        // 以白色背景覆盖，不再显示系统状态栏灰色条
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            // 二级及更深页面不显示底部导航栏：Scaffold 因此不再下发底部 padding，
            // 内容区自然铺满到屏幕底部（原先预留的那一条高度直接还给内容）。
            if (!deepLevel) {
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    McTab.values().forEach { t ->
                        NavigationBarItem(
                            // 停在「更多」时不能让下面的 Tab 也高亮：两者同为一级，只能亮一个
                            selected = tab == t && current == null,
                            onClick = { selectTab(t) },
                            icon = { Icon(t.icon, contentDescription = stringResource(t.labelRes)) },
                            label = { Text(stringResource(t.labelRes), fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Indigo,
                                selectedTextColor = Indigo,
                                indicatorColor = IndigoSoft,
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted
                            )
                        )
                    }
                    NavigationBarItem(
                        selected = current?.page == SubPage.More,
                        onClick = { navigate(SubPage.More) },
                        icon = { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.mcapp_more)) },
                        label = { Text(stringResource(R.string.mcapp_more), fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Indigo, selectedTextColor = Indigo, indicatorColor = IndigoSoft, unselectedIconColor = Muted, unselectedTextColor = Muted)
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                // 底部导航栏已经让位，但系统导航条（手势横条）仍在，这里只避让它，
                // 不再额外留出任何「给底栏预留」的空白。
                .then(if (deepLevel) Modifier.navigationBarsPadding() else Modifier)
        ) {
            val pageKey = when {
                showLogs -> "page:logs"
                current != null -> "page:${current.key}"
                else -> "page:tab:${tab.name}"
            }
            pageStates.SaveableStateProvider(pageKey) {
                when {
                    showLogs -> LogsScreen(vm = vm, onBack = { goBack() })
                    current != null -> SubPageContent(
                        entry = current,
                        vm = vm,
                        onNavigate = { page, arg -> navigate(page, arg) },
                        onBack = { goBack() }
                    )
                    else -> when (tab) {
                        McTab.Dashboard -> DashboardScreen(
                            vm = vm,
                            onShowDownloadHelp = { navigate(SubPage.DownloadHelp) },
                            onShowDiagnostics = { navigate(SubPage.Diagnostics) },
                            onEnvManager = { module -> navigate(SubPage.EnvManager, module.toString()) }
                        )
                        McTab.Download -> DownloadScreen(
                            vm = vm,
                            onShowDownloadHelp = { navigate(SubPage.DownloadHelp) }
                        )
                        McTab.ServerManagement -> ServerManagementScreen(
                            vm = vm,
                            onPlugins = { navigate(SubPage.Plugins) },
                            onProperties = { navigate(SubPage.Properties) },
                            onIcon = { navigate(SubPage.ServerIcon) },
                            onWorlds = { navigate(SubPage.WorldManage) }
                        )
                        McTab.Players -> PlayersScreen(
                            vm = vm,
                            onNavigateProperties = { navigate(SubPage.Properties) },
                            onNavigateOpGuide = { navigate(SubPage.OpLevelGuide) }
                        )
                        McTab.Files -> FileManagerScreen(vm = vm, onOpenMtGuide = { navigate(SubPage.MtGuide) })
                        McTab.Terminal -> TerminalScreen(vm = vm)
                        McTab.Settings -> SettingsScreen(
                            vm = vm,
                            onNavigate = { navigate(it) }
                        )
                    }
                }
            }

            // 全局悬浮返回键：只在二级及更深层级出现，固定在左上角、压在所有滚动内容之上。
            // 位置与 BackBar / HeaderBlock 预留的占位完全重合（见 FloatingBackButton）。
            if (deepLevel) {
                FloatingBackButton(
                    onClick = invokeBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .zIndex(10f)
                )
            }
        }

        // 软件更新对话框（全局，覆盖所有页面）
        UpdateDialog(vm = vm)
        val crashContent = currentCrashContent
        val crashAnalysis = currentCrashAnalysis
        if (crashContent != null && crashAnalysis != null) {
            CrashReportDialog(
                crashContent,
                crashAnalysis,
                lastRunLog,
                onDismiss = { vm.clearCrashContent() },
                onGoToJavaInstall = {
                    vm.clearCrashContent()
                    navigate(SubPage.EnvManager, "0")
                }
            )
        }
    }
}

/**
 * 二级页面的分发：一个页面一个分支，全部从 [entry] 取参数、返回一律走 [onBack]。
 *
 * 和改造前的区别只有两点：跳转改成 [onNavigate]（压栈，不再覆盖当前页），
 * 返回改成出栈（原来 FrpProfileEditor/EasyTierGuide 是硬编码「返回网络页」，
 * 从别处进来会被甩到错误的一级页面）。
 */
@Composable
private fun SubPageContent(
    entry: NavEntry,
    vm: McViewModel,
    onNavigate: (SubPage, String) -> Unit,
    onBack: () -> Unit
) {
    when (entry.page) {
        SubPage.Properties -> PropertiesScreen(vm = vm, onBack = onBack)
        SubPage.Network -> NetworkScreen(
            vm = vm,
            onBack = onBack,
            onOpenFrpEditor = { id -> onNavigate(SubPage.FrpProfileEditor, id.orEmpty()) },
            onOpenEasyTierGuide = { onNavigate(SubPage.EasyTierGuide, "") },
            onOpenEasyTierEngine = { onNavigate(SubPage.EnvManager, "1") }
        )
        SubPage.EasyTierGuide -> EasyTierGuideScreen(vm = vm, onBack = onBack)
        SubPage.FrpProfileEditor -> FrpProfileEditorScreen(
            vm = vm,
            profileId = entry.arg.ifBlank { null },
            onBack = onBack
        )
        SubPage.Backup -> BackupScreen(vm = vm, onBack = onBack)
        SubPage.WorldManage -> WorldsScreen(vm = vm, onBack = onBack)
        SubPage.DownloadHelp -> DownloadHelpScreen(vm = vm, onBack = onBack)
        SubPage.MtGuide -> MtGuideScreen(onBack = onBack)
        SubPage.KeepAlive -> KeepAliveScreen(vm = vm, onBack = onBack)
        SubPage.OpLevelGuide -> OpLevelGuideScreen(
            onBack = onBack,
            onNavigateProperties = { onNavigate(SubPage.Properties, "") },
            onNavigatePlugins = { onNavigate(SubPage.Plugins, "") }
        )
        SubPage.Diagnostics -> DiagnosticsScreen(vm = vm, onBack = onBack)
        SubPage.Plugins -> PluginsScreen(vm = vm)
        SubPage.ServerIcon -> ServerIconScreen(vm = vm, onBack = onBack)
        SubPage.CrashReports -> CrashReportsScreen(
            vm = vm,
            onBack = onBack,
            onGoToJavaInstall = { onNavigate(SubPage.EnvManager, "0") }
        )
        SubPage.WidgetSettings -> WidgetSettingsScreen(onBack = onBack)
        SubPage.EnvManager -> EnvManagerScreen(
            vm = vm,
            initialTab = entry.arg.toIntOrNull() ?: 0,
            onBack = onBack
        )
        SubPage.Mcp -> McpScreen(vm = vm, onBack = onBack)
        SubPage.Ftp -> FtpScreen(vm = vm, onBack = onBack)
        SubPage.Automation -> AutomationScreen(vm = vm, onBack = onBack)
        SubPage.More -> MoreScreen(
            onNetwork = { onNavigate(SubPage.Network, "") },
            onBackup = { onNavigate(SubPage.Backup, "") },
            onDiagnostics = { onNavigate(SubPage.Diagnostics, "") },
            onKeepAlive = { onNavigate(SubPage.KeepAlive, "") },
            onHelp = { onNavigate(SubPage.DownloadHelp, "") },
            onCrashReports = { onNavigate(SubPage.CrashReports, "") },
            onWidgetSettings = { onNavigate(SubPage.WidgetSettings, "") },
            onMcp = { onNavigate(SubPage.Mcp, "") },
            onFtp = { onNavigate(SubPage.Ftp, "") },
            onAutomation = { onNavigate(SubPage.Automation, "") }
        )
    }
}
