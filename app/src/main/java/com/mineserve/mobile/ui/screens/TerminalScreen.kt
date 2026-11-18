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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
    // 性能修改理由（2026-11-15）：这里**不再** collect terminalConsoleLines。
    // 日志每 200ms 发布一次，在这里订阅等于每次刷新都让整个 TerminalPane 重组 ——
    // 标题栏、会话标签、22 个快捷指令胶囊、底部输入框全部跟着重算，配合下面的
    // 强制滚动（每次都把 300 行全量测量一遍）就是「终端页卡得动不了」的成因。
    // 现在日志只在 [MinecraftConsoleArea] 这一层订阅，只有日志区重组。
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
    LaunchedEffect(Unit) {
        vm.setLogTranslationEnabled(translateLogs && isChineseUi)
        vm.loadRecentTerminalLog()
        // 「allow-cheats=false 时玩家命令会静默失效」这条提示要读 server.properties，
        // 而属性只在属性页/网络页加载过，直接进控制台会读到空表、提示永不出现。
        vm.loadServerProperties()
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
                    // 复制按需取当前全量历史（不进组合订阅，避免日志刷新带着整页重组）。
                    val allLines = if (active.type == TerminalSessionType.Minecraft) {
                        vm.terminalConsoleLines.value
                    } else {
                        vm.terminalSessions.value.firstOrNull { it.id == active.id }?.lines.orEmpty()
                    }
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(active.name, allLines.joinToString("\n") { it.text }))
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
            // key(active.id)：切换会话时把「用户是否上滑过」「渲染窗口」这些本地状态
            // 连同滚动跟随协程一起重建成初始值，不需要再单开一个 LaunchedEffect 去重置。
            key(active.id) {
                if (active.type == TerminalSessionType.Minecraft) {
                    MinecraftConsoleArea(
                        vm = vm,
                        listState = listState,
                        autoScrollPaused = autoScrollPaused,
                        pal = pal,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    TerminalLines(
                        rawLines = active.lines,
                        listState = listState,
                        autoScrollPaused = autoScrollPaused,
                        pal = pal,
                        modifier = Modifier.weight(1f)
                    )
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

/**
 * 控制台（Minecraft）日志区：全页唯一订阅 [McViewModel.terminalConsoleLines] 的地方。
 *
 * 性能修改理由（2026-11-15）：日志每 200ms 发布一次。原先订阅写在 TerminalPane 上，
 * 等于每次刷新都让整页重组 —— 标题栏、会话标签、22 个快捷指令胶囊（含 emoji 排版）、
 * 底部 BasicTextField 全部跟着重算，输入法打开时还会反复触发布局。订阅收进这一层后，
 * 日志刷新只影响日志区本身。
 */
@Composable
private fun MinecraftConsoleArea(
    vm: McViewModel,
    listState: LazyListState,
    autoScrollPaused: Boolean,
    pal: TerminalPalette,
    modifier: Modifier
) {
    val lines by vm.terminalConsoleLines.collectAsState()
    TerminalLines(lines, listState, autoScrollPaused, pal, modifier)
}

/**
 * 日志列表 + 置底跟随。
 *
 * 性能修改理由（2026-11-15）：
 * 1. 自动滚动不再以 `rawLines` 为 key。rawLines 每次发布都是新实例（VM 每 200ms 重建），
 *    把它当 key 等于每 200ms 把协程连根销毁重建一次，snapshotFlow 的订阅也被反复拆装。
 *    现在以「最后一行 id」为 key：只在真的来了新日志时触发一次。
 * 2. 去掉 `scrollToItem(index, Int.MAX_VALUE)`。为了「消化」2^31 的偏移，LazyColumn
 *    会从目标位置一格一格往后量到列表末尾，等于每次滚动都把 300 行全量测量一遍。
 *    日志一密集就是持续占满主线程 —— 这是「卡得动不了」的主因。滚到最后一项即可，
 *    列表到底会自然钳位，观感一致。
 * 3. 字号/行高/字体收敛到同一个 [logTextStyle]，不再每行各建一个 TextStyle。
 */
@Composable
private fun TerminalLines(
    rawLines: List<TerminalDisplayLine>,
    listState: LazyListState,
    autoScrollPaused: Boolean,
    pal: TerminalPalette,
    modifier: Modifier
) {
    // 保留完整历史用于复制/导出，但只把最近 MAX_RENDERED_LINES 行交给 Compose。
    val renderedLines = remember(rawLines) { rawLines.takeLast(MAX_RENDERED_LINES) }
    val tailId = renderedLines.lastOrNull()?.id ?: 0L
    // 用户是否手动上滑：置底跟随只在「用户仍在底部附近」时生效
    var userScrolledUp by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible < layout.totalItemsCount - 2
        }.distinctUntilChanged().collect { userScrolledUp = it }
    }
    // 新日志 / 恢复跟随 / 首次进入：都只走这一条路径，一次刷新最多滚一次。
    // 等一帧再滚，避免和同帧的列表更新抢主线程。
    LaunchedEffect(listState, autoScrollPaused, tailId) {
        // 用户正在拖/惯性滑动时不抢滚动权：否则手指被一次次拽回底部，表现就是「拖不动」。
        if (autoScrollPaused || userScrolledUp || listState.isScrollInProgress) return@LaunchedEffect
        if (renderedLines.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        listState.scrollToItem(renderedLines.lastIndex)
    }
    // 用户点「恢复滚动」时无条件回到底部（此时可能还停在上滑位置）。
    LaunchedEffect(autoScrollPaused) {
        if (!autoScrollPaused && renderedLines.isNotEmpty()) {
            userScrolledUp = false
            listState.scrollToItem(renderedLines.lastIndex)
        }
    }
    if (renderedLines.isEmpty()) {
        EmptyTerminalState(pal = pal, modifier = modifier)
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        items(
            count = renderedLines.size,
            key = { index -> renderedLines[index].id },
            contentType = { "terminal-log" }
        ) { index ->
            val line = renderedLines[index]
            Text(
                remember(line.text) { line.text.forTerminalDisplay() },
                color = pal.colorFor(line.tone),
                style = logTextStyle,
                softWrap = true
            )
        }
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
    // 清屏二次确认：清掉的是本次运行的全部控制台历史（不可恢复），值得拦一道。
    // 放在 TopBar 内部：只有这里的菜单会触发它，状态不需要上提到页面级。
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        com.mineserve.mobile.ui.ConfirmDialog(
            title = stringResource(R.string.tm_clear_session),
            message = stringResource(R.string.tm_clear_confirm_content),
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                onClear()
            }
        )
    }
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
                            // 清屏不可恢复（会话历史即本次运行的唯一现场），加一道二次确认
                            confirmClear = true
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
                    // 关闭按钮单独 32dp 命中区：原先整个会话标签可点关闭，
                    // 想切会话却关掉会话是这里最常见的一次误操作。
                    Box(
                        modifier = Modifier
                            .size(32.dp)
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

// 会话胶囊高度 38dp + 行内 2/10dp 上下留白：点中胶囊任意位置都有 ≥38dp 的命中区，
// 逼近 [McIcon.Touch] 的 40dp 标准而不至于让标签行吃掉太多终端可视高度。
private val SessionChipHeight = 38.dp

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

/** 日志行样式：所有行共用一份，避免每行各建一个 TextStyle 触发重测。 */
private val logTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.5.sp,
    // 显式给出 lineHeight：一条日志自动折行时行距由它决定。
    // 不写会按字体默认值走，多行日志挤成一片，读不出是一句还是两句。
    lineHeight = 16.5.sp
)

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
