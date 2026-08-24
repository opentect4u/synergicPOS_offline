package com.example.synergic_pos_offline.utils

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a scanner gets off the printed code.
 *
 * [UpiQr.bitmap] and [UpiQr.readPayee] both need android.graphics, so the code is
 * encoded and read back here through the same ZXing pipeline the app uses either
 * side of it - same error correction, same quiet zone, same binarizer. That is
 * enough to answer the question these tests exist for: does the amount survive the
 * round trip, so the customer never types it.
 */
class UpiQrTest {

    /** As [UpiQr.bitmap] draws it: whole pixels per module, white ground. */
    private fun roundTrip(content: String, modulePx: Int = 8): String {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1, hints)
        val side = matrix.width * modulePx
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (!matrix.get(x, y)) continue
                for (dy in 0 until modulePx) {
                    val row = (y * modulePx + dy) * side + x * modulePx
                    for (dx in 0 until modulePx) pixels[row + dx] = 0xFF000000.toInt()
                }
            }
        }
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
        return reader.decodeWithState(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        ).text
    }

    @Test
    fun `the payment URI carries the amount, the payee and the currency`() {
        val uri = UpiQr.payUri("shop@okaxis", "Corner Store", 1234.5, "Bill A101")
        assertTrue(uri, uri.startsWith("upi://pay?"))
        assertTrue(uri, uri.contains("pa=shop@okaxis"))
        assertTrue(uri, uri.contains("am=1234.50"))
        assertTrue(uri, uri.contains("cu=INR"))
        assertTrue(uri, uri.contains("pn=Corner%20Store"))
        assertTrue(uri, uri.contains("tn=Bill%20A101"))
    }

    @Test
    fun `the amount is stated to the paise`() {
        assertTrue(UpiQr.payUri("shop@okaxis", "", 40.0).contains("am=40.00"))
        assertTrue(UpiQr.payUri("shop@okaxis", "", 0.5).contains("am=0.50"))
        // Rounded the way every printed figure on the bill is, so the code asks for
        // exactly the total shown above it.
        assertTrue(UpiQr.payUri("shop@okaxis", "", 99.995).contains("am=100.00"))
    }

    @Test
    fun `a payee name that needs escaping does not break the URI`() {
        val uri = UpiQr.payUri("shop@okaxis", "Ram & Co. Traders", 10.0)
        assertTrue(uri, uri.contains("pn=Ram%20%26%20Co.%20Traders"))
        // The escaped ampersand must not read as another parameter: pa, pn, am, cu.
        assertEquals(4, uri.removePrefix("upi://pay?").split("&").size)
    }

    @Test
    fun `no fields are sent empty`() {
        val uri = UpiQr.payUri("shop@okaxis", "", 10.0, note = "  ")
        assertFalse(uri, uri.contains("pn="))
        assertFalse(uri, uri.contains("tn="))
    }

    @Test
    fun `scanning the printed code gives back the URI with the amount in it`() {
        val uri = UpiQr.payUri("corner.store@okaxis", "Corner Store", 2450.75, "Bill INV-2091")
        assertEquals(uri, roundTrip(uri))
    }

    @Test
    fun `the longest realistic code still reads`() {
        val uri = UpiQr.payUri(
            "a".repeat(50) + "@okhdfcbank", "A Very Long Registered Trading Name Pvt Ltd",
            999999.99, "Bill PREFIX-000000123"
        )
        assertEquals(uri, roundTrip(uri))
    }

    @Test
    fun `a UPI ID is recognised by its shape`() {
        assertTrue(UpiQr.isValidVpa("9876543210@ybl"))
        assertTrue(UpiQr.isValidVpa("corner.store-1_2@okaxis"))
        assertTrue(UpiQr.isValidVpa("  shop@okaxis  "))
        assertFalse(UpiQr.isValidVpa("shop"))
        assertFalse(UpiQr.isValidVpa("shop@"))
        assertFalse(UpiQr.isValidVpa("@okaxis"))
        assertFalse(UpiQr.isValidVpa("shop okaxis"))
        assertFalse(UpiQr.isValidVpa(null))
        assertFalse(UpiQr.isValidVpa(""))
    }
}
