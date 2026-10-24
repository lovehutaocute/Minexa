package com.mineserve.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R
import androidx.compose.ui.unit.dp
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

/** 子页面类型（从设置页进入的二级页面） */
enum class SubPage { Properties, Network, Backup, WorldManage, DownloadHelp, MtGuide, KeepAlive, OpLevelGuide, Diagnostics, Plugins, ServerIcon, CrashReports, WidgetSettings, Mcp, EnvManager, Ftp, FrpProfileEditor, EasyTierGuide, More, Automation }

/**
 * 应用根布局：底部 6 Tab；概览页可跳转日志页；设置页可跳转子页面
 */
@Composable
fun McApp() {
    val vm: McViewModel = viewModel(factory = McViewModel.Factory)
    val currentCrashContent by vm.currentCrashContent.collectAsState()
    val currentCrashAnalysis by vm.currentCrashAnalysis.collectAsState()
    val lastRunLog by vm.lastRunLog.collectAsState()
    var tab by rememberSaveable { mutableStateOf(McTab.Dashboard) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var subPage by rememberSaveable { mutableStateOf<SubPage?>(null) }
    // 依赖与环境管理页的初始模块（0=Java 1=依赖 2=Termux）
    var envManagerTab by rememberSaveable { mutableStateOf(0) }
    // FRP 配置编辑页要编辑哪一份配置（"" 表示新建）。
    // 单独存一个变量而不是做成带参的 SubPage：SubPage 是 enum，
    // 而编辑目标是个可变 id，两者拆开更清楚。
    var editFrpProfileId by rememberSaveable { mutableStateOf("") }

    // 把 Activity 的前台/后台状态显式推给 VM。
    // 各页面用 collectAsState（非生命周期感知）订阅日志流：Activity 停止后 Compose 组合不销毁、
    // subscriptionCount 恒 > 0，VM 仅凭订阅数无法判断界面是否可见，后台降频会失效。
    LifecycleResumeEffect(Unit) {
        vm.setUiVisible(true)
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
    // 更新通知点击 → 打开更新对话框
    LaunchedEffect(McApplication.get().openUpdateRequest.value) {
        if (McApplication.get().openUpdateRequest.value) {
            vm.showUpdateDialog()
            McApplication.get().consumeOpenUpdateRequest()
        }
    }

    // 系统返回键：逐级返回上一页面，禁止直接退出应用。
    // 优先级：日志页 → 子页面 → 其他 Tab → 概览（Dashboard）；已在概览时消费返回键不退出。
    BackHandler {
        when {
            showLogs -> showLogs = false
            // FRP 配置编辑页是从网络页点进来的：返回必须回到网络页，
            // 否则会把用户直接甩回上一个 Tab，丢失「刚才是从哪进来的」这个上下文。
            subPage == SubPage.FrpProfileEditor -> subPage = SubPage.Network
            subPage != null -> subPage = null
            tab != McTab.Dashboard -> {
                tab = McTab.Dashboard
                showLogs = false
                subPage = null
            }
        }
    }

    Scaffold(
        // 全屏展示：内容延伸到状态栏，顶部状态栏区域由各页面首个组件（HeaderBlock/返回栏）
        // 以白色背景覆盖，不再显示系统状态栏灰色条
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                McTab.values().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && subPage == null,
                        onClick = { tab = t; showLogs = false; subPage = null },
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
                    selected = subPage in setOf(SubPage.More, SubPage.Network, SubPage.FrpProfileEditor, SubPage.EasyTierGuide, SubPage.Backup, SubPage.Diagnostics, SubPage.KeepAlive, SubPage.DownloadHelp, SubPage.MtGuide, SubPage.OpLevelGuide, SubPage.WidgetSettings, SubPage.Mcp, SubPage.EnvManager, SubPage.Ftp, SubPage.Automation),
                    onClick = { subPage = SubPage.More; showLogs = false },
                    icon = { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.mcapp_more)) },
                    label = { Text(stringResource(R.string.mcapp_more), fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Indigo, selectedTextColor = Indigo, indicatorColor = IndigoSoft, unselectedIconColor = Muted, unselectedTextColor = Muted)
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            val page = Triple(showLogs, subPage, tab)
            when {
                page.first -> LogsScreen(vm = vm, onBack = { showLogs = false })
                page.second != null -> {
                    when (page.second) {
                        SubPage.Properties -> PropertiesScreen(vm = vm, onBack = { subPage = null })
                        SubPage.Network -> NetworkScreen(
                            vm = vm,
                            onBack = { subPage = null },
                            onOpenFrpEditor = { id ->
                                editFrpProfileId = id.orEmpty()
                                subPage = SubPage.FrpProfileEditor
                            },
                            onOpenEasyTierGuide = { subPage = SubPage.EasyTierGuide },
                            onOpenEasyTierEngine = {
                                envManagerTab = 1
                                subPage = SubPage.EnvManager
                            }
                        )
                        SubPage.EasyTierGuide -> EasyTierGuideScreen(
                            vm = vm,
                            onBack = { subPage = SubPage.Network }
                        )
                        SubPage.FrpProfileEditor -> FrpProfileEditorScreen(
                            vm = vm,
                            profileId = editFrpProfileId.ifBlank { null },
                            onBack = { subPage = SubPage.Network }
                        )
                        SubPage.Backup -> BackupScreen(vm = vm, onBack = { subPage = null })
                        SubPage.WorldManage -> WorldsScreen(vm = vm, onBack = { subPage = null })
                        SubPage.DownloadHelp -> DownloadHelpScreen(vm = vm, onBack = { subPage = null })
                        SubPage.MtGuide -> MtGuideScreen(onBack = { subPage = null })
                        SubPage.KeepAlive -> KeepAliveScreen(vm = vm, onBack = { subPage = null })
                        SubPage.OpLevelGuide -> OpLevelGuideScreen(
                            onBack = { subPage = null },
                            onNavigateProperties = { subPage = SubPage.Properties },
                            onNavigatePlugins = { subPage = SubPage.Plugins }
                        )
                        SubPage.Diagnostics -> DiagnosticsScreen(vm = vm, onBack = { subPage = null })
                        SubPage.Plugins -> PluginsScreen(vm = vm)
                        SubPage.ServerIcon -> ServerIconScreen(vm = vm, onBack = { subPage = null })
                        SubPage.CrashReports -> CrashReportsScreen(
                            vm = vm,
                            onBack = { subPage = null },
                            onGoToJavaInstall = {
                                envManagerTab = 0
                                subPage = SubPage.EnvManager
                            }
                        )
                        SubPage.WidgetSettings -> WidgetSettingsScreen(onBack = { subPage = null })
                        SubPage.EnvManager -> EnvManagerScreen(vm = vm, initialTab = envManagerTab, onBack = { subPage = null })
                        SubPage.Mcp -> McpScreen(vm = vm, onBack = { subPage = null })
                        SubPage.Ftp -> FtpScreen(vm = vm, onBack = { subPage = null })
                        SubPage.Automation -> AutomationScreen(vm = vm, onBack = { subPage = null })
                        SubPage.More -> MoreScreen(
                            onNetwork = { subPage = SubPage.Network },
                            onBackup = { subPage = SubPage.Backup },
                            onDiagnostics = { subPage = SubPage.Diagnostics },
                            onKeepAlive = { subPage = SubPage.KeepAlive },
                            onHelp = { subPage = SubPage.DownloadHelp },
                            onCrashReports = { subPage = SubPage.CrashReports },
                            onWidgetSettings = { subPage = SubPage.WidgetSettings },
                            onMcp = { subPage = SubPage.Mcp },
                            onFtp = { subPage = SubPage.Ftp },
                            onAutomation = { subPage = SubPage.Automation }
                        )
                        null -> {}
                    }
                }
                else -> when (page.third) {
                    McTab.Dashboard -> DashboardScreen(
                        vm = vm,
                        onShowDownloadHelp = { subPage = SubPage.DownloadHelp },
                        onShowDiagnostics = { subPage = SubPage.Diagnostics },
                        onEnvManager = { tab ->
                            envManagerTab = tab
                            subPage = SubPage.EnvManager
                        }
                    )
                    McTab.Download -> DownloadScreen(
                        vm = vm,
                        onShowDownloadHelp = { subPage = SubPage.DownloadHelp }
                    )
                    McTab.ServerManagement -> ServerManagementScreen(
                        vm = vm,
                        onPlugins = { subPage = SubPage.Plugins },
                        onProperties = { subPage = SubPage.Properties },
                        onIcon = { subPage = SubPage.ServerIcon },
                        onWorlds = { subPage = SubPage.WorldManage }
                    )
                    McTab.Players -> PlayersScreen(
                        vm = vm,
                        onNavigateProperties = { subPage = SubPage.Properties },
                        onNavigateOpGuide = { subPage = SubPage.OpLevelGuide }
                    )
                    McTab.Files -> FileManagerScreen(vm = vm, onOpenMtGuide = { subPage = SubPage.MtGuide })
                    McTab.Terminal -> TerminalScreen(vm = vm)
                    McTab.Settings -> SettingsScreen(
                        vm = vm,
                        onNavigate = { subPage = it }
                    )
                }
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
                    envManagerTab = 0
                    subPage = SubPage.EnvManager
                }
            )
        }
    }
}
