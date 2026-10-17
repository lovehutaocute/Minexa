package com.mineserve.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 全局尺寸令牌。
 *
 * 存在的意义是把原先散落在 20+ 个页面里的 8/10/12/14/16/18/20dp 之类魔数收敛成
 * 一套可复用的取值：页面左右边距只有 [Page] 一个取值，卡片之间只有 [CardGap]，
 * 卡片内边距只有 [CardInner] / [CardInnerCompact]。
 * 组件与页面一律引用这里的令牌，不再各写各的数字，避免同一层级出现不同的留白。
 */
object McSpacing {
    /** 页面左右安全边距。HeaderBlock、McCard 与页面级列表内容都用它，保证左边缘对齐。 */
    val Page = 16.dp

    /** 卡片之间的垂直间距。 */
    val CardGap = 8.dp

    /** 紧凑卡片（compact）之间的垂直间距。 */
    val CardGapCompact = 4.dp

    /** 卡片内边距。 */
    val CardInner = 14.dp

    /** 紧凑卡片内边距。 */
    val CardInnerCompact = 12.dp

    /** 卡片内分组之间的间距（标题→内容、分组→分组）。 */
    val Section = 12.dp

    /** 行内元素之间的间距。 */
    val Row = 8.dp

    /** 卡片标题与内容之间的间距。 */
    val CardHeader = 10.dp

    /** 紧凑卡片标题与内容之间的间距。 */
    val CardHeaderCompact = 8.dp
}

/**
 * 全局圆角令牌。
 *
 * 卡片、按钮、输入框、列表行的圆角是设计语言里最容易被“随手写一个数”破坏的部分，
 * 这里固定为几档：卡片 12dp、控件 12dp（行内紧凑控件 10dp）、行内小容器 10dp、
 * 弹窗 20dp。同一层级只用一档，避免相邻元素圆角不同心。
 */
object McShape {
    /** 卡片（McCard）与页面级容器。 */
    val Card = RoundedCornerShape(12.dp)

    /** 按钮、输入框、下拉框等可操作控件（卡片级 / 整行 / 弹窗操作）。 */
    val Control = RoundedCornerShape(12.dp)

    /** 行内紧凑控件（列表行、字段行里与其它内容并排的小按钮）。 */
    val ControlCompact = RoundedCornerShape(10.dp)

    /** 卡片内部的行级容器（列表行、地址条、分组标题）。 */
    val Row = RoundedCornerShape(10.dp)

    /** 步骤点、标签等小圆角。 */
    val Small = RoundedCornerShape(6.dp)

    /** 状态胶囊 / 分段控件的滑轨。 */
    val Chip = RoundedCornerShape(12.dp)

    /** 弹窗。 */
    val Dialog = RoundedCornerShape(20.dp)

    /** 完全圆形（状态圆点、头像占位）。 */
    val Circle = RoundedCornerShape(percent = 50)
}

/**
 * 全局图标尺寸令牌。
 *
 * 只保留三档常用尺寸 + 一档命中区：同一语义（行内提示 / 卡片标题 / 页面操作 /
 * 图标按钮）在任意页面看到的线条粗细与视觉重量都一致，不再出现 15/16/17/18dp 混用。
 */
object McIcon {
    /** 行内说明图标（提示条、统计行）。 */
    val Inline = 16.dp

    /** 卡片标题左侧的操作图标。 */
    val Action = 18.dp

    /** 列表项 / 主要操作图标。 */
    val Item = 20.dp

    /** 空状态、弹窗主图标。 */
    val Display = 24.dp

    /** 图标按钮的最小命中区（≥ 平台建议的 40dp）。 */
    val Touch = 40.dp

    /** 紧凑行内的图标按钮命中区。 */
    val TouchCompact = 36.dp
}
