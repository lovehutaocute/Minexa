package com.mineserve.mobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.mineserve.mobile.ui.theme.BackdropFrame
import com.mineserve.mobile.ui.theme.LocalBackdropState
import com.mineserve.mobile.ui.theme.LocalSkin

/**
 * 整屏背景：主题包给了 `assets.background` 就铺图，没给就退回纯色底。
 *
 * 压罩的判定用**当前配色底色的亮度**而不是 `isSystemInDarkTheme()`：
 * 主题可以强制成深色（`supportsDark=false` 时反过来也一样），那时系统仍是浅色，
 * 用系统判断会漏压黑罩，卡片看着像贴在白天上。
 *
 * 只有真的有背景图时才压罩：纯色主题的底色本身已经是 dark 令牌，再压会脏。
 */
@Composable
fun SkinBackdrop(modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val background = skin.assets.background
    val blurred = skin.assets.blurredBackground
    val backdrop = LocalBackdropState.current
    val dark = skin.scheme.surface.luminance() < 0.5f

    // 把「背景画在哪、用的什么变换」交给毛玻璃面板：它们按这个框在自己位置采样模糊图。
    // 写成绘制阶段读的状态；这里只在几何变化（转屏、切主题）时写，不会每帧触发重组。
    val publishGeometry: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = { coords ->
        if (backdrop != null) {
            val next = if (background != null && blurred != null) {
                BackdropFrame(
                    blur = blurred,
                    imageWidth = background.width,
                    imageHeight = background.height,
                    size = coords.size,
                    origin = coords.positionInWindow()
                )
            } else {
                null
            }
            if (backdrop.frame != next) backdrop.frame = next
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned(publishGeometry)
    ) {
        if (background != null) {
            Image(
                bitmap = background,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // 深色主题按 backdropScrimDark 压黑（浅色插画在深色模式下太刺眼）；
            // 浅色主题**不压**——主题作者已按「中段亮度 55–75%」出的图，再压一层白
            // 会让整张背景看起来像一层灰雾（用户的实际反馈就是「背景没了」）。
            if (dark) {
                // 压罩色用 `ext.deep` 而不是纯黑：深蓝主题压黑会变成「脏灰」，
                // 用主题自己的最深色才是同一套色系里的暗。
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            skin.ext.deep.copy(
                                alpha = skin.metrics.backdropScrimDark.coerceIn(0f, 0.9f)
                            )
                        )
                )
            }
            // 顶部渐光：`ext.topGlow` × `metrics.backdropTopGlowAlpha`。
            // 作用是把状态栏那一条压深，让白色状态栏图标在浅色云海上也看得清。
            // 默认 topGlow 是透明，什么都不画。
            val glow = skin.ext.topGlow
            if (glow.alpha > 0f) {
                val alpha = skin.metrics.backdropTopGlowAlpha.coerceIn(0f, 0.6f)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to glow.copy(alpha = alpha),
                                0.28f to glow.copy(alpha = alpha * 0.35f),
                                1f to Color.Transparent
                            )
                        )
                )
            }
        } else {
            Box(Modifier.fillMaxSize().background(skin.scheme.background))
        }
    }
}
