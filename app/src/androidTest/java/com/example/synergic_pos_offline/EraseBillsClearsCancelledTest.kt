package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.BillErase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Erase Bills takes the cancelled bills too.
 *
 * A cancelled bill is not flagged in place - BillDeleteDao MOVES it, header into
 * td_bills_delete and lines into td_bill_items_delete, and takes the originals out of
 * td_bills. That is what keeps it out of every sales report without each report
 * needing to learn a new exclusion.
 *
 * The cost of that shape is this: anything clearing "all bills" by emptying td_bills
 * empties only the live books, and every bill the shop ever cancelled stays on the
 * device - reported by the Void Bill Report, carried by every backup - on a till that
 * has just been told it has no bills at all.
 */
@RunWith(AndroidJUnit4::class)
class EraseBillsClearsCancelledTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    @Test
    fun eraseTakesTheLiveBooksAndTheCancelledArchiveAlike() {
        plantLiveBill()
        plantCancelledBill()

        val before = BillErase.preview(ctx)
        assertTrue("a live bill was planted", before.bills > 0)
        assertTrue("a cancelled bill was planted", before.cancelled > 0)
        assertTrue("so there is something to erase", before.hasAnything)

        val outcome = BillErase.erase(ctx)
        assertEquals("every live bill should have gone", 0, count("td_bills"))
        assertEquals("and every cancelled one with it", 0, count("td_bills_delete"))
        assertEquals("cancelled bill lines too", 0, count("td_bill_items_delete"))
        assertEquals("live bill lines too", 0, count("td_bill_items"))

        // Reported, not merely done - an operator who is not told the archive went
        // has no way to tell it apart from an archive that was quietly left behind.
        assertTrue("the outcome should count the cancelled bills", outcome.cancelled > 0)
        assertEquals("nothing should be left to erase", false, BillErase.preview(ctx).hasAnything)
    }

    /**
     * A till holding ONLY cancelled bills still has something to lose.
     *
     * This is the case the old guard got wrong: it asked td_bills alone, found it
     * empty, and reported "no bills to erase" while the archive sat there.
     */
    @Test
    fun aTillWithOnlyCancelledBillsHasSomethingToErase() {
        // Cleared through the app's own path rather than by emptying td_bills by
        // hand: six tables carry a foreign key onto it, so a raw delete is refused.
        BillErase.erase(ctx)
        plantCancelledBill()

        val preview = BillErase.preview(ctx)
        assertEquals("no live bills", 0, preview.bills)
        assertTrue("but cancelled ones", preview.cancelled > 0)
        assertTrue("which is something to erase", preview.hasAnything)

        BillErase.erase(ctx)
        assertEquals(0, count("td_bills_delete"))
    }

    private fun plantLiveBill() {
        db.execSQL(
            "INSERT INTO td_bills (bill_number, bill_date, bill_type, bill_status, net_amount) " +
                "VALUES ('ERASE-TEST', date('now'), 'CASH', 'COMPLETED', 100)"
        )
        val id = lastId()
        db.execSQL(
            "INSERT INTO td_bill_items (bill_id, product_name, quantity, rate, item_subtotal) " +
                "VALUES ($id, 'Live line', 1, 100, 100)"
        )
    }

    /** A bill in the archive, the shape BillDeleteDao leaves behind. */
    private fun plantCancelledBill() {
        db.execSQL(
            "INSERT INTO td_bills_delete (receipt_no, bill_number, bill_date, bill_type, net_amount) " +
                "VALUES (900001, 'CANCELLED-TEST', date('now'), 'CASH', 50)"
        )
        db.execSQL(
            "INSERT INTO td_bill_items_delete (id, bill_id, product_name, quantity, rate) " +
                "VALUES (900001, 900001, 'Cancelled line', 1, 50)"
        )
    }

    private fun lastId(): Long =
        db.rawQuery("SELECT last_insert_rowid()", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun count(table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}
