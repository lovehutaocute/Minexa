package com.mineserve.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
