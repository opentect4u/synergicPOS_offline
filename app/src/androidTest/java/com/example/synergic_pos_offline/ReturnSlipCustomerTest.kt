package com.example.synergic_pos_offline

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.ReturnDao
import com.example.synergic_pos_offline.utils.ReturnReceiptRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A sale return names the customer it went back to.
 *
 * The slip already said who processed it ("Returned by"), but nothing at all about
 * who received the money - so a return could not be matched to its customer from the
 * paper, while the bill it came off named them plainly.
 *
 * The return row itself records what came back, not who brought it, so the customer
 * is followed through the ORIGINAL BILL - its `customer_id`, or the payment row's own
 * `cust_name` for a walk-in typed at the counter. Both places the bill reads, so the
 * two slips name the same person.
 */
@RunWith(AndroidJUnit4::class)
class ReturnSlipCustomerTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    private var receiptNo: Long = 0
    private var returnId: Long = 0
    private var previousDetails: BillSettingsDao.CustomerDetails? = null

    private companion object {
        const val NAME = "Return Customer"
        const val PHONE = "9812345670"
    }

    @Before
    fun printNameAndMobile() {
        val dao = BillSettingsDao(ctx)
        previousDetails = dao.load().customerDetails
        dao.save(dao.load().copy(customerDetails = BillSettingsDao.CustomerDetails.MOBILE_NAME))
    }

    @After
    fun clearUpAfterItself() {
        previousDetails?.let { was ->
            val dao = BillSettingsDao(ctx)
            runCatching { dao.save(dao.load().copy(customerDetails = was)) }
        }
        listOf(
            "DELETE FROM td_return_items WHERE return_id = $returnId",
            "DELETE FROM td_sale_returns WHERE id = $returnId",
            "DELETE FROM td_payments WHERE bill_id = $receiptNo",
            "DELETE FROM td_bill_items WHERE bill_id = $receiptNo",
            "DELETE FROM td_bills WHERE receipt_no = $receiptNo"
        ).forEach { runCatching { db.execSQL(it) } }
    }

    /**
     * A walk-in typed at the counter reaches the return slip.
     *
     * The harder of the two routes: there is no row in the customer master to join
     * to, so the name has to come off the sale's own payment row - the fallback the
     * bill has always used and the return never did.
     */
    @Test
    fun theCustomerFromTheOriginalBillIsPrinted() {
        plantBillWithWalkInCustomer()
        plantReturnAgainstTheBill()

        val slip = render()

        assertEquals(
            "the name should be on the slip",
            "NAME: ${NAME.uppercase()}", textOf(slip, R.id.tvReturnCustName)
        )
        assertEquals(
            "and the mobile with it",
            "MOBILE: $PHONE", textOf(slip, R.id.tvReturnCustMobile)
        )
        assertEquals(
            "GSTIN was not captured, so it stays off",
            View.GONE, slip.findViewById<View>(R.id.tvReturnCustGstin).visibility
        )
    }

    /**
     * "Customer Details" governs the return exactly as it governs the bill.
     *
     * Set to mobile only, the name must not print - otherwise the two slips disagree
     * about a setting the shop set once.
     */
    @Test
    fun theSettingDecidesWhichLinesPrint() {
        val dao = BillSettingsDao(ctx)
        dao.save(dao.load().copy(customerDetails = BillSettingsDao.CustomerDetails.ONLY_MOBILE))

        plantBillWithWalkInCustomer()
        plantReturnAgainstTheBill()

        val slip = render()
        assertEquals(
            "only the mobile was asked for",
            View.GONE, slip.findViewById<View>(R.id.tvReturnCustName).visibility
        )
        assertTrue(
            "and the mobile itself should be there",
            textOf(slip, R.id.tvReturnCustMobile).contains(PHONE)
        )
    }

    /**
     * An item-wise return has no bill, so it names nobody - and does not crash.
     *
     * The case that would otherwise have been found in a shop: the lookup joins
     * through `original_bill_id`, which is null here.
     */
    @Test
    fun anItemWiseReturnPrintsNoCustomerAndStillRenders() {
        db.execSQL(
            "INSERT INTO td_sale_returns (original_bill_id, return_bill_number, return_date) " +
                "VALUES (NULL, ?, date('now'))",
            arrayOf<Any>("ITEMWISE-${System.currentTimeMillis()}")
        )
        returnId = lastId()

        val slip = render()
        listOf(R.id.tvReturnCustName, R.id.tvReturnCustMobile, R.id.tvReturnCustGstin).forEach {
            assertEquals(
                "nothing was captured, so nothing may print",
                View.GONE, slip.findViewById<View>(it).visibility
            )
        }
    }

    // ---- Helpers -------------------------------------------------------------------

    private fun render(): View {
        var slip: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val themed = androidx.appcompat.view.ContextThemeWrapper(
                ctx, com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar
            )
            val view = LayoutInflater.from(themed).inflate(R.layout.receipt_return, null, false)
            ReturnReceiptRenderer(themed).populate(view, result(), "TESTER")
            slip = view
        }
        return slip!!
    }

    /** A return result carrying the id of the row planted above. */
    private fun result() = ReturnDao.Result(
        id = returnId,
        returnNumber = "RET-TEST",
        dateTime = "2026-09-11 12:00:00",
        originalBillNumber = if (receiptNo > 0) "B-TEST" else null,
        lines = emptyList(),
        totalGross = 100.0,
        totalDiscount = 0.0,
        totalCgst = 0.0,
        totalSgst = 0.0,
        totalVat = 0.0,
        totalAmount = 100.0
    )

    private fun plantBillWithWalkInCustomer() {
        db.execSQL(
            "INSERT INTO td_bills (bill_number, bill_date, bill_type, bill_status, net_amount) " +
                "VALUES ('B-TEST', date('now'), 'CASH', 'COMPLETED', 100)"
        )
        receiptNo = lastId()
        // Nobody on the master - the name lives on the payment row, which is the
        // fallback this is really testing.
        db.execSQL(
            "INSERT INTO td_payments (bill_id, payment_mode, amount_paid, cust_name, cust_phone) " +
                "VALUES (?, 'CASH', 100, ?, ?)",
            arrayOf<Any>(receiptNo, NAME, PHONE)
        )
    }

    private fun plantReturnAgainstTheBill() {
        db.execSQL(
            "INSERT INTO td_sale_returns (original_bill_id, return_bill_number, return_date) " +
                "VALUES (?, ?, date('now'))",
            arrayOf<Any>(receiptNo, "RET-${System.currentTimeMillis()}")
        )
        returnId = lastId()
    }

    private fun lastId(): Long =
        db.rawQuery("SELECT last_insert_rowid()", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun textOf(root: View, id: Int): String =
        root.findViewById<TextView>(id).text.toString()
}
