package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.example.synergic_pos_offline.database.DatabaseHelper
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
 * ## Shared by the masters' table and both sale grids
 *
 * Every list that shows product photos had the same fault and it is the same fix, so it
 * lives in one place.
 *
 * ## Sale grids fetch each photo by id, not with the catalogue
 *
 * The sale screens used to read every product's image BLOB with the catalogue - and the
 * grocery then decoded all of them up front. At a few hundred products that was merely
 * slow. At 5,000 products carrying a photo each it was ~700 MB of JPEG bytes read into
 * the heap before the first tile could be drawn, plus a bitmap per product on top: the
 * app was killed for memory, or sat collecting garbage until Android called it not
 * responding. So the catalogue now carries only a photo STAMP - see [productStamp] - and
 * [loadProduct] reads one product's bytes when its tile is actually on screen. Memory is
 * what the cache holds, however large the catalogue grows.
 *
 * ## A fling does not queue up the whole catalogue
 *
 * Flinging a 5,000-product grid binds hundreds of tiles for a frame each. Every one asks
 * for its photo, and decoding them in the order they were asked for would spend the next
 * several seconds on tiles long gone while the ones the operator stopped on waited at the
 * back of the queue. So the queue is LAST-IN-FIRST-OUT - the tiles most recently bound,
 * which are the ones still showing, are decoded first - and a request whose tile has
 * since been rebound to another product is dropped unread (see the `wanted` check).
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

    /** A queue that hands out the most recently added task first - see the class notes. */
    private class LifoQueue : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }

    /**
     * Two decoders: enough to keep ahead of a scroll, not so many they starve the UI.
     * Newest request first - see the class notes on a fling.
     */
    private val decoder = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, LifoQueue()
    ) { r -> Thread(r, "thumb-decode").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Someone waiting on a decode. [wanted] says whether they still are - a tile rebound
     * to another product is not - and is asked on the decoder thread, so it must only
     * read a field, never touch a view.
     */
    private class Waiter(val wanted: () -> Boolean, val onReady: (Bitmap?) -> Unit)

    /**
     * Decodes in flight, and everyone waiting on each - so one photo is decoded once.
     * Guarded by itself: the decoder threads read it to decide whether to skip.
     */
    private val pending = HashMap<String, MutableList<Waiter>>()

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
        enqueue("$key@$targetPx", targetPx, { true }, { bytes }, onReady)
    }

    // ---- Product photos, fetched by id -------------------------------------

    /**
     * The fingerprint the catalogue carries in place of a product's photo bytes - its
     * size and last-modified time - or "" for a product with no photo. A changed photo
     * is a new stamp and so a new cache key, which is what lets the cache survive a
     * catalogue reload without ever showing a stale picture.
     *
     * The catalogue query selects `length(product_image)` and `modified_at` and passes
     * them here; neither reads the BLOB itself.
     */
    fun productStamp(imageLength: Long, modifiedAt: String?): String =
        if (imageLength <= 0L) "" else "$imageLength:${modifiedAt.orEmpty()}"

    private fun productKey(productId: Long, stamp: String) = "product:$productId:$stamp"

    /**
     * A product's photo, read from the database and decoded OFF the main thread, for a
     * sale-grid tile. [stamp] is from [productStamp]; blank means no photo, and
     * [onReady] gets null straight away.
     *
     * [wanted] is asked just before the read: false - the tile has scrolled away and
     * been rebound to something else - and the read is skipped. [onReady] runs on the
     * main thread; the caller still checks the tile shows the same product.
     */
    fun loadProduct(
        context: Context, productId: Long, stamp: String, targetPx: Int,
        wanted: () -> Boolean = { true }, onReady: (Bitmap?) -> Unit
    ) {
        if (stamp.isEmpty()) { onReady(null); return }
        val app = context.applicationContext
        enqueue("${productKey(productId, stamp)}@$targetPx", targetPx, wanted,
            { productImageBytes(app, productId) }, onReady)
    }

    /** The product's photo if it is already decoded at [targetPx], without any work. */
    fun cachedProduct(productId: Long, stamp: String, targetPx: Int): Bitmap? =
        if (stamp.isEmpty()) null else cache.get("${productKey(productId, stamp)}@$targetPx")

    /**
     * The product's photo, read and decoded right here if it is not held yet.
     *
     * For the one place a single photo is wanted immediately - the product popup a tap
     * opens. One BLOB and one decode is a few tens of milliseconds, where the old path
     * read every photo in the shop.
     */
    fun productBitmap(context: Context, productId: Long, stamp: String, targetPx: Int): Bitmap? {
        if (stamp.isEmpty()) return null
        return bitmap(productKey(productId, stamp), productImageBytes(context, productId), targetPx)
    }

    /**
     * One product's photo bytes. Null for none, and for one that cannot be read - a
     * BLOB too large for a cursor window is a blank tile, not a crashed sale screen.
     */
    private fun productImageBytes(context: Context, productId: Long): ByteArray? = runCatching {
        DatabaseHelper.getInstance(context).readableDatabase.rawQuery(
            "SELECT product_image FROM md_products WHERE id = ?", arrayOf(productId.toString())
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    // ---- The queue ------------------------------------------------------------

    /** Call from the main thread. */
    private fun enqueue(
        id: String, targetPx: Int, wanted: () -> Boolean,
        bytes: () -> ByteArray?, onReady: (Bitmap?) -> Unit
    ) {
        cache.get(id)?.let { onReady(it); return }
        synchronized(pending) {
            pending[id]?.let { it.add(Waiter(wanted, onReady)); return }
            pending[id] = mutableListOf(Waiter(wanted, onReady))
        }
        submit(id, targetPx, bytes)
    }

    private fun submit(id: String, targetPx: Int, bytes: () -> ByteArray?) {
        decoder.execute {
            // Nobody still showing this photo: skip the read and the decode. Settled on
            // the main thread, where a tile that has just been bound back to it may
            // have joined the waiters in the meantime - then it is simply run again.
            val anyoneWants = synchronized(pending) {
                pending[id]?.any { runCatching { it.wanted() }.getOrDefault(true) } ?: false
            }
            if (!anyoneWants) {
                main.post {
                    val waiters = synchronized(pending) { pending[id]?.toList().orEmpty() }
                    if (waiters.any { it.wanted() }) submit(id, targetPx, bytes)
                    else synchronized(pending) { pending.remove(id) }?.forEach { it.onReady(null) }
                }
                return@execute
            }
            val decoded = bytes()?.let { decodeSampled(it, targetPx) }
            main.post {
                if (decoded != null) cache.put(id, decoded)
                synchronized(pending) { pending.remove(id) }?.forEach { it.onReady(decoded) }
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
