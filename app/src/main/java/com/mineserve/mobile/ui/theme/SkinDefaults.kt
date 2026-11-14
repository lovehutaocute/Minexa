package com.mineserve.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.mineserve.mobile.data.skin.ExtColors
import com.mineserve.mobile.data.skin.MetricTokens
import com.mineserve.mobile.data.skin.ShapeTokens
import com.mineserve.mobile.data.skin.SkinAssets
import com.mineserve.mobile.data.skin.SkinResolved
import com.mineserve.mobile.data.skin.TypographyTokens

/**
 * 构建期默认配色：**主题包缺键 / 尚未加载时**的取值，与改造前逐位一致。
 *
 * 这里是唯一允许出现字面十六进制色的地方 —— 其它所有 `Color.kt` 常量都改成读
 * [SkinPalette.current]，也就是「当前生效主题」。把默认值集中在一处，才能保证
 * 「没有主题包时界面和 1.5.0 完全一样」这件事可以一眼核对。
 */
internal object SkinDefaults {

    // ── 亮色 ──────────────────────────────────────────────
    val LightBg = Color(0xFFF3F4F8)
    val LightCard = Color(0xFFFFFFFF)
    val LightInk = Color(0xFF1F2430)
    val LightMuted = Color(0xFF6B7385)
    val LightLine = Color(0xFFE4E7EE)

    val Indigo = Color(0xFF3B4C9C)
    val IndigoDark = Color(0xFF2B3A7A)
    val LightIndigoSoft = Color(0xFFEEF0FA)

    val Mint = Color(0xFF2FBF87)
    val LightMintSoft = Color(0xFFE7F8F0)
    val MintBright = Color(0xFF8CF0C4)

    val Coral = Color(0xFFF97066)
    val LightCoralSoft = Color(0xFFFDEEEC)

    val LightFieldGray = Color(0xFFF0F1F5)

    val LightContainerLowest = Color(0xFFFFFFFF)
    val LightContainerLow = Color(0xFFFAFBFC)
    val LightContainer = Color(0xFFF7F8FA)
    val LightContainerHigh = Color(0xFFF5F6F9)
    val LightContainerHighest = Color(0xFFF0F1F5)
    val LightSurfaceDim = Color(0xFFE9EBF1)
    val LightSurfaceBright = Color(0xFFFFFFFF)

    // ── 深色 ──────────────────────────────────────────────
    val DarkBg = Color(0xFF10131A)
    val DarkCard = Color(0xFF1A1F2B)
    val DarkInk = Color(0xFFE6E9F2)
    val DarkMuted = Color(0xFF9AA3B5)
    val DarkLine = Color(0xFF2A3140)
    val DarkIndigoSoft = Color(0xFF2A3563)
    val DarkMintSoft = Color(0xFF17382C)
    val DarkCoralSoft = Color(0xFF3D2424)
    val DarkFieldGray = Color(0xFF232936)

    val DarkContainerLowest = Color(0xFF0B0E14)
    val DarkContainerLow = Color(0xFF161B25)
    val DarkContainer = Color(0xFF1A1F2B)
    val DarkContainerHigh = Color(0xFF20262F)
    val DarkContainerHighest = Color(0xFF262C38)
    val DarkSurfaceDim = Color(0xFF10131A)
    val DarkSurfaceBright = Color(0xFF343B49)

    val LightPrimaryDark = Color(0xFF9FB0F0)
    val LightOnPrimaryDark = Color(0xFF1B2447)
    val LightOnPrimaryContainerDark = Color(0xFFD5DCFA)
    val TertiaryDark = Color(0xFFFFB4A8)
    val OnTertiaryDark = Color(0xFF561E1A)

    val LightScheme: ColorScheme = lightColorScheme(
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
        inversePrimary = LightPrimaryDark,
        scrim = Color(0xFF000000)
    )

    val DarkScheme: ColorScheme = darkColorScheme(
        primary = LightPrimaryDark,
        onPrimary = LightOnPrimaryDark,
        primaryContainer = DarkIndigoSoft,
        onPrimaryContainer = LightOnPrimaryContainerDark,
        secondary = MintBright,
        onSecondary = Color(0xFF06301F),
        secondaryContainer = DarkMintSoft,
        onSecondaryContainer = MintBright,
        tertiary = TertiaryDark,
        onTertiary = OnTertiaryDark,
        tertiaryContainer = DarkCoralSoft,
        onTertiaryContainer = TertiaryDark,
        background = DarkBg,
        onBackground = DarkInk,
        surface = DarkCard,
        onSurface = DarkInk,
        surfaceVariant = DarkFieldGray,
        onSurfaceVariant = DarkMuted,
        outline = DarkLine,
        outlineVariant = DarkLine,
        surfaceTint = LightPrimaryDark,
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

    /**
     * 兜底：没有主题包（或首启 bootstrap 尚未完成）时的配色。
     *
     * 必须与 1.5.0 的硬编码配色逐位一致 —— 否则用户第一次打开 App 会看到一帧
     * M3 淡紫，或者干脆永久停在一套「没装主题就该有的颜色」上。
     */
    fun baseline(dark: Boolean = false): SkinResolved {
        val scheme = if (dark) DarkScheme else LightScheme
        val pillStart = if (dark) LightPrimaryDark else Indigo
        return SkinResolved(
            id = "default",
            name = "默认",
            scheme = scheme,
            ext = ExtColors(
                glassTop = scheme.surface,
                glassMid = scheme.surface,
                glassBottom = scheme.surface,
                glassRim = Color.White,
                cardRim = scheme.outlineVariant,
                pillStart = pillStart,
                pillEnd = IndigoDark,
                navActiveStart = scheme.primaryContainer,
                navActiveEnd = scheme.primaryContainer,
                // Hero 卡是深底白字：轨道/进度/文字的兜底都必须按「在白字体系里」取，
                // 否则浅色主题下会在靛蓝卡上出现灰轨道、灰字。
                heroTrack = Color.White.copy(alpha = 0.22f),
                heroBarStart = MintBright,
                heroBarEnd = Mint,
                heroOnDim = Color.White.copy(alpha = 0.7f),
                heroOnSoft = Color.White.copy(alpha = 0.9f),
                inkTitle = scheme.onSurface,
                inkBody = scheme.onSurfaceVariant,
                inkHint = scheme.onSurfaceVariant,
                night = scheme.inverseSurface,
                deep = scheme.scrim,
                hydro = scheme.secondary,
                gold = scheme.tertiary,
                goldInk = scheme.tertiary,
                hasExplicitGold = false,
                topGlow = Color(0x2E000000),
                navSurface = scheme.surface.copy(alpha = 0.96f)
            ),
            shape = ShapeTokens(),
            metrics = MetricTokens(),
            typography = TypographyTokens(),
            assets = SkinAssets(),
            isBuiltin = true
        )
    }
}
