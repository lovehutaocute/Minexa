package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ServerIconScreen(vm: McViewModel, onBack: () -> Unit) {
    val version by vm.serverIconVersion.collectAsState()
    val bitmap by produceState<Bitmap?>(null, version) {
        value = null
        value = withContext(Dispatchers.IO) {
            vm.serverIconFile()?.let { file ->
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = 1
                })
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::setServerIcon) }
    Column(Modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.ui_server_icon), onBack)
        Column(
            Modifier.padding(horizontal = McSpacing.Page, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.si_java_title), fontSize = 18.sp)
            Text(stringResource(R.string.si_hint), color = Muted, fontSize = 12.sp)
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.size(96.dp), contentScale = ContentScale.Crop) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { launcher.launch("image/*") },
                    shape = McShape.ControlCompact,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.ui_server_icon_change)) }
                OutlinedButton(
                    onClick = vm::removeServerIcon,
                    shape = McShape.ControlCompact,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.ui_server_icon_reset), color = Coral) }
            }
        }
    }
}
