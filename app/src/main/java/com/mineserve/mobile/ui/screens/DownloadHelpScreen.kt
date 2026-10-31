package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted

/**
 * 下载慢帮助页面：提供切换下载源、使用代理、切换网络等解决方案
 */
@Composable
fun DownloadHelpScreen(vm: McViewModel, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 统一返回栏
        BackBar(title = stringResource(R.string.s404), onBack = onBack)
        HeaderBlock(eyebrow = stringResource(R.string.eyebrow_help), title = stringResource(R.string.s405), statusBarPadding = false)

        // 介绍
        McCard(title = stringResource(R.string.s406)) {
            Text(
                stringResource(R.string.s407),
                color = Muted, fontSize = 12.sp
            )
        }

        // 方案1：切换下载源（最推荐）
        HelpSolutionCard(
            icon = Icons.Outlined.Dns,
            iconColor = Indigo,
            tag = stringResource(R.string.s408),
            tagColor = Mint,
            title = stringResource(R.string.s409),
            description = stringResource(R.string.s410)
        ) {
            HelpStep("1", stringResource(R.string.s411))
            HelpStep("2", stringResource(R.string.s412))
            HelpStep("3", stringResource(R.string.s413))
            HelpStep("4", stringResource(R.string.s414))
            HelpStep("5", stringResource(R.string.s415))
        }

        // 方案2：切换 apt 软件源
        HelpSolutionCard(
            icon = Icons.Outlined.Public,
            iconColor = Indigo,
            tag = stringResource(R.string.s408),
            tagColor = Mint,
            title = stringResource(R.string.s416),
            description = stringResource(R.string.s417)
        ) {
            HelpStep("1", stringResource(R.string.s411))
            HelpStep("2", stringResource(R.string.s418))
            HelpStep("3", stringResource(R.string.s419))
            HelpStep("4", stringResource(R.string.s420))
        }

        // 方案3：切换网络
        HelpSolutionCard(
            icon = Icons.Outlined.Wifi,
            iconColor = Indigo,
            tag = stringResource(R.string.s421),
            tagColor = Mint,
            title = stringResource(R.string.s422),
            description = stringResource(R.string.s423)
        ) {
            HelpStep("1", stringResource(R.string.s424))
            HelpStep("2", stringResource(R.string.s425))
            HelpStep("3", stringResource(R.string.s426))
            HelpStep("4", stringResource(R.string.s427))
        }

        // 方案4：使用 VPN/代理
        HelpSolutionCard(
            icon = Icons.Outlined.VpnKey,
            iconColor = Coral,
            tag = stringResource(R.string.s428),
            tagColor = Coral,
            title = stringResource(R.string.s429),
            description = stringResource(R.string.s430)
        ) {
            HelpStep("1", stringResource(R.string.s431))
            HelpStep("2", stringResource(R.string.s432))
            HelpStep("3", stringResource(R.string.s433))
            HelpStep("4", stringResource(R.string.s434))
        }

        // 方案5：手动下载
        HelpSolutionCard(
            icon = Icons.Outlined.Sync,
            iconColor = Coral,
            tag = stringResource(R.string.s435),
            tagColor = Coral,
            title = stringResource(R.string.s436),
            description = stringResource(R.string.s437)
        ) {
            HelpStep("1", stringResource(R.string.s438))
            HelpStep("2", stringResource(R.string.s439))
            HelpStep("3", stringResource(R.string.s440))
            HelpStep("4", stringResource(R.string.s441))
        }

        // 测速小贴士
        McCard(title = stringResource(R.string.s442)) {
            Text(
                stringResource(R.string.s443),
                color = Muted, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            SpeedRow("5+ MB/s", stringResource(R.string.s445), Mint)
            SpeedRow("1-5 MB/s", stringResource(R.string.s446), Mint)
            SpeedRow("0.5-1 MB/s", stringResource(R.string.s447), Indigo)
            SpeedRow("< 0.5 MB/s", stringResource(R.string.s448), Coral)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.s449),
                color = Muted, fontSize = 11.sp
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HelpSolutionCard(
    icon: ImageVector,
    iconColor: Color,
    tag: String,
    tagColor: Color,
    title: String,
    description: String,
    steps: @Composable () -> Unit
) {
    McCard(title = title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.size(10.dp))
            // 标签
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(tagColor.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(tag, color = tagColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(8.dp))
        steps()
    }
}

@Composable
private fun HelpStep(step: String, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Indigo),
            contentAlignment = Alignment.Center
        ) {
            Text(step, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(8.dp))
        Text(title, fontSize = 12.sp)
    }
}

@Composable
private fun SpeedRow(speed: String, desc: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(speed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.width(90.dp))
        Text(desc, color = Muted, fontSize = 11.sp)
    }
}
