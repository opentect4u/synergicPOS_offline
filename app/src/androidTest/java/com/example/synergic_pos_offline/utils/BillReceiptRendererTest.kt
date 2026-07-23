package com.example.synergic_pos_offline.utils

import android.graphics.Bitmap
import android.util.Log
import android.view.ContextThemeWrapper
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
import java.io.File
import java.io.FileOutputStream

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

    /**
     * Renders the receipt for 58mm and 80mm, checks each is non-blank, and saves both
     * to the app's external files dir for eyeballing. Confirms the paper-width render
     * does not produce a blank or collapsed slip before it ever reaches a printer.
     */
    @Test
    fun rendersEachPaperWidthNonBlankAndSaves() {
        val receiptNo = anyReceiptNo()
        assumeTrue("no bill on this device to render", receiptNo != null)
        requireNotNull(receiptNo)

        // The renderer inflates MaterialComponents views, so it needs the app's theme -
        // exactly what a Fragment/Activity context carries in the running app.
        val themed = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline)
        val dir = context.filesDir
        val report = StringBuilder()

        for ((label, dots) in listOf("58mm" to 384, "80mm" to 576)) {
            val bmp = onMain { BillReceiptRenderer(themed).renderToBitmap(receiptNo, dots) }
            assertNotNull("render returned null at $label", bmp)
            requireNotNull(bmp)

            // Count pixels that are neither transparent nor white - real printed ink.
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val ink = px.count { (it ushr 24) != 0 && (it and 0x00FFFFFF) != 0x00FFFFFF }

            File(dir, "receipt_$label.png").also { f ->
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            report.append("$label -> ${bmp.width}x${bmp.height}, inkPixels=$ink\n")

            assertTrue("$label render is blank (no ink)", ink > 500)
            assertTrue("$label collapsed to ${bmp.width}x${bmp.height}", bmp.height > bmp.width)
        }

        File(dir, "receipt_dims.txt").writeText(report.toString())
        Log.i("RENDERCHECK", "\n$report")
    }
}
