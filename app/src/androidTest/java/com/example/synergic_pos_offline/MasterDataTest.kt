package com.example.synergic_pos_offline

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.DatabaseBackup
import com.example.synergic_pos_offline.utils.MasterData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The catalogue moves between tills without carrying the shop it came from.
 *
 * The store stamp is the whole trick, and the thing to pin down. Nearly every screen
 * reads its rows back with `WHERE store_id = <this store>`, so a catalogue exported
 * with its old store still on it would load onto another till and be invisible there
 * - present in the tables, filtered out of every screen. It goes out empty and is
 * stamped on the way in.
 */
@RunWith(AndroidJUnit4::class)
class MasterDataTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    private val storeName = "Master Test Store"
    private val productName = "Master Test Product"

    private val categoryName = "MasterTestCategory"

    @After
    fun clearLeftovers() {
        db.delete(DatabaseHelper.Tables.MD_PRODUCTS, "product_name = ?", arrayOf(productName))
        db.delete(DatabaseHelper.Tables.MD_CATEGORY, "category_name = ?", arrayOf(categoryName))
        db.delete(DatabaseHelper.Tables.MD_UNITS, "unit_name = ?", arrayOf("MasterTestUnit"))
        db.delete(DatabaseHelper.Tables.MD_RATE_NAME, "rate_name = ?", arrayOf("MasterTestTier"))
        db.delete(DatabaseHelper.Tables.MD_REGISTRATION, "store_name = ?", arrayOf(storeName))
    }

    private fun seedRegistration(): Long = db.insert(
        DatabaseHelper.Tables.MD_REGISTRATION, null,
        ContentValues().apply { put("store_name", storeName) }
    )

    /**
     * A catalogue belonging to a real store.
     *
     * `md_products.store_id` is a foreign key onto `md_registration`, so a catalogue
     * cannot be seeded under a store that does not exist - the insert is refused and
     * the test would go on to assert about rows that were never there.
     */
    private fun seedCatalogue(storeId: Long): Long {
        db.insert(
            DatabaseHelper.Tables.MD_UNITS, null,
            ContentValues().apply {
                put("store_id", storeId); put("unit_name", "MasterTestUnit")
                put("unit_symbol", "mtu")
            }
        )
        db.insert(
            DatabaseHelper.Tables.MD_RATE_NAME, null,
            ContentValues().apply {
                put("store_id", storeId); put("rate_name", "MasterTestTier")
            }
        )
        val categoryId = db.insert(
            DatabaseHelper.Tables.MD_CATEGORY, null,
            ContentValues().apply {
                put("store_id", storeId); put("category_name", categoryName)
            }
        )
        return db.insert(
            DatabaseHelper.Tables.MD_PRODUCTS, null,
            ContentValues().apply {
                put("store_id", storeId); put("product_name", productName)
                put("sku", "MTP-1"); put("category_id", categoryId)
            }
        )
    }

    private fun categoryOfProduct(): Long? = db.query(
        DatabaseHelper.Tables.MD_PRODUCTS, arrayOf("category_id"),
        "product_name = ?", arrayOf(productName), null, null, null, "1"
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    private fun categoryNameOf(id: Long): String? = db.query(
        DatabaseHelper.Tables.MD_CATEGORY, arrayOf("category_name"),
        "id = ?", arrayOf(id.toString()), null, null, null, "1"
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun storeIdOfProduct(): Long? = db.query(
        DatabaseHelper.Tables.MD_PRODUCTS, arrayOf("store_id"),
        "product_name = ?", arrayOf(productName), null, null, null, "1"
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    @Test
    fun theExportCarriesTheCatalogueAndNoStore() {
        val storeId = seedRegistration()
        val productId = seedCatalogue(storeId)
        assertTrue("could not seed a catalogue", productId != -1L)

        val export = MasterData.export(ctx)

        assertTrue("the products should be carried", export.sql.contains(productName))
        assertTrue("it should say what it is", MasterData.looksLikeMasterExport(export.sql))
        assertTrue(
            "the file should declare the column it blanks",
            export.sql.contains("-- written empty: store_id")
        )

        // Checked on the row itself rather than by searching the file for the store's
        // number, which is a single digit and appears all over a price list. Columns
        // come out in the table's own order - id, store_id, product_name - so the
        // seeded product's line says outright whether the store went with it.
        val line = export.sql.lineSequence().first { it.contains(productName) }
        assertTrue(
            "store_id should have been written empty, was: $line",
            Regex("""VALUES \(\d+, NULL, '$productName'""").containsMatchIn(line)
        )
    }

    /** Nothing outside the four master tables is in the file. */
    @Test
    fun theExportIsOnlyTheFourMasterTables() {
        val export = MasterData.export(ctx)
        val tables = Regex("""INSERT INTO (\w+) """).findAll(export.sql)
            .map { it.groupValues[1] }.toSet()

        assertTrue(
            "unexpected tables in a master export: ${tables - MasterData.TABLES}",
            MasterData.TABLES.containsAll(tables)
        )
    }

    /**
     * Loading a catalogue stamps it with the store this device is registered as -
     * which is what makes an otherwise store-less file this shop's.
     */
    @Test
    fun restoringStampsTheCatalogueWithThisDevicesStore() {
        val storeId = seedRegistration()
        assertTrue("could not seed a registration", storeId != -1L)
        assertTrue("could not seed a catalogue", seedCatalogue(storeId) != -1L)
        val export = MasterData.export(ctx)

        val result = MasterData.restore(ctx, export.sql.lineSequence())

        assertTrue("restore reported: ${result.error}", result.ok)
        assertNotNull("the catalogue should have been stamped", result.storeId)
        assertEquals(
            "the product should now belong to this device's store",
            result.storeId, storeIdOfProduct()
        )
        assertTrue("the product should have survived the round trip", result.rows > 0)
    }

    /**
     * A product still knows which category it is in after the round trip.
     *
     * The categories travel with the products for exactly this reason: without them a
     * restored product points at a category id that means something else on the new
     * till, or nothing at all - the catalogue arrives and how it groups does not.
     */
    @Test
    fun productsKeepTheirCategories() {
        val storeId = seedRegistration()
        assertTrue("could not seed a catalogue", seedCatalogue(storeId) != -1L)
        val export = MasterData.export(ctx)

        assertTrue(
            "the categories should be carried", export.sql.contains(categoryName)
        )

        val result = MasterData.restore(ctx, export.sql.lineSequence())
        assertTrue("restore reported: ${result.error}", result.ok)

        val category = categoryOfProduct()
        assertNotNull("the product should still name a category", category)
        assertEquals(
            "and it should be the category it was exported in",
            categoryName, categoryNameOf(category!!)
        )
    }

    /**
     * A whole-database backup picked here loads only its master tables.
     *
     * The Restore Masters button must not be a way to replace the shop's bills by
     * choosing the wrong file, so the restore is bounded to the four tables whatever
     * the file turns out to hold.
     */
    @Test
    fun aFullBackupPickedHereTouchesOnlyTheMasters() {
        seedCatalogue(seedRegistration())
        val billsBefore = count(DatabaseHelper.Tables.TD_BILLS)
        val full = DatabaseBackup.export(ctx)

        // Add a bill that the full backup above does not contain: if the restore
        // honoured the whole file it would clear td_bills and this would vanish.
        db.execSQL(
            "INSERT INTO ${DatabaseHelper.Tables.TD_BILLS} " +
                "(bill_number, bill_seq_no, bill_date, bill_type, net_amount) " +
                "VALUES ('MASTER-GUARD', 999999, '2026-08-10', 'CASH', 1.0)"
        )

        val result = MasterData.restore(ctx, full.sql.lineSequence())

        assertTrue("restore reported: ${result.error}", result.ok)
        assertEquals(
            "no bill should have been touched",
            billsBefore + 1, count(DatabaseHelper.Tables.TD_BILLS)
        )
        db.delete(DatabaseHelper.Tables.TD_BILLS, "bill_number = ?", arrayOf("MASTER-GUARD"))
    }

    private fun count(table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}
