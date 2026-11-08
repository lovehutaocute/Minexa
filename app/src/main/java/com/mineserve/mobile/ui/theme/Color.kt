package com.mineserve.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── 亮色设计令牌（与参考界面完全对齐） ──────────────────────────────
val LightBg = Color(0xFFF3F4F8)
val LightCard = Color(0xFFFFFFFF)
val LightInk = Color(0xFF1F2430)
// 次要文字由 #8890A0 加深到 #6B7385：原值在纯白卡片上的对比度只有约 3.2:1，
// 11sp 的说明文字（全项目用量最大的一档）读起来发灰；新值约 4.8:1，达到 WCAG AA，
// 色相不变，因此配色观感保持一致。
val LightMuted = Color(0xFF6B7385)
// 卡片描边由 #E7E9F0 微微加深：过浅的描边在纯白卡片上几乎不可见，
// 加深后卡片边界更清晰，同时仍然足够轻，不会显得厚重。
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

// ── 深色设计令牌 ─────────────────────────────────────────────────
val DarkBg = Color(0xFF10131A)
val DarkCard = Color(0xFF1A1F2B)
val DarkInk = Color(0xFFE6E9F2)
val DarkMuted = Color(0xFF9AA3B5)
val DarkLine = Color(0xFF2A3140)
val DarkIndigoSoft = Color(0xFF2A3563)
val DarkMintSoft = Color(0xFF17382C)
val DarkCoralSoft = Color(0xFF3D2424)
val DarkFieldGray = Color(0xFF232936)

// ── 容器色阶 ──────────────────────────────────────────────────────
// Material3 的弹窗、下拉菜单、Chip 等组件默认取 surfaceContainer* 系列色，
// 若不在 colorScheme 里显式覆盖，它们会落到 M3 内置的淡紫灰（#ECE6F0 一类），
// 与项目的中性灰蓝体系明显不是一套配色。这里把整条容器色阶显式定义出来，
// 让所有走默认值的组件自动落在同一套配色上，不必逐个调用点去改。
val LightContainerLowest = Color(0xFFFFFFFF)
val LightContainerLow = Color(0xFFFAFBFC)
val LightContainer = Color(0xFFF7F8FA)
val LightContainerHigh = Color(0xFFF5F6F9)
val LightContainerHighest = Color(0xFFF0F1F5)
val LightSurfaceDim = Color(0xFFE9EBF1)
val LightSurfaceBright = Color(0xFFFFFFFF)

val DarkContainerLowest = Color(0xFF0B0E14)
val DarkContainerLow = Color(0xFF161B25)
val DarkContainer = Color(0xFF1A1F2B)
val DarkContainerHigh = Color(0xFF20262F)
val DarkContainerHighest = Color(0xFF262C38)
val DarkSurfaceDim = Color(0xFF10131A)
val DarkSurfaceBright = Color(0xFF343B49)

// ── 终端配色 ─────────────────────────────────────────────────────
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
    bar = LightCard,
    line = LightLine,
    ink = LightInk,
    muted = LightMuted,
    accent = Indigo,
    onAccent = Color.White,
    chipBg = LightFieldGray,
    fieldBg = LightCard,
    // 浅底上的日志色统一压暗一档：原来的 One Dark 亮色（#57E357 等）
    // 放到白底上对比度不到 2:1，等于看不见。
    logCommand = Color(0xFF0E7A54),
    logError = Color(0xFFC0362C),
    logWarning = Color(0xFF8A6000),
    logInfo = Color(0xFF0F6B66),
    logDebug = Color(0xFF2B4FA8),
    logDownload = Color(0xFF3B4C9C),
    logSuccess = Color(0xFF146C46),
    logDefault = LightInk
)

val DarkTerminalPalette = TerminalPalette(
    surface = DarkContainerLowest,
    bar = DarkContainerLow,
    line = DarkLine,
    ink = DarkInk,
    muted = DarkMuted,
    accent = Color(0xFF9FB0F0),
    onAccent = Color(0xFF1B2447),
    chipBg = DarkContainerHigh,
    fieldBg = DarkContainerLow,
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
    logDefault = DarkInk
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

// Theme tokens come from MaterialTheme, so previews and explicit theme overrides
// remain consistent instead of silently reading the system theme again.
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
