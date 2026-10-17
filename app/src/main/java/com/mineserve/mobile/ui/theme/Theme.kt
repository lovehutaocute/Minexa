package com.mineserve.mobile.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * 全局形状：让走默认形状的组件（Card / OutlinedTextField / AlertDialog / Chip）
 * 自动落在项目的圆角体系上，而不是 Material3 的内置默认值。
 * - extraSmall 是 OutlinedTextField 的默认形状：原先 4dp 的尖角输入框与 12dp 的按钮
 *   明显不是一套语言，统一到 10dp；
 * - medium 是 Card 的默认形状，与 McCard 的 12dp 保持一致；
 * - large 是 AlertDialog 的默认形状，统一到 20dp（原先 28dp 偏大）。
 */
private val McShapes = Shapes(
    extraSmall = McShape.Row,
    small = McShape.Row,
    medium = McShape.Card,
    large = McShape.Dialog,
    extraLarge = McShape.Dialog
)

private val McColorScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = LightCard,
    primaryContainer = LightIndigoSoft,
    onPrimaryContainer = Indigo,
    secondary = Mint,
    onSecondary = LightCard,
    secondaryContainer = LightMintSoft,
    onSecondaryContainer = Mint,
    tertiary = Coral,
    onTertiary = LightCard,
    tertiaryContainer = LightCoralSoft,
    onTertiaryContainer = Coral,
    background = LightBg,
    onBackground = LightInk,
    surface = LightCard,
    onSurface = LightInk,
    surfaceVariant = LightFieldGray,
    onSurfaceVariant = LightMuted,
    outline = LightLine,
    // 显式给出描边与容器色阶：Line 令牌读的是 outlineVariant，
    // 不覆盖会落到 M3 默认的 #CAC4D0（明显偏灰），卡片边框会比设计稿重。
    outlineVariant = LightLine,
    surfaceTint = Indigo,
    surfaceContainerLowest = LightContainerLowest,
    surfaceContainerLow = LightContainerLow,
    surfaceContainer = LightContainer,
    surfaceContainerHigh = LightContainerHigh,
    surfaceContainerHighest = LightContainerHighest,
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
    inverseSurface = LightInk,
    inverseOnSurface = LightBg,
    inversePrimary = Color(0xFF9FB0F0),
    scrim = Color(0xFF000000)
)

private val McDarkColorScheme = darkColorScheme(
    primary = Color(0xFF9FB0F0),
    onPrimary = Color(0xFF1B2447),
    primaryContainer = DarkIndigoSoft,
    onPrimaryContainer = Color(0xFFD5DCFA),
    secondary = MintBright,
    onSecondary = Color(0xFF06301F),
    secondaryContainer = DarkMintSoft,
    onSecondaryContainer = MintBright,
    tertiary = Color(0xFFFFB4A8),
    onTertiary = Color(0xFF561E1A),
    tertiaryContainer = DarkCoralSoft,
    onTertiaryContainer = Color(0xFFFFB4A8),
    background = DarkBg,
    onBackground = DarkInk,
    surface = DarkCard,
    onSurface = DarkInk,
    surfaceVariant = DarkFieldGray,
    onSurfaceVariant = DarkMuted,
    outline = DarkLine,
    outlineVariant = DarkLine,
    surfaceTint = Color(0xFF9FB0F0),
    surfaceContainerLowest = DarkContainerLowest,
    surfaceContainerLow = DarkContainerLow,
    surfaceContainer = DarkContainer,
    surfaceContainerHigh = DarkContainerHigh,
    surfaceContainerHighest = DarkContainerHighest,
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    inverseSurface = DarkInk,
    inverseOnSurface = DarkBg,
    inversePrimary = Indigo,
    scrim = Color(0xFF000000)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MineServeMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) McDarkColorScheme else McColorScheme,
        typography = McTypography,
        shapes = McShapes
    ) {
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            content()
        }
    }
}
