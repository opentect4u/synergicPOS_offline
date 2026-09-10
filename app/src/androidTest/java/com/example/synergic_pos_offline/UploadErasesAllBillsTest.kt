package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.ProductBulkImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A bulk product upload throws the books away - all of them.
 *
 * An upload redraws the catalogue every bill was written against, and every report
 * reads its bills THROUGH that catalogue as it stands now. Bills left behind report
 * under prices and tax rates that were never what was sold, which is worse than no
 * bills at all: it looks like a record.
 *
 * The two cases that matter are the two that used to survive - a bill for a product
 * the sheet never mentions, and a bill that was already cancelled.
 */
@RunWith(AndroidJUnit4::class)
class UploadErasesAllBillsTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    @Test
    fun uploadingASheetErasesEveryBillOnTheTill() {
        val untouched = plantBillFor(productNotInSheet())
        plantCancelledBill()
        // A SALE RETURN, which is what makes this more than a rename of the old code:
        // td_sale_returns keys onto td_bills, so a clear that leaves returns standing
        // cannot delete the bills at all - the constraint refuses it.
        plantSaleReturn(untouched)

        assertTrue("a live bill was planted", count("td_bills") > 0)
        assertTrue("a cancelled bill was planted", count("td_bills_delete") > 0)
        assertTrue("a sale return was planted", count("td_sale_returns") > 0)

        // The sheet names ONE product, and it is not the one the live bill was for.
        val sheet = listOf(
            mapOf("product_name" to "Upload erase test", "rate_1" to "10", "unit_1" to "PCS")
        )
        val counts = ProductBulkImporter.mergeCounts(ctx, sheet)
        assertTrue("the confirmation should count every bill", counts.billsToDelete > 0)

        val result = ProductBulkImporter.import(ctx, sheet, ProductBulkImporter.Mode.APPEND)

        assertEquals("every live bill should have gone", 0, count("td_bills"))
        assertEquals("every cancelled bill too", 0, count("td_bills_delete"))
        assertEquals("cancelled bill lines too", 0, count("td_bill_items_delete"))
        assertEquals("and the returns that pointed at them", 0, count("td_sale_returns"))
        assertTrue("the result should report what it erased", result.billsDeleted > 0)

        // The catalogue itself still merged - the sheet's product went in.
        assertTrue("the sheet's product should have been added", result.imported > 0)
    }

    /**
     * What a customer owes survives the books being thrown away.
     *
     * `balance_amount` is a figure held on the customer, not a sum over the ledger,
     * so clearing the ledger's rows takes the history of a debt and leaves the debt.
     * If that ever stops being true, this upload starts writing off money.
     */
    @Test
    fun aCustomerDebtSurvivesTheErase() {
        val customer = anyCustomer() ?: return
        db.execSQL("UPDATE md_customers SET balance_amount = 250.0 WHERE id = $customer")
        plantBillFor(productNotInSheet())

        ProductBulkImporter.import(
            ctx,
            listOf(mapOf("product_name" to "Debt survives test", "rate_1" to "5", "unit_1" to "PCS")),
            ProductBulkImporter.Mode.APPEND
        )

        val balance = db.rawQuery(
            "SELECT balance_amount FROM md_customers WHERE id = ?", arrayOf(customer.toString())
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else -1.0 }
        assertEquals("the debt must not be written off", 250.0, balance, 0.005)
    }

    // ---- Helpers -------------------------------------------------------------------

    private fun productNotInSheet(): Long {
        db.rawQuery("SELECT id FROM md_products ORDER BY id LIMIT 1", null).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        db.execSQL("INSERT INTO md_products (product_name) VALUES ('Erase test product')")
        return lastId()
    }

    private fun anyCustomer(): Long? =
        db.rawQuery("SELECT id FROM md_customers LIMIT 1", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun plantBillFor(productId: Long): Long {
        db.execSQL(
            "INSERT INTO td_bills (bill_number, bill_date, bill_type, bill_status, net_amount) " +
                "VALUES ('UPLOAD-ERASE', date('now'), 'CASH', 'COMPLETED', 100)"
        )
        val id = lastId()
        db.execSQL(
            "INSERT INTO td_bill_items (bill_id, product_id, product_name, quantity, rate, item_subtotal) " +
                "VALUES ($id, $productId, 'Untouched line', 1, 100, 100)"
        )
        return id
    }

    private fun plantCancelledBill() {
        db.execSQL(
            "INSERT INTO td_bills_delete (receipt_no, bill_number, bill_date, bill_type, net_amount) " +
                "VALUES (900002, 'UPLOAD-CANCELLED', date('now'), 'CASH', 50)"
        )
        db.execSQL(
            "INSERT INTO td_bill_items_delete (id, bill_id, product_name, quantity, rate) " +
                "VALUES (900002, 900002, 'Cancelled line', 1, 50)"
        )
    }

    private fun plantSaleReturn(billId: Long) {
        db.execSQL(
            "INSERT INTO td_sale_returns (original_bill_id, return_bill_number, return_date) " +
                "VALUES ($billId, 'UPLOAD-RET-${System.currentTimeMillis()}', date('now'))"
        )
    }

    private fun lastId(): Long =
        db.rawQuery("SELECT last_insert_rowid()", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun count(table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}
