package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.data.FrpProfile
import com.mineserve.mobile.data.FrpProfileKind
import com.mineserve.mobile.data.FrpProfiles
import com.mineserve.mobile.server.tunnel.MeFrpApi
import com.mineserve.mobile.server.tunnel.SakuraFrpApi
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.DebouncedTextField
import com.mineserve.mobile.ui.HintNote
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.SegPill
import com.mineserve.mobile.ui.WarnNote
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * FRP 配置编辑页（全屏子页面）。
 *
 * ## 为什么是全屏页而不是弹窗
 *
 * 一份配置的字段量不小：名称、类型、以及三种类型各有自己的输入 +
 * 「拉取隧道列表 → 选中一条 → 可能还要新建隧道」。塞进对话框会得到一个
 * 需要内部滚动的窄条，选项一多就互相遮挡；全屏页能像正常表单那样排布。
 *
 * ## 三种类型对应三份已存在的后端
 *
 * [FrpProfileKind] 只决定「存哪些字段」，启动时由 [FrpProfiles.applyTo]
 * 投影给既有的 FrpBackend / SakuraFrpBackend / MeFrpBackend —— 本页不引入
 * 任何新的运行逻辑，只是把原先散在设置区里的输入搬到能保存多份的地方。
 *
 * @param profileId 要编辑的配置 id；null 表示新建。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FrpProfileEditorScreen(
    vm: McViewModel,
    profileId: String?,
    onBack: () -> Unit
) {
    val config by vm.config.collectAsState()
    val all = config.frpProfiles
    val original = all.firstOrNull { it.id == profileId }
    val isNew = original == null

    // 新配置在这里就把 id 定下来：之后无论保存几次、改几次名，它都是同一份配置，
    // 不会因为「还没保存所以没有 id」而重复新增。
    val draftId = remember(profileId) { original?.id ?: UUID.randomUUID().toString() }

    var name by remember(profileId) {
        mutableStateOf(original?.name ?: FrpProfiles.defaultName(FrpProfileKind.CustomToml, all))
    }
    var kind by remember(profileId) { mutableStateOf(original?.kind ?: FrpProfileKind.CustomToml) }
    var configText by remember(profileId) { mutableStateOf(original?.configText.orEmpty()) }
    var token by remember(profileId) { mutableStateOf(original?.token.orEmpty()) }
    var tunnelId by remember(profileId) { mutableStateOf(original?.tunnelId.orEmpty()) }
    var tunnelLabel by remember(profileId) { mutableStateOf(original?.tunnelLabel.orEmpty()) }
    var error by remember(profileId) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(profileId) { mutableStateOf(false) }

    /**
     * 切换类型时同步默认名。
     *
     * 只在「名称还是当前类型的自动默认名」时才覆盖 —— 用户自己起的名字
     * （哪怕和默认名撞了）一律不碰，否则切一下类型就把人写的名字冲掉了。
     */
    fun changeKind(next: FrpProfileKind) {
        val wasAuto = name.isBlank() || name == FrpProfiles.defaultName(kind, all)
        kind = next
        if (wasAuto) name = FrpProfiles.defaultName(next, all)
        // 类型变了，之前选中的隧道属于上一个平台，必须清掉：
        // 否则会把 A 平台的隧道 id 送到 B 平台，启动时必然失败。
        tunnelId = ""
        tunnelLabel = ""
        error = null
    }

    fun currentDraft(): FrpProfile = FrpProfile(
        id = draftId,
        name = name.trim().ifBlank { FrpProfiles.defaultName(kind, all) },
        kind = kind,
        configText = configText,
        token = token.trim(),
        tunnelId = tunnelId,
        tunnelLabel = tunnelLabel
    )

    Scaffold(
        // 嵌套在外层 Scaffold 内：insets 已由外层消费，避免顶部重复留白
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            BackBar(
                title = stringResource(
                    if (isNew) R.string.net_frp_edit_title_new else R.string.net_frp_edit_title_edit
                ),
                onBack = onBack
            )

            // ── 名称与类型 ────────────────────────────────────
            McCard(title = stringResource(R.string.net_frp_edit_section_basic)) {
                DebouncedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.net_frp_edit_name)) },
                    placeholder = { Text(stringResource(R.string.net_frp_edit_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // 重名只提醒不拦截：两台机器叫同一个名字是合理的，
                // 真正的唯一标识是 id，不是名字。
                if (FrpProfiles.isNameTaken(name, all, draftId)) {
                    Spacer(Modifier.height(6.dp))
                    WarnNote(stringResource(R.string.net_frp_edit_name_dup))
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.net_frp_edit_kind),
                    color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SegPill(
                        text = stringResource(R.string.net_frp_edit_kind_toml),
                        selected = kind == FrpProfileKind.CustomToml,
                        compact = true,
                        onClick = { changeKind(FrpProfileKind.CustomToml) }
                    )
                    SegPill(
                        text = stringResource(R.string.net_frp_edit_kind_sakura),
                        selected = kind == FrpProfileKind.SakuraFrp,
                        compact = true,
                        onClick = { changeKind(FrpProfileKind.SakuraFrp) }
                    )
                    SegPill(
                        text = stringResource(R.string.net_frp_edit_kind_me),
                        selected = kind == FrpProfileKind.MeFrp,
                        compact = true,
                        onClick = { changeKind(FrpProfileKind.MeFrp) }
                    )
                }
            }

            // ── 类型专属内容 ──────────────────────────────────
            when (kind) {
                FrpProfileKind.CustomToml -> McCard(title = stringResource(R.string.s599)) {
                    DebouncedTextField(
                        value = configText,
                        onValueChange = { configText = it },
                        placeholder = { Text(stringResource(R.string.s600)) },
                        minLines = 6,
                        maxLines = 14,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    HintNote(stringResource(R.string.s601))
                    Spacer(Modifier.height(4.dp))
                    // 端口提示里带上真源端口：自建 frp 最容易填错的就是这里
                    HintNote(stringResource(R.string.net_frp_edit_toml_note, serverPortOf(vm)))
                }

                FrpProfileKind.SakuraFrp -> McCard(title = stringResource(R.string.net_frp_edit_kind_sakura)) {
                    TokenField(
                        token = token,
                        onTokenChange = { token = it },
                        hint = stringResource(R.string.net_frp_edit_token_hint_sakura)
                    )
                    Spacer(Modifier.height(12.dp))
                    SakuraTunnelSection(
                        token = token.trim(),
                        selectedTunnelId = tunnelId,
                        defaultPort = serverPortOf(vm),
                        onSelect = { t ->
                            tunnelId = t.id
                            tunnelLabel = FrpProfiles.tunnelLabel(
                                nodeName = t.nodeId,
                                type = t.type,
                                localIp = t.localIp,
                                localPort = t.localPort,
                                remote = t.remoteAddress.ifBlank { t.remotePort?.toString().orEmpty() }
                            )
                            error = null
                        }
                    )
                }

                FrpProfileKind.MeFrp -> McCard(title = stringResource(R.string.net_frp_edit_kind_me)) {
                    TokenField(
                        token = token,
                        onTokenChange = { token = it },
                        hint = stringResource(R.string.net_frp_edit_token_hint_me)
                    )
                    Spacer(Modifier.height(12.dp))
                    MeFrpTunnelSection(
                        token = token.trim(),
                        selectedTunnelId = tunnelId,
                        onSelect = { p ->
                            tunnelId = p.id
                            tunnelLabel = FrpProfiles.tunnelLabel(
                                nodeName = p.nodeName,
                                type = p.type,
                                localIp = p.localIp,
                                localPort = p.localPort,
                                remote = p.remoteAddress.ifBlank { p.remotePort?.toString().orEmpty() }
                            )
                            error = null
                        }
                    )
                }
            }

            // ── 校验提示 + 操作 ───────────────────────────────
            error?.let {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.padding(horizontal = 14.dp)) { WarnNote(it) }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val draft = currentDraft()
                    val problem = FrpProfiles.validationError(draft)
                    if (problem != null) {
                        error = problem
                    } else {
                        vm.saveFrpProfile(draft)
                        onBack()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
            ) {
                Text(stringResource(R.string.net_frp_edit_save), color = Color.White)
            }

            if (!isNew) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                ) {
                    Text(stringResource(R.string.net_frp_edit_delete), color = Coral)
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.net_frp_delete_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text(stringResource(R.string.net_frp_delete_message, name), fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        vm.deleteFrpProfile(draftId)
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                    shape = McShape.Control
                ) { Text(stringResource(R.string.net_frp_dialog_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.s402)) }
            }
        )
    }
}

/** 取端口真源。集中一处，避免页面里到处写 subscribe。 */
@Composable
private fun serverPortOf(vm: McViewModel): Int = vm.serverPort.collectAsState().value

// ── 通用输入 ──────────────────────────────────────────────────

@Composable
private fun TokenField(token: String, onTokenChange: (String) -> Unit, hint: String) {
    DebouncedTextField(
        value = token,
        onValueChange = onTokenChange,
        label = { Text(stringResource(R.string.net_frp_edit_token)) },
        placeholder = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

// ── SakuraFrp 隧道选择 ────────────────────────────────────────

/**
 * SakuraFrp 隧道列表 + 新建隧道。
 *
 * 沿用原有实现（SakuraFrpApi 的四个接口 + 节点选择弹窗），只是把选中结果
 * 从「写进全局配置的 sakuraTunnelId」改成「回报给本页草稿」——
 * 好处是没保存前不会污染当前生效的配置。
 *
 * `remember(token)` 刻意以令牌为键：令牌一改就整体作废，避免把上一个账号的
 * 隧道显示成当前令牌可用的隧道（那样用户会选中一条自己无权启动的隧道）。
 */
@Composable
private fun SakuraTunnelSection(
    token: String,
    selectedTunnelId: String,
    defaultPort: Int,
    onSelect: (SakuraFrpApi.Tunnel) -> Unit
) {
    val scope = rememberCoroutineScope()
    var account by remember(token) { mutableStateOf<SakuraFrpApi.Account?>(null) }
    var tunnels by remember(token) { mutableStateOf<List<SakuraFrpApi.Tunnel>>(emptyList()) }
    var nodes by remember(token) { mutableStateOf<List<SakuraFrpApi.Node>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember(token) { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }

    fun refresh(withNodes: Boolean) {
        if (token.isBlank() || loading) return
        // scope 是 Main 作用域：网络请求切到 IO，Compose 状态只在 Main 线程赋值
        scope.launch {
            loading = true
            message = ""
            try {
                val info = withContext(Dispatchers.IO) { SakuraFrpApi.userInfo(token) }
                val list = withContext(Dispatchers.IO) { SakuraFrpApi.tunnelList(token) }
                account = info
                tunnels = list
                if (withNodes) {
                    nodes = withContext(Dispatchers.IO) { SakuraFrpApi.nodeList(token, info.level) }
                }
            } catch (e: Exception) {
                message = e.message ?: com.mineserve.mobile.McApplication.get().getString(R.string.frpx_fetch_failed)
            } finally {
                loading = false
            }
        }
    }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { refresh(withNodes = false) },
                enabled = token.isNotBlank() && !loading,
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.size(6.dp))
                }
                Text(stringResource(R.string.net_frp_edit_fetch), fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    if (nodes.isEmpty()) refresh(withNodes = true)
                    showCreate = true
                },
                enabled = token.isNotBlank() && !loading,
                shape = RoundedCornerShape(10.dp)
            ) { Text(stringResource(R.string.net_frp_edit_create_tunnel), fontSize = 12.sp) }
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(message, color = Coral, fontSize = 11.sp)
        }

        account?.let { acc ->
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.net_frp_edit_account,
                    acc.username,
                    acc.group,
                    "${tunnels.size}" + (acc.maxTunnels?.let { "/$it" } ?: "")
                ),
                color = Muted, fontSize = 11.sp
            )
        }

        if (tunnels.isEmpty() && !loading) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.net_frp_edit_empty_sakura), color = Muted, fontSize = 11.sp)
        }

        tunnels.forEach { t ->
            TunnelRow(
                selected = t.id == selectedTunnelId,
                online = t.online,
                title = t.name + if (t.id == selectedTunnelId) stringResource(R.string.net_frp_edit_selected) else "",
                subtitle = FrpProfiles.tunnelLabel(
                    nodeName = t.nodeId,
                    type = t.type,
                    localIp = t.localIp,
                    localPort = t.localPort,
                    remote = t.remoteAddress.ifBlank { t.remotePort?.toString().orEmpty() }
                ),
                onClick = { onSelect(t) }
            )
        }
    }

    if (showCreate) {
        SakuraCreateTunnelDialog(
            nodes = nodes,
            defaultPort = defaultPort,
            onDismiss = { showCreate = false },
            onConfirm = { node, name, type, port ->
                showCreate = false
                scope.launch {
                    loading = true
                    message = ""
                    try {
                        withContext(Dispatchers.IO) {
                            SakuraFrpApi.createTunnel(token, node.id, name, type, "127.0.0.1", port)
                        }
                        refresh(withNodes = false)
                    } catch (e: Exception) {
                        message = e.message ?: com.mineserve.mobile.McApplication.get().getString(R.string.frpx_create_failed)
                    } finally {
                        loading = false
                    }
                }
            }
        )
    }
}

// ── ME Frp 隧道选择 ───────────────────────────────────────────

/**
 * ME Frp 隧道列表。
 *
 * 平台不提供「建隧道」接口，隧道只能在其网站上创建，所以这里没有新建按钮，
 * 空列表时给出可执行的指引而不是一个点了会失败的按钮。
 */
@Composable
private fun MeFrpTunnelSection(
    token: String,
    selectedTunnelId: String,
    onSelect: (MeFrpApi.Proxy) -> Unit
) {
    val scope = rememberCoroutineScope()
    var account by remember(token) { mutableStateOf<MeFrpApi.Account?>(null) }
    var proxies by remember(token) { mutableStateOf<List<MeFrpApi.Proxy>>(emptyList()) }
    var message by remember(token) { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        if (token.isBlank() || loading) return
        scope.launch {
            loading = true
            message = ""
            try {
                val acc = withContext(Dispatchers.IO) { MeFrpApi.userInfo(token) }
                val list = withContext(Dispatchers.IO) { MeFrpApi.proxyList(token) }
                account = acc
                proxies = list
            } catch (e: Exception) {
                message = e.message ?: com.mineserve.mobile.McApplication.get().getString(R.string.frpx_fetch_failed)
            } finally {
                loading = false
            }
        }
    }

    Column {
        Button(
            onClick = { refresh() },
            enabled = token.isNotBlank() && !loading,
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.size(6.dp))
            }
            Text(stringResource(R.string.net_frp_edit_fetch), fontSize = 12.sp)
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(message, color = Coral, fontSize = 11.sp)
        }

        account?.let { acc ->
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.net_frp_edit_account,
                    acc.username,
                    acc.group,
                    "${proxies.size}" + (acc.maxTunnels?.let { "/$it" } ?: "")
                ),
                color = Muted, fontSize = 11.sp
            )
        }

        if (proxies.isEmpty() && !loading) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.net_frp_edit_empty_me), color = Muted, fontSize = 11.sp)
        }

        proxies.forEach { p ->
            TunnelRow(
                selected = p.id == selectedTunnelId,
                online = p.online,
                title = p.name + if (p.id == selectedTunnelId) stringResource(R.string.net_frp_edit_selected) else "",
                subtitle = FrpProfiles.tunnelLabel(
                    nodeName = p.nodeName,
                    type = p.type,
                    localIp = p.localIp,
                    localPort = p.localPort,
                    remote = p.remoteAddress.ifBlank { p.remotePort?.toString().orEmpty() }
                ),
                onClick = { onSelect(p) }
            )
        }
    }
}

/** 隧道列表的一行：在线指示灯 + 名称 + 摘要。加了截断，长 IPv6 地址不会撑破卡片。 */
@Composable
private fun TunnelRow(
    selected: Boolean,
    online: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) IndigoSoft else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (online) Mint else Muted, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                color = Muted,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── SakuraFrp 新建隧道弹窗 ────────────────────────────────────

@Composable
private fun SakuraCreateTunnelDialog(
    nodes: List<SakuraFrpApi.Node>,
    defaultPort: Int,
    onDismiss: () -> Unit,
    onConfirm: (node: SakuraFrpApi.Node, name: String, type: String, port: Int) -> Unit
) {
    var selectedNode by remember { mutableStateOf(nodes.firstOrNull { it.online }) }
    var nodeMenu by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("Minexa") }
    var type by remember { mutableStateOf("tcp") }
    var portText by remember { mutableStateOf(defaultPort.toString()) }
    val port = portText.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.net_frp_edit_sakura_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (nodes.isEmpty()) {
                    Text(stringResource(R.string.net_frp_edit_sakura_node_empty), color = Coral, fontSize = 11.sp)
                }
                Box {
                    OutlinedTextField(
                        value = selectedNode?.let { "${it.name}（${it.id}）" }
                            ?: stringResource(R.string.net_frp_edit_sakura_node_pick),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.net_frp_edit_sakura_node)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .clickable { if (nodes.isNotEmpty()) nodeMenu = true }
                    )
                    DropdownMenu(
                        expanded = nodeMenu,
                        onDismissRequest = { nodeMenu = false }
                    ) {
                        nodes.forEach { node ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${node.name}（${node.id}）" +
                                            if (node.online) "" else stringResource(R.string.net_frp_edit_sakura_offline)
                                    )
                                },
                                onClick = { selectedNode = node; nodeMenu = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.net_frp_edit_sakura_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("tcp", "udp").forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t.uppercase()) }
                        )
                    }
                }
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.net_frp_edit_sakura_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val node = selectedNode ?: return@Button
                    onConfirm(node, name.ifBlank { "Minexa" }, type, port ?: defaultPort)
                },
                enabled = selectedNode != null && port != null && name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = McShape.Control
            ) { Text(stringResource(R.string.net_frp_edit_sakura_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.s402)) }
        }
    )
}
