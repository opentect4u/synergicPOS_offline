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

    /**
     * The picture at [uri] - a camera capture or a gallery pick - ready to store, or
     * null if it cannot be read.
     *
     * ## Never decoded at the size it was taken
     *
     * A tablet camera writes 8 to 50 megapixels, which is 32 to 200 MB once decoded,
     * and the picture is only going to be kept at [MAX_EDGE]. Decoding it whole first
     * ran a 3-4 GB till out of memory - an Error, not an Exception, so nothing caught
     * it and Take Photo closed the app. The size is read first, and the picture is
     * decoded already shrunk by a power of two to the nearest size above [MAX_EDGE];
     * [compress] takes it the rest of the way.
     *
     * ## Turned the right way up
     *
     * A camera saves the sensor's own orientation and writes in the file which way to
     * turn it. Decoding ignores that, so a photo taken holding the tablet upright was
     * stored on its side. The turn is applied here.
     */
    fun fromUri(context: android.content.Context, uri: android.net.Uri, maxEdge: Int = MAX_EDGE): ByteArray? =
        decodeUpright(context, uri, maxEdge)?.let { bitmap ->
            try { compress(bitmap) } finally { bitmap.recycle() }
        }

    /**
     * [uri] decoded no bigger than about twice [maxEdge] on its longest side and
     * turned upright - see [fromUri]. Null if it cannot be read or does not fit in
     * memory even so; never throws.
     */
    fun decodeUpright(context: android.content.Context, uri: android.net.Uri, maxEdge: Int): Bitmap? = try {
        val resolver = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val (w, h) = bounds.outWidth to bounds.outHeight
        if (w <= 0 || h <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= maxEdge) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
            decoded?.let { rotateUpright(it, orientationOf(context, uri)) }
        }
    } catch (e: OutOfMemoryError) {
        android.util.Log.w("ProductImages", "Picture too large to load", e)
        null
    } catch (e: Exception) {
        android.util.Log.w("ProductImages", "Picture could not be read", e)
        null
    }

    /** The EXIF orientation the camera wrote, or "normal" if it wrote none. */
    private fun orientationOf(context: android.content.Context, uri: android.net.Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            android.media.ExifInterface(it).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrNull() ?: android.media.ExifInterface.ORIENTATION_NORMAL

    private fun rotateUpright(source: Bitmap, orientation: Int): Bitmap {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return source
        }
        val turned = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (turned !== source) source.recycle()
        return turned
    }
}
