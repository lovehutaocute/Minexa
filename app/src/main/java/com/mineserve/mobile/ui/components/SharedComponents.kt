package com.mineserve.mobile.ui

// 性能修改理由：输入过程使用本地状态，避免异步保存回读打断输入法的光标位置。
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.data.InstallStep
import com.mineserve.mobile.data.JavaRuntime
import com.mineserve.mobile.data.JavaVendor
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.ProotDistro
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.data.StepStatus
import kotlinx.coroutines.delay
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.CoralSoft
import com.mineserve.mobile.ui.theme.FieldBg
import com.mineserve.mobile.ui.theme.FieldGray
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoDark
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Line
import com.mineserve.mobile.ui.theme.LocalGlassEnabled
import com.mineserve.mobile.data.skin.SkinTileSlots
import com.mineserve.mobile.ui.components.drawSkinTile
import com.mineserve.mobile.ui.components.AppDialog
import com.mineserve.mobile.ui.components.skinSurface
import com.mineserve.mobile.ui.components.skinTile
import com.mineserve.mobile.ui.components.skinTileOverlay
import com.mineserve.mobile.ui.theme.LocalSkin
import com.mineserve.mobile.ui.theme.McMotion
import com.mineserve.mobile.ui.theme.frostedGlass
import com.mineserve.mobile.ui.theme.pressScale
import com.mineserve.mobile.ui.theme.rememberTickHaptic
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.MintBright
import com.mineserve.mobile.ui.theme.MintSoft
import com.mineserve.mobile.ui.theme.Muted
import com.mineserve.mobile.ui.components.skinButtonColors

/**
 * 全站统一顶栏：不画底色、不画分隔线，只放右对齐的动作按钮。
 *
 * 2026-11-13 改版：原先每个页面顶上都是一条「eyebrow 小字 + 页面大标题 + 右侧按钮」的
 * 标题栏，高的页面吃掉 90dp 以上，而标题传达的信息与底部导航、Hero 卡片重复。
 * 现在标题文字下线、白底也去掉，按钮直接浮在页面背景上，内容上移。
 *
 * 没有按钮的页面（trailing == null 且不需要给返回键让位）整条顶栏不占位，
 * 只保留状态栏避让的高度 —— 否则会在状态栏下留一条无意义的空白带。
 *
 * 按钮留在顶部而不是跟着内容滚动：手册 / QQ 群 / 环境管理 / 终端菜单是全局动作，
 * 一旦跟着列表滚走就找不回来。
 *
 * [reserveBackSlot] 给「没有独立返回栏、靠 McApp 悬浮返回键返回」的二级页面用
 * （备份 / 插件 / 更多）：行高至少留出悬浮键的高度，首张卡片不会压在返回键下面。
 */
@Composable
fun PageTopBar(
    modifier: Modifier = Modifier,
    statusBarPadding: Boolean = true,
    reserveBackSlot: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    // 顶栏贴图（v2 `assets.header`）。原本顶栏是**透明的** —— 只留状态栏的高度，
    // 没有可画的「一条栏」。主题作者画了顶栏图时，这里才给它一个真实高度（56dp）；
    // 没给图时一点高度都不加，所有页面的版式与改造前完全一致。
    val headerTile = skinTile(SkinTileSlots.HEADER)
    val headerSkin = if (headerTile != null) {
        Modifier.skinTileOverlay(SkinTileSlots.HEADER, RectangleShape)
    } else {
        Modifier
    }
    val headerBand = if (headerTile != null) Modifier.heightIn(min = 56.dp) else Modifier

    if (trailing == null && !reserveBackSlot) {
        // 无按钮：不占位，只把状态栏高度让出来，内容紧贴状态栏下沿
        Spacer(
            modifier
                .fillMaxWidth()
                // 贴图放最前：它画的是**整块**区域（含状态栏留白），
                // 放到 padding 之后就只能画到状态栏下面那一条。
                .then(headerSkin)
                .then(headerBand)
                .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
        )
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(headerSkin)
            .then(headerBand)
            .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
            .heightIn(min = if (reserveBackSlot) ReserveBackSlotHeight + 8.dp else 0.dp)
            .padding(horizontal = McSpacing.Page, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        trailing?.invoke()
    }
}

/**
 * 一级页面顶栏（原 eyebrow + h1 + 右侧按钮）。
 *
 * 标题文字已下线：[eyebrow] / [title] 只为不改动 ~20 处调用点而保留，
 * 后续可连同 strings.xml 里的 eyebrow_* 一起清掉。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun HeaderBlock(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    statusBarPadding: Boolean = true,
    reserveBackSlot: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    PageTopBar(
        modifier = modifier,
        statusBarPadding = statusBarPadding,
        reserveBackSlot = reserveBackSlot,
        trailing = trailing
    )
}

/**
 * 二级页面返回栏：只剩给悬浮返回键让出的高度和右侧按钮，无底色、无分隔线。
 *
 * 返回按钮本身仍由 McApp 的 [FloatingBackButton] 统一渲染（悬浮、压在所有内容之上、位置固定）；
 * [title] 已下线，保留参数只为不动调用点，[onBack] 由悬浮键负责。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun BackBar(
    title: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    PageTopBar(reserveBackSlot = true, trailing = trailing)
}

// ── 悬浮返回键的度量 ────────────────────────────────────────────────
// 三个尺寸只在这里定义一次：BackBar 的占位、HeaderBlock 让出的高度、悬浮键本身
// 都引用它，任何一处改了另外两处自动跟随，不会出现「按钮和空位错开半格」。

/** 命中区 / 视觉直径（40dp，平台最小触摸区的下限）。 */
private val BackChipSize = McIcon.Touch

/** 距左边缘与状态栏下的偏移：与 BackBar 行内的 start=8dp / top=6dp 对齐。 */
private val BackChipStart = 8.dp
private val BackChipTop = 6.dp

/** HeaderBlock 为悬浮键让出的垂直空间：按钮高度 + 一点余量，标题不会被压住。 */
private val ReserveBackSlotHeight = 40.dp

/**
 * 二级页面的悬浮返回键：固定在状态栏左下方，压在所有滚动内容之上。
 *
 * 存在的理由：二级页面的内容各自滚动，返回入口如果跟着内容滚走，用户就得先滚回顶部；
 * 而且若干页面（备份 / 插件 / 更多）原本根本没有返回入口。
 * 视觉上用「半透明白 + 描边 + 投影」的圆片，无论底下是白卡片还是彩底都能看清。
 *
 * 位置与 [BackBar] 里的占位、[HeaderBlock] 的 reserveBackSlot 严格对齐 —— 三者共用
 * [BackChipSize] / [BackChipStart] / [BackChipTop]。
 */
@Composable
fun FloatingBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.s404)
    val haptic = rememberTickHaptic()
    Box(
        modifier = modifier
            .statusBarsPadding()
            .padding(start = BackChipStart, top = BackChipTop)
    ) {
        Box(
            modifier = Modifier
                .size(BackChipSize)
                .pressScale(pressedScale = 0.92f)
                .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                .border(1.dp, Line.copy(alpha = 0.9f), CircleShape)
                .clickable(role = Role.Button, onClickLabel = label, onClick = { haptic(); onClick() }),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(McIcon.Item)
            )
        }
    }
}

/** 统一空状态提示：图标 + 灰字文案，垂直居中 */
@Composable
fun EmptyHint(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Muted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(text, color = Muted, fontSize = 13.sp)
    }
}

/**
 * Hero 卡片：渐变背景 + 状态行 + 紧凑指标行（TPS / 在线 / 进程内存 / 运行时长）
 */
@Composable
fun HeroBlock(
    state: ServerState,
    coreLabel: String,
    cpuPercent: Int? = null,
    /**
     * 服务端 -Xmx 上限（MB）。内存进度条用它作分母，与 [ServerState.usedMemoryMb] 一起
     * 决定进度比例；为 null/0 时进度条按 0 处理，只显示数值文本。
     */
    maxHeapMb: Int? = null,
    onlineModeEnabled: Boolean = false,
    /**
     * 服务端配置文件里 `white-list` 是否为 true（PNX 对应 pnx.yml 的 `settings.allowList`）。
     *
     * 白名单开着时必须在概览页看得见：它决定了「谁能进服」，而玩家的反馈只会是
     * 一句 `You are not white-listed`——服务器看起来一切正常，人却进不来。
     */
    whitelistEnabled: Boolean = false,
    isRefreshing: Boolean = false,
    lastRefreshAtMs: Long = 0L,
    /**
     * 跳过「启动中」阶段展示（见 [com.mineserve.mobile.data.McConfig.skipStartupProgress]）。
     *
     * true 时：不显示「正在启动服务端 · xxx」与阶段进度条，状态行直接按运行中呈现。
     * 只影响呈现，[ServerState.startupPhase] 本身照常推进。
     */
    skipStartupProgress: Boolean = false,
    onRefresh: (() -> Unit)? = null
) {
    val skin = LocalSkin.current
    // 外层不 clip：主题包把 hero 声明成 overflow 时，立绘要能探出卡片上下边缘。
    // 卡片本体的圆角/裁切由内层负责，描边最后画在外层，保证探出时轮廓不断。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = McSpacing.Page, vertical = 8.dp)
            // 描边画在外层（draw modifier 在子内容之后绘制）：溢出立绘压过来时轮廓仍完整。
            .border(1.dp, skin.ext.cardRim, McShape.Hero)
    ) {
        // ① 卡片本体：圆角 + 裁切 + 主题渐变兜底。
        //
        // 「启动中顶部缺边」的真正原因是两层叠加：
        // 1. 主题包的 Hero 插画四角自带 ~24px 的**透明**圆角，之前只靠 `scale(1.06f)`
        //    把透明角推出卡片外 —— 那点放大只在卡片维持插画原始比例时刚好够用；
        //    「启动中」会把卡片撑高（多出进度条 + 两行阶段文字），Crop 重新映射后
        //    透明角又回到卡片的圆角内侧，露出后面的页面背景，看起来就是顶部缺了一块。
        // 2. Hero 卡没有描边时，画面上一旦有 alpha 缺口，轮廓就彻底断了。
        // 先在卡片底层铺满主题渐变（透明像素透出的是卡片自己的底色），再靠最外层的
        // 1dp rim 勾完整轮廓。
        // v2：主题包给了 `assets.heroCard` 就整块换成贴图（九宫格拉伸），
        // 没给才是渐变 —— 有贴图时插画仍可叠在上面，两者不冲突。
        val heroTile = skinTile(SkinTileSlots.HERO_CARD)
        Box(
            Modifier
                .matchParentSize()
                .clip(McShape.Hero)
                .then(
                    if (heroTile != null) {
                        Modifier.drawBehind { drawSkinTile(heroTile, skin.ext.pillStart) }
                    } else {
                        Modifier.background(
                            Brush.linearGradient(colors = listOf(skin.ext.pillStart, skin.ext.pillEnd))
                        )
                    }
                )
        )

        // ② Hero 插画。两种模式二选一：
        // - crop（默认）：铺满卡片并裁切，放大 6% 把插画自带的透明圆角推出边界；
        // - overflow：按卡片高度等比放大、靠右居中，**不裁**，立绘可以探出卡片上下边缘
        //   （`manifest.assetsMeta.hero.scale = "overflow"`）。
        val heroArt = skin.assets.hero
        if (heroArt != null) {
            if (skin.assets.heroOverflow) {
                // 放在 matchParentSize 容器里，避免放大的立绘把卡片撑大（参与测量的子项会改尺寸）。
                Box(Modifier.matchParentSize()) {
                    Image(
                        bitmap = heroArt,
                        contentDescription = null,
                        contentScale = ContentScale.FillHeight,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(HERO_OVERFLOW_SCALE)
                            .aspectRatio(heroArt.width.toFloat() / heroArt.height)
                    )
                }
            } else {
                Box(Modifier.matchParentSize().clip(McShape.Hero)) {
                    Image(
                        bitmap = heroArt,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize().scale(1.06f)
                    )
                }
            }
            val scrim = skin.metrics.heroScrim
            if (scrim > 0f) {
                // 压罩只压在卡片范围内：溢出立绘探出去的部分不能跟着变黑。
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(McShape.Hero)
                        .background(Color.Black.copy(alpha = scrim.coerceIn(0f, 0.85f)))
                )
            }
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // 启动中：isRunning 且尚未完成启动（runningSinceMs == 0）。
            // 用户开了「跳过启动中进度」就当作已经就绪，不再暴露中间态。
            val isStarting = state.isRunning && state.runningSinceMs == 0L && !skipStartupProgress
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 状态指示点：启动中橙色，运行中亮绿，未运行半透明白。
                    // 运行中带呼吸（透明度 1 ↔ 0.45，1s 往返）：这是**功能性**动效——
                    // 「灯还亮着」是服务器存活的持续确认，静态圆点无法区分
                    // 「活着」和「最后采集时刻活着」。启动中不呼吸：
                    // 橙色由下方进度条负责交代推进状态。
                    val pulse = rememberInfiniteTransition(label = "hero-status-pulse")
                    val dotAlpha by pulse.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.45f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "hero-status-pulse-alpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isStarting -> Color(0xFFFFA726)
                                    state.isRunning -> MintBright.copy(
                                        alpha = if (state.runningSinceMs > 0L) dotAlpha else 1f
                                    )
                                    else -> Color.White.copy(alpha = 0.5f)
                                }
                            )
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        when {
                            isStarting -> stringResource(R.string.hero_starting, coreLabel)
                            state.isRunning -> stringResource(R.string.hero_running, coreLabel)
                            else -> stringResource(R.string.hero_stopped)
                        },
                        color = skin.ext.heroOnSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onRefresh != null) {
                    // 刷新中显示旋转进度圈，替代静态图标——
                    // 采集本身很快，若没有这层反馈，用户无法区分「没反应」和「刷了但值没变」。
                    if (isRefreshing) {
                        Box(
                            modifier = Modifier.size(McIcon.TouchCompact),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(McIcon.Action),
                                strokeWidth = 2.dp,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.size(McIcon.TouchCompact)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.hero_refresh),
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(McIcon.Action)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                HeroStat(
                    if (state.isRunning) String.format("%.1f", state.tps) else "--",
                    stringResource(R.string.hero_tps)
                )
                HeroStat(
                    if (state.isRunning) "${state.onlinePlayers}/${state.maxPlayers}" else "--/--",
                    stringResource(R.string.hero_online)
                )
                HeroStat(
                    if (state.isRunning && state.runningSinceMs > 0)
                        formatUptime(android.os.SystemClock.elapsedRealtime() - state.runningSinceMs)
                    else "--",
                    stringResource(R.string.hero_uptime)
                )
            }
            // CPU 与内存同为水平进度条，各占一行；CPU 是整机读数，未运行服务端时也照常显示。
            Spacer(Modifier.height(8.dp))
            CpuMeterBar(percent = cpuPercent)
            Spacer(Modifier.height(8.dp))
            MemoryBar(
                usedMb = if (state.isRunning) state.usedMemoryMb else 0L,
                maxMb = maxHeapMb?.toLong() ?: 0L,
                running = state.isRunning
            )
            // 手动刷新完成反馈：明确告诉用户「已经更新过了」，
            // 即使各项数值本身没有变化，也不会被误认为按钮失效。
            if (!isRefreshing && lastRefreshAtMs > 0L) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.shx_updated, formatRefreshAgo(lastRefreshAtMs)),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 9.sp
                )
            }
            if (onlineModeEnabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.hero_online_mode_warning),
                    color = Color(0xFFFF8A80),
                    fontSize = 10.sp
                )
            }
            if (whitelistEnabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.hero_whitelist_on),
                    color = Color(0xFFFFD180),
                    fontSize = 10.sp
                )
            }
            // 启动中提示：适配启动耗时较长的场景
            if (isStarting) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.startupPhase.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFFFC857),
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    stringResource(R.string.shx_phase, startupPhaseLabel(state.startupPhase), (state.startupPhase.progress * 100).toInt()),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
                Text(
                    stringResource(R.string.shx_pipeline),
                    color = Color.White.copy(alpha = 0.52f),
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 溢出模式下立绘相对卡片高度的放大倍数（1.12 → 上下各探出 6%）。
 *
 * 不给主题包配置项：能探出多少应该由图本身决定（画布留白），
 * 这个数值只是保证「探出可见」的最小值。
 */
private const val HERO_OVERFLOW_SCALE = 1.12f

/**
 * Hero 卡片里的内存水平进度条。
 *
 * - 进度 = 已用内存 / 设置的上限（[ServerState.usedMemoryMb] / `maxHeapMb`）；
 * - 最右侧显示「已使用内存 / 设置的上限内存」，如 `2.4 GB / 4 GB`；
 * - 上限未知或为 0 时进度条按 0 处理，只显示数值文本，避免出现除零或满格误导。
 */
@Composable
private fun MemoryBar(usedMb: Long, maxMb: Long, running: Boolean) {
    val label = stringResource(R.string.hero_memory)
    val safeMax = maxMb.coerceAtLeast(0L)
    val safeUsed = usedMb.coerceIn(0L, if (safeMax > 0L) safeMax else usedMb)
    val ratio = if (safeMax > 0L) (safeUsed.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f) else 0f
    val valueText = if (running) {
        "${formatMemoryFull(safeUsed)} / ${formatMemoryFull(safeMax)}"
    } else {
        "-- / ${formatMemoryFull(safeMax)}"
    }
    HeroMeterBar(label = label, ratio = ratio, valueText = valueText)
}

/**
 * Hero 卡片里的 CPU 水平进度条（与内存同款外形）。
 *
 * 与内存条唯一的区别是右侧数值为百分比，且**不受服务端运行状态影响**：
 * CPU 是整机读数，服务端没跑也要显示当前占用。
 */
@Composable
private fun CpuMeterBar(percent: Int?) {
    val label = stringResource(R.string.hero_cpu)
    val value = percent?.coerceIn(0, 100)
    val ratio = (value ?: 0) / 100f
    HeroMeterBar(label = label, ratio = ratio, valueText = value?.let { "$it%" } ?: "--")
}

/**
 * Hero 卡片内「标签 + 水平进度条 + 右侧数值」的通用行。
 *
 * CPU 与内存共用同一外形（原 CPU 的环形条已按需求改为与内存一致的水平条）：
 * 圆角轨道 + 渐变填充，底色针对深色渐变卡片用半透明白，保证在靛蓝背景上仍可辨认。
 */
@Composable
private fun HeroMeterBar(label: String, ratio: Float, valueText: String) {
    val hero = LocalSkin.current.ext
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "$label, $valueText" }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = hero.heroOnDim,
                fontSize = 9.sp
            )
            Spacer(Modifier.width(8.dp))
            // 进度条占据剩余空间，右端留给数值文本。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(7.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(hero.heroTrack)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio.coerceIn(0f, 1f))
                        .height(7.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(listOf(hero.heroBarStart, hero.heroBarEnd))
                        )
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                valueText,
                color = hero.heroOnSoft,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/** 内存数值格式化：MB 输入，>=1024MB 用 GB 展示（保留一位小数），否则用 MB。 */
private fun formatMemoryFull(mb: Long): String =
    if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0) else "${mb} MB"

@Composable
private fun HeroStat(value: String, label: String) {
    val hero = LocalSkin.current.ext
    Column(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "$value, $label"
        }
    ) {
        Text(
            value,
            color = hero.heroOnSoft,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            color = hero.heroOnDim,
            fontSize = 9.sp
        )
    }
}

/** 运行时长格式化：秒/分钟/小时 */
@Composable
private fun formatUptime(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h >= 100 -> "${h}h"
        h > 0 -> "${h}h${m}m"
        m > 0 -> stringResource(R.string.sc_minutes, m)
        else -> stringResource(R.string.sc_seconds, totalSec)
    }
}

/**
 * 手动刷新的相对时间描述（「刚刚 / N 秒前 / N 分钟前」）。
 *
 * 存在的意义是让「刷新」这个动作**可被观察到**：采集带缓存，数值经常不变，
 * 光看数字无法判断按钮是否生效。
 */
@Composable
private fun formatRefreshAgo(refreshAtMs: Long): String {
    val elapsedSec = ((System.currentTimeMillis() - refreshAtMs).coerceAtLeast(0L)) / 1000
    return when {
        elapsedSec < 5 -> stringResource(R.string.shx_just_now)
        elapsedSec < 60 -> stringResource(R.string.shx_secs_ago, elapsedSec.toInt())
        elapsedSec < 3600 -> stringResource(R.string.shx_mins_ago, (elapsedSec / 60).toInt())
        else -> stringResource(R.string.shx_hours_ago, (elapsedSec / 3600).toInt())
    }
}

/** 依赖安装步骤的本地化文案（枚举里的 label 是简体中文基线，不能直接展示）。 */
@Composable
fun installStepLabel(step: InstallStep): String = when (step) {
    InstallStep.Jdk -> stringResource(R.string.step_jdk)
    InstallStep.Fonts -> stringResource(R.string.step_fonts)
    InstallStep.Proot -> stringResource(R.string.step_proot)
}

/** 启动阶段枚举的本地化文案（枚举本身只带进度，不带可直接展示的中文）。 */
@Composable
private fun startupPhaseLabel(phase: StartupPhase): String = when (phase) {
    StartupPhase.Idle -> stringResource(R.string.shx_phase_idle)
    StartupPhase.PreparingEnvironment -> stringResource(R.string.shx_phase_env)
    StartupPhase.StartingJava -> stringResource(R.string.shx_phase_java)
    StartupPhase.DownloadingDependencies -> stringResource(R.string.shx_phase_deps)
    StartupPhase.LoadingCore -> stringResource(R.string.shx_phase_core)
    StartupPhase.StartingNetwork -> stringResource(R.string.shx_phase_net)
    StartupPhase.CreatingWorld -> stringResource(R.string.shx_phase_world)
    StartupPhase.Ready -> stringResource(R.string.shx_phase_ready)
    StartupPhase.Failed -> stringResource(R.string.shx_phase_failed)
}

/**
 * 通用卡片容器：白底 + 1px 描边 + 极浅投影，圆角与页面边距取自全局令牌。
 *
 * 所有卡片共用同一圆角（[McShape.Card]）、同一边距（[McSpacing.Page]）与同一投影，
 * 这样不同页面上下滚动时卡片边缘始终在同一条竖线上，不会因为某个页面写了 14dp、
 * 另一个页面写了 20dp 而出现整体错位。
 *
 * [content] 带 [ColumnScope] 接收者：卡片内容可以按需使用 `Modifier.weight(1f)`
 * 做「表头固定 + 列表占满剩余高度」的列表面板布局（玩家页在线列表、世界页世界列表
 * 的懒加载改造就靠它）。所有既有调用点都是尾随 lambda 写法，签名变化对它们零影响。
 */
@Composable
fun McCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    // 折叠状态由卡片自己持有：标题行整体可点，右侧箭头只做视觉指示。
    // key 用 title，避免同一页面多张卡共用同一个 rememberSaveable 槽位。
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    // 卡片底色/描边走主题的玻璃令牌：有背景图时按 glassAlpha 半透（背景能从卡片里透出来），
    // 没有背景图就不透明。默认主题下 glassTop = surface、cardRim = outlineVariant，与改造前一致。
    val skin = LocalSkin.current
    // 毛玻璃开启：卡片自己不填色，改由内容层铺一层「渐变半透明 + 高光边」。
    // 关闭时走原来的实色 + 1dp 投影，绘制开销与改造前一致。
    val glass = LocalGlassEnabled.current
    // 主题包给了组件贴图（v2 `assets.card`）时就整块交给贴图，颜色只作兜底。
    // 贴图优先级高于毛玻璃：作者既然画了整张卡，再叠一层模糊会把他画的细节糊掉。
    val tileSlot = if (compact) SkinTileSlots.CARD_COMPACT else SkinTileSlots.CARD
    val hasTile = skinTile(tileSlot) != null
    val cardColor = if (skin.assets.background != null) {
        skin.ext.glassTop.copy(alpha = skin.metrics.glassAlpha.coerceIn(0.7f, 1f))
    } else {
        skin.ext.glassTop
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = McSpacing.Page,
                vertical = if (compact) McSpacing.CardGapCompact else McSpacing.CardGap
            )
            // 毛玻璃：模糊背景 + 薄雾 + 高光边；关闭时不加任何绘制节点。
            .frostedGlass(McShape.Card, glass && !hasTile)
            .skinTileOverlay(tileSlot, McShape.Card),
        shape = McShape.Card,
        colors = CardDefaults.cardColors(
            containerColor = if (glass || hasTile) Color.Transparent else cardColor
        ),
        // 毛玻璃靠描边勾出轮廓，投影会给半透明面加一圈脏边，所以开着时不开投影。
        elevation = CardDefaults.cardElevation(defaultElevation = if (glass) 0.dp else 1.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (glass || hasTile) Color.Transparent else skin.ext.cardRim
        )
    ) {
        Column(
            Modifier.padding(
                if (compact) McSpacing.CardInnerCompact else McSpacing.CardInner
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = skin.ext.inkTitle
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    trailing?.invoke()
                    if (collapsible) {
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) R.string.diag_crash_collapse else R.string.s623
                            ),
                            tint = Muted,
                            modifier = Modifier.size(McIcon.Item)
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = !collapsible || expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(
                        Modifier.height(
                            if (compact) McSpacing.CardHeaderCompact else McSpacing.CardHeader
                        )
                    )
                    content()
                }
            }
        }
    }
}

/**
 * 步骤行（参考界面 step-row）
 */
@Composable
fun StepRow(
    name: String,
    status: StepStatus,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
            .clearAndSetSemantics {
                contentDescription = "$name, $tag"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (bg, fg, text) = when (status) {
            StepStatus.Done -> Triple(MintSoft, Mint, "✓")
            StepStatus.Active -> Triple(IndigoSoft, Indigo, "•")
            StepStatus.Wait -> Triple(FieldGray, Muted, "○")
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Text(
            name,
            color = if (status == StepStatus.Wait) Muted else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Text(tag, color = Muted, fontSize = 10.sp)
    }
}

/**
 * 进度条（参考界面 progress-track）。
 *
 * 填充宽度带 [McMotion.DurationMedium] 的平滑过渡：安装/下载进度是离散上报的
 * （每一段数据到达才 +N%），不补间的话进度条是一格一格跳的，看起来像卡顿。
 */
@Composable
fun ProgressTrack(percent: Int) {
    val target = (percent.coerceIn(0, 100)) / 100f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = McMotion.DurationMedium, easing = FastOutSlowInEasing),
        label = "progress-track"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(FieldGray)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(Indigo, Color(0xFF6E7FD6))
                    )
                )
        )
    }
}

/**
 * 圆角分段按钮：参考界面 .seg / .on
 */
@Composable
fun SegPill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    unselectedBackground: Color = FieldGray,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .pressScale(pressedScale = 0.94f)
            .skinSurface(
                SkinTileSlots.CHIP,
                if (selected) Indigo else unselectedBackground,
                McShape.Chip
            )
            .clickable { onClick() }
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp)
    ) {
        Text(
            text,
            color = if (selected) Color.White else Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 防抖输入框：本地即时编辑 + 300ms 防抖写回外部值，失焦时立即写回。
 *
 * 解决 TextField 直接绑定持久化 StateFlow（如 config）导致的输入延迟与
 * 快速输入文字顺序错乱问题：输入过程完全在本地状态上进行，停顿 300ms
 * 后才把结果回传（快速连续输入只回传最后一次），失焦时立即回传避免丢失。
 *
 * @param sanitize 可选输入清洗函数（如数字框只保留数字），每次输入后应用
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun DebouncedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    sanitize: ((String) -> String)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    textStyle: TextStyle = TextStyle.Default,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    /**
     * 由外部发起的一次「在末尾追加文本」请求（如 MOTD 的色板插入）。
     *
     * 必须由组件自己拼到**本地**文本上：调用方拿到的 `value` 是已经落盘的外部值，
     * 在 300ms 防抖窗口内它是旧的 —— 用 `onValueChange(value + code)` 会把用户
     * 刚敲的字整体丢掉。这里用 id 变化触发，一次请求只应用一次。
     */
    insertRequest: Pair<Long, String>? = null
) {
    var text by remember { mutableStateOf(value) }
    var isFocused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    // 外部值同步：仅在非编辑状态时同步到本地。
    // 关键：防抖写回后外部 value 的回显更新有延迟，若此时用户已继续输入，
    // 无条件覆盖会把正在输入的内容重置回旧值（快速输入文字丢失/乱序）。
    LaunchedEffect(value) {
        if (!editing && text != value) text = value
    }

    // 输入停顿 300ms 后写回（连续输入只写回最后一次）
    LaunchedEffect(text) {
        if (text == value) return@LaunchedEffect
        delay(300)
        if (text != value) {
            onValueChange(text)
            editing = false
        }
    }

    // 外部追加请求（色板等）：拼到本地文本上再整体回传，不能用外部的 value 拼
    LaunchedEffect(insertRequest) {
        val req = insertRequest ?: return@LaunchedEffect
        val merged = text + req.second
        text = merged
        onValueChange(merged)
    }

    // 失焦时立即写回，避免切换页面丢失最后输入
    LaunchedEffect(isFocused) {
        if (!isFocused && text != value) {
            onValueChange(text)
            editing = false
        }
        if (isFocused) {
            delay(100)
            bringIntoViewRequester.bringIntoView()
        }
    }

    // 组合移除（切页/销毁）时立即回传未写回内容，避免防抖窗口内输入丢失。
    // value 是普通参数、不是快照状态，DisposableEffect(Unit) 的闭包只会捕获首帧的值，
    // 因此用 rememberUpdatedState 取最新值作为比较基准，否则退出时会用旧文本覆盖已生效的新值。
    val latestValue by rememberUpdatedState(value)
    DisposableEffect(Unit) {
        onDispose {
            if (text != latestValue) onValueChange(text)
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            val next = sanitize?.invoke(newText) ?: newText
            // 仅当清洗后文本真的变化时才进入编辑态：
            // 若清洗结果与原文本相同，LaunchedEffect(text) 不会重启，editing 会永久卡在 true，
            // 导致外部值同步失效、退出时把旧文本写回。
            if (next != text) {
                text = next
                editing = true
            }
        },
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { isFocused = it.isFocused }
            // OutlinedTextField 的容器色默认透明（只有描边），所以贴图可以直接垫在底下。
            .skinTileOverlay(SkinTileSlots.INPUT_FIELD, shape),
        enabled = enabled,
        singleLine = singleLine,
        label = label,
        placeholder = placeholder,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        textStyle = textStyle,
        shape = shape
    )
}

// ── EdgeCube 式网络状态组件 ────────────────────────────────────
//
// 借鉴 EdgeCube「网络映射页」的信息组织：状态用胶囊而不是纯文本，地址用带图标与
// 复制按钮的小卡片，运行统计在位时才出现，失败原因单独成条而不是塞进状态文字里。

/**
 * 状态胶囊：圆角 12dp + 12% alpha 同色底 + 同色文字。
 *
 * 语义色沿用项目既有的三档：运行中 [Mint]、进行中（启动/探测）[Coral]、其余 [Muted]。
 * 调用方负责按状态给色，本组件只负责画。
 */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .skinSurface(SkinTileSlots.CHIP, color.copy(alpha = 0.12f), McShape.Chip)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 公网/局域网地址展示条：图标 + 标题 + 等宽加粗地址 + 一键复制。
 *
 * [value] 为空时不显示复制按钮——没有可复制的内容时留一个禁用按钮只会让人反复点。
 */
@Composable
fun AddressCopyRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
    icon: ImageVector = Icons.Outlined.Public,
    accent: Color = Indigo,
    containerColor: Color = FieldBg,
    borderColor: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(McShape.Row)
            .background(containerColor)
            .then(
                if (borderColor != null) Modifier.border(1.dp, borderColor, McShape.Row)
                else Modifier
            )
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(McIcon.Action))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (value.isNotBlank()) {
            IconButton(onClick = onCopy, modifier = Modifier.size(McIcon.Touch)) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.s388),
                    tint = accent,
                    modifier = Modifier.size(McIcon.Item)
                )
            }
        }
    }
}

/**
 * 单行运行统计：小图标 + 说明文本。
 *
 * 只在隧道真正运行时才调用——未运行时不摆一排「0 B/s」占地方。
 */
@Composable
fun StatLine(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Muted, modifier = Modifier.size(McIcon.Inline))
        Spacer(Modifier.size(8.dp))
        Text(text, color = Muted, fontSize = 11.sp)
    }
}

/**
 * 前提说明条：info 图标 + 说明文案。
 *
 * 用来讲清「这个功能只在什么条件下有效」，避免用户在不支持的环境下反复尝试。
 */
@Composable
fun HintNote(text: String, icon: ImageVector = Icons.Outlined.Info) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .skinSurface(SkinTileSlots.BANNER, FieldBg, McShape.Row)
            .padding(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Indigo, modifier = Modifier.size(McIcon.Inline))
        Spacer(Modifier.size(8.dp))
        Text(text, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

/**
 * 失败/警告条：warning 图标 + 原因文案，用 Coral 系配色。
 *
 * 与 [HintNote] 分开是因为「当前出错了」和「使用前需知」的视觉权重不该一样。
 */
@Composable
fun WarnNote(text: String) {
    // 有 banner 贴图时不再描边：作者图里自带边，再画一条就是双线。
    val hasBannerTile = skinTile(SkinTileSlots.BANNER) != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .skinSurface(SkinTileSlots.BANNER, CoralSoft, McShape.Row)
            .then(
                if (hasBannerTile) Modifier else Modifier.border(1.dp, Coral, McShape.Row)
            )
            .padding(12.dp)
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = Coral,
            modifier = Modifier.size(McIcon.Inline)
        )
        Spacer(Modifier.size(8.dp))
        Text(text, color = Coral, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

/** QQ 交流群跳转链接 */
private const val QQ_GROUP_URL =
    "https://qun.qq.com/universal-share/share?ac=1&authKey=ISbTtN7IFJ0ItNdgzSlZ68hWxg136HpWhwOjj%2BRcl55agd85N3DCzBU82z7U8dQT&busi_data=eyJncm91cENvZGUiOiI1OTM2ODIwMzMiLCJ0b2tlbiI6ImJBS1d3WHRabHRBNUJXcHE5d1EzK01SbUZsVXg5ajM4SVdCeGhBZTVBQXNhMGlpck5DWE04azFKWWhSVW1JbTYiLCJ1aW4iOiIxNjcyNDU0ODQifQ%3D%3D&data=mDeXPqhlgK8JWPqiG2MpojgJuRaMiLLUN_czFSB2Yuhhl2mi9r-v-f6C6DzXxyXQY_Nog12BLMt6kJ8aanRlfg&svctype=4&tempid=h5_group_info"

/** QQ 群号（纯数字，用于复制与展示） */
private const val QQ_GROUP_NUMBER = "593682033"

/** 打开 QQ 加群页面；无浏览器/QQ 时静默失败 */
private fun openQqGroup(context: android.content.Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_URL)))
    } catch (_: Exception) {
        // 无可用应用时静默
    }
}

/**
 * QQ 交流群入口卡片：显示群号并提供一键加群按钮。
 */
@Composable
fun QqGroupCard() {
    val context = LocalContext.current
    McCard(title = stringResource(R.string.s1051)) {
        Text(
            stringResource(R.string.s1052),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Indigo
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.s1053),
            color = Muted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { openQqGroup(context) },
            colors = skinButtonColors(SkinTileSlots.PRIMARY_BUTTON, Indigo),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.PRIMARY_BUTTON, RoundedCornerShape(16.dp))) {
            Text(
                stringResource(R.string.s1054),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * QQ 交流群弹窗：概览页顶部企鹅图标点击后弹出。
 *
 * 显示群号，提供「复制群号」与「一键加群」两个操作。
 */
@Composable
fun QqGroupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    AppDialog(
        onDismissRequest = onDismiss,
        shape = McShape.Dialog,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_qq_penguin),
                contentDescription = null,
                tint = Indigo,
                modifier = Modifier.size(40.dp)
            )
        },
        title = {
            Text(
                stringResource(R.string.s1051),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.s1053),
                    color = Muted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
                // 群号展示块
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(FieldGray)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.qq_group_label),
                            color = Muted,
                            fontSize = 10.sp
                        )
                        Text(
                            QQ_GROUP_NUMBER,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(QQ_GROUP_NUMBER)) }) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            tint = Indigo,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.qq_copy_group), color = Indigo, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.skinTileOverlay(SkinTileSlots.PRIMARY_BUTTON, RoundedCornerShape(16.dp)),
                onClick = { openQqGroup(context) },
                colors = skinButtonColors(SkinTileSlots.PRIMARY_BUTTON, Indigo),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    stringResource(R.string.s1054),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.env_cancel), color = Muted)
            }
        }
    )
}

// ══════════════════════════════════════════════════════════════
// Java 运行环境：来源分组列表
//
// 依赖管理页的 Java 板块与概览页「运行环境」弹窗共用这一套渲染，
// 分组与版本都直接来自 JavaVendor.entries × vendor.versions，
// 不在这里另抄一份清单 —— 否则两处会各自漂移出「弹窗里能选、依赖页里没有」的错位。
// ══════════════════════════════════════════════════════════════

/**
 * 来源分组标题：一行标题 + 一句来源说明，右侧折叠箭头。整行可点，点击切换展开。
 *
 * 字体、颜色、圆角与间距沿用原有取值，只多了一个箭头与点击态 ——
 * 折叠控件不应该看起来像另一个东西。
 *
 * 折叠的意义：三个来源的版本行加起来 11 条，默认全展开会把「卸载 / 重装 / 安装」
 * 三键挤到屏幕外，而这三个按钮才是这个板块的主要操作。
 */
@Composable
fun RuntimeGroupHeader(
    title: String,
    note: String,
    expanded: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(McShape.Row)
            .clickable(enabled = enabled) { onToggle() }
            .padding(top = 8.dp, start = 4.dp, end = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Indigo,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(note, color = Muted, fontSize = 10.sp)
        }
        Icon(
            // 展开时朝上、折叠时朝下：箭头指向「点下去会发生什么」的相反方向是常态，
            // 但这里用「当前状态」语义更直观 —— 朝下代表内容被收起。
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = Indigo,
            modifier = Modifier.size(McIcon.Item)
        )
    }
}

/**
 * 分段切换控件：等宽分段 + 滑动高亮块。
 *
 * 项目里原先有四套「同义不同形」的切换控件：插件页与玩家页各写了一遍
 * 「淡蓝轨道 + 蓝色选中块」，环境管理页用 [FilterChip]，PRoot 发行版用另一份
 * 带动画的高亮块。这里收敛成一份实现，所有分段切换都由它渲染，
 * 于是轨道高度、圆角、字号、选中色、切换动画在任意页面都一致。
 *
 * 选中块用 260ms 缓动**滑动**到目标位置而不是瞬移：切换后往往伴随下方内容变化，
 * 有位移过程才容易把「我点了这里」和「下面变了」联系起来。
 *
 * @param options 分段文案（等宽排布，调用方自行带上数量等后缀）
 * @param selectedIndex 当前选中项下标，越界时按 0 处理
 * @param enabled 为假时整条置灰且不响应点击
 */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit
) {
    if (options.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, options.lastIndex)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            // 贴图只铺轨道；滑块仍是纯色，否则两段一模一样就看不出选中了。
            .skinSurface(SkinTileSlots.CHIP, FieldGray, McShape.Chip)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        val segmentWidth = maxWidth / options.size
        val highlightOffset by animateDpAsState(
            targetValue = segmentWidth * safeIndex,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            label = "segmented-tabs-highlight"
        )
        // 高亮块：留 3dp 内边距，所以圆角比轨道小 3dp，视觉上正好同心
        Box(
            modifier = Modifier
                .offset(x = highlightOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Indigo)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                val isSelected = index == safeIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(enabled = enabled) { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (isSelected) Color.White else Muted,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Ubuntu 发行版胶囊切换控件。
 *
 * 左侧 Ubuntu 20.04、右侧 Ubuntu 22.04；实现完全交给 [SegmentedTabs]，
 * 保证与插件分类、玩家标签等分段切换控件在高度、圆角、配色与滑动动画上完全一致。
 *
 * 版本里用短名（「Ubuntu 20.04」）：一半宽度放不下带代号的显示名。
 */
@Composable
fun ProotDistroPillSwitcher(
    selected: ProotDistro,
    enabled: Boolean,
    onSelect: (ProotDistro) -> Unit
) {
    val options = ProotDistro.entries
    if (options.isEmpty()) return
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    SegmentedTabs(
        // 胶囊里用短名（「Ubuntu 20.04」）：一半宽度放不下带代号的显示名
        options = options.map { it.shortName },
        selectedIndex = selectedIndex,
        enabled = enabled,
        onSelect = { index -> options.getOrNull(index)?.let(onSelect) }
    )
}

/** 来源的分组标题文案。 */
fun JavaVendor.groupTitleRes(): Int = when (this) {
    JavaVendor.Termux -> R.string.java_group_termux
    JavaVendor.Fcl -> R.string.java_group_fcl
    JavaVendor.Proot -> R.string.java_group_proot
}

/** 来源分组标题下的一句说明。 */
fun JavaVendor.groupNoteRes(): Int = when (this) {
    JavaVendor.Termux -> R.string.java_group_termux_note
    JavaVendor.Fcl -> R.string.java_group_fcl_note
    JavaVendor.Proot -> R.string.java_group_proot_note
}

/**
 * 版本行的一句补充说明；只有能力上确实有差异的版本才有。
 *
 * - Proot 的 Java 8：只有 focal 提供它，切到 jammy 后这一项会从列表里消失。
 *   不提示的话，用户会以为「装好的 Java 8 丢了」。
 * - Proot 的其他版本：容器内是 glibc 环境，IPv6 兼容层不生效，
 *   且能装哪些版本取决于所选发行版（见 [ProotDistro.availableJavaVersions]）。
 *
 * 注：Java 8 曾挂在 Termux 分组下（那时它已跑在容器里），现已归入 PRoot 分组，
 * 因此不再有「Termux + Java 8」的说明分支。
 */
private fun javaVersionNoteRes(vendor: JavaVendor, version: JavaVersion): Int? =
    when {
        vendor == JavaVendor.Proot && version == JavaVersion.Java8 -> R.string.java_note_proot_java8
        vendor == JavaVendor.Proot -> R.string.java_note_proot_container
        else -> null
    }

/**
 * 弹窗 / 列表里的单个运行环境版本行：选中态用 Mint 边框 + 淡底。
 *
 * [busy] 为真时右侧的状态文字换成进度圈 —— 只有正在下载解压的那一行会亮起来，
 * 否则用户不知道那个 37–62MB 的进度到底是给谁的。
 */
@Composable
fun RuntimeVersionRow(
    label: String,
    note: String?,
    status: String?,
    statusColor: Color = Muted,
    selected: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(McShape.Row)
            .background(if (selected) Mint.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                width = if (selected) 1.5.dp else 0.dp,
                color = if (selected) Mint else Color.Transparent,
                shape = McShape.Row
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Mint else Color.Unspecified
            )
            note?.let {
                Text(it, color = Muted, fontSize = 10.sp)
            }
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(McIcon.Inline), strokeWidth = 2.dp, color = Indigo)
        } else {
            status?.let {
                Text(it, color = statusColor, fontSize = 11.sp)
            }
        }
    }
}

/**
 * Java 运行环境分组列表：三个来源各一段（分组标题 + 组内纯版本号）。
 *
 * @param selected 当前选中项，选中态由 [JavaRuntime]（来源 + 版本）决定，
 *   两套 JDK 的版本号完全重合，只比版本号会串台。
 * @param enabled 为假时不响应点击（安装中 / 服务器运行中）。
 * @param inFlight 正在安装的那一项，只在该行显示进度圈。
 * @param expandedVendors 当前展开的来源集合。**默认空集（全部折叠）**：
 *   三个来源共 11 条版本行，全展开会把「卸载 / 重装 / 安装」三键顶出屏幕，
 *   而这三个按钮才是本板块的主要操作。展开状态由调用方持有，
 *   因为它是界面状态，不该由数据层或 ViewModel 承担。
 * @param onToggleVendor 点击分组标题时切换该来源的展开状态。
 * @param prootDistro PRoot 分类当前选中的发行版。PRoot 分类**必须**按它过滤版本：
 *   `JavaVendor.Proot.versions` 是所有发行版的并集（8/11/17/21），但单个发行版
 *   只提供其中一部分（focal 无 21、jammy 无 8）。若照并集全列出来，
 *   用户会看到 "Java 21" 可点、点了却装不上，属于「列表里出现装不上的项」。
 * @param onDistroChange 切换 PRoot 发行版。必须在**同一次**写入里同时调整版本：
 *   两个发行版的可用版本不同，沿用旧版本会写出装不上的组合。
 */
@Composable
fun JavaRuntimeGroupList(
    installed: Set<JavaRuntime>,
    selected: JavaRuntime,
    onSelect: (JavaRuntime) -> Unit,
    enabled: Boolean = true,
    inFlight: JavaRuntime? = null,
    expandedVendors: Set<JavaVendor> = emptySet(),
    onToggleVendor: (JavaVendor) -> Unit = {},
    prootDistro: ProotDistro = ProotDistro.Default,
    onDistroChange: (ProotDistro) -> Unit = {}
) {
    JavaVendor.entries.forEach { vendor ->
        val expanded = vendor in expandedVendors
        // 折叠时把「已选哪一份」写进说明行：三个分组默认全折叠，而下面的
        // 「卸载 / 重装 / 安装」三键作用于当前选中项 —— 不标出来的话，
        // 用户在折叠状态下根本看不出这三个按钮会作用到哪一份 Java 上。
        // 展开时不需要：选中的那一行有 Mint 高亮边框，已经足够显眼。
        val note = buildString {
            append(stringResource(vendor.groupNoteRes()))
            if (!expanded && selected.vendor == vendor) {
                append(" · ")
                append(stringResource(R.string.java_group_selected, selected.version.displayName))
            }
        }
        RuntimeGroupHeader(
            title = stringResource(vendor.groupTitleRes()),
            note = note,
            expanded = expanded,
            onToggle = { onToggleVendor(vendor) }
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(160)) + expandVertically(tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(180))
        ) {
            Column {
                if (vendor == JavaVendor.Proot) {
                    // 发行版切换放在版本行之前：它决定下面能列出哪些版本，
                    // 顺序反了会让人以为「换了发行版版本却没变」是 bug。
                    Spacer(Modifier.height(6.dp))
                    ProotDistroPillSwitcher(
                        selected = prootDistro,
                        enabled = enabled,
                        onSelect = onDistroChange
                    )
                    Spacer(Modifier.height(2.dp))
                }
                // PRoot 列全部候选版本（并集），其中当前发行版不提供的置灰 ——
                // 只看并集会让用户点到一个装不上的版本；只列可用版本又会让用户
                // 以为应用漏了某个版本。置灰同时满足「看得到」与「点不动」。
                val versions = vendor.versions
                versions.forEach { version ->
                    val runtime = JavaRuntime(vendor, version)
                    val isInstalled = runtime in installed
                    val unavailable = vendor == JavaVendor.Proot &&
                        version !in prootDistro.availableJavaVersions
                    RuntimeVersionRow(
                        label = version.displayName,
                        note = javaVersionNoteRes(vendor, version)?.let { stringResource(it) },
                        status = when {
                            unavailable -> stringResource(R.string.java_note_proot_unavailable)
                            isInstalled -> stringResource(R.string.dash_java_installed)
                            else -> stringResource(R.string.env_java_not_installed)
                        },
                        statusColor = if (isInstalled && !unavailable) Mint else Muted,
                        selected = runtime == selected,
                        busy = runtime == inFlight,
                        onClick = { if (enabled && !unavailable) onSelect(runtime) }
                    )
                }
            }
        }
    }
}

/**
 * 页面级容器色（Scaffold 的 containerColor）。
 *
 * 主题包带了整屏背景图时返回**透明**，让 [SkinBackdrop] 的插画透到每个页面里；
 * 没有背景图时返回 background 色，与改造前完全一致。
 *
 * 为什么需要它：各页面几乎都用 `Scaffold`，而 Scaffold 默认容器色是不透明的
 * `colorScheme.background`。不统一改掉，就会出现「有的页面有背景、有的没有」——
 * 有背景的那几个恰好是没用 Scaffold 的页面。
 */
@Composable
fun pageSurface(): Color {
    val skin = LocalSkin.current
    return if (skin.assets.background != null) Color.Transparent else skin.scheme.background
}

/**
 * 危险操作二次确认弹窗：全站统一实现。
 *
 * 背景：仓库里已有 10+ 处「删除/覆盖/清空」确认弹窗，各页面手写 AppDialog 组装，
 * 标题字重、正文颜色、确认按钮色号各自微调过，同一动作在不同页面长不一样。
 * 新加的危险操作（自动化任务删除、玩家封禁、终端清屏）直接复用本组件，
 * 老弹窗不动——它们工作正常，批量改写只会引入回归风险。
 *
 * 视觉约定与既有弹窗保持一致：确认用 [Coral]（与全站危险色一致），
 * 取消用 [Muted]；确认触发一次轻触觉反馈（删除类操作值得一次实体确认感）。
 *
 * @param title 弹窗标题
 * @param message 正文说明（建议讲清「会失去什么」，而不是「确定吗」）
 * @param confirmLabel 确认按钮文案，默认「确认删除」
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = stringResource(R.string.s401)
) {
    val haptic = rememberTickHaptic()
    AppDialog(
        onDismissRequest = onDismiss,
        shape = McShape.Dialog,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = { Text(message, color = Muted, fontSize = 12.sp, lineHeight = 18.sp) },
        confirmButton = {
            TextButton(onClick = { haptic(); onConfirm() }) {
                Text(confirmLabel, color = Coral, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.s402), color = Muted)
            }
        }
    )
}
