package com.example.synergic_pos_offline

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.BillReceiptRenderer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A cancelled bill can still be put on paper, and the paper says it is cancelled.
 *
 * Bill History's list greyed its Print button out on a cancelled bill, on the
 * reasoning that there was nothing left to reproduce. There is: the slip is exactly
 * what gets asked for when a customer comes back about a sale that was voided, and
 * the bill SCREEN had always allowed it - only the list refused.
 *
 * Enabling a button is worth nothing if it prints an empty slip, and a cancelled bill
 * is the awkward case: it has left `td_bills` entirely for the archive, so every read
 * behind the renderer has to look somewhere else. That is what these ask - not "is
 * the button enabled" but "is there anything behind it".
 *
 * The caption matters as much as the content. A reprint that came out looking like an
 * ordinary bill would be a voided sale in a customer's hand with nothing on it saying
 * so, which is worse than not printing at all.
 */
@RunWith(AndroidJUnit4::class)
class CancelledBillStillPrintsTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    private var receiptNo = 0L

    private companion object {
        const val ITEM = "Sample line"
        const val BILL = 250.0
    }

    @After
    fun clearUpAfterItself() {
        listOf(
            "DELETE FROM td_bill_prints WHERE bill_id = $receiptNo",
            "DELETE FROM td_payments WHERE bill_id = $receiptNo",
            "DELETE FROM td_bill_items WHERE bill_id = $receiptNo",
            "DELETE FROM td_bills WHERE receipt_no = $receiptNo",
            "DELETE FROM td_bill_items_delete WHERE bill_id = $receiptNo",
            "DELETE FROM td_bills_delete WHERE receipt_no = $receiptNo"
        ).forEach { runCatching { db.execSQL(it) } }
    }

    /**
     * The slip still carries the sale: its number, and the line it sold.
     *
     * Read back off the rendered view, because the claim is about what comes out of
     * the printer, not about which table a query happened to name.
     */
    @Test
    fun aCancelledBillStillRendersItsOwnContents() {
        val billNo = sellAndCancel()

        val texts = textsOf(render())

        assertTrue(
            "the slip should carry the bill number. Lines were: $texts",
            texts.any { it.contains(billNo) }
        )
        assertTrue(
            "and the item it sold",
            texts.any { it.contains(ITEM, ignoreCase = true) }
        )
    }

    /**
     * And it says CANCELLED, on a till that has configured no captions at all.
     *
     * The captions master is empty until somebody opens that screen, so a slip that
     * only said "cancelled" when the shop had set a caption up would say nothing on
     * most tills - which is the case that matters.
     */
    @Test
    fun theSlipSaysItIsCancelled() {
        sellAndCancel()

        val texts = textsOf(render())
        assertTrue(
            "a cancelled bill's slip must say so. Lines were: $texts",
            texts.any { it.contains("Cancelled Bill", ignoreCase = true) }
        )
    }

    /**
     * A live bill is NOT captioned cancelled.
     *
     * The guard: captioning everything "Cancelled Bill" would pass the test above and
     * put the word on every ordinary sale.
     */
    @Test
    fun anOrdinaryBillIsNotCaptionedCancelled() {
        receiptNo = sell()

        val texts = textsOf(render())
        assertFalse(
            "a live sale must not be captioned cancelled. Lines were: $texts",
            texts.any { it.contains("Cancelled Bill", ignoreCase = true) }
        )
    }

    // ---- Helpers -----------------------------------------------------------------------

    /** Sells a bill, cancels it into the archive, and returns its number. */
    private fun sellAndCancel(): String {
        receiptNo = sell()
        val billNo = BillDao(ctx).getAll().first { it.receiptNo == receiptNo }.billNo
        val result = BillDao(ctx).cancelBill(receiptNo)
        assertTrue("the bill should cancel: ${result.refusal}", result.ok)
        return billNo
    }

    private fun sell(): Long = (BillDao(ctx).createBill(
        BillDao.NewBill(
            billType = "CASH",
            customerId = null,
            items = listOf(
                BillDao.Item(productId = null, name = ITEM, quantity = 1.0, rate = BILL)
            ),
            payment = BillDao.Payment(mode = "CASH", amountPaid = BILL),
            totalPrice = BILL,
            discountAmount = 0.0,
            discountPercentage = 0.0,
            cgstAmount = 0.0,
            sgstAmount = 0.0,
            netAmount = BILL
        )
    ) ?: error("the bill should have been written")).receiptNo

    /** The bill rendered the way the printer renders it, as a view tree. */
    private fun render(): View {
        var slip: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val themed = androidx.appcompat.view.ContextThemeWrapper(
                ctx, com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar
            )
            val view = LayoutInflater.from(themed)
                .inflate(BillReceiptRenderer.layoutFor(themed), null, false)
            BillReceiptRenderer(themed).populate(view, receiptNo)
            slip = view
        }
        return slip!!
    }

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
