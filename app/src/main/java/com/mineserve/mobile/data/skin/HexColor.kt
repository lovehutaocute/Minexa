package com.mineserve.mobile.data.skin

import androidx.compose.ui.graphics.Color

/**
 * 颜色解析与 WCAG 对比度计算。
 *
 * 主题包来自外部，任何一处「解析失败就抛异常」都会变成导入时的崩溃，所以这里的选择是：
 * 非法 → 返回 null / 只告警，绝不抛。
 */
private val HEX = Regex("^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

/** 解析 `#RRGGBB` / `#AARRGGBB`（alpha 在前，与 Android 一致，不是 CSS 的 `#RRGGBBAA`）。 */
fun parseHexColor(s: String?): Color? {
    if (s == null || !HEX.matches(s)) return null
    val h = s.substring(1)
    // 必须走 toLong(16) 再截断：8 位色值如 F5E5ECFC 超过 Int.MAX_VALUE，
    // 直接 toInt(16) 会抛 NumberFormatException。
    val argb = h.toLong(16).toInt()
    return Color(if (h.length == 6) argb or 0xFF000000.toInt() else argb)
}

/** 带兜底：缺键时用内置默认值，保证任何缺失都不会让界面变透明。 */
internal fun String?.colorOr(fallback: Color): Color = parseHexColor(this) ?: fallback

/** (r,g,b,a) —— 与 themetool.py 的 `parse_color` 等价的中间表示，用于对比度计算。 */
internal data class Rgba(val r: Int, val g: Int, val b: Int, val a: Float)

internal fun parseRgba(s: String?): Rgba? {
    if (s == null || !HEX.matches(s)) return null
    val h = s.substring(1)
    return if (h.length == 6) {
        Rgba(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16), 1f)
    } else {
        Rgba(
            h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16), h.substring(6, 8).toInt(16),
            h.substring(0, 2).toInt(16) / 255f
        )
    }
}

private fun linear(c: Int): Double {
    val s = c / 255.0
    return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
}

private fun luminance(r: Int, g: Int, b: Int): Double =
    0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)

/**
 * WCAG 对比度。半透明前景**先按 alpha 合成到背景上**再算 —— 主题包大量使用 96% 的玻璃色，
 * 直接取 RGB 会得到偏高的假对比度。
 */
internal fun contrastRatio(fg: Rgba, bg: Rgba): Double {
    val composed = IntArray(3) { i ->
        val f = when (i) { 0 -> fg.r; 1 -> fg.g; else -> fg.b }
        val b = when (i) { 0 -> bg.r; 1 -> bg.g; else -> bg.b }
        Math.round(f * fg.a + b * (1 - fg.a))
    }
    val l1 = luminance(composed[0], composed[1], composed[2])
    val l2 = luminance(bg.r, bg.g, bg.b)
    return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
}

/** 对比度检查表：与 `themetool.py` 的 `CONTRAST_RULES` 一一对应，改一处必须改另一处。 */
internal data class ContrastRule(
    val group: String,
    val fgKey: String,
    val bgKey: String,
    val min: Double,
    val label: String
)

internal val CONTRAST_RULES = listOf(
    ContrastRule("color", "onBackground", "background", 4.5, "页面正文"),
    ContrastRule("color", "onSurface", "surface", 4.5, "卡片正文"),
    ContrastRule("color", "onSurfaceVariant", "surface", 4.5, "次要说明文字"),
    ContrastRule("color", "onPrimary", "primary", 4.5, "主按钮文字"),
    ContrastRule("color", "onSecondary", "secondary", 4.5, "成功态文字"),
    ContrastRule("color", "onTertiary", "tertiary", 4.5, "危险态文字"),
    ContrastRule("color", "onPrimaryContainer", "primaryContainer", 4.5, "浅色容器文字"),
    ContrastRule("color", "onSecondaryContainer", "secondaryContainer", 4.5, "成功容器文字"),
    ContrastRule("color", "onTertiaryContainer", "tertiaryContainer", 4.5, "危险容器文字"),
    ContrastRule("ext", "inkTitle", "glassTop", 4.5, "卡片标题"),
    ContrastRule("ext", "inkBody", "glassMid", 4.5, "卡片正文"),
    ContrastRule("ext", "inkHint", "glassBottom", 4.5, "卡片提示"),
    ContrastRule("ext", "heroOnDim", "night", 3.0, "Hero 卡深底小字")
)
