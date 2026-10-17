package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.Muted

@Composable
fun MtGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 统一返回栏
        BackBar(title = stringResource(R.string.s404), onBack = onBack)

        HeaderBlock(eyebrow = stringResource(R.string.eyebrow_tools), title = stringResource(R.string.s564), statusBarPadding = false)

        McCard(title = stringResource(R.string.s565)) {
            Text(stringResource(R.string.s566), color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mt2.cn/download/")))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = McShape.Control,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.s567), fontSize = 14.sp)
            }
        }

        McCard(title = stringResource(R.string.s568)) {
            GuideStep("1", stringResource(R.string.s569), stringResource(R.string.s570))
            GuideStep("2", stringResource(R.string.s571), stringResource(R.string.s572))
            GuideStep("3", stringResource(R.string.s573), stringResource(R.string.s574))
            GuideStep("4", stringResource(R.string.s575), stringResource(R.string.s576))
            GuideStep("5", stringResource(R.string.s577), stringResource(R.string.s578))
            GuideStep("6", stringResource(R.string.s579), stringResource(R.string.s580))
            GuideStep("7", stringResource(R.string.s581), stringResource(R.string.s582))
        }

        McCard(title = stringResource(R.string.s583)) {
            Text(
                stringResource(R.string.s584),
                color = Muted, fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun GuideStep(step: String, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
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
