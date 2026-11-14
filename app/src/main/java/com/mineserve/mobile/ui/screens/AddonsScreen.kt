package com.mineserve.mobile.ui.screens

import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.server.BdsPacks
import com.mineserve.mobile.server.Worlds
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.pageSurface
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.SegmentedTabs
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest

/**
 * 基岩版附加包页：行为包 + 资源包。
 *
 * 只服务基岩系（BDS / PowerNukkitX / PocketMine / Allay）。Java 版没有「附加包」这个概念，
 * 它的世界级数据包与单资源包在「模组与插件」页里（同一次点击进入，分类不同），
 * 不要为了统一而把两套模型塞进同一个表单——它们的文件位置、生效方式、可验证程度全不一样。
 */
@Composable
fun AddonsScreen(vm: McViewModel, onBack: () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    val isBootstrapped by vm.isBootstrapped.collectAsState()

    LaunchedEffect(Unit) { vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) } }

    Scaffold(

        containerColor = pageSurface(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(pageSurface())
        ) {
            BackBar(stringResource(R.string.ad_title), onBack)
            if (!isBootstrapped) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.wl_not_deployed), color = Muted, fontSize = 13.sp)
                }
                return@Column
            }
            BdsAddonsContent(vm)
        }
    }
}

@Composable
private fun BdsAddonsContent(vm: McViewModel) {
    val scan by vm.addons.collectAsState()
    val loading by vm.addonsLoading.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val running by vm.serverState.collectAsState()
    val texturePackRequired by vm.texturePackRequired.collectAsState()

    var tab by remember { mutableStateOf(BdsPacks.Kind.BEHAVIOR) }
    // 官方自带包（vanilla / chemistry / experimental…）动辄几十个，默认折叠
    var showBuiltin by remember { mutableStateOf(false) }

    val context = LocalContext.current
    // .mcpack / .mcaddon 都是 zip，MIME 各家实现不一样，直接放开选（安装时会校验）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importAddonFromUri(uri, displayName(context, uri))
    }

    LaunchedEffect(isBootstrapped) { if (isBootstrapped) vm.loadAddons() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp, bottom = 18.dp)
    ) {
        // 顶部只留一句说明 + 一个主操作：导入。其余信息按需出现在对应分组里。
        McCard(title = stringResource(R.string.ad_title)) {
            Text(stringResource(R.string.ad_desc), color = Muted, fontSize = 11.sp)
            if (running.isRunning) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.ad_stop_first), color = Coral, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    // 同 JavaContentTabs：纯 */* 选不到 .mcaddon/.mcpack/.zip
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed", "*/*")) },
                    enabled = !running.isRunning,
                    shape = McShape.ControlCompact
                ) {
                    Icon(Icons.Outlined.FileUpload, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ad_import_archive), fontSize = 13.sp)
                }
                if (loading) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.ad_import_hint), color = Muted, fontSize = 10.sp)
        }

        SegmentedTabs(
            options = BdsPacks.Kind.entries.map {
                stringResource(
                    if (it == BdsPacks.Kind.BEHAVIOR) R.string.ad_tab_behavior else R.string.ad_tab_resource
                )
            },
            selectedIndex = tab.ordinal,
            modifier = Modifier.padding(horizontal = McSpacing.Page, vertical = 2.dp),
            onSelect = { index -> BdsPacks.Kind.entries.getOrNull(index)?.let { tab = it } }
        )

        val current = scan
        if (current == null) {
            if (!loading) {
                McCard(title = stringResource(R.string.ad_title)) {
                    Text(stringResource(R.string.ad_empty), color = Muted, fontSize = 12.sp)
                }
            }
            return@Column
        }

        val packs = current.ofKind(tab)
        val orphans = current.orphans(tab)

        // 强制资源包：只有资源包 tab 才有意义（texturepack-required 只影响资源包）
        if (tab == BdsPacks.Kind.RESOURCE) {
            McCard(title = stringResource(R.string.ad_tpr_title)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ad_tpr_desc), color = Muted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = texturePackRequired == true,
                        enabled = texturePackRequired != null && !running.isRunning,
                        onCheckedChange = { vm.setTexturePackRequired(it) }
                    )
                }
                // 联动校验：开了却没有资源包 = 白开；登记了找不到包 = 客户端可能卡在下载
                if (texturePackRequired == true && current.enabledResource.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.ad_tpr_warn_empty), color = Coral, fontSize = 11.sp)
                }
                if (texturePackRequired == true && orphans.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.ad_tpr_warn_orphan), color = Coral, fontSize = 11.sp)
                }
            }
        }

        if (packs.isEmpty() && orphans.isEmpty()) {
            McCard(title = stringResource(R.string.ad_empty_title)) {
                Text(stringResource(R.string.ad_empty), color = Muted, fontSize = 12.sp)
            }
        } else {
            // 分组顺序：本世界 → 全服 → 开发 → 系统自带
            val order = listOf(
                BdsPacks.Scope.WORLD,
                BdsPacks.Scope.GLOBAL,
                BdsPacks.Scope.DEV,
                BdsPacks.Scope.BUILTIN
            )
            order.forEach { scope ->
                val group = packs.filter { it.scope == scope }
                if (group.isEmpty()) return@forEach
                if (scope == BdsPacks.Scope.BUILTIN && !showBuiltin) {
                    McCard(title = stringResource(scopeTitle(scope))) {
                        Text(
                            stringResource(R.string.ad_builtin_collapsed, group.size),
                            color = Indigo,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { showBuiltin = true }
                        )
                    }
                    return@forEach
                }
                McCard(
                    title = stringResource(scopeTitle(scope)),
                    trailing = {
                        Text(
                            stringResource(R.string.ad_group_count, group.count { current.isEnabled(it) }, group.size),
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                ) {
                    group.forEachIndexed { index, pack ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        AddonRow(
                            pack = pack,
                            enabled = current.isEnabled(pack),
                            switchable = scope != BdsPacks.Scope.BUILTIN && !running.isRunning,
                            onToggle = { vm.setAddonEnabled(pack, it) }
                        )
                    }
                }
            }
        }

        if (orphans.isNotEmpty()) {
            McCard(title = stringResource(R.string.ad_orphan_title), collapsible = true, initiallyExpanded = false) {
                Text(stringResource(R.string.ad_orphan_desc), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                orphans.forEach { e ->
                    Text("${e.uuid}  ${e.versionText}", fontSize = 10.sp, color = Muted)
                }
            }
        }

        // 包目录里躺着的 .mcpack / .mcaddon：BDS 对 .mcaddon 是直接报错的，
        // 这里给一键解压，省得用户去文件管理里手工拆。
        val archives = current.archives.filter { it.kind == tab }
        if (archives.isNotEmpty()) {
            McCard(title = stringResource(R.string.ad_archives_title)) {
                Text(stringResource(R.string.ad_archives_desc), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                archives.forEachIndexed { index, a ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.file.name, fontSize = 12.sp, maxLines = 1)
                            Text(Worlds.formatSize(a.file.length()), fontSize = 10.sp, color = Muted)
                        }
                        TextButton(
                            onClick = { vm.installAddonArchive(a) },
                            enabled = !running.isRunning
                        ) { Text(stringResource(R.string.ad_install_archive), fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

private fun scopeTitle(scope: BdsPacks.Scope): Int = when (scope) {
    BdsPacks.Scope.WORLD -> R.string.ad_scope_world
    BdsPacks.Scope.GLOBAL -> R.string.ad_scope_global
    BdsPacks.Scope.DEV -> R.string.ad_scope_dev
    BdsPacks.Scope.BUILTIN -> R.string.ad_scope_system
}

/** 从 SAF 的 URI 里取显示名（取不到就返回空串，安装时会按 uuid/时间戳兜底命名）。 */
private fun displayName(context: Context, uri: android.net.Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
            .orEmpty()
    }.getOrDefault("")

@Composable
private fun AddonRow(
    pack: BdsPacks.Pack,
    enabled: Boolean,
    switchable: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Outlined.Extension,
            null,
            tint = if (enabled) Indigo else Muted,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                pack.name.ifBlank { pack.dirName },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                buildString {
                    append("v${pack.versionText}")
                    if (pack.sizeBytes > 0) {
                        append(" · ")
                        append(Worlds.formatSize(pack.sizeBytes))
                    }
                },
                fontSize = 10.sp,
                color = Muted,
                maxLines = 1
            )
            if (pack.problems.isNotEmpty()) {
                Text(pack.problems.first(), fontSize = 10.sp, color = Coral, maxLines = 2)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = enabled, enabled = switchable, onCheckedChange = onToggle)
    }
}
