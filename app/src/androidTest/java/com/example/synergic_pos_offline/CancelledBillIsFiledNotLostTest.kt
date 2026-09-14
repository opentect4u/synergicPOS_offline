package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.CustomerDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.VoidBillReportDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A bill cancelled from the Bill History list is FILED, not lost.
 *
 * The delete icon on a History row used to erase the bill outright - row gone, lines
 * gone, nothing anywhere. So an operator who cancelled a bill and then went looking
 * for it under Cancelled found nothing, and the till looked like one that had lost a
 * sale rather than one that had been told to void it.
 *
 * Everything downstream was already built for an archive: [BillDao.Bill.deleted],
 * History's union across the live and archived tables, the Void Bill Report, even
 * reprinting from the archive. Only the list's own delete button was not using it -
 * while the Delete on the single-bill SCREEN was. One button, two meanings.
 *
 * So what these ask is not "did the row go" but "where did it go": out of the sales
 * figures, and into the place the operator is told to look.
 */
@RunWith(AndroidJUnit4::class)
class CancelledBillIsFiledNotLostTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    private var customerId = 0L
    private val bills = mutableListOf<Long>()

    private companion object {
        const val PHONE = "9800000077"
        const val BILL = 500.0
        const val BROUGHT_FORWARD = 1000.0
    }

    @After
    fun clearUpAfterItself() {
        bills.forEach { receiptNo ->
            listOf(
                "DELETE FROM td_customer_ledger WHERE bill_id = $receiptNo",
                "DELETE FROM td_payments WHERE bill_id = $receiptNo",
                "DELETE FROM td_bill_items WHERE bill_id = $receiptNo",
                "DELETE FROM td_bills WHERE receipt_no = $receiptNo",
                "DELETE FROM td_bill_items_delete WHERE bill_id = $receiptNo",
                "DELETE FROM td_bills_delete WHERE receipt_no = $receiptNo"
            ).forEach { runCatching { db.execSQL(it) } }
        }
        if (customerId > 0) {
            runCatching { db.execSQL("DELETE FROM td_customer_ledger WHERE customer_id = $customerId") }
            runCatching { CustomerDao(ctx).delete(listOf(customerId)) }
        }
    }

    // ---- Where the bill goes -----------------------------------------------------------

    /**
     * Out of `td_bills` - which is what takes it off every sales report at once - and
     * into the archive, in one move.
     */
    @Test
    fun theBillLeavesTheSalesTableAndLandsInTheArchive() {
        val receiptNo = sell()
        assertTrue("planted bill should be live", rowExists("td_bills", "receipt_no", receiptNo))

        val result = BillDao(ctx).cancelBill(receiptNo)
        assertTrue("the cancel should succeed: ${result.refusal}", result.ok)

        assertFalse(
            "a cancelled bill must not stay in td_bills, or it goes on being counted",
            rowExists("td_bills", "receipt_no", receiptNo)
        )
        assertTrue(
            "and it must be in the archive, which is where Cancelled reads from",
            rowExists("td_bills_delete", "receipt_no", receiptNo)
        )
    }

    /**
     * Its LINES go with it.
     *
     * A header archived without its lines is a bill nobody can account for - the
     * Cancelled list would show it and opening it would show an empty slip.
     */
    @Test
    fun theBillsLinesAreArchivedToo() {
        val receiptNo = sell()
        BillDao(ctx).cancelBill(receiptNo)

        assertTrue(
            "the lines should be in the archive",
            rowExists("td_bill_items_delete", "bill_id", receiptNo)
        )
        assertFalse(
            "and out of the live table",
            rowExists("td_bill_items", "bill_id", receiptNo)
        )
    }

    /**
     * The Bill History list shows it under Cancelled.
     *
     * This is the thing that was actually reported, asked of the same call the screen
     * makes: History reads both tables and files an archived bill with the cancelled
     * ones, so the row must come back with `cancelled` set.
     */
    @Test
    fun billHistoryListsItUnderCancelled() {
        val receiptNo = sell()
        val billNo = BillDao(ctx).getAll().first { it.receiptNo == receiptNo }.billNo

        BillDao(ctx).cancelBill(receiptNo)

        val row = BillDao(ctx).getAll().firstOrNull { it.receiptNo == receiptNo }
        assertNotNull("the bill should still be listed in History", row)
        assertTrue("filed with the cancelled ones", row!!.cancelled)
        assertTrue("and marked as deleted", row.deleted)
        assertEquals("under the number it was sold as", billNo, row.billNo)
    }

    /** And on the Void Bill Report, the other place the operator is told to look. */
    @Test
    fun theVoidBillReportShowsIt() {
        val receiptNo = sell()
        val billNo = BillDao(ctx).getAll().first { it.receiptNo == receiptNo }.billNo
        BillDao(ctx).cancelBill(receiptNo)

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val report = VoidBillReportDao(ctx).between(today, today)

        assertTrue(
            "the cancelled bill should be on the Void Bill Report. Found: " +
                report.lines.map { it.billNumber },
            report.lines.any { it.billNumber == billNo }
        )
    }

    // ---- What cancelling undoes --------------------------------------------------------

    /**
     * A credit sale comes off the customer's balance, not just off the ledger.
     *
     * Deleting the ledger lines alone would leave the customer still owing for a bill
     * that no longer counts - a debt with nothing behind it. The balance has to move
     * back by what the sale put on it.
     */
    @Test
    fun aCreditSaleComesBackOffTheCustomersBalance() {
        val receiptNo = sell(credit = true, paid = 0.0)
        assertEquals(
            "the sale should have gone on the account first",
            BROUGHT_FORWARD + BILL, balanceOnFile(), 0.005
        )

        BillDao(ctx).cancelBill(receiptNo)

        assertEquals(
            "and cancelling should leave them exactly where they started",
            BROUGHT_FORWARD, balanceOnFile(), 0.005
        )
        assertFalse(
            "with no ledger line left pointing at the cancelled bill",
            rowExists("td_customer_ledger", "bill_id", receiptNo)
        )
    }

    /**
     * A part-paid credit sale reverses by what it actually put on the account.
     *
     * The guard against reversing the whole bill: 500 billed with 200 taken at the
     * counter moved the balance by 300, so 300 is what must come back off it.
     */
    @Test
    fun aPartPaidCreditSaleReversesByWhatItPutOn() {
        val receiptNo = sell(credit = true, paid = 200.0)
        assertEquals(BROUGHT_FORWARD + BILL - 200.0, balanceOnFile(), 0.005)

        BillDao(ctx).cancelBill(receiptNo)
        assertEquals(BROUGHT_FORWARD, balanceOnFile(), 0.005)
    }

    /**
     * A credit sale is not refused.
     *
     * The single-bill screen used to refuse any bill carrying a ledger entry, which -
     * now that every credit sale writes one - would have made credit sales permanently
     * uncancellable.
     */
    @Test
    fun aCreditSaleCanBeCancelledAtAll() {
        val receiptNo = sell(credit = true, paid = 0.0)
        val result = BillDao(ctx).cancelBill(receiptNo)
        assertTrue("a credit sale must be cancellable: ${result.refusal}", result.ok)
    }

    // ---- What it still refuses ---------------------------------------------------------

    /**
     * A bill with a sale return against it stays put, and says why.
     *
     * The return is its own document with its own number; cancelling the sale under it
     * would leave a credit note pointing at nothing.
     */
    @Test
    fun aBillWithAReturnAgainstItIsRefused() {
        val receiptNo = sell()
        db.execSQL(
            "INSERT INTO td_sale_returns (original_bill_id, return_bill_number, return_date) " +
                "VALUES (?, ?, date('now'))",
            arrayOf<Any>(receiptNo, "RET-${System.currentTimeMillis()}")
        )

        val result = BillDao(ctx).cancelBill(receiptNo)

        assertFalse("it must be refused", result.ok)
        assertTrue(
            "and say what is standing in the way: was \"${result.refusal}\"",
            result.refusal.orEmpty().contains("return", ignoreCase = true)
        )
        assertTrue(
            "the bill stays exactly where it was",
            rowExists("td_bills", "receipt_no", receiptNo)
        )
        assertFalse(
            "and nothing is half-written into the archive",
            rowExists("td_bills_delete", "receipt_no", receiptNo)
        )

        runCatching { db.execSQL("DELETE FROM td_sale_returns WHERE original_bill_id = $receiptNo") }
    }

    // ---- Helpers -----------------------------------------------------------------------

    /** One bill of [BILL] to the planted customer. */
    private fun sell(credit: Boolean = false, paid: Double = BILL): Long {
        if (credit && customerId == 0L) plantCustomer()
        val mode = if (credit) "CREDIT" else "CASH"
        val result = BillDao(ctx).createBill(
            BillDao.NewBill(
                billType = mode,
                customerId = if (credit) customerId else null,
                items = listOf(
                    BillDao.Item(productId = null, name = "Cancel me", quantity = 1.0, rate = BILL)
                ),
                payment = BillDao.Payment(
                    mode = mode,
                    amountPaid = paid,
                    custId = if (credit) customerId else null
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

    private fun plantCustomer() {
        customerId = CustomerDao(ctx).insert(
            CustomerDao.Customer(
                id = 0L, name = "Cancel customer", address = "", phone = PHONE, gstin = "",
                creditEnabled = true, creditLimit = 50000.0, balance = BROUGHT_FORWARD
            )
        )
    }

    private fun balanceOnFile(): Double = CustomerDao(ctx).findById(customerId)!!.balance

    private fun rowExists(table: String, column: String, value: Long): Boolean =
        db.rawQuery("SELECT 1 FROM $table WHERE $column = ? LIMIT 1", arrayOf(value.toString()))
            .use { it.moveToFirst() }
}
