package com.mineserve.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 对齐参考 html：
 *  - body   : Manrope 400/500/600/700/800
 *  - title  : Sora     600/700/800
 * 由于 Android 默认无 Manrope/Sora，使用系统字体作为兜底；保留字号字重语义
 *
 * 这里把**整套**样式都补全，而不是只覆盖 title/body 三档：
 * AlertDialog 标题取 headlineSmall、Snackbar 取 bodyMedium、Chip 取 labelLarge，
 * 未覆盖的样式会落到 Material3 默认值（如 24sp 的弹窗标题），
 * 于是同一个应用里出现两套字号节奏。补全后任何走默认样式的组件都在体系内。
 */
private val sora = TextStyle(fontWeight = FontWeight.Bold)
private val manrope = TextStyle(fontWeight = FontWeight.Medium)

val McTypography = Typography(
    // ── 页面 / 弹窗标题 ──
    headlineLarge = sora.copy(fontSize = 24.sp, lineHeight = 32.sp),
    headlineMedium = sora.copy(fontSize = 20.sp, lineHeight = 28.sp),
    headlineSmall = sora.copy(fontSize = 17.sp, lineHeight = 24.sp),

    // ── 卡片 / 段落标题 ──
    titleLarge = sora.copy(fontSize = 21.sp, lineHeight = 28.sp),                 // h1
    titleMedium = sora.copy(fontSize = 14.sp, lineHeight = 20.sp),                // 卡片标题
    titleSmall = manrope.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp), // 列表项标题

    // ── 正文 ──
    bodyLarge = manrope.copy(fontSize = 13.sp, lineHeight = 19.sp),
    bodyMedium = manrope.copy(fontSize = 12.sp, lineHeight = 17.sp),
    bodySmall = manrope.copy(fontSize = 11.sp, lineHeight = 16.sp),

    // ── 标签 / 按钮 / 极小说明 ──
    labelLarge = manrope.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp),
    labelMedium = manrope.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp),
    labelSmall = manrope.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp)
)
