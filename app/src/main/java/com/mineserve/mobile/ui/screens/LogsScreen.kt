package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Card
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Ink
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mineserve.mobile.data.skin.SkinTileSlots
import com.mineserve.mobile.ui.components.skinTileOverlay
import com.mineserve.mobile.ui.components.AppDialog

/**
 * 日志页：只读可视化反馈（不是交互入口）。
 * - 顶部"返回"按钮回到概览
 * - 实时滚动展示 consoleFlow 输出
 * - 底部提供一条 send 框（受控、给高级用户用），不影响"全程不接触命令行"定位
 */
@Composable
fun LogsScreen(vm: McViewModel, onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    // 会话面板状态
    val serverState by vm.serverState.collectAsState()
    var termuxInput by remember { mutableStateOf("") }
    var mcExpanded by remember { mutableStateOf(true) }
    // Termux 会话默认收起（高级功能，默认折叠避免占用主界面）
    var termuxExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // 日志汉化开关（prefs，默认开启）；仅中文系统语言下生效
    val logPrefs = remember { context.getSharedPreferences("log_prefs", Context.MODE_PRIVATE) }
    var logLocalized by remember { mutableStateOf(logPrefs.getBoolean("localize", true)) }
    var showLogSettings by remember { mutableStateOf(false) }
    val isChineseLocale = java.util.Locale.getDefault().language == "zh"

    // 性能修改理由（2026-11-15）：原先这里是 `val lines by vm.consoleLines.collectAsState()`，
    // 而 consoleLines 每 200ms 发布一次 → 整页（返回键、12 个快捷指令、两个面板的标题栏与
    // 输入框、下面那一整套日志派生状态）每 200ms 全部重组，输入框里每个字符都要重算。
    // 现在页面顶层**不读日志的值**：只保留订阅本身（flush 循环靠 subscriptionCount 判断要不
    // 要发布），并把「有没有日志」收敛成一个布尔供面板可见性使用；真正渲染日志的订阅在
    // [McConsoleArea] 那一层。
    val linesState = vm.consoleLines.collectAsState()
    val hasLines by remember { derivedStateOf { linesState.value.isNotEmpty() } }


    Column(modifier = Modifier.fillMaxSize()) {
        // 与其它子页面统一的顶栏度量：左侧 8dp 承接 40dp 返回键，右侧 16dp 与卡片对齐。
        // 标题文字已下线（2026-11-13），只留返回键与设置齿轮；无底色、无分隔线。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = McSpacing.Page, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(McIcon.Touch)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.s404),
                    tint = Ink,
                    modifier = Modifier.size(McIcon.Item)
                )
            }
            Spacer(Modifier.weight(1f))
            // 日志设置齿轮按钮（汉化开关）
            IconButton(onClick = { showLogSettings = true }, modifier = Modifier.size(McIcon.Touch)) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.ui_log_localize),
                    tint = Indigo,
                    modifier = Modifier.size(McIcon.Item)
                )
            }
        }

        // 快捷指令栏：原版 MC 常用指令，横向滑动查看全部
        val quickCommands = listOf(
            Triple("🕐", R.string.cmd_time_day, "/time set day"),
            Triple("🌙", R.string.cmd_time_night, "/time set night"),
            Triple("☀️", R.string.cmd_weather_clear, "/weather clear"),
            Triple("🌧️", R.string.cmd_weather_rain, "/weather rain"),
            Triple("⛈️", R.string.cmd_weather_thunder, "/weather thunder"),
            Triple("🎮", R.string.cmd_gamemode_survival, "/gamemode survival"),
            Triple("🧱", R.string.cmd_gamemode_creative, "/gamemode creative"),
            Triple("👥", R.string.cmd_list, "/list"),
            Triple("🐢", R.string.cmd_tps, "/tps"),
            Triple("💾", R.string.cmd_save_all, "/save-all"),
            Triple("📣", R.string.cmd_say, "/say "),
            Triple("👟", R.string.cmd_kick, "/kick ")
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = McSpacing.Page, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickCommands.forEach { (emoji, labelRes, cmd) ->
                androidx.compose.material3.TextButton(
                    onClick = { vm.sendCommand(cmd) },
                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp).height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    shape = McShape.Small,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = LogPanelPalette.AccentBlue)
                ) {
                    Text(
                        "$emoji ${stringResource(labelRes)}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // ── 会话面板 1：MC 终端（服务器运行中，或已有日志（含下载/初始化 bootstrap 日志）时显示） ──
        if (serverState.isRunning || hasLines) {
            McTerminalPanel(
                vm = vm,
                expanded = mcExpanded,
                onToggle = { mcExpanded = !mcExpanded },
                input = input,
                onInputChange = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        vm.sendCommand(input)
                        input = ""
                    }
                },
                logLocalized = logLocalized,
                isChineseLocale = isChineseLocale,
                modifier = if (mcExpanded) Modifier.weight(1f) else Modifier
            )
        } // if (serverState.isRunning || hasLines) — MC 终端在服务器运行时或有日志（含下载/初始化）时显示

        // ── 会话面板 2：Termux 终端（始终显示） ──
        TermuxSessionPanel(
            vm = vm,
            expanded = termuxExpanded,
            onToggle = { termuxExpanded = !termuxExpanded },
            input = termuxInput,
            onInputChange = { termuxInput = it },
            onSend = {
                if (termuxInput.isNotBlank() && !vm.termuxBusy.value) {
                    vm.execTermuxCommand(termuxInput)
                    termuxInput = ""
                }
            },
            modifier = if (termuxExpanded) Modifier.weight(1f) else Modifier
        )
    }

    // 日志设置弹窗（汉化开关）
    if (showLogSettings) {
        AppDialog(
            onDismissRequest = { showLogSettings = false },
            title = { Text(stringResource(R.string.ui_log_localize), fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ui_log_localize), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.ui_log_localize_hint), color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    Switch(
                        checked = logLocalized,
                        onCheckedChange = {
                            logLocalized = it
                            logPrefs.edit().putBoolean("localize", it).apply()
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogSettings = false }) {
                    Text(stringResource(R.string.s620))
                }
            }
        )
    }
}

/**
 * MC 终端面板外壳：标题栏、复制按钮、底部输入框。
 *
 * 性能修改理由（2026-11-15）：这里**不订阅** consoleLines。外壳与输入框必须留在订阅层之外 ——
 * 日志每 200ms 刷新一次，若外壳也订阅，`onValueChange` 每次都是新 lambda，OutlinedTextField
 * 不可跳过，打字会跟着掉帧。日志只在 [McConsoleArea] 那一层订阅。
 */
@Composable
private fun McTerminalPanel(
    vm: McViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    logLocalized: Boolean,
    isChineseLocale: Boolean,
    modifier: Modifier
) {
    val context = LocalContext.current
    TerminalPanel(
        title = stringResource(R.string.mc_terminal),
        statusColor = LogPanelPalette.AccentGreen,
        expanded = expanded,
        onToggle = onToggle,
        onCopy = {
            // 复制按需取当前全量历史，不进组合订阅（与 TerminalScreen 同一写法）。
            val all = vm.consoleLines.value
            if (all.isNotEmpty()) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("MCServer Logs", all.joinToString("\n")))
                Toast.makeText(context, context.getString(R.string.s558, all.size), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.s559), Toast.LENGTH_SHORT).show()
            }
        },
        modifier = modifier
    ) {
        McConsoleArea(
            vm = vm,
            logLocalized = logLocalized,
            isChineseLocale = isChineseLocale,
            modifier = Modifier.weight(1f)
        )

        // 控制台输入框（受控，非交互式 UI 入口）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Card)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text(stringResource(R.string.s562), fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f).skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row))
            Spacer(Modifier.height(0.dp))
            IconButton(
                onClick = onSend,
                modifier = Modifier.size(McIcon.Touch)
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = stringResource(R.string.s563),
                    tint = Indigo,
                    modifier = Modifier.size(McIcon.Item)
                )
            }
        }
    }
}

/**
 * MC 日志区：全页唯一订阅 [McViewModel.consoleLines] 的地方。
 *
 * 由它派生的汉化文本/颜色、行 id 三件套、滚动跟随全部留在这里 —— 这些都以 `lines` 为 key
 * 或直接读 `lines`，留在页面顶层会把整页重新拉回 200ms 重组。
 */
@Composable
private fun McConsoleArea(
    vm: McViewModel,
    logLocalized: Boolean,
    isChineseLocale: Boolean,
    modifier: Modifier
) {
    val lines by vm.consoleLines.collectAsState()

    // 日志每 200ms 批量刷新一次。显示文本和颜色的计算放到后台线程完成，
    // 避免每次刷新都在主线程对全量行做汉化映射，导致触摸输入/滚动掉帧。
    var displayLines by remember { mutableStateOf(emptyList<Pair<String, Color>>()) }
    LaunchedEffect(lines, logLocalized, isChineseLocale) {
        if (lines.isEmpty()) {
            displayLines = emptyList()
        } else {
            displayLines = withContext(Dispatchers.Default) {
                lines.map { line ->
                    val text = if (logLocalized && isChineseLocale) localizeLogLine(line) else line
                    text to consoleLineColor(line)
                }
            }
        }
    }

    // 日志行 → 稳定 id：日志缓冲是有界的，写满后会从头部裁剪旧行，用下标作 LazyColumn 的 key
    // 会在裁剪时把同一 key 指向不同内容，导致行内容闪变与滚动跳动。
    // 这里维护一份自增 id，并在每次刷新时按「上一帧尾部 ↔ 新一帧头部」的重叠区间复用旧 id。
    var logLineIds by remember { mutableStateOf(emptyList<Long>()) }
    var prevLogLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var nextLogId by remember { mutableStateOf(0L) }

    LaunchedEffect(lines) {
        if (lines.isEmpty()) {
            logLineIds = emptyList(); prevLogLines = emptyList(); nextLogId = 0L
        } else {
            val (ids, next) = assignStableLineIds(prevLogLines, logLineIds, lines, nextLogId)
            logLineIds = ids; nextLogId = next; prevLogLines = lines.toList()
        }
    }

    // 日志列表
    val listState = rememberLazyListState()
    // 进入页面时定位到最新日志（底部），而非顶部
    LaunchedEffect(Unit) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(McShape.Card)
            .background(LogPanelPalette.PanelBg)
    ) {
        if (lines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.s561), color = LogPanelPalette.Placeholder, fontSize = 12.sp)
            }
        } else {
            // 自动滚动：仅当用户已位于底部附近时才跟随（瞬时，不打扰上翻）
            val atBottom by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible >= info.totalItemsCount - 3
                }
            }
            androidx.compose.runtime.LaunchedEffect(lines.size, atBottom) {
                if (lines.isNotEmpty() && atBottom) listState.scrollToItem(lines.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(
                    displayLines,
                    // id 列表与文本列表由不同 Effect 更新，短暂错位时用负数兜底，避免与真实 id 撞车导致重复 key 崩溃
                    key = { index, _ -> logLineIds.getOrNull(index) ?: (-1L - index) },
                    contentType = { _, _ -> "console-log" }
                ) { _, (text, color) ->
                    Text(
                        text,
                        color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * Termux 面板外壳：标题栏、复制按钮、底部输入框。
 * 同 [McTerminalPanel]：外壳不订阅 termuxLines，日志只在 [TermuxLinesArea] 订阅。
 */
@Composable
private fun TermuxSessionPanel(
    vm: McViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val termuxBusy by vm.termuxBusy.collectAsState()
    TerminalPanel(
        title = stringResource(R.string.termux_terminal),
        statusColor = LogPanelPalette.AccentBlue,
        expanded = expanded,
        onToggle = onToggle,
        onCopy = {
            val all = vm.termuxLines.value
            if (all.isNotEmpty()) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Termux Logs", all.joinToString("\n")))
                Toast.makeText(context, context.getString(R.string.s558, all.size), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.s559), Toast.LENGTH_SHORT).show()
            }
        },
        modifier = modifier
    ) {
        TermuxLinesArea(vm = vm, modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Card)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text(stringResource(R.string.termux_hint), fontSize = 12.sp) },
                singleLine = true,
                enabled = !termuxBusy,
                modifier = Modifier.weight(1f).skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row))
            Spacer(Modifier.height(0.dp))
            IconButton(
                onClick = onSend,
                enabled = !termuxBusy,
                modifier = Modifier.size(McIcon.Touch)
            ) {
                if (termuxBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(McIcon.Action), strokeWidth = 2.dp, color = Indigo)
                } else {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = stringResource(R.string.s563),
                        tint = Indigo,
                        modifier = Modifier.size(McIcon.Item)
                    )
                }
            }
        }
    }
}

/** Termux 日志区：唯一订阅 [McViewModel.termuxLines] 的地方。 */
@Composable
private fun TermuxLinesArea(vm: McViewModel, modifier: Modifier) {
    val termuxLines by vm.termuxLines.collectAsState()

    var termuxLineIds by remember { mutableStateOf(emptyList<Long>()) }
    var prevTermuxLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var nextTermuxId by remember { mutableStateOf(0L) }

    LaunchedEffect(termuxLines) {
        if (termuxLines.isEmpty()) {
            termuxLineIds = emptyList(); prevTermuxLines = emptyList(); nextTermuxId = 0L
        } else {
            val (ids, next) = assignStableLineIds(prevTermuxLines, termuxLineIds, termuxLines, nextTermuxId)
            termuxLineIds = ids; nextTermuxId = next; prevTermuxLines = termuxLines.toList()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(McShape.Card)
            .background(LogPanelPalette.PanelBg)
    ) {
        if (termuxLines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.termux_placeholder), color = LogPanelPalette.Placeholder, fontSize = 12.sp)
            }
        } else {
            val tState = rememberLazyListState()
            LaunchedEffect(termuxLines.size) {
                if (termuxLines.isNotEmpty()) tState.scrollToItem(termuxLines.size - 1)
            }
            LazyColumn(
                state = tState,
                modifier = Modifier.fillMaxSize().padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(
                    termuxLines,
                    key = { index, _ -> termuxLineIds.getOrNull(index) ?: (-1L - index) },
                    contentType = { _, _ -> "termux-log" }
                ) { _, line ->
                    Text(
                        line,
                        color = if (line.startsWith("$ ")) LogPanelPalette.AccentGreen else LogPanelPalette.Text,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * 为日志行分配稳定递增 id，供 LazyColumn 的 key 使用。
 *
 * 日志缓冲是有界的：写满后会从头部丢弃旧行，因此「下标」与「内容」的对应关系会整体前移，
 * 直接拿 index 当 key 会在裁剪时把同一 key 指向不同内容，造成行内容闪变与滚动跳动。
 * 这里比较「上一帧尾部」与「新一帧头部」的重叠区间以复用旧 id，其余视为新增行分配新 id。
 *
 * @return 新 id 列表 to 下一个可用 id
 */
private fun assignStableLineIds(
    prevLines: List<String>,
    prevIds: List<Long>,
    newLines: List<String>,
    nextId: Long
): Pair<List<Long>, Long> {
    val maxOverlap = minOf(prevLines.size, newLines.size)
    var overlap = maxOverlap
    while (overlap > 0) {
        val offset = prevLines.size - overlap
        var same = true
        for (i in 0 until overlap) {
            if (prevLines[offset + i] != newLines[i]) { same = false; break }
        }
        if (same) break
        overlap--
    }
    val ids = ArrayList<Long>(newLines.size)
    val base = prevIds.size - overlap
    for (i in 0 until overlap) ids.add(prevIds[base + i])
    var id = nextId
    for (i in overlap until newLines.size) ids.add(id++)
    return ids to id
}

/**
 * 日志页内嵌终端面板的调色板（Catppuccin 风格，2026-11 收敛）。
 *
 * 与 `theme/Color.kt` 的 `DarkTerminalPalette` 色相语义一致，但这里是「概览页里
 * 嵌的两块终端小窗」的历史观感，用户已看惯这组值，**不并入**主题化调色板
 * （那套随终端页深浅开关切换）。收敛的目的只是给原先 14 处散落的十六进制
 * 一个唯一出处并命名语义，取值完全不变。
 */
private object LogPanelPalette {
    /** 面板底色（MC 控制台 / Termux 两块终端小窗共用）。 */
    val PanelBg = Color(0xFF1E1E2E)

    /** 空态占位文字。 */
    val Placeholder = Color(0xFF8888AA)

    /** 默认正文。 */
    val Text = Color(0xFFCDD6F4)

    /** 蓝色强调：隧道标记行、Termux 面板状态点。 */
    val AccentBlue = Color(0xFF89B4FA)

    /** 绿色强调：命令行前缀、bootstrap 标记、MC 面板状态点。 */
    val AccentGreen = Color(0xFFA6E3A1)

    /** 错误行。 */
    val Error = Color(0xFFF38BA8)

    /** 警告行。 */
    val Warn = Color(0xFFF9E2AF)

    /** 崩溃标记行。 */
    val Crash = Color(0xFFFAB387)
}

/** 日志行颜色（纯主线程判断，开销很小） */
private fun consoleLineColor(line: String): Color = when {
    line.contains("[ERROR]") || line.contains("ERROR") || line.contains("FATAL") -> LogPanelPalette.Error
    line.contains("[WARN]") || line.contains("WARN") -> LogPanelPalette.Warn
    line.contains("[tunnel]") -> LogPanelPalette.AccentBlue
    line.contains("[crash]") -> LogPanelPalette.Crash
    line.contains("[bootstrap]") -> LogPanelPalette.AccentGreen
    else -> LogPanelPalette.Text
}

// 正则只在进程生命周期内编译一次（原先每次 localizeLogLine 都重新编译）。
private val JOINED_RE = Regex("(\\w+) joined the game")
private val LEFT_RE = Regex("(\\w+) left the game")
private val ONLINE_RE = Regex("There are (\\d+) of a max of (\\d+) players online")
private val DONE_RE = Regex("Done \\(([\\d.]+)s\\)!.*")

/** 日志行部分汉化：进服/离服/在线人数/世界保存/启动完成（仅显示层，不改原始数据） */
private fun localizeLogLine(line: String): String {
    return line
        .replace(JOINED_RE, "玩家 $1 加入了游戏")
        .replace(LEFT_RE, "玩家 $1 离开了游戏")
        .replace(ONLINE_RE, "当前在线 $1/$2 人")
        .replace("Saving worlds", "正在保存世界")
        .replace("Saved the game", "世界已保存")
        .replace("Saved the world", "世界已保存")
        .replace(DONE_RE, "服务器启动完成（$1s）")
        .replace("You need to agree to the EULA", "未接受 EULA，无法启动")
        .replace("Address already in use", "端口被占用")
        .replace("BindException", "端口绑定失败")
        .replace("Can't keep up", "服务器过载/卡顿")
        .replace("OutOfMemoryError", "内存不足")
        .replace("Java heap space", "堆内存不足")
        .replace("You are not white-listed", "不在白名单")
        .replace("Stopping the server", "正在停止服务端")
        .replace("Stopping server", "正在停止服务端")
        .replace("Autosaved", "已自动保存")
        .replace("Saving chunks", "正在保存区块")
        .replace("Saving players", "正在保存玩家数据")
        .replace("Internal Exception", "内部异常")
        .replace("Connection reset", "连接被重置")
        .replace("Connection refused", "连接被拒绝")
        .replace("Timed out", "连接超时")
}

/** 可折叠会话面板（标题栏：状态点 + 名称 + 展开/收起箭头） */
@Composable
private fun TerminalPanel(
    title: String,
    statusColor: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = McSpacing.Page, vertical = 4.dp)
            .clip(McShape.Card)
            .background(Card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(6.dp))
            Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // 会话面板各自的复制按钮（复制本面板日志）
            if (onCopy != null) {
                IconButton(onClick = onCopy, modifier = Modifier.size(McIcon.TouchCompact)) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.s560),
                        tint = Indigo,
                        modifier = Modifier.size(McIcon.Action)
                    )
                }
            }
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = Muted,
                modifier = Modifier.size(McIcon.Action)
            )
        }
        if (expanded) {
            // 占满父面板剩余高度，保证内部 content 的 weight(1f) 生效（否则日志区高度为 0 → 空白）
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
        }
    }
}
