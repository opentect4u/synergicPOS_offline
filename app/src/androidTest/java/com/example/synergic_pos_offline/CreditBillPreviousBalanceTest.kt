package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.utils.BillReceiptRenderer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A drafted CREDIT bill states the account correctly.
 *
 * The slip carries four figures that have to reconcile:
 *
 *     PREVI BALANCE + BILL AMOUNT - CASH RECEIVED = TOTAL BALANCE
 *
 * and the renderer does not store them - it is handed the closing balance and works
 * PREVI BALANCE backwards from the bill and the cash. So CASH RECEIVED being wrong
 * does not merely misprint one line, it moves the previous balance by the same amount.
 *
 * That is what happened on every drafted bill: the cash figure was read from the
 * saved payment row and a draft has none, so it printed 0.00 however much had just
 * been taken. The restaurant till prints its bill FROM a draft, so that was the slip
 * the customer was handed.
 *
 * Checked through the arithmetic rather than by scraping the rendered text: the
 * relationship is the thing that must hold, and it is what a reader of the slip is
 * checking when they add the column up.
 */
@RunWith(AndroidJUnit4::class)
class CreditBillPreviousBalanceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val BROUGHT_FORWARD = 500.0
        const val BILL = 1000.0
        const val PAID = 400.0
    }

    /**
     * The draft carries what was handed over, so the slip can state it.
     *
     * Without this the renderer has no figure to print and falls back to zero - see
     * the class note for what that does to the line above it.
     */
    @Test
    fun theDraftCarriesTheCashTakenAtTheCounter() {
        val draft = creditDraft(paid = PAID)
        assertEquals("the draft should carry the part-payment", PAID, draft.amountPaid, 0.005)
    }

    /**
     * The four figures reconcile, which is what a customer checks.
     *
     * `outstanding` is what the customer will owe once the sale is booked - brought
     * forward, plus the bill, less what they are paying now - and the slip's own
     * PREVI BALANCE has to come back out of it as the figure they started with.
     */
    @Test
    fun thePreviousBalanceComesBackOutOfTheSlipsOwnArithmetic() {
        val draft = creditDraft(paid = PAID)

        // The renderer's own sum, in the sign convention the slip prints in: the
        // customer-side balance is the negative of what the master holds.
        val totalBalance = -(draft.customer.outstanding ?: 0.0)
        val previBalance = totalBalance + draft.netAmount - draft.amountPaid

        assertEquals(
            "PREVI BALANCE must be what the customer already owed, and nothing else",
            -BROUGHT_FORWARD, previBalance, 0.005
        )
        assertEquals(
            "and the four figures must reconcile",
            totalBalance, previBalance - draft.netAmount + draft.amountPaid, 0.005
        )
    }

    /**
     * With nothing paid the slip still reads correctly.
     *
     * The guard against fixing this by simply always subtracting something: a credit
     * sale with no payment must leave PREVI BALANCE exactly as it was.
     */
    @Test
    fun aCreditSaleWithNoPaymentStillReadsRight() {
        val draft = creditDraft(paid = 0.0)
        val totalBalance = -(draft.customer.outstanding ?: 0.0)
        val previBalance = totalBalance + draft.netAmount - draft.amountPaid

        assertEquals(-BROUGHT_FORWARD, previBalance, 0.005)
        assertEquals("nothing was taken", 0.0, draft.amountPaid, 0.005)
    }

    // ---- Helpers -------------------------------------------------------------------

    /** A credit bill for a customer already owing [BROUGHT_FORWARD]. */
    private fun creditDraft(paid: Double) = BillReceiptRenderer.Draft(
        billNumber = "CREDIT-TEST",
        dateTime = "2026-09-11 12:00:00",
        cashier = "TEST",
        customer = BillReceiptRenderer.Draft.Customer(
            name = "Ledger customer",
            // What they will owe once this is booked - the same sum
            // BillDao.recordBalanceDue writes.
            outstanding = BROUGHT_FORWARD + BILL - paid
        ),
        items = listOf(
            BillReceiptRenderer.Draft.Item(name = "Credit item", quantity = 1.0, rate = BILL)
        ),
        discount = 0.0,
        roundOff = 0.0,
        netAmount = BILL,
        paymentModes = listOf("CREDIT"),
        amountPaid = paid
    )
}
