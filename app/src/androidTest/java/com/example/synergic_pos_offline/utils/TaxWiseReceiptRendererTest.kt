package com.example.synergic_pos_offline.utils

import android.graphics.Bitmap
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.BillSettingsDao.BillFormat
import com.example.synergic_pos_offline.database.TaxSettingsDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the Tax wise short layout and checks the tax table it exists for.
 *
 * The sale is the one on the reference slip this format was built from, so the
 * figures asserted here are that slip's own: three lines, two of them taxed at 5%
 * and clubbed into one row, one at 10%.
 */
@RunWith(AndroidJUnit4::class)
class TaxWiseReceiptRendererTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val themed = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline)

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

    /**
     * The reference sale: 98.00 and 20.00 taxed at 2.5 + 2.5, and 56.00 at 5 + 5.
     * The first two club into a single 5.00% row over a 118.00 base; the third is a
     * 10.00% row of its own.
     */
    private fun draft() = BillReceiptRenderer.Draft(
        billNumber = "3",
        dateTime = "2026-07-31 10:26:00",
        cashier = "ADMIN",
        customer = BillReceiptRenderer.Draft.Customer(),
        items = listOf(
            BillReceiptRenderer.Draft.Item(
                name = "Cofee Bru", quantity = 1.0, rate = 98.0,
                cgstRate = 2.5, sgstRate = 2.5, unit = "GM"
            ),
            BillReceiptRenderer.Draft.Item(
                name = "Lux Soap", quantity = 1.0, rate = 56.0,
                cgstRate = 5.0, sgstRate = 5.0, unit = "PKT"
            ),
            BillReceiptRenderer.Draft.Item(
                name = "Bislery..1L", quantity = 1.0, rate = 20.0,
                cgstRate = 2.5, sgstRate = 2.5, unit = "LTR"
            )
        ),
        discount = 0.0,
        // As on the reference slip: 185.50 rounds to 186.00.
        roundOff = 0.50,
        netAmount = 186.0,
        paymentModes = listOf("CASH")
    )

    /**
     * Renders [draft] into the Tax wise short layout with the till switched to it -
     * GST on, round off on, since both are what the slip reports - and puts every
     * setting back afterwards.
     */
    private fun <T> asTaxWise(
        draft: BillReceiptRenderer.Draft = draft(),
        paperDots: Int = 576,
        block: (View) -> T
    ): T {
        val bills = BillSettingsDao(context)
        val taxes = TaxSettingsDao(context)
        val billsBefore = bills.load()
        val taxBefore = taxes.load()
        bills.save(
            billsBefore.copy(billFormat = BillFormat.TAX_WISE_SHORT, roundOff = true)
        )
        taxes.save(
            taxBefore.copy(
                gstEnabled = true,
                gstMode = TaxSettingsDao.GstMode.EXCLUSIVE,
                vatEnabled = false
            )
        )
        try {
            return onMain {
                val root = LayoutInflater.from(themed)
                    .inflate(BillReceiptRenderer.layoutFor(context), null, false)
                root.findViewById<View>(R.id.btnPrintBill)?.visibility = View.GONE
                BillReceiptRenderer(themed).populate(root, 0L, paperDots, draft = draft)
                block(root)
            }
        } finally {
            taxes.save(taxBefore)
            bills.save(billsBefore)
        }
    }

    /** Every visible TextView's text in the tree, in the order they are laid out. */
    private fun texts(root: View): List<String> = buildList {
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView) v.text?.toString()?.let { if (it.isNotBlank()) add(it) }
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(root)
    }

    /** The tax table's rows, each as the list of figures across it. */
    private fun taxRows(root: View): List<List<String>> {
        val rows = root.findViewById<LinearLayout>(R.id.llTaxRows)
        return (0 until rows.childCount).map { texts(rows.getChildAt(it)) }
    }

    @Test
    fun choosingTaxWiseSelectsTheTaxWiseLayout() {
        assertEquals(
            R.layout.fragment_bill_tax_wise,
            BillReceiptRenderer.layoutFor(BillFormat.TAX_WISE_SHORT)
        )
    }

    /**
     * The table itself: a row per rate at the *combined* rate charged, the lines
     * sharing a rate clubbed into one base, and a TOTAL that is that base with its
     * tax on it.
     */
    @Test
    fun clubsEachRateIntoOneRowOfTheTaxTable() {
        val rows = asTaxWise { taxRows(it) }

        assertEquals("expected one row per rate, got $rows", 2, rows.size)
        // 98.00 + 20.00 taxed at 2.5 + 2.5: base 118.00, 2.95 each side, 123.90 in all.
        assertEquals(listOf("5.00%", "118.00", "2.95", "2.95", "123.90"), rows[0])
        // 56.00 taxed at 5 + 5: base 56.00, 2.80 each side, 61.60 in all.
        assertEquals(listOf("10.00%", "56.00", "2.80", "2.80", "61.60"), rows[1])
    }

    /** The headings the table's figures sit under. */
    @Test
    fun headsTheTableWithItsColumns() {
        val lines = asTaxWise { texts(it) }
        listOf("TAX%", "B.AMT", "SGST", "CGST", "TOTAL").forEach {
            assertTrue("heading $it missing: $lines", lines.contains(it))
        }
    }

    /**
     * The totals below the table, and what is *not* there: the tax is reported by
     * the table, so no line per component and no TOTAL TAX to add them up.
     */
    @Test
    fun statesTheTotalsWithoutRepeatingTheTax() {
        val summary = asTaxWise { texts(it.findViewById<LinearLayout>(R.id.llSummary)) }
        val grand = asTaxWise { it.findViewById<TextView>(R.id.tvGrandTotal).text.toString() }

        assertTrue("no per-component tax lines here: $summary",
            summary.none { it.startsWith("SGST") || it.startsWith("CGST") })
        assertTrue("the table already totals the tax: $summary",
            summary.none { it.startsWith("TOTAL TAX") })

        // 118.00 + 5% and 56.00 + 10%, as the table states them.
        assertTrue("TOTAL AMOUNT missing: $summary", summary.any { it.startsWith("TOTAL AMOUNT") })
        assertTrue("185.50 missing: $summary", summary.contains("185.50"))
        assertTrue("ROUNDED OFF missing: $summary", summary.any { it.startsWith("ROUNDED OFF") })
        assertTrue("0.50 missing: $summary", summary.contains("0.50"))
        assertEquals("186.00", grand)
    }

    /** A bill with no tax on it prints no table - headings and rules included. */
    @Test
    fun leavesOutTheWholeTableWhenThereIsNoTax() {
        val untaxed = draft().copy(
            items = listOf(
                BillReceiptRenderer.Draft.Item(name = "Plain Item", quantity = 2.0, rate = 50.0)
            ),
            roundOff = 0.0,
            netAmount = 100.0
        )
        val visible = asTaxWise(untaxed) { root ->
            root.findViewById<View>(R.id.llTaxTable).visibility == View.VISIBLE
        }
        assertTrue("an untaxed bill should print no tax table", !visible)
    }

    /**
     * Renders at both paper widths, checks each carries real ink and saves it for
     * eyeballing - a layout that measures to nothing prints blank paper.
     */
    @Test
    fun rendersEachPaperWidthNonBlankAndSaves() {
        val report = StringBuilder()
        for ((label, dots) in listOf("58mm" to 384, "80mm" to 576)) {
            val bmp = asTaxWise(paperDots = dots) { root ->
                val card = root.findViewById<View>(R.id.cardReceipt)
                (card.parent as? ViewGroup)?.removeView(card)
                val widthPx =
                    (360.0 * dots / 576 * context.resources.displayMetrics.density).toInt()
                card.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                card.layout(0, 0, card.measuredWidth, card.measuredHeight)
                ReceiptPrinter.capture(card)
            }
            assertNotNull("Tax wise render returned null at $label", bmp)
            requireNotNull(bmp)

            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val ink = px.count { (it ushr 24) != 0 && (it and 0x00FFFFFF) != 0x00FFFFFF }

            File(context.filesDir, "taxwise_$label.png").also { f ->
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            report.append("$label -> ${bmp.width}x${bmp.height}, inkPixels=$ink\n")

            assertTrue("$label Tax wise render is blank (no ink)", ink > 500)
            assertTrue("$label collapsed to ${bmp.width}x${bmp.height}", bmp.height > bmp.width)
        }
        Log.i("RENDERCHECK", "\ntaxwise\n$report")
    }
}
