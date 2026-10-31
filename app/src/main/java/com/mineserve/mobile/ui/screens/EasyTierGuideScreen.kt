package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.server.tunnel.easytier.EasyTierSettings
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.HintNote
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.components.QrCodeImage
import com.mineserve.mobile.ui.theme.FieldBg
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.MintSoft
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest

/**
 * 组网引导页：把「玩家该做什么」交给玩家。
 *
 * ## 为什么是一整页而不是一个弹窗
 *
 * 这一页要被**转发出去**，不是给房主看的。房主打开它的动作是「截图/复制」，
 * 内容必须能独立成立：三步图文 + 一张码 + 三个值。弹窗里塞这些会滚成一条缝，
 * 截图出来缺半截。
 *
 * ## 二维码里是什么
 *
 * 就是「复制全部说明」那份文本（见 [McViewModel.easyTierShareText]）。
 * 两处共用同一份文本不是偷懒 —— 分成两份之后必然出现「码里是旧密钥、文字里是新的」
 * 这种只能靠玩家报错才能发现的不一致。
 */
@Composable
fun EasyTierGuideScreen(vm: McViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    val serverPort by vm.serverPort.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) { vm.loadServerProperties() }

    val networkName = config.easyTierNetworkName.trim()
    val secret = config.easyTierNetworkSecret.trim()
    // 与网络页那张卡同一套派生规则，改一处必须两处一起改。
    // 端口是转发端口（25566 一类），不是服务端端口 —— 两个监听不能占同一个端口。
    val virtualIp = config.easyTierVirtualIp.trim().ifBlank {
        if (networkName.isBlank()) "" else EasyTierSettings.deriveVirtualIp(networkName)
    }
    val address = EasyTierSettings.playerAddress(virtualIp, serverPort)
    val shareText = remember(networkName, secret, address) { vm.easyTierShareText() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            BackBar(title = stringResource(R.string.easytier_guide_entry), onBack = onBack)
            HeaderBlock(
                eyebrow = stringResource(R.string.easytier_guide_eyebrow),
                title = stringResource(R.string.easytier_guide_title),
                statusBarPadding = false
            )

            // 引导语放在卡片外：它解释「这一页是干什么用的」，塞进卡片标题会被当成卡片内容
            Text(
                stringResource(R.string.easytier_guide_lead),
                color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = McSpacing.Page, vertical = 4.dp)
            )

            // ── 二维码 ────────────────────────────────────────
            McCard(title = stringResource(R.string.easytier_guide_qr_title)) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (shareText.isBlank()) {
                        Text(
                            stringResource(R.string.easytier_address_needs_name),
                            color = Muted, fontSize = 12.sp
                        )
                    } else {
                        QrCodeImage(content = shareText, size = 200.dp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                HintNote(stringResource(R.string.easytier_guide_qr_hint))
            }

            // ── 玩家要做三步 ──────────────────────────────────
            McCard(title = stringResource(R.string.easytier_guide_steps_title)) {
                InviteStep("1", stringResource(R.string.easytier_guide_step1_t), stringResource(R.string.easytier_guide_step1_d))
                InviteStep("2", stringResource(R.string.easytier_guide_step2_t), stringResource(R.string.easytier_guide_step2_d))
                InviteStep("3", stringResource(R.string.easytier_guide_step3_t), stringResource(R.string.easytier_guide_step3_d))
            }

            // ── 三个值 ────────────────────────────────────────
            McCard(title = stringResource(R.string.easytier_guide_values_title)) {
                GuideValueRow(
                    label = stringResource(R.string.easytier_network_name),
                    value = networkName,
                    accent = Indigo,
                    container = FieldBg
                )
                Spacer(Modifier.height(8.dp))
                GuideValueRow(
                    label = stringResource(R.string.easytier_network_secret),
                    value = secret,
                    accent = Indigo,
                    container = FieldBg
                )
                Spacer(Modifier.height(8.dp))
                GuideValueRow(
                    label = stringResource(R.string.easytier_address),
                    value = address,
                    accent = Mint,
                    container = MintSoft
                )
                Spacer(Modifier.height(10.dp))
                HintNote(stringResource(R.string.easytier_guide_note))
            }

            // ── 复制 ──────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = McSpacing.Page)) {
                Button(
                    onClick = { vm.copyEasyTierGuide(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                    shape = McShape.Control,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.easytier_guide_copy_all), color = Color.White)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** 一步：序号圆点 + 标题 + 说明。与网络页「操作指南」同一种视觉语言。 */
@Composable
private fun InviteStep(step: String, title: String, description: String) {
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

/**
 * 值行：标签 + 可换行的等宽值 + 复制键。
 *
 * 不用公用的 `AddressCopyRow`：那个把值限成一行、超长省略 —— 网络密钥 32 位，
 * 在某些字号下会被截掉尾巴，而这正是玩家抄错的地方。这里的值允许折成三行。
 */
@Composable
private fun GuideValueRow(
    label: String,
    value: String,
    accent: Color,
    container: Color
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                value.ifBlank { "--" },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 3
            )
        }
        if (value.isNotBlank()) {
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
                },
                modifier = Modifier.size(McIcon.TouchCompact)
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.s388),
                    tint = accent,
                    modifier = Modifier.size(McIcon.Inline)
                )
            }
        }
    }
}
