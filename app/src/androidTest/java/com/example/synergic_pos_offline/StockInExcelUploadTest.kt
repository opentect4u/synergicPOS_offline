package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.StockBulkImporter
import com.example.synergic_pos_offline.utils.StockCsvTemplate
import com.example.synergic_pos_offline.utils.Xlsx
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/**
 * The Stock In template goes out as a workbook and comes back readable.
 *
 * The round trip is the whole feature, and the place it can break is in the middle:
 * the importer looks its columns up BY NAME, so a heading that survives the trip
 * through Excel as anything other than `product_name` / `stock` leaves every row
 * unreadable - and the screen reports "nothing to receive" for a sheet the operator
 * filled in correctly.
 *
 * So the workbook is written, read back the way the screen reads it, and handed to
 * the importer, rather than any of the three being checked on its own.
 */
@RunWith(AndroidJUnit4::class)
class StockInExcelUploadTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    private var planted: Long = 0
    private var previousUser: com.example.synergic_pos_offline.models.User? = null

    /**
     * A signed-in operator, because the template is the SHOP'S catalogue.
     *
     * StockCsvTemplate reads the items for the session's store - without one it comes
     * back holding nothing but its heading row, and every assertion below would pass
     * against an empty sheet while proving nothing.
     */
    @Before
    fun signIn() {
        previousUser = com.example.synergic_pos_offline.utils.SessionManager.currentUser
        val store = db.rawQuery("SELECT store_id FROM md_users LIMIT 1", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        com.example.synergic_pos_offline.utils.SessionManager.currentUser =
            com.example.synergic_pos_offline.models.User(
                userId = "stock-in-test",
                password = "",
                role = com.example.synergic_pos_offline.models.UserRole.ADMIN,
                storeId = store
            )
        maxTxn = maxIdOf("td_stock_transactions")
        maxBatch = maxIdOf("md_batch_stock")
    }

    private fun maxIdOf(table: String): Long =
        db.rawQuery("SELECT COALESCE(MAX(id), 0) FROM $table", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    @After
    fun signOut() {
        com.example.synergic_pos_offline.utils.SessionManager.currentUser = previousUser
    }

    /**
     * Puts the shelf back.
     *
     * This books stock in against a product the shop actually has, so it has to take
     * it out again - only the rows this test created, found by the ids that did not
     * exist when it started.
     */
    @After
    fun clearUpAfterItself() {
        if (planted <= 0) return
        runCatching {
            db.execSQL("DELETE FROM td_stock_transactions WHERE product_id = $planted AND id > $maxTxn")
        }
        runCatching {
            db.execSQL("DELETE FROM md_batch_stock WHERE product_id = $planted AND id > $maxBatch")
        }
    }

    /** The high-water marks before the test, so only its own rows are removed. */
    private var maxTxn: Long = 0
    private var maxBatch: Long = 0

    @Test
    fun aFilledInWorkbookIsReadBackAndReceived() {
        // A product ALREADY in the catalogue, not one planted here: md_products.store_id
        // carries a foreign key, so a row invented for a test either breaks it or lands
        // under no store and is then absent from the very template being tested.
        val existing = db.rawQuery(
            "SELECT id, product_name FROM md_products WHERE product_name IS NOT NULL " +
                "AND TRIM(product_name) <> '' ORDER BY id LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null } ?: return
        planted = existing.first
        val name = existing.second
        val before = stockOf(planted)

        // The template as the Download button now writes it.
        val template = StockCsvTemplate.rows(ctx)
        assertEquals(
            "the heading row is what the importer matches on",
            StockCsvTemplate.header, template.first()
        )
        assertTrue(
            "the planted product should be on the sheet",
            template.drop(1).any { it.firstOrNull() == name }
        )

        // Filled in the way an operator fills it in: a quantity beside one item.
        val filled = template.map { row ->
            if (row.firstOrNull() == name) listOf(name, "12") else row
        }
        val workbook = Xlsx.write(filled, "Stock In")
        assertTrue("a workbook starts with PK", Xlsx.looksLikeXlsx(workbook.copyOf(2)))

        // Read back the way StockListFragment reads it - headings lower-cased and
        // trimmed, then a map per row.
        val read = Xlsx.read(ByteArrayInputStream(workbook))
        val headings = read.first().map { it.trim().lowercase() }
        val rows = read.drop(1)
            .filter { cells -> cells.any { it.isNotBlank() } }
            .map { cells ->
                headings.mapIndexed { i, key -> key to cells.getOrNull(i)?.trim().orEmpty() }.toMap()
            }

        val preview = StockBulkImporter.preview(ctx, rows)
        assertEquals("exactly the one filled-in line should be received", 1, preview.received)

        StockBulkImporter.import(ctx, rows)
        // Received stock is ADDED to whatever the item already held - the sheet says
        // what arrived, not what the shelf now totals.
        assertEquals("the quantity should have been booked in", before + 12.0, stockOf(planted), 0.005)
    }

    /**
     * A CSV of the same sheet still uploads.
     *
     * The template is a workbook now, but a shop with a sheet it has kept for months
     * must not have to convert a file the app can already read - so both readers stay
     * and this is the one that would quietly rot.
     */
    @Test
    fun aCsvOfTheSameSheetStillReads() {
        // Built from the same rows the workbook is, so the two cannot describe two
        // different templates - that is the property worth pinning, and it holds
        // whether or not this till happens to have a catalogue.
        val csv = StockCsvTemplate.content(ctx)
        val headerLine = csv.lineSequence().first()
        assertEquals(
            "the CSV's heading row must match the workbook's",
            StockCsvTemplate.header.joinToString(","), headerLine
        )
        assertEquals(
            "and it should carry a line per item, as the workbook does",
            StockCsvTemplate.rows(ctx).size,
            csv.lineSequence().count { it.isNotBlank() }
        )
    }

    private fun stockOf(id: Long): Double = db.rawQuery(
        "SELECT COALESCE(SUM(current_quantity), 0) FROM md_batch_stock WHERE product_id = ?",
        arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
}
