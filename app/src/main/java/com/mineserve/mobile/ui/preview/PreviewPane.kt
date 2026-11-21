package com.mineserve.mobile.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mineserve.mobile.ui.theme.MineServeMobileTheme

/**
 * `@Preview` 的统一外壳：套主题 + 定宽 + 铺背景。
 *
 * 为什么必须套 [MineServeMobileTheme]：工程里 700+ 处颜色读的是
 * `SkinPalette` 装的全局桥，不装主题拿到的是一堆未初始化常量，预览会全黑或直接崩。
 * 主题内部对 `dir == null` 的 builtin 走 `SkinDefaults.baseline()` 短路，
 * 不解码任何图片资源，所以预览里起得来、也不依赖 assets。
 *
 * 深浅色由 `isSystemInDarkTheme()` 决定，`@Preview(uiMode = UI_MODE_NIGHT_YES)`
 * 会自动让它返回 true，因此一个组件只写一个预览函数、打两个 `@Preview` 注解即可。
 *
 * 宽度固定 360dp（主流手机的逻辑宽度），这样 `fillMaxWidth()` 的组件在预览里
 * 才是真实比例——用 wrapContent 看间距会失真，dp 收敛时看不出问题。
 */
@Composable
fun PreviewPane(
    width: Dp = 360.dp,
    content: @Composable () -> Unit
) {
    MineServeMobileTheme(darkTheme = isSystemInDarkTheme()) {
        Box(
            modifier = Modifier
                .width(width)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            content()
        }
    }
}
