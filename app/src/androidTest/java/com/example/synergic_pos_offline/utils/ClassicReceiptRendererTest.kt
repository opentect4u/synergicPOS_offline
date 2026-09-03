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
 * Renders the Classic bill layout off a known sale and checks it says what a
 * Classic slip says.
 *
 * Driven from a [BillReceiptRenderer.Draft] rather than a bill on the device, so it
 * runs on a fresh install and - more to the point - the totals are known in advance
 * and can be asserted rather than merely being non-blank.
 */
@RunWith(AndroidJUnit4::class)
class ClassicReceiptRendererTest {

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
     * Two lines at 5% GST and one at 10%, so the slip has two rate slabs to break
     * out and the tax adds up to a figure worth asserting.
     */
    private fun draft() = BillReceiptRenderer.Draft(
        billNumber = "1",
        dateTime = "2026-07-31 10:24:00",
        cashier = "ADMIN",
        customer = BillReceiptRenderer.Draft.Customer(),
        items = listOf(
            BillReceiptRenderer.Draft.Item(
                name = "Lux Soap", quantity = 1.0, rate = 56.0,
                cgstRate = 2.5, sgstRate = 2.5, unit = "PKT"
            ),
            BillReceiptRenderer.Draft.Item(
                name = "Cofee Bru", quantity = 1.0, rate = 98.0,
                cgstRate = 2.5, sgstRate = 2.5, unit = "GM"
            ),
            BillReceiptRenderer.Draft.Item(
                name = "Bislery..1L", quantity = 1.0, rate = 20.0,
                cgstRate = 5.0, sgstRate = 5.0, unit = "LTR"
            )
        ),
        discount = 0.0,
        roundOff = 0.0,
        netAmount = 174.0,
        paymentModes = listOf("CASH")
    )

    /**
     * Renders [draft] into the Classic layout with the till switched to it, and puts
     * the till back as it was afterwards.
     *
     * GST is switched on for the duration too. The slip prints whatever regime the
     * till is set to, so a device configured with no tax at all would otherwise make
     * the tax assertions pass vacuously by having nothing to print.
     */
    private fun <T> asClassic(
        draft: BillReceiptRenderer.Draft = draft(),
        paperDots: Int = 576,
        block: (View) -> T
    ): T {
        val bills = BillSettingsDao(context)
        val taxes = TaxSettingsDao(context)
        val formatBefore = bills.load().billFormat
        val taxBefore = taxes.load()
        bills.saveBillFormat(BillFormat.CLASSIC)
        taxes.save(
            taxBefore.copy(
                taxEnabled = true,
                taxMode = TaxSettingsDao.GstMode.EXCLUSIVE
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
            bills.saveBillFormat(formatBefore)
        }
    }

    /**
     * Every visible TextView's text in the tree, in the order they are laid out.
     * Hidden views are skipped - a column the renderer switched off still carries
     * its heading, and counting it would be reading text off the paper that is not
     * on it.
     */
    private fun texts(root: View): List<String> = buildList {
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView) v.text?.toString()?.let { if (it.isNotBlank()) add(it) }
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(root)
    }

    @Test
    fun choosingClassicSelectsTheClassicLayout() {
        assertEquals(R.layout.fragment_bill_classic, BillReceiptRenderer.layoutFor(BillFormat.CLASSIC))
        assertEquals(R.layout.fragment_bill, BillReceiptRenderer.layoutFor(BillFormat.STANDARD))
        // The formats without a layout of their own print Standard, which is what
        // Print Template tells the operator will happen.
        assertEquals(R.layout.fragment_bill, BillReceiptRenderer.layoutFor(BillFormat.CLUBBED))
    }

    /**
     * The head, the item table and the totals, as a Classic slip states them: one
     * row per line item carrying its unit, tax broken out per rate lowest first with
     * SGST above CGST, and TOTAL TAX / TOTAL AMOUNT below them.
     */
    @Test
    fun printsTheClassicHeadItemsAndTotals() {
        val lines = asClassic { texts(it) }
        assertTrue("the DISC column has nothing to show: $lines", !lines.contains("DISC"))

        assertTrue("bill number missing: $lines", lines.contains("BILL NO: 1"))
        assertTrue("date missing: $lines", lines.contains("31-07-2026"))
        assertTrue("time missing: $lines", lines.contains("10:24"))

        assertTrue("column headings missing: $lines", lines.contains("SR.NO ITEM"))
        assertTrue("PRICE heading missing: $lines", lines.contains("PRICE"))
        assertTrue("AMOUNT heading missing: $lines", lines.contains("AMOUNT"))

        assertTrue("item 1 missing: $lines", lines.contains("1 LUX SOAP"))
        assertTrue("item 3 missing: $lines", lines.contains("3 BISLERY..1L"))
        // The quantity carries its unit on a Classic slip.
        assertTrue("unit missing from the quantity: $lines", lines.contains("1 PKT"))
        assertTrue("unit missing from the quantity: $lines", lines.contains("1 LTR"))

        // 154.00 at 5% is 7.34 (3.67 + 3.67); 20.00 at 10% is 1.82 (0.91 + 0.91) -
        // both bills are priced exclusive of tax by default.
        assertTrue("2.50% slab missing: $lines", lines.any { it.startsWith("SGST") && it.contains("2.50%") })
        assertTrue("5.00% slab missing: $lines", lines.any { it.startsWith("CGST") && it.contains("5.00%") })
        // The labels carry their own alignment padding and colon, so they are
        // matched by what they say rather than compared whole.
        assertTrue("TOTAL TAX missing: $lines", lines.any { it.startsWith("TOTAL TAX") })
        assertTrue("TOTAL AMOUNT missing: $lines", lines.any { it.startsWith("TOTAL AMOUNT") })
        // The colons line up: every totals line puts one at the same offset.
        val colons = lines.filter { it.contains(" :") }.map { it.indexOf(" :") }
        assertTrue("the colons should line up, got $colons", colons.distinct().size == 1)

        // The lowest rate reads first, and SGST sits above CGST within a slab.
        val firstTax = lines.indexOfFirst { it.startsWith("SGST") || it.startsWith("CGST") }
        assertTrue("no tax lines at all: $lines", firstTax >= 0)
        assertTrue("SGST should head the slab: ${lines[firstTax]}", lines[firstTax].startsWith("SGST"))
        assertTrue("lowest rate should read first: ${lines[firstTax]}", lines[firstTax].contains("2.50%"))
    }

    /** The payable figure, set apart on its own GRAND TOTAL line. */
    @Test
    fun printsTheGrandTotal() {
        val total = asClassic { root ->
            root.findViewById<TextView>(R.id.tvGrandTotal).text.toString()
        }
        // 56.00 + 98.00 + 20.00, plus 7.70 GST on the first two and 2.00 on the third.
        assertEquals("183.70", total)
    }

    /**
     * An undiscounted, untaxed bill prints neither a DISCOUNT line nor any tax -
     * the conditional parts of the block stay conditional in this layout too.
     */
    @Test
    fun leavesOutTaxAndDiscountWhenThereAreNone() {
        val untaxed = draft().copy(
            items = listOf(
                BillReceiptRenderer.Draft.Item(name = "Plain Item", quantity = 2.0, rate = 50.0)
            ),
            netAmount = 100.0
        )
        // Only the summary block is read - a footer line could legitimately say
        // anything, and this is about what the totals do or do not list.
        val lines = asClassic(untaxed) { texts(it.findViewById<LinearLayout>(R.id.llSummary)) }

        assertTrue("no tax, so no tax lines: $lines", lines.none { it.startsWith("SGST") || it.startsWith("CGST") })
        assertTrue("no tax, so no TOTAL TAX: $lines", lines.none { it.startsWith("TOTAL TAX") })
        assertTrue("no discount, so no DISCOUNT: $lines", lines.none { it.startsWith("DISCOUNT") })
        assertTrue("the total is always stated: $lines", lines.any { it.startsWith("TOTAL AMOUNT") })
    }

    /**
     * Renders Classic at both paper widths, checks each carries real ink and saves
     * it for eyeballing - the same guard [BillReceiptRendererTest] puts on Standard,
     * since a layout that measures to nothing prints blank paper.
     */
    @Test
    fun rendersEachPaperWidthNonBlankAndSaves() {
        val report = StringBuilder()
        for ((label, dots) in listOf("58mm" to 384, "80mm" to 576)) {
            val bmp = asClassic(paperDots = dots) { root ->
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
            assertNotNull("Classic render returned null at $label", bmp)
            requireNotNull(bmp)

            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val ink = px.count { (it ushr 24) != 0 && (it and 0x00FFFFFF) != 0x00FFFFFF }

            File(context.filesDir, "classic_$label.png").also { f ->
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            report.append("$label -> ${bmp.width}x${bmp.height}, inkPixels=$ink\n")

            assertTrue("$label Classic render is blank (no ink)", ink > 500)
            assertTrue("$label collapsed to ${bmp.width}x${bmp.height}", bmp.height > bmp.width)
        }
        Log.i("RENDERCHECK", "\nclassic\n$report")
    }
}
