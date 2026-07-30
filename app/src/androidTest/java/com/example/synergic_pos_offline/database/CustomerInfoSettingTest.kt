package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.BillReceiptRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * General Settings' "Customer Info", and the guarantee it is there to make: a sale
 * that captured no customer prints no customer block, whatever Bill Settings would
 * otherwise have shown.
 *
 * The setting itself is read by the till screens, which need a live UI; what is
 * checked here is that it round-trips, that it defaults on, and that a bill written
 * the way those screens write one with capture off really does print nothing.
 */
@RunWith(AndroidJUnit4::class)
class CustomerInfoSettingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db = DatabaseHelper.getInstance(context).writableDatabase
    private val settings = GeneralSettingsDao(context)

    private var customerId = -1L
    private val receipts = mutableListOf<Long>()
    private val original = settings.load()

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

    private fun themed() = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline)

    @After
    fun cleanUp() {
        settings.save(original)
        receipts.forEach { no ->
            db.delete(DatabaseHelper.Tables.TD_CUSTOMER_LEDGER, "bill_id=?", arrayOf(no.toString()))
            db.delete(DatabaseHelper.Tables.TD_PAYMENTS, "bill_id=?", arrayOf(no.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILL_ITEMS, "bill_id=?", arrayOf(no.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILLS, "receipt_no=?", arrayOf(no.toString()))
        }
        if (customerId > 0) {
            db.delete(DatabaseHelper.Tables.MD_CUSTOMERS, "id=?", arrayOf(customerId.toString()))
        }
    }

    // ---- The setting -------------------------------------------------------

    @Test
    fun customerInfoDefaultsOn() {
        // The till behaved this way before the setting existed, so a store that has
        // never touched it must not suddenly stop capturing customers.
        assertTrue("Customer Info should default on", GeneralSettingsDao.GeneralSettings().customerInfo)
    }

    @Test
    fun theSettingRoundTrips() {
        settings.save(original.copy(customerInfo = false))
        assertEquals(false, settings.load().customerInfo)
        settings.save(original.copy(customerInfo = true))
        assertEquals(true, settings.load().customerInfo)
    }

    // ---- What the receipt prints -------------------------------------------

    private fun bill(type: String, custId: Long?, name: String?, phone: String?): Long {
        val result = BillDao(context).createBill(
            BillDao.NewBill(
                billType = type,
                customerId = custId,
                items = listOf(BillDao.Item(productId = null, name = "Test item", quantity = 1.0, rate = 500.0)),
                payment = BillDao.Payment(
                    mode = type,
                    amountPaid = if (type == "CREDIT") 0.0 else 500.0,
                    custName = name, custPhone = phone, custId = custId
                ),
                totalPrice = 500.0, discountAmount = 0.0, discountPercentage = 0.0,
                cgstAmount = 0.0, sgstAmount = 0.0, netAmount = 500.0
            )
        )
        requireNotNull(result) { "createBill returned null" }
        receipts.add(result.receiptNo)
        return result.receiptNo
    }

    /** Which of the customer lines are visible on the rendered slip. */
    private fun customerLines(receiptNo: Long): List<String> = onMain {
        val view = LayoutInflater.from(themed()).inflate(R.layout.fragment_bill, null, false)
        BillReceiptRenderer(themed()).populate(view, receiptNo)
        listOf(R.id.tvCustMobile, R.id.tvName, R.id.tvCustGstin, R.id.tvCustAddress, R.id.tvCustOutstanding)
            .map { view.findViewById<TextView>(it) }
            .filter { it.visibility == View.VISIBLE }
            .map { it.text.toString() }
    }

    /**
     * A cash sale written with capture off - no customer id, no name, no phone.
     * Nothing on the slip should mention a customer.
     */
    @Test
    fun aSaleWithNoCustomerPrintsNoCustomerBlock() {
        val receiptNo = bill("CASH", custId = null, name = null, phone = null)
        val lines = customerLines(receiptNo)
        assertTrue("a sale with no customer printed: $lines", lines.isEmpty())
    }

    /** A credit sale still carries and prints its customer - the stated exception. */
    @Test
    fun aCreditSaleStillPrintsItsCustomer() {
        customerId = db.insert(
            DatabaseHelper.Tables.MD_CUSTOMERS, null,
            ContentValues().apply {
                put("customer_name", "Capture Test")
                put("phone_number", "9000000555")
                put("gstin", "19AAACR1234F1ZQ")
                put("credit_enabled", 1)
                put("credit_limit", 5000.0)
                put("balance_amount", 0.0)
            }
        )
        assertTrue("could not seed the customer", customerId > 0)

        val receiptNo = bill("CREDIT", customerId, "Capture Test", "9000000555")
        val lines = customerLines(receiptNo)

        assertTrue("the credit slip lost its customer: $lines", lines.isNotEmpty())
        assertTrue(
            "the credit slip should carry the outstanding balance: $lines",
            lines.any { it.startsWith("OUTSTANDING") }
        )
    }
}
