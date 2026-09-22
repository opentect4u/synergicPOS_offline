package com.example.synergic_pos_offline.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Decoded row thumbnails, kept so a table decodes each one once instead of once per bind.
 *
 * ## The problem this exists for
 *
 * A master table recycles its row VIEWS - that is what RecyclerView is for - but nothing
 * bounds the work of BINDING one, and a product row binds a photo. The image arrives as
 * a JPEG BLOB out of the database, and decoding it costs two `BitmapFactory` passes: one
 * to read its dimensions, one to decode it down to thumbnail size.
 *
 * Bound once that is nothing. Bound on every pass of a row across the screen it is the
 * whole frame budget: scroll a product list up and down and the same fifty photos are
 * decoded again and again, each time from scratch. That is what made the list feel slow -
 * not the number of rows, which the table has paged since it was written.
 *
 * ## Keyed by row, cleared on reload
 *
 * A thumbnail belongs to a row id, so that is the key. It is also a SNAPSHOT: edit a
 * product's photo and the old bitmap would still be cached under the same id, so every
 * full reload of a table clears this - see [clear]. Cheap to refill and never wrong.
 *
 * ## Sized in bytes, not entries
 *
 * A count would be a guess at how big a thumbnail is. These are capped at 120px but an
 * entry is still ~57KB at ARGB_8888, and the cap that matters is how much memory the
 * till can spare - a shop's tablet is not a phone with room to waste.
 */
object RowThumbnails {

    /** The longest edge a row thumbnail is decoded to, in pixels. */
    const val THUMB_PX = 120

    private val cache = object : LruCache<String, Bitmap>(maxBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * An eighth of what the app may allocate.
     *
     * Deliberately modest. This is a convenience, not a store: everything in it can be
     * rebuilt from the BLOB it came from, so it should be the first thing to give up
     * memory when a bill or a report wants it.
     */
    private fun maxBytes(): Int =
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(2L * 1024 * 1024, 16L * 1024 * 1024).toInt()

    /**
     * The thumbnail for [key], decoding [bytes] only if it is not already held.
     *
     * Returns null for a row with no image, and for one whose bytes will not decode - a
     * column holding something that is not an image is a placeholder on screen, not a
     * crash in a list.
     */
    fun bitmap(key: String, bytes: ByteArray?, targetPx: Int = THUMB_PX): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        val id = "$key@$targetPx"
        cache.get(id)?.let { return it }
        val decoded = decodeSampled(bytes, targetPx) ?: return null
        cache.put(id, decoded)
        return decoded
    }

    /** Drops everything held. Called whenever a table reloads its rows. */
    fun clear() = cache.evictAll()

    /**
     * Decodes only as many pixels as are needed.
     *
     * The bounds pass costs no allocation - `inJustDecodeBounds` reads the header alone -
     * and it is what lets the real pass skip most of the file. A 2000px product photo
     * shown at 120px decodes at 1/16 scale, so a sixteenth of the work and of the memory.
     */
    private fun decodeSampled(bytes: ByteArray, targetPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > targetPx || bounds.outHeight / sample > targetPx) {
            sample *= 2
        }
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (_: Exception) {
        null
    }
}
