package com.example.synergic_pos_offline.utils

import android.content.ContentValues
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A credit bill is collected later, so the slip has to tell the customer what they
 * now owe in total - this sale on top of whatever was already on their account.
 *
 * Booked through the real [BillDao] rather than by writing the tables directly, so
 * the figure the receipt prints is checked against the one the sale actually left
 * on `md_customers`.
 */
@RunWith(AndroidJUnit4::class)
class CreditBillOutstandingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db = DatabaseHelper.getInstance(context).writableDatabase

    private var customerId = -1L
    private var receiptNo = -1L

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

    @After
    fun cleanUp() {
        // Deepest reference first: a ledger line points at the payment row, which
        // points at the bill, so unwinding in any other order trips a foreign key.
        if (receiptNo > 0) {
            db.delete(DatabaseHelper.Tables.TD_CUSTOMER_LEDGER, "bill_id=?", arrayOf(receiptNo.toString()))
            db.delete(DatabaseHelper.Tables.TD_PAYMENTS, "bill_id=?", arrayOf(receiptNo.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILL_ITEMS, "bill_id=?", arrayOf(receiptNo.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILLS, "receipt_no=?", arrayOf(receiptNo.toString()))
        }
        if (customerId > 0) {
            db.delete(DatabaseHelper.Tables.MD_CUSTOMERS, "id=?", arrayOf(customerId.toString()))
        }
    }

    @Test
    fun creditReceiptStatesWhatTheCustomerNowOwes() {
        // On file already: 1000 owed, 5000 of credit left.
        customerId = db.insert(
            DatabaseHelper.Tables.MD_CUSTOMERS, null,
            ContentValues().apply {
                put("customer_name", "Outstanding Test")
                put("phone_number", "9000000001")
                put("credit_enabled", 1)
                put("credit_limit", 5000.0)
                put("balance_amount", 1000.0)
            }
        )
        assertTrue("could not seed the customer", customerId > 0)

        // A 500 credit sale with 200 taken at the till leaves 300 on the account.
        val result = BillDao(context).createBill(
            BillDao.NewBill(
                billType = "CREDIT",
                customerId = customerId,
                items = listOf(BillDao.Item(productId = null, name = "Test item", quantity = 1.0, rate = 500.0)),
                payment = BillDao.Payment(mode = "CREDIT", amountPaid = 200.0, custId = customerId),
                totalPrice = 500.0,
                discountAmount = 0.0,
                discountPercentage = 0.0,
                cgstAmount = 0.0,
                sgstAmount = 0.0,
                netAmount = 500.0
            )
        )
        requireNotNull(result) { "createBill returned null" }
        receiptNo = result.receiptNo

        val stored = db.rawQuery(
            "SELECT balance_amount FROM ${DatabaseHelper.Tables.MD_CUSTOMERS} WHERE id = ?",
            arrayOf(customerId.toString())
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else -1.0 }
        assertEquals("the sale did not book the balance", 1300.0, stored, 0.001)

        val themed = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline)
        val printed = onMain {
            val root = LayoutInflater.from(themed).inflate(R.layout.fragment_bill, null, false)
            BillReceiptRenderer(themed).populate(root, receiptNo)
            val tv = root.findViewById<TextView>(R.id.tvCustOutstanding)
            if (tv.visibility != View.VISIBLE) "<hidden>" else tv.text.toString()
        }

        assertEquals("OUTSTANDING: 1300.00", printed)

        // Saved for eyeballing: the line has to sit in the customer block, not
        // wander off into the totals.
        onMain { BillReceiptRenderer(themed).renderToBitmap(receiptNo, 576) }?.let { bmp ->
            java.io.FileOutputStream(java.io.File(context.filesDir, "credit_bill_receipt.png")).use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test
    fun aCashSaleLeavesTheOutstandingLineOff() {
        customerId = db.insert(
            DatabaseHelper.Tables.MD_CUSTOMERS, null,
            ContentValues().apply {
                put("customer_name", "Cash Test")
                put("phone_number", "9000000002")
                put("credit_enabled", 1)
                put("credit_limit", 5000.0)
                put("balance_amount", 1000.0)
            }
        )

        val result = BillDao(context).createBill(
            BillDao.NewBill(
                billType = "CASH",
                customerId = customerId,
                items = listOf(BillDao.Item(productId = null, name = "Test item", quantity = 1.0, rate = 500.0)),
                payment = BillDao.Payment(mode = "CASH", amountPaid = 500.0, custId = customerId),
                totalPrice = 500.0,
                discountAmount = 0.0,
                discountPercentage = 0.0,
                cgstAmount = 0.0,
                sgstAmount = 0.0,
                netAmount = 500.0
            )
        )
        requireNotNull(result) { "createBill returned null" }
        receiptNo = result.receiptNo

        val visible = onMain {
            val root = LayoutInflater.from(themedContext()).inflate(R.layout.fragment_bill, null, false)
            BillReceiptRenderer(themedContext()).populate(root, receiptNo)
            root.findViewById<TextView>(R.id.tvCustOutstanding).visibility == View.VISIBLE
        }

        assertTrue("a settled sale should not print an outstanding line", !visible)
    }

    private fun themedContext() = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline)
}
