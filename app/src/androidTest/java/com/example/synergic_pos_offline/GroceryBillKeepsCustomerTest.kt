package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A grocery sale keeps the customer the counter attached to it.
 *
 * The name on a bill comes from one of two places when it is printed or reprinted:
 * `td_bills.customer_id` for somebody on the master, or `td_payments.cust_name` for a
 * walk-in typed at the counter (see BillReceiptRenderer.loadCustomerInfo). A sale that
 * records neither prints no name - not on the slip handed over, and not on any
 * duplicate taken afterwards, because both read the same saved row.
 *
 * That is what a grocery till did whenever General Settings' "Customer Info" was off:
 * the Sale screen still offered Add Customer, the bill PREVIEW still showed the name -
 * it reads CheckoutSession directly and never consulted the setting - and the save
 * then dropped both fields on the floor. The setting governs whether the checkout
 * screen ASKS for customer details; it was being read as permission to discard a
 * customer the operator had already chosen.
 */
@RunWith(AndroidJUnit4::class)
class GroceryBillKeepsCustomerTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    private var receiptNo: Long = 0

    @After
    fun clearUpAfterItself() {
        if (receiptNo <= 0) return
        listOf(
            "DELETE FROM td_bill_prints WHERE bill_id = $receiptNo",
            "DELETE FROM td_customer_ledger WHERE bill_id = $receiptNo",
            "DELETE FROM td_payments WHERE bill_id = $receiptNo",
            "DELETE FROM td_bill_items WHERE bill_id = $receiptNo",
            "DELETE FROM td_bills WHERE receipt_no = $receiptNo"
        ).forEach { runCatching { db.execSQL(it) } }
    }

    /**
     * The saved sale carries the name, so every print of it can.
     *
     * Asserted against the DATABASE rather than a rendered slip, because that is
     * where the loss happened: once the row is written without a customer there is
     * nothing for the original or the duplicate to print, and both fail together.
     */
    @Test
    fun aWalkInCustomersNameSurvivesTheSale() {
        val name = "Counter Customer ${System.currentTimeMillis() % 100000}"
        val phone = "9" + (System.currentTimeMillis() % 1000000000L).toString().padStart(9, '0')

        val result = BillDao(ctx).createBill(
            BillDao.NewBill(
                billType = "CASH",
                // Nobody on the master - exactly the walk-in the counter types in.
                customerId = null,
                items = listOf(
                    BillDao.Item(productId = null, name = "Customer test item", quantity = 1.0, rate = 100.0)
                ),
                payment = BillDao.Payment(
                    mode = "CASH",
                    amountPaid = 100.0,
                    custName = name,
                    custPhone = phone
                ),
                totalPrice = 100.0,
                discountAmount = 0.0,
                discountPercentage = 0.0,
                cgstAmount = 0.0,
                sgstAmount = 0.0,
                netAmount = 100.0
            )
        )
        assertNotNull("the bill should have been written", result)
        receiptNo = receiptNoOf(result!!)

        // What the renderer reads for a walk-in, on the original AND on a duplicate.
        assertEquals(
            "the sale must carry the name it was rung up against",
            name, paymentField("cust_name")
        )
        assertEquals("and the phone with it", phone, paymentField("cust_phone"))
    }

    // ---- Helpers -------------------------------------------------------------------

    /** The receipt number out of whatever shape BillDao.Result carries. */
    private fun receiptNoOf(result: Any): Long =
        db.rawQuery("SELECT MAX(receipt_no) FROM td_bills", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun paymentField(column: String): String? = db.rawQuery(
        "SELECT $column FROM td_payments WHERE bill_id = ? ORDER BY id ASC LIMIT 1",
        arrayOf(receiptNo.toString())
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
}
