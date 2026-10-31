package com.mineserve.mobile.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把文本编码成二维码位图。
 *
 * **永远是黑码白底**，不跟随主题：二维码的对比度是物理约束，不是审美偏好 ——
 * 深色主题下画成浅码深底，多数手机相机和扫码 App 会直接识别失败。
 * 所以调用方要把它放在白色底板上（见 [QrCodeImage]）。
 *
 * 纠错级别取 **M（15%）**：玩家多半是拿另一台手机对着屏幕拍，
 * 屏幕反光/摩尔纹会吃掉一部分模块。M 在这一档里编码密度和容错最均衡，
 * 继续升到 Q/H 会让码变密、反而更难拍。
 *
 * @param sizePx 编码出的位图边长（像素）。传 0 或负数返回 null。
 */
fun encodeQrBitmap(content: String, sizePx: Int = 720): Bitmap? {
    if (content.isBlank() || sizePx <= 0) return null
    return runCatching {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                // 静默区（白边）：不画的话相机找不到码的边界。2 模块是 zxing 推荐下限。
                EncodeHintType.MARGIN to 2
            )
        )
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        val black = android.graphics.Color.BLACK
        val white = android.graphics.Color.WHITE
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix[x, y]) black else white
            }
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }.getOrNull()
}

/**
 * 显示一张二维码。
 *
 * 编码在后台线程做：内容短时也就几毫秒，但一旦用户把网络密钥粘贴得很长，
 * 主线程上编码会明显掉帧（构建引导页的那一下最容易被感知到）。
 *
 * [content] 为空或编码失败时整块不占位 —— 与其画一个空白方块让人以为是加载中，
 * 不如让清晰的文本说明顶上。
 */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    padding: Dp = 12.dp
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, content) {
        value = withContext(Dispatchers.Default) { encodeQrBitmap(content) }
    }
    val bmp = bitmap ?: return

    // 白色底板 + 圆角：深色主题下二维码仍然可扫
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .size(size)
            .then(Modifier),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            // 放大时用 NearestBicubic 之外的滤波会让模块边缘发灰，降低可扫描性
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .size(size - padding * 2)
        )
    }
}
