package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.SegPill
import com.mineserve.mobile.ui.DebouncedTextField
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Muted

// ── server.properties 参数元数据（完整覆盖，基础/高级分组） ──────────

private enum class PropGroup(val labelRes: Int) { Basic(R.string.s841), Advanced(R.string.s842) }

private enum class PropType { Bool, Int, Text, Enum }

private data class PropertySpec(
    val key: String,
    val labelRes: Int,
    val descRes: Int = 0,
    val type: PropType = PropType.Text,
    val group: PropGroup = PropGroup.Advanced,
    val options: List<Pair<Int, String>> = emptyList(), // 显示名 resource ID to 值（仅 Enum）
    val default: String = ""
)

private val propertySpecs: List<PropertySpec> = listOf(
    // ── 基础配置 ──
    PropertySpec("difficulty", R.string.s843, R.string.s844, PropType.Enum, PropGroup.Basic,
        listOf(R.string.s845 to "peaceful", R.string.s846 to "easy", R.string.s847 to "normal", R.string.s848 to "hard"), "easy"),
    PropertySpec("gamemode", R.string.s730, R.string.s849, PropType.Enum, PropGroup.Basic,
        listOf(R.string.s281 to "survival", R.string.s282 to "creative", R.string.s283 to "adventure", R.string.s284 to "spectator"), "survival"),
    PropertySpec("pvp", 0, R.string.s850, PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("white-list", R.string.s659, R.string.s703, PropType.Bool, PropGroup.Basic),
    PropertySpec("online-mode", R.string.s851, R.string.s852, PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("enforce-whitelist", R.string.s853, R.string.s854, PropType.Bool, PropGroup.Basic),
    PropertySpec("max-players", R.string.s855, R.string.s856, PropType.Int, PropGroup.Basic, default = "20"),
    PropertySpec("server-port", R.string.s857, R.string.s858, PropType.Int, PropGroup.Basic, default = "25565"),
    PropertySpec("server-ip", R.string.s859, R.string.s860, PropType.Text, PropGroup.Basic),
    PropertySpec("motd", R.string.s861, R.string.s862, PropType.Text, PropGroup.Basic, default = "A Minecraft Server"),
    PropertySpec("view-distance", R.string.s863, R.string.s864, PropType.Int, PropGroup.Basic, default = "10"),
    PropertySpec("simulation-distance", R.string.s865, R.string.s866, PropType.Int, PropGroup.Basic, default = "10"),
    PropertySpec("max-world-size", R.string.s867, R.string.s868, PropType.Int, PropGroup.Basic, default = "29999984"),
    PropertySpec("spawn-protection", R.string.s869, R.string.s870, PropType.Int, PropGroup.Basic, default = "16"),
    PropertySpec("hardcore", R.string.s871, R.string.s872, PropType.Bool, PropGroup.Basic),
    PropertySpec("enable-command-block", R.string.s873, R.string.s874, PropType.Bool, PropGroup.Basic),
    PropertySpec("allow-flight", R.string.s875, R.string.s876, PropType.Bool, PropGroup.Basic),
    PropertySpec("allow-nether", R.string.s877, R.string.s878, PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("spawn-animals", R.string.s879, R.string.s880, PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("spawn-monsters", R.string.s881, R.string.s882, PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("spawn-npcs", R.string.s883, R.string.s884, PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("generate-structures", R.string.s885, R.string.s886, PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("level-name", R.string.s887, R.string.s888, PropType.Text, PropGroup.Basic, default = "world"),
    PropertySpec("level-seed", R.string.s889, R.string.s890, PropType.Text, PropGroup.Basic),
    PropertySpec("level-type", R.string.s891, R.string.s892, PropType.Enum, PropGroup.Basic,
        listOf(
            R.string.s847 to "minecraft:normal",
            R.string.s893 to "minecraft:flat",
            R.string.s894 to "minecraft:large_biomes",
            R.string.s895 to "minecraft:amplified",
            R.string.s896 to "minecraft:single_biome_surface"
        ), "minecraft:normal"),
    PropertySpec("force-gamemode", R.string.s897, R.string.s898, PropType.Bool, PropGroup.Basic),
    PropertySpec("max-tick-time", R.string.s899, R.string.s900, PropType.Int, PropGroup.Basic, default = "60000"),
    PropertySpec("enable-status", R.string.s901, R.string.s902, PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("hide-online-players", R.string.s903, R.string.s904, PropType.Bool, PropGroup.Basic),
    PropertySpec("op-permission-level", R.string.s905, R.string.s906, PropType.Int, PropGroup.Basic, default = "4"),
    PropertySpec("function-permission-level", R.string.s907, R.string.s908, PropType.Int, PropGroup.Basic, default = "2"),
    PropertySpec("rate-limit", R.string.s909, R.string.s910, PropType.Int, PropGroup.Basic),
    PropertySpec("require-resource-pack", R.string.s911, R.string.s912, PropType.Bool, PropGroup.Basic),
    PropertySpec("resource-pack", R.string.s913, R.string.s914, PropType.Text, PropGroup.Basic),
    PropertySpec("player-idle-timeout", R.string.s915, R.string.s916, PropType.Int, PropGroup.Basic),
    PropertySpec("network-compression-threshold", R.string.s917, R.string.s918, PropType.Int, PropGroup.Basic, default = "256"),
    PropertySpec("max-chained-neighbor-updates", R.string.s919, R.string.s920, PropType.Int, PropGroup.Basic, default = "1000000"),

    // ── 高级配置 ──
    PropertySpec("accepts-transfers", R.string.s921, R.string.s922, PropType.Bool),
    PropertySpec("broadcast-console-to-ops", R.string.s923, R.string.s924, PropType.Bool, default = "true"),
    PropertySpec("broadcast-rcon-to-ops", R.string.s925, R.string.s926, PropType.Bool, default = "true"),
    PropertySpec("bug-report-link", R.string.s927, R.string.s928, PropType.Text),
    PropertySpec("chat-spam-threshold-seconds", R.string.s929, R.string.s930, PropType.Int, default = "10"),
    PropertySpec("command-spam-threshold-seconds", R.string.s931, R.string.s932, PropType.Int, default = "10"),
    PropertySpec("debug", R.string.s933, R.string.s934, PropType.Bool),
    PropertySpec("enable-code-of-conduct", R.string.s935, R.string.s936, PropType.Bool),
    PropertySpec("enable-jmx-monitoring", R.string.s937, R.string.s938, PropType.Bool),
    PropertySpec("enable-query", R.string.s939, R.string.s940, PropType.Bool),
    PropertySpec("enable-rcon", R.string.s941, R.string.s942, PropType.Bool),
    PropertySpec("entity-broadcast-range-percentage", R.string.s943, R.string.s944, PropType.Int, default = "100"),
    PropertySpec("generator-settings", R.string.s945, R.string.s946, PropType.Text, default = "{}"),
    PropertySpec("initial-disabled-packs", R.string.s947, R.string.s948, PropType.Text),
    PropertySpec("initial-enabled-packs", R.string.s949, R.string.s950, PropType.Text, default = "vanilla"),
    PropertySpec("log-ips", R.string.s951, R.string.s952, PropType.Bool, default = "true"),
    PropertySpec("management-server-allowed-origins", R.string.s953, R.string.s954, PropType.Text),
    PropertySpec("management-server-enabled", R.string.s955, R.string.s956, PropType.Bool),
    PropertySpec("management-server-host", R.string.s957, R.string.s958, PropType.Text, default = "localhost"),
    PropertySpec("management-server-port", R.string.s959, R.string.s960, PropType.Int),
    PropertySpec("management-server-secret", R.string.s961, R.string.s962, PropType.Text),
    PropertySpec("management-server-tls-enabled", R.string.s963, R.string.s964, PropType.Bool, default = "true"),
    PropertySpec("management-server-tls-keystore", R.string.s965, R.string.s966, PropType.Text),
    PropertySpec("management-server-tls-keystore-password", R.string.s967, R.string.s968, PropType.Text),
    PropertySpec("pause-when-empty-seconds", R.string.s969, R.string.s970, PropType.Int, default = "-1"),
    PropertySpec("prevent-proxy-connections", R.string.s971, R.string.s972, PropType.Bool),
    PropertySpec("query.port", R.string.s973, R.string.s974, PropType.Int, default = "25565"),
    PropertySpec("rcon.password", R.string.s975, R.string.s976, PropType.Text),
    PropertySpec("rcon.port", R.string.s977, R.string.s978, PropType.Int, default = "25575"),
    PropertySpec("region-file-compression", R.string.s979, R.string.s980, PropType.Text, default = "deflate"),
    PropertySpec("resource-pack-id", R.string.s981, R.string.s982, PropType.Text),
    PropertySpec("resource-pack-prompt", R.string.s983, R.string.s984, PropType.Text),
    PropertySpec("resource-pack-sha1", R.string.s985, R.string.s986, PropType.Text),
    PropertySpec("status-heartbeat-interval", R.string.s987, R.string.s988, PropType.Int),
    PropertySpec("sync-chunk-writes", R.string.s989, R.string.s990, PropType.Bool, default = "true"),
    PropertySpec("text-filtering-config", R.string.s991, R.string.s992, PropType.Text),
    PropertySpec("text-filtering-version", R.string.s993, R.string.s994, PropType.Int),
    PropertySpec("use-native-transport", R.string.s995, R.string.s996, PropType.Bool, default = "true")
)


@Composable
fun JavaPropertiesScreen(vm: McViewModel, onBack: () -> Unit, showBackBar: Boolean = true) {
    val loaded by vm.serverProperties.collectAsState()
    val propertiesExist by vm.serverPropertiesExist.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val config by vm.config.collectAsState()

    // 本地编辑状态：进入页面后由 ViewModel 加载结果同步，控件修改时本地更新
    var props by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showRestartDialog by remember { mutableStateOf(false) }

    // 进入页面时加载 server.properties
    LaunchedEffect(Unit) { vm.loadServerProperties() }
    // ViewModel 加载完成后同步到本地编辑状态
    LaunchedEffect(loaded) { props = loaded }

    // 配置页头部（返回栏 + 标题 + 当前核心提示），各状态分支共用
    val header: @Composable () -> Unit = {
        if (showBackBar) BackBar(title = stringResource(R.string.s541), onBack = onBack)
    }

    // 配置文件尚未生成：不展示可编辑表单。
    // 此处的预设值并非服务端真实生效值，编辑后保存会写出一份不完整配置，
    // 与服务端首启自动补全的默认值产生错位。改为引导用户先启动服务端。
    if (propertiesExist == false) {
        Column(modifier = Modifier.fillMaxSize()) {
            header()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                HeaderBlock(
                    eyebrow = stringResource(R.string.eyebrow_properties),
                    title = stringResource(R.string.s997),
                    statusBarPadding = !showBackBar
                )
                val activeCore = config.installedCores.find { it.name == config.activeCoreName }
                Text(
                    stringResource(R.string.s998, activeCore?.name ?: stringResource(R.string.s999)),
                    color = Muted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = McSpacing.Page)
                )
                Spacer(Modifier.height(12.dp))
                PropertiesNotGeneratedCard(
                    isRunning = serverState.isRunning,
                    onStartServer = { vm.startServer() },
                    onRefresh = { vm.loadServerProperties() }
                )
                Spacer(Modifier.height(16.dp))
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 统一返回栏；作为底部导航 tab 时隐藏
        header()
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = stringResource(R.string.eyebrow_properties), title = stringResource(R.string.s997), statusBarPadding = !showBackBar)

            // 当前核心提示：每个命名服务器拥有独立配置
            val activeCore = config.installedCores.find { it.name == config.activeCoreName }
            Text(
                stringResource(R.string.s998, activeCore?.name ?: stringResource(R.string.s999)),
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = McSpacing.Page)
            )
            Spacer(Modifier.height(8.dp))

            // ── 基础配置（常用参数，完整展示） ──
            McCard(title = stringResource(PropGroup.Basic.labelRes)) {
                propertySpecs.filter { it.group == PropGroup.Basic }.forEach { spec ->
                    renderProperty(spec, props) { v ->
                        props = props.toMutableMap().apply { put(spec.key, v) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ── 高级配置（全部剩余参数） ──
            McCard(title = stringResource(PropGroup.Advanced.labelRes)) {
                propertySpecs.filter { it.group == PropGroup.Advanced }.forEach { spec ->
                    renderProperty(spec, props) { v ->
                        props = props.toMutableMap().apply { put(spec.key, v) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // 兜底：未收录的新参数（保证完整加载展示，不丢失）
                val knownKeys = propertySpecs.map { it.key }.toSet()
                val unknownKeys = props.keys.filter { it !in knownKeys }
                if (unknownKeys.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.s1000), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    unknownKeys.forEach { key ->
                        Text(key, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        DebouncedTextField(
                            value = props[key] ?: "",
                            onValueChange = { v -> props = props.toMutableMap().apply { put(key, v) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 底部固定保存按钮
        Button(
            onClick = {
                if (serverState.isRunning) {
                    showRestartDialog = true
                } else {
                    vm.saveServerProperties(props)
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            shape = McShape.Control,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = McSpacing.Page, vertical = 12.dp)
        ) {
            Text(stringResource(R.string.s328), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }

    // 服务器运行中时保存的重启提示弹窗
    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text(stringResource(R.string.s581), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.s1001)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    vm.saveServerProperties(props)
                }) { Text(stringResource(R.string.s1002), color = Indigo) }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text(stringResource(R.string.s402), color = Coral)
                }
            }
        )
    }
}

/** 从 props 读取布尔值，key 不存在或解析失败时返回 default */
private fun boolOf(props: Map<String, String>, key: String, default: Boolean): Boolean {
    return props[key]?.let { it.equals("true", ignoreCase = true) } ?: default
}

/** 带标题/副标题的开关行 */
@Composable
private fun PropertySwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Indigo,
                checkedTrackColor = IndigoSoft
            )
        )
    }
}

/** 带标签的数字输入框 */
@Composable
private fun LabeledNumberField(
    label: String,
    desc: String = "",
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true
) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    if (desc.isNotEmpty()) Text(desc, color = Muted, fontSize = 10.sp)
    Spacer(Modifier.height(4.dp))
    DebouncedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        sanitize = { it.filter(Char::isDigit) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 按类型渲染单个属性（数据驱动） */
@Composable
private fun renderProperty(
    spec: PropertySpec,
    props: Map<String, String>,
    enabled: Boolean = true,
    onChange: (String) -> Unit
) {
    val label = if (spec.labelRes != 0) stringResource(spec.labelRes) else "PVP"
    val desc = if (spec.descRes != 0) stringResource(spec.descRes) else ""
    when (spec.type) {
        PropType.Bool -> PropertySwitch(
            title = label,
            subtitle = desc,
            checked = boolOf(props, spec.key, spec.default.toBoolean()),
            onChange = { if (enabled) onChange(it.toString()) },
            enabled = enabled
        )
        PropType.Int -> LabeledNumberField(
            label = label,
            desc = desc,
            value = props[spec.key] ?: spec.default,
            onValueChange = { if (enabled) onChange(it) },
            enabled = enabled
        )
        PropType.Enum -> EnumField(
            label = label,
            desc = desc,
            value = props[spec.key] ?: spec.default,
            options = spec.options,
            onValueChange = { if (enabled) onChange(it) },
            enabled = enabled
        )
        PropType.Text -> LabeledTextField(
            label = label,
            desc = desc,
            value = props[spec.key] ?: spec.default,
            onValueChange = { if (enabled) onChange(it) },
            enabled = enabled
        )
    }
}

/** 枚举选择（SegPill 流式布局，自动换行） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnumField(
    label: String,
    desc: String,
    value: String,
    options: List<Pair<Int, String>>,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true
) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    if (desc.isNotEmpty()) Text(desc, color = Muted, fontSize = 10.sp)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (displayRes, v) ->
            SegPill(
                text = stringResource(displayRes),
                selected = value == v,
                onClick = { if (enabled) onValueChange(v) }
            )
        }
    }
}

/** 带标签的文本输入框 */
@Composable
private fun LabeledTextField(
    label: String,
    desc: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true
) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    if (desc.isNotEmpty()) Text(desc, color = Muted, fontSize = 10.sp)
    Spacer(Modifier.height(4.dp))
    DebouncedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

private enum class PnxFieldType { Toggle, Number, Text, Choice }

private data class PnxField(
    val key: String,
    val labelRes: Int,
    val type: PnxFieldType,
    val default: String,
    val options: List<String> = emptyList()
)

private val pnxGroups = listOf(
    R.string.grp_server to listOf(
        PnxField("server-ip", R.string.pnx_listen_addr, PnxFieldType.Text, "0.0.0.0"),
        PnxField("server-port", R.string.pnx_udp_port, PnxFieldType.Number, "19132"),
        PnxField("max-players", R.string.pnx_max_players, PnxFieldType.Number, "20"),
        PnxField("motd", R.string.pnx_motd, PnxFieldType.Text, "PowerNukkitX Server"),
        PnxField("sub-motd", R.string.pnx_sub_motd, PnxFieldType.Text, "powernukkitx.org"),
        PnxField("language", R.string.pnx_language, PnxFieldType.Text, "eng"),
        PnxField("level-name", R.string.pnx_level_name, PnxFieldType.Text, "world"),
        PnxField("auto-save", R.string.pnx_auto_save, PnxFieldType.Toggle, "true"),
        PnxField("white-list", R.string.pnx_white_list, PnxFieldType.Toggle, "false"),
        PnxField("online-mode", R.string.pnx_online_mode, PnxFieldType.Toggle, "true")
    ),
    R.string.grp_gamemode to listOf(
        PnxField("gamemode", R.string.pnx_gamemode, PnxFieldType.Choice, "survival", listOf("survival", "creative", "adventure", "spectator")),
        PnxField("force-gamemode", R.string.pnx_force_gamemode, PnxFieldType.Toggle, "false"),
        PnxField("difficulty", R.string.pnx_difficulty, PnxFieldType.Choice, "easy", listOf("peaceful", "easy", "normal", "hard")),
        PnxField("pvp", R.string.pnx_pvp, PnxFieldType.Toggle, "true"),
        PnxField("view-distance", R.string.pnx_view_distance, PnxFieldType.Number, "8"),
        PnxField("spawn-protection", R.string.pnx_spawn_protection, PnxFieldType.Number, "16"),
        PnxField("allow-nether", R.string.pnx_allow_nether, PnxFieldType.Toggle, "true"),
        PnxField("allow-the-end", R.string.pnx_allow_end, PnxFieldType.Toggle, "true"),
        PnxField("enable-command-block", R.string.pnx_cmd_block, PnxFieldType.Toggle, "false"),
        PnxField("spawn-animals", R.string.pnx_spawn_animals, PnxFieldType.Toggle, "true"),
        PnxField("weather-cycle", R.string.pnx_weather, PnxFieldType.Toggle, "true"),
        PnxField("daylight-cycle", R.string.pnx_daylight, PnxFieldType.Toggle, "true"),
        PnxField("hunger", R.string.pnx_hunger, PnxFieldType.Toggle, "true")
    ),
    R.string.grp_perf to listOf(PnxField("base-tps", R.string.pnx_base_tps, PnxFieldType.Number, "20")),
    R.string.grp_net to listOf(PnxField("enable-status", R.string.pnx_enable_status, PnxFieldType.Toggle, "true"))
)

@Composable
fun PropertiesScreen(vm: McViewModel, onBack: () -> Unit, showBackBar: Boolean = true) {
    val config by vm.config.collectAsState()
    when (config.installedCores.firstOrNull { it.name == config.activeCoreName }?.core) {
        com.mineserve.mobile.data.ServerCore.PowerNukkitX -> PowerNukkitXPropertiesScreen(vm, onBack, showBackBar)
        null -> PropertiesEmptyScreen(onBack, showBackBar)
        else -> JavaPropertiesScreen(vm, onBack, showBackBar)
    }
}

@Composable
private fun PropertiesEmptyScreen(onBack: () -> Unit, showBackBar: Boolean) {
    Column(Modifier.fillMaxSize()) {
        if (showBackBar) BackBar(title = stringResource(R.string.props_back_title), onBack = onBack)
        HeaderBlock(stringResource(R.string.props_eyebrow), stringResource(R.string.props_back_title), statusBarPadding = !showBackBar)
        Text(stringResource(R.string.props_no_core), color = Muted, modifier = Modifier.padding(16.dp))
    }
}

/**
 * 配置文件尚未生成时的引导卡片。
 *
 * 不渲染可编辑表单，避免用户在"预设值 ≠ 服务端真实值"的情况下做出错误修改。
 * 提供两条路径：直接启动服务端（推荐），或启动后回来刷新。
 */
@Composable
private fun PropertiesNotGeneratedCard(
    isRunning: Boolean,
    onStartServer: () -> Unit,
    onRefresh: () -> Unit,
    desc: String = stringResource(R.string.props_not_generated_desc),
    hint: String = stringResource(R.string.props_not_generated_hint)
) {
    McCard(title = stringResource(R.string.props_not_generated_title)) {
        Text(
            desc,
            color = Muted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            color = Muted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(12.dp))
        if (isRunning) {
            // 服务端已在启动/运行中，给出刷新入口等待配置文件写出
            Text(
                stringResource(R.string.props_not_generated_running),
                color = Indigo,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = McShape.Control,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.props_not_generated_refresh),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            Button(
                onClick = onStartServer,
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = McShape.Control,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.props_not_generated_start),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.props_not_generated_refresh),
                    color = Indigo,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun PowerNukkitXPropertiesScreen(vm: McViewModel, onBack: () -> Unit, showBackBar: Boolean = true) {
    val loaded by vm.serverProperties.collectAsState()
    val propertiesExist by vm.serverPropertiesExist.collectAsState()
    val state by vm.serverState.collectAsState()
    var props by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var restartConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadServerProperties() }
    LaunchedEffect(loaded) { props = loaded }

    // 配置文件尚未生成：与 Java 版一致，引导先启动服务端而非编辑预设值。
    if (propertiesExist == false) {
        Column(Modifier.fillMaxSize()) {
            if (showBackBar) BackBar(title = stringResource(R.string.props_pnx_back), onBack = onBack)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                HeaderBlock("POWERNUKKITX", stringResource(R.string.props_pnx_title), statusBarPadding = !showBackBar)
                Spacer(Modifier.height(12.dp))
                PropertiesNotGeneratedCard(
                    isRunning = state.isRunning,
                    onStartServer = { vm.startServer() },
                    onRefresh = { vm.loadServerProperties() },
                    desc = stringResource(R.string.props_pnx_not_generated_desc),
                    hint = stringResource(R.string.props_pnx_not_generated_hint)
                )
                Spacer(Modifier.height(16.dp))
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (showBackBar) BackBar(title = stringResource(R.string.props_pnx_back), onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            HeaderBlock("POWERNUKKITX", stringResource(R.string.props_pnx_title), statusBarPadding = !showBackBar)
            Text(
                stringResource(R.string.props_pnx_source),
                color = Coral, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)
            )
            pnxGroups.forEach { (titleRes, fields) ->
                McCard(title = stringResource(titleRes), compact = true) {
                    fields.forEachIndexed { index, field ->
                        PnxFieldRow(field, props[field.key] ?: field.default) { value ->
                            props = props.toMutableMap().apply { put(field.key, value) }
                        }
                        if (index != fields.lastIndex) Spacer(Modifier.height(8.dp))
                    }
                }
            }
            McCard(title = stringResource(R.string.props_advanced), compact = true) {
                Text(stringResource(R.string.props_advanced_hint), color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { if (state.isRunning) restartConfirm = true else vm.saveServerProperties(props) },
            colors = ButtonDefaults.buttonColors(containerColor = Indigo), shape = McShape.Control,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) { Text(stringResource(R.string.props_save), color = Color.White, fontWeight = FontWeight.Bold) }
    }
    if (restartConfirm) AlertDialog(
        onDismissRequest = { restartConfirm = false }, title = { Text(stringResource(R.string.props_save)) },
        text = { Text(stringResource(R.string.props_save_running)) },
        confirmButton = { TextButton(onClick = { restartConfirm = false; vm.saveServerProperties(props) }) { Text(stringResource(R.string.props_save), color = Indigo) } },
        dismissButton = { TextButton(onClick = { restartConfirm = false }) { Text(stringResource(R.string.props_cancel), color = Coral) } }
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PnxFieldRow(field: PnxField, value: String, onChange: (String) -> Unit) {
    when (field.type) {
        PnxFieldType.Toggle -> PropertySwitch(stringResource(field.labelRes), "", value.equals("true", true), { onChange(it.toString()) })
        PnxFieldType.Number -> LabeledNumberField(stringResource(field.labelRes), value = value, onValueChange = onChange)
        PnxFieldType.Text -> LabeledTextField(stringResource(field.labelRes), "", value, onChange)
        PnxFieldType.Choice -> {
            Text(stringResource(field.labelRes), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                field.options.forEach { option -> SegPill(option, value == option) { onChange(option) } }
            }
        }
    }
}