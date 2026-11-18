package com.mineserve.mobile.ui.theme

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView

/**
 * 全局动效令牌（2026-11 UI 动效层）。
 *
 * 取值参照 Material 3 Motion 规范的「快速离开 / 缓慢进入」组合，但整体压短一档：
 * 本应用是工具型界面，动效只需要「交代变化发生」而不需要抢戏；低速设备上更短的
 * 动画意味着更少的双页同屏时间（页面切换动画期间新旧两页同时在组合里）。
 *
 * 用法约定：
 * - 微交互（按压、开关、Tab 高亮）：[DurationPress] 档；
 * - 内容切换（Tab 内容、折叠展开）：[DurationMedium] 档；
 * - 页面级切换（导航入栈出栈）：[DurationPage] 档。
 * 同一语义只用一档，避免相邻交互一个 120ms 一个 400ms 的节奏错乱。
 */
object McMotion {
    /** 按压反馈、小控件状态变化的时长（ms）。 */
    const val DurationPress = 120

    /** Tab 内容切换、折叠展开等中观动效（ms）。 */
    const val DurationMedium = 220

    /** 页面级导航动效（ms）。出屏侧更短（[DurationPageExit]）保证跟手感。 */
    const val DurationPage = 280
    const val DurationPageExit = 200

    /** 缓入缓出：内容切换、页面切换的主缓动。 */
    val StandardEasing = FastOutSlowInEasing

    /** 快出缓入：按压回弹等「先响应后落定」的微交互。 */
    val DecelerateEasing = LinearOutSlowInEasing
}

/**
 * 按压缩放反馈：按下时整体缩到 [pressedScale]，松手弹回。
 *
 * 为什么单独用 pointerInput 探测按压而不是共享 interactionSource：
 * - 调用点都是既有的 `Box + clickable` 结构，改成「自建 interactionSource + 传参」
 *   需要动每个调用点的 clickable 签名，侵入太大；
 * - 这里只**观察**手势不消费事件（requireUnconsumed = false 且不 consume），
 *   与 clickable 的 ripple 互不干扰，滚动手势也照常由父层接管。
 *
 * 缩放画在 graphicsLayer 里：不触发布局、不引起兄弟元素重排，低端机开销可忽略。
 * 配合 [McMotion.DurationPress] 的 spring，按下「即陷」、松手「轻弹」，给纯色块
 * 按钮补上默认 ripple 之外的实体感。
 *
 * 用在「整块即按钮」的高频控件上（分段胶囊、行内动作、悬浮返回键），
 * 不要铺到列表行等大面积内容上——大面积缩放在滚动中容易诱发晕动不适。
 */
fun Modifier.pressScale(
    pressedScale: Float = 0.96f,
    enabled: Boolean = true
): Modifier = composed {
    if (!enabled) return@composed this
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 900f
        ),
        label = "press-scale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
}

/**
 * 轻触觉反馈：关键操作（导航切换、确认删除、开关切换）给一次系统级轻震。
 *
 * 用 VIRTUAL_KEY（各 ROM 都映射为「轻点」强度），不依赖 Compose 版本的
 * HapticFeedbackType 扩展；无振动器的设备自动静默，无需调用方判断。
 * 面向未来的 API 已由 View 层自动处理（API 30+ 的语义不变），不做版本分支。
 */
@androidx.compose.runtime.Composable
fun rememberTickHaptic(): () -> Unit {
    val view = LocalView.current
    return remember {
        {
            view.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY
            )
        }
    }
}
