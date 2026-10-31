package com.mineserve.mobile.ui.screens

// 性能修改理由：终端列表保留复用能力，并缓存行样式和截断超长显示文本以降低测量开销。
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.TerminalDisplayLine
import com.mineserve.mobile.ui.TerminalLogTone
import com.mineserve.mobile.ui.TerminalSessionType

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
    val sessions by vm.terminalSessions.collectAsState()
    val activeId by vm.activeTerminalSessionId.collectAsState()
    val active = sessions.firstOrNull { it.id == activeId } ?: sessions.first()
    val mcLines by vm.terminalConsoleLines.collectAsState()
    val context = LocalContext.current
    var input by remember(activeId) { mutableStateOf("") }
    val logPrefs = remember { context.getSharedPreferences("log_prefs", Context.MODE_PRIVATE) }
    var translateLogs by remember { mutableStateOf(logPrefs.getBoolean("localize", true)) }
    var showSettings by remember { mutableStateOf(false) }
    // 日志汉化只对中文界面有意义，其它语言下隐藏入口并强制关闭翻译
    val isChineseUi = java.util.Locale.getDefault().language == "zh"
    var autoScrollPaused by rememberSaveable { mutableStateOf(false) }
    var pendingDangerousCommand by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val rawLines = if (active.type == TerminalSessionType.Minecraft) mcLines else active.lines
    LaunchedEffect(Unit) {
        vm.setLogTranslationEnabled(translateLogs && isChineseUi)
        vm.loadRecentTerminalLog()
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
    // 新日志到达时，若用户未手动上滑，自动滚动到底部
    LaunchedEffect(rawLines.lastOrNull()) {
        if (renderedLines.isNotEmpty() && !userScrolledUp && !autoScrollPaused) {
            listState.scrollToItem(renderedLines.lastIndex, Int.MAX_VALUE)
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
    Scaffold(
        containerColor = Color(0xFF121419),
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            // Kept in this screen's bottom slot so the app navigation bar and
            // IME cannot overlay the command field or its send action.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121419))
                    .imePadding()
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (active.type == TerminalSessionType.Minecraft) ">" else "$",
                    color = Color(0xFF57E357),
                    fontFamily = FontFamily.Monospace
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF3996FF),
                        unfocusedBorderColor = Color(0xFF596273),
                        cursorColor = Color(0xFF57E357)
                    )
                )
                IconButton(
                    onClick = sendInput,
                    enabled = input.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF3996FF), RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.tm_send), tint = Color.White)
                }
            }
        }
    ) { terminalPadding ->
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF121419))
            .statusBarsPadding()
            .padding(terminalPadding)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.tm_title), color = Color(0xFFE8E8E8), fontSize = 22.sp, fontFamily = FontFamily.Monospace)
            Row {
                if (isChineseUi) IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, stringResource(R.string.tm_settings), tint = Color.White) }
                IconButton(onClick = {
                    if (active.type == TerminalSessionType.Minecraft) vm.clearConsole()
                    else vm.clearTerminalSession(active.id)
                }) { Icon(Icons.Outlined.Delete, stringResource(R.string.tm_clear_session), tint = Color(0xFFFFA7A7)) }
                IconButton(onClick = { autoScrollPaused = !autoScrollPaused }) {
                    Icon(
                        if (autoScrollPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        if (autoScrollPaused) stringResource(R.string.tm_resume_scroll) else stringResource(R.string.tm_pause_scroll),
                        tint = if (autoScrollPaused) Color(0xFFFFC857) else Color.White
                    )
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(active.name, rawLines.joinToString("\n") { it.text }))
                    android.widget.Toast.makeText(context, context.getString(R.string.tm_copied), android.widget.Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.tm_copy_log), tint = Color.White) }
                IconButton(onClick = vm::createTerminalSession) { Icon(Icons.Outlined.Add, stringResource(R.string.tm_new_session), tint = Color.White) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sessions.forEach { session ->
                Row(Modifier.background(if (session.id == activeId) Color(0xFF2B313C) else Color(0xFF20242C), RoundedCornerShape(12.dp)).padding(start = 12.dp), horizontalArrangement = Arrangement.Center) {
                    Text(session.name, color = Color(0xFFE8E8E8), fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp).clickable { vm.selectTerminalSession(session.id) })
                    if (session.type == TerminalSessionType.Termux) IconButton(onClick = { vm.closeTerminalSession(session.id) }) { Icon(Icons.Outlined.Close, stringResource(R.string.tm_close), tint = Color(0xFF999999)) } else Spacer(Modifier.width(8.dp))
                }
            }
        }
        if (active.type == TerminalSessionType.Minecraft) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quickMcCommands.forEach { item ->
                    AssistChip(
                        onClick = { sendCommand(item.command) },
                        label = { Text("${item.emoji} ${stringResource(item.labelRes)}", fontSize = 11.sp) }
                    )
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(
                renderedLines,
                key = { _, line -> line.id },
                contentType = { _, _ -> "terminal-log" }
            ) { _, line ->
                val style = terminalLogStyles.getValue(line.tone)
                val displayText = remember(line.text) { line.text.forTerminalDisplay() }
                Text(
                    displayText,
                    color = style.color,
                    fontSize = 11.sp,
                    fontFamily = terminalLogFontFamily,
                    softWrap = true
                )
            }
        }
    }
    }
    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        title = { Text(stringResource(R.string.tm_settings)) },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.tm_localize))
                Switch(
                    checked = translateLogs,
                    onCheckedChange = {
                        translateLogs = it
                        logPrefs.edit().putBoolean("localize", it).apply()
                        vm.setLogTranslationEnabled(it && isChineseUi)
                    }
                )
            }
        },
        confirmButton = { TextButton(onClick = { showSettings = false }) { Text(stringResource(R.string.tm_done)) } }
    )
    pendingDangerousCommand?.let { command ->
        AlertDialog(
            onDismissRequest = { pendingDangerousCommand = null },
            title = { Text(stringResource(R.string.tm_danger_title), color = Color(0xFFFF7B72)) },
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
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF7B72))
                ) { Text(stringResource(R.string.tm_danger_confirm)) }
            }
        )
    }
}

private const val MAX_RENDERED_LINES = 300
private const val MAX_DISPLAY_LOG_CHARS = 400

private data class TerminalLogStyle(val color: Color)

private val terminalLogFontFamily = FontFamily.Monospace
private val terminalLogStyles = mapOf(
    TerminalLogTone.Command to TerminalLogStyle(Color(0xFF57E357)),
    TerminalLogTone.Error to TerminalLogStyle(Color(0xFFFF7B72)),
    TerminalLogTone.Warning to TerminalLogStyle(Color(0xFFE3B341)),
    TerminalLogTone.Info to TerminalLogStyle(Color(0xFF8BD5CA)),
    TerminalLogTone.Debug to TerminalLogStyle(Color(0xFF82AAFF)),
    TerminalLogTone.Download to TerminalLogStyle(Color(0xFF89B4FA)),
    TerminalLogTone.Success to TerminalLogStyle(Color(0xFFA6E3A1)),
    TerminalLogTone.Default to TerminalLogStyle(Color(0xFFE8E8E8))
)

private fun String.forTerminalDisplay(): String =
    if (length > MAX_DISPLAY_LOG_CHARS) take(MAX_DISPLAY_LOG_CHARS) + "..." else this
