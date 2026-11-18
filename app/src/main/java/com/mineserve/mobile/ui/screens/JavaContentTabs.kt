package com.mineserve.mobile.ui.screens

import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.mineserve.mobile.ui.EmptyHint
import com.mineserve.mobile.server.JavaResourcePack
import com.mineserve.mobile.server.Worlds
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.Muted
import com.mineserve.mobile.data.skin.SkinTileSlots
import com.mineserve.mobile.ui.components.skinTileOverlay

/**
 * Java 版的「内容扩展」两块：世界级数据包 + 服务端资源包。
 *
 * 与基岩版附加包的本质差别（实测见 `docs/JAVA-ADVANCED-WORLD-MANAGEMENT-PLAN.md`）：
 * - 数据包是**世界级**的，放 `world/datapacks`，启用顺序 = 加载顺序；
 * - 资源包只支持**一个**，且必须是外部 URL —— 服务端自己不提供文件下载。
 *
 * 这里只负责渲染，逻辑都在 `McViewModel` / `WorldManager`，方便单测。
 */

/** 数据包：列表 + 启用开关 + 导入。 */
@Composable
fun JavaDataPackTab(vm: McViewModel) {
    val packs by vm.javaDataPacks.collectAsState()
    val running by vm.serverState.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importJavaDataPackFromUri(uri, pickDisplayName(context, uri))
    }

    LaunchedEffect(isBootstrapped) { if (isBootstrapped) vm.loadJavaAddons() }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        McCard(title = stringResource(R.string.jv_datapacks_title)) {
            Text(stringResource(R.string.jv_datapacks_desc), color = Muted, fontSize = 11.sp)
            if (running.isRunning) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.jv_stop_first), color = Coral, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                // 只传 */* 时，国产文件管理器会落到「文档」分类，zip 根本选不到
                //（与世界导入同一套 MIME 才正常，真机实测）。
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed", "*/*")) },
                enabled = !running.isRunning,
                shape = McShape.ControlCompact
            ) {
                Icon(Icons.Outlined.FileUpload, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.jv_import), fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
        }

        McCard(title = stringResource(R.string.jv_datapacks_installed, packs.size)) {
            if (packs.isEmpty()) {
                // 与全站空态一致：图标 + 灰字的 EmptyHint，而不是一行裸 Text
                EmptyHint(icon = Icons.Outlined.FolderZip, text = stringResource(R.string.jv_empty))
            } else {
                packs.forEachIndexed { index, pack ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            Icons.Outlined.FolderZip,
                            null,
                            tint = if (pack.enabled) Indigo else Muted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                pack.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                buildString {
                                    append(Worlds.formatSize(pack.sizeBytes))
                                    pack.packFormat?.let {
                                        append(" · ")
                                        append(stringResource(R.string.jv_pack_format, it))
                                    }
                                },
                                fontSize = 10.sp,
                                color = Muted
                            )
                            pack.problem?.let {
                                Text(it, fontSize = 10.sp, color = Coral, maxLines = 2)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = pack.enabled,
                            enabled = !running.isRunning && pack.problem == null,
                            onCheckedChange = { vm.setJavaDataPackEnabled(pack, it) }
                        )
                    }
                }
            }
        }
    }
}

/** 资源包：单包表单（URL / SHA-1 / ID / 提示语 / 强制）。 */
@Composable
fun JavaResourcePackTab(vm: McViewModel) {
    val cfg by vm.resourcePack.collectAsState()
    val issues by vm.resourcePackIssues.collectAsState()
    val running by vm.serverState.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()

    var url by remember(cfg?.url) { mutableStateOf(cfg?.url.orEmpty()) }
    var sha1 by remember(cfg?.sha1) { mutableStateOf(cfg?.sha1.orEmpty()) }
    var packId by remember(cfg?.id) { mutableStateOf(cfg?.id.orEmpty()) }
    var prompt by remember(cfg?.prompt) { mutableStateOf(cfg?.prompt.orEmpty()) }
    var required by remember(cfg?.required) { mutableStateOf(cfg?.required ?: false) }

    val sha1Launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.fillResourcePackSha1(uri) }

    LaunchedEffect(isBootstrapped) { if (isBootstrapped) vm.loadJavaAddons() }

    McCard(title = stringResource(R.string.jv_rp_title)) {
        Text(stringResource(R.string.jv_rp_desc), color = Muted, fontSize = 11.sp)
        if (running.isRunning) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.jv_stop_first), color = Coral, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.jv_rp_url)) },
            placeholder = { Text("https://…/pack.zip") },
            singleLine = true,
            enabled = !running.isRunning,
            modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = sha1,
                onValueChange = { sha1 = it },
                label = { Text(stringResource(R.string.jv_rp_sha1)) },
                singleLine = true,
                enabled = !running.isRunning,
                modifier = Modifier.weight(1f).skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row))
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { sha1Launcher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = !running.isRunning
            ) {
                Icon(Icons.Outlined.Image, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.jv_rp_pick_file), fontSize = 11.sp)
            }
        }
        Text(stringResource(R.string.jv_rp_sha1_hint), color = Muted, fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = packId,
            onValueChange = { packId = it },
            label = { Text(stringResource(R.string.jv_rp_id)) },
            singleLine = true,
            enabled = !running.isRunning,
            modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text(stringResource(R.string.jv_rp_prompt)) },
            singleLine = true,
            enabled = !running.isRunning,
            modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.INPUT_FIELD, McShape.Row))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.jv_rp_required), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.jv_rp_required_desc),
                    color = Muted,
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = required,
                enabled = !running.isRunning,
                onCheckedChange = { required = it }
            )
        }
        if (issues.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            issues.forEach { Text("· $it", color = Coral, fontSize = 10.sp) }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { vm.saveResourcePack(JavaResourcePack.Config(url, sha1, packId, prompt, required)) },
            enabled = !running.isRunning,
            shape = McShape.ControlCompact
        ) { Text(stringResource(R.string.jv_rp_save), fontSize = 13.sp) }
    }
}

private fun pickDisplayName(context: Context, uri: android.net.Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
            .orEmpty()
    }.getOrDefault("")
