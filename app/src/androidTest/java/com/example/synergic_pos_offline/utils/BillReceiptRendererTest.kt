package com.example.synergic_pos_offline.utils

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.DatabaseHelper
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the off-screen receipt render that checkout auto-printing depends on.
 *
 * Nothing here is on screen, so the view hierarchy is inflated, measured and laid
 * out by hand - the part that has no equivalent on the bill screen and so is the
 * part that can silently produce blank paper.
 */
@RunWith(AndroidJUnit4::class)
class BillReceiptRendererTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** Runs on the main thread, as the app does; view inflation is not thread-safe. */
    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        var err: Throwable? = null
        instrumentation.runOnMainSync {
            try { out = block() } catch (t: Throwable) { err = t }
        }
        err?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun anyReceiptNo(): Long? {
        val db = DatabaseHelper.getInstance(context).readableDatabase
        db.rawQuery("SELECT receipt_no FROM ${DatabaseHelper.Tables.TD_BILLS} LIMIT 1", null)
            .use { c -> return if (c.moveToFirst()) c.getLong(0) else null }
    }

    /** Reports which step of the render collapses, rather than just "null". */
    @Test
    fun measuresTheReceiptCardOffScreen() {
        // Reported as skipped rather than passing vacuously: a fresh install has no
        // bills, and a green tick there would say the render works when it never ran.
        val receiptNo = anyReceiptNo()
        assumeTrue("no bill on this device to render", receiptNo != null)
        requireNotNull(receiptNo)

        val report = onMain {
            val root = LayoutInflater.from(context).inflate(R.layout.fragment_bill, null, false)
            val card = root.findViewById<View>(R.id.cardReceipt)
                ?: return@onMain "cardReceipt not found in the layout"

            BillReceiptRenderer(context).populate(root, receiptNo)
            (card.parent as? ViewGroup)?.removeView(card)

            val widthPx = (360 * context.resources.displayMetrics.density).toInt()
            card.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            card.layout(0, 0, card.measuredWidth, card.measuredHeight)
            "requested=$widthPx measured=${card.measuredWidth}x${card.measuredHeight} " +
                "laid out=${card.width}x${card.height}"
        }

        assertTrue("card collapsed -> $report", report.contains("laid out=") && !report.contains("x0"))
    }

    @Test
    fun rendersAnExistingBillToABitmap() {
        // Reported as skipped rather than passing vacuously: a fresh install has no
        // bills, and a green tick there would say the render works when it never ran.
        val receiptNo = anyReceiptNo()
        assumeTrue("no bill on this device to render", receiptNo != null)
        requireNotNull(receiptNo)

        val bitmap = onMain { BillReceiptRenderer(context).renderToBitmap(receiptNo) }

        assertNotNull("renderToBitmap returned null for bill $receiptNo", bitmap)
        assertTrue("receipt has no width", bitmap!!.width > 0)
        // A real receipt is far taller than it is wide; anything squatter means the
        // layout collapsed to its header.
        assertTrue("receipt collapsed to ${bitmap.width}x${bitmap.height}", bitmap.height > bitmap.width)
    }
}
