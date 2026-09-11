package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.CustomerLedgerDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A credit sale with cash taken at the counter reads correctly on the ledger.
 *
 * The worked example, which is the whole of it: a customer already owing 500 buys
 * 1,000 of goods and hands over 400. The account must end at 1,100, and the ledger
 * must SHOW the three facts that got it there - 500 brought forward, 1,000 supplied,
 * 400 received.
 *
 * It used to write one line: a DEBIT for the 600 shortfall. The closing balance was
 * right and nothing else was. The sale read as 600, which matched no bill the
 * customer was holding; the 400 they had paid appeared nowhere; and the totals at the
 * foot of the statement counted neither figure. Netting a payment away before writing
 * it down is what makes an account impossible to reconcile against its own bills.
 *
 * One path covers both tills - grocery and restaurant both save through
 * [BillDao.createBill].
 */
@RunWith(AndroidJUnit4::class)
class CreditLedgerPartialPaymentTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    private var customerId: Long = 0
    private var receiptNo: Long = 0

    private companion object {
        const val BROUGHT_FORWARD = 500.0
        const val SALE = 1000.0
        const val PAID_AT_COUNTER = 400.0

        /** 500 + 1000 - 400. */
        const val EXPECTED_CLOSING = 1100.0
    }

    @After
    fun clearUpAfterItself() {
        listOf(
            "DELETE FROM td_customer_ledger WHERE customer_id = $customerId",
            "DELETE FROM td_bill_prints WHERE bill_id = $receiptNo",
            "DELETE FROM td_payments WHERE bill_id = $receiptNo",
            "DELETE FROM td_bill_items WHERE bill_id = $receiptNo",
            "DELETE FROM td_bills WHERE receipt_no = $receiptNo",
            "DELETE FROM md_customers WHERE id = $customerId"
        ).forEach { runCatching { db.execSQL(it) } }
    }

    @Test
    fun theSaleAndThePaymentAreBothOnTheAccount() {
        plantCustomerOwing(BROUGHT_FORWARD)
        sellOnCredit(sale = SALE, paid = PAID_AT_COUNTER)

        // What was written: the goods supplied, and the money taken against them.
        assertEquals("the whole sale should be debited", SALE, ledgerAmount("DEBIT"), 0.005)
        assertEquals(
            "the cash handed over at the counter should be credited",
            PAID_AT_COUNTER, ledgerAmount("CREDIT"), 0.005
        )

        // And the account itself still lands where it always did.
        assertEquals(
            "what the customer owes must be brought forward plus sale less paid",
            EXPECTED_CLOSING, balanceOf(customerId), 0.005
        )
    }

    /**
     * The statement frames it the way a ledger frames one.
     *
     * Opening carries the previous balance in, both movements are listed, and the
     * closing figure reconciles - which is exactly what could not be read off a
     * single netted line.
     */
    @Test
    fun theStatementShowsBroughtForwardSaleAndPayment() {
        plantCustomerOwing(BROUGHT_FORWARD)
        sellOnCredit(sale = SALE, paid = PAID_AT_COUNTER)

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val ledger = CustomerLedgerDao(ctx).forCustomer(customerId, today, today)
        assertNotNull("the customer should have a statement", ledger)

        assertEquals("brought forward", BROUGHT_FORWARD, ledger!!.opening, 0.005)
        assertEquals("supplied on credit in the range", SALE, ledger.totalOut, 0.005)
        assertEquals("collected in the range", PAID_AT_COUNTER, ledger.totalIn, 0.005)
        assertEquals("closing", EXPECTED_CLOSING, ledger.closing, 0.005)

        // The two lines read in order, and the balance column walks with them.
        assertEquals("two movements on the day", 2, ledger.entries.size)
        assertEquals(SALE, ledger.entries[0].out, 0.005)
        assertEquals(
            "the debt after the sale, before anything was paid",
            BROUGHT_FORWARD + SALE, ledger.entries[0].balance, 0.005
        )
        assertEquals(PAID_AT_COUNTER, ledger.entries[1].`in`, 0.005)
        assertEquals(EXPECTED_CLOSING, ledger.entries[1].balance, 0.005)
    }

    /** A credit sale nothing was paid against writes no payment line. */
    @Test
    fun nothingPaidMeansNoPaymentLine() {
        plantCustomerOwing(0.0)
        sellOnCredit(sale = SALE, paid = 0.0)

        assertEquals("the sale is still debited in full", SALE, ledgerAmount("DEBIT"), 0.005)
        assertEquals("but a payment of nothing is not a movement", 0, countOf("CREDIT"))
        assertEquals(SALE, balanceOf(customerId), 0.005)
    }

    // ---- Helpers -------------------------------------------------------------------

    private fun plantCustomerOwing(balance: Double) {
        val phone = "8" + (System.currentTimeMillis() % 1000000000L).toString().padStart(9, '0')
        db.execSQL(
            "INSERT INTO md_customers (customer_name, phone_number, credit_enabled, " +
                "credit_limit, balance_amount) VALUES ('Ledger test', ?, 1, 100000, ?)",
            arrayOf<Any>(phone, balance)
        )
        customerId = db.rawQuery("SELECT last_insert_rowid()", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    private fun sellOnCredit(sale: Double, paid: Double) {
        BillDao(ctx).createBill(
            BillDao.NewBill(
                billType = "CREDIT",
                customerId = customerId,
                items = listOf(
                    BillDao.Item(productId = null, name = "Ledger test item", quantity = 1.0, rate = sale)
                ),
                payment = BillDao.Payment(mode = "CASH", amountPaid = paid, custId = customerId),
                totalPrice = sale,
                discountAmount = 0.0,
                discountPercentage = 0.0,
                cgstAmount = 0.0,
                sgstAmount = 0.0,
                netAmount = sale
            )
        )
        receiptNo = db.rawQuery("SELECT MAX(receipt_no) FROM td_bills", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    private fun ledgerAmount(type: String): Double = db.rawQuery(
        "SELECT COALESCE(SUM(amount), 0) FROM td_customer_ledger " +
            "WHERE customer_id = ? AND transaction_type = ?",
        arrayOf(customerId.toString(), type)
    ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

    private fun countOf(type: String): Int = db.rawQuery(
        "SELECT COUNT(*) FROM td_customer_ledger WHERE customer_id = ? AND transaction_type = ?",
        arrayOf(customerId.toString(), type)
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun balanceOf(id: Long): Double = db.rawQuery(
        "SELECT balance_amount FROM md_customers WHERE id = ?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) c.getDouble(0) else -1.0 }
}
