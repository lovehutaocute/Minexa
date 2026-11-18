package com.mineserve.mobile.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.server.BdsWorldGen
import com.mineserve.mobile.server.JavaWorldGen
import com.mineserve.mobile.server.WorldGenLedger
import com.mineserve.mobile.server.WorldManager
import com.mineserve.mobile.server.Worlds
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.LoadingHint
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.pageSurface
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import com.mineserve.mobile.data.skin.SkinTileSlots
import com.mineserve.mobile.ui.components.skinTileOverlay
import com.mineserve.mobile.ui.components.AppDialog

/**
 * 世界（存档）管理页：列出服务端目录下的所有世界，支持切换、导出、导入、重置。
 *
 * 页面只保留三块，顺序固定为「当前世界 → 世界列表 → 其它操作」：
 * 1. **当前世界**：名字 + 真实状态（已生成 / 待生成 / 读取失败），能读到什么就显示什么，
 *    读不到就明确说"还没生成"，不再出现「种子：尚未生成 / 生成类型：未知」这种什么都没说的组合。
 * 2. **世界列表**：新建 / 导入收进卡片右上角（它们都是"往列表里加东西"，属于同一件事）。
 * 3. 附加包/数据包**不在这里**：那是"给服务器装内容"，入口在「服务器管理 → 附加包 / 模组与插件」。
 */
@Composable
fun WorldsScreen(vm: McViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val worlds by vm.worlds.collectAsState()
    val loading by vm.worldsLoading.collectAsState()
    val activeName by vm.activeWorldName.collectAsState()
    val activeMeta by vm.activeWorldMeta.collectAsState()
    val javaMeta by vm.activeJavaWorldMeta.collectAsState()
    val activePlan by vm.activeWorldPlan.collectAsState()
    val activeCore by vm.activeCore.collectAsState()
    val javaDims by vm.javaDimensions.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    // 两套 level.dat 格式不同：新建世界与维度重置必须按核心类型分支
    val isJava = activeCore?.isBedrock == false

    // 待重置（二次确认）与待导出（选定 SAF 目标后执行）的世界
    var pendingReset by remember { mutableStateOf<Worlds.WorldInfo?>(null) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var pendingRename by remember { mutableStateOf<Worlds.WorldInfo?>(null) }
    var pendingDimReset by remember { mutableStateOf<String?>(null) }

    // 键必须是 isBootstrapped：loadWorlds() 在未部署时直接 return，用 Unit 作键的话
    // 之后 isBootstrapped 变 true 也不会重跑，世界列表会一直空着。
    LaunchedEffect(isBootstrapped) { if (isBootstrapped) vm.loadWorlds() }
    LaunchedEffect(isBootstrapped, isJava) { if (isBootstrapped && isJava) vm.loadJavaAddons() }
    LaunchedEffect(Unit) { vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) } }

    // 导出：先让用户挑一个保存位置，再打包写过去
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        val name = pendingExport
        if (uri != null && name != null) vm.exportWorldToUri(name, uri)
        pendingExport = null
    }

    // 导入：选一个 zip，用文件名（去掉扩展名）作为期望的世界名，重名会自动加后缀
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fallback = context.getString(R.string.wl_import_default_name) + "_" + System.currentTimeMillis().toString().takeLast(5)
            val preferred = queryDisplayName(context, uri)?.let { stripZipExt(context, it) } ?: fallback
            vm.importWorldFromUri(uri, preferred)
        }
    }

    Scaffold(

        containerColor = pageSurface(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(pageSurface())
        ) {
            BackBar(stringResource(R.string.wl_title), onBack)
            if (!isBootstrapped) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.wl_not_deployed), color = Muted, fontSize = 13.sp)
                }
                return@Scaffold
            }

            // 页面不再整体滚动（2026-11 列表懒加载改造）：
            // 当前世界卡固定在顶部，世界列表占满剩余高度、行在卡内虚拟化滚动——
            // 世界多的用户（批量导入 / 多存档）只组合可见行；导出说明固定在底部。
            ActiveWorldCard(
                name = activeName.ifBlank { "world" },
                isJava = isJava,
                meta = activeMeta,
                javaMeta = javaMeta,
                plan = activePlan,
                dims = javaDims,
                onClearSeed = { vm.clearWorldSeed() },
                onResetDimension = { pendingDimReset = it }
            )

            McCard(
                title = stringResource(R.string.wl_card_list),
                // 有列表才占满剩余高度；空列表保持紧凑卡片，不出「一张巨大的空卡」
                modifier = if (worlds.isNotEmpty()) Modifier.weight(1f) else Modifier,
                trailing = {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconAction(Icons.Outlined.Add, stringResource(R.string.wl_create)) { showCreate = true }
                            Spacer(Modifier.width(10.dp))
                            IconAction(Icons.Outlined.FileUpload, stringResource(R.string.wl_import)) {
                                importLauncher.launch(
                                    arrayOf("application/zip", "application/octet-stream", "*/*")
                                )
                            }
                        }
                    }
                }
            ) {
                if (worlds.isEmpty()) {
                    if (loading) {
                        // 扫描是磁盘 IO，大目录会卡几秒：给出「在加载」的明确占位，
                        // 不再与「一个世界都没有」的空文案混用同一行灰字。
                        LoadingHint(text = stringResource(R.string.wl_loading_usage))
                    } else {
                        Text(stringResource(R.string.wl_empty), color = Muted, fontSize = 12.sp)
                    }
                } else {
                    // key 用世界名：目录扫描结果天然唯一；重命名 / 激活切换后
                    // 列表能按行做增量重组，而不是整卡重建。
                    // animateItem：导入 / 删除世界时新旧行平滑让位，不再是整列硬跳。
                    LazyColumn(Modifier.weight(1f)) {
                        itemsIndexed(worlds, key = { _, w -> w.name }) { index, world ->
                            Column(Modifier.animateItem()) {
                                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                WorldRow(
                                world = world,
                                java = isJava,
                                onActivate = { vm.setActiveWorld(world.name) },
                                onExport = {
                                    // 先查运行状态再拉系统保存框：否则系统会先建出一个
                                    // 0 字节 zip，导出却已经被拒绝（真机实测）。
                                    if (!vm.canExportWorldNow()) return@WorldRow
                                    pendingExport = world.name
                                    exportLauncher.launch("${context.getString(R.string.wl_export_prefix)}_${world.name}.zip")
                                },
                                onReset = { pendingReset = world },
                                onRename = { pendingRename = world }
                                )
                            }
                        }
                    }
                }
            }

            // 导出/导入的注意事项：只保留一句，不再单独占一张卡片
            Text(
                stringResource(R.string.wl_export_note),
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(10.dp))
        }
    }

    // 重置确认：删除存档不可逆，这里明确告知会自动备份
    pendingReset?.let { world ->
        AppDialog(
            onDismissRequest = { pendingReset = null },
            title = { Text(stringResource(R.string.wl_reset_title, world.name)) },
            text = { Text(stringResource(R.string.wl_reset_body), fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetWorld(world.name)
                    pendingReset = null
                }) { Text(stringResource(R.string.wl_reset_confirm), color = Coral, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReset = null }) { Text(stringResource(R.string.s402)) }
            }
        )
    }

    if (showCreate) {
        CreateWorldDialog(
            isJava = isJava,
            onDismiss = { showCreate = false },
            onConfirm = { name, seed, gen, layers ->
                vm.createWorld(name, seed, gen, layers)
                showCreate = false
            },
            onConfirmJava = { name, seed, type, layers, biome ->
                vm.createJavaWorld(name, seed, type, layers, biome)
                showCreate = false
            }
        )
    }

    pendingRename?.let { world ->
        RenameWorldDialog(
            oldName = world.name,
            onDismiss = { pendingRename = null },
            onConfirm = { newName ->
                vm.renameWorld(world.name, newName)
                pendingRename = null
            }
        )
    }

    pendingDimReset?.let { key ->
        AppDialog(
            onDismissRequest = { pendingDimReset = null },
            title = { Text(stringResource(R.string.wl_reset_dim)) },
            text = { Text(stringResource(R.string.wl_reset_dim_body), fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetDimension(activeName, key)
                    pendingDimReset = null
                }) { Text(stringResource(R.string.wl_reset_confirm), color = Coral, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDimReset = null }) { Text(stringResource(R.string.s402)) }
            }
        )
    }
}

/**
 * 当前世界卡片。
 *
 * 三种状态严格区分，**不许混着显示**：
 * - 已生成：字段值来自世界文件（真相）；
 * - 待生成：字段值来自「建世界时记下的要求」，并明确标注"启动服务端后生成"；
 * - 读取失败：`level.dat` 在但解析不了（损坏 / 非 NBT），不假装"未知"。
 */
@Composable
private fun ActiveWorldCard(
    name: String,
    isJava: Boolean,
    meta: WorldManager.BedrockMeta?,
    javaMeta: com.mineserve.mobile.server.JavaWorlds.Meta?,
    plan: WorldGenLedger.Record?,
    dims: List<com.mineserve.mobile.server.JavaWorlds.DimensionDir>,
    onClearSeed: () -> Unit,
    onResetDimension: (String) -> Unit
) {
    val generated = if (isJava) javaMeta != null else meta?.generated == true
    val unreadable = !isJava && meta?.unreadable == true
    val statusText = when {
        generated -> stringResource(R.string.wl_state_generated)
        unreadable -> stringResource(R.string.wl_state_unreadable)
        else -> stringResource(R.string.wl_state_pending)
    }
    val statusColor = when {
        generated -> Indigo
        unreadable -> Coral
        else -> Muted
    }

    McCard(title = stringResource(R.string.wl_card_active)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = statusColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            Box(
                modifier = Modifier
                    .clip(McShape.Row)
                    .background(if (generated) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(statusText, fontSize = 10.sp, color = statusColor, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (generated) {
            if (isJava) {
                val m = javaMeta!!
                MetaRow(stringResource(R.string.wl_seed_label), m.seed?.toString() ?: stringResource(R.string.wl_seed_random))
                Spacer(Modifier.height(4.dp))
                MetaRow(stringResource(R.string.wl_gen_label), javaGeneratorLabel(m.generatorType))
                m.versionName?.let {
                    Spacer(Modifier.height(4.dp))
                    MetaRow(stringResource(R.string.wl_version_label), it)
                }
                m.difficulty?.let {
                    Spacer(Modifier.height(4.dp))
                    MetaRow(stringResource(R.string.wl_difficulty_label), it)
                }
                Spacer(Modifier.height(4.dp))
                MetaRow(
                    stringResource(R.string.wl_datapacks_title_short),
                    m.dataPacksEnabled.count { it.startsWith("file/") }.toString()
                )
            } else {
                MetaRow(
                    stringResource(R.string.wl_seed_label),
                    meta?.seed?.toString() ?: stringResource(R.string.wl_seed_random)
                )
                Spacer(Modifier.height(4.dp))
                MetaRow(stringResource(R.string.wl_gen_label), generatorLabel(meta?.generator))
            }
        } else {
            // 还没生成：只展示"当时要求的"，并说明它还不是既成事实
            Text(stringResource(R.string.wl_pending_desc), color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            // 种子一律给一行：填了显示原文，没填就写明"未设置（随机）"，
            // 不能因为读不到就留空或写成"随机"——那会让人以为已经有一个随机种子了。
            MetaRow(
                stringResource(R.string.wl_seed_label),
                plan?.seedText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.wl_seed_unset)
            )
            val plannedGen: String? = if (!isJava) {
                WorldGenLedger.bdsGeneratorCode(plan)?.let { generatorLabel(it) }
            } else {
                WorldGenLedger.javaGeneratorId(plan)?.let { javaGeneratorLabel(it) }
            }
            plannedGen?.let {
                Spacer(Modifier.height(4.dp))
                MetaRow(stringResource(R.string.wl_gen_label), it)
            }
        }

        if (isJava && dims.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.wl_dimensions), color = Muted, fontSize = 11.sp)
            dims.forEach { dim ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (dim.key == "nether") stringResource(R.string.wl_dim_nether) else stringResource(R.string.wl_dim_end),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onResetDimension(dim.key) }) {
                        Text(stringResource(R.string.wl_reset_dim), fontSize = 12.sp, color = Coral)
                    }
                }
            }
        }

        // 清空种子只影响"下一次生成"，已经生成的世界改它没用，所以只在待生成时给入口
        if (!isJava && !generated) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.wl_clear_seed_action),
                color = Indigo,
                fontSize = 11.sp,
                modifier = Modifier.clickable { onClearSeed() }
            )
        }
    }
}

/**
 * 新建世界：名字 + 种子 + 生成方式。
 *
 * 两个版本走的是**完全不同的机制**，所以按核心类型分支：
 * - 基岩版（[BdsWorldGen]）：先在世界目录里放一份 5 字段的 `level.dat`，BDS 首次启动按它生成；
 * - Java 版（[JavaWorldGen]）：只写 `server.properties` 的 `level-type` / `generator-settings` /
 *   `level-seed`，服务端自己生成世界。
 *
 * 确定按钮**永远可点**：校验放在点击时做，错误就写在输入框下面。
 * 之前是"名字为空就 disable"，用户点了没反应，只会以为界面卡住了。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateWorldDialog(
    isJava: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, BdsWorldGen.Generator, List<BdsWorldGen.Layer>) -> Unit,
    onConfirmJava: (String, String, JavaWorldGen.LevelType, List<BdsWorldGen.Layer>, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf("") }
    var generator by remember { mutableStateOf(BdsWorldGen.Generator.NORMAL) }
    var javaType by remember { mutableStateOf(JavaWorldGen.LevelType.NORMAL) }
    var layersText by remember { mutableStateOf("") }
    var biome by remember { mutableStateOf(JavaWorldGen.DEFAULT_BIOME) }
    var attempted by remember { mutableStateOf(false) }
    val resolved = worldSeedPreview(seed)

    val nameBad = name.isBlank()
    val needLayers = if (isJava) javaType == JavaWorldGen.LevelType.FLAT
    else generator == BdsWorldGen.Generator.FLAT
    // 只有选了超平坦才解析层配置；格式错就拦住确认（静默退回默认层会让用户以为设置生效了）
    val layers = if (needLayers) BdsWorldGen.parseLayers(layersText) else BdsWorldGen.DEFAULT_FLAT
    val layersBad = needLayers && layers == null
    val needBiome = isJava &&
        (javaType == JavaWorldGen.LevelType.FLAT || javaType == JavaWorldGen.LevelType.SINGLE_BIOME)
    val biomeId = JavaWorldGen.normalizeId(biome)
    val biomeBad = needBiome && biomeId == null

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wl_create_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.wl_create_name)) },
                    placeholder = { Text(stringResource(R.string.wl_create_name_hint)) },
                    singleLine = true,
                    isError = attempted && nameBad,
                    modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row))
                if (attempted && nameBad) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.wl_create_fail_name), color = Coral, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = seed,
                    onValueChange = { seed = it },
                    label = { Text(stringResource(R.string.wl_create_seed)) },
                    placeholder = { Text(stringResource(R.string.wl_create_seed_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row))
                if (resolved != null && seed.trim().toLongOrNull() == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.wl_create_seed_preview, resolved),
                        color = Muted,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.wl_gen_label), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                // 必须换行：Java 有 5 种生成方式，排一行会超出屏宽被裁掉，
                // 用户点到的是"看得见边框、看不到字"的残块，等于没选中（曾导致"选了超平坦却建成普通"）。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (isJava) {
                        JavaWorldGen.LevelType.entries.forEach { t ->
                            FilterChip(
                                selected = javaType == t,
                                onClick = { javaType = t },
                                label = { Text(stringResource(javaGeneratorTitle(t)), fontSize = 11.sp) }
                            )
                        }
                    } else {
                        BdsWorldGen.Generator.entries.forEach { g ->
                            FilterChip(
                                selected = generator == g,
                                onClick = { generator = g },
                                label = { Text(stringResource(generatorTitle(g)), fontSize = 12.sp) }
                            )
                        }
                    }
                }
                // 把"当前选中的是哪个"用文字再写一遍：chip 的选中态是浅色底，
                // 小屏 + 大字体下很容易看不出来，用户会以为自己选了超平坦其实没选。
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        R.string.wl_gen_chosen,
                        stringResource(
                            if (isJava) javaGeneratorTitle(javaType) else generatorTitle(generator)
                        )
                    ),
                    color = Indigo,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                if (needBiome) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = biome,
                        onValueChange = { biome = it },
                        label = { Text(stringResource(R.string.wl_biome_label)) },
                        placeholder = { Text(stringResource(R.string.wl_biome_hint)) },
                        singleLine = true,
                        isError = biomeBad,
                        modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row))
                }
                if (needLayers) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = layersText,
                        onValueChange = { layersText = it },
                        label = { Text(stringResource(R.string.wl_layers_label)) },
                        placeholder = { Text(stringResource(R.string.wl_layers_hint)) },
                        singleLine = true,
                        isError = layersBad,
                        modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (layersBad) stringResource(R.string.wl_layers_invalid)
                        else stringResource(
                            R.string.wl_layers_preview,
                            BdsWorldGen.layersText(layers ?: BdsWorldGen.DEFAULT_FLAT)
                        ),
                        color = if (layersBad) Coral else Muted,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(if (isJava) R.string.wl_create_note_java else R.string.wl_create_note),
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                attempted = true
                if (nameBad || layersBad || biomeBad) return@TextButton
                if (isJava) {
                    onConfirmJava(
                        name, seed, javaType,
                        layers ?: BdsWorldGen.DEFAULT_FLAT,
                        biomeId ?: JavaWorldGen.DEFAULT_BIOME
                    )
                } else {
                    onConfirm(name, seed, generator, layers ?: BdsWorldGen.DEFAULT_FLAT)
                }
            }) { Text(stringResource(R.string.wl_create_confirm), color = Indigo, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.s402)) }
        }
    )
}

/** 重命名世界：只改名字，不动存档内容。 */
@Composable
private fun RenameWorldDialog(oldName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(oldName) }
    var attempted by remember { mutableStateOf(false) }
    val bad = name.isBlank()
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wl_rename)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.wl_create_name)) },
                    singleLine = true,
                    isError = attempted && bad,
                    modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row))
                if (attempted && bad) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.wl_create_fail_name), color = Coral, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.wl_rename_note), color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                attempted = true
                if (!bad && name != oldName) onConfirm(name)
            }) { Text(stringResource(R.string.wl_create_confirm), color = Indigo, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.s402)) }
        }
    )
}

/** 与 BDS 一致的种子换算（数字取字面值，文本取 Java `String.hashCode`）。 */
private fun worldSeedPreview(raw: String): Long? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    text.toLongOrNull()?.let { return it }
    var hash = 0
    for (ch in text) hash = 31 * hash + ch.code
    return hash.toLong()
}

/** 生成方式的显示名。 */
private fun generatorTitle(g: BdsWorldGen.Generator): Int = when (g) {
    BdsWorldGen.Generator.NORMAL -> R.string.wl_gen_normal
    BdsWorldGen.Generator.FLAT -> R.string.wl_gen_flat
    BdsWorldGen.Generator.VOID -> R.string.wl_gen_void
}

/** Java 版生成方式的显示名。 */
private fun javaGeneratorTitle(t: JavaWorldGen.LevelType): Int = when (t) {
    JavaWorldGen.LevelType.NORMAL -> R.string.wl_gen_normal
    JavaWorldGen.LevelType.FLAT -> R.string.wl_gen_flat
    JavaWorldGen.LevelType.LARGE_BIOMES -> R.string.wl_gen_large_biomes
    JavaWorldGen.LevelType.AMPLIFIED -> R.string.wl_gen_amplified
    JavaWorldGen.LevelType.SINGLE_BIOME -> R.string.wl_gen_single_biome
}

/** 从世界文件读到的生成器类型（`minecraft:flat`）转显示名；未知就原样显示。 */
@Composable
private fun javaGeneratorLabel(raw: String?): String {
    val type = JavaWorldGen.LevelType.fromId(raw)
    return when {
        type != null -> stringResource(javaGeneratorTitle(type))
        // 读不到就说读不到。以前这里兜底成"普通"，于是"超平坦世界"会显示成普通，
        // 用户完全无法判断是自己的设置没生效还是显示错了。
        raw.isNullOrBlank() -> stringResource(R.string.wl_gen_unknown)
        else -> raw.removePrefix("minecraft:")
    }
}

/** 「标签：值」一行，用于种子 / 生成类型。 */
@Composable
private fun MetaRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label", color = Muted, fontSize = 11.sp, modifier = Modifier.width(64.dp))
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 生成类型对应文案（1=普通、2=超平坦、5=虚空，均为实测值）。 */
@Composable
private fun generatorLabel(generator: Int?): String = when (generator) {
    1 -> stringResource(R.string.wl_gen_normal)
    2 -> stringResource(R.string.wl_gen_flat)
    5 -> stringResource(R.string.wl_gen_void)
    else -> stringResource(R.string.wl_gen_unknown)
}

/** 单个世界：名称 + 类型/大小 + 当前生效标记 + 操作。 */
@Composable
private fun WorldRow(
    world: Worlds.WorldInfo,
    java: Boolean,
    onActivate: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit,
    onRename: () -> Unit
) {
    val kindLabel = when (world.kind) {
        Worlds.Kind.Java -> "Java"
        Worlds.Kind.Bedrock -> stringResource(R.string.wl_kind_bedrock)
        Worlds.Kind.Unknown -> stringResource(R.string.wl_kind_unknown)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Map,
                null,
                tint = if (world.isActive) Indigo else Muted,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    world.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    buildString {
                        append(kindLabel)
                        append(" · ")
                        // 还没生成过的世界不显示 0 B，那会被误读成「空世界」
                        if (world.planned) append(stringResource(R.string.wl_pending_generation))
                        else {
                            append(world.sizeText)
                            if (world.sizeTruncated) append("+")
                        }
                        if (world.isActive) append(stringResource(R.string.wl_in_use_suffix))
                    },
                    fontSize = 11.sp,
                    color = Muted
                )
            }
            if (world.isActive) {
                Box(
                    modifier = Modifier
                        .clip(McShape.Row)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(stringResource(R.string.wl_in_use), fontSize = 10.sp, color = Indigo, fontWeight = FontWeight.SemiBold)
                }
            } else {
                TextButton(onClick = onActivate) {
                    Text(stringResource(R.string.wl_set_active), fontSize = 12.sp, color = Indigo, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(icon = Icons.Outlined.FileDownload, label = stringResource(R.string.wl_export), onClick = onExport)
            // 重命名要同步 level.dat 的 LevelName，目前只对 Java 世界实现（基岩版需要小端 NBT）
            if (java) {
                SmallAction(icon = Icons.Outlined.DriveFileRenameOutline, label = stringResource(R.string.wl_rename), onClick = onRename)
            }
            SmallAction(icon = Icons.Outlined.DeleteOutline, label = stringResource(R.string.wl_reset), tint = Coral, onClick = onReset)
        }
    }
}

/** 行内小按钮：图标 + 文字，弱化视觉以突出主操作。 */
@Composable
private fun SmallAction(icon: ImageVector, label: String, tint: Color = Indigo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(McShape.Row)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(McIcon.Inline))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = tint)
    }
}

/** 卡片右上角的图标按钮（带点击区，最少 32dp）。 */
@Composable
private fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(McShape.Row)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Indigo, modifier = Modifier.size(McIcon.Inline + 2.dp))
    }
}

/** 读取 SAF 文档的显示名（用于给导入的世界起一个有意义的名字）。 */
private fun queryDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
} catch (e: Exception) {
    null
}

private fun stripZipExt(context: Context, name: String): String =
    name.removeSuffix(".zip").removeSuffix(".ZIP")
        .ifBlank { context.getString(R.string.wl_import_default_name) }
