package com.example.synergic_pos_offline.fragments

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.R
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The Bill History list draws its column headings and its rows from two separate
 * layouts, so nothing but arithmetic keeps the figures under their labels. This
 * lays both out at one width and checks the Amount column still lines up now that
 * a second action button shares the right-hand end of the row.
 */
@RunWith(AndroidJUnit4::class)
class BillRowLayoutTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

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

    private fun themed() = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline)

    /** Lays [view] out at [width] and returns it. */
    private fun layOut(view: View, width: Int): View {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    /** The heading strip, pulled out of the list layout it lives in. */
    private fun headerRow(inflater: LayoutInflater): View {
        val list = inflater.inflate(R.layout.fragment_bill_list, null, false)
        // The strip is the LinearLayout whose first child reads "Bill No".
        return findHeading(list) ?: error("column heading row not found")
    }

    private fun findHeading(view: View): View? {
        if (view is LinearLayout && view.childCount > 0) {
            val first = view.getChildAt(0)
            if (first is TextView && first.text == "Bill No") return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findHeading(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test
    fun theAmountColumnLinesUpWithItsHeading() {
        val width = (900 * context.resources.displayMetrics.density).toInt()

        val report = onMain {
            val inflater = LayoutInflater.from(themed())

            val header = layOut(headerRow(inflater), width)
            val rowView = inflater.inflate(R.layout.item_bill_row, null, false)
            // Tinted the way BillAdapter tints it, so what is saved below is the row
            // as it actually appears rather than an untouched layout.
            val accent = android.content.res.ColorStateList.valueOf(
                com.example.synergic_pos_offline.utils.ThemeManager.getThemeColor(themed())
            )
            rowView.findViewById<MaterialButton>(R.id.btnPrintBillRow).apply {
                strokeColor = accent
                iconTint = accent
            }
            rowView.findViewById<MaterialButton>(R.id.btnViewBill).apply {
                strokeColor = accent
                setTextColor(accent)
            }
            // A detached view never resolves its layout direction, and MaterialButton
            // places its icon with a *relative* compound drawable - so the icon
            // silently does not draw here, though it does in the running app.
            // Resolving it by hand makes this render match what the list shows.
            rowView.layoutDirection = View.LAYOUT_DIRECTION_LTR
            val row = layOut(rowView, width)

            val headingAmount = (0 until (header as ViewGroup).childCount)
                .map { header.getChildAt(it) }
                .first { it is TextView && it.text == "Amount" }
            val rowAmount = row.findViewById<TextView>(R.id.tvRowAmount)
            val print = row.findViewById<MaterialButton>(R.id.btnPrintBillRow)
            val viewBtn = row.findViewById<MaterialButton>(R.id.btnViewBill)

            // Both are right-aligned, so their right edges are what have to agree.
            val headingRight = headingAmount.left + headingAmount.width
            val rowRight = rowAmount.left + rowAmount.width

            // Stacked into one image so the alignment can be seen, not just asserted.
            val bmp = Bitmap.createBitmap(width, header.height + row.height, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply {
                drawColor(Color.WHITE)
                header.draw(this)
                translate(0f, header.height.toFloat())
                row.draw(this)
            }
            FileOutputStream(File(context.filesDir, "bill_row.png")).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }

            listOf(headingRight, rowRight, print.width, viewBtn.width, row.width)
        }

        val (headingRight, rowRight, printWidth, viewWidth, rowWidth) = report

        assertEquals("Amount heading and figure are not aligned", headingRight, rowRight)
        assertTrue("the print button has no width", printWidth > 0)
        assertTrue("the View button has no width", viewWidth > 0)
        // The figure has to clear the buttons, or it reads as a label on one of them.
        assertTrue(
            "the amount is not held off the buttons (ends at $rowRight of $rowWidth)",
            rowRight < rowWidth - printWidth - viewWidth
        )
    }
}
