package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mineserve.mobile.R
import com.mineserve.mobile.data.AutomationFrequency
import com.mineserve.mobile.data.AutomationTask
import com.mineserve.mobile.data.AutomationTaskType
import com.mineserve.mobile.data.CronExpression
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.EmptyHint
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.SegPill
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.FieldBg
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * 自动化页：管理定时任务（备份 / 重启 / 广播 / 自定义命令 / 开关服）与停止后自动备份。
 *
 * 从「更多」页进入。任务数据存于 [com.mineserve.mobile.data.McConfig.automationTasks]，
 * 由 [com.mineserve.mobile.data.AutomationScheduler] 用 AlarmManager 排期、
 * 前台服务执行。
 */
@Composable
fun AutomationScreen(vm: McViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    val tasks = config.automationTasks
    // editing == null 且 showEditor == true 表示「新建」
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AutomationTask?>(null) }

    Column(Modifier.fillMaxSize()) {
        BackBar(title = stringResource(R.string.automation_title), onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(
                eyebrow = stringResource(R.string.eyebrow_automation),
                title = stringResource(R.string.automation_title),
                statusBarPadding = false
            )

            McCard(
                title = stringResource(R.string.automation_tasks_title),
                trailing = {
                    IconButton(
                        onClick = { editing = null; showEditor = true },
                        modifier = Modifier.size(McIcon.TouchCompact)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.automation_add_task), tint = Indigo)
                    }
                }
            ) {
                Text(stringResource(R.string.automation_tasks_hint), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                if (tasks.isEmpty()) {
                    EmptyHint(Icons.Outlined.Schedule, stringResource(R.string.automation_task_empty))
                } else {
                    tasks.forEach { task ->
                        AutomationTaskCard(
                            task = task,
                            onToggle = { vm.setAutomationTaskEnabled(task.id, it) },
                            onRunNow = { vm.runAutomationTaskNow(task.id) },
                            onEdit = { editing = task; showEditor = true },
                            onDelete = { vm.deleteAutomationTask(task.id) }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { editing = null; showEditor = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                    shape = McShape.Control,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(McIcon.Action))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.automation_add_task), fontSize = 13.sp)
                }
            }

            // 停止后自动备份（原设置页自动化卡片的能力，保留于此）
            McCard(title = stringResource(R.string.set_stop_backup)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.set_stop_backup), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.set_stop_backup_hint), color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = config.stopAutoBackup, onCheckedChange = { vm.setStopAutoBackup(it) })
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showEditor) {
        AutomationTaskEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { task ->
                if (editing == null) vm.addAutomationTask(task) else vm.updateAutomationTask(task)
                showEditor = false
            }
        )
    }
}

// ── 任务卡片 ──────────────────────────────────────────────────────

@Composable
private fun AutomationTaskCard(
    task: AutomationTask,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = taskTypeAccent(task.type)
    Surface(
        color = FieldBg,
        shape = McShape.Row,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(McIcon.TouchCompact)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(taskTypeIcon(task.type), contentDescription = null, tint = accent, modifier = Modifier.size(McIcon.Item))
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(taskTypeLabelRes(task.type)) + " · " + automationFrequencyLabel(task),
                        color = Muted,
                        fontSize = 11.sp
                    )
                    if (task.lastRunMs > 0L) {
                        Text(
                            stringResource(R.string.automation_last_run, formatTimestamp(task.lastRunMs)),
                            color = Muted,
                            fontSize = 10.sp
                        )
                    }
                }
                Switch(checked = task.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CardAction(Icons.Outlined.PlayArrow, stringResource(R.string.automation_run_now), Indigo, onRunNow)
                CardAction(Icons.Outlined.Edit, stringResource(R.string.automation_edit), Indigo, onEdit)
                CardAction(Icons.Outlined.Delete, stringResource(R.string.automation_delete), Coral, onDelete)
            }
        }
    }
}

@Composable
private fun CardAction(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(McIcon.TouchCompact)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(McIcon.Item))
    }
}

// ── 新建 / 编辑对话框 ─────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutomationTaskEditorDialog(
    initial: AutomationTask?,
    onDismiss: () -> Unit,
    onSave: (AutomationTask) -> Unit
) {
    var type by remember { mutableStateOf(initial?.type ?: AutomationTaskType.WorldBackup) }
    val startDefaultName = defaultTaskName(initial?.type ?: AutomationTaskType.WorldBackup)
    var name by remember { mutableStateOf(initial?.name?.takeIf { it.isNotBlank() } ?: startDefaultName) }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var retention by remember { mutableIntStateOf(initial?.retentionCount ?: 5) }
    var frequency by remember { mutableStateOf(initial?.frequency ?: AutomationFrequency.Interval) }
    var intervalText by remember { mutableStateOf((initial?.intervalMinutes ?: 360).toString()) }
    var intervalIsHours by remember { mutableStateOf((initial?.intervalMinutes ?: 360) % 60 == 0) }
    var dayIntervalText by remember { mutableStateOf((initial?.dayInterval ?: 1).toString()) }
    var weekIntervalText by remember { mutableStateOf((initial?.weekInterval ?: 1).toString()) }
    var selectedDays by remember {
        mutableStateOf((initial?.daysOfWeek ?: emptyList()).ifEmpty { listOf(Calendar.SUNDAY) }.toSet())
    }
    var hour by remember { mutableIntStateOf(initial?.hour ?: 4) }
    var minute by remember { mutableIntStateOf(initial?.minute ?: 0) }
    var cronText by remember { mutableStateOf(initial?.cron ?: "0 4 * * *") }
    // 用户是否手动改过任务名；未改过时随动作类型自动更新默认名
    var nameEdited by remember { mutableStateOf(initial != null) }

    val defaultNames = AutomationTaskType.entries.associateWith { defaultTaskName(it) }

    val isBackup = type == AutomationTaskType.WorldBackup || type == AutomationTaskType.FullBackup
    val needsCommand = type == AutomationTaskType.BroadcastMessage || type == AutomationTaskType.CustomCommand
    val cronValid = CronExpression.isValid(cronText)
    val canSave = name.isNotBlank() && (frequency != AutomationFrequency.CustomCron || cronValid)

    // 实时「解析后」预览
    val previewTask = AutomationTask(
        id = initial?.id ?: "preview",
        name = name,
        enabled = initial?.enabled ?: true,
        type = type,
        frequency = frequency,
        intervalMinutes = computedIntervalMinutes(intervalText, intervalIsHours),
        hour = hour,
        minute = minute,
        dayInterval = (dayIntervalText.toIntOrNull() ?: 1).coerceAtLeast(1),
        daysOfWeek = selectedDays.sorted(),
        weekInterval = (weekIntervalText.toIntOrNull() ?: 1).coerceAtLeast(1),
        cron = cronText.trim(),
        command = command.trim(),
        retentionCount = if (isBackup) retention else 0
    )

    val save = {
        if (canSave) {
            onSave(
                previewTask.copy(
                    id = initial?.id ?: UUID.randomUUID().toString().take(8),
                    name = name.trim()
                )
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(start = McSpacing.Page, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(if (initial == null) R.string.automation_new_task else R.string.automation_edit_task),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(McIcon.TouchCompact)) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.automation_cancel))
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 1. 动作
                    McCard(title = stringResource(R.string.automation_section_action)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AutomationTaskType.entries.forEach { t ->
                                SegPill(text = stringResource(taskTypeLabelRes(t)), selected = type == t) {
                                    type = t
                                    if (!nameEdited || name.isBlank()) {
                                        name = defaultNames[t].orEmpty()
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it; nameEdited = true },
                            label = { Text(stringResource(R.string.automation_field_name)) },
                            singleLine = true,
                            shape = McShape.Control,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (needsCommand) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = command,
                                onValueChange = { command = it },
                                label = {
                                    Text(
                                        stringResource(
                                            if (type == AutomationTaskType.BroadcastMessage) R.string.automation_field_command_broadcast
                                            else R.string.automation_field_command
                                        )
                                    )
                                },
                                singleLine = true,
                                shape = McShape.Control,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (isBackup) {
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.automation_retention), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(3, 5, 10, 20, 0).forEach { count ->
                                    SegPill(
                                        text = if (count == 0) stringResource(R.string.automation_keep_all)
                                        else stringResource(R.string.automation_keep_n, count),
                                        selected = retention == count
                                    ) { retention = count }
                                }
                            }
                        }
                    }

                    // 2. 时间
                    McCard(title = stringResource(R.string.automation_section_timing)) {
                        Surface(color = FieldBg, shape = McShape.Row, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = Indigo, modifier = Modifier.size(McIcon.Inline))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.automation_resolved, automationFrequencyLabel(previewTask)),
                                    color = Indigo,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SegPill(text = stringResource(R.string.automation_freq_interval), selected = frequency == AutomationFrequency.Interval) { frequency = AutomationFrequency.Interval }
                            SegPill(text = stringResource(R.string.automation_freq_days), selected = frequency == AutomationFrequency.EveryXDays) { frequency = AutomationFrequency.EveryXDays }
                            SegPill(text = stringResource(R.string.automation_freq_weekly), selected = frequency == AutomationFrequency.WeeklyDays) { frequency = AutomationFrequency.WeeklyDays }
                            SegPill(text = stringResource(R.string.automation_freq_cron), selected = frequency == AutomationFrequency.CustomCron) { frequency = AutomationFrequency.CustomCron }
                        }
                        Spacer(Modifier.height(10.dp))

                        when (frequency) {
                            AutomationFrequency.Interval -> {
                                Text(stringResource(R.string.automation_repeat_every), color = Muted, fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = intervalText,
                                        onValueChange = { intervalText = it.filter(Char::isDigit) },
                                        label = { Text(stringResource(R.string.automation_value)) },
                                        singleLine = true,
                                        shape = McShape.Control,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.width(120.dp)
                                    )
                                    SegPill(text = stringResource(R.string.automation_unit_minutes), selected = !intervalIsHours) { intervalIsHours = false }
                                    SegPill(text = stringResource(R.string.automation_unit_hours), selected = intervalIsHours) { intervalIsHours = true }
                                }
                                Spacer(Modifier.height(8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("15m" to 15, "30m" to 30, "1h" to 60, "2h" to 120, "4h" to 240, "6h" to 360, "12h" to 720, "24h" to 1440).forEach { (label, mins) ->
                                        SegPill(text = label, selected = computedIntervalMinutes(intervalText, intervalIsHours) == mins) {
                                            intervalIsHours = mins % 60 == 0
                                            intervalText = (if (mins % 60 == 0) mins / 60 else mins).toString()
                                        }
                                    }
                                }
                            }

                            AutomationFrequency.EveryXDays -> {
                                Text(stringResource(R.string.automation_day_interval), color = Muted, fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = dayIntervalText,
                                        onValueChange = { dayIntervalText = it.filter(Char::isDigit) },
                                        label = { Text(stringResource(R.string.automation_every_n_days)) },
                                        singleLine = true,
                                        shape = McShape.Control,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.width(150.dp)
                                    )
                                    SegPill(text = stringResource(R.string.automation_daily), selected = dayIntervalText == "1") { dayIntervalText = "1" }
                                    SegPill(text = "2", selected = dayIntervalText == "2") { dayIntervalText = "2" }
                                }
                                TimeOfDayPicker(hour, minute) { h, m -> hour = h; minute = m }
                            }

                            AutomationFrequency.WeeklyDays -> {
                                Text(stringResource(R.string.automation_days_of_week), color = Muted, fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    (Calendar.SUNDAY..Calendar.SATURDAY).forEach { day ->
                                        SegPill(text = dayShortName(day), selected = selectedDays.contains(day)) {
                                            selectedDays = if (selectedDays.contains(day)) {
                                                if (selectedDays.size > 1) selectedDays - day else selectedDays
                                            } else selectedDays + day
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SegPill(text = stringResource(R.string.automation_weekends), selected = selectedDays == setOf(Calendar.SATURDAY, Calendar.SUNDAY)) {
                                        selectedDays = setOf(Calendar.SATURDAY, Calendar.SUNDAY)
                                    }
                                    SegPill(
                                        text = stringResource(R.string.automation_weekdays),
                                        selected = selectedDays == setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
                                    ) {
                                        selectedDays = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(stringResource(R.string.automation_week_cadence), color = Muted, fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = weekIntervalText,
                                        onValueChange = { weekIntervalText = it.filter(Char::isDigit) },
                                        label = { Text(stringResource(R.string.automation_every_n_weeks)) },
                                        singleLine = true,
                                        shape = McShape.Control,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.width(150.dp)
                                    )
                                    SegPill(text = stringResource(R.string.automation_weekly), selected = weekIntervalText == "1") { weekIntervalText = "1" }
                                    SegPill(text = stringResource(R.string.automation_biweekly), selected = weekIntervalText == "2") { weekIntervalText = "2" }
                                }
                                TimeOfDayPicker(hour, minute) { h, m -> hour = h; minute = m }
                            }

                            AutomationFrequency.CustomCron -> {
                                Text(stringResource(R.string.automation_cron_expr), color = Muted, fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = cronText,
                                    onValueChange = { cronText = it },
                                    singleLine = true,
                                    shape = McShape.Control,
                                    placeholder = { Text("0 4 * * *") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(if (cronValid) R.string.automation_cron_valid else R.string.automation_cron_invalid),
                                    color = if (cronValid) Mint else Coral,
                                    fontSize = 11.sp
                                )
                                Spacer(Modifier.height(8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(
                                        "*/30" to "*/30 * * * *",
                                        "4:00" to "0 4 * * *",
                                        "Sun4" to "0 4 * * 0",
                                        "4&16" to "0 4,16 * * *",
                                        "Mon-Fri 2" to "0 2 * * 1-5"
                                    ).forEach { (label, expr) ->
                                        SegPill(text = label, selected = cronText.trim() == expr) { cronText = expr }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = McSpacing.Page, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(onClick = onDismiss, shape = McShape.Control, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.automation_cancel))
                    }
                    Button(
                        onClick = save,
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = McShape.Control,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.automation_save))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeOfDayPicker(hour: Int, minute: Int, onChange: (Int, Int) -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.automation_time), color = Muted, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(2 to 0, 4 to 0, 6 to 0, 12 to 0, 23 to 0).forEach { (h, m) ->
            SegPill(text = formatTime(h, m), selected = hour == h && minute == m) { onChange(h, m) }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = hour.toString().padStart(2, '0'),
            onValueChange = { v -> v.toIntOrNull()?.let { onChange(it.coerceIn(0, 23), minute) } },
            label = { Text("HH") },
            singleLine = true,
            shape = McShape.Control,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(90.dp)
        )
        OutlinedTextField(
            value = minute.toString().padStart(2, '0'),
            onValueChange = { v -> v.toIntOrNull()?.let { onChange(hour, it.coerceIn(0, 59)) } },
            label = { Text("mm") },
            singleLine = true,
            shape = McShape.Control,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(90.dp)
        )
    }
}

// ── 文案 / 映射工具 ───────────────────────────────────────────────

@Composable
private fun automationFrequencyLabel(task: AutomationTask): String = when (task.frequency) {
    AutomationFrequency.Interval -> {
        val mins = task.intervalMinutes.coerceAtLeast(1)
        if (mins >= 60 && mins % 60 == 0) {
            stringResource(R.string.auto_desc_interval_hours, mins / 60)
        } else {
            stringResource(R.string.auto_desc_interval_minutes, mins)
        }
    }

    AutomationFrequency.EveryXDays -> {
        val time = formatTime(task.hour, task.minute)
        if (task.dayInterval <= 1) stringResource(R.string.auto_desc_daily, time)
        else stringResource(R.string.auto_desc_every_n_days, task.dayInterval, time)
    }

    AutomationFrequency.WeeklyDays -> {
        val time = formatTime(task.hour, task.minute)
        val days = task.daysOfWeek.ifEmpty { listOf(Calendar.SUNDAY) }
            .sorted()
            .map { dayShortName(it) }
            .joinToString(", ")
        if (task.weekInterval <= 1) stringResource(R.string.auto_desc_weekly, days, time)
        else stringResource(R.string.auto_desc_every_n_weeks, task.weekInterval, days, time)
    }

    AutomationFrequency.CustomCron ->
        if (task.cron.isBlank()) stringResource(R.string.automation_freq_cron)
        else stringResource(R.string.auto_desc_cron, task.cron)
}

@Composable
private fun dayShortName(day: Int): String = stringResource(
    when (day) {
        Calendar.MONDAY -> R.string.auto_day_mon
        Calendar.TUESDAY -> R.string.auto_day_tue
        Calendar.WEDNESDAY -> R.string.auto_day_wed
        Calendar.THURSDAY -> R.string.auto_day_thu
        Calendar.FRIDAY -> R.string.auto_day_fri
        Calendar.SATURDAY -> R.string.auto_day_sat
        else -> R.string.auto_day_sun
    }
)

private fun taskTypeLabelRes(type: AutomationTaskType): Int = when (type) {
    AutomationTaskType.StartServer -> R.string.auto_type_start
    AutomationTaskType.StopServer -> R.string.auto_type_stop
    AutomationTaskType.WorldBackup -> R.string.auto_type_world_backup
    AutomationTaskType.FullBackup -> R.string.auto_type_full_backup
    AutomationTaskType.RestartServer -> R.string.auto_type_restart
    AutomationTaskType.BroadcastMessage -> R.string.auto_type_broadcast
    AutomationTaskType.CustomCommand -> R.string.auto_type_command
}

private fun taskTypeIcon(type: AutomationTaskType): ImageVector = when (type) {
    AutomationTaskType.StartServer -> Icons.Outlined.PlayArrow
    AutomationTaskType.StopServer -> Icons.Outlined.PowerSettingsNew
    AutomationTaskType.WorldBackup, AutomationTaskType.FullBackup -> Icons.Outlined.Archive
    AutomationTaskType.RestartServer -> Icons.Outlined.RestartAlt
    AutomationTaskType.BroadcastMessage -> Icons.Outlined.Campaign
    AutomationTaskType.CustomCommand -> Icons.Outlined.Terminal
}

private fun taskTypeAccent(type: AutomationTaskType): Color = when (type) {
    AutomationTaskType.StartServer -> Mint
    AutomationTaskType.StopServer -> Coral
    AutomationTaskType.RestartServer -> Coral
    AutomationTaskType.WorldBackup, AutomationTaskType.FullBackup -> Mint
    AutomationTaskType.BroadcastMessage, AutomationTaskType.CustomCommand -> Indigo
}

@Composable
private fun defaultTaskName(type: AutomationTaskType): String = stringResource(
    when (type) {
        AutomationTaskType.StartServer -> R.string.auto_type_start
        AutomationTaskType.StopServer -> R.string.auto_type_stop
        AutomationTaskType.WorldBackup -> R.string.auto_type_world_backup
        AutomationTaskType.FullBackup -> R.string.auto_type_full_backup
        AutomationTaskType.RestartServer -> R.string.auto_type_restart
        AutomationTaskType.BroadcastMessage -> R.string.auto_type_broadcast
        AutomationTaskType.CustomCommand -> R.string.auto_type_command
    }
)

private fun computedIntervalMinutes(text: String, isHours: Boolean): Int {
    val value = (text.toIntOrNull() ?: 1).coerceAtLeast(1)
    return if (isHours) value * 60 else value
}

private fun formatTime(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

private fun formatTimestamp(ms: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(ms))
