package com.example.synergic_pos_offline

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.CustomerDao
import com.example.synergic_pos_offline.database.CustomerLedgerDao
import com.example.synergic_pos_offline.utils.LedgerReceiptRenderer
import com.example.synergic_pos_offline.utils.PrintType
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A customer ledger statement comes off the printer with blank paper under it.
 *
 * Two lines of it, the same as a kitchen ticket and a return slip. On most counter
 * printers the last line stops under the print HEAD rather than past the tear bar, so a
 * statement torn straight after printing takes the bottom of itself with it - and on a
 * ledger that bottom is the closing balance, the one figure the customer is being handed
 * the paper for.
 *
 * Checked on the rendered BITMAP rather than by reading the constant back, for the same
 * reason [ReturnSlipPaperFeedTest] does: the constant being right is not the claim. The
 * claim is that the paper actually comes out blank at the bottom, and a feed drawn in the
 * wrong colour, or added before the capture rather than after, would satisfy the constant
 * and fail here.
 */
@RunWith(AndroidJUnit4::class)
class LedgerPaperFeedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Two body lines at this device's density - what the renderer feeds. */
    private val feed: Int
        get() = android.graphics.Paint().apply {
            textSize = PrintType.BODY_SP * ctx.resources.displayMetrics.density
        }.let { (2 * (it.descent() - it.ascent())).toInt() }

    /**
     * The card's own bottom padding, in pixels - `receipt_ledger.xml`, paddingVertical.
     *
     * The statement ALREADY ended in this much white before any feed was added, which is
     * the whole reason the assertion below is written against it. A test that only asked
     * "are the last N rows blank?" passes just as happily with no feed at all - it was
     * measuring the card's padding and calling it paper. Verified by reverting the
     * change: that form of the test still passed.
     */
    private val cardPadding: Int
        get() = (22 * ctx.resources.displayMetrics.density).toInt()

    @Test
    fun theStatementEndsInBlankPaperBeyondItsOwnPadding() {
        val slip = render()
        assertNotNull("the ledger should render", slip)

        assertTrue(
            "the statement should be taller than the feed it carries",
            slip!!.height > feed
        )

        // The blank run at the foot has to cover the card's padding AND the fed paper on
        // top of it. Without the feed this falls short by about two lines, which is the
        // point: it is the ADDED paper being measured, not the layout's own margin.
        val blank = trailingBlankRows(slip)
        assertTrue(
            "expected at least ${cardPadding + feed} blank rows at the foot " +
                "(${cardPadding} of card padding + $feed of feed) but found $blank",
            blank >= cardPadding + feed
        )
    }

    /**
     * How many rows at the bottom are blank paper, counting up from the last one.
     *
     * Sampled across the full width, so a row that is blank down one edge and printed
     * down the other counts as printed.
     */
    private fun trailingBlankRows(slip: Bitmap): Int {
        var rows = 0
        for (y in slip.height - 1 downTo 0) {
            var blank = true
            for (x in 0 until slip.width step (slip.width / 8).coerceAtLeast(1)) {
                val px = slip.getPixel(x, y)
                if (px != Color.WHITE && Color.alpha(px) != 0) { blank = false; break }
            }
            if (!blank) break
            rows++
        }
        return rows
    }

    /**
     * The blankness is the FEED, not an empty statement.
     *
     * Proves the test above is measuring paper added below the document rather than a
     * document that happened to render empty.
     */
    @Test
    fun thereIsPrintedContentAboveTheFeed() {
        val slip = render()!!
        var darkRows = 0
        for (y in 0 until slip.height) {
            for (x in 0 until slip.width step 4) {
                val px = slip.getPixel(x, y)
                if (Color.alpha(px) > 0 && Color.red(px) < 128) { darkRows++; break }
            }
        }
        assertTrue("the statement should actually have printed something on it", darkRows > 0)
    }

    private fun render(): Bitmap? {
        var out: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            out = LedgerReceiptRenderer(ctx).renderToBitmap(ledger(), "TESTER")
        }
        return out
    }

    private fun ledger() = CustomerLedgerDao.Ledger(
        customer = CustomerDao.Customer(
            id = 1,
            name = "Feed Test",
            address = "",
            phone = "9000000000",
            gstin = "",
            creditEnabled = true,
            creditLimit = 5000.0,
            balance = 250.0
        ),
        fromDate = "2026-09-01",
        toDate = "2026-09-22",
        opening = 100.0,
        entries = emptyList(),
        totalIn = 50.0,
        totalOut = 200.0,
        closing = 250.0
    )
}
