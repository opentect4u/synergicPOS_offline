package com.example.synergic_pos_offline.database

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The four figures a ledger is judged on have to reconcile: opening plus what went
 * out, less what came in, must land exactly on closing - and closing must agree
 * with the balance the rest of the app bills against.
 *
 * The history is built through the real DAOs and then backdated, so the movements
 * are the ones a till would actually have written.
 */
@RunWith(AndroidJUnit4::class)
class CustomerLedgerDaoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val helper = DatabaseHelper.getInstance(context)
    private val db = helper.writableDatabase

    private val phone = "9000000777"
    private var customerId = -1L
    private val receipts = mutableListOf<Long>()

    @Before
    fun seed() {
        customerId = db.insert(
            DatabaseHelper.Tables.MD_CUSTOMERS, null,
            ContentValues().apply {
                put("customer_name", "Ledger Test")
                put("phone_number", phone)
                put("credit_enabled", 1)
                put("credit_limit", 10000.0)
                put("balance_amount", 0.0)
            }
        )
        assertTrue("could not seed the customer", customerId > 0)

        // June: a 500 credit sale, nothing paid at the till.
        val sale = BillDao(context).createBill(
            BillDao.NewBill(
                billType = "CREDIT",
                customerId = customerId,
                items = listOf(BillDao.Item(productId = null, name = "Goods", quantity = 1.0, rate = 500.0)),
                payment = BillDao.Payment(mode = "CREDIT", amountPaid = 0.0, custId = customerId),
                totalPrice = 500.0, discountAmount = 0.0, discountPercentage = 0.0,
                cgstAmount = 0.0, sgstAmount = 0.0, netAmount = 500.0
            )
        )
        requireNotNull(sale) { "createBill returned null" }
        receipts.add(sale.receiptNo)
        backdate("DEBIT", "2026-06-10 11:00:00")

        // July: 200 of it collected.
        requireNotNull(AdvancePaymentDao(context).collect(customerId, 200.0, "CASH")) {
            "collect returned null"
        }
        backdate("CREDIT", "2026-07-15 16:30:00")
    }

    /** Moves the newest [type] line onto [dateTime], standing in for history. */
    private fun backdate(type: String, dateTime: String) {
        db.execSQL(
            """
            UPDATE ${DatabaseHelper.Tables.TD_CUSTOMER_LEDGER}
            SET transaction_date = ?
            WHERE id = (SELECT MAX(id) FROM ${DatabaseHelper.Tables.TD_CUSTOMER_LEDGER}
                        WHERE customer_id = ? AND transaction_type = ?)
            """.trimIndent(),
            arrayOf(dateTime, customerId.toString(), type)
        )
    }

    @After
    fun cleanUp() {
        db.delete(DatabaseHelper.Tables.TD_CUSTOMER_LEDGER, "customer_id=?", arrayOf(customerId.toString()))
        db.delete(DatabaseHelper.Tables.TD_ADVANCE_PAYMENTS, "customer_id=?", arrayOf(customerId.toString()))
        receipts.forEach { no ->
            db.delete(DatabaseHelper.Tables.TD_PAYMENTS, "bill_id=?", arrayOf(no.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILL_ITEMS, "bill_id=?", arrayOf(no.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILLS, "receipt_no=?", arrayOf(no.toString()))
        }
        db.delete(DatabaseHelper.Tables.MD_CUSTOMERS, "id=?", arrayOf(customerId.toString()))
    }

    /** The account after both movements: 500 billed, 200 collected. */
    @Test
    fun theMasterCarriesTheRunningBalance() {
        val balance = db.rawQuery(
            "SELECT balance_amount FROM ${DatabaseHelper.Tables.MD_CUSTOMERS} WHERE id = ?",
            arrayOf(customerId.toString())
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else -1.0 }
        assertEquals(300.0, balance, 0.001)
    }

    /**
     * July only. The June sale is before the range, so it belongs to the opening
     * balance rather than to a line in it.
     */
    @Test
    fun aRangeOpensOnWhatWasAlreadyOwed() {
        val ledger = CustomerLedgerDao(context).forPhone(phone, "2026-07-01", "2026-07-31")
        requireNotNull(ledger) { "no ledger for $phone" }

        assertEquals("opening", 500.0, ledger.opening, 0.001)
        assertEquals("in", 200.0, ledger.totalIn, 0.001)
        assertEquals("out", 0.0, ledger.totalOut, 0.001)
        assertEquals("closing", 300.0, ledger.closing, 0.001)
        assertEquals("one movement in July", 1, ledger.entries.size)
        assertEquals(200.0, ledger.entries[0].`in`, 0.001)
        assertEquals("balance after the collection", 300.0, ledger.entries[0].balance, 0.001)
    }

    /**
     * June only. The July collection lands after the range, so it must be wound back
     * out of today's balance to reach the closing figure for June.
     */
    @Test
    fun aClosedRangeIgnoresLaterMovements() {
        val ledger = CustomerLedgerDao(context).forPhone(phone, "2026-06-01", "2026-06-30")
        requireNotNull(ledger) { "no ledger for $phone" }

        assertEquals("opening", 0.0, ledger.opening, 0.001)
        assertEquals("in", 0.0, ledger.totalIn, 0.001)
        assertEquals("out", 500.0, ledger.totalOut, 0.001)
        assertEquals("closing", 500.0, ledger.closing, 0.001)
        assertEquals("one movement in June", 1, ledger.entries.size)
        assertEquals(500.0, ledger.entries[0].out, 0.001)
        assertEquals("balance after the sale", 500.0, ledger.entries[0].balance, 0.001)
    }

    /** Whatever the range, the four figures have to add up. */
    @Test
    fun theFiguresReconcileAcrossEveryRange() {
        val dao = CustomerLedgerDao(context)
        listOf(
            "2026-06-01" to "2026-06-30",
            "2026-07-01" to "2026-07-31",
            "2026-06-01" to "2026-07-31",
            "2026-05-01" to "2026-05-31"
        ).forEach { (from, to) ->
            val led = dao.forPhone(phone, from, to)
            requireNotNull(led) { "no ledger for $from..$to" }
            assertEquals(
                "opening + out - in != closing for $from..$to",
                led.closing,
                led.opening + led.totalOut - led.totalIn,
                0.001
            )
            // And the last line's running balance is the closing figure.
            led.entries.lastOrNull()?.let {
                assertEquals("last line != closing for $from..$to", led.closing, it.balance, 0.001)
            }
        }
    }

    /** A number nobody is registered against has no account to report. */
    @Test
    fun anUnknownPhoneHasNoLedger() {
        assertEquals(null, CustomerLedgerDao(context).forPhone("0000000000", "2026-01-01", "2026-12-31"))
    }
}
