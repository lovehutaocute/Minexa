package com.mineserve.mobile.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mineserve.mobile.data.skin.SkinRuntime

/**
 * 全局主题入口：把「当前主题包」解析成 Material3 配色 + 形状，并下发给整棵树。
 *
 * 三件事必须按顺序发生：
 * 1. [SkinRuntime.resolve] 取到本次深浅色下的令牌（图片也在这里解码，只解一次）；
 * 2. [SkinPalette.install] 把结果装进全局桥，供 `Color.kt` 里 700+ 处旧常量读取；
 * 3. `LocalSkin` + `MaterialTheme` 下发，子内容才开始组合。
 *
 * `remember` 的三个键都不能少：少 `runtime` 会导致切主题不刷新，少 `widthPx` 会导致
 * 转屏后图片糊，少 `darkTheme` 会导致深浅色不跟随。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MineServeMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    skin: SkinRuntime? = null,
    /**
     * 毛玻璃总开关（由 `SkinState.glassEnabled` 算出：手动设置优先，否则跟随主题包）。
     *
     * 放在树顶下发而不是各页面各自读仓库：卡片、底栏、按钮全是分散的调用点，
     * 让它们都去订阅 DataStore 只会出现「一半开着一半没开」的中间态。
     */
    glassEnabled: Boolean = false,
    content: @Composable () -> Unit
) {
    val runtime = skin ?: remember { SkinRuntime.builtin() }
    val widthPx = LocalContext.current.resources.displayMetrics.widthPixels
    val resolved = remember(runtime, darkTheme, widthPx) {
        // 兜底实例（首启 bootstrap 未完成 / 主题目录被删）不能走 resolve()：
        // 它没有任何令牌，会落到 M3 默认的淡紫。用工程自己的默认配色，冷启动才不会闪一下。
        if (runtime.isFallback) SkinDefaults.baseline(darkTheme)
        else runtime.resolve(darkTheme, widthPx)
    }

    // 在子内容组合之前安装：同一帧内所有 getter 读到的都是新配色。
    SkinPalette.install(resolved)

    val shapes = remember(resolved.shape) {
        Shapes(
            extraSmall = RoundedCornerShape(resolved.shape.controlRadius.dp),
            small = RoundedCornerShape(resolved.shape.controlRadius.dp),
            medium = RoundedCornerShape(resolved.shape.cardRadius.dp),
            large = RoundedCornerShape(resolved.shape.dialogRadius.dp),
            extraLarge = RoundedCornerShape(resolved.shape.dialogRadius.dp)
        )
    }

    CompositionLocalProvider(LocalSkin provides resolved) {
        MaterialTheme(
            colorScheme = resolved.scheme,
            typography = McTypography,
            shapes = shapes
        ) {
            // 终端调色板不在这里下发：终端的深浅色是终端页自己的开关（默认深色），
            // 与 App 主题无关，由 TerminalScreen 在页面内 provide。
            CompositionLocalProvider(
                LocalOverscrollConfiguration provides null,
                LocalGlassEnabled provides glassEnabled
            ) {
                content()
            }
        }
    }
}
