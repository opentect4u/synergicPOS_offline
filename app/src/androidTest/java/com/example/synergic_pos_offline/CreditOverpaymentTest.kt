package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.CustomerDao
import com.example.synergic_pos_offline.database.CustomerLedgerDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A customer paying MORE than the bill is paying down what they already owed.
 *
 * The case, as it happens at a counter: the customer owes 1,000 from before, buys 960
 * of goods on their account, and hands over 5,000. Three figures have to move - the
 * sale goes on, the money comes off, and the 3,040 left over stays with the shop as
 * credit against their next visit.
 *
 *     1,000  +  960  -  5,000  =  -3,040
 *
 * None of it moved. The sale only reached the ledger when it left a SHORTFALL, so a
 * payment that covered the bill skipped the account entirely; and the payment was
 * capped at the bill before being written, so even had it got there the excess would
 * have been discarded. The customer walked away with the till still showing the
 * original 1,000 against them, and the slip in their hand printing a PREVI BALANCE of
 * -5,040 - a figure worked backwards from an account that had not been updated.
 *
 * The collection screen has always taken an over-payment properly (see
 * AdvancePaymentDao.collect - "the balance simply carries on down through zero"). It
 * was billing that disagreed with it.
 */
@RunWith(AndroidJUnit4::class)
class CreditOverpaymentTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    private var customerId = 0L
    private val bills = mutableListOf<Long>()

    private companion object {
        const val PHONE = "9800000042"
        const val BROUGHT_FORWARD = 1000.0
        const val BILL = 960.0
        const val HANDED_OVER = 5000.0
        const val LIMIT = 20000.0
    }

    @Before
    fun plantACustomerWhoAlreadyOwes() {
        customerId = CustomerDao(ctx).insert(
            CustomerDao.Customer(
                id = 0L, name = "Overpay customer", address = "", phone = PHONE, gstin = "",
                creditEnabled = true, creditLimit = LIMIT, balance = BROUGHT_FORWARD
            )
        )
        assertTrue("the customer should have been planted", customerId > 0)
    }

    @After
    fun clearUpAfterItself() {
        bills.forEach { receiptNo ->
            listOf(
                "DELETE FROM td_customer_ledger WHERE bill_id = $receiptNo",
                "DELETE FROM td_payments WHERE bill_id = $receiptNo",
                "DELETE FROM td_bill_items WHERE bill_id = $receiptNo",
                "DELETE FROM td_bills WHERE receipt_no = $receiptNo"
            ).forEach { runCatching { db.execSQL(it) } }
        }
        runCatching { db.execSQL("DELETE FROM td_customer_ledger WHERE customer_id = $customerId") }
        runCatching { CustomerDao(ctx).delete(listOf(customerId)) }
    }

    // ---- The account -----------------------------------------------------------------

    /**
     * The balance carries on down through zero and lands on what the shop owes back.
     *
     * The single figure everything else on this page is a restatement of. Positive on
     * the master means the customer owes; below zero means they are in credit.
     */
    @Test
    fun payingOverTheBillLeavesTheCustomerInCredit() {
        sell(paid = HANDED_OVER)

        assertEquals(
            "1000 owed + 960 billed - 5000 paid",
            -3040.0, balanceOnFile(), 0.005
        )
    }

    /**
     * Both movements are written down, at their full size.
     *
     * Not one netted line: the ledger is a record of what happened, and what happened
     * was a 960 sale and a 5,000 payment. Netting them to "3,040 credit" would leave
     * an account that cannot be reconciled against the bill the customer is holding.
     */
    @Test
    fun theSaleAndThePaymentAreBothOnTheLedgerInFull() {
        val receiptNo = sell(paid = HANDED_OVER)

        val lines = ledgerLines(receiptNo)
        assertEquals("one line for the goods, one for the money", 2, lines.size)

        val debit = lines.first { it.first == "DEBIT" }
        val credit = lines.first { it.first == "CREDIT" }
        assertEquals("the whole sale went on the account", BILL, debit.second, 0.005)
        assertEquals(
            "and the whole payment came off it - not just the part covering the bill",
            HANDED_OVER, credit.second, 0.005
        )
    }

    /**
     * The stored balance column reads in order: up by the sale, then down by the money.
     */
    @Test
    fun theBalanceColumnWalksThroughTheSaleAndThePayment() {
        val receiptNo = sell(paid = HANDED_OVER)
        val lines = ledgerLines(receiptNo)

        assertEquals(
            "after the goods: 1000 + 960",
            1960.0, lines.first { it.first == "DEBIT" }.third, 0.005
        )
        assertEquals(
            "after the money: 1960 - 5000",
            -3040.0, lines.first { it.first == "CREDIT" }.third, 0.005
        )
    }

    /**
     * Paying does not buy the customer a bigger credit limit.
     *
     * The limit comes down by what is PUT ON the account, and an over-payment puts
     * nothing on it - so the limit must sit still. Subtracting the negative shortfall
     * would have grown it by 4,040, and since the limit as originally granted is
     * recorded nowhere, there would be no way to give it back.
     */
    @Test
    fun anOverPaymentDoesNotInflateTheCreditLimit() {
        sell(paid = HANDED_OVER)
        assertEquals("the limit the shop granted, untouched", LIMIT, limitOnFile(), 0.005)
    }

    /** The bill itself is settled, and says so. */
    @Test
    fun theBillIsRecordedAsFullySettled() {
        val receiptNo = sell(paid = HANDED_OVER)
        db.rawQuery(
            "SELECT balance_amount, payment_status FROM td_payments WHERE bill_id = ?",
            arrayOf(receiptNo.toString())
        ).use { c ->
            assertTrue("the payment row should exist", c.moveToFirst())
            assertEquals("nothing is owed on THIS bill", 0.0, c.getDouble(0), 0.005)
            assertEquals("COMPLETED", c.getString(1))
        }
    }

    // ---- The report --------------------------------------------------------------------

    /**
     * The Customer Ledger states the same account, and its four figures reconcile.
     *
     * opening + out - in = closing. The report walks its own running balance rather
     * than trusting the stored column, so this is a genuinely separate statement of
     * the same arithmetic rather than a re-read of it.
     */
    @Test
    fun theLedgerReportReconciles() {
        sell(paid = HANDED_OVER)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        val ledger = CustomerLedgerDao(ctx).forCustomer(customerId, today, today)!!

        assertEquals("what they owed before any of this", BROUGHT_FORWARD, ledger.opening, 0.005)
        assertEquals("put on account today", BILL, ledger.totalOut, 0.005)
        assertEquals("collected today", HANDED_OVER, ledger.totalIn, 0.005)
        assertEquals("and where that leaves them", -3040.0, ledger.closing, 0.005)
        assertEquals(
            "the four figures must add up",
            ledger.closing,
            ledger.opening + ledger.totalOut - ledger.totalIn,
            0.005
        )
    }

    // ---- The slip ----------------------------------------------------------------------

    /**
     * The printed slip's four lines come back out of the account.
     *
     * The renderer does not store PREVI BALANCE - it is handed the closing balance and
     * works backwards, `previ = total + bill - cash`, in the customer's own sign
     * convention (positive = in credit). So an account left unbooked does not merely
     * misprint one line; it moves the previous balance by the whole payment, which is
     * how a 1,000 debt came to print as -5,040.
     */
    @Test
    fun theSlipsOwnArithmeticGivesBackTheRealPreviousBalance() {
        sell(paid = HANDED_OVER)

        // Exactly what BillReceiptRenderer computes from md_customers.balance_amount.
        val totalBalance = -balanceOnFile()
        val previBalance = totalBalance + BILL - HANDED_OVER

        assertEquals("TOTAL BALANCE - they are 3,040 in credit", 3040.0, totalBalance, 0.005)
        assertEquals("PREVI BALANCE - they owed 1,000", -BROUGHT_FORWARD, previBalance, 0.005)
    }

    // ---- Guards ------------------------------------------------------------------------

    /**
     * A credit bill settled EXACTLY still books both movements.
     *
     * The other case the shortfall gate dropped. The balance is unchanged either way,
     * which is why it went unnoticed - but with nothing written down, neither the sale
     * nor the payment appeared on the customer's statement at all.
     */
    @Test
    fun aCreditBillPaidExactlyIsStillRecorded() {
        val receiptNo = sell(paid = BILL)

        assertEquals("two movements that cancel out", 2, ledgerLines(receiptNo).size)
        assertEquals(
            "so the balance is exactly where it started",
            BROUGHT_FORWARD, balanceOnFile(), 0.005
        )
    }

    /** And a part-payment still behaves as it always did. */
    @Test
    fun aPartPaymentStillLeavesTheRestOnTheAccount() {
        sell(paid = 400.0)
        assertEquals("1000 + 960 - 400", 1560.0, balanceOnFile(), 0.005)
    }

    /**
     * Cash over the bill is CHANGE, not a payment on account.
     *
     * The guard on the widened gate: handing over 5,000 for a 960 cash sale gets 4,040
     * back in the hand. Booking that onto the account would credit the customer with
     * money they walked out with.
     */
    @Test
    fun moneyOverTheBillOnACASHSaleIsChangeAndNeverTouchesTheAccount() {
        val receiptNo = sell(paid = HANDED_OVER, mode = "CASH")

        assertEquals("a cash sale does not touch the ledger", 0, ledgerLines(receiptNo).size)
        assertEquals("nor the balance", BROUGHT_FORWARD, balanceOnFile(), 0.005)
    }

    // ---- Helpers -----------------------------------------------------------------------

    /** Books one bill of [BILL] to the planted customer, taking [paid] at the counter. */
    private fun sell(paid: Double, mode: String = "CREDIT"): Long {
        val result = BillDao(ctx).createBill(
            BillDao.NewBill(
                billType = mode,
                customerId = customerId,
                items = listOf(
                    BillDao.Item(productId = null, name = "Credit item", quantity = 1.0, rate = BILL)
                ),
                payment = BillDao.Payment(
                    mode = mode,
                    amountPaid = paid,
                    // As the till works it out: change on a cash sale, none on credit.
                    changeAmount = if (mode == "CASH") (paid - BILL).coerceAtLeast(0.0) else 0.0,
                    custName = "Overpay customer",
                    custPhone = PHONE,
                    custId = customerId
                ),
                totalPrice = BILL,
                discountAmount = 0.0,
                discountPercentage = 0.0,
                cgstAmount = 0.0,
                sgstAmount = 0.0,
                netAmount = BILL
            )
        ) ?: error("the bill should have been written")
        bills.add(result.receiptNo)
        return result.receiptNo
    }

    /** Type, amount and balance-after, for every ledger line this bill wrote. */
    private fun ledgerLines(receiptNo: Long): List<Triple<String, Double, Double>> =
        db.rawQuery(
            "SELECT transaction_type, amount, balance FROM td_customer_ledger " +
                "WHERE bill_id = ? ORDER BY id ASC",
            arrayOf(receiptNo.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Triple(c.getString(0), c.getDouble(1), c.getDouble(2)))
            }
        }

    private fun balanceOnFile(): Double = CustomerDao(ctx).findById(customerId)!!.balance

    private fun limitOnFile(): Double = CustomerDao(ctx).findById(customerId)!!.creditLimit
}
