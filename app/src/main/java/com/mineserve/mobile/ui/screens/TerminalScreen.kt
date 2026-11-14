package com.mineserve.mobile.ui.screens

// 性能修改理由：终端列表保留复用能力，并缓存行样式和截断超长显示文本以降低测量开销。
//
// 视觉修改理由（2026-11）：
// 1. 原先整页把背景 / 文字 / 日志色写死成 #121419、#E8E8E8、#57E357 等散装十六进制。
//    现在统一走 [LocalTerminalPalette]：色相全部归到项目色系，并且由终端页自己的
//    「浅色终端」开关决定用哪一套 —— 默认深色，与 App 主题解耦。
// 2. 标题栏原先只是一行 22sp 等宽文字 + 5 个裸图标按钮，和别的页「eyebrow + 标题」
//    的层级语言不一致。现在改成同一套层次，并把低频操作（清空 / 新建 / 设置）收进
//    溢出菜单，主操作只留「暂停滚动 + 复制」。
// 3. 会话标签、快捷指令原先各自手搓圆角，与全项目的胶囊语言（McShape.Chip）不统一。
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.SharedPreferences
import com.mineserve.mobile.R
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.TerminalDisplayLine
import com.mineserve.mobile.ui.TerminalLogTone
import com.mineserve.mobile.ui.TerminalSession
import com.mineserve.mobile.ui.TerminalSessionType
import com.mineserve.mobile.ui.theme.DarkTerminalPalette
import com.mineserve.mobile.ui.theme.LightTerminalPalette
import com.mineserve.mobile.ui.theme.LocalTerminalPalette
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.TerminalPalette
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import com.mineserve.mobile.ui.components.AppDialog

/**
 * 终端页的深浅色偏好。
 *
 * 与 App 主题解耦：用户可能整套 App 用浅色、但终端仍想要黑底（终端惯例），
 * 也可能反过来。所以这是终端页自己的一个开关，默认深色（KEY_TERMINAL_LIGHT 缺省 false）。
 */
private const val LOG_PREFS = "log_prefs"
private const val KEY_TERMINAL_LIGHT = "terminal_light"

private data class QuickMcCommand(val emoji: String, val labelRes: Int, val command: String)

private val quickMcCommands = listOf(
    QuickMcCommand("☀️", R.string.tc_weather_clear, "weather clear"),
    QuickMcCommand("🌧️", R.string.tc_weather_rain, "weather rain"),
    QuickMcCommand("⛈️", R.string.tc_weather_thunder, "weather thunder"),
    QuickMcCommand("🌅", R.string.tc_time_day, "time set day"),
    QuickMcCommand("🌙", R.string.tc_time_night, "time set night"),
    QuickMcCommand("🕛", R.string.tc_time_noon, "time set noon"),
    QuickMcCommand("🕹️", R.string.tc_gm_survival, "gamemode survival @p"),
    QuickMcCommand("🧱", R.string.tc_gm_creative, "gamemode creative @p"),
    QuickMcCommand("🗺️", R.string.tc_gm_adventure, "gamemode adventure @p"),
    QuickMcCommand("👁️", R.string.tc_gm_spectator, "gamemode spectator @p"),
    QuickMcCommand("🕊️", R.string.tc_diff_peaceful, "difficulty peaceful"),
    QuickMcCommand("⚔️", R.string.tc_diff_normal, "difficulty normal"),
    QuickMcCommand("🔥", R.string.tc_diff_hard, "difficulty hard"),
    QuickMcCommand("💀", R.string.tc_kill_entities, "kill @e[type=!player]"),
    QuickMcCommand("👥", R.string.tc_player_list, "list"),
    QuickMcCommand("📍", R.string.tc_spawnpoint, "spawnpoint @p"),
    QuickMcCommand("🎒", R.string.tc_clear_effects, "effect clear @p"),
    QuickMcCommand("🛡️", R.string.tc_keep_inventory, "gamerule keepInventory true"),
    QuickMcCommand("🚫", R.string.tc_whitelist_off, "whitelist off"),
    QuickMcCommand("✅", R.string.tc_whitelist_on, "whitelist on"),
    QuickMcCommand("💾", R.string.tc_save_world, "save-all"),
    QuickMcCommand("❓", R.string.tc_help, "help"),
    QuickMcCommand("🛑", R.string.tc_stop_server, "stop")
)

@Composable
fun TerminalScreen(vm: McViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(LOG_PREFS, Context.MODE_PRIVATE) }
    // 默认深色：缺省值 false = 黑色终端
    var lightTheme by remember { mutableStateOf(prefs.getBoolean(KEY_TERMINAL_LIGHT, false)) }
    CompositionLocalProvider(
        LocalTerminalPalette provides if (lightTheme) LightTerminalPalette else DarkTerminalPalette
    ) {
        TerminalPane(
            vm = vm,
            prefs = prefs,
            lightTheme = lightTheme,
            onLightThemeChange = { on ->
                lightTheme = on
                prefs.edit().putBoolean(KEY_TERMINAL_LIGHT, on).apply()
            }
        )
    }
}

@Composable
private fun TerminalPane(
    vm: McViewModel,
    prefs: SharedPreferences,
    lightTheme: Boolean,
    onLightThemeChange: (Boolean) -> Unit
) {
    val sessions by vm.terminalSessions.collectAsState()
    val activeId by vm.activeTerminalSessionId.collectAsState()
    // 会话列表由 VM 维护，理论上进来时至少有一条；真为空时给一帧空态，
    // 不用 first() 硬取（否则整棵组合树会抛异常）。
    val active = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull() ?: return
    val mcLines by vm.terminalConsoleLines.collectAsState()
    val serverProps by vm.serverProperties.collectAsState()
    val context = LocalContext.current
    val pal = LocalTerminalPalette.current
    var input by remember(activeId) { mutableStateOf("") }
    var translateLogs by remember { mutableStateOf(prefs.getBoolean("localize", true)) }
    var showSettings by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // 日志汉化只对中文界面有意义；主题开关对所有语言都显示（它是通用偏好）。
    val isChineseUi = java.util.Locale.getDefault().language == "zh"
    var autoScrollPaused by rememberSaveable { mutableStateOf(false) }
    var pendingDangerousCommand by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val rawLines = if (active.type == TerminalSessionType.Minecraft) mcLines else active.lines
    LaunchedEffect(Unit) {
        vm.setLogTranslationEnabled(translateLogs && isChineseUi)
        vm.loadRecentTerminalLog()
        // 「allow-cheats=false 时玩家命令会静默失效」这条提示要读 server.properties，
        // 而属性只在属性页/网络页加载过，直接进控制台会读到空表、提示永不出现。
        vm.loadServerProperties()
    }
    // Keep the full session history for copy/export, but cap Compose nodes on low-end devices.
    val renderedLines = remember(rawLines) { rawLines.takeLast(MAX_RENDERED_LINES) }
    // 追踪用户是否手动向上滚动；用户在底部附近时自动滚动跟随新日志
    var userScrolledUp by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible < layout.totalItemsCount - 2
        }.distinctUntilChanged().collect { scrolledUp -> userScrolledUp = scrolledUp }
    }
    // 切换会话时重置滚动状态并跳到底部
    LaunchedEffect(activeId) {
        userScrolledUp = false
        if (renderedLines.isNotEmpty()) listState.scrollToItem(renderedLines.lastIndex)
    }
    // 新日志到达时，若用户未手动上滑，自动滚动到底部。
    //
    // 不用 `LaunchedEffect(rawLines.lastOrNull())`：那是以「最后一行文本」为 key，
    // 服务端刷日志（BDS 启动阶段一秒几十行）就等于每秒触发几十次
    // `scrollToItem`，全部排在主线程 —— 这是启动后/切控制台瞬间卡住（ANR）的
    // 主要嫌疑之一。
    //
    // key 必须包含 rawLines 本身：它是 ImmutableList，每次更新都是**新实例**，
    // 而 `snapshotFlow { rawLines.size }` 里的 size 不是快照状态 —— 闭包持有首次
    // 组合时的那个列表，不换 key 就只在启动时发射一次，日志再多也不跟随。
    // 按批次换 key 正好实现「一次更新滚一次」，与上面的意图一致。
    LaunchedEffect(listState, autoScrollPaused, rawLines) {
        snapshotFlow { rawLines.size }
            .conflate()
            .collect {
                if (!userScrolledUp && !autoScrollPaused) {
                    // 等一帧再滚：避免和同帧的列表更新抢主线程
                    withFrameNanos { }
                    val last = (renderedLines.size - 1).coerceAtLeast(0)
                    if (renderedLines.isNotEmpty()) listState.scrollToItem(last, Int.MAX_VALUE)
                }
            }
    }

    LaunchedEffect(autoScrollPaused) {
        if (!autoScrollPaused && renderedLines.isNotEmpty()) {
            listState.scrollToItem(renderedLines.lastIndex, Int.MAX_VALUE)
        }
    }

    val sendCommand: (String) -> Unit = { command ->
        val dangerous = active.type == TerminalSessionType.Minecraft && command.trim() in setOf(
            "stop",
            "kill @e[type=!player]",
            "whitelist off"
        )
        if (dangerous) {
            pendingDangerousCommand = command
        } else if (active.type == TerminalSessionType.Termux) {
            vm.executeTerminalCommand(active.id, command)
        } else if (command.trim() == "save-all") {
            // 快捷条上的「保存世界」对基岩版要发 hold + resume：
            // BDS 没有 save-all，只发 hold 会让世界从此不再落盘（真机实测）。
            vm.saveWorld()
        } else {
            vm.sendCommand(command)
        }
    }

    val sendInput = {
        if (input.isNotBlank()) {
            if (active.type == TerminalSessionType.Termux && active.busy) {
                vm.sendTerminalInput(active.id, input)
            } else {
                sendCommand(input)
            }
            input = ""
        }
    }

    val clearSession = {
        if (active.type == TerminalSessionType.Minecraft) vm.clearConsole()
        else vm.clearTerminalSession(active.id)
    }

    Scaffold(
        containerColor = pal.surface,
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            // Kept in this screen's bottom slot so the app navigation bar and
            // IME cannot overlay the command field or its send action.
            CommandBar(
                value = input,
                onValueChange = { input = it },
                onSubmit = { sendInput() },
                prompt = if (active.type == TerminalSessionType.Minecraft) ">" else "$",
                pal = pal
            )
        }
    ) { terminalPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .background(pal.surface)
                .padding(terminalPadding)
        ) {
            TerminalTopBar(
                sessionName = active.name,
                autoScrollPaused = autoScrollPaused,
                onTogglePause = { autoScrollPaused = !autoScrollPaused },
                onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(active.name, rawLines.joinToString("\n") { it.text }))
                    android.widget.Toast.makeText(context, context.getString(R.string.tm_copied), android.widget.Toast.LENGTH_SHORT).show()
                },
                onClear = clearSession,
                onNewSession = { vm.createTerminalSession() },
                onOpenSettings = { showSettings = true },
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                pal = pal
            )
            SessionTabs(
                sessions = sessions,
                activeId = activeId,
                onSelect = { vm.selectTerminalSession(it) },
                onClose = { vm.closeTerminalSession(it) },
                pal = pal
            )
            if (active.type == TerminalSessionType.Minecraft) {
                QuickCommandRow(onSend = sendCommand, pal = pal)
                // allow-cheats=false 时，**玩家在游戏里**发命令会「发出去什么都没有」，
                // 连一句报错都没有（控制台自己不受影响，所以容易误判成客户端问题）。
                // 这个开关在属性页深处，用户不会联想到，就在这里点明。
                if (serverProps["allow-cheats"]?.equals("false", ignoreCase = true) == true) {
                    Text(
                        stringResource(R.string.tc_allow_cheats_off_hint),
                        color = pal.logWarning,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
            if (renderedLines.isEmpty()) {
                EmptyTerminalState(pal = pal, modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    itemsIndexed(
                        renderedLines,
                        key = { _, line -> line.id },
                        contentType = { _, _ -> "terminal-log" }
                    ) { _, line ->
                        val color = pal.colorFor(line.tone)
                        val displayText = remember(line.text) { line.text.forTerminalDisplay() }
                        Text(
                            displayText,
                            color = color,
                            fontSize = 11.5.sp,
                            // 显式给出 lineHeight：一条日志自动折行时行距由它决定。
                            // 不写会按字体默认值走，多行日志挤成一片，读不出是一句还是两句。
                            lineHeight = 16.5.sp,
                            fontFamily = terminalLogFontFamily,
                            softWrap = true
                        )
                    }
                }
            }
        }
    }
    if (showSettings) {
        // tm_localize 是「主标题\n副说明」两段式文案：整串丢进一个 Text 会让
        // 标题和说明同字号同颜色，扫一眼分不出哪句是开关的名字。
        val localizeParts = stringResource(R.string.tm_localize).split('\n', limit = 2)
        AppDialog(
            onDismissRequest = { showSettings = false },
            shape = McShape.Dialog,
            title = { Text(stringResource(R.string.tm_settings), fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    TerminalSwitchRow(
                        title = stringResource(R.string.tm_theme_light),
                        subtitle = stringResource(R.string.tm_theme_hint),
                        checked = lightTheme,
                        onCheckedChange = onLightThemeChange
                    )
                    if (isChineseUi) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        TerminalSwitchRow(
                            title = localizeParts[0],
                            subtitle = localizeParts.getOrNull(1).orEmpty(),
                            checked = translateLogs,
                            onCheckedChange = {
                                translateLogs = it
                                prefs.edit().putBoolean("localize", it).apply()
                                vm.setLogTranslationEnabled(it && isChineseUi)
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text(stringResource(R.string.tm_done)) }
            }
        )
    }
    pendingDangerousCommand?.let { command ->
        AppDialog(
            onDismissRequest = { pendingDangerousCommand = null },
            shape = McShape.Dialog,
            title = { Text(stringResource(R.string.tm_danger_title), color = pal.logError) },
            text = { Text(stringResource(R.string.tm_danger_body, command)) },
            dismissButton = {
                TextButton(onClick = { pendingDangerousCommand = null }) { Text(stringResource(R.string.s402)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDangerousCommand = null
                        vm.sendCommand(command)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = pal.logError)
                ) { Text(stringResource(R.string.tm_danger_confirm)) }
            }
        )
    }
}

/** 终端设置里的一行开关：标题 + 一句副说明 + Switch。 */
@Composable
private fun TerminalSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 顶部操作栏。
 *
 * 2026-11-13 起标题文字下线（原先放当前会话名），只留右侧动作按钮，下方一条细线与内容分隔。
 * [sessionName] 因此不再显示，保留参数只为不改动调用点 —— 会话归属由下方的会话标签行表达。
 *
 * 操作分两档：高频的「暂停滚动 / 复制」留在外面，低频的「新建 / 清空 / 设置」
 * 收进溢出菜单。改造前 5 个图标平铺，既挤又有两个（清空、新建）误触代价高。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
private fun TerminalTopBar(
    sessionName: String,
    autoScrollPaused: Boolean,
    onTogglePause: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    pal: TerminalPalette
) {
    Column(
        Modifier
            .fillMaxWidth()
    ) {
        // 标题栏文字（会话名）已下线，只留右侧动作按钮；无底色、无分隔线
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            TerminalIconAction(
                icon = if (autoScrollPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                description = stringResource(
                    if (autoScrollPaused) R.string.tm_resume_scroll else R.string.tm_pause_scroll
                ),
                tint = if (autoScrollPaused) pal.logWarning else pal.muted,
                onClick = onTogglePause
            )
            TerminalIconAction(
                icon = Icons.Outlined.ContentCopy,
                description = stringResource(R.string.tm_copy_log),
                tint = pal.muted,
                onClick = onCopy
            )
            Box {
                TerminalIconAction(
                    icon = Icons.Outlined.MoreVert,
                    description = stringResource(R.string.tm_settings),
                    tint = pal.muted,
                    onClick = { onMenuOpenChange(true) }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tm_new_session), fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Outlined.Add, null, modifier = Modifier.size(McIcon.Item), tint = pal.muted) },
                        onClick = {
                            onMenuOpenChange(false)
                            onNewSession()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tm_clear_session), fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(McIcon.Item), tint = pal.logError) },
                        onClick = {
                            onMenuOpenChange(false)
                            onClear()
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tm_settings), fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Outlined.Settings, null, modifier = Modifier.size(McIcon.Item), tint = pal.muted) },
                        onClick = {
                            onMenuOpenChange(false)
                            onOpenSettings()
                        }
                    )
                }
            }
        }
    }
}

/** 标题栏图标按钮：命中区固定 [McIcon.Touch]，视觉图标 [McIcon.Item]。 */
@Composable
private fun TerminalIconAction(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(McIcon.Touch)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(McIcon.Item))
    }
}

/** 会话标签行。胶囊外观与全项目的 [McShape.Chip] 对齐，不再各写各的圆角。 */
@Composable
private fun SessionTabs(
    sessions: List<TerminalSession>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    pal: TerminalPalette
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(pal.bar)
            .horizontalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        sessions.forEach { session ->
            val selected = session.id == activeId
            Row(
                modifier = Modifier
                    .height(SessionChipHeight)
                    .clip(McShape.Chip)
                    .background(if (selected) pal.accent else pal.chipBg)
                    .clickable { onSelect(session.id) }
                    .padding(
                        start = 14.dp,
                        end = if (session.type == TerminalSessionType.Termux) 4.dp else 14.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    session.name,
                    color = if (selected) pal.onAccent else pal.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .widthIn(max = 180.dp)
                        .then(if (session.type == TerminalSessionType.Termux) Modifier.padding(end = 2.dp) else Modifier)
                )
                if (session.type == TerminalSessionType.Termux) {
                    // 关闭按钮单独 28dp 命中区：原先整个会话标签可点关闭，
                    // 想切会话却关掉会话是这里最常见的一次误操作。
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { onClose(session.id) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.tm_close),
                            tint = if (selected) pal.onAccent else pal.muted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

private val SessionChipHeight = 34.dp

/** 快捷指令行（仅 Minecraft 控制台）。 */
@Composable
private fun QuickCommandRow(onSend: (String) -> Unit, pal: TerminalPalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(pal.bar)
            .horizontalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        quickMcCommands.forEach { item ->
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(McShape.Chip)
                    .background(pal.chipBg)
                    .clickable { onSend(item.command) }
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${item.emoji} ${stringResource(item.labelRes)}",
                    color = pal.ink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 空状态。
 *
 * 会话刚建、或刚执行完清空时，日志区如果只是纯背景色，看不出是「没有日志」
 * 还是「页面挂了」。这里给一个图标 + 一句说明，明确告诉用户日志何时出现。
 */
@Composable
private fun EmptyTerminalState(pal: TerminalPalette, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(pal.chipBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Terminal,
                contentDescription = null,
                tint = pal.muted,
                modifier = Modifier.size(McIcon.Display)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.tm_empty),
            color = pal.muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 底部命令行。
 *
 * 用自绘的 BasicTextField 而不是 OutlinedTextField：后者的最小高度固定 56dp，
 * 在「标题栏 + 标签行 + 快捷指令」已经占掉三行之后，输入区再占 56dp 会把
 * 日志区压得很窄。这里固定 44dp（仍满足最小触摸区）。
 */
@Composable
private fun CommandBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    prompt: String,
    pal: TerminalPalette
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.bar)
            .imePadding()
    ) {
        HorizontalDivider(color = pal.line)
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                prompt,
                color = pal.logCommand,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(CommandFieldHeight)
                    .clip(McShape.ControlCompact)
                    .background(pal.fieldBg)
                    .border(1.dp, pal.line, McShape.ControlCompact),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = pal.ink,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(pal.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                )
            }
            val canSend = value.isNotBlank()
            Box(
                modifier = Modifier
                    .size(CommandFieldHeight)
                    .clip(McShape.ControlCompact)
                    .background(if (canSend) pal.accent else pal.chipBg)
                    .clickable(enabled = canSend, onClick = onSubmit),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = stringResource(R.string.tm_send),
                    tint = if (canSend) pal.onAccent else pal.muted,
                    modifier = Modifier.size(McIcon.Item)
                )
            }
        }
    }
}

private val CommandFieldHeight = 44.dp

private const val MAX_RENDERED_LINES = 300
private const val MAX_DISPLAY_LOG_CHARS = 400

private val terminalLogFontFamily = FontFamily.Monospace

/**
 * 日志语义色。取值全部来自 [TerminalPalette]，浅/深两套各自保证对比度：
 * 浅底上用压暗一档的同色相，深底上用提亮一档的同色相，语义指向保持一致。
 */
private fun TerminalPalette.colorFor(tone: TerminalLogTone): Color = when (tone) {
    TerminalLogTone.Command -> logCommand
    TerminalLogTone.Error -> logError
    TerminalLogTone.Warning -> logWarning
    TerminalLogTone.Info -> logInfo
    TerminalLogTone.Debug -> logDebug
    TerminalLogTone.Download -> logDownload
    TerminalLogTone.Success -> logSuccess
    TerminalLogTone.Default -> logDefault
}

private fun String.forTerminalDisplay(): String =
    if (length > MAX_DISPLAY_LOG_CHARS) take(MAX_DISPLAY_LOG_CHARS) + "..." else this
