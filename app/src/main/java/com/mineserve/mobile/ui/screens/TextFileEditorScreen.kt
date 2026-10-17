package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McSpacing

@Composable
fun TextFileEditorScreen(vm: McViewModel, file: McViewModel.TextEditorFile, onBack: () -> Unit) {
    var text by remember(file.path, file.content) { mutableStateOf(file.content) }
    var confirmRunningSave by remember { mutableStateOf(false) }
    val state by vm.serverState.collectAsState()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = McSpacing.Page, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(McIcon.Touch)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.s404), modifier = Modifier.size(McIcon.Item))
            }
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(file.path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { vm.openTextFile(java.io.File(file.path)) }, modifier = Modifier.size(McIcon.Touch)) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.tfe_refresh), modifier = Modifier.size(McIcon.Item))
            }
            IconButton(
                onClick = { (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(file.name, text)) },
                modifier = Modifier.size(McIcon.Touch)
            ) {
                Icon(Icons.Outlined.ContentCopy, stringResource(R.string.crash_copy), modifier = Modifier.size(McIcon.Item))
            }
            IconButton(
                onClick = { if (state.isRunning) confirmRunningSave = true else vm.saveTextFile(file.path, text) },
                modifier = Modifier.size(McIcon.Touch)
            ) {
                Icon(Icons.Outlined.Save, stringResource(R.string.tfe_save), modifier = Modifier.size(McIcon.Item))
            }
        }
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxSize().padding(12.dp), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), minLines = 20)
    }
    if (confirmRunningSave) AlertDialog(onDismissRequest = { confirmRunningSave = false }, title = { Text(stringResource(R.string.sm_running)) }, text = { Text(stringResource(R.string.tfe_save_running)) }, confirmButton = { TextButton(onClick = { confirmRunningSave = false; vm.saveTextFile(file.path, text) }) { Text(stringResource(R.string.tfe_save)) } }, dismissButton = { TextButton(onClick = { confirmRunningSave = false }) { Text(stringResource(R.string.props_cancel)) } })
}