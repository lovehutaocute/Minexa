package com.mineserve.mobile.data.skin

import kotlinx.serialization.Serializable

/**
 * 主题包（`.mctheme`）的数据模型。
 *
 * 这个文件只描述「包长什么样」，不做任何校验与 IO —— 校验在 [SkinPack] / [SkinInstaller]，
 * 解析成运行时对象在 [SkinRuntime]。所有字段都给了默认值：包来自外部，任何一个键缺失
 * 都必须能落到默认值上，而不是抛异常（`ignoreUnknownKeys = true` 负责前向兼容）。
 */
@Serializable
data class SkinManifest(
    val schemaVersion: Int = 0,
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val minAppVersionCode: Int = 0,
    val supportsDark: Boolean = true,
    val preview: String? = null,
    val tokens: String = "tokens.json",
    val assets: Map<String, String> = emptyMap(),
    val assetsMeta: Map<String, AssetMeta> = emptyMap(),
    /**
     * 深色模式专用的组件贴图（可选）。
     *
     * 为什么需要：组件贴图是**烘焙了颜色**的位图，浅色那套是白面板，
     * 深色模式下直接用就是一块白板配浅色文字 —— 字全看不见。
     * 键和 [assets] 完全一样；缺某个槽位就回退到浅色那张（老包行为不变）。
     */
    val assetsDark: Map<String, String> = emptyMap(),
    val assetsMetaDark: Map<String, AssetMeta> = emptyMap()
)

@Serializable
data class AssetMeta(
    val width: Int = 0,
    val height: Int = 0,
    val scale: String = "crop",
    /**
     * 九宫格边距，单位 dp，顺序 `[左, 上, 右, 下]`。
     *
     * 给了就按九宫格拉伸（四角保持原像素，中间区域拉伸），经典用法是让圆角不变形；
     * 不给就整图拉伸 —— 纯色 / 简单渐变的贴图看不出差别，带花纹的必须给。
     *
     * 换算成图像素时要靠 [width]（设计宽度 dp）：没有它就算不出「这段边距占图的多大比例」，
     * 会被当成整图拉伸处理。
     */
    val ninePatch: List<Float>? = null,
    /**
     * 是否用主题色**染色**。
     *
     * 开了之后贴图只取形状（alpha），颜色由主题的语义色决定 —— 一张白色按钮图可以同时
     * 给浅色 / 深色 / 不同主题复用。带花纹、带描边细节的图不要开。
     */
    val tint: Boolean = false
)

/** 浅色/深色双份令牌。字段缺失时由 `colorOr()` 回退到 M3 角色色。 */
@Serializable
data class Sided<T>(val light: T? = null, val dark: T? = null)

@Serializable
data class ShapeTokens(
    val cardRadius: Float = 18f,
    val controlRadius: Float = 16f,
    val dialogRadius: Float = 28f,
    val heroRadius: Float = 18f,
    val navRadius: Float = 18f
)

@Serializable
data class MetricTokens(
    val glassAlpha: Float = 0.94f,
    val backdropScrimDark: Float = 0.62f,
    val backdropTopGlowAlpha: Float = 0.18f,
    val heroCardOffsetRatio: Float = 0.0407f,
    val accentArtAlpha: Float = 0.55f,
    val navSurfaceAlpha: Float = 0.96f,
    /**
     * Hero 卡插画上压的遮罩强度（0 = 不压）。
     *
     * 默认 0：主题作者给的 hero 图通常自带深底（如芙宁娜那张左半是深蓝），
     * 再压一层黑幕只会把插画糊掉。真的用了浅色插画的主题才需要把它调到 0.35–0.55。
     */
    val heroScrim: Float = 0f
)

@Serializable
data class TypographyTokens(
    val titleLetterSpacing: Float = 0.4f,
    val heroTitleSize: Float = 21f,
    val useSerifTitle: Boolean = false
)

/** Material3 的 32 个角色色。缺键时按 alpha 合成前的角色色兜底。 */
@Serializable
data class ColorRoleTokens(
    val primary: String? = null,
    val onPrimary: String? = null,
    val primaryContainer: String? = null,
    val onPrimaryContainer: String? = null,
    val secondary: String? = null,
    val onSecondary: String? = null,
    val secondaryContainer: String? = null,
    val onSecondaryContainer: String? = null,
    val tertiary: String? = null,
    val onTertiary: String? = null,
    val tertiaryContainer: String? = null,
    val onTertiaryContainer: String? = null,
    val background: String? = null,
    val onBackground: String? = null,
    val surface: String? = null,
    val onSurface: String? = null,
    val surfaceVariant: String? = null,
    val onSurfaceVariant: String? = null,
    val outline: String? = null,
    val outlineVariant: String? = null,
    val surfaceTint: String? = null,
    val surfaceContainerLowest: String? = null,
    val surfaceContainerLow: String? = null,
    val surfaceContainer: String? = null,
    val surfaceContainerHigh: String? = null,
    val surfaceContainerHighest: String? = null,
    val surfaceDim: String? = null,
    val surfaceBright: String? = null,
    val inverseSurface: String? = null,
    val inverseOnSurface: String? = null,
    val inversePrimary: String? = null,
    val scrim: String? = null
) {
    /** 32 个必填键（键名与字段名一致），校验与对比度检查共用。 */
    val entries: List<Pair<String, String?>>
        get() = listOf(
            "primary" to primary, "onPrimary" to onPrimary,
            "primaryContainer" to primaryContainer, "onPrimaryContainer" to onPrimaryContainer,
            "secondary" to secondary, "onSecondary" to onSecondary,
            "secondaryContainer" to secondaryContainer, "onSecondaryContainer" to onSecondaryContainer,
            "tertiary" to tertiary, "onTertiary" to onTertiary,
            "tertiaryContainer" to tertiaryContainer, "onTertiaryContainer" to onTertiaryContainer,
            "background" to background, "onBackground" to onBackground,
            "surface" to surface, "onSurface" to onSurface,
            "surfaceVariant" to surfaceVariant, "onSurfaceVariant" to onSurfaceVariant,
            "outline" to outline, "outlineVariant" to outlineVariant,
            "surfaceTint" to surfaceTint,
            "surfaceContainerLowest" to surfaceContainerLowest,
            "surfaceContainerLow" to surfaceContainerLow,
            "surfaceContainer" to surfaceContainer,
            "surfaceContainerHigh" to surfaceContainerHigh,
            "surfaceContainerHighest" to surfaceContainerHighest,
            "surfaceDim" to surfaceDim, "surfaceBright" to surfaceBright,
            "inverseSurface" to inverseSurface, "inverseOnSurface" to inverseOnSurface,
            "inversePrimary" to inversePrimary, "scrim" to scrim
        )

    fun byKey(key: String): String? = entries.firstOrNull { it.first == key }?.second
}

/** 扩展令牌：管角色皮肤（玻璃卡、Hero、导航、金线），缺键回退到 [ColorRoleTokens]。 */
@Serializable
data class ExtTokens(
    val glassTop: String? = null,
    val glassMid: String? = null,
    val glassBottom: String? = null,
    val glassRim: String? = null,
    val cardRim: String? = null,
    val pillStart: String? = null,
    val pillEnd: String? = null,
    val navActiveStart: String? = null,
    val navActiveEnd: String? = null,
    val heroTrack: String? = null,
    val heroBarStart: String? = null,
    val heroBarEnd: String? = null,
    val heroOnDim: String? = null,
    val heroOnSoft: String? = null,
    val inkTitle: String? = null,
    val inkBody: String? = null,
    val inkHint: String? = null,
    val night: String? = null,
    val deep: String? = null,
    val hydro: String? = null,
    val gold: String? = null,
    val goldInk: String? = null,
    val topGlow: String? = null,
    val navSurface: String? = null
) {
    val entries: List<Pair<String, String?>>
        get() = listOf(
            "glassTop" to glassTop, "glassMid" to glassMid, "glassBottom" to glassBottom,
            "glassRim" to glassRim, "cardRim" to cardRim,
            "pillStart" to pillStart, "pillEnd" to pillEnd,
            "navActiveStart" to navActiveStart, "navActiveEnd" to navActiveEnd,
            "heroTrack" to heroTrack, "heroBarStart" to heroBarStart, "heroBarEnd" to heroBarEnd,
            "heroOnDim" to heroOnDim, "heroOnSoft" to heroOnSoft,
            "inkTitle" to inkTitle, "inkBody" to inkBody, "inkHint" to inkHint,
            "night" to night, "deep" to deep, "hydro" to hydro,
            "gold" to gold, "goldInk" to goldInk,
            "topGlow" to topGlow, "navSurface" to navSurface
        )

    fun byKey(key: String): String? = entries.firstOrNull { it.first == key }?.second
}

@Serializable
data class SkinTokens(
    val schemaVersion: Int = 1,
    val shape: ShapeTokens = ShapeTokens(),
    val metrics: MetricTokens = MetricTokens(),
    val color: Sided<ColorRoleTokens> = Sided(),
    val ext: Sided<ExtTokens> = Sided(),
    val typography: TypographyTokens = TypographyTokens()
)
