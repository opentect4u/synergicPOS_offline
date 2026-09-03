package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URLEncoder
import java.util.Locale

/**
 * The UPI payment QR printed on a bill.
 *
 * The point of building the code here rather than printing the shopkeeper's saved
 * QR image is the amount. A QR downloaded from a payment app is *static*: it names
 * the payee and nothing else, so whoever scans it has to key the figure in - and a
 * mistyped figure is the till's problem to sort out afterwards. A code built per
 * bill carries `am`, so the payment app opens with the bill's total already filled
 * in and the customer only confirms it.
 *
 * That is why Bill Settings keeps the *UPI ID* rather than an uploaded picture: the
 * picture cannot carry an amount that nobody knew when it was made. Uploading a QR
 * is still the easiest way to get the ID onto the till, so [readPayee] reads one
 * and pulls the ID out of it; the printed code is generated from there.
 *
 * All of it is local - the encoder and the reader are pure Java, and a UPI URI
 * needs no network to build - so it works on a till that has never been online.
 */
object UpiQr {

    /**
     * A UPI address as it is written: an account handle, an `@`, and the bank or app
     * that resolves it - `9876543210@ybl`, `store.name@okaxis`.
     *
     * Deliberately loose about what the handle may hold, because the rules differ
     * per provider and a till that rejects a real ID is worse than one that lets a
     * wrong one through: a wrong ID fails visibly at the first scan, whereas a
     * rejected one leaves the shopkeeper no way to enter theirs at all.
     */
    private val VPA = Regex("^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9.-]{1,64}$")

    /** A payee as a UPI QR names them. [name] is blank when the code carried none. */
    data class Payee(val vpa: String, val name: String)

    /** True when [vpa] is shaped like a UPI ID (`name@bank`). */
    fun isValidVpa(vpa: String?): Boolean = !vpa.isNullOrBlank() && VPA.matches(vpa.trim())

    /**
     * The `upi://pay` URI a payment app opens when the printed code is scanned.
     *
     * [amount] is the whole point of generating a code per bill - it lands in the
     * app's amount field, so nothing is keyed in at the counter. It is stated to the
     * paise because UPI reads `am` as a decimal figure and an app rejects a
     * malformed one outright.
     *
     * No transaction reference (`tr`) is sent. It would be handy for reconciliation,
     * but the apps that honour one treat a repeated reference as a payment already
     * made - and a bill can be reprinted - so a reprint of an unpaid bill would come
     * back as "already completed". The bill number rides in the note instead, where
     * only a person ever reads it.
     */
    fun payUri(vpa: String, payeeName: String, amount: Double, note: String? = null): String {
        val params = buildList {
            add("pa" to vpa.trim())
            payeeName.trim().take(PAYEE_NAME_MAX).takeIf { it.isNotEmpty() }?.let { add("pn" to it) }
            add("am" to String.format(Locale.US, "%.2f", BillRounding.toPaise(amount)))
            add("cu" to "INR")
            note?.trim()?.take(NOTE_MAX)?.takeIf { it.isNotEmpty() }?.let { add("tn" to it) }
        }
        return params.joinToString("&", prefix = "upi://pay?") { (k, v) -> k + "=" + enc(v) }
    }

    /**
     * Encodes [content] as a square QR bitmap [sizePx] on a side, or null if it
     * could not be encoded.
     *
     * Drawn from the module grid at whole pixels per module: a code whose modules
     * land on half a pixel comes off a thermal head as a grey smear no scanner
     * reads. The result is therefore the largest whole-module square that fits
     * [sizePx], centred on a white ground of exactly [sizePx], so the caller can put
     * it in a fixed slot without rescaling it again.
     */
    fun bitmap(content: String, sizePx: Int): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ECC,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES
        )
        // Asked for at its natural size and scaled here, rather than encoded straight
        // to sizePx: ZXing would pick the scale itself and then pad the leftover with
        // white on two sides instead of centring the code in the square.
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1, hints)
        val modules = matrix.width
        if (modules <= 0) return null

        val scale = (sizePx / modules).coerceAtLeast(1)
        val drawn = modules * scale
        val side = maxOf(sizePx, drawn)
        val offset = (side - drawn) / 2

        val pixels = IntArray(side * side) { Color.WHITE }
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                if (!matrix.get(x, y)) continue
                val top = offset + y * scale
                val left = offset + x * scale
                for (dy in 0 until scale) {
                    val row = (top + dy) * side + left
                    for (dx in 0 until scale) pixels[row + dx] = Color.BLACK
                }
            }
        }
        Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }.getOrElse {
        android.util.Log.e(TAG, "Could not encode UPI QR", it)
        null
    }

    /**
     * Reads the payee out of a QR image the shopkeeper picked - the code their own
     * payment app gave them, screenshotted or photographed.
     *
     * Returns null when the image holds no QR, or holds one that is not a UPI
     * payment code: a parcel tracking code or a WiFi code decodes perfectly well and
     * would otherwise be saved as a payment address nobody can pay.
     */
    fun readPayee(context: Context, uri: Uri): Payee? = decode(context, uri)?.let { payeeOf(it) }

    /** The payee named by a scanned [text], or null when it is not a UPI pay URI. */
    fun payeeOf(text: String): Payee? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("upi:", ignoreCase = true)) return null
        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val vpa = runCatching { parsed.getQueryParameter("pa") }.getOrNull()?.trim().orEmpty()
        if (!isValidVpa(vpa)) return null
        val name = runCatching { parsed.getQueryParameter("pn") }.getOrNull()?.trim().orEmpty()
        return Payee(vpa, name)
    }

    /** The text encoded in the QR in [uri]'s image, or null when there is none. */
    private fun decode(context: Context, uri: Uri): String? {
        val bitmap = ImageUtils.uriToBitmap(context, uri)?.let { scaleForRead(it) } ?: return null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    // A photographed code is lit unevenly and often printed small; the
                    // slower pass is worth it when the alternative is telling the
                    // shopkeeper their own QR cannot be read.
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
        // The second pass reads the inverted image: a code shown white-on-dark -
        // which is how several payment apps hand one out - is invisible otherwise.
        return listOf(source, source.invert()).firstNotNullOfOrNull { candidate ->
            runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(candidate))).text }
                .getOrNull()
        }
    }

    /** [src] shrunk to [READ_MAX_DIM] on its longest edge, or [src] if already smaller. */
    private fun scaleForRead(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= READ_MAX_DIM) return src
        val ratio = READ_MAX_DIM.toDouble() / longest
        return Bitmap.createScaledBitmap(
            src, (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1), true
        )
    }

    /**
     * Percent-encodes a parameter value, leaving `@` as it is.
     *
     * A query may legally hold a bare `@`, and every UPI QR in the wild writes the
     * address that way. `%40` is equally legal and the apps that read the spec
     * properly accept it - but not all of them do, and a payment address is not the
     * place to be right at the customer's expense.
     *
     * The space is sent as `%20` rather than `+`, which is a form-encoding
     * convention rather than a URI one and reaches the payee name as a literal plus.
     */
    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%40", "@")

    /** Error correction level of the printed code. */
    private val ECC = ErrorCorrectionLevel.M

    /**
     * Quiet zone around the code, in modules.
     *
     * The spec asks for four. On an 80mm roll four modules of white is several
     * millimetres of paper each side and shrinks the code itself, and the slip is
     * printed on white with clear space around it anyway - two is what scans
     * reliably here without spending the width.
     */
    private const val QUIET_ZONE_MODULES = 2

    /** Longest edge an uploaded image is read at - enough detail to decode, small
     *  enough that a 12MP photo of a shop counter is not held whole to do it. */
    private const val READ_MAX_DIM = 1024

    /** What UPI itself caps the payee name at. */
    private const val PAYEE_NAME_MAX = 50

    /** Keeps the note - and with it the whole code - short enough to print large. */
    private const val NOTE_MAX = 30

    private const val TAG = "UpiQr"
}
