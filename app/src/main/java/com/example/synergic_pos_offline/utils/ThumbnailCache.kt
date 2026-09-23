package com.example.synergic_pos_offline.utils

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Decoded product images, kept so a list decodes each one once instead of once per bind.
 *
 * ## The problem this exists for
 *
 * A list recycles its VIEWS - that is what RecyclerView is for - but nothing bounds the
 * work of BINDING one, and a product row or tile binds a photo. The image arrives as a
 * JPEG BLOB out of the database, and decoding it costs two `BitmapFactory` passes: one
 * to read its dimensions, one to decode it down to size.
 *
 * Bound once that is nothing. Bound on every pass of a row across the screen it is the
 * whole frame budget: scroll a product list up and down and the same fifty photos are
 * decoded again and again, each time from scratch. That is what made these lists feel
 * slow - not the number of rows, which they have paged for as long as they have existed.
 *
 * ## Shared by the masters' table and the restaurant grid
 *
 * Both had the same fault and it is the same fix, so it lives in one place. The grocery
 * sale page does not use this: it decodes its whole catalogue up front across a thread
 * pool and holds the results itself, which suits a screen that is opened once and used
 * all day. This suits the screens that are opened, scrolled and left.
 *
 * ## Keyed by caller, cleared on reload
 *
 * A bitmap belongs to a row or product id, so that is the key - and the target size, so
 * a 120px table thumbnail and a 320px grid tile of the same product do not collide. It
 * is a SNAPSHOT: edit a product's photo and the old bitmap would still be held under the
 * same id, so a full reload clears it - see [clear]. Cheap to refill and never wrong.
 *
 * ## Sized in bytes, not entries
 *
 * A count would be a guess at how big an image is, and these are not all one size: a
 * table thumbnail is ~57KB at ARGB_8888 where a grid tile is ~410KB. What has to be
 * capped is the memory, not the number of them - a shop's tablet is not a phone with
 * room to waste.
 */
object ThumbnailCache {

    /** The longest edge a table row's thumbnail is decoded to, in pixels. */
    const val THUMB_PX = 120

    /** The longest edge a sale-grid tile's photo is decoded to - as [PosBillingFragment]. */
    const val TILE_PX = 320

    private val cache = object : LruCache<String, Bitmap>(maxBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * A quarter of what the app may allocate, between 4 and 48 MB.
     *
     * It used to be an eighth, capped at 16 MB - about twenty-five restaurant tiles,
     * where an eight-across grid shows forty at once. Scrolling it evicted each photo
     * before it came round again, so every tile crossing the screen was decoded afresh:
     * a cache that never hit. Still bounded, and still the first thing to give memory
     * back - everything in it can be rebuilt from the BLOB it came from.
     */
    private fun maxBytes(): Int =
        (Runtime.getRuntime().maxMemory() / 4).coerceIn(4L * 1024 * 1024, 48L * 1024 * 1024).toInt()

    /** Two decoders: enough to keep ahead of a scroll, not so many they starve the UI. */
    private val decoder = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "thumb-decode").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** Decodes in flight, and everyone waiting on each - so one photo is decoded once. */
    private val pending = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

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

    /** The thumbnail for [key] if it is already decoded, without decoding anything. */
    fun cached(key: String, targetPx: Int = THUMB_PX): Bitmap? = cache.get("$key@$targetPx")

    /**
     * The thumbnail for [key], decoded OFF the main thread when it is not held yet.
     *
     * [onReady] runs on the main thread - straight away on a cache hit, or once the
     * decode lands. For a list that binds as it scrolls: decoding a JPEG in the bind
     * costs a frame or more per tile, which is what a scroll feels as a stutter. The
     * caller checks the row is still showing the same product before using the result.
     * Call from the main thread.
     */
    fun load(key: String, bytes: ByteArray?, targetPx: Int, onReady: (Bitmap?) -> Unit) {
        if (bytes == null || bytes.isEmpty()) { onReady(null); return }
        val id = "$key@$targetPx"
        cache.get(id)?.let { onReady(it); return }
        pending[id]?.let { it.add(onReady); return }
        pending[id] = mutableListOf(onReady)
        decoder.execute {
            val decoded = decodeSampled(bytes, targetPx)
            main.post {
                if (decoded != null) cache.put(id, decoded)
                pending.remove(id)?.forEach { it(decoded) }
            }
        }
    }

    /** Drops everything held. Called whenever a list reloads its rows. */
    fun clear() = cache.evictAll()

    /**
     * Decodes only as many pixels as are needed, then scales to [targetPx] exactly.
     *
     * Through [ImageUtils.decodeThumb], which the grocery sale page already decodes its
     * catalogue with - the sampling rule belongs in one place, and a second copy of it
     * here would be a second thing to get subtly wrong. Sampling only halves, so what
     * comes back can be anything up to twice [targetPx] on its long edge - four times
     * the pixels asked for. Scaled the rest of the way here, so the cache holds four
     * times as many photos in the same memory.
     *
     * Wrapped to turn a failure into null: a column holding something that is not an
     * image should be a blank thumbnail, not a list that dies while being scrolled.
     */
    private fun decodeSampled(bytes: ByteArray, targetPx: Int): Bitmap? = runCatching {
        val sampled = ImageUtils.decodeThumb(bytes, targetPx) ?: return@runCatching null
        val longest = maxOf(sampled.width, sampled.height)
        if (longest <= targetPx) return@runCatching sampled
        val scale = targetPx.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            sampled,
            (sampled.width * scale).toInt().coerceAtLeast(1),
            (sampled.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== sampled) sampled.recycle()
        scaled
    }.getOrNull()
}
