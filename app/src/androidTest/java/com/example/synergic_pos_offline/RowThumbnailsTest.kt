package com.example.synergic_pos_offline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.synergic_pos_offline.utils.RowThumbnails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Row thumbnails are decoded once, not once per bind.
 *
 * The master tables recycle their row views, but binding a product row decoded its
 * photograph from a JPEG BLOB every time the row crossed the screen - two BitmapFactory
 * passes, per row, per pass. That, not the number of rows, is what made a long product
 * list stutter; the table has paged its rows since it was written.
 *
 * The timing test below is a measurement rather than a threshold: it asserts only that
 * the cache is substantially faster, because the absolute numbers belong to whatever
 * machine runs it.
 */
@RunWith(AndroidJUnit4::class)
class RowThumbnailsTest {

    /** A JPEG the size of a photograph taken on a phone, as a product image would be. */
    private fun photo(px: Int = 1600): ByteArray {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Noise, not a flat fill: a single-colour JPEG compresses to almost nothing and
        // would decode far faster than a real photograph, flattering the uncached case.
        val paint = Paint()
        var seed = 12345
        for (x in 0 until px step 8) {
            for (y in 0 until px step 8) {
                seed = seed * 1103515245 + 12345
                paint.color = Color.rgb((seed shr 16) and 0xFF, (seed shr 8) and 0xFF, seed and 0xFF)
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + 8).toFloat(), (y + 8).toFloat(), paint)
            }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Before
    fun emptyTheCache() = RowThumbnails.clear()

    @Test
    fun theSameRowIsDecodedOnceAndHandedBackAfterwards() {
        val bytes = photo(600)
        val first = RowThumbnails.bitmap("1", bytes)
        val second = RowThumbnails.bitmap("1", bytes)
        assertNotNull(first)
        // The very same object, which is what makes a rebind free.
        assertSame("a second bind should not decode again", first, second)
    }

    @Test
    fun aRowWithNoImageGetsNoBitmap() {
        assertNull(RowThumbnails.bitmap("1", null))
        assertNull("an empty blob is not an image", RowThumbnails.bitmap("2", ByteArray(0)))
    }

    @Test
    fun bytesThatAreNotAnImageGiveAPlaceholderRatherThanACrash() {
        // A column holding something that is not an image should be a blank thumbnail on
        // screen, not a list that dies while being scrolled.
        assertNull(RowThumbnails.bitmap("3", ByteArray(64) { it.toByte() }))
    }

    @Test
    fun clearingMakesTheNextBindDecodeAfresh() {
        // Why every reload clears it: edit a product's photo and the old bitmap would
        // otherwise still be held under the same row id.
        val bytes = photo(600)
        val first = RowThumbnails.bitmap("1", bytes)
        RowThumbnails.clear()
        val afterClear = RowThumbnails.bitmap("1", bytes)
        assertNotNull(afterClear)
        assertTrue("clear() should have dropped the old bitmap", first !== afterClear)
    }

    @Test
    fun differentRowsKeepTheirOwnImages() {
        val a = photo(400)
        val b = photo(600)
        val first = RowThumbnails.bitmap("a", a)
        val second = RowThumbnails.bitmap("b", b)
        assertTrue("two rows must not share one bitmap", first !== second)
        assertSame(first, RowThumbnails.bitmap("a", a))
        assertSame(second, RowThumbnails.bitmap("b", b))
    }

    @Test
    fun theThumbnailIsScaledDownNotKeptAtFullSize() {
        // The point of the sampling pass. A 1600px photo held at full size would be
        // 10MB of bitmap per row; at 120px it is about 57KB.
        val thumb = RowThumbnails.bitmap("1", photo(1600))
        assertNotNull(thumb)
        assertTrue(
            "decoded at ${thumb!!.width}x${thumb.height}, expected no more than 2x the 120px target",
            thumb.width <= RowThumbnails.THUMB_PX * 2 && thumb.height <= RowThumbnails.THUMB_PX * 2
        )
    }

    @Test
    fun rebindingIsDramaticallyCheaperThanDecoding() {
        val bytes = photo(1600)
        val binds = 200

        // What the list used to do: a decode on every pass of every row.
        RowThumbnails.clear()
        val uncached = nanos {
            repeat(binds) {
                RowThumbnails.bitmap("row$it", bytes)
                RowThumbnails.clear()
            }
        }

        // What it does now: decode once, then hand the same bitmap back.
        RowThumbnails.clear()
        RowThumbnails.bitmap("row", bytes)
        val cached = nanos { repeat(binds) { RowThumbnails.bitmap("row", bytes) } }

        val ratio = uncached.toDouble() / cached.coerceAtLeast(1)
        println(
            "RowThumbnails: $binds binds - decoding ${uncached / 1_000_000}ms, " +
                "cached ${cached / 1_000_000}ms (${"%.0f".format(ratio)}x)"
        )
        assertTrue(
            "caching should be far cheaper than decoding, but was only ${"%.1f".format(ratio)}x",
            ratio > 20
        )
    }

    private fun nanos(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    @Test
    fun theCacheDoesNotGrowWithoutBound() {
        // Every row of a large catalogue, bound once. The cache must evict rather than
        // hold every product photo in the shop - which is the memory problem it would
        // otherwise create while solving the speed one.
        val bytes = photo(800)
        repeat(400) { RowThumbnails.bitmap("row$it", bytes) }
        // The earliest entries should have been evicted; the most recent must survive.
        assertNotNull("the newest thumbnail should still be held", RowThumbnails.bitmap("row399", bytes))
        assertEquals(
            "the newest thumbnail should be the cached instance",
            RowThumbnails.bitmap("row399", bytes),
            RowThumbnails.bitmap("row399", bytes)
        )
    }
}
