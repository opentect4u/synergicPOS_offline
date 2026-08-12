package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.ByteArrayOutputStream

/**
 * Shared helpers for turning picked/captured images into compact JPEG blobs and
 * back into list thumbnails. Used by any screen that stores an image in the DB.
 */
object ImageUtils {

    const val STORE_MAX_DIM = 640    // longest edge of a stored image, px
    const val STORE_QUALITY = 85     // JPEG compression quality
    const val THUMB_DIM = 96         // decoded thumbnail edge for lists, px

    /** Decodes [uri], downscales it to [maxDim], and returns JPEG bytes (or null). */
    fun uriToJpegBytes(
        context: Context, uri: Uri,
        maxDim: Int = STORE_MAX_DIM, quality: Int = STORE_QUALITY
    ): ByteArray? {
        val bitmap = runCatching { decodeBitmap(context, uri) }.getOrNull() ?: return null
        val scaled = scaleDown(bitmap, maxDim)
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }

    /** Decodes [uri] into a full bitmap (or null) - for the crop screen. */
    fun uriToBitmap(context: Context, uri: Uri): Bitmap? =
        runCatching { decodeBitmap(context, uri) }.getOrNull()

    /**
     * Flattens [bitmap] onto a white background (so a logo with transparency prints on
     * white, not black, on the bill), downscales it to [maxDim] longest edge, and
     * returns JPEG bytes - the standard stored size every logo lands at.
     */
    fun bitmapToJpegBytes(
        bitmap: Bitmap, maxDim: Int = STORE_MAX_DIM, quality: Int = STORE_QUALITY
    ): ByteArray {
        val scaled = scaleDown(bitmap, maxDim)
        val flat = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(flat).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        return ByteArrayOutputStream().use { out ->
            flat.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }

    /** Decodes stored JPEG [bytes] into a small bitmap for a list thumbnail. */
    fun decodeThumb(bytes: ByteArray, targetPx: Int = THUMB_DIM): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= targetPx) {
            sample *= 2
            longest /= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun decodeBitmap(context: Context, uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.contentResolver, uri)
            ) { decoder, _, _ ->
                decoder.isMutableRequired = false
                // Force a software bitmap: a hardware bitmap cannot be cropped/scaled or
                // drawn onto a software Canvas (as the crop + white-flatten step does),
                // which throws "Software rendering doesn't support hardware bitmaps".
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val ratio = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true
        )
    }
}
