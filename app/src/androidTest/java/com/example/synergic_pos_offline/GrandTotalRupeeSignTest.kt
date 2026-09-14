package com.example.synergic_pos_offline

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.ReturnDao
import com.example.synergic_pos_offline.utils.BillReceiptRenderer
import com.example.synergic_pos_offline.utils.PeriodReportRenderer
import com.example.synergic_pos_offline.utils.PrintType
import com.example.synergic_pos_offline.utils.ReturnReceiptRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The figure the customer looks for carries a rupee sign - on every slip, in every
 * bill format, and on that line only.
 *
 * ## Why "on that line only" is half the test
 *
 * A slip is a column of figures under headings that already say what they are, and a
 * currency mark repeated down it is noise that costs a character of paper per line -
 * which on a 58mm roll is a character the item names needed. So the sign is a way of
 * picking the payable figure OUT of that column, and a change that put it on
 * everything would destroy the thing it was added for while still passing any test
 * that only asked "is there a rupee sign".
 *
 * ## Why it is asked of the rendered slip
 *
 * The three bill formats are three different layouts reached by three different
 * branches - Standard builds its NET AMT row in code, Classic and Tax-wise fill a
 * fixed GRAND TOTAL view - so "the renderer does it" is three separate claims. These
 * render each format and read the text back off the view tree.
 *
 * The glyph itself is worth a word: roboto_mono_regular.ttf, which the bill is set
 * in, has no U+20B9 at all. It prints off the platform's fallback face - see
 * [PrintType.grandTotal], where that and what it costs are written down.
 */
@RunWith(AndroidJUnit4::class)
class GrandTotalRupeeSignTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var previousFormat: BillSettingsDao.BillFormat? = null

    private companion object {
        const val PAYABLE = 960.0

        /**
         * A marked figure: the sign and then an amount, and nothing else.
         *
         * The amount itself is deliberately not pinned. What the slip comes to is the
         * till's business - it adds whatever parcel, service and other charges the
         * shop has configured, so a bill drafted at 960 can correctly print 1,104 -
         * and a test that hard-coded the figure would be testing the charge settings
         * of whatever device it happened to run on.
         */
        val MARKED = Regex("""^₹[\d,]+\.\d{2}$""")
    }

    @After
    fun putTheFormatBack() {
        previousFormat?.let { was ->
            val dao = BillSettingsDao(ctx)
            runCatching { dao.save(dao.load().copy(billFormat = was)) }
        }
    }

    // ---- The bill, in each of its three formats ----------------------------------------

    @Test
    fun theStandardBillPrintsTheRupeeSignOnNetAmt() {
        assertTotalCarriesTheSign(BillSettingsDao.BillFormat.STANDARD)
    }

    @Test
    fun theClassicBillPrintsTheRupeeSignOnGrandTotal() {
        assertTotalCarriesTheSign(BillSettingsDao.BillFormat.CLASSIC)
    }

    @Test
    fun theTaxWiseBillPrintsTheRupeeSignOnGrandTotal() {
        assertTotalCarriesTheSign(BillSettingsDao.BillFormat.TAX_WISE_SHORT)
    }

    /**
     * And no other line on the bill carries one.
     *
     * Run on Classic, where the summary above the total is longest - subtotal, each
     * tax rate, the round off - so it is the format with the most lines that could
     * wrongly pick the sign up.
     */
    @Test
    fun noOtherLineOnTheBillCarriesTheSign() {
        val slip = renderBill(BillSettingsDao.BillFormat.CLASSIC)
        val marked = textsOf(slip).filter { it.contains(PrintType.RUPEE) }

        assertEquals(
            "exactly one line may carry the rupee sign, and it is the payable figure. " +
                "Found: $marked",
            1, marked.size
        )
        assertTrue(
            "and that line is a money figure, not a label: was \"${marked.first()}\"",
            MARKED.matches(marked.first().trim())
        )
    }

    /**
     * The sign is attached to the FIGURE, not dropped into the label.
     *
     * Guards the lazy fix - renaming the label to "GRAND TOTAL (₹)" - which reads
     * fine on screen and leaves the amount itself unmarked on the paper.
     */
    @Test
    fun theSignSitsOnTheAmountRatherThanTheLabel() {
        val slip = renderBill(BillSettingsDao.BillFormat.CLASSIC)
        val line = textsOf(slip).first { it.contains(PrintType.RUPEE) }
        assertTrue(
            "the sign should lead the amount: was \"$line\"",
            line.trim().startsWith(PrintType.RUPEE)
        )
    }

    // ---- The other slips ---------------------------------------------------------------

    /**
     * A sale return's REFUND is that slip's payable figure, and carries the sign.
     *
     * The rows above it - gross, discount, each tax - must not, for the same reason
     * they must not on a bill.
     */
    @Test
    fun theReturnSlipMarksTheRefundAndNothingElse() {
        var slip: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val themed = androidx.appcompat.view.ContextThemeWrapper(
                ctx, com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar
            )
            val view = LayoutInflater.from(themed).inflate(R.layout.receipt_return, null, false)
            ReturnReceiptRenderer(themed).populate(view, returnResult(), "TESTER")
            slip = view
        }

        val marked = textsOf(slip!!).filter { it.contains(PrintType.RUPEE) }
        assertEquals(
            "only the refund may be marked. Found: $marked", 1, marked.size
        )

        // The refund as the slip's own summary works it out - asked of ReturnDao
        // rather than written in here, so this states "the marked line is the
        // emphasised one" rather than "the marked line is some number I expected".
        val refund = ReturnDao.summaryRows(returnResult().lines).first { it.emphasis }.value
        assertEquals(
            "the marked figure should be the REFUND row",
            PrintType.grandTotal(String.format(java.util.Locale.US, "%.2f", refund)),
            marked.first().trim()
        )
    }

    /**
     * A printed REPORT marks its total the same way, and marks nothing else.
     *
     * Every period report - bill wise, payment wise, shift wise and the rest - is
     * drawn by this one renderer from a [PeriodReportRenderer.Content], so the
     * summary lines above the total are the risk here: a report states six or seven
     * figures before the one it is read for.
     *
     * The content is built here rather than through a report screen because what is
     * being asked is the renderer's behaviour, and driving a real report would need a
     * till with sales on it - which the roll-over can empty between runs.
     */
    @Test
    fun aPrintedReportMarksOnlyItsTotal() {
        var slip: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val themed = androidx.appcompat.view.ContextThemeWrapper(
                ctx, com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar
            )
            val view = LayoutInflater.from(themed)
                .inflate(R.layout.receipt_period_report, null, false)
            PeriodReportRenderer(themed).populate(
                view,
                PeriodReportRenderer.Content(
                    title = "BILL WISE REPORT",
                    period = "01-09-2026  to  14-09-2026",
                    subtitle = "2 bill(s)",
                    columns = listOf("BILL", "AMOUNT"),
                    rows = listOf(listOf("B-1", "600.00"), listOf("B-2", "360.00")),
                    summary = listOf("TAXABLE AMOUNT :" to "920.00", "GST AMOUNT :" to "40.00"),
                    // What a report fragment now hands over for its grand total.
                    total = "TOTAL AMOUNT :" to PrintType.grandTotal("960.00")
                ),
                "TESTER"
            )
            slip = view
        }

        val marked = textsOf(slip!!).filter { it.contains(PrintType.RUPEE) }
        assertEquals(
            "a report marks its total and nothing above it. Found: $marked",
            1, marked.size
        )
        assertEquals("₹960.00", marked.first().trim())
    }

    // ---- The helper itself -------------------------------------------------------------

    /**
     * The mark is the sign and the amount, with nothing between them.
     *
     * "₹ 960.00" would cost a second character of paper on the one line that is set
     * largest, which is where a 58mm roll runs out of room first.
     */
    @Test
    fun theHelperPrefixesTheSignWithNoSpace() {
        assertEquals("₹960.00", PrintType.grandTotal("960.00"))
        assertEquals("₹", PrintType.RUPEE)
    }

    // ---- Helpers -----------------------------------------------------------------------

    /**
     * Exactly one figure on the slip is marked, and it is the last one down the page.
     *
     * "Last" is what identifies the total without naming its value: the payable is
     * the bottom of the column by construction - the item lines, the subtotal and
     * every tax are worked out before it, and only the cashier and footer lines come
     * after. So a sign that landed on a tax row or an item amount would leave a bare
     * figure below it and fail here, while the amount itself stays the till's
     * business (whatever parcel, service and other charges the shop has configured).
     */
    private fun assertTotalCarriesTheSign(format: BillSettingsDao.BillFormat) {
        val texts = textsOf(renderBill(format))
        val marked = texts.filter { it.contains(PrintType.RUPEE) }

        assertEquals(
            "$format should mark exactly one figure. Lines were: $texts",
            1, marked.size
        )
        assertTrue(
            "$format marked \"${marked.first()}\", which is not a plain money figure",
            MARKED.matches(marked.first().trim())
        )

        val money = Regex("""^[\d,]+\.\d{2}$""")
        val lastMoneyLine = texts.indexOfLast { money.matches(it.trim()) }
        val markedLine = texts.indexOfFirst { it.contains(PrintType.RUPEE) }
        assertTrue(
            "$format marked a figure with bare money still below it, so the sign is " +
                "not on the total. Lines were: $texts",
            markedLine > lastMoneyLine
        )
    }

    /** The bill rendered in [format], as a view tree to read the text back off. */
    private fun renderBill(format: BillSettingsDao.BillFormat): View {
        val dao = BillSettingsDao(ctx)
        if (previousFormat == null) previousFormat = dao.load().billFormat
        dao.save(dao.load().copy(billFormat = format))

        var slip: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val themed = androidx.appcompat.view.ContextThemeWrapper(
                ctx, com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar
            )
            val view = LayoutInflater.from(themed)
                .inflate(BillReceiptRenderer.layoutFor(format), null, false)
            BillReceiptRenderer(themed).populate(view, receiptNo = 0, draft = draft())
            slip = view
        }
        return slip!!
    }

    private fun draft() = BillReceiptRenderer.Draft(
        billNumber = "RUPEE-TEST",
        dateTime = "2026-09-14 12:00:00",
        cashier = "TESTER",
        customer = BillReceiptRenderer.Draft.Customer(),
        items = listOf(
            BillReceiptRenderer.Draft.Item(name = "Test item", quantity = 1.0, rate = PAYABLE)
        ),
        discount = 0.0,
        roundOff = 0.0,
        netAmount = PAYABLE,
        paymentModes = listOf("CASH")
    )

    private fun returnResult() = ReturnDao.Result(
        id = 0,
        returnNumber = "RET-RUPEE",
        dateTime = "2026-09-14 12:00:00",
        originalBillNumber = null,
        lines = emptyList(),
        totalGross = 100.0,
        totalDiscount = 0.0,
        totalCgst = 0.0,
        totalSgst = 0.0,
        totalVat = 0.0,
        totalAmount = 100.0
    )

    /** Every visible piece of text on the slip, in the order it is laid out. */
    private fun textsOf(root: View): List<String> = buildList {
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            when (v) {
                is TextView -> v.text?.toString()?.takeIf { it.isNotBlank() }?.let { add(it) }
                is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
    }
}
