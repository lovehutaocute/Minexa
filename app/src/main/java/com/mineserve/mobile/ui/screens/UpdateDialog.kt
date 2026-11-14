package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.McViewModel.UpdateUiState
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.components.AppDialog

@Composable
fun UpdateDialog(vm: McViewModel) {
    val visible by vm.updateDialogVisible.collectAsState()
    val state by vm.updateState.collectAsState()
    if (!visible) return
    when (val current = state) {
        UpdateUiState.Checking -> AppDialog(
            onDismissRequest = vm::dismissUpdateDialog,
            title = { Text(stringResource(R.string.upd_checking)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::dismissUpdateDialog) { Text(stringResource(R.string.upd_cancel)) } }
        )
        is UpdateUiState.Available -> AppDialog(
            onDismissRequest = vm::skipCurrentUpdate,
            title = { Text(stringResource(R.string.upd_found, current.release.tag)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.upd_notes_title), fontSize = 13.sp)
                    Text(
                        current.release.notes.ifBlank { stringResource(R.string.upd_notes_empty) },
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                    )
                    Text(
                        stringResource(R.string.upd_warn),
                        color = Color(0xFFD32F2F),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = vm::openGithubUpdate) { Text(stringResource(R.string.upd_github)) }
                    TextButton(onClick = vm::downloadUpdate) { Text(stringResource(R.string.upd_download), color = Indigo) }
                }
            },
            dismissButton = { TextButton(onClick = vm::skipCurrentUpdate) { Text(stringResource(R.string.upd_skip)) } }
        )
        is UpdateUiState.Downloading -> AppDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.upd_downloading)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator({ current.progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    Text("${(current.progress.coerceIn(0f, 1f) * 100).toInt()}%", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {}
        )
        is UpdateUiState.Downloaded -> AppDialog(
            onDismissRequest = vm::dismissUpdateDialog,
            title = { Text(stringResource(R.string.upd_downloaded)) },
            text = { Text(stringResource(R.string.upd_install_hint)) },
            confirmButton = { TextButton(onClick = { vm.installDownloadedUpdate(current.apkPath) }) { Text(stringResource(R.string.upd_install), color = Indigo) } },
            dismissButton = { TextButton(onClick = vm::dismissUpdateDialog) { Text(stringResource(R.string.upd_install_later)) } }
        )
        is UpdateUiState.Failed -> AppDialog(
            onDismissRequest = vm::dismissUpdateDialog,
            title = { Text(stringResource(R.string.upd_failed)) },
            text = { Text(current.message, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    if (current.release == null) vm.checkForUpdate(manual = true) else vm.downloadUpdate()
                }) { Text(if (current.release == null) stringResource(R.string.upd_retry_check) else stringResource(R.string.upd_retry_download), color = Indigo) }
            },
            dismissButton = { TextButton(onClick = vm::dismissUpdateDialog) { Text(stringResource(R.string.upd_close)) } }
        )
        UpdateUiState.Idle -> Unit
    }
}
