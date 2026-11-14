package com.mineserve.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── 全局配色：全部读「当前生效主题」 ────────────────────────────────
// 这些名字是 1.5.0 之前就存在的顶层常量，被 20+ 页面直接引用（Indigo 一处就 300+ 次）。
// 现在它们改成 getter，取值来自 [SkinPalette.current]（由 MineServeMobileTheme 安装）。
// 调用点零改动，换主题整界面同步 —— 具体理由见 SkinPalette 的注释。
//
// 字面色值的唯一出处是 [SkinDefaults]：没有主题包时这里取到的值与 1.5.0 完全一致。

private val scheme get() = SkinPalette.current.scheme
private val ext get() = SkinPalette.current.ext

val LightBg: Color get() = scheme.background
val LightCard: Color get() = scheme.surface
val LightInk: Color get() = scheme.onBackground
val LightMuted: Color get() = scheme.onSurfaceVariant
val LightLine: Color get() = scheme.outline

val Indigo: Color get() = scheme.primary
val IndigoDark: Color get() = ext.pillEnd
val LightIndigoSoft: Color get() = scheme.primaryContainer

val Mint: Color get() = scheme.secondary
val LightMintSoft: Color get() = scheme.secondaryContainer
val MintBright: Color get() = scheme.secondary

val Coral: Color get() = scheme.tertiary
val LightCoralSoft: Color get() = scheme.tertiaryContainer

val LightFieldGray: Color get() = scheme.surfaceVariant

// ── 深色令牌 ─────────────────────────────────────────────────────
// 语义在改造后变成了「当前主题的对应角色」：深色模式下取深色令牌，浅色模式下取浅色令牌。
// 唯一还在用它们的是 Theme.kt 的默认配色（已迁到 SkinDefaults），所以不会出现
// 「浅色模式读到深色值」的错配。
val DarkBg: Color get() = scheme.background
val DarkCard: Color get() = scheme.surface
val DarkInk: Color get() = scheme.onBackground
val DarkMuted: Color get() = scheme.onSurfaceVariant
val DarkLine: Color get() = scheme.outline
val DarkIndigoSoft: Color get() = scheme.primaryContainer
val DarkMintSoft: Color get() = scheme.secondaryContainer
val DarkCoralSoft: Color get() = scheme.tertiaryContainer
val DarkFieldGray: Color get() = scheme.surfaceVariant

// ── 容器色阶 ──────────────────────────────────────────────────────
val LightContainerLowest: Color get() = scheme.surfaceContainerLowest
val LightContainerLow: Color get() = scheme.surfaceContainerLow
val LightContainer: Color get() = scheme.surfaceContainer
val LightContainerHigh: Color get() = scheme.surfaceContainerHigh
val LightContainerHighest: Color get() = scheme.surfaceContainerHighest
val LightSurfaceDim: Color get() = scheme.surfaceDim
val LightSurfaceBright: Color get() = scheme.surfaceBright

val DarkContainerLowest: Color get() = scheme.surfaceContainerLowest
val DarkContainerLow: Color get() = scheme.surfaceContainerLow
val DarkContainer: Color get() = scheme.surfaceContainer
val DarkContainerHigh: Color get() = scheme.surfaceContainerHigh
val DarkContainerHighest: Color get() = scheme.surfaceContainerHighest
val DarkSurfaceDim: Color get() = scheme.surfaceDim
val DarkSurfaceBright: Color get() = scheme.surfaceBright

// ── 主题扩展色（角色皮肤） ────────────────────────────────────────
/** 玻璃卡 1px 内描边。 */
val ThemeGlassRim: Color get() = ext.glassRim
val ThemeCardRim: Color get() = ext.cardRim
val ThemeNavSurface: Color get() = ext.navSurface
val ThemeTopGlow: Color get() = ext.topGlow
val ThemeNight: Color get() = ext.night
val ThemeDeep: Color get() = ext.deep
val ThemeGold: Color get() = ext.gold
val ThemeHydro: Color get() = ext.hydro

// ── 终端配色（明确不主题化） ─────────────────────────────────────
/**
 * 终端页专用调色板。
 *
 * 终端是唯一需要「等宽字体 + 区分语义的日志色」的页面，直接套卡片体系里的
 * Indigo / Mint / Coral 不行 —— 那几档是给浅色卡片上的小面积点缀用的，
 * 铺成整屏正文会艳得跳出体系。但也不能像改造前那样把 `#121419`、`#57E357`
 * 这些散装十六进制写死在页面里：亮色主题下这一页会变成一整块黑洞，
 * 与全 App 的浅色卡片完全割裂，深色主题下它的底色又和 [DarkBg] 差一点点，
 * 和其它页拼在同一条导航栏上看着就是不齐。
 *
 * 所以这里单独定义一套终端色，由主题通过 [LocalTerminalPalette] 下发：
 * 浅色主题 = 浅底深字，夜间主题 = 深底浅字，色相全部取自现有令牌家族。
 *
 * 日志色均在各自底色上校验过对比度，正文档 ≥ 4.5:1（WCAG AA），
 * 命令提示与加粗标识 ≥ 3:1；同一色相在浅/深两套里保持一致的语义指向。
 *
 * **主题包不改变这两套色**：日志色需要跨主题稳定（用户记住「红色=报错」），
 * 而且终端页有自己的深浅开关，跟 App 主题不是一套逻辑。因此这里用字面色值，
 * 不读 [SkinPalette] —— 这是刻意的，不是遗漏。
 */
data class TerminalPalette(
    /** 页面底色。 */
    val surface: Color,
    /** 标题栏 / 输入栏底色，比 [surface] 高一档做出分区。 */
    val bar: Color,
    /** 分隔线、描边。 */
    val line: Color,
    /** 正文。 */
    val ink: Color,
    /** 次要文字、未选中标签。 */
    val muted: Color,
    /** 强调色：选中标签、光标、发送按钮。 */
    val accent: Color,
    /** 选中标签上的文字色。 */
    val onAccent: Color,
    /** 未选中胶囊 / 快捷指令底色。 */
    val chipBg: Color,
    /** 输入框底色。 */
    val fieldBg: Color,
    val logCommand: Color,
    val logError: Color,
    val logWarning: Color,
    val logInfo: Color,
    val logDebug: Color,
    val logDownload: Color,
    val logSuccess: Color,
    val logDefault: Color
)

val LightTerminalPalette = TerminalPalette(
    surface = Color(0xFFFBFBFD),
    bar = Color(0xFFFFFFFF),
    line = Color(0xFFE4E7EE),
    ink = Color(0xFF1F2430),
    muted = Color(0xFF6B7385),
    accent = Color(0xFF3B4C9C),
    onAccent = Color.White,
    chipBg = Color(0xFFF0F1F5),
    fieldBg = Color(0xFFFFFFFF),
    // 浅底上的日志色统一压暗一档：原来的 One Dark 亮色（#57E357 等）
    // 放到白底上对比度不到 2:1，等于看不见。
    logCommand = Color(0xFF0E7A54),
    logError = Color(0xFFC0362C),
    logWarning = Color(0xFF8A6000),
    logInfo = Color(0xFF0F6B66),
    logDebug = Color(0xFF2B4FA8),
    logDownload = Color(0xFF3B4C9C),
    logSuccess = Color(0xFF146C46),
    logDefault = Color(0xFF1F2430)
)

val DarkTerminalPalette = TerminalPalette(
    surface = Color(0xFF0B0E14),
    bar = Color(0xFF161B25),
    line = Color(0xFF2A3140),
    ink = Color(0xFFE6E9F2),
    muted = Color(0xFF9AA3B5),
    accent = Color(0xFF9FB0F0),
    onAccent = Color(0xFF1B2447),
    chipBg = Color(0xFF20262F),
    fieldBg = Color(0xFF161B25),
    // 深底上的日志色沿用原终端观感（用户已经看惯了），但把色相对齐到项目色系：
    // 原来的 #82AAFF / #89B4FA 两个蓝几乎分不出，这里 Debug 归到 Indigo、
    // Download 改成更亮的青蓝，两者一眼能分开。
    logCommand = Color(0xFF7EE7B8),
    logError = Color(0xFFFF9E93),
    logWarning = Color(0xFFE8BE5C),
    logInfo = Color(0xFF79D6CC),
    logDebug = Color(0xFF9FB0F0),
    logDownload = Color(0xFF8CC4FF),
    logSuccess = Color(0xFFA6E3A1),
    logDefault = Color(0xFFE6E9F2)
)

/**
 * 当前终端会话的调色板。
 *
 * 终端的深浅色由终端页自己的开关决定（默认深色），不跟随系统主题 ——
 * 所以这里由 [com.mineserve.mobile.ui.screens.TerminalScreen] 在页面内 provide，
 * 而不是在 [MineServeMobileTheme] 里统一下发。默认值取深色，任何遗漏包裹的
 * 调用点都会落在「黑色终端」这个默认约定上，不会出现半深半浅的混合页面。
 *
 * 用 static 版本：它只在这一个开关切换时变化，不随重组读取，终端页有几百个
 * 日志行，每行都去读一次 compositionLocalOf 的 track 没有意义。
 */
val LocalTerminalPalette = staticCompositionLocalOf { DarkTerminalPalette }

// 语义读法：新代码优先用这些（跟随 MaterialTheme，预览里也能取到正确值）。
val Bg: Color @Composable get() = MaterialTheme.colorScheme.background
val Card: Color @Composable get() = MaterialTheme.colorScheme.surface
val Ink: Color @Composable get() = MaterialTheme.colorScheme.onBackground
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
val IndigoSoft: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
val MintSoft: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer
val CoralSoft: Color @Composable get() = MaterialTheme.colorScheme.tertiaryContainer
val FieldBg: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
val TrackBg: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
val FieldGray: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
