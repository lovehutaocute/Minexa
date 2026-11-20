package com.mineserve.mobile.ui.screens

import android.content.res.Configuration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mineserve.mobile.R
import com.mineserve.mobile.data.ServerCore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.server.PlayerManager
import com.mineserve.mobile.ui.ConfirmDialog
import com.mineserve.mobile.ui.EmptyHint
import com.mineserve.mobile.ui.PageTopBar
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.pageSurface
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.SegmentedTabs
import com.mineserve.mobile.ui.preview.PreviewPane
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.CoralSoft
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.MintSoft
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.mineserve.mobile.data.skin.SkinTileSlots
import com.mineserve.mobile.ui.components.skinTileOverlay
import com.mineserve.mobile.ui.components.skinButtonColors
import com.mineserve.mobile.ui.components.AppDialog

private enum class PlayerListTab(val labelRes: Int) { Online(R.string.s657), Ops(R.string.s658), Whitelist(R.string.s659), Banned(R.string.s660) }

private enum class BanDuration(val labelRes: Int, val value: String) {
    FOREVER(R.string.s661, ""),
    MIN30(R.string.s662, "30m"),
    HOUR1(R.string.s663, "1h"),
    HOURS6(R.string.s664, "6h"),
    DAY1(R.string.s665, "1d"),
    DAYS7(R.string.s666, "7d"),
    DAYS30(R.string.s667, "30d")
}

private enum class OpLevel(val labelRes: Int, val value: Int) {
    L1(R.string.s668, 1),
    L2(R.string.s669, 2),
    L3(R.string.s670, 3),
    L4(R.string.s671, 4)
}

private fun matchesPlayerQuery(name: String, query: String): Boolean {
    val normalizedName = name.filterNot(Char::isWhitespace).lowercase()
    val normalizedQuery = query.filterNot(Char::isWhitespace).lowercase()
    if (normalizedQuery.isBlank()) return true
    if (normalizedName.contains(normalizedQuery)) return true
    var cursor = 0
    normalizedName.forEach { char ->
        if (cursor < normalizedQuery.length && char == normalizedQuery[cursor]) cursor++
    }
    return cursor == normalizedQuery.length
}

/**
 * 玩家管理页（重构版）
 *
 * 优化点：
 *  - 命令发送前校验服务器运行状态，未运行给明确错误反馈
 *  - 命令后延迟 600ms 再刷新列表，避免读到旧 JSON
 *  - 新增限时封禁（tempban）、白名单开关、OP 等级选择
 *  - 每个列表加搜索框
 *  - 点击列表项弹出玩家详情对话框
 *  - 移除冗余的快捷操作卡片（操作整合到列表行）
 *  - 服务器未运行时显示醒目状态横幅
 */
@Composable
fun PlayersScreen(
    vm: McViewModel,
    onNavigateProperties: () -> Unit = {},
    onNavigateOpGuide: () -> Unit = {}
) {
    val serverState by vm.serverState.collectAsState()
    val ops by vm.ops.collectAsState()
    val whitelist by vm.whitelist.collectAsState()
    val banned by vm.bannedPlayers.collectAsState()
    val whitelistEnabled by vm.whitelistEnabled.collectAsState()
    val defaultOpLevel by vm.defaultOpLevel.collectAsState()
    val onlinePlayers by vm.onlinePlayerNames.collectAsState()
    val playerHistory by vm.playerHistory.collectAsState()
    val config by vm.config.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val activitySummaries = remember(playerHistory) { vm.playerActivitySummaries() }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var activeTab by remember { mutableStateOf(PlayerListTab.Online) }
    var detailPlayer by remember { mutableStateOf<DetailInfo?>(null) }
    // 等待二次确认的封禁目标玩家名：null = 没有待确认的封禁
    var pendingBanPlayer by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val historyExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(vm::exportPlayerHistory) }

    // 进入页面/切换核心/服务器启动状态变化时刷新 OP/白名单/封禁列表
    // （服务器启动完成后 ops.json 等才会生成，故监听 isRunning）
    LaunchedEffect(isBootstrapped, config.activeCoreName, serverState.isRunning) {
        if (isBootstrapped && config.activeCoreName != null) {
            vm.refreshPlayers()
        }
    }
    // 进入在线玩家 tab 时发送 list 命令，全量校正在线名单
    LaunchedEffect(activeTab) {
        if (activeTab == PlayerListTab.Online) vm.refreshOnlinePlayers()
    }
    LaunchedEffect(Unit) { vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) } }

    val isRunning = serverState.isRunning
    val activeCore = config.installedCores.find { it.name == config.activeCoreName }
    // 官方 BDS 既没有封禁命令也没有封禁文件，封禁页对它永远只能是空的 —— 直接不展示
    val isBds = activeCore?.core == ServerCore.Bds
    val playerTabs = remember(isBds) {
        PlayerListTab.values().filterNot { isBds && it == PlayerListTab.Banned }
    }
    LaunchedEffect(playerTabs) {
        if (activeTab !in playerTabs) activeTab = PlayerListTab.Online
    }

    Scaffold(

        containerColor = pageSurface(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        // 页面不再整体滚动（2026-11 列表懒加载改造）：
        // 状态横幅与分段 Tab 固定在顶部，Tab 内容区拿到剩余高度（weight）——
        // 在线玩家列表在卡内虚拟化滚动，百人在线时只组合可见行，滚动帧率与 10 人在线一致；
        // 附带收益：分段 Tab 永远可达，不会被滚走。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 右上角「进服/离服记录」入口。
            // 必须交给 PageTopBar 承载：它把 statusBarsPadding 加在**容器**上，按钮因此排在状态栏下沿。
            // 之前写成 Row + HeaderBlock + IconButton，HeaderBlock 无 trailing 时只剩状态栏那么高，
            // 40dp 的按钮在这一行里垂直居中就往上下各溢出十几个 dp，上半截被状态栏吃掉、点不动。
            PageTopBar(
                trailing = {
                    IconButton(
                        onClick = { showHistory = true },
                        modifier = Modifier.size(McIcon.Touch)
                    ) {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = stringResource(R.string.s673),
                            tint = Indigo,
                            modifier = Modifier.size(McIcon.Item)
                        )
                    }
                }
            )

            // ── 服务器状态横幅 ──
            ServerStatusBanner(
                isRunning = isRunning,
                isBootstrapped = isBootstrapped,
                hasActiveCore = activeCore != null,
                onlineCount = serverState.onlinePlayers,
                maxPlayers = serverState.maxPlayers,
                onRefresh = { vm.refreshPlayers() }
            )

            // ── 标签切换 ──
            SegmentedTabs(
                options = playerTabs.map { tab ->
                    val count = when (tab) {
                        PlayerListTab.Online -> onlinePlayers.size
                        PlayerListTab.Ops -> ops.size
                        PlayerListTab.Whitelist -> whitelist.size
                        PlayerListTab.Banned -> banned.size
                    }
                    val tabLabel = stringResource(tab.labelRes)
                    if (count > 0) "$tabLabel $count" else tabLabel
                },
                selectedIndex = playerTabs.indexOf(activeTab),
                modifier = Modifier.padding(horizontal = McSpacing.Page, vertical = 4.dp),
                onSelect = { index -> playerTabs.getOrNull(index)?.let { activeTab = it } }
            )

            // Tab 内容直接切换（2026-11-18 按用户要求移除交叉淡入）。
            // weight(1f)：Tab 内容区吃掉剩余高度——在线列表在其中懒加载（卡片填满、
            // 行虚拟化滚动），其余三个表单型 Tab 交给 [ScrollableTabBody] 自行滚动。
            Box(Modifier.weight(1f)) {
                when (activeTab) {
                PlayerListTab.Online -> OnlineTab(
                    players = onlinePlayers,
                    isRunning = isRunning,
                    onRefresh = { vm.refreshOnlinePlayers() },
                    onShowDetail = { detailPlayer = DetailInfo.Activity(it) },
                    onCopy = { name ->
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MC Player", name))
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.s188, name)) }
                    },
                    onKick = { name -> vm.kickPlayer(name) },
                    onSetGameMode = { name, mode -> vm.setGameMode(name, mode) }
                )
                PlayerListTab.Ops -> ScrollableTabBody {
                    OpsTab(
                        ops = ops,
                        isRunning = isRunning,
                        defaultOpLevel = defaultOpLevel,
                        supportsOpLevel = !isBds,
                        onOpAddDefault = { vm.opPlayer(it) },
                        onOpAdd = { name, level -> vm.opPlayerWithLevel(name, level) },
                        onNavigateProperties = onNavigateProperties,
                        onNavigateOpGuide = onNavigateOpGuide,
                        onShowDetail = { detailPlayer = DetailInfo.Op(it) }
                    )
                }
                PlayerListTab.Whitelist -> ScrollableTabBody {
                    WhitelistTab(
                        whitelist = whitelist,
                        isRunning = isRunning,
                        whitelistEnabled = whitelistEnabled,
                        onToggle = { vm.toggleWhitelist(it) },
                        onWhitelistAdd = { vm.whitelistAdd(it) },
                        onShowDetail = { detailPlayer = DetailInfo.Whitelist(it) }
                    )
                }
                PlayerListTab.Banned -> ScrollableTabBody {
                    BannedTab(
                        banned = banned,
                        isRunning = isRunning,
                        onBan = { name, duration, reason ->
                            if (duration.isBlank()) vm.banPlayer(name, reason)
                            else vm.tempBanPlayer(name, duration, reason)
                        },
                        onShowDetail = { detailPlayer = DetailInfo.Banned(it) }
                    )
                }
                }
            }
        }
    }

    // 详情对话框
    detailPlayer?.let { info ->
        PlayerDetailDialog(
            info = info,
            activity = activitySummaries.firstOrNull { it.player.equals(info.playerName(), ignoreCase = true) },
            isRunning = isRunning,
            onDismiss = { detailPlayer = null },
            onDeop = { vm.deopPlayer(it); detailPlayer = null },
            onWhitelistRemove = { vm.whitelistRemove(it); detailPlayer = null },
            onPardon = { vm.pardonPlayer(it); detailPlayer = null },
            onKick = { vm.kickPlayer(it, context.getString(R.string.s675)); detailPlayer = null },
            // 封禁是三类操作里唯一影响持久的：踢出会立刻回来、封禁要管理员手动 pardon。
            // 手滑点中「封禁」后玩家会莫名其妙进不了服，所以这里拦一道二次确认。
            onBan = { pendingBanPlayer = it },
            onSetGameMode = { name, mode -> vm.setGameMode(name, mode) },
            onGiveXp = { name, amount -> vm.giveXp(name, amount) }
        )
    }

    // 封禁二次确认：详情弹窗点「封禁」后先弹这里，确认才真正落指令。
    pendingBanPlayer?.let { name ->
        ConfirmDialog(
            title = stringResource(R.string.s728),
            message = stringResource(R.string.player_ban_confirm_content, name),
            onDismiss = { pendingBanPlayer = null },
            onConfirm = {
                vm.banPlayer(name, context.getString(R.string.s676))
                pendingBanPlayer = null
                detailPlayer = null
            }
        )
    }

    // 玩家进服/离服历史记录对话框
    if (showHistory) {
        PlayerHistoryDialog(
            history = playerHistory,
            summaries = activitySummaries,
            onDismiss = { showHistory = false },
            onExport = { historyExportLauncher.launch("player_activity.csv") },
            onClear = vm::clearPlayerHistory,
            onShowPlayer = { detailPlayer = DetailInfo.Activity(it) }
        )
    }
}

// ── 玩家历史记录对话框 ──────────────────────────────────────────────

@Composable
private fun PlayerHistoryDialog(
    history: List<McViewModel.PlayerHistoryEntry>,
    summaries: List<McViewModel.PlayerActivitySummary>,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onShowPlayer: (String) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var clearConfirm by remember { mutableStateOf(false) }
    val filtered = remember(history, search) { history.filter { matchesPlayerQuery(it.player, search) } }
    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = Indigo, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.s677), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.s678), color = Muted, fontSize = 12.sp)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(stringResource(R.string.player_history_summary, summaries.size, formatActivitySeconds(summaries.sumOf { it.totalSeconds })), color = Muted, fontSize = 11.sp)
                    OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row), singleLine = true, label = { Text(stringResource(R.string.player_search)) })
                    filtered.forEach { h ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onShowPlayer(h.player) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isJoin = h.event == stringResource(R.string.s189)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isJoin) MintSoft else CoralSoft)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    h.event,
                                    color = if (isJoin) Mint else Coral,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.size(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    h.player,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(buildString { append(h.time); if (h.sessionStart != null && h.sessionEnd != null) append("  |  ${formatActivitySeconds(((h.sessionEnd - h.sessionStart) / 1000).coerceAtLeast(0))}"); if (h.interrupted) append(stringResource(R.string.plx_interrupted_suffix)) }, color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onExport) { Text(stringResource(R.string.player_export_csv)) }
                TextButton(onClick = { clearConfirm = true }) { Text(stringResource(R.string.player_clear), color = Coral) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.s620)) }
            }
        }
    )
    if (clearConfirm) AppDialog(onDismissRequest = { clearConfirm = false }, title = { Text(stringResource(R.string.player_clear_title)) }, text = { Text(stringResource(R.string.player_clear_hint)) }, confirmButton = { TextButton(onClick = { onClear(); clearConfirm = false }) { Text(stringResource(R.string.player_clear), color = Coral) } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text(stringResource(R.string.props_cancel)) } })
}

@Composable
private fun formatActivitySeconds(seconds: Long): String = when {
    seconds >= 3600 -> stringResource(R.string.fmt_hours_min, seconds / 3600, (seconds % 3600) / 60)
    seconds >= 60 -> stringResource(R.string.fmt_minutes, seconds / 60)
    else -> stringResource(R.string.fmt_seconds, seconds)
}

// ── 服务器状态横幅 ──────────────────────────────────────────────────

@Composable
private fun ServerStatusBanner(
    isRunning: Boolean,
    isBootstrapped: Boolean,
    hasActiveCore: Boolean,
    onlineCount: Int,
    maxPlayers: Int,
    onRefresh: () -> Unit
) {
    McCard(
        title = stringResource(R.string.s679),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 刷新入口：原先是一个 14dp 的图标直接 clickable，命中区远低于
                // 40dp 标准，用户实际很难点中。改成标准图标按钮（36dp 命中区）。
                IconButton(onClick = onRefresh, modifier = Modifier.size(McIcon.TouchCompact)) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.s333),
                        tint = Indigo,
                        modifier = Modifier.size(McIcon.Action)
                    )
                }
            }
        }
    ) {
        if (!isBootstrapped) {
            StatusRow(color = Coral, text = stringResource(R.string.s680))
        } else if (!hasActiveCore) {
            StatusRow(color = Coral, text = stringResource(R.string.s681))
        } else if (!isRunning) {
            StatusRow(color = Coral, text = stringResource(R.string.s682))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Mint)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(R.string.s683, onlineCount, maxPlayers),
                    color = Mint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun StatusRow(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(8.dp)).background(color))
        Spacer(Modifier.size(6.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── 在线玩家标签 ────────────────────────────────────────────────────

/**
 * 表单型 Tab 的滚动容器：OP / 白名单 / 封禁三个 Tab 内容含输入表单与中等长度列表，
 * 保持原有的整页滚动体验（滚动权从页面级移到这里）。
 */
@Composable
private fun ScrollableTabBody(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun OnlineTab(
    players: List<String>,
    isRunning: Boolean,
    onRefresh: () -> Unit,
    onShowDetail: (String) -> Unit,
    onCopy: (String) -> Unit,
    onKick: (String) -> Unit,
    onSetGameMode: (String, Int) -> Unit
) {
    var search by remember { mutableStateOf("") }
    // distinct()：LazyColumn 的 key 用玩家名，上游数据万一重复出现同名，
    // 重复 key 会在布局期抛异常——这里兜底去重（正常服务器名单本身就是唯一的）。
    val filteredPlayers = remember(players, search) {
        players.filter { matchesPlayerQuery(it, search) }.distinct()
    }
    // 列表面板化：有列表可滚时卡片填满剩余高度，玩家行在卡内虚拟化滚动——
    // 大服百人在线只组合可见行，滚动帧率与 10 人在线时一致；
    // 没有列表（未运行 / 搜索无结果）时卡片保持原有「贴内容」的紧凑高度。
    val lazy = isRunning && filteredPlayers.isNotEmpty()
    McCard(
        title = stringResource(R.string.s657),
        modifier = if (lazy) Modifier.fillMaxSize() else Modifier
    ) {
        if (isRunning) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                label = { Text(stringResource(R.string.player_search), fontSize = 11.sp) }
            )
        }
        if (!isRunning) {
            StatusRow(color = Coral, text = stringResource(R.string.s684))
        } else if (filteredPlayers.isEmpty()) {
            EmptyHint(icon = Icons.Outlined.Person, text = stringResource(R.string.s685))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredPlayers, key = { it }) { name ->
                    // animateItem：玩家进服/离服时新行滑入、旧行平滑退出，
                    // 不再是整列表硬跳；key 已按玩家名稳定。
                    Box(Modifier.animateItem()) {
                        OnlinePlayerRow(
                            name = name,
                            onShowDetail = { onShowDetail(name) },
                            onCopy = { onCopy(name) },
                            onKick = { onKick(name) },
                            onSetGameMode = { mode -> onSetGameMode(name, mode) }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = Indigo)
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.s686), color = Indigo, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun OnlinePlayerRow(
    name: String,
    onShowDetail: () -> Unit,
    onCopy: () -> Unit,
    onKick: () -> Unit,
    onSetGameMode: (Int) -> Unit
) {
    var modeMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(IndigoSoft)
            .clickable(onClick = onShowDetail)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Mint),
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(2).uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.s318), color = Mint, fontSize = 10.sp)
        }
        // 复制玩家名
        IconButton(onClick = onCopy, modifier = Modifier.size(McIcon.TouchCompact)) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.s687), tint = Indigo, modifier = Modifier.size(16.dp))
        }
        // 踢出玩家
        IconButton(onClick = onKick, modifier = Modifier.size(McIcon.TouchCompact)) {
            Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = stringResource(R.string.s688), tint = Coral, modifier = Modifier.size(16.dp))
        }
        // 切换游戏模式
        Box {
            IconButton(onClick = { modeMenuOpen = true }, modifier = Modifier.size(McIcon.TouchCompact)) {
                Icon(Icons.Outlined.VideogameAsset, contentDescription = stringResource(R.string.s689), tint = Indigo, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = modeMenuOpen, onDismissRequest = { modeMenuOpen = false }) {
                listOf(R.string.s281 to 0, R.string.s282 to 1, R.string.s283 to 2, R.string.s284 to 3).forEach { (labelRes, mode) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes), fontSize = 12.sp) },
                        onClick = { modeMenuOpen = false; onSetGameMode(mode) }
                    )
                }
            }
        }
    }
}

// ── OP 列表标签 ──────────────────────────────────────────────────────

@Composable
private fun OpsTab(
    ops: List<PlayerManager.OpEntry>,
    isRunning: Boolean,
    defaultOpLevel: Int?,
    supportsOpLevel: Boolean = true,
    onOpAddDefault: (String) -> Unit,
    onOpAdd: (String, Int) -> Unit,
    onNavigateProperties: () -> Unit,
    onNavigateOpGuide: () -> Unit,
    onShowDetail: (PlayerManager.OpEntry) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var opName by remember { mutableStateOf("") }
    // 默认勾选"使用配置文件默认等级"——无需重启，实时生效
    var useDefaultLevel by remember { mutableStateOf(true) }
    var selectedLevel by remember { mutableStateOf(OpLevel.L4) }
    var levelMenuOpen by remember { mutableStateOf(false) }

    McCard(title = stringResource(R.string.s690)) {
        Text(
            stringResource(R.string.s691),
            color = Muted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(10.dp))

        // 搜索框
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text(stringResource(R.string.s692), fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(14.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.ControlCompact),
            shape = McShape.ControlCompact
        )
        Spacer(Modifier.height(10.dp))

        if (ops.isEmpty()) {
            EmptyHint(icon = Icons.Outlined.Person, text = stringResource(R.string.s693))
        } else {
            val filtered = ops.filter { matchesPlayerQuery(it.name, search) }
            if (filtered.isEmpty()) {
                EmptyHint(icon = Icons.Outlined.Person, text = stringResource(R.string.s694))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filtered.forEach { op ->
                        PlayerListRow(
                            name = op.name,
                            subtitle = stringResource(R.string.s695, op.level, op.uuid.take(8)),
                            badgeText = "Lv.${op.level}",
                            badgeColor = Indigo,
                            onClick = { onShowDetail(op) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.s696), color = Indigo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = opName,
            onValueChange = { opName = it },
            label = { Text(stringResource(R.string.s697), fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.ControlCompact),
            shape = McShape.ControlCompact
        )
        Spacer(Modifier.height(8.dp))

        // ── 勾选框：使用配置文件默认 OP 等级（无需重启） ──
        // BDS 只有 operator / member 两档权限，没有等级，这套选择器对它没有意义
        if (supportsOpLevel) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = useDefaultLevel,
                    onCheckedChange = { useDefaultLevel = it },
                    colors = CheckboxDefaults.colors(checkedColor = Indigo)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.s1031),
                        fontSize = 11.sp
                    )
                    // 显示当前配置的默认 OP 等级
                    Text(
                        if (defaultOpLevel != null) stringResource(R.string.s1059, defaultOpLevel)
                        else stringResource(R.string.s1060),
                        color = Muted,
                        fontSize = 10.sp
                    )
                }
                Text(
                    stringResource(R.string.s1032),
                    color = Indigo,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onNavigateProperties() }
                )
            }
        }

        // ── 手动指定等级模式 ──
        if (!useDefaultLevel && supportsOpLevel) {
            Spacer(Modifier.height(6.dp))
            // 等级选择
            Box {
                OutlinedButton(
                    onClick = { levelMenuOpen = true },
                    shape = McShape.ControlCompact,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(selectedLevel.labelRes), color = Indigo, fontSize = 11.sp)
                }
                DropdownMenu(expanded = levelMenuOpen, onDismissRequest = { levelMenuOpen = false }) {
                    OpLevel.values().forEach { lvl ->
                        DropdownMenuItem(
                            text = { Text(stringResource(lvl.labelRes), fontSize = 11.sp) },
                            onClick = { selectedLevel = lvl; levelMenuOpen = false }
                        )
                    }
                }
            }
            // 红色提示 + 蓝色"不想重启？"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.s1033),
                    color = Coral,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.s1034),
                    color = Indigo,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onNavigateOpGuide() }
                )
            }
            Text(
                stringResource(R.string.s1036),
                color = Muted,
                fontSize = 9.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        if (!isRunning) {
            Text(stringResource(R.string.s699), color = Coral, fontSize = 10.sp)
        }
        Button(
            onClick = {
                if (opName.isNotBlank()) {
                    if (useDefaultLevel || !supportsOpLevel) {
                        onOpAddDefault(opName.trim())
                    } else {
                        onOpAdd(opName.trim(), selectedLevel.value)
                    }
                    opName = ""
                }
            },
            enabled = isRunning && opName.isNotBlank(),
            colors = skinButtonColors(SkinTileSlots.PRIMARY_BUTTON, Indigo),
            shape = McShape.ControlCompact,
            modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.PRIMARY_BUTTON, McShape.ControlCompact)) {
            Text(
                if (useDefaultLevel || !supportsOpLevel) stringResource(R.string.s1035)
                else stringResource(R.string.s700, selectedLevel.value),
                color = Color.White,
                fontSize = 11.sp
            )
        }
    }
}

// ── 白名单标签 ──────────────────────────────────────────────────────

@Composable
private fun WhitelistTab(
    whitelist: List<PlayerManager.WhitelistEntry>,
    isRunning: Boolean,
    whitelistEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onWhitelistAdd: (String) -> Unit,
    onShowDetail: (PlayerManager.WhitelistEntry) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }

    McCard(title = stringResource(R.string.s659)) {
        // 白名单开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (whitelistEnabled) stringResource(R.string.s272) else stringResource(R.string.s273),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (whitelistEnabled) Mint else Muted
                )
                Text(
                    if (whitelistEnabled) stringResource(R.string.s703)
                    else stringResource(R.string.s704),
                    color = Muted,
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = whitelistEnabled,
                onCheckedChange = onToggle,
                enabled = isRunning
            )
        }
        if (!isRunning) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.s274), color = Coral, fontSize = 10.sp)
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text(stringResource(R.string.s705), fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(14.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.ControlCompact),
            shape = McShape.ControlCompact
        )
        Spacer(Modifier.height(10.dp))

        if (whitelist.isEmpty()) {
            EmptyHint(icon = Icons.Outlined.Person, text = stringResource(R.string.s706))
        } else {
            val filtered = whitelist.filter { matchesPlayerQuery(it.name, search) }
            if (filtered.isEmpty()) {
                EmptyHint(icon = Icons.Outlined.Person, text = stringResource(R.string.s707))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filtered.forEach { w ->
                        PlayerListRow(
                            name = w.name,
                            subtitle = w.uuid,
                            badgeText = null,
                            badgeColor = Color.Transparent,
                            onClick = { onShowDetail(w) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.s708), color = Indigo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.s697), fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f).skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.ControlCompact),
                shape = McShape.ControlCompact
            )
            Button(
                modifier = Modifier.skinTileOverlay(SkinTileSlots.PRIMARY_BUTTON, McShape.ControlCompact),
                onClick = {
                    if (newName.isNotBlank()) {
                        onWhitelistAdd(newName.trim())
                        newName = ""
                    }
                },
                enabled = isRunning && newName.isNotBlank(),
                colors = skinButtonColors(SkinTileSlots.PRIMARY_BUTTON, Indigo),
                shape = McShape.ControlCompact
            ) { Text(stringResource(R.string.s709), color = Color.White, fontSize = 11.sp) }
        }
    }
}

// ── 封禁列表标签 ────────────────────────────────────────────────────

@Composable
private fun BannedTab(
    banned: List<PlayerManager.BannedEntry>,
    isRunning: Boolean,
    onBan: (String, String, String) -> Unit,
    onShowDetail: (PlayerManager.BannedEntry) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var banName by remember { mutableStateOf("") }
    var banReason by remember { mutableStateOf("") }
    var selectedDuration by remember { mutableStateOf(BanDuration.FOREVER) }
    var durationMenuOpen by remember { mutableStateOf(false) }

    McCard(title = stringResource(R.string.s660)) {
        if (banned.isEmpty()) {
            EmptyHint(icon = Icons.Outlined.Person, text = stringResource(R.string.s710))
        } else {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text(stringResource(R.string.s711), fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(14.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.ControlCompact),
                shape = McShape.ControlCompact
            )
            Spacer(Modifier.height(10.dp))
            val filtered = banned.filter { matchesPlayerQuery(it.name, search) }
            if (filtered.isEmpty()) {
                EmptyHint(icon = Icons.Outlined.Person, text = stringResource(R.string.s712))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filtered.forEach { b ->
                        val isTemp = b.expires != "forever" && b.expires.isNotBlank()
                        PlayerListRow(
                            name = b.name,
                            subtitle = if (isTemp) stringResource(R.string.s713, b.expires) else stringResource(R.string.s714),
                            badgeText = if (isTemp) stringResource(R.string.s715) else stringResource(R.string.s661),
                            badgeColor = if (isTemp) Coral else CoralSoft,
                            onClick = { onShowDetail(b) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.s716), color = Coral, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = banName,
            onValueChange = { banName = it },
            label = { Text(stringResource(R.string.s697), fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.ControlCompact),
            shape = McShape.ControlCompact
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = banReason,
            onValueChange = { banReason = it },
            label = { Text(stringResource(R.string.s717), fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.ControlCompact),
            shape = McShape.ControlCompact
        )
        Spacer(Modifier.height(6.dp))
        // 封禁时长选择
        Box {
            OutlinedButton(
                onClick = { durationMenuOpen = true },
                shape = McShape.ControlCompact,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(selectedDuration.labelRes), color = Indigo, fontSize = 11.sp)
            }
            DropdownMenu(expanded = durationMenuOpen, onDismissRequest = { durationMenuOpen = false }) {
                BanDuration.values().forEach { d ->
                    DropdownMenuItem(
                        text = { Text(stringResource(d.labelRes), fontSize = 11.sp) },
                        onClick = { selectedDuration = d; durationMenuOpen = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!isRunning) {
            Text(stringResource(R.string.s718), color = Coral, fontSize = 10.sp)
        }
        Button(
            onClick = {
                if (banName.isNotBlank()) {
                    onBan(banName.trim(), selectedDuration.value, banReason.trim())
                    banName = ""
                    banReason = ""
                }
            },
            enabled = isRunning && banName.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Coral),
            shape = McShape.ControlCompact,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (selectedDuration == BanDuration.FOREVER) stringResource(R.string.s714)
                else stringResource(R.string.s719, stringResource(selectedDuration.labelRes)),
                color = Color.White,
                fontSize = 11.sp
            )
        }
    }
}

// ── 详情对话框 ──────────────────────────────────────────────────────

private sealed class DetailInfo {
    data class Activity(val player: String) : DetailInfo()
    data class Op(val entry: PlayerManager.OpEntry) : DetailInfo()
    data class Whitelist(val entry: PlayerManager.WhitelistEntry) : DetailInfo()
    data class Banned(val entry: PlayerManager.BannedEntry) : DetailInfo()
}

private fun DetailInfo.playerName(): String = when (this) {
    is DetailInfo.Activity -> player
    is DetailInfo.Op -> entry.name
    is DetailInfo.Whitelist -> entry.name
    is DetailInfo.Banned -> entry.name
}

@Composable
private fun PlayerDetailDialog(
    info: DetailInfo,
    activity: McViewModel.PlayerActivitySummary?,
    isRunning: Boolean,
    onDismiss: () -> Unit,
    onDeop: (String) -> Unit,
    onWhitelistRemove: (String) -> Unit,
    onPardon: (String) -> Unit,
    onKick: (String) -> Unit,
    onBan: (String) -> Unit,
    onSetGameMode: (String, Int) -> Unit,
    onGiveXp: (String, Int) -> Unit
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = Indigo, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.s720), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (info) {
                    is DetailInfo.Activity -> DetailRow(stringResource(R.string.s697), info.player)
                    is DetailInfo.Op -> {
                        val op = info.entry
                        DetailRow(stringResource(R.string.s697), op.name)
                        DetailRow("UUID", op.uuid)
                        DetailRow(stringResource(R.string.s721), "${op.level} / 4")
                    }
                    is DetailInfo.Whitelist -> {
                        val w = info.entry
                        DetailRow(stringResource(R.string.s697), w.name)
                        DetailRow("UUID", w.uuid)
                    }
                    is DetailInfo.Banned -> {
                        val b = info.entry
                        DetailRow(stringResource(R.string.s697), b.name)
                        DetailRow("UUID", b.uuid)
                        DetailRow(stringResource(R.string.s722), b.reason.ifBlank { stringResource(R.string.s723) })
                        DetailRow(stringResource(R.string.s724), b.expires.ifBlank { stringResource(R.string.s661) })
                        if (b.source.isNotBlank()) DetailRow(stringResource(R.string.s725), b.source)
                    }
                }

                activity?.let { summary ->
                    Spacer(Modifier.height(8.dp))
                    val onlineNow = stringResource(R.string.plx_online_now)
                    val noRecord = stringResource(R.string.plx_no_record)
                    Text(stringResource(R.string.plx_activity_title), color = Indigo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    DetailRow(stringResource(R.string.plx_recent_join), summary.lastJoinTime ?: noRecord)
                    DetailRow(stringResource(R.string.plx_recent_leave), summary.lastLeaveTime ?: if (summary.active) onlineNow else noRecord)
                    DetailRow(stringResource(R.string.plx_total_online), formatActivitySeconds(summary.totalSeconds))
                    DetailRow(stringResource(R.string.plx_last_exit_reason), summary.lastExitReason ?: if (summary.active) onlineNow else noRecord)
                }

                Spacer(Modifier.height(12.dp))
                if (!isRunning) {
                    Text(stringResource(R.string.s726), color = Coral, fontSize = 10.sp)
                } else {
                    Text(stringResource(R.string.s727), color = Indigo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))

                    val playerName = when (info) {
                        is DetailInfo.Activity -> info.player
                        is DetailInfo.Op -> info.entry.name
                        is DetailInfo.Whitelist -> info.entry.name
                        is DetailInfo.Banned -> info.entry.name
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onKick(playerName) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.s688), color = Coral, fontSize = 11.sp)
                        }
                        TextButton(onClick = { onBan(playerName) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.s728), color = Coral, fontSize = 11.sp)
                        }
                    }

                    // 根据类型显示对应操作
                    when (info) {
                        is DetailInfo.Activity -> Unit
                        is DetailInfo.Op -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { onDeop(playerName) }, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.s729), color = Coral, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.s730), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.s731), color = Coral.copy(alpha = 0.9f), fontSize = 9.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                listOf(R.string.s281 to 0, R.string.s282 to 1, R.string.s283 to 2, R.string.s284 to 3).forEach { (labelRes, mode) ->
                                    TextButton(onClick = { onSetGameMode(playerName, mode) }) {
                                        Text(stringResource(labelRes), fontSize = 10.sp, color = Indigo)
                                    }
                                }
                            }
                        }
                        is DetailInfo.Whitelist -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { onWhitelistRemove(playerName) }, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.s732), color = Coral, fontSize = 11.sp)
                                }
                            }
                        }
                        is DetailInfo.Banned -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { onPardon(playerName) }, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.s733), color = Mint, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.s620)) }
        }
    )
}

// ── 通用组件 ──────────────────────────────────────────────────────

@Composable
private fun PlayerListRow(
    name: String,
    subtitle: String,
    badgeText: String?,
    badgeColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(IndigoSoft)
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Indigo),
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(2).uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badgeText != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(badgeColor.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(badgeText, color = badgeColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.size(6.dp))
        }
        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.s734), tint = Muted, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 预览（Android Studio Design 面板专用）：只被 @Preview 调用，release 由 R8 裁掉
// ════════════════════════════════════════════════════════════════════════════

@Preview(name = "StatusRow · Light", showBackground = true)
@Preview(name = "StatusRow · Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewStatusRow() {
    PreviewPane {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusRow(color = MaterialTheme.colorScheme.primary, text = "服务器运行中")
            StatusRow(color = MaterialTheme.colorScheme.error, text = "服务器已停止")
        }
    }
}

@Preview(name = "PlayerListRow · Light", showBackground = true)
@Preview(name = "PlayerListRow · Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewPlayerListRow() {
    PreviewPane {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PlayerListRow(
                name = "Steve",
                subtitle = "生存模式 · 延迟 23ms",
                badgeText = "OP",
                badgeColor = MaterialTheme.colorScheme.primary,
                onClick = {}
            )
            PlayerListRow(
                name = "Alex",
                subtitle = "创造模式 · 延迟 87ms",
                badgeText = null,
                badgeColor = MaterialTheme.colorScheme.primary,
                onClick = {}
            )
        }
    }
}
