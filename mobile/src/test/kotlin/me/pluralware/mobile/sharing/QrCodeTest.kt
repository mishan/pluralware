package me.pluralware.mobile.sharing

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import me.pluralware.shared.notify.Invite
import me.pluralware.shared.notify.Vapid
import org.junit.Assert.assertEquals
import org.junit.Test

class QrCodeTest {

    @Test
    fun `an invite link's QR code scans back to the same link`() {
        val link = Invite(system = "A system with a longish name", vapid = Vapid.generate().publicKey).link()
        val matrix = encode(link)

        // Render with a quiet zone, the way the screen draws it, and scan it.
        val scale = 4
        val quiet = 4
        val side = (matrix.width + 2 * quiet) * scale
        val pixels = IntArray(side * side) { i ->
            val x = i % side / scale - quiet
            val y = i / side / scale - quiet
            val dark = x in 0 until matrix.width && y in 0 until matrix.height && matrix[x, y]
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))

        assertEquals(link, QRCodeReader().decode(bitmap).text)
    }
}
