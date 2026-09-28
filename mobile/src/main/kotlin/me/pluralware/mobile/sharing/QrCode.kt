package me.pluralware.mobile.sharing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * [text] as a QR code: dark modules on white, whatever the theme, since
 * scanners want contrast. Drawn straight onto a canvas, no bitmap.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { encode(text) }
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.White)
            .padding(12.dp)
            .semantics { contentDescription = "QR code of the invite link" },
    ) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    // A hair of overlap hides seams between cells when scaled.
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

internal fun encode(text: String): BitMatrix = QRCodeWriter().encode(
    text,
    BarcodeFormat.QR_CODE,
    0,
    0,
    // The padding above is the quiet zone.
    mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
)
