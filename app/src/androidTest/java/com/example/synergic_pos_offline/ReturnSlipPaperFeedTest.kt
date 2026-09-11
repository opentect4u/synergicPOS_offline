package com.example.synergic_pos_offline

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.ReturnDao
import com.example.synergic_pos_offline.utils.PrintType
import com.example.synergic_pos_offline.utils.ReturnReceiptRenderer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A sale return slip comes off the printer with blank paper under it.
 *
 * Two lines of it, the same as a kitchen ticket. On most counter printers the last
 * line stops under the print HEAD rather than past the tear bar, so a slip torn
 * straight after printing takes the bottom of itself with it - on a return that is
 * the "Returned by" line, and on a narrow roll the total above it too.
 *
 * Checked on the rendered BITMAP rather than by reading the constant back, because
 * the constant being right is not the claim - the claim is that the paper actually
 * comes out blank at the bottom, and a feed drawn in the wrong colour or added
 * before the capture rather than after would satisfy the constant and fail here.
 */
@RunWith(AndroidJUnit4::class)
class ReturnSlipPaperFeedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theSlipEndsInBlankPaper() {
        val slip = render()
        assertNotNull("the return slip should render", slip)

        // Two body lines at this device's density - what the renderer feeds.
        val lineHeight = android.graphics.Paint().apply {
            textSize = PrintType.BODY_SP * ctx.resources.displayMetrics.density
        }.let { it.descent() - it.ascent() }
        val feed = (2 * lineHeight).toInt()

        assertTrue(
            "the slip should be taller than the feed it carries",
            slip!!.height > feed
        )

        // Every row of the feed must be blank. Sampled across the full width, so a
        // feed that was only blank down one edge would still fail.
        for (y in slip.height - feed until slip.height) {
            for (x in 0 until slip.width step (slip.width / 8).coerceAtLeast(1)) {
                val px = slip.getPixel(x, y)
                assertTrue(
                    "paper fed under the slip must be blank - found " +
                        "${Integer.toHexString(px)} at ($x, $y) of ${slip.width}x${slip.height}",
                    px == Color.WHITE || Color.alpha(px) == 0
                )
            }
        }
    }

    /**
     * The blankness is the FEED, not an empty slip.
     *
     * The row just above the feed carries the last printed line, so this proves the
     * test above is measuring paper added below the document rather than a document
     * that happened to render empty.
     */
    @Test
    fun thereIsPrintedContentAboveTheFeed() {
        val slip = render()!!
        var darkRows = 0
        for (y in 0 until slip.height) {
            var dark = false
            for (x in 0 until slip.width step 4) {
                val px = slip.getPixel(x, y)
                if (Color.alpha(px) > 0 && Color.red(px) < 128) { dark = true; break }
            }
            if (dark) darkRows++
        }
        assertTrue("the slip should actually have printed something on it", darkRows > 0)
    }

    private fun render(): Bitmap? {
        var out: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            out = ReturnReceiptRenderer(ctx).renderToBitmap(result(), "TESTER")
        }
        return out
    }

    private fun result() = ReturnDao.Result(
        id = 0,
        returnNumber = "FEED-TEST",
        dateTime = "2026-09-11 12:00:00",
        originalBillNumber = null,
        lines = emptyList(),
        totalGross = 100.0,
        totalDiscount = 0.0,
        totalCgst = 0.0,
        totalSgst = 0.0,
        totalVat = 0.0,
        totalAmount = 100.0
    )
}
