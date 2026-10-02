package com.mineserve.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── 亮色设计令牌（与参考界面完全对齐） ──────────────────────────────
val LightBg = Color(0xFFF3F4F8)
val LightCard = Color(0xFFFFFFFF)
val LightInk = Color(0xFF1F2430)
val LightMuted = Color(0xFF8890A0)
val LightLine = Color(0xFFE7E9F0)

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
