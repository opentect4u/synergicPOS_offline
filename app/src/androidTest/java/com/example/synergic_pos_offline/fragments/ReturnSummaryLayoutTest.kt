package com.example.synergic_pos_offline.fragments

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The item-wise return breaks a line down the way the item sale dialog does, so the
 * same person reads a return the way they read a sale.
 *
 * That is a claim about two layouts agreeing, which nothing enforces at build time -
 * so it is checked here by reading the rows out of both.
 */
@RunWith(AndroidJUnit4::class)
class ReturnSummaryLayoutTest {

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

    /** The static label of every row in a breakdown block, top to bottom. */
    private fun labels(root: View, ids: List<Int>): List<String> =
        ids.mapNotNull { root.findViewById<TextView>(it)?.text?.toString() }

    @Test
    fun theReturnBreakdownMirrorsTheSaleDialog() {
        val report = onMain {
            val inflater = LayoutInflater.from(themed())
            val sale = inflater.inflate(R.layout.dialog_product_entry, null, false)
            val ret = inflater.inflate(R.layout.fragment_return_itemwise, null, false)

            // The rows each block is built from, in order.
            val saleRows = labels(
                sale, listOf(R.id.tvItemDiscountLabel, R.id.tvCgstLabel, R.id.tvSgstLabel)
            )
            val returnRows = labels(
                ret, listOf(R.id.tvReturnDiscountLabel, R.id.tvReturnCgstLabel, R.id.tvReturnSgstLabel)
            )
            saleRows to returnRows
        }

        val (saleRows, returnRows) = report
        assertEquals("the return should carry the same breakdown rows as a sale", saleRows, returnRows)
    }

    /** The amount is set apart the same way: bold label, larger figure. */
    @Test
    fun theRefundIsSetApartLikeTheSaleAmount() {
        val sizes = onMain {
            val inflater = LayoutInflater.from(themed())
            val sale = inflater.inflate(R.layout.dialog_product_entry, null, false)
            val ret = inflater.inflate(R.layout.fragment_return_itemwise, null, false)
            sale.findViewById<TextView>(R.id.tvLineAmount).textSize to
                ret.findViewById<TextView>(R.id.tvReturnLineAmount).textSize
        }
        assertEquals("the refund should be set at the sale amount's size", sizes.first, sizes.second, 0.01f)
    }

    /** Saved for eyeballing: the return screen's detail card as it appears. */
    @Test
    fun rendersTheReturnDetailForEyeballing() {
        val width = (900 * context.resources.displayMetrics.density).toInt()
        onMain {
            val view = LayoutInflater.from(themed())
                .inflate(R.layout.fragment_return_itemwise, null, false)
            // The detail card is hidden until an item is picked.
            view.findViewById<View>(R.id.llItemEmpty).visibility = View.GONE
            view.findViewById<View>(R.id.svItemDetail).visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.tvItemName).text = "Orange Juice 1L"
            view.findViewById<TextView>(R.id.tvItemMeta).text = "Barcode 8901234  ·  HSN 220290"
            view.findViewById<TextView>(R.id.tvReturnCgstLabel).text = "CGST (2.50%)"
            view.findViewById<TextView>(R.id.tvReturnSgstLabel).text = "SGST (2.50%)"
            view.findViewById<TextView>(R.id.tvReturnTaxable).text = "₹180.00"
            view.findViewById<TextView>(R.id.tvReturnCgstAmt).text = "₹4.50"
            view.findViewById<TextView>(R.id.tvReturnSgstAmt).text = "₹4.50"
            view.findViewById<TextView>(R.id.tvReturnLineAmount).text = "₹189.00"
            view.layoutDirection = View.LAYOUT_DIRECTION_LTR

            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                    (1400 * context.resources.displayMetrics.density).toInt(),
                    View.MeasureSpec.EXACTLY
                )
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)

            if (view.width > 0 && view.height > 0) {
                val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                Canvas(bmp).also { it.drawColor(Color.WHITE) }.let { view.draw(it) }
                FileOutputStream(File(context.filesDir, "return_itemwise_screen.png")).use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            assertTrue("the detail card collapsed", view.height > 0)
        }
    }
}
