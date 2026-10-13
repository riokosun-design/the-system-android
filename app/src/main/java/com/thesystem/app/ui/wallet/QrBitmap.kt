package com.thesystem.app.ui.wallet

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** UPI deep-link payload: exact amount + order note baked in — the hunter's
 *  payment app cannot fat-finger the figure the server expects. */
fun upiQrPayload(upiId: String, payeeName: String, amountInr: Double, note: String): String =
    "upi://pay?pa=$upiId&pn=${java.net.URLEncoder.encode(payeeName, "UTF-8")}" +
        "&am=${"%.2f".format(amountInr)}&cu=INR&tn=${java.net.URLEncoder.encode(note, "UTF-8")}"

/** Pure encode — ZXing core only (no Android bitmap deps), unit-testable math. */
fun encodeQr(content: String, sizePx: Int = 480): IntArray? = runCatching {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
    )
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    IntArray(sizePx * sizePx) { i -> if (m[i % sizePx, i / sizePx]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
}.getOrNull()

@Composable
fun QrImage(content: String, modifier: Modifier = Modifier) {
    val bmp: ImageBitmap? = remember(content) {
        encodeQr(content)?.let { pixels ->
            val side = kotlin.math.sqrt(pixels.size.toDouble()).toInt()
            val b = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
            b.setPixels(pixels, 0, side, 0, 0, side, side)
            b.asImageBitmap()
        }
    }
    if (bmp != null) Image(bitmap = bmp, contentDescription = "payment QR", modifier = modifier)
}
