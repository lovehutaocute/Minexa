package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.data.FrpLocalTarget
import com.mineserve.mobile.data.FrpProfile
import com.mineserve.mobile.data.FrpProfileKind
import com.mineserve.mobile.data.FrpProfiles
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.server.tunnel.StunDiagnostics
import com.mineserve.mobile.server.tunnel.easytier.EasyTierEngine
import com.mineserve.mobile.server.tunnel.easytier.EasyTierSettings
import com.mineserve.mobile.ui.AddressCopyRow
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.DebouncedTextField
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.HintNote
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.StatLine
import com.mineserve.mobile.ui.StatusPill
import com.mineserve.mobile.ui.WarnNote
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.FieldBg
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.MintSoft
import com.mineserve.mobile.ui.theme.Muted
import com.mineserve.mobile.ui.theme.TrackBg
import kotlinx.coroutines.flow.collectLatest

/**
 * 网络页。
 *
 * ## 组织方式
 *
 * 页面只有**三条独立的通道**，各自一张卡片、各自一套启停按钮：
 *
 * 1. 本地连接地址 —— 本机与局域网怎么连（不涉及公网）；
 * 2. FRP 卡 —— frp 家族（自建 frp / SakuraFrp / ME Frp）共用一条通道，
 *    靠「多份配置」切换具体用哪一份；
 * 3. STUN 卡 —— 自成一通道，可与 FRP 同时运行；
 * 4. EasyTier 卡 —— App 内 VpnService 组网，与前两条互不干扰。
 *
 * FRP 与 STUN 面向「谁拿到地址谁就能进」；EasyTier 相反 —— 它把服务器和玩家
 * 拉进同一个虚拟局域网，玩家按**局域网的方式**连进来。所以那张卡上没有「公网地址」，
 * 只有「玩家连接地址」，以及一个把网络名/密钥交给玩家的入口（引导页 + 二维码）。
 *
 * 原先那张「连接与穿透状态」卡已删除：它把所有后端的状态糊成一份，
 * 既表达不了「两条同时在跑」，其中的速度/流量统计也只有已下线 bore 后端会上报。
 * 现在每张卡订阅自己的状态流，显示的都是该通道的真实运行结果。
 *
 * @param onOpenFrpEditor 打开 FRP 配置编辑页；参数是配置 id，null 表示新建。
 * @param onOpenEasyTierGuide 打开组网引导页（邀请玩家）。
 * @param onOpenEasyTierEngine 打开依赖页的 EasyTier 引擎下载卡片（引擎未下载时用）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkScreen(
    vm: McViewModel,
    onBack: () -> Unit,
    showBackBar: Boolean = true,
    onOpenFrpEditor: (String?) -> Unit = {},
    onOpenEasyTierGuide: () -> Unit = {},
    onOpenEasyTierEngine: () -> Unit = {}
) {
    val config by vm.config.collectAsState()
    // 端口真源：服务端配置文件里的 server-port（未生成时退回 localPort）。
    val serverPort by vm.serverPort.collectAsState()
    val lanIp by vm.lanIp.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val frpState by vm.frpState.collectAsState()
    val stunState by vm.stunState.collectAsState()
    val easyTierState by vm.easyTierState.collectAsState()
    val stunDiag by vm.stunDiagnostics.collectAsState()
    val consoleLines by vm.consoleLines.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.refreshLanIp() }
    // 端口真源也在这里补读一次：用户可能在别处（文件管理器、服务端配置页）改过端口，
    // 只靠「服务端配置页」那一次读取的话，本页地址栏会一直显示旧端口。
    LaunchedEffect(Unit) { vm.loadServerProperties() }
    // 组网身份补齐：网络名/密钥为空时自动生成一套。
    // 「填个网络名就能分享给任何人」的前提是用户不用自己想名字，也不该被一个空密钥卡住。
    LaunchedEffect(Unit) { vm.ensureEasyTierIdentity() }
    // 收集操作结果和错误消息，通过 Snackbar 显示（修复"点击无反应"Bug的关键）
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // 子页面时显示返回栏；作为底部 tab 时由 HeaderBlock 处理状态栏
            if (showBackBar) {
                BackBar(title = stringResource(R.string.s541), onBack = onBack)
            }
            HeaderBlock(
                eyebrow = stringResource(R.string.eyebrow_network),
                title = stringResource(R.string.s585),
                statusBarPadding = !showBackBar
            )

            // ── 1. 本地连接地址 ────────────────────────────────
            LocalAddressCard(
                vm = vm,
                serverPort = serverPort,
                lanIp = lanIp,
                serverRunning = serverState.isRunning,
                context = context
            )

            // ── 2. FRP ────────────────────────────────────────
            FrpCard(
                vm = vm,
                config = config,
                frpState = frpState,
                serverRunning = serverState.isRunning,
                serverPort = serverPort,
                onStart = { vm.startFrp() },
                onStop = { vm.stopFrp() },
                onOpenEditor = onOpenFrpEditor,
                onCopy = { url -> vm.copyPublicUrl(context, url) }
            )

            // ── 3. STUN 直连 ──────────────────────────────────
            StunCard(
                vm = vm,
                config = config,
                diagnostics = stunDiag,
                stunState = stunState,
                serverRunning = serverState.isRunning,
                onStart = { vm.startStun() },
                onStop = { vm.stopStun() },
                onCopy = { url -> vm.copyPublicUrl(context, url) }
            )

            // ── 4. EasyTier 组网 ──────────────────────────────
            EasyTierCard(
                vm = vm,
                config = config,
                easyTierState = easyTierState,
                serverRunning = serverState.isRunning,
                serverPort = serverPort,
                onStart = { vm.startEasyTier() },
                onStop = { vm.stopEasyTier() },
                onOpenGuide = onOpenEasyTierGuide,
                onOpenEnginePage = onOpenEasyTierEngine
            )

            // ── 5. 隧道日志 ───────────────────────────────────
            TunnelLogPreview(consoleLines, frpState, stunState)

            // ── 6. 免费 FRP 平台 ──────────────────────────────
            FreeFrpPlatformsCard(context)

            // ── 7. 操作指南 ───────────────────────────────────
            NetworkGuide()

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 复制文本到剪贴板（含说明标签，用于系统剪贴板隐私提示）。 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}

// ── 两张卡共用的骨架 ──────────────────────────────────────────
//
// 抽出来是为了让 FRP 与 STUN 的「状态怎么显示、按钮长什么样」出自同一份代码：
// 各写一遍迟早会漂移成两种视觉语言。

/** 状态 → 语义色与文案。全局唯一一份映射。 */
@Composable
private fun statusVisual(status: TunnelStatus): Triple<Color, String, Color> = when (status) {
    TunnelStatus.Running -> Triple(Mint, stringResource(R.string.net_status_running), Mint)
    TunnelStatus.Starting -> Triple(Coral, stringResource(R.string.net_status_starting), Coral)
    TunnelStatus.Failed -> Triple(Coral, stringResource(R.string.net_status_failed), Coral)
    TunnelStatus.Stopped -> Triple(Muted, stringResource(R.string.net_status_stopped), Muted)
    TunnelStatus.Idle -> Triple(Muted, stringResource(R.string.net_status_idle), Muted)
}

/** 状态行：指示灯/转圈 + 状态胶囊 +（可选）当前方式名。 */
@Composable
private fun TunnelStatusHeader(status: TunnelStatus, typeLabel: String?) {
    val (dotColor, text, color) = statusVisual(status)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status == TunnelStatus.Starting) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        Spacer(Modifier.size(8.dp))
        StatusPill(text, color)
        if (!typeLabel.isNullOrBlank()) {
            Spacer(Modifier.size(6.dp))
            Text("·", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.size(6.dp))
            Text(typeLabel, color = Muted, fontSize = 11.sp)
        }
    }
}

/** 启动/停止按钮行。两张卡必须用同一个，样式才不会漂。 */
@Composable
private fun TunnelActionRow(
    startText: String,
    stopText: String,
    startEnabled: Boolean,
    stopEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onStart,
            enabled = startEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = Indigo,
                disabledContainerColor = TrackBg
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(startText, color = Color.White)
        }
        Button(
            onClick = onStop,
            enabled = stopEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = Muted,
                disabledContainerColor = TrackBg
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Outlined.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stopText, color = Color.White)
        }
    }
}

/**
 * 运行前/运行中的提示条，三张卡共用。
 *
 * 「服务端没开」这条提醒必须留在卡里：穿透只是把流量转到本机端口，
 * 服务端没运行就没人接 —— 原先这条挂在已删除的总状态卡上。
 *
 * [serverStoppedText] 用来换掉「服务端未运行」那句：默认文案里点名要按「启动穿透」，
 * 而组网卡上的按钮叫「启动组网」，沿用默认文案会把人指向一个不存在的按钮。
 */
@Composable
private fun TunnelPreconditionNotes(
    state: TunnelState,
    serverRunning: Boolean,
    serverStoppedText: String? = null
) {
    if (!serverRunning && state.status != TunnelStatus.Running) {
        Spacer(Modifier.height(10.dp))
        WarnNote(serverStoppedText ?: stringResource(R.string.net_tunnel_server_stopped))
    }
    if (state.status == TunnelStatus.Failed && state.errorMessage.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        WarnNote(state.errorMessage)
    }
    if (state.status == TunnelStatus.Running && state.publicUrl.isBlank()) {
        // 隧道通了但拿不到入口地址（自建 frps 未写 remotePort、平台未下发等）。
        // 不是错误，所以给指引而不是报警。
        Spacer(Modifier.height(10.dp))
        WarnNote(stringResource(R.string.net_public_url_unknown))
    }
}

/**
 * 公网地址展示块。
 *
 * **只在运行中显示**：隧道停止后地址即失效，继续摆在界面上会被当成可用地址发出去。
 */
@Composable
private fun PublicAddressBlock(
    label: String,
    url: String,
    state: TunnelState,
    onCopy: () -> Unit
) {
    if (state.status != TunnelStatus.Running || url.isBlank()) return
    Spacer(Modifier.height(12.dp))
    AddressCopyRow(
        label = label,
        value = url,
        onCopy = onCopy,
        accent = Mint,
        containerColor = MintSoft,
        borderColor = Mint
    )
}

// ── 1. 本地连接地址 ───────────────────────────────────────────

@Composable
private fun LocalAddressCard(
    vm: McViewModel,
    serverPort: Int,
    lanIp: String,
    serverRunning: Boolean,
    context: Context
) {
    McCard(title = stringResource(R.string.s586)) {
        // 服务端运行状态：与两张隧道卡同一种「胶囊」表达，不再是裸文字
        StatusPill(
            if (serverRunning) stringResource(R.string.s587) else stringResource(R.string.s280),
            if (serverRunning) Mint else Muted
        )
        Spacer(Modifier.height(12.dp))

        // 本机回环地址（同设备直连测试用）
        Text(stringResource(R.string.s588), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        AddressCopyRow(
            label = stringResource(R.string.s588),
            value = "127.0.0.1:$serverPort",
            onCopy = { copyToClipboard(context, "MC Server", "127.0.0.1:$serverPort") }
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.s589), color = Muted, fontSize = 10.sp)

        Spacer(Modifier.height(12.dp))

        // 局域网地址（同网络下其他设备连接用）
        Text(stringResource(R.string.s590), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        val lanAddress = if (lanIp == "--") "--:$serverPort" else "$lanIp:$serverPort"
        AddressCopyRow(
            label = stringResource(R.string.s592),
            value = lanAddress,
            onCopy = { vm.copyServerAddress(context) },
            icon = Icons.Outlined.Lan,
            accent = Mint,
            containerColor = MintSoft
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.s594),
                color = Muted, fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.refreshLanIp() }, modifier = Modifier.size(McIcon.TouchCompact)) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.s591),
                    tint = Muted,
                    modifier = Modifier.size(McIcon.Inline)
                )
            }
        }
    }
}

// ── 2. FRP 卡片 ───────────────────────────────────────────────

/**
 * FRP 卡。
 *
 * frp 家族（自建 frp / SakuraFrp / ME Frp）共用一条通道 —— 它们都由同一个
 * frpc 引擎进程承载，天然互斥。所以这里不提供「多条 FRP 同开」的入口，
 * 而是提供「多份配置、切换着用」。
 */
@Composable
private fun FrpCard(
    vm: McViewModel,
    config: McConfig,
    frpState: TunnelState,
    serverRunning: Boolean,
    serverPort: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenEditor: (String?) -> Unit,
    onCopy: (String) -> Unit
) {
    val profile = FrpProfiles.active(config)
    var showPicker by remember { mutableStateOf(false) }

    McCard(title = stringResource(R.string.net_frp_card_title)) {
        TunnelStatusHeader(frpState.status, frpState.activeType?.displayName)
        Spacer(Modifier.height(12.dp))

        if (profile == null) {
            // 空状态：把「下一步做什么」直接摆出来，而不是让用户对着一个禁用的启动键猜。
            HintNote(stringResource(R.string.net_frp_no_profile))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onOpenEditor(null) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null, tint = Indigo, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.net_frp_create), color = Indigo, fontSize = 12.sp)
            }
        } else {
            // 配置行本身就是「切换配置」的入口（整行可点），命中区域够大
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(FieldBg)
                    .clickable { showPicker = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.net_frp_profile_label),
                        color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        FrpProfiles.label(profile),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(R.string.net_frp_switch_hint),
                    tint = Indigo,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // 配置文本里的转发端口和真实端口对不上时必须提前说：这种情况隧道会「显示已通」，
        // 玩家却进不来，日志里只有一句 connection refused，从字面看不出是端口错了。
        // 运行中不提示 —— 那时启动链路已经按真实端口对齐过了。
        if (profile != null &&
            profile.kind == FrpProfileKind.CustomToml &&
            frpState.status != TunnelStatus.Running
        ) {
            val target = FrpLocalTarget.target(profile.configText)
            // 指向别的机器的配置是用户有意为之，不该拿游戏端口去提醒他。
            if (!target.foreignHost) {
                val only = target.only
                when {
                    target.ports.isNotEmpty() && !target.single -> {
                        Spacer(Modifier.height(8.dp))
                        WarnNote(stringResource(R.string.net_frp_port_ambiguous, serverPort))
                    }
                    only != null && only != serverPort -> {
                        Spacer(Modifier.height(8.dp))
                        WarnNote(stringResource(R.string.net_frp_port_mismatch, only, serverPort))
                    }
                }
            }
        }

        TunnelPreconditionNotes(frpState, serverRunning)
        PublicAddressBlock(
            label = stringResource(R.string.net_frp_address),
            url = frpState.publicUrl,
            state = frpState
        ) { onCopy(frpState.publicUrl) }

        Spacer(Modifier.height(12.dp))
        TunnelActionRow(
            startText = stringResource(R.string.net_frp_start),
            stopText = stringResource(R.string.net_frp_stop),
            // 没有配置时启动没有意义（一定会报错），所以在按钮层就拦住；
            // 「缺引擎」这类运行期问题不在按钮层拦，点了会给出后端真实错误。
            startEnabled = frpState.status != TunnelStatus.Starting && profile != null,
            stopEnabled = frpState.status == TunnelStatus.Running ||
                frpState.status == TunnelStatus.Starting,
            onStart = onStart,
            onStop = onStop
        )
    }

    if (showPicker) {
        FrpProfilePickerDialog(
            vm = vm,
            config = config,
            activeId = profile?.id,
            onDismiss = { showPicker = false },
            onOpenEditor = { id ->
                showPicker = false
                onOpenEditor(id)
            }
        )
    }
}

/**
 * 配置选择窗口。
 *
 * 交互定稿：**点任意一条 = 选中并自动关闭**（用户明确要求少一步操作），
 * 想编辑或删除再点该行右侧的小图标，不会误触发切换。
 */
@Composable
private fun FrpProfilePickerDialog(
    vm: McViewModel,
    config: McConfig,
    activeId: String?,
    onDismiss: () -> Unit,
    onOpenEditor: (String?) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<FrpProfile?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.net_frp_dialog_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                config.frpProfiles.forEach { p ->
                    val selected = p.id == activeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) IndigoSoft else Color.Transparent)
                            .clickable {
                                vm.setActiveFrpProfile(p.id)
                                onDismiss()
                            }
                            .padding(start = 4.dp, end = 0.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = {
                                vm.setActiveFrpProfile(p.id)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = Indigo)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                if (p.tunnelLabel.isBlank()) p.kind.displayName
                                else "${p.kind.displayName} · ${p.tunnelLabel}",
                                color = Muted, fontSize = 10.sp, maxLines = 2
                            )
                        }
                        IconButton(onClick = { onOpenEditor(p.id) }, modifier = Modifier.size(McIcon.TouchCompact)) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(R.string.net_frp_dialog_edit),
                                tint = Indigo, modifier = Modifier.size(McIcon.Inline)
                            )
                        }
                        IconButton(onClick = { pendingDelete = p }, modifier = Modifier.size(McIcon.TouchCompact)) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.net_frp_dialog_delete),
                                tint = Coral, modifier = Modifier.size(McIcon.Inline)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onOpenEditor(null) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, tint = Indigo, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.net_frp_create), color = Indigo, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.s402)) }
        }
    )

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.net_frp_delete_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text(stringResource(R.string.net_frp_delete_message, target.name), fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteFrpProfile(target.id)
                        pendingDelete = null
                        // 删光之后选择窗口没有内容可点，直接收起来
                        if (vm.config.value.frpProfiles.isEmpty()) onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                    shape = McShape.Control
                ) { Text(stringResource(R.string.net_frp_dialog_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.s402)) }
            }
        )
    }
}

// ── 3. STUN 直连卡片 ──────────────────────────────────────────

/**
 * STUN 直连卡。
 *
 * 与 FRP 卡**相互独立**：各自订阅自己的状态流、各自的启停按钮，
 * 因此可以同时运行、互不干扰。
 *
 * 界面上不再出现「NAT 等级 / 全锥形 / CGNAT」这类判定结论 —— 用户看不懂，
 * 也无法据此行动。取而代之的是一句能直接指导选择的说明
 * （会暴露家庭 IP、多数家庭宽带用不了、优先用 FRP），
 * 以及失败时后端给出的人话原因。
 */
@Composable
private fun StunCard(
    vm: McViewModel,
    config: McConfig,
    diagnostics: StunDiagnostics,
    stunState: TunnelState,
    serverRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: (String) -> Unit
) {
    var showAdvanced by remember { mutableStateOf(false) }
    val address = diagnostics.publicAddress.ifBlank { stunState.publicUrl }

    McCard(title = stringResource(R.string.stun_card_title)) {
        TunnelStatusHeader(stunState.status, null)
        Spacer(Modifier.height(10.dp))
        HintNote(stringResource(R.string.net_stun_ip_warning))

        TunnelPreconditionNotes(stunState, serverRunning)

        if (stunState.status == TunnelStatus.Running && address.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            AddressCopyRow(
                label = stringResource(R.string.stun_card_address),
                value = address,
                onCopy = { onCopy(address) },
                accent = Mint,
                containerColor = MintSoft,
                borderColor = Mint
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (diagnostics.portStable) stringResource(R.string.stun_card_address_hint)
                else stringResource(R.string.stun_card_address_drift, diagnostics.addressChanges),
                color = Muted, fontSize = 10.sp
            )
        } else if (stunState.status != TunnelStatus.Failed) {
            // 未启动（或正在启动）：只表明「地址会出现在这里」，不摆任何地址
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.net_stun_idle_hint), color = Muted, fontSize = 11.sp)
        }

        // 运行统计：全部来自真实运行结果，平时为 0 的不显示
        if (stunState.status == TunnelStatus.Running) {
            Spacer(Modifier.height(12.dp))
            StatLine(
                Icons.Outlined.Link,
                stringResource(R.string.stun_stat_conn, diagnostics.activeConnections)
            )
            if (diagnostics.idleTimeouts > 0) {
                StatLine(
                    Icons.Outlined.Info,
                    stringResource(R.string.stun_stat_idle, diagnostics.idleTimeouts)
                )
            }
            if (diagnostics.limitRejects > 0) {
                StatLine(
                    Icons.Outlined.DataUsage,
                    stringResource(R.string.stun_stat_limit, diagnostics.limitRejects)
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        TunnelActionRow(
            startText = stringResource(R.string.stun_card_start),
            stopText = stringResource(R.string.stun_card_stop),
            startEnabled = stunState.status != TunnelStatus.Starting,
            stopEnabled = stunState.status == TunnelStatus.Running ||
                stunState.status == TunnelStatus.Starting,
            onStart = onStart,
            onStop = onStop
        )

        // ── 高级设置（折叠） ───────────────────────────────────
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null, tint = Indigo, modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text(
                if (showAdvanced) stringResource(R.string.stun_advanced_hide)
                else stringResource(R.string.stun_advanced),
                color = Indigo, fontSize = 12.sp
            )
        }
        if (showAdvanced) {
            StunConfigSection(vm = vm, config = config)
        }
    }
}

/** STUN 配置里的单行开关。 */
@Composable
private fun StunToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
    }
}

@Composable
private fun StunConfigSection(vm: McViewModel, config: McConfig) {
    // 目标端口跟随真源（服务端配置文件里的 server-port），不再读 config.localPort。
    val serverPort by vm.serverPort.collectAsState()
    val followLocalPort = config.stunLocalPort <= 0
    Column {
        // 目标端口
        Text(stringResource(R.string.stun_target_port), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = followLocalPort,
                onCheckedChange = { follow ->
                    vm.updateConfig { it.copy(stunLocalPort = if (follow) 0 else serverPort) }
                }
            )
            Text(
                stringResource(R.string.stun_follow_local_port, serverPort),
                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp
            )
        }
        if (!followLocalPort) {
            Spacer(Modifier.height(6.dp))
            DebouncedTextField(
                value = config.stunLocalPort.toString(),
                onValueChange = { v ->
                    val port = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0
                    vm.updateConfig { it.copy(stunLocalPort = port) }
                },
                placeholder = { Text(stringResource(R.string.stun_target_port_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(12.dp))
        // 保活间隔
        Text(stringResource(R.string.stun_keepalive), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        DebouncedTextField(
            value = config.stunKeepAliveSeconds.toString(),
            onValueChange = { v ->
                val secs = v.filter(Char::isDigit).take(4).toIntOrNull() ?: 20
                vm.updateConfig { it.copy(stunKeepAliveSeconds = secs) }
            },
            placeholder = { Text("20") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        HintNote(stringResource(R.string.stun_keepalive_hint))

        Spacer(Modifier.height(12.dp))
        // 并发上限
        Text(stringResource(R.string.stun_max_conn), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        DebouncedTextField(
            value = config.stunMaxConnections.toString(),
            onValueChange = { v ->
                val n = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 128
                vm.updateConfig { it.copy(stunMaxConnections = n) }
            },
            placeholder = { Text("128") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
        // 开关项
        StunToggleRow(
            title = stringResource(R.string.stun_show_conn_log),
            checked = config.stunShowConnectionLog,
            onChange = { on -> vm.updateConfig { it.copy(stunShowConnectionLog = on) } }
        )

        Spacer(Modifier.height(4.dp))
        StunToggleRow(
            title = stringResource(R.string.stun_proxy_protocol),
            checked = config.stunProxyProtocol,
            onChange = { on -> vm.updateConfig { it.copy(stunProxyProtocol = on) } }
        )
        if (config.stunProxyProtocol) {
            Spacer(Modifier.height(6.dp))
            WarnNote(stringResource(R.string.stun_proxy_protocol_warn))
        }

        Spacer(Modifier.height(12.dp))
        // 自定义服务器
        Text(stringResource(R.string.stun_servers), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        DebouncedTextField(
            value = config.stunServers,
            onValueChange = { v -> vm.updateConfig { it.copy(stunServers = v) } },
            placeholder = { Text(stringResource(R.string.stun_servers_hint)) },
            singleLine = false,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        HintNote(stringResource(R.string.stun_servers_note))
    }
}

// ── 4. EasyTier 组网卡片 ──────────────────────────────────────

/**
 * EasyTier 组网卡。
 *
 * 与另外两张卡的**根本区别**：那两张是「把本机端口搬到公网」，玩家拿公网地址进；
 * 这一张是把服务器和玩家拉进同一个虚拟局域网，玩家用局域网地址进。所以：
 *
 * - 状态行下永远不出现「公网地址」，只有「玩家连接地址」；
 * - 身份信息（网络名 + 密钥）必须能整段交给玩家 —— 这就是「邀请玩家」那个入口存在的理由，
 *   让用户自己在两个输入框里抄给别人，抄错的概率远高于扫一张码。
 *
 * 启动前必须先要 VPN 授权：`establish()` 没有授权时只返回 null，既无异常也无错误码。
 * 授权框只能由 Activity 弹，所以这里用 ActivityResult 契约，而不是让 Service 自己试。
 */
@Composable
private fun EasyTierCard(
    vm: McViewModel,
    config: McConfig,
    easyTierState: TunnelState,
    serverRunning: Boolean,
    serverPort: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenEnginePage: () -> Unit
) {
    val context = LocalContext.current
    val available = vm.easyTierAvailable

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) onStart() else vm.notifyEasyTierVpnDenied()
    }

    // 虚拟 IP 与地址都在界面侧现算，不额外存一份：
    // 网络名或服务端端口一改，这里立刻跟着变，不会出现「说明文字里是旧地址」。
    // 端口是**转发端口**（非服务端端口）—— 两个监听不能占同一个端口。
    val virtualIp = config.easyTierVirtualIp.trim().ifBlank {
        val name = config.easyTierNetworkName.trim()
        if (name.isBlank()) "" else EasyTierSettings.deriveVirtualIp(name)
    }
    val address = EasyTierSettings.playerAddress(virtualIp, serverPort)
    val running = easyTierState.status == TunnelStatus.Running

    McCard(
        title = stringResource(R.string.easytier_card_title),
        trailing = if (available) {
            {
                IconButton(onClick = onOpenGuide, modifier = Modifier.size(McIcon.TouchCompact)) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.easytier_guide_entry),
                        tint = Indigo,
                        modifier = Modifier.size(McIcon.Inline)
                    )
                }
            }
        } else null
    ) {
        TunnelStatusHeader(easyTierState.status, null)
        Spacer(Modifier.height(10.dp))
        HintNote(stringResource(R.string.easytier_intro))

        if (!available) {
            // 红色醒目：引擎没下载意味着这张卡整个不可用，必须第一眼就看到原因和出口。
            // 别用 WarnNote —— 它是"提醒"级别，这里要的是"阻塞"级别。
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Coral.copy(alpha = 0.12f))
                    .border(1.dp, Coral, RoundedCornerShape(16.dp))
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = Coral,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.easytier_engine_card_missing),
                    color = Coral,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onOpenEnginePage) {
                    Text(
                        stringResource(R.string.easytier_engine_goto),
                        color = Coral,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ── 网络名 ────────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.easytier_network_name),
            color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        DebouncedTextField(
            value = config.easyTierNetworkName,
            onValueChange = { v -> vm.updateConfig { it.copy(easyTierNetworkName = v) } },
            enabled = !running && available,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.easytier_network_name_hint)) },
            modifier = Modifier.fillMaxWidth()
        )

        // ── 网络密钥 ──────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.easytier_network_secret),
                color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { vm.regenerateEasyTierSecret() },
                enabled = !running && available
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = null, tint = Indigo, modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.easytier_regenerate), color = Indigo, fontSize = 11.sp)
            }
        }
        DebouncedTextField(
            value = config.easyTierNetworkSecret,
            onValueChange = { v -> vm.updateConfig { it.copy(easyTierNetworkSecret = v) } },
            enabled = !running && available,
            singleLine = true,
            // 不设 placeholder：下方 HintNote 常驻显示同一句话，两者并存会重复一遍
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        HintNote(stringResource(R.string.easytier_secret_hint))

        // ── 玩家连接地址 ──────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        if (address.isBlank()) {
            Text(stringResource(R.string.easytier_address_needs_name), color = Muted, fontSize = 11.sp)
        } else {
            AddressCopyRow(
                label = stringResource(R.string.easytier_address),
                value = address,
                onCopy = { vm.copyPublicUrl(context, address) },
                icon = Icons.Outlined.Lan,
                accent = Mint,
                containerColor = MintSoft,
                borderColor = Mint
            )
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.easytier_address_hint), color = Muted, fontSize = 10.sp)
        }

        TunnelPreconditionNotes(
            easyTierState,
            serverRunning,
            serverStoppedText = stringResource(R.string.easytier_server_stopped)
        )

        // ── 启停 ──────────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        TunnelActionRow(
            startText = stringResource(R.string.easytier_start),
            stopText = stringResource(R.string.easytier_stop),
            startEnabled = available &&
                easyTierState.status != TunnelStatus.Starting &&
                config.easyTierNetworkName.isNotBlank() &&
                config.easyTierNetworkSecret.isNotBlank(),
            stopEnabled = easyTierState.status == TunnelStatus.Running ||
                easyTierState.status == TunnelStatus.Starting,
            onStart = {
                // 已授权时 prepareIntent 返回 null，直接起；否则先弹系统授权框
                val prepare = EasyTierEngine.prepareIntent(context)
                if (prepare == null) onStart() else vpnLauncher.launch(prepare)
            },
            onStop = onStop
        )

        if (available) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenGuide,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, tint = Indigo, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.easytier_guide_entry), color = Indigo, fontSize = 12.sp)
            }
        }
    }
}

// ── 5. 隧道日志预览 ───────────────────────────────────────────

@Composable
private fun TunnelLogPreview(
    consoleLines: List<String>,
    frpState: TunnelState,
    stunState: TunnelState
) {
    // 过滤出隧道相关日志（两条通道都汇进同一个 [tunnel] 前缀）
    val tunnelLogs: List<String> = remember(consoleLines) {
        consoleLines.filter { it.contains("[tunnel]") }
    }

    var showFullLog by remember { mutableStateOf(false) }
    // 连接数取「正在运行」那条通道的：两条都在跑时优先 FRP（与聚合语义一致）。
    val running = when {
        frpState.status == TunnelStatus.Running -> frpState
        stunState.status == TunnelStatus.Running -> stunState
        else -> null
    }

    McCard(title = stringResource(R.string.net_section_log)) {
        if (tunnelLogs.isEmpty()) {
            Text(stringResource(R.string.net_log_empty), color = Muted, fontSize = 11.sp)
        } else {
            // 最近 8 条预览
            tunnelLogs.takeLast(8).forEach { line ->
                Text(
                    line,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Muted,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showFullLog = true },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Outlined.OpenInFull,
                    contentDescription = null,
                    tint = Indigo,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(R.string.net_log_expand) + " (${tunnelLogs.size})",
                    fontSize = 12.sp,
                    color = Indigo
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.net_log_connection),
            color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        if (running != null) {
            StatLine(
                Icons.Outlined.Link,
                stringResource(R.string.net_stats_connections, running.activeConnections)
            )
            Spacer(Modifier.height(6.dp))
        }
        HintNote(stringResource(R.string.net_log_connection_hint))
    }

    // 全屏日志 Dialog
    if (showFullLog) {
        AlertDialog(
            onDismissRequest = { showFullLog = false },
            title = {
                Text(stringResource(R.string.s618, tunnelLogs.size), fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (tunnelLogs.isEmpty()) {
                        Text(stringResource(R.string.s619), color = Muted, fontSize = 13.sp)
                    } else {
                        tunnelLogs.forEach { line ->
                            Text(
                                line,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Muted,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showFullLog = false }, shape = McShape.Control) {
                    Text(stringResource(R.string.s620))
                }
            }
        )
    }
}

// ── 5. 免费 FRP 平台 ──────────────────────────────────────────

/** 免费 FRP 平台清单（官方网址，点击跳转浏览器） */
private data class FrpPlatform(val nameRes: Int, val url: String)

private val freeFrpPlatforms = listOf(
    FrpPlatform(R.string.nwx_platform_openfrp, "https://www.openfrp.net/"),
    FrpPlatform(R.string.nwx_platform_chmlfrp, "https://www.chmlfrp.cn/"),
    FrpPlatform(R.string.nwx_platform_starry, "https://frp.starryfrp.com/"),
    FrpPlatform(R.string.nwx_platform_sakura, "https://www.natfrp.com/"),
    FrpPlatform(R.string.nwx_platform_mefrp, "https://www.mefrp.com/")
)

@Composable
private fun FreeFrpPlatformsCard(context: Context) {
    McCard(title = stringResource(R.string.s654)) {
        Text(
            stringResource(R.string.s655),
            color = Muted, fontSize = 10.sp, lineHeight = 14.sp
        )
        Spacer(Modifier.height(10.dp))
        freeFrpPlatforms.forEach { p ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(IndigoSoft)
                    .clickable {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
                        } catch (e: Exception) {
                            // 设备上无可用浏览器时静默忽略
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(p.nameRes), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(p.url, color = Indigo, fontSize = 10.sp)
                }
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.s656),
                    tint = Indigo,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

// ── 6. 操作指南 ───────────────────────────────────────────────

/**
 * 操作指南。
 *
 * 默认**收起**：页面顶部现在有两张大卡片，指南再默认展开会把页面拉得很长。
 */
@Composable
private fun NetworkGuide() {
    var expanded by remember { mutableStateOf(false) }

    McCard(title = stringResource(R.string.s621)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.s622, if (expanded) stringResource(R.string.s620) else stringResource(R.string.s623)),
                color = Indigo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = Indigo
            )
        }

        if (expanded) {
            Column {
                Text(
                    stringResource(R.string.net_guide_intro),
                    color = Muted, fontSize = 11.sp, lineHeight = 16.sp
                )
                Spacer(Modifier.height(10.dp))
                // 六步平铺，靠序号说话。不再分「FRP / STUN」小节 ——
                // 小节标题会和步骤标题重复一遍，反而更难读。
                GuideStep("1", stringResource(R.string.net_guide_frp_t), stringResource(R.string.net_guide_frp_d))
                GuideStep("2", stringResource(R.string.net_guide_switch_t), stringResource(R.string.net_guide_switch_d))
                GuideStep("3", stringResource(R.string.net_guide_frp_run_t), stringResource(R.string.net_guide_frp_run_d))
                GuideStep("4", stringResource(R.string.net_guide_stun_t), stringResource(R.string.net_guide_stun_d))
                GuideStep("5", stringResource(R.string.net_guide_both_t), stringResource(R.string.net_guide_both_d))
                GuideStep("6", stringResource(R.string.net_guide_stop_t), stringResource(R.string.net_guide_stop_d))
            }
        }
    }
}

@Composable
private fun GuideStep(step: String, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Indigo),
            contentAlignment = Alignment.Center
        ) {
            Text(step, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
