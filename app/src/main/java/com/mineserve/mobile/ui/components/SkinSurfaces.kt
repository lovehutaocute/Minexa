package com.mineserve.mobile.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.window.DialogProperties
import com.mineserve.mobile.data.skin.SkinTileSlots
import com.mineserve.mobile.ui.theme.LocalSkin

/**
 * 需要「整块换皮」的 Material3 组件的接线层（`.mctheme` v2）。
 *
 * 为什么单独一个文件：`Button` / `AlertDialog` 这类组件的底色是它们自己画的
 * （`Surface(containerColor)` 盖在传入的 modifier 之上），所以贴图不能只靠
 * `Modifier.drawBehind` —— 必须**同时**把容器色让出来。这些「成对修改」集中在这里，
 * 调用点只换一个函数名，不用每处各写一遍条件分支。
 *
 * 不用刻意隔离：这里没有任何业务逻辑，只有「有图 / 没图」两条路。
 */

/**
 * 主按钮的配色：主题包给了 `primaryButton` 贴图时把容器色让成透明，让贴图透出来。
 *
 * [base] 就是改造前写死的那个 `containerColor`（靛蓝、薄荷绿……）。没有贴图时
 * 返回值与 `ButtonDefaults.buttonColors(containerColor = base)` 完全等价 ——
 * 连 `contentColor` 都还在，不会因为「容器变透明」把按钮文字变成默认色。
 */
@Composable
fun skinButtonColors(slot: String, base: Color): ButtonColors {
    val hasTile = skinTile(slot) != null
    // 文字色直接抄「原来的容器色」算出来的那一份，而不是硬编码白色：
    // 主题包换了 primary 之后 onPrimary 可能是深色，硬编码白字会看不清。
    val originalContent = ButtonDefaults.buttonColors(containerColor = base).contentColor
    return ButtonDefaults.buttonColors(
        containerColor = if (hasTile) Color.Transparent else base,
        contentColor = originalContent
    )
}

/**
 * 只算「容器色」的版本，给多行 `buttonColors(containerColor = …, disabledContainerColor = …)`
 * 这种没法整体替换的写法用。
 */
@Composable
fun skinContainerColor(slot: String, base: Color): Color =
    if (skinTile(slot) != null) Color.Transparent else base

/**
 * 所有弹窗的统一入口（替代直接用 Material3 的 `AlertDialog`）。
 *
 * 参数与 `AlertDialog` 一一对应、意义相同，只在「主题包给了 `dialog` 贴图」时多做两件事：
 * 容器色让成透明 + 用贴图填底（贴图按 `shape` 裁切）。
 *
 * 为什么弹窗必须走这里：`AlertDialog` 的 `modifier` 挂在它的 Surface 上，而 Surface
 * 自己的底色是在 modifier **之后**画的，直接 `drawBehind` 会被盖掉。把 `containerColor`
 * 让出来是唯一不重写整个弹窗布局的做法。
 *
 * 注意两个分支里调的必须是 Material3 的 [AlertDialog]：早先这两处误写成本函数名，
 * 编译期完全合法（自己调自己），运行时一进任意弹窗就 `StackOverflowError`。
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    properties: DialogProperties = DialogProperties()
) {
    if (skinTile(SkinTileSlots.DIALOG) != null) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier.skinTileOverlay(SkinTileSlots.DIALOG, shape),
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = Color.Transparent,
            properties = properties
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = containerColor,
            properties = properties
        )
    }
}
