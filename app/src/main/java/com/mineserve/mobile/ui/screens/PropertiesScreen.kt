package com.mineserve.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.server.PropertyCatalog
import com.mineserve.mobile.server.PropertyFlavor
import com.mineserve.mobile.server.PropertyGroup
import com.mineserve.mobile.server.PropertyOption
import com.mineserve.mobile.server.PropertyUiType
import com.mineserve.mobile.server.ResolvedProperty
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.DebouncedTextField
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.SegPill
import com.mineserve.mobile.ui.theme.Bg
import com.mineserve.mobile.ui.theme.Card
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.FieldGray
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Line
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Muted
import kotlin.math.roundToInt

/**
 * 服务器属性页。
 *
 * **动态生成**：结构完全来自当前核心目录下 `server.properties`（PNX 为 `pnx.yml`）
 * 里真实存在的键值对，字典 [PropertyCatalog] 只决定「怎么显示」。任何核心
 * （Paper / Purpur / Fabric / Vanilla / BDS / PocketMine / 带 Mod 的自定义核心）
 * 都能开箱即用，字典没收录的键落到底部「未收录配置」，按原始 key 渲染。
 *
 * **无损保存**：只提交值真正变过的键，合并逻辑在
 * `ServerPropertiesManager.writeProperties` —— 注释、空行、行序、未改动的键都不动。
 *
 * 版式（2026-10 按真机反馈收紧）：上一版顶部叠了「返回设置 / 服务器配置 /
 * 服务器属性 / 当前核心…」四层同义文字，MOTD 卡又吃掉近半屏，首屏几乎看不到
 * 真正要调的设置。现在：
 * 1. 顶栏一行：返回箭头 + 标题 + 「核心 · 条目数」胶囊，搜索框与顶栏共用一块白底
 * 2. 展示信息卡：输入框 + 一行横滑色板 + 服务器列表条目预览，高度压到原来一半
 * 3. P0–P3 分组，行内不再画分隔线，靠留白分组
 * 4. 底部 dock：左状态右按钮，错误原因写回对应行而不是横跨整宽的红字
 */
@Composable
fun PropertiesScreen(vm: McViewModel, onBack: () -> Unit, showBackBar: Boolean = true) {
    val config by vm.config.collectAsState()
    val active = config.installedCores.firstOrNull { it.name == config.activeCoreName }
    if (active == null) {
        PropertiesEmptyScreen(onBack, showBackBar)
        return
    }
    DynamicPropertiesScreen(
        vm = vm,
        core = active.core,
        coreName = active.name,
        onBack = onBack,
        showBackBar = showBackBar
    )
}

@Composable
private fun DynamicPropertiesScreen(
    vm: McViewModel,
    core: ServerCore,
    coreName: String,
    onBack: () -> Unit,
    showBackBar: Boolean
) {
    val loaded by vm.serverProperties.collectAsState()
    val propertiesExist by vm.serverPropertiesExist.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val context = LocalContext.current

    val flavor = remember(core) { PropertyFlavor.of(core) }
    val sourceName = if (core == ServerCore.PowerNukkitX) "pnx.yml" else "server.properties"

    // 编辑态：original 是磁盘上的值（撤销的基准），values 是当前编辑值。
    // 保存成功后 VM 推回合并后的新值，LaunchedEffect 顺势把两者重置成同一份，
    // 「已修改」标记因此自动清空，不需要额外的保存回调。
    var original by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadServerProperties() }
    LaunchedEffect(loaded) {
        original = loaded
        values = loaded
    }

    val rows = remember(values, flavor) { PropertyCatalog.buildRows(values, flavor) }
    val changed = remember(values, original) { PropertyCatalog.diff(values, original) }
    val dirty = changed.isNotEmpty()

    val trimmedQuery = query.trim()
    val filtered = remember(rows, trimmedQuery) {
        if (trimmedQuery.isEmpty()) rows else rows.filter { row ->
            val title = if (row.meta.titleRes != 0) context.getString(row.meta.titleRes) else row.key
            val desc = if (row.meta.descRes != 0) context.getString(row.meta.descRes) else ""
            title.contains(trimmedQuery, ignoreCase = true) ||
                row.key.contains(trimmedQuery, ignoreCase = true) ||
                desc.contains(trimmedQuery, ignoreCase = true)
        }
    }
    val heroRows = remember(filtered) { PropertyCatalog.heroRows(filtered) }
    val grouped = remember(filtered) { PropertyCatalog.groupedRows(filtered) }
    val sections = remember(grouped) {
        PropertyGroup.values().map { group -> group to grouped.filter { it.meta.group == group } }
            .filter { it.second.isNotEmpty() }
    }

    val guardBack: () -> Unit = { if (dirty) showLeaveDialog = true else onBack() }
    BackHandler(enabled = dirty) { showLeaveDialog = true }

    fun edit(key: String, value: String) {
        values = values.toMutableMap().apply { put(key, value) }
    }

    fun revert(key: String) {
        val base = original[key] ?: return
        values = values.toMutableMap().apply { put(key, base) }
    }

    // 折叠状态：只记「被用户折叠的组」。默认展开规则跟以前一致（P3 超过 6 项收起）；
    // 搜索时一律展开 —— 折叠状态下命中项藏在卡里，看着像没搜到。
    // 拆成切片后折叠不能再交给 McCard 自己管（卡片已经不存在了），所以提到这里。
    var collapsedGroups by rememberSaveable { mutableStateOf(listOf<String>()) }
    fun isGroupExpanded(group: PropertyGroup, size: Int): Boolean = when {
        trimmedQuery.isNotEmpty() -> true
        group.name in collapsedGroups -> false
        else -> group != PropertyGroup.P3 || size <= 6
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // ── 顶栏 + 搜索：同一块白底 ──
        Column(Modifier.fillMaxWidth().background(Card)) {
            if (showBackBar) {
                BackBar(
                    title = stringResource(R.string.s997),
                    onBack = guardBack,
                    trailing = {
                        ScopeChip(
                            text = coreName,
                            count = if (trimmedQuery.isEmpty()) rows.size else filtered.size
                        )
                    }
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = McSpacing.Page, end = McSpacing.Page, top = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.s997),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.weight(1f)
                        )
                        ScopeChip(
                            text = coreName,
                            count = if (trimmedQuery.isEmpty()) rows.size else filtered.size
                        )
                    }
                }
            }
            SearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.prop_ui_search),
                modifier = Modifier.padding(
                    start = McSpacing.Page,
                    end = McSpacing.Page,
                    top = 2.dp,
                    bottom = 10.dp
                )
            )
        }

        if (propertiesExist == false) {
            NotGeneratedBody(
                coreName = coreName,
                sourceName = sourceName,
                isRunning = serverState.isRunning,
                vm = vm
            )
            return@Column
        }

        if (propertiesExist == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Indigo, strokeWidth = 2.dp)
            }
        } else {
            // 内容 + 悬浮保存按钮：按钮浮在列表之上，不占底部一整条
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 底部留 96dp，最后一项不会被悬浮保存按钮压住
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    if (heroRows.isNotEmpty()) {
                        item(key = "hero", contentType = "hero") {
                            heroRows.forEach { row ->
                                MotdHeroCard(
                                    title = if (row.meta.titleRes != 0) stringResource(row.meta.titleRes) else row.key,
                                    key = row.key,
                                    value = values[row.key] ?: row.meta.defaultValue,
                                    modified = changed.containsKey(row.key),
                                    maxPlayers = values["max-players"]?.toIntOrNull() ?: 20,
                                    onValueChange = { edit(row.key, it) },
                                    onRevert = { revert(row.key) }
                                )
                            }
                        }
                    }

                    /*
                     * 分组不再是一整张卡，而是切成「组头 + 提示 + 每行」若干 Lazy item。
                     *
                     * 滑动卡顿的根因就在这里：原来一个分组就是一个 item，卡片内部
                     * `forEachIndexed` 把所有行一次性组合。P0 组分动辄三十多行，每行还带
                     * 输入框 / 滑块 / 分段控件，等于每滚过一屏就要组合几百个 Composable，
                     * 而且是「一张卡」这个不可拆分单元，只露出一半也得整卡组合+测量。
                     *
                     * 现在每行独立成 item，Lazy 只组合屏幕上那 6~8 行。视觉上靠连续白底
                     * 拼回一张卡：组头画上圆角，最后一行画下圆角，中间的行只有左右边距，
                     * 相接处无缝、也不画分隔线。
                     */
                    sections.forEach { (group, groupRows) ->
                        val expanded = isGroupExpanded(group, groupRows.size)
                        item(key = "sec_hdr_${group.name}", contentType = "section_header") {
                            SectionHeaderSlice(
                                title = stringResource(group.labelRes),
                                count = groupRows.size,
                                expanded = expanded,
                                collapsible = trimmedQuery.isEmpty(),
                                onToggle = {
                                    collapsedGroups = if (expanded) {
                                        collapsedGroups + group.name
                                    } else {
                                        collapsedGroups - group.name
                                    }
                                }
                            )
                        }
                        if (expanded) {
                            if (group == PropertyGroup.P3) {
                                item(key = "sec_hint_${group.name}", contentType = "section_hint") {
                                    SectionHintSlice(stringResource(R.string.prop_ui_p3_hint))
                                }
                            }
                            groupRows.forEachIndexed { index, row ->
                                item(key = row.key, contentType = "row") {
                                    PropertyRowSlice(last = index == groupRows.lastIndex) {
                                        PropertyRow(
                                            row = row,
                                            value = values[row.key] ?: row.meta.defaultValue,
                                            modified = original[row.key] != values[row.key],
                                            onValueChange = { v -> edit(row.key, v) },
                                            onRevert = { revert(row.key) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (values.isEmpty()) {
                        item(key = "empty_file") {
                            EmptyNotice(stringResource(R.string.prop_ui_empty_file))
                        }
                    } else if (filtered.isEmpty()) {
                        item(key = "empty_search") {
                            EmptyNotice(stringResource(R.string.prop_ui_no_match, trimmedQuery))
                        }
                    }
                }

                SaveFab(
                    enabled = dirty,
                    onClick = {
                        if (serverState.isRunning) showRestartDialog = true
                        else vm.saveServerProperties(changed)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 20.dp)
                )
            }
        }
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text(stringResource(R.string.s581), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.s1001)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    vm.saveServerProperties(changed)
                }) { Text(stringResource(R.string.s1002), color = Indigo) }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text(stringResource(R.string.s402), color = Coral)
                }
            }
        )
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.prop_ui_unsaved_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.prop_ui_unsaved_body, changed.size)) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveDialog = false
                    onBack()
                }) { Text(stringResource(R.string.prop_ui_leave), color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text(stringResource(R.string.prop_ui_stay), color = Indigo)
                }
            }
        )
    }
}

// ── 顶栏配件 ──────────────────────────────────────────────────────

/** 「核心 · 条目数」胶囊：作用域信息一行说完，不再单独占两行 10sp 灰字。 */
@Composable
private fun ScopeChip(text: String, count: Int) {
    Row(
        modifier = Modifier
            .clip(McShape.Small)
            .background(FieldGray)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 92.dp)
        )
        Text(" · ", fontSize = 10.sp, color = Muted)
        Text(
            stringResource(R.string.prop_ui_count, count),
            fontSize = 10.sp,
            color = Muted
        )
    }
}

/**
 * 搜索条：自绘而不是 OutlinedTextField。
 *
 * 描边输入框放在白底顶栏上会「白框贴白底」，只剩一条细线支撑边界；填充式搜索条
 * （FieldGray 底、无描边、42dp 高）在白底和灰底上都干净，也省掉 M3 默认的 56dp 高度。
 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(McShape.ControlCompact)
            .background(FieldGray)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = Muted,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(placeholder, color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(Indigo),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.s402),
                tint = Muted,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onQueryChange("") }
            )
        }
    }
}

// ── 分组切片 ──────────────────────────────────────────────────────
//
// 分组卡被拆成三段：组头（上圆角）、可选的组内提示、每行一块（末行下圆角）。
// 三段的左右边距都是 McSpacing.Page，中间不留间隙，白底相接就拼成一张卡。
// 之所以不用「一个 item 放整组」，是因为那样滚动时整组要一次性组合（见 LazyColumn
// 处的注释）；这三段各自都是独立 item，Lazy 只组合屏幕上可见的那几条。

private val CardRadius = 18.dp

/** 组头：标题 + 条目数 + 展开箭头。折叠时就它自己一张完整小卡。 */
@Composable
private fun SectionHeaderSlice(
    title: String,
    count: Int,
    expanded: Boolean,
    collapsible: Boolean,
    onToggle: () -> Unit
) {
    val shape = if (expanded) {
        RoundedCornerShape(topStart = CardRadius, topEnd = CardRadius)
    } else {
        McShape.Card
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = McSpacing.Page)
            // 组间距画在白底外面：放进去会在卡上方多出一条浅色带
            .padding(top = McSpacing.CardGap)
            .clip(shape)
            .background(Card)
            .then(if (collapsible) Modifier.clickable { onToggle() } else Modifier)
            .padding(horizontal = McSpacing.CardInnerCompact, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(stringResource(R.string.prop_ui_count, count), color = Muted, fontSize = 10.sp)
        if (collapsible) {
            Spacer(Modifier.width(2.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.diag_crash_collapse else R.string.s623
                ),
                tint = Muted,
                modifier = Modifier.size(McIcon.Item)
            )
        }
    }
}

/** 组内提示（目前只有 P3「工作台会原样写回」那句），承接组头的白底。 */
@Composable
private fun SectionHintSlice(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = McSpacing.Page)
            .background(Card)
            .padding(horizontal = McSpacing.CardInnerCompact)
            .padding(top = 2.dp)
    ) {
        Text(text, color = Muted, fontSize = 10.sp)
    }
}

/**
 * 单行切片，[last] 为真时补下圆角与底部留白，闭合整张卡。
 *
 * 顶部一律直角：上圆角由组头负责，行自己再画一次会在卡中间出现接缝。
 */
@Composable
private fun PropertyRowSlice(last: Boolean, content: @Composable () -> Unit) {
    val shape = if (last) {
        RoundedCornerShape(bottomStart = CardRadius, bottomEnd = CardRadius)
    } else {
        RoundedCornerShape(0.dp)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = McSpacing.Page)
            .clip(shape)
            .background(Card)
            .padding(horizontal = McSpacing.CardInnerCompact)
            .padding(top = 8.dp, bottom = if (last) 12.dp else 8.dp)
    ) {
        content()
    }
}

// ── 单行分发 ──────────────────────────────────────────────────────

@Composable
private fun PropertyRow(
    row: ResolvedProperty,
    value: String,
    modified: Boolean,
    onValueChange: (String) -> Unit,
    onRevert: () -> Unit
) {
    val title = if (row.meta.titleRes != 0) stringResource(row.meta.titleRes) else row.key
    val desc = if (row.meta.descRes != 0) stringResource(row.meta.descRes) else ""

    when (row.uiType) {
        PropertyUiType.Toggle -> PropertyHead(
            title = title,
            key = row.key,
            desc = desc,
            known = row.known,
            modified = modified,
            onRevert = onRevert,
            trailing = {
                PropertySwitch(
                    checked = value.trim().equals("true", true) ||
                        value.trim().equals("on", true) ||
                        value.trim().equals("yes", true),
                    onCheckedChange = onValueChange
                )
            }
        )

        PropertyUiType.Segmented -> {
            PropertyHead(title, row.key, desc, row.known, modified, onRevert)
            Spacer(Modifier.height(7.dp))
            SegmentedControl(row.options, value, onValueChange)
        }

        PropertyUiType.Slider -> {
            val range = row.range
            if (range == null) {
                PropertyHead(title, row.key, desc, row.known, modified, onRevert)
                Spacer(Modifier.height(6.dp))
                NumberInput(value, decimal = false, onValueChange = onValueChange)
            } else {
                val current = (value.toIntOrNull() ?: range.first).coerceIn(range.first, range.last)
                // 拖动走本地状态：每移动一格就往 values 里写，整页会跟着重组，上百行明显卡。
                var draft by remember(row.key, value) { mutableStateOf(current.toFloat()) }
                PropertyHead(
                    title = title,
                    key = row.key,
                    desc = desc,
                    known = row.known,
                    modified = modified,
                    onRevert = onRevert,
                    trailing = {
                        Text(
                            draft.roundToInt().toString(),
                            color = Indigo,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
                Slider(
                    value = draft,
                    onValueChange = { draft = it },
                    onValueChangeFinished = { onValueChange(draft.roundToInt().toString()) },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = (range.last - range.first - 1).coerceAtLeast(0),
                    colors = SliderDefaults.colors(
                        thumbColor = Indigo,
                        activeTrackColor = Indigo,
                        inactiveTrackColor = FieldGray
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(range.first.toString(), color = Muted, fontSize = 9.sp)
                    Text(range.last.toString(), color = Muted, fontSize = 9.sp)
                }
            }
        }

        PropertyUiType.Port -> {
            PropertyHead(title, row.key, desc, row.known, modified, onRevert)
            Spacer(Modifier.height(6.dp))
            NumberInput(
                value = value,
                decimal = false,
                maxLength = 5,
                onValueChange = onValueChange
            )
        }

        PropertyUiType.Number -> {
            PropertyHead(title, row.key, desc, row.known, modified, onRevert)
            Spacer(Modifier.height(6.dp))
            NumberInput(value, decimal = row.decimal, onValueChange = onValueChange)
        }

        PropertyUiType.Multiline -> {
            PropertyHead(title, row.key, desc, row.known, modified, onRevert)
            Spacer(Modifier.height(6.dp))
            DebouncedTextField(
                value = value,
                onValueChange = onValueChange,
                minLines = 2,
                maxLines = 6,
                textStyle = TextStyle(fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        PropertyUiType.Text -> {
            PropertyHead(title, row.key, desc, row.known, modified, onRevert)
            Spacer(Modifier.height(6.dp))
            DebouncedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 行标题区：标题 + 原始 key 同一行，说明下一行。
 *
 * key 常显是刻意的 —— 用户对着 Wiki / 论坛排错时看的是 `view-distance` 这种英文键名。
 * 但让 key 单独占一行会给每行多加 14dp，70 项就是两千像素的纯浪费，所以改成跟在
 * 标题后面用 9.5sp 等宽灰字。改过的行只在标题后挂一个小圆点 + 右侧「撤销」。
 */
@Composable
private fun PropertyHead(
    title: String,
    key: String,
    desc: String,
    known: Boolean,
    modified: Boolean,
    onRevert: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    key,
                    color = Muted,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!known) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.prop_ui_unknown),
                        color = Coral,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .border(1.dp, Coral.copy(alpha = 0.4f), McShape.Small)
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                }
                if (modified) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(6.dp).background(Indigo, CircleShape))
                }
            }
            if (desc.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(desc, color = Muted, fontSize = 11.sp, lineHeight = 14.5.sp)
            }
        }
        if (modified) {
            Text(
                stringResource(R.string.prop_ui_revert),
                color = Indigo,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(McShape.Small)
                    .clickable { onRevert() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        trailing?.invoke()
    }
}

/**
 * 开关：自绘 40×22。
 *
 * Material3 Switch 的未选中态是「深灰轨道 + 小圆点」，在这页白卡里的视觉重量比旁边
 * 13sp 的标题还大；缩到 40dp、只用 FieldGray / Indigo 两档色，才和胶囊、滑块是同一套
 * 语汇。语义角色仍声明为 Switch，读屏照常播报。
 */
@Composable
private fun PropertySwitch(checked: Boolean, onCheckedChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(CircleShape)
            .background(if (checked) Indigo else FieldGray)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { onCheckedChange(it.toString()) }
            )
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(18.dp).background(Color.White, CircleShape))
    }
}

// ── 控件 ──────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentedControl(
    options: List<PropertyOption>,
    value: String,
    onValueChange: (String) -> Unit
) {
    val matched = options.any { it.matches(value) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            SegPill(
                text = stringResource(option.labelRes),
                selected = option.matches(value),
                compact = true,
                onClick = { onValueChange(option.value) }
            )
        }
        // 当前值不在候选表里（旧版本写法或该核心独有）：把原值挂成一颗选中的胶囊。
        // 全部显示未选中会让用户以为值丢了，随手一点就覆盖掉原本正确的配置。
        if (!matched && value.isNotBlank()) {
            SegPill(text = value, selected = true, compact = true, onClick = { })
        }
    }
}

/**
 * 数字输入框。
 *
 * 只做「输入过滤」不做「合法性判定」：输入法打不出字母，一位一位敲进去的越界值
 * 由服务端在下次启动时拒绝并给出自己的日志。属性页不替服务端做判断，也就不会出现
 * 「某个你根本没碰过的项把整页保存锁死」这种事。
 */
@Composable
private fun NumberInput(
    value: String,
    decimal: Boolean,
    maxLength: Int = 12,
    onValueChange: (String) -> Unit
) {
    DebouncedTextField(
        value = value,
        onValueChange = { input -> onValueChange(sanitizeNumber(input, decimal).take(maxLength)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
        ),
        textStyle = TextStyle(fontSize = 12.sp),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 只保留数字（可带一个前导负号、一个小数点），挡掉中文输入法打出的奇怪字符。 */
private fun sanitizeNumber(input: String, decimal: Boolean): String {
    val negative = input.startsWith("-")
    val body = StringBuilder()
    var dotSeen = false
    for (ch in input) {
        when {
            ch.isDigit() -> body.append(ch)
            decimal && (ch == '.' || ch == ',') && !dotSeen -> {
                dotSeen = true
                body.append('.')
            }
        }
    }
    return if (negative) "-$body" else body.toString()
}

// ── 展示信息卡 ────────────────────────────────────────────────────

/** Minecraft 聊天颜色表（§0–§f）。 */
private val MC_COLORS: List<Pair<Char, Color>> = listOf(
    '0' to Color(0xFF000000), '1' to Color(0xFF0000AA), '2' to Color(0xFF00AA00),
    '3' to Color(0xFF00AAAA), '4' to Color(0xFFAA0000), '5' to Color(0xFFAA00AA),
    '6' to Color(0xFFFFAA00), '7' to Color(0xFFAAAAAA), '8' to Color(0xFF555555),
    '9' to Color(0xFF5555FF), 'a' to Color(0xFF55FF55), 'b' to Color(0xFF55FFFF),
    'c' to Color(0xFFFF5555), 'd' to Color(0xFFFF55FF), 'e' to Color(0xFFFFFF55),
    'f' to Color(0xFFFFFFFF)
)

/**
 * 展示信息（MOTD / 服务器名）卡片。
 *
 * 玩家第一眼看到的就是这两行字，但原文混着 `§` 代码，普通输入框读不出效果。
 * 这里给到：大文本框 + 一行横滑色板 + **服务器列表条目预览**（图标 / 彩色标题 /
 * 在线人数 / 信号条）—— 玩家真正看到的那一格。
 *
 * 上一版把 16 个色块换成两排、外加独立预览区，整卡吃掉近半屏，把「核心设置」
 * 挤出首屏。这是真机反馈里最刺眼的一处，现在压到 ~200dp。
 */
@Composable
private fun MotdHeroCard(
    title: String,
    key: String,
    value: String,
    modified: Boolean,
    maxPlayers: Int,
    onValueChange: (String) -> Unit,
    onRevert: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = McSpacing.Page, vertical = McSpacing.CardGap),
        shape = McShape.Card,
        colors = CardDefaults.cardColors(containerColor = IndigoSoft),
        border = BorderStroke(1.dp, Indigo.copy(alpha = 0.18f))
    ) {
        Column(Modifier.padding(McSpacing.CardInner)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(
                    key,
                    color = Muted,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.weight(1f))
                if (modified) {
                    Text(
                        stringResource(R.string.prop_ui_revert),
                        color = Indigo,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(McShape.Small)
                            .clickable { onRevert() }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            DebouncedTextField(
                value = value,
                onValueChange = onValueChange,
                minLines = 2,
                maxLines = 4,
                placeholder = { Text(stringResource(R.string.prop_ui_motd_empty), fontSize = 11.sp) },
                textStyle = TextStyle(fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            ColorPalette(onInsert = { code -> onValueChange(value + code) })

            Spacer(Modifier.height(10.dp))
            ServerListPreview(
                motd = value,
                maxPlayers = maxPlayers,
                emptyText = stringResource(R.string.prop_ui_motd_empty)
            )
        }
    }
}

/**
 * 色板：16 个颜色 + 5 个格式，横滑一行。
 *
 * 换行成两排会把卡片撑高 40dp，且第二排只放 3 个色块，看着像没排好；横滑一行既省
 * 高度，也符合「工具条」的隐喻（右侧露出半个色块提示可滑）。
 */
@Composable
private fun ColorPalette(onInsert: (String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(end = 4.dp)
    ) {
        items(MC_COLORS) { (code, color) ->
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(color, CircleShape)
                    .border(1.dp, Color.Black.copy(alpha = 0.08f), CircleShape)
                    .clickable { onInsert("§$code") }
            )
        }
        item {
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(width = 1.dp, height = 14.dp)
                    .background(Indigo.copy(alpha = 0.18f))
            )
        }
        item { FormatChip("§l", "B", FontWeight.ExtraBold, FontStyle.Normal, null) { onInsert("§l") } }
        item { FormatChip("§o", "I", FontWeight.Normal, FontStyle.Italic, null) { onInsert("§o") } }
        item {
            FormatChip("§n", "U", FontWeight.Normal, FontStyle.Normal, TextDecoration.Underline) {
                onInsert("§n")
            }
        }
        item {
            FormatChip("§m", "S", FontWeight.Normal, FontStyle.Normal, TextDecoration.LineThrough) {
                onInsert("§m")
            }
        }
        item {
            FormatChip("§r", "×", FontWeight.Normal, FontStyle.Normal, TextDecoration.LineThrough) {
                onInsert("§r")
            }
        }
    }
}

@Composable
private fun FormatChip(
    code: String,
    label: String,
    weight: FontWeight,
    style: FontStyle,
    decoration: TextDecoration?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(McShape.Small)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Line, McShape.Small)
            .clickable(onClickLabel = code) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Indigo,
            fontSize = 11.sp,
            fontWeight = weight,
            fontStyle = style,
            textDecoration = decoration
        )
    }
}

/**
 * 服务器列表条目预览 —— 玩家在多人游戏列表里真正看到的那一格。
 *
 * 比一条纯文本黑条更有说服力：左边是图标位（真实客户端会放 server-icon.png），
 * 右边第一行是渲染后的 MOTD，第二行是人数，最右是信号条。
 */
@Composable
private fun ServerListPreview(motd: String, maxPlayers: Int, emptyText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(McShape.Row)
            .background(Color(0xFF101014))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(McShape.Small)
                .background(Brush.linearGradient(listOf(Indigo, Color(0xFF6E7FD6)))),
            contentAlignment = Alignment.Center
        ) {
            Text("M", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // `mcPreview` 要逐字符扫描 § 代码并构建 AnnotatedString，每次重组都算一遍
            // 是白费（滚动时会反复重组），按 motd 缓存。
            val previewText = remember(motd, emptyText) {
                if (motd.isBlank()) AnnotatedString(emptyText) else mcPreview(motd)
            }
            Text(
                text = previewText,
                color = Color(0xFFE6E6E6),
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.prop_ui_preview_sub, maxPlayers),
                color = Color(0xFF8A8F9C),
                fontSize = 9.5.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        PingBars()
    }
}

@Composable
private fun PingBars() {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(5.dp, 9.dp, 13.dp).forEach { h ->
            Box(
                Modifier
                    .size(width = 3.dp, height = h)
                    .clip(CircleShape)
                    .background(Color(0xFF55FF55))
            )
        }
    }
}

/**
 * 把 `§` 代码渲染成带样式的文本。
 *
 * 支持颜色（0–f）、粗体 l、斜体 o、下划线 n、删除线 m、重置 r；`§k`（乱码）
 * 无法在 Compose 里还原，吞掉代码保留文字。
 */
private fun mcPreview(text: String): AnnotatedString = buildAnnotatedString {
    var color = Color(0xFFE6E6E6)
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    val buffer = StringBuilder()

    fun flush() {
        if (buffer.isEmpty()) return
        withStyle(
            SpanStyle(
                color = color,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = when {
                    underline && strike -> TextDecoration.combine(
                        listOf(TextDecoration.Underline, TextDecoration.LineThrough)
                    )
                    underline -> TextDecoration.Underline
                    strike -> TextDecoration.LineThrough
                    else -> null
                }
            )
        ) { append(buffer.toString()) }
        buffer.clear()
    }

    var i = 0
    while (i < text.length) {
        val ch = text[i]
        val isCode = (ch == '§' || ch == '&') && i + 1 < text.length
        if (!isCode) {
            buffer.append(ch)
            i++
            continue
        }
        flush()
        when (val c = text[i + 1].lowercaseChar()) {
            in '0'..'9', in 'a'..'f' -> {
                color = MC_COLORS.first { it.first == c }.second
                // 颜色代码会重置所有格式，与原版行为一致
                bold = false; italic = false; underline = false; strike = false
            }
            'l' -> bold = true
            'o' -> italic = true
            'n' -> underline = true
            'm' -> strike = true
            'r' -> {
                color = Color(0xFFE6E6E6)
                bold = false; italic = false; underline = false; strike = false
            }
            else -> Unit // §k 乱码无法还原，只吞掉代码
        }
        i += 2
    }
    flush()
}

// ── 底部 dock ─────────────────────────────────────────────────────

/**
 * 悬浮保存按钮。
 *
 * 不占底部一整条：属性页内容可以一直滚到屏幕底，按钮浮在右下角，滚到任何位置
 * 都能点到。没有改动时置灰不可点，避免用户以为「有东西没存」。
 */
@Composable
private fun SaveFab(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .shadow(if (enabled) 10.dp else 2.dp, CircleShape)
            .clip(CircleShape)
            .background(if (enabled) Indigo else FieldGray)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Save,
            contentDescription = null,
            tint = if (enabled) Color.White else Muted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            stringResource(R.string.s328),
            color = if (enabled) Color.White else Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EmptyNotice(text: String) {
    Text(
        text,
        color = Muted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = McSpacing.Page, vertical = 24.dp)
    )
}

// ── 状态页 ────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.NotGeneratedBody(
    coreName: String,
    sourceName: String,
    isRunning: Boolean,
    vm: McViewModel
) {
    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            stringResource(R.string.prop_ui_source, sourceName) + " · " + coreName,
            color = Muted,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = McSpacing.Page, top = 10.dp)
        )
        Spacer(Modifier.height(10.dp))
        PropertiesNotGeneratedCard(
            isRunning = isRunning,
            onStartServer = { vm.startServer() },
            onRefresh = { vm.loadServerProperties() }
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PropertiesEmptyScreen(onBack: () -> Unit, showBackBar: Boolean) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        if (showBackBar) {
            BackBar(title = stringResource(R.string.props_back_title), onBack = onBack)
        } else {
            Text(
                stringResource(R.string.props_back_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = McSpacing.Page, top = 12.dp)
            )
        }
        Text(
            stringResource(R.string.props_no_core),
            color = Muted,
            modifier = Modifier.padding(McSpacing.Page)
        )
    }
}

/**
 * 配置文件尚未生成时的引导卡片。
 *
 * 不渲染可编辑表单，避免用户在"预设值 ≠ 服务端真实值"的情况下做出错误修改。
 * 提供两条路径：直接启动服务端（推荐），或启动后回来刷新。
 */
@Composable
private fun PropertiesNotGeneratedCard(
    isRunning: Boolean,
    onStartServer: () -> Unit,
    onRefresh: () -> Unit,
    desc: String = stringResource(R.string.props_not_generated_desc),
    hint: String = stringResource(R.string.props_not_generated_hint)
) {
    McCard(title = stringResource(R.string.props_not_generated_title)) {
        Text(desc, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text(hint, color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        if (isRunning) {
            Text(
                stringResource(R.string.props_not_generated_running),
                color = Indigo,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = McShape.Control,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.props_not_generated_refresh),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            Button(
                onClick = onStartServer,
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = McShape.Control,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.props_not_generated_start),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.props_not_generated_refresh),
                    color = Indigo,
                    fontSize = 12.sp
                )
            }
        }
    }
}
