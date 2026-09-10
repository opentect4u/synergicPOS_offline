package com.example.synergic_pos_offline.utils

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * How a product's picture is turned into the bytes that go in the database.
 *
 * `md_products.product_image` is a BLOB - the image itself lives in the row, not on
 * disk beside it - so a catalogue is one file to back up and one file to restore, and
 * a product cannot end up pointing at a picture that has been moved or deleted. The
 * cost of that is that every byte is carried by every backup, which is why nothing is
 * stored at the size it arrived.
 *
 * ONE DEFINITION, shared. The Products screen compresses a picture the operator picks
 * for a single product; [ProductImageImporter] compresses a folder of them at once.
 * Two copies of these numbers would mean a product's picture looked different
 * depending on which way it was put there, and would drift the first time either was
 * tuned.
 */
object ProductImages {

    /** The longest edge any stored picture is scaled down to. */
    const val MAX_EDGE = 800

    /** JPEG quality. 80 is where the artefacts stop being visible at [MAX_EDGE]. */
    const val QUALITY = 80

    /**
     * [source] scaled to fit [MAX_EDGE] and encoded as JPEG.
     *
     * Only ever scaled DOWN - an image already smaller than the limit is left at its
     * own size rather than blown up to meet it, which would add bytes and no detail.
     */
    fun compress(source: Bitmap): ByteArray {
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(source.width, source.height))
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source, (source.width * scale).toInt(), (source.height * scale).toInt(), true
            )
        } else {
            source
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
    }
}
