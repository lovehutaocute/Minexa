package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted

/**
 * OP 等级指引页：说明原版 MC /op 命令不支持指定等级的限制，并提供两种解决方案。
 */
@Composable
fun OpLevelGuideScreen(
    onBack: () -> Unit,
    onNavigateProperties: () -> Unit,
    onNavigatePlugins: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 统一返回栏
        BackBar(title = stringResource(R.string.s1037), onBack = onBack)

        // 关于 OP 等级说明
        McCard(title = stringResource(R.string.s1038)) {
            Text(
                stringResource(R.string.s1039),
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }

        // 方案一：使用配置默认等级
        McCard(title = stringResource(R.string.s1040)) {
            Text(
                stringResource(R.string.s1041),
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onNavigateProperties,
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.s1042),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 方案二：安装权限管理插件
        McCard(title = stringResource(R.string.s1043)) {
            Text(
                stringResource(R.string.s1044),
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onNavigatePlugins,
                colors = ButtonDefaults.buttonColors(containerColor = Mint),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.s1045),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // OP 等级说明
        McCard(title = stringResource(R.string.s1046)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.s1047), fontSize = 11.sp, color = Muted)
                Text(stringResource(R.string.s1048), fontSize = 11.sp, color = Muted)
                Text(stringResource(R.string.s1049), fontSize = 11.sp, color = Muted)
                Text(stringResource(R.string.s1050), fontSize = 11.sp, color = Coral, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
