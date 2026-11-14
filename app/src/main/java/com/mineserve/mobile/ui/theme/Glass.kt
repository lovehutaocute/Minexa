package com.mineserve.mobile.ui.theme

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 毛玻璃开关（运行时全局状态）。
 *
 * 取值由 [MineServeMobileTheme] 在树顶下发，规则见 `SkinRepository`：
 * 用户手动设置优先，没设过则跟随「是否启用主题包」——主题包有整屏插图，
 * 玻璃后面才有东西可模糊；默认配色是纯色底，开了也只是把白色稀释。
 */
val LocalGlassEnabled = compositionLocalOf { false }

/**
 * 背景（[com.mineserve.mobile.ui.components.SkinBackdrop]）的几何信息。
 *
 * 毛玻璃要「模糊真正在面板背后的那部分画面」，就必须知道两件事：背景被画到了哪里、
 * 用的什么变换（`ContentScale.Crop`），以及面板自己落在屏幕的哪个位置。
 */
@Immutable
data class BackdropFrame(
    /** 预模糊的小图（见 `SkinRuntime.blurDownscale`）。 */
    val blur: ImageBitmap,
    /** 原图尺寸，用来复刻 Crop 的缩放/裁切量。 */
    val imageWidth: Int,
    val imageHeight: Int,
    /** 背景在窗口里的绘制区域。 */
    val size: IntSize,
    /** 背景左上角在窗口坐标系里的位置（状态栏/导航栏偏移都算在内）。 */
    val origin: Offset
)

/**
 * 背景几何的持有者：由 `McApp` 创建并下发，`SkinBackdrop` 在排版后写入。
 *
 * 写成可变状态而不是参数，是为了让面板只在**绘制阶段**读它 —— 滚动时位置变化只触发
 * 重绘（一次纹理采样），不会引起重组。
 */
class BackdropState {
    var frame by mutableStateOf<BackdropFrame?>(null)
}

/** 没有背景图（纯色底）时为 null：此时毛玻璃退化成半透明着色，与「无内容可模糊」一致。 */
val LocalBackdropState = compositionLocalOf<BackdropState?> { null }

/**
 * 毛玻璃面板：**真实模糊背景** + 半透明着色 + 1px 高光边。
 *
 * 实现要点（为什么不用 `Modifier.blur` / RenderEffect / Haze）：
 * - `Modifier.blur` 模糊的是控件**自己画的内容**，不是它背后的画面，做不出磨砂玻璃；
 *   RenderEffect 需 API 31+，本工程 minSdk 26，且逐面板实时模糊在滚动时会持续占用 GPU；
 * - Haze 等库在 API ≤30 上只能退化成半透明色块（正是「没效果」的那种），
 *   开启其 RenderScript 兜底又要后台线程逐帧卷积，老机器反而更卡；
 * - 本工程的背景是**一张静态插画**，所以模糊只需在切主题时算一次（缩到 1/18 再由绘制放大），
 *   面板按位置采样 → 效果与实时模糊一致，开销等于画一个色块，任何 API 都能跑。
 *
 * [enabled] 为 false 时原样返回 [this]，不增加任何绘制与布局节点。
 */
@Composable
fun Modifier.frostedGlass(
    shape: Shape,
    enabled: Boolean = LocalGlassEnabled.current,
    /** 玻璃着色（叠在模糊背景上的那层薄雾），默认取主题玻璃令牌。 */
    tint: Color? = null,
    /** 是否画 1px 高光边。控件自己已有描边（如 OutlinedButton）时传 false，避免两条线。 */
    withRim: Boolean = true
): Modifier {
    val backdrop = LocalBackdropState.current
    val glassTint = tint ?: LocalSkin.current.ext.glassTop.copy(alpha = 0.26f)
    val rimColor = LocalSkin.current.ext.glassRim.copy(alpha = 0.45f)
    // 面板自身在窗口中的位置：滚动时由 onGloballyPositioned 更新，只写绘制阶段读的状态。
    // remember 必须在下面的早退之前 —— 否则开关毛玻璃时调用点数量变化，会打乱槽位表。
    var originInWindow by remember { mutableStateOf(Offset.Zero) }

    if (!enabled) return this

    var result = this
        .clip(shape)
        .onGloballyPositioned { originInWindow = it.positionInWindow() }
        .drawWithCache {
            onDrawBehind {
                val frame = backdrop?.frame
                if (frame != null) {
                    // 复刻 SkinBackdrop 里 ContentScale.Crop 的变换：等比放大到铺满窗口，居中裁切。
                    val scale = max(
                        frame.size.width / frame.imageWidth.toFloat(),
                        frame.size.height / frame.imageHeight.toFloat()
                    )
                    val dstWidth = (frame.imageWidth * scale).roundToInt()
                    val dstHeight = (frame.imageHeight * scale).roundToInt()
                    val cropX = (frame.size.width - dstWidth) / 2f
                    val cropY = (frame.size.height - dstHeight) / 2f
                    // 把画布原点挪到「面板左上角在窗口里的位置」，背景于是按真实位置对齐；
                    // 放大时用 Low 过滤：低分辨率图放大本来就自带柔和插值，Medium 只会更耗而不更准。
                    val local = originInWindow - frame.origin
                    translate(-local.x, -local.y) {
                        drawImage(
                            image = frame.blur,
                            dstOffset = IntOffset(cropX.roundToInt(), cropY.roundToInt()),
                            dstSize = IntSize(dstWidth, dstHeight),
                            filterQuality = FilterQuality.Low
                        )
                    }
                }
                // 薄雾：让模糊背景「透而不清」，同时保证其上文字有足够对比度。
                drawRect(glassTint)
            }
        }
    if (withRim) result = result.border(1.dp, rimColor, shape)
    return result
}
