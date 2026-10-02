package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.server.PluginManager
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.EmptyHint
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.CoralSoft
import com.mineserve.mobile.ui.theme.FieldGray
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.MintSoft
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class PluginTab(val labelRes: Int) { Installed(R.string.s735), Upload(R.string.s736) }

/** 资源类型：插件 / 模组（按核心兼容性屏蔽） */
private enum class ResourceType(val labelRes: Int) { Plugin(R.string.s737), Mod(R.string.s738) }

/** 插件资源站点（点击跳转官网） */
private data class PluginSite(val name: String, val descRes: Int, val url: String)

private val pluginSites = listOf(
    PluginSite(
        "SpigotMC Resources",
        R.string.s739,
        "https://www.spigotmc.org/resources/"
    ),
    PluginSite(
        "Hangar（Paper 官方）",
        R.string.s741,
        "https://hangar.papermc.io/"
    ),
    PluginSite(
        "Modrinth",
        R.string.s742,
        "https://modrinth.com/plugins"
    ),
    PluginSite(
        "BuiltByBit（原 MC-Market）",
        R.string.s744,
        "https://builtbybit.com"
    ),
    PluginSite(
        "CurseForge Bukkit 分区",
        R.string.s746,
        "https://www.curseforge.com/minecraft/bukkit-plugins"
    )
)
private enum class InstalledFilter(val labelRes: Int) { All(R.string.s747), Enabled(R.string.s748), Disabled(R.string.s749), Local(R.string.s127) }

/**
 * 插件管理页（重构完善版）
 *
 * 完善功能：
 *  - P0-1 plugin.yml 元信息解析（真实名称/版本/作者/依赖）
 *  - P0-2 GitHub API 更新检测（5 分钟缓存）
 *  - P0-3 插件详情对话框（完整元信息）
 *  - P1-4 搜索 + 筛选标签
 *  - P1-5 自定义 URL 下载
 *  - P1-6 删除时询问清理数据目录
 */
@Composable
fun PluginsScreen(vm: McViewModel) {
    val context = LocalContext.current
    val config by vm.config.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val installedPlugins by vm.installedPlugins.collectAsState()
    val mods by vm.mods.collectAsState()
    val modrinthResults by vm.modrinthResults.collectAsState()
    val modrinthLoaders by vm.modrinthLoaders.collectAsState()
    val modrinthGameVersions by vm.modrinthGameVersions.collectAsState()
    val selectedModVersion by vm.selectedModVersion.collectAsState()
    val pluginModrinthResults by vm.pluginModrinthResults.collectAsState()
    val pluginModrinthLoaders by vm.pluginModrinthLoaders.collectAsState()
    val selectedPluginVersion by vm.selectedPluginVersion.collectAsState()
    val isLoadingMoreMods by vm.isLoadingMoreMods.collectAsState()
    val isLoadingMorePlugins by vm.isLoadingMorePlugins.collectAsState()
    val downloadProgress by vm.pluginDownloadProgress.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 全局安装/下载进度对话框（精选插件、Modrinth、自定义 URL 统一反馈）
    val installingEntry = downloadProgress.entries.firstOrNull()
    if (installingEntry != null) {
        val p = installingEntry.value
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.ui_installing), fontSize = 16.sp) },
            text = {
                Column {
                    Text(p.pluginId, fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    if (p.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (p.downloadedBytes.toFloat() / p.totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = Indigo
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${formatBytes(p.downloadedBytes)} / ${formatBytes(p.totalBytes)} · ${p.speedBytesPerSec / 1024} KB/s",
                            fontSize = 12.sp, color = Muted
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp), color = Indigo, strokeWidth = 3.dp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${formatBytes(p.downloadedBytes)} · ${p.speedBytesPerSec / 1024} KB/s",
                            fontSize = 12.sp, color = Muted
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

    var activeTab by remember { mutableStateOf(PluginTab.Installed) }
    var pendingDelete by remember { mutableStateOf<PluginManager.InstalledPlugin?>(null) }
    var pendingModDelete by remember { mutableStateOf<PluginManager.ModEntry?>(null) }
    var detailPlugin by remember { mutableStateOf<PluginManager.InstalledPlugin?>(null) }

    // 进入页面或核心切换时自动刷新
    LaunchedEffect(isBootstrapped, config.activeCoreName) {
        if (isBootstrapped && config.activeCoreName != null) {
            vm.refreshInstalledPlugins()
        }
    }
    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }

    val activeCore = config.installedCores.find { it.name == config.activeCoreName }
    val coreType = activeCore?.core ?: config.selectedCore
    val isPowerNukkitX = coreType == com.mineserve.mobile.data.ServerCore.PowerNukkitX
    // 按核心兼容性计算可用资源类型（不支持的分类自动屏蔽）
    val availableTypes = buildList {
        if (coreType.supportsPlugins) add(ResourceType.Plugin)
        if (coreType.supportsMods) add(ResourceType.Mod)
    }
    var resourceType by remember { mutableStateOf(ResourceType.Plugin) }
    // 核心切换时校正资源类型（默认取第一个可用类型）
    LaunchedEffect(coreType) {
        resourceType = availableTypes.firstOrNull() ?: ResourceType.Plugin
    }
    // 进入模组分类时刷新模组列表 + 加载 Modrinth 加载器与游戏版本
    LaunchedEffect(isBootstrapped, config.activeCoreName, resourceType) {
        if (isBootstrapped && config.activeCoreName != null && resourceType == ResourceType.Mod) {
            vm.refreshMods()
            vm.loadModrinthLoaders()
            vm.loadModrinthGameVersions()
        }
    }
    // 进入插件分类时加载插件 Modrinth 资源
    LaunchedEffect(isBootstrapped, config.activeCoreName, resourceType) {
        if (isBootstrapped && config.activeCoreName != null && resourceType == ResourceType.Plugin) {
            vm.loadPluginModrinthLoaders()
            vm.loadPluginModrinthVersions()
        }
    }
    val pluginsPath = vm.currentPluginsPath()
    val isServerRunning = serverState.isRunning
    // 插件冲突检测接近 O(n²)，用 remember 缓存，避免每次重组全量重算
    val pluginConflicts = remember(installedPlugins) { vm.detectPluginConflicts(installedPlugins) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            if (isPowerNukkitX) {
                Text(
                    stringResource(R.string.pl_pnx_mods_hint),
                    color = Coral,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            HeaderBlock(eyebrow = stringResource(R.string.eyebrow_plugins), title = stringResource(R.string.s750))

            // ── 当前核心状态卡片 ──
            McCard(
                title = stringResource(R.string.s751),
                trailing = {
                    Text(
                        stringResource(R.string.s333),
                        color = Indigo,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            if (activeCore != null) {
                                vm.refreshInstalledPlugins()
                                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.s752)) }
                            } else {
                                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.s753)) }
                            }
                        }
                    )
                }
            ) {
                if (activeCore == null) {
                    Text(
                        stringResource(R.string.s754),
                        color = Coral,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    Text(
                        "${activeCore.name}  ·  ${activeCore.core.displayName} ${activeCore.version}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.s755, pluginsPath ?: "--"),
                        color = Muted,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (installedPlugins.isEmpty()) Muted else Mint)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            stringResource(R.string.s756, installedPlugins.size) +
                                if (installedPlugins.count { !it.isEnabled } > 0)
                                    stringResource(R.string.s757, installedPlugins.count { !it.isEnabled })
                                else "",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (coreType.supportsPlugins) stringResource(R.string.s467) else stringResource(R.string.s468),
                            color = if (coreType.supportsPlugins) Mint else Coral,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (coreType.supportsMods) stringResource(R.string.s469) else stringResource(R.string.s470),
                            color = if (coreType.supportsMods) Mint else Coral,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // ── 资源类型切换（按核心兼容性屏蔽不支持的分类） ──
            if (availableTypes.isEmpty()) {
                Text(
                    stringResource(R.string.s758, coreType.displayName),
                    color = Coral,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(IndigoSoft)
                        .padding(4.dp)
                ) {
                    availableTypes.forEach { t ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (resourceType == t) Indigo else Color.Transparent)
                                .clickable { resourceType = t }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(t.labelRes),
                                color = if (resourceType == t) Color.White else Muted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // ── 标签切换栏（仅插件分类显示） ──
            if (resourceType == ResourceType.Plugin) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(IndigoSoft)
                    .padding(4.dp)
            ) {
                PluginTab.values().forEach { tab ->
                    val count = when (tab) {
                        PluginTab.Installed -> installedPlugins.size
                        else -> 0
                    }
                    val label = if (count > 0) "${stringResource(tab.labelRes)} $count" else stringResource(tab.labelRes)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (activeTab == tab) Indigo else Color.Transparent)
                            .clickable { activeTab = tab }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (activeTab == tab) Color.White else Muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            }

            // ── 内容区（插件 / 模组按资源类型分流） ──
            if (resourceType == ResourceType.Plugin) {
            when (activeTab) {
                PluginTab.Installed -> InstalledTab(
                    installed = installedPlugins,
                    conflicts = pluginConflicts,
                    activeCoreExists = activeCore != null,
                    serverRunning = isServerRunning,
                    onToggle = { vm.togglePluginEnabled(it.fileName) },
                    onDelete = { pendingDelete = it },
                    onShowDetail = { detailPlugin = it }
                )

                PluginTab.Upload -> UploadTab(
                    activeCoreExists = activeCore != null,
                    customUrlProgress = vm.customUrlDownloadProgress(),
                    onPickFile = { uri -> vm.installPluginFromUri(uri) },
                    onInstallFromUrl = { url, name -> vm.installPluginFromUrl(url, name) },
                    onGotoInstalled = { activeTab = PluginTab.Installed }
                )
            }
            } else {
                ModsTab(
                    mods = mods,
                    curatedMods = vm.curatedMods,
                    activeCoreExists = activeCore != null,
                    coreType = coreType,
                    currentServerVersion = activeCore?.version ?: config.mcVersion,
                    serverRunning = isServerRunning,
                    modrinthResults = modrinthResults,
                    modrinthLoaders = modrinthLoaders,
                    modrinthGameVersions = modrinthGameVersions,
                    selectedModVersion = selectedModVersion,
                    onSetModVersion = { vm.setSelectedModVersion(it) },
                    onToggle = { vm.toggleModEnabled(it) },
                    onDelete = { pendingModDelete = it },
                    onUpload = { uri -> vm.installModFromUri(uri) },
                    onInstallCurated = { vm.installCuratedMod(it) },
                    onSearchModrinth = { query, loaders, sort, version ->
                        vm.searchModrinthMods(query, loaders, sort, version)
                    },
                    onInstallModrinth = { hit, version -> vm.installModrinthMod(hit, version) },
                    onLoadMoreModrinth = { vm.loadMoreModrinthMods() },
                    isLoadingMore = isLoadingMoreMods
                )
            }

            // ── 插件页 Modrinth 资源检索（与模组页布局统一） ──
            if (resourceType == ResourceType.Plugin) {
                PluginModrinthCard(
                    results = pluginModrinthResults,
                    loaders = pluginModrinthLoaders,
                    gameVersions = modrinthGameVersions,
                    selectedVersion = selectedPluginVersion,
                    currentServerVersion = activeCore?.version ?: config.mcVersion,
                    onSetVersion = { vm.setSelectedPluginVersion(it) },
                    onSearch = { query, loaders, sort, version ->
                        vm.searchModrinthPlugin(query, loaders, sort, version)
                    },
                    onInstall = { hit, version -> vm.installModrinthPlugin(hit, version) },
                    onLoadMore = { vm.loadMoreModrinthPlugin() },
                    isLoadingMore = isLoadingMorePlugins
                )
            }

            // ── 底部热重载 ──
            McCard(title = stringResource(R.string.s759)) {
                Text(
                    if (isServerRunning)
                        stringResource(R.string.s760)
                    else
                        stringResource(R.string.s761),
                    color = if (isServerRunning) Muted else Coral,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        vm.sendCommand("reload")
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.s762)) }
                    },
                    enabled = isServerRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Indigo,
                        disabledContainerColor = IndigoSoft
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (isServerRunning) stringResource(R.string.s763) else stringResource(R.string.s280),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 底部配置提示
            Text(
                stringResource(R.string.s764),
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            // ── 插件资源站点 ──
            Spacer(Modifier.height(10.dp))
            McCard(title = stringResource(R.string.s765)) {
                Text(
                    stringResource(R.string.s766),
                    color = Muted,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(8.dp))
                val context = LocalContext.current
                pluginSites.forEach { site ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site.url)))
                                } catch (e: Exception) { /* 无浏览器时忽略 */ }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(site.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(site.descRes),
                                color = Muted,
                                fontSize = 10.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = stringResource(R.string.s767),
                            tint = Indigo,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // 删除确认对话框（含数据目录清理选项）
    pendingDelete?.let { plugin ->
        DeletePluginDialog(
            plugin = plugin,
            onConfirm = { alsoRemoveData ->
                vm.deletePlugin(plugin.fileName, alsoRemoveData)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }

    // 模组删除确认对话框
    pendingModDelete?.let { mod ->
        AlertDialog(
            onDismissRequest = { pendingModDelete = null },
            title = { Text(stringResource(R.string.s768), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(R.string.s769, mod.baseName),
                    color = Muted,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteMod(mod.fileName)
                    pendingModDelete = null
                }) {
                    Text(stringResource(R.string.s339), color = Coral, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingModDelete = null }) {
                    Text(stringResource(R.string.s402), color = Muted)
                }
            }
        )
    }

    // 插件详情对话框
    detailPlugin?.let { plugin ->
        PluginDetailDialog(
            plugin = plugin,
            onDismiss = { detailPlugin = null }
        )
    }
}

// ── 已安装标签 ──────────────────────────────────────────────────────

@Composable
private fun InstalledTab(
    installed: List<PluginManager.InstalledPlugin>,
    conflicts: List<String>,
    activeCoreExists: Boolean,
    serverRunning: Boolean,
    onToggle: (PluginManager.InstalledPlugin) -> Unit,
    onDelete: (PluginManager.InstalledPlugin) -> Unit,
    onShowDetail: (PluginManager.InstalledPlugin) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(InstalledFilter.All) }

    McCard(title = stringResource(R.string.s390)) {
        if (!activeCoreExists) {
            EmptyHint(icon = Icons.Outlined.Extension, text = stringResource(R.string.s770))
            return@McCard
        }
        if (installed.isEmpty()) {
            EmptyHint(icon = Icons.Outlined.Extension, text = stringResource(R.string.s771))
            return@McCard
        }

        // 服务端运行中禁止启用/禁用或删除插件（文件正被 JVM 占用，改动不会生效且易损坏）
        if (serverRunning) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.pl_running_block), color = Coral, fontSize = 11.sp, lineHeight = 15.sp)
        }

        // 插件冲突警告
        if (conflicts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Coral.copy(alpha = 0.12f))
                    .border(1.dp, Coral, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Column {
                    Text(stringResource(R.string.pl_conflict_title), color = Coral, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    conflicts.forEach { c ->
                        Text("• $c", color = Coral, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }
        }

        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.s772), fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(Modifier.height(8.dp))

        // 筛选标签
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            InstalledFilter.values().forEach { filter ->
                val count = when (filter) {
                    InstalledFilter.All -> installed.size
                    InstalledFilter.Enabled -> installed.count { it.isEnabled }
                    InstalledFilter.Disabled -> installed.count { !it.isEnabled }
                    InstalledFilter.Local -> installed.count { it.sourceTag == "本地" }
                }
                val label = "$filter $count"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (activeFilter == filter) Indigo else IndigoSoft)
                        .clickable { activeFilter = filter }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        label,
                        color = if (activeFilter == filter) Color.White else Muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 过滤 + 搜索
        val filtered = installed.filter { p ->
            (activeFilter == InstalledFilter.All ||
                (activeFilter == InstalledFilter.Enabled && p.isEnabled) ||
                (activeFilter == InstalledFilter.Disabled && !p.isEnabled) ||
                (activeFilter == InstalledFilter.Local && p.sourceTag == "本地")) &&
                (searchQuery.isBlank() || p.baseName.contains(searchQuery, ignoreCase = true) ||
                    (p.meta?.name?.contains(searchQuery, ignoreCase = true) == true))
        }

        if (filtered.isEmpty()) {
            EmptyHint(icon = Icons.Outlined.Extension, text = stringResource(R.string.s773))
            return@McCard
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            filtered.forEach { p ->
                InstalledPluginRow(
                    plugin = p,
                    enabled = !serverRunning,
                    onToggle = { onToggle(p) },
                    onDelete = { onDelete(p) },
                    onShowDetail = { onShowDetail(p) }
                )
            }
        }
    }
}

@Composable
private fun InstalledPluginRow(
    plugin: PluginManager.InstalledPlugin,
    enabled: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onShowDetail: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (plugin.isEnabled) IndigoSoft else CoralSoft.copy(alpha = 0.4f))
            .clickable { onShowDetail() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (plugin.isEnabled) Indigo else Muted),
            contentAlignment = Alignment.Center
        ) {
            Text(
                plugin.baseName.take(2).uppercase(),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plugin.meta?.name ?: plugin.baseName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (plugin.isEnabled) MaterialTheme.colorScheme.onSurface else Muted
                )
                if (plugin.meta?.version != null) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "v${plugin.meta.version}",
                        color = Muted,
                        fontSize = 9.sp
                    )
                }
            }
            val metaLine = buildString {
                append(plugin.sizeText)
                append("  ·  ")
                append(plugin.lastModifiedText)
                append("  ·  ")
                append(plugin.sourceTag)
                if (!plugin.isEnabled) append(stringResource(R.string.pl_disabled_suffix))
            }
            Text(
                metaLine,
                color = Muted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 详情按钮
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Transparent)
                .clickable { onShowDetail() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.s734), tint = Indigo, modifier = Modifier.size(14.dp))
        }
        // 启用/禁用开关
        Switch(
            checked = plugin.isEnabled,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            modifier = Modifier.size(width = 36.dp, height = 20.dp)
        )
        Spacer(Modifier.size(6.dp))
        // 删除按钮
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CoralSoft)
                .clickable(enabled = enabled) { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.s339), tint = Coral, modifier = Modifier.size(14.dp))
        }
    }
}

// ── 本地上传标签 ────────────────────────────────────────────────────

@Composable
private fun UploadTab(
    activeCoreExists: Boolean,
    customUrlProgress: McViewModel.PluginDownloadProgress?,
    onPickFile: (Uri) -> Unit,
    onInstallFromUrl: (String, String) -> Unit,
    onGotoInstalled: () -> Unit
) {
    // SAF 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onPickFile(uri)
    }

    var customUrl by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }

    McCard(title = stringResource(R.string.s792)) {
        if (!activeCoreExists) {
            EmptyHint(icon = Icons.Outlined.Extension, text = stringResource(R.string.s770))
            return@McCard
        }
        Text(
            stringResource(R.string.s793),
            color = Muted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.s794),
            color = Muted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(16.dp))

        // 大按钮区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(IndigoSoft)
                .border(width = 1.dp, color = Indigo, shape = RoundedCornerShape(12.dp))
                .clickable {
                    filePickerLauncher.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*"))
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = stringResource(R.string.s497), tint = Indigo, modifier = Modifier.size(36.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.s795), color = Indigo, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(2.dp))
                Text(stringResource(R.string.s796), color = Muted, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        // 分隔线
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f).height(1.dp).background(IndigoSoft))
            Text(stringResource(R.string.s797), color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Box(modifier = Modifier.weight(1f).height(1.dp).background(IndigoSoft))
        }

        Spacer(Modifier.height(16.dp))

        // 自定义 URL 下载
        Text(stringResource(R.string.s798), color = Indigo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.s799),
            color = Muted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = customUrl,
            onValueChange = { customUrl = it },
            label = { Text(stringResource(R.string.s800), fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customName,
            onValueChange = { customName = it },
            label = { Text(stringResource(R.string.s801), fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )

        // 自定义 URL 下载进度
        if (customUrlProgress != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { customUrlProgress.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = Mint,
                trackColor = IndigoSoft
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${customUrlProgress.percent}%", color = Indigo, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(customUrlProgress.speedText, color = Muted, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                onInstallFromUrl(customUrl.trim(), customName.trim())
            },
            enabled = customUrl.isNotBlank() && customName.isNotBlank() && customUrlProgress == null,
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.s486), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onGotoInstalled,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.s802), color = Indigo, fontSize = 11.sp)
        }
    }
}

// ── 对话框组件 ──────────────────────────────────────────────────────

@Composable
private fun DeletePluginDialog(
    plugin: PluginManager.InstalledPlugin,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var alsoRemoveData by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.s803), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(stringResource(R.string.s804), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    plugin.fileName,
                    color = Indigo,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.s805, plugin.sizeText, plugin.lastModifiedText),
                    color = Muted,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.s806),
                    color = Coral,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { alsoRemoveData = !alsoRemoveData }
                ) {
                    Checkbox(
                        checked = alsoRemoveData,
                        onCheckedChange = { alsoRemoveData = it }
                    )
                    Spacer(Modifier.size(4.dp))
                    Column {
                        Text(stringResource(R.string.s807), fontSize = 12.sp)
                        Text(
                            if (plugin.meta?.name != null)
                                stringResource(R.string.s808, plugin.meta.name)
                            else
                                stringResource(R.string.s809),
                            color = Muted,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(alsoRemoveData) },
                colors = ButtonDefaults.buttonColors(containerColor = Coral)
            ) { Text(stringResource(R.string.s339), color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.s402)) }
        }
    )
}

@Composable
private fun PluginDetailDialog(
    plugin: PluginManager.InstalledPlugin,
    onDismiss: () -> Unit
) {
    val meta = plugin.meta
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Extension, contentDescription = null, tint = Indigo, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.s810), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DetailRow(stringResource(R.string.s811), plugin.fileName)
                DetailRow(stringResource(R.string.s812), plugin.baseName)
                if (meta != null) {
                    DetailRow(stringResource(R.string.s813), meta.name)
                    DetailRow(stringResource(R.string.s814), meta.version)
                    DetailRow(stringResource(R.string.s815), meta.mainClass.ifEmpty { "—" })
                    DetailRow(stringResource(R.string.s816), meta.author)
                    DetailRow(stringResource(R.string.s817), meta.apiVersion.ifEmpty { "—" })
                    if (meta.description.isNotBlank()) {
                        DetailRow(stringResource(R.string.s818), meta.description)
                    }
                    if (meta.depends.isNotEmpty()) {
                        DetailRow(stringResource(R.string.s819), meta.depends.joinToString(", "))
                    }
                    if (meta.softDepends.isNotEmpty()) {
                        DetailRow(stringResource(R.string.s820), meta.softDepends.joinToString(", "))
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.s821),
                        color = Muted,
                        fontSize = 10.sp
                    )
                }
                DetailRow(stringResource(R.string.s822), plugin.sizeText)
                DetailRow(stringResource(R.string.s823), plugin.lastModifiedText)
                DetailRow(stringResource(R.string.s725), plugin.sourceTag)
                DetailRow(stringResource(R.string.s824), if (plugin.isEnabled) stringResource(R.string.s748) else stringResource(R.string.s237))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.s620)) }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ── 通用组件 ──

@Composable
private fun BadgeChip(text: String, color: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

// ── 模组分类 ──────────────────────────────────────────────────────

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModsTab(
    mods: List<PluginManager.ModEntry>,
    curatedMods: List<PluginManager.CuratedMod>,
    activeCoreExists: Boolean,
    coreType: ServerCore,
    currentServerVersion: String,
    modrinthResults: List<PluginManager.ModrinthHit>,
    modrinthLoaders: List<String>,
    modrinthGameVersions: List<String>,
    selectedModVersion: String,
    onSetModVersion: (String) -> Unit,
    serverRunning: Boolean,
    onToggle: (String) -> Unit,
    onDelete: (PluginManager.ModEntry) -> Unit,
    onUpload: (Uri) -> Unit,
    onInstallCurated: (PluginManager.CuratedMod) -> Unit,
    onSearchModrinth: (String, List<String>, String, String) -> Unit,
    onInstallModrinth: (PluginManager.ModrinthHit, String) -> Unit,
    onLoadMoreModrinth: () -> Unit,
    isLoadingMore: Boolean
) {
    var detailHit by remember { mutableStateOf<PluginManager.ModrinthHit?>(null) }
    var modrinthQuery by remember { mutableStateOf("") }
    var selectedLoaders by remember { mutableStateOf(setOf<String>()) }
    var sortIndex by remember { mutableStateOf(0) }
    val sortOptions = listOf("downloads" to stringResource(R.string.s825), "relevance" to stringResource(R.string.s826), "newest" to stringResource(R.string.s827))
    // 本地上传模组选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onUpload) }

    // 已安装模组
    McCard(title = stringResource(R.string.s828)) {
        if (serverRunning) {
            // 服务端运行中禁止启用/禁用或删除模组（文件正被 JVM 占用，改动不会生效且易损坏）
            Text(stringResource(R.string.pl_running_block), color = Coral, fontSize = 11.sp, lineHeight = 15.sp)
            Spacer(Modifier.height(6.dp))
        }
        if (mods.isEmpty()) {
            Text(
                stringResource(R.string.s829),
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                mods.forEach { mod ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(IndigoSoft)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Indigo),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(mod.baseName.take(2).uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mod.baseName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val statusText = if (mod.isEnabled) stringResource(R.string.s238) else stringResource(R.string.s237)
                            Text(
                                "${mod.sizeText} · $statusText",
                                color = Muted,
                                fontSize = 10.sp
                            )
                        }
                        TextButton(onClick = { onToggle(mod.fileName) }, enabled = !serverRunning) {
                            Text(
                                if (mod.isEnabled) stringResource(R.string.s749) else stringResource(R.string.s748),
                                color = if (mod.isEnabled) Coral else Mint,
                                fontSize = 11.sp
                            )
                        }
                        IconButton(onClick = { onDelete(mod) }, enabled = !serverRunning) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.s339), tint = Coral, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { filePickerLauncher.launch(arrayOf("application/java-archive", "*/*")) },
            enabled = activeCoreExists,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.s830), color = Indigo, fontSize = 12.sp)
        }
    }


    // ── Modrinth 模组获取 ──
    var sortMenuOpen by remember { mutableStateOf(false) }
    McCard(title = stringResource(R.string.s831)) {
        Text(
            stringResource(R.string.s832),
            color = Muted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(6.dp))
        // 搜索框 + 排序 + 搜索按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = modrinthQuery,
                onValueChange = { modrinthQuery = it },
                placeholder = { Text(stringResource(R.string.s833), fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.size(6.dp))
            Box {
                OutlinedButton(
                    onClick = { sortMenuOpen = true },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(sortOptions[sortIndex].second, fontSize = 11.sp, color = Indigo)
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    sortOptions.forEachIndexed { i, (_, label) ->
                        DropdownMenuItem(
                            text = { Text(label, fontSize = 12.sp) },
                            onClick = { sortIndex = i; sortMenuOpen = false }
                        )
                    }
                }
            }
Spacer(Modifier.size(6.dp))
            Button(
                onClick = { onSearchModrinth(modrinthQuery.trim(), selectedLoaders.toList(), sortOptions[sortIndex].first, selectedModVersion) },
                enabled = modrinthQuery.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.s834), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // 版本筛选 ｜ 加载器 下拉（布局参考：版本下拉框｜加载器下拉框）
        var versionMenuOpen by remember { mutableStateOf(false) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.s1061), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(6.dp))
            Box {
                OutlinedButton(
                    onClick = { versionMenuOpen = true },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        if (selectedModVersion.isNotBlank()) selectedModVersion else "--",
                        fontSize = 11.sp,
                        color = Indigo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DropdownMenu(expanded = versionMenuOpen, onDismissRequest = { versionMenuOpen = false }) {
                    if (modrinthGameVersions.isEmpty()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.s1062), fontSize = 11.sp) }, onClick = { versionMenuOpen = false })
                    }
                    modrinthGameVersions.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(v, fontSize = 11.sp, color = if (v == currentServerVersion) Indigo else Muted) },
                            onClick = { onSetModVersion(v); versionMenuOpen = false }
                        )
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.s835), color = Muted, fontSize = 10.sp)
        }
        if (selectedModVersion.isNotBlank() && selectedModVersion != currentServerVersion && currentServerVersion.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.s1063, selectedModVersion, currentServerVersion),
                color = Coral,
                fontSize = 10.sp,
fontWeight = FontWeight.SemiBold
            )
        }
        // 加载器多选筛选（可展开列表，避免元素零散排布）
        if (modrinthLoaders.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LoaderFilterSection(
                loaders = modrinthLoaders,
                selected = selectedLoaders,
                onToggle = { loader ->
                    selectedLoaders = if (loader in selectedLoaders) selectedLoaders - loader else selectedLoaders + loader
                }
            )
        }
        // 结果列表
        if (modrinthResults.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val coreLoader = if (coreType == ServerCore.Fabric || coreType == ServerCore.Quilt) "fabric" else "forge"
            modrinthResults.forEach { hit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clickable { detailHit = hit },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModrinthIcon(hit.icon_url)
                    Spacer(Modifier.size(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(hit.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val downloadsText = formatModrinthDownloads(hit.downloads)
                        Text(
                            "${hit.author} · $downloadsText",
                            color = Muted,
                            fontSize = 10.sp
                        )
                        // 当前核心不匹配红字提醒
                        if (hit.categories.isNotEmpty() && coreLoader !in hit.categories) {
                            Text(
                                stringResource(R.string.s836, coreType.displayName),
                                color = Coral,
                                fontSize = 9.sp
                            )
                        }
                        // 选中版本与当前服务器版本不匹配红字提示（模组条目下方）
                        if (selectedModVersion.isNotBlank() && currentServerVersion.isNotBlank() && selectedModVersion != currentServerVersion) {
                            Text(
                                stringResource(R.string.s1063, selectedModVersion, currentServerVersion),
                                color = Coral,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else if (hit.description.isNotBlank()) {
                            Text(hit.description, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(
                        onClick = { onInstallModrinth(hit, selectedModVersion) },
                        colors = ButtonDefaults.buttonColors(containerColor = Mint),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.s837), color = Color.White, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onLoadMoreModrinth,
                enabled = !isLoadingMore,
                modifier = Modifier.fillMaxWidth().height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text(if (isLoadingMore) "加载中..." else stringResource(R.string.pl_load_more), color = Indigo, fontSize = 11.sp) }
        }
    }

    // 模组详情对话框
    ModrinthDetailDialog(detailHit) { detailHit = null }
}

/** 插件页 Modrinth 资源检索（布局与模组页统一） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginModrinthCard(
    results: List<PluginManager.ModrinthHit>,
    loaders: List<String>,
    gameVersions: List<String>,
    selectedVersion: String,
    currentServerVersion: String,
    onSetVersion: (String) -> Unit,
    onSearch: (String, List<String>, String, String) -> Unit,
    onInstall: (PluginManager.ModrinthHit, String) -> Unit,
    onLoadMore: () -> Unit,
    isLoadingMore: Boolean
) {
    var query by remember { mutableStateOf("") }
    var selectedLoaders by remember { mutableStateOf(setOf<String>()) }
    var detailHit by remember { mutableStateOf<PluginManager.ModrinthHit?>(null) }
    var sortIndex by remember { mutableStateOf(0) }
    val sortOptions = listOf("downloads" to stringResource(R.string.s825), "relevance" to stringResource(R.string.s826), "newest" to stringResource(R.string.s827))
    var sortMenuOpen by remember { mutableStateOf(false) }
    var versionMenuOpen by remember { mutableStateOf(false) }
    // 插件页统一使用 Modrinth 插件库（project_type=plugin，加载器以 bukkit/paper 为主）
    val coreLoader = "bukkit"

    McCard(title = stringResource(R.string.s1068)) {
        Text(
            stringResource(R.string.s1069),
            color = Muted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(6.dp))
        // 搜索框 + 排序 + 搜索按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.s1070), fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.size(6.dp))
            Box {
                OutlinedButton(
                    onClick = { sortMenuOpen = true },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(sortOptions[sortIndex].second, fontSize = 11.sp, color = Indigo)
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    sortOptions.forEachIndexed { i, (_, label) ->
                        DropdownMenuItem(
                            text = { Text(label, fontSize = 12.sp) },
                            onClick = { sortIndex = i; sortMenuOpen = false }
                        )
                    }
                }
            }
            Spacer(Modifier.size(6.dp))
            Button(
                onClick = { onSearch(query.trim(), selectedLoaders.toList(), sortOptions[sortIndex].first, selectedVersion) },
                enabled = query.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.s834), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // 版本筛选 ｜ 加载器 下拉
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.s1061), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(6.dp))
            Box {
                OutlinedButton(
                    onClick = { versionMenuOpen = true },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        if (selectedVersion.isNotBlank()) selectedVersion else "--",
                        fontSize = 11.sp,
                        color = Indigo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DropdownMenu(expanded = versionMenuOpen, onDismissRequest = { versionMenuOpen = false }) {
                    if (gameVersions.isEmpty()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.s1062), fontSize = 11.sp) }, onClick = { versionMenuOpen = false })
                    }
                    gameVersions.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(v, fontSize = 11.sp, color = if (v == currentServerVersion) Indigo else Muted) },
                            onClick = { onSetVersion(v); versionMenuOpen = false }
                        )
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.s835), color = Muted, fontSize = 10.sp)
        }
        if (selectedVersion.isNotBlank() && currentServerVersion.isNotBlank() && selectedVersion != currentServerVersion) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.s1063, selectedVersion, currentServerVersion),
                color = Coral,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        // 加载器多选筛选（可展开列表，避免元素零散排布）
        if (loaders.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LoaderFilterSection(
                loaders = loaders,
                selected = selectedLoaders,
                onToggle = { loader ->
                    selectedLoaders = if (loader in selectedLoaders) selectedLoaders - loader else selectedLoaders + loader
                }
            )
        }
        // 结果列表（条目下方红字提示版本不匹配）
        if (results.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            results.forEach { hit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clickable { detailHit = hit },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModrinthIcon(hit.icon_url)
                    Spacer(Modifier.size(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(hit.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val downloadsText = formatModrinthDownloads(hit.downloads)
                        Text(
                            "${hit.author} · $downloadsText",
                            color = Muted,
                            fontSize = 10.sp
                        )
                        if (hit.categories.isNotEmpty() && coreLoader !in hit.categories) {
                            Text(
                                stringResource(R.string.s1071, coreLoader),
                                color = Coral,
                                fontSize = 9.sp
                            )
                        }
                        if (selectedVersion.isNotBlank() && currentServerVersion.isNotBlank() && selectedVersion != currentServerVersion) {
                            Text(
                                stringResource(R.string.s1063, selectedVersion, currentServerVersion),
                                color = Coral,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else if (hit.description.isNotBlank()) {
                            Text(hit.description, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(
                        onClick = { onInstall(hit, selectedVersion) },
                        colors = ButtonDefaults.buttonColors(containerColor = Mint),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.s837), color = Color.White, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onLoadMore,
                enabled = !isLoadingMore,
                modifier = Modifier.fillMaxWidth().height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text(if (isLoadingMore) "加载中..." else stringResource(R.string.pl_load_more), color = Indigo, fontSize = 11.sp) }
        }
    }

    // 插件详情对话框（Modrinth 搜索结果）
    ModrinthDetailDialog(detailHit) { detailHit = null }
}

/** Modrinth 下载量格式化 */
@Composable
private fun formatModrinthDownloads(d: Long): String = when {
    d >= 1_000_000 -> String.format(stringResource(R.string.s838), d / 1_000_000.0)
    d >= 1_000 -> String.format(stringResource(R.string.s839), d / 1_000.0)
    else -> stringResource(R.string.s840, d)
}

/** Modrinth 模组图标（网络加载，失败显示占位） */
/** 可展开的加载器筛选列表：默认收起（显示标题+已选数量），点击展开多选，避免元素零散排布 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoaderFilterSection(
    loaders: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(FieldGray)
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.s835),
                color = Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (selected.isNotEmpty()) {
                Text(
                    stringResource(R.string.ui_loader_selected, selected.size),
                    color = Indigo,
                    fontSize = 10.sp
                )
                Spacer(Modifier.size(6.dp))
            }
            Text(if (expanded) "▾" else "▸", color = Muted, fontSize = 11.sp)
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                loaders.forEach { loader ->
                    val isSelected = loader in selected
                    FilterChip(
                        selected = isSelected,
                        onClick = { onToggle(loader) },
                        label = { Text(loader, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = IndigoSoft,
                            selectedLabelColor = Indigo
                        )
                    )
                }
            }
        }
    }
}

/** Modrinth 搜索结果详情对话框（模组/插件通用）：作者/下载量/分类/描述 */
@Composable
private fun ModrinthDetailDialog(hit: PluginManager.ModrinthHit?, onDismiss: () -> Unit) {
    val h = hit ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModrinthIcon(h.icon_url, size = 30.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    h.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "${h.author} · ${formatModrinthDownloads(h.downloads)}",
                    color = Muted,
                    fontSize = 12.sp
                )
                if (h.categories.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        h.categories.joinToString(" · "),
                        color = Indigo,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    h.description.ifBlank { "-" },
                    fontSize = 13.sp,
                    color = com.mineserve.mobile.ui.theme.Ink
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.s620))
            }
        }
    )
}

@Composable
private fun ModrinthIcon(url: String, size: Dp = 32.dp) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = null
        if (url.isNotBlank()) {
            bitmap = withContext(Dispatchers.IO) {
                var conn: java.net.HttpURLConnection? = null
                try {
                    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn = connection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.setRequestProperty("User-Agent", "McServerManager/1.0 (mcserver-manager)")
                    val bytes = connection.inputStream.use { it.readBytes() }
                    decodeModrinthIcon(bytes)
                } catch (e: Exception) {
                    null
                } finally {
                    conn?.disconnect()
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(IndigoSoft),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop
            )
        } else {
            Text("🟦", fontSize = 14.sp)
        }
    }
}

/** Plugin cards render icons at 32dp, so retaining full-size remote artwork only wastes memory. */
private fun decodeModrinthIcon(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 96 || bounds.outHeight / sample > 96) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    )
}

/** 字节数格式化（进度对话框使用） */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.US, "%.2f GB", gb)
}
