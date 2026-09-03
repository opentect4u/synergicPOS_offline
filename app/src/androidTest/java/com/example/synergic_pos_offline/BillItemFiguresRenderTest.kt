package com.example.synergic_pos_offline

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.utils.BillReceiptRenderer
import com.example.synergic_pos_offline.utils.ReceiptPrinter
import com.example.synergic_pos_offline.utils.ReceiptContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every figure on a printed bill actually has ink on it.
 *
 * Written after a bill shipped whose quantity, price, discount and amount columns
 * printed *blank* - each cell the right width, holding the right text, drawing
 * nothing. The cause was `isSingleLine = true`, which also turns on horizontal
 * scrolling; with a fixed width and no ellipsize the TextView laid its text out at
 * its natural width and scrolled it out of the cell.
 *
 * That is why this checks pixels rather than the view tree: the view tree was
 * perfect throughout. Each cell's rectangle is located in the captured bitmap and
 * searched for dark pixels, which is the only assertion the bug could not pass.
 */
@RunWith(AndroidJUnit4::class)
class BillItemFiguresRenderTest {

    private fun draft(withDiscount: Boolean) = BillReceiptRenderer.Draft(
        billNumber = "TEST",
        dateTime = "2026-08-07 11:37:00",
        cashier = "ADMIN",
        customer = BillReceiptRenderer.Draft.Customer(name = "Walk-in"),
        items = listOf(
            // Short name: shares its line with the figures.
            BillReceiptRenderer.Draft.Item(
                name = "lali", quantity = 1.0, rate = 40.0,
                cgstRate = 2.5, sgstRate = 2.5, unit = "CUP"
            ),
            // Long name, and a fractional quantity whose trailing zero used to wrap:
            // takes a line of its own with the figures beneath.
            BillReceiptRenderer.Draft.Item(
                name = "MIXED FR RICE", quantity = 1.5, rate = 100.0,
                cgstRate = 2.5, sgstRate = 2.5, unit = "PLT",
                discountAmount = if (withDiscount) 15.0 else 0.0
            )
        ),
        discount = if (withDiscount) 15.0 else 0.0,
        roundOff = 0.0,
        netAmount = 0.0,
        paymentModes = listOf("CASH")
    )

    @Test
    fun everyFigureIsPrinted() {
        val raw = InstrumentationRegistry.getInstrumentation().targetContext
        val ctx = ReceiptContext.standardFontScale(raw)
        val density = ctx.resources.displayMetrics.density

        val layouts = mapOf(
            "classic" to R.layout.fragment_bill_classic,
            "standard" to R.layout.fragment_bill,
            "taxwise" to R.layout.fragment_bill_tax_wise
        )
        for ((name, layout) in layouts) {
            for (dots in listOf(576, 384)) {
                for (disc in listOf(false, true)) {
                    check("$name ${dots}dots disc=$disc", layout, dots, disc, ctx, raw, density)
                }
            }
        }
    }

    private fun check(
        label: String,
        layout: Int,
        dots: Int,
        disc: Boolean,
        ctx: android.content.Context,
        raw: android.content.Context,
        density: Float
    ) {
        val view = LayoutInflater.from(ctx).inflate(layout, null, false)
        BillReceiptRenderer(raw).populate(view, 0L, dots, draft = draft(disc))

        val card = view.findViewById<View>(R.id.cardReceipt)
        (card.parent as? ViewGroup)?.removeView(card)
        val widthPx = (300.0 * dots / 576 * density).toInt()
        card.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        val bitmap = ReceiptPrinter.capture(card) ?: error("$label: nothing rendered")

        val figures = mutableListOf<TextView>()
        // Searched from the card, not the root: the card was detached above, the way
        // the renderer detaches it before capturing.
        collectFigures(card.findViewById<LinearLayout>(R.id.llItems), figures)
        assertTrue("$label: no figure cells were built at all", figures.isNotEmpty())

        for (cell in figures) {
            val text = cell.text?.toString().orEmpty()
            if (text.isBlank()) continue
            assertTrue(
                "$label: figure cell '$text' has no width",
                cell.width > 0
            )
            assertTrue(
                "$label: figure cell '$text' printed blank - it is laid out and holds " +
                    "text, but no ink reached the paper",
                hasInk(bitmap, cell, card)
            )
        }
    }

    /** The figure cells of the item table: every leaf TextView bar the name column. */
    private fun collectFigures(group: ViewGroup, out: MutableList<TextView>) {
        for (i in 0 until group.childCount) {
            when (val child = group.getChildAt(i)) {
                is ViewGroup -> collectFigures(child, out)
                // The name is the full-width or first cell of its row; the figures are
                // the ones that follow it.
                is TextView -> if (i > 0) out.add(child)
            }
        }
    }

    /** True if any pixel inside [cell]'s rectangle in [bitmap] is dark. */
    private fun hasInk(bitmap: Bitmap, cell: View, root: View): Boolean {
        var x = 0
        var y = 0
        var v: View? = cell
        while (v != null && v !== root) {
            x += v.left
            y += v.top
            v = v.parent as? View
        }
        val right = (x + cell.width).coerceAtMost(bitmap.width)
        val bottom = (y + cell.height).coerceAtMost(bitmap.height)
        for (px in x.coerceAtLeast(0) until right) {
            for (py in y.coerceAtLeast(0) until bottom) {
                val c = bitmap.getPixel(px, py)
                val luma = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
                if (luma < 128) return true
            }
        }
        return false
    }
}
