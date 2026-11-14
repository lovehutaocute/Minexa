package com.mineserve.mobile.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import com.mineserve.mobile.data.skin.SkinResolved

/**
 * 当前生效的主题（已按深浅色解析完成）。
 *
 * 这是**新代码**读取主题的唯一入口：`LocalSkin.current`。用 static 版本 ——
 * 它只在换主题/切深浅时变一次，随后整棵树重组一次，正好是换肤需要的语义。
 *
 * 默认值故意用 `error()` 而不是给一份假配色：任何漏包主题的页面会在第一次渲染时
 * 立刻崩在具体行号上，比「颜色悄悄变成 M3 淡紫」好排查一百倍。
 */
val LocalSkin = staticCompositionLocalOf<SkinResolved> {
    error("LocalSkin 只能在 MineServeMobileTheme 内部读取")
}

/**
 * 全局配色桥。
 *
 * 为什么需要它：本工程有 700+ 处直接引用 `Indigo` / `Coral` / `Mint` 这类顶层常量
 * （`Color.kt`）。把它们逐个改成 `MaterialTheme.colorScheme.*` 会产生几百处 diff，
 * 且很容易把装饰色当语义色改错。
 *
 * 折中方案：`Color.kt` 里的常量保留原名，改成读 [current]；[MineServeMobileTheme]
 * 在组合子内容之前安装一次。这样调用点零改动、换肤全量生效，而且不会有
 * 「只变了一半」的残留色。代价是它是个全局可变状态，所以：
 *
 * - **只允许** [MineServeMobileTheme] 调用 [install]，其它地方调都算 bug；
 * - 安装发生在子内容组合之前，同一帧内读到的就是新配色，不存在滞后一帧的问题；
 * - 需要在 `remember`/`LaunchedEffect` 之外读色的旧代码（如 `drawBehind`）也能拿到当前值。
 */
object SkinPalette {

    @Volatile
    private var resolved: SkinResolved = SkinDefaults.baseline()

    val current: SkinResolved get() = resolved

    fun install(value: SkinResolved) {
        resolved = value
    }
}
