package com.mineserve.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mineserve.mobile.data.skin.SkinTile
import com.mineserve.mobile.ui.theme.LocalSkin
import kotlin.math.roundToInt

/**
 * 组件贴图（`.mctheme` v2）。
 *
 * 主题包可以在 `assets` 里给单个组件塞一张图，比如：
 * ```jsonc
 * "assets":      { "card": "assets/tiles/card.webp" },
 * "assetsMeta":  { "card": { "width": 328, "height": 96,
 *                            "ninePatch": [18, 18, 18, 18], "tint": false } }
 * ```
 *
 * 设计上**永远有颜色兜底**：没给图就是原来那句 `.background(color)`，一个像素都不变。
 * 这样老的 v1 包、没做组件贴图的包、解析失败的包，行为完全一致。
 */

/**
 * 当前模式该用的那张贴图。
 *
 * 贴图是烘焙了颜色的位图：浅色那套在深色模式下就是一块白板配浅字。
 * 所以包里给了 `assetsDark` 就优先它，缺某个槽位再回退浅色那张。
 *
 * 必须是 composable —— 它读 [LocalSkin] 这个 CompositionLocal。早前漏了这个注解，
 * 整个文件编译失败，于是同包的 [skinTile] 等符号对所有调用方都变成
 * Unresolved reference，报错点却落在 SharedComponents.kt（病根在这里，不在那里）。
 */
@Composable
private fun currentTiles(): Map<String, SkinTile> {
    val skin = LocalSkin.current
    return if (skin.isDark && skin.assets.tilesDark.isNotEmpty()) skin.assets.tilesDark
    else skin.assets.tiles
}

/**
 * 「有图用图、没图用色」的通用底。
 *
 * @param slot 槽位名，必须在 `SkinTileSlots` 白名单里
 * @param fallback 没有贴图时的填充色（就是改造前那句 `background(...)` 的颜色）
 * @param tint 贴图声明了 `tint: true` 时才生效：只取形状，填这个色
 */
fun Modifier.skinSurface(
    slot: String,
    fallback: Color,
    shape: Shape,
    tint: Color? = null
): Modifier = composed {
    val tile = currentTiles()[slot]
    if (tile == null) {
        background(fallback, shape)
    } else {
        clip(shape).drawBehind { drawSkinTile(tile, tint) }
    }
}

/**
 * 只画贴图，不兜底颜色。
 *
 * 用在「底色已经在别处画好」的地方（Hero 卡自带渐变底、启动条自带插画逻辑），
 * 此时贴图是叠上去的一层皮肤。返回 `Modifier` 本身表示没有贴图，调用方不用分支。
 */
fun Modifier.skinTileOverlay(
    slot: String,
    shape: Shape,
    tint: Color? = null
): Modifier = composed {
    val tile = currentTiles()[slot]
    if (tile == null) Modifier else clip(shape).drawBehind { drawSkinTile(tile, tint) }
}

/** 当前主题有没有给这个槽位配贴图（用于「有图就不画默认装饰」这类判断）。 */
@Composable
fun hasSkinTile(slot: String): Boolean = currentTiles().containsKey(slot)

/** 取当前模式生效的贴图；没有就是 null（组件必须自己兜底色）。 */
@Composable
fun skinTile(slot: String): SkinTile? = currentTiles()[slot]

/**
 * 把一张贴图铺满当前绘制区域；带九宫格边距时四角保持原像素、中间拉伸。
 *
 * 整图拉伸用 `FilterQuality.Medium`：贴图常是 webp 缩过的，双线性放大比默认的近邻
 * 更不容易出锯齿；九宫格的四角是 1:1 贴上去的，画质就是原图。
 */
fun DrawScope.drawSkinTile(tile: SkinTile, tint: Color?) {
    val bmp = tile.bitmap
    val dw = size.width.roundToInt()
    val dh = size.height.roundToInt()
    if (dw <= 0 || dh <= 0) return
    val filter = if (tile.tint && tint != null) ColorFilter.tint(tint, BlendMode.SrcIn) else null

    if (!tile.isNinePatch) {
        drawImage(
            image = bmp,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(dw, dh),
            colorFilter = filter,
            filterQuality = FilterQuality.Medium
        )
        return
    }

    // 切图用源像素、落画布用目标像素：两个尺度必须分开算。
    val src = tile.srcInsets()
    val dst = tile.dstInsets(density)

    // 目标尺寸比「两条边之和」还小：切出来中间那格是负宽度，直接整图拉伸。
    if (dst[0] + dst[2] >= dw || dst[1] + dst[3] >= dh) {
        drawImage(
            image = bmp,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(dw, dh),
            colorFilter = filter,
            filterQuality = FilterQuality.Medium
        )
        return
    }

    // 用整数边界，避免 9 块之间出现 1px 的缝（浮点各自取整一定会漏）。
    val sx = intArrayOf(0, src[0].roundToInt(), bmp.width - src[2].roundToInt(), bmp.width)
    val sy = intArrayOf(0, src[1].roundToInt(), bmp.height - src[3].roundToInt(), bmp.height)
    val dx = intArrayOf(0, dst[0].roundToInt(), dw - dst[2].roundToInt(), dw)
    val dy = intArrayOf(0, dst[1].roundToInt(), dh - dst[3].roundToInt(), dh)

    for (i in 0..2) {
        for (j in 0..2) {
            val sw = sx[i + 1] - sx[i]
            val sh = sy[j + 1] - sy[j]
            val dwi = dx[i + 1] - dx[i]
            val dhi = dy[j + 1] - dy[j]
            if (sw <= 0 || sh <= 0 || dwi <= 0 || dhi <= 0) continue
            drawImage(
                image = bmp,
                srcOffset = IntOffset(sx[i], sy[j]),
                srcSize = IntSize(sw, sh),
                dstOffset = IntOffset(dx[i], dy[j]),
                dstSize = IntSize(dwi, dhi),
                colorFilter = filter,
                filterQuality = FilterQuality.Medium
            )
        }
    }
}


