package com.example.synergic_pos_offline

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.BillSettingsSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The v23 migration moves every bill's settings out of the bill and loses nothing.
 *
 * This is the one change in this codebase that rewrites the busiest table in a shop's
 * database, so it is tested against a REAL database carrying real snapshots rather than
 * by reading the code. Three things have to hold:
 *
 *  - **Nothing is lost.** Every bill that had a snapshot ends up pointing at a settings
 *    row, and no bill row disappears in the rebuild.
 *  - **Nothing is changed.** The settings a migrated bill reads back must be exactly what
 *    [BillSettingsSnapshot.parse] would have read from its JSON. A snapshot exists so a
 *    reprint looks like the day of sale; a migration that quietly flips one flag defeats
 *    the whole point and would never be noticed.
 *  - **The old column is gone**, and stays gone.
 */
@RunWith(AndroidJUnit4::class)
class BillSettingsMigrationTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var file: File
    private lateinit var db: SQLiteDatabase

    /**
     * Snapshots covering what a real shop's history holds: a current one, one from before
     * `taxEnabled` replaced `taxRegime`, one missing the fields that default to ON, a
     * duplicate of the first (to prove sharing), and rubbish.
     */
    private val current = """{"hsnCode":true,"productSerialNumber":true,"timeOnBill":false,
        "customerDetails":"FULL","customerAddressPrinting":true,"totalAmountFontSize":"LARGE",
        "roundOff":true,"amountInWords":true,"taxEnabled":true,"discountPreTax":false,
        "inclusive":true,"itemwiseDiscount":false}""".trimIndent().replace("\n", "")

    private val oldRegimeNone = """{"hsnCode":false,"taxRegime":"NONE"}"""
    private val oldRegimeGst = """{"hsnCode":false,"taxRegime":"GST"}"""
    private val sparse = """{"hsnCode":false}"""
    private val rubbish = "not json at all"

    @Before
    fun buildAV22Database() {
        file = File(ctx.cacheDir, "migration-test-${System.nanoTime()}.db")
        db = SQLiteDatabase.openOrCreateDatabase(file, null)

        // The v22 shape of the two bill tables - only the columns this migration reads or
        // rebuilds. rebuildPreservingColumns copies whatever the two shapes share, so the
        // rest of the real schema is irrelevant to what is being tested.
        db.execSQL(
            """
            CREATE TABLE td_bills (
                receipt_no INTEGER PRIMARY KEY AUTOINCREMENT,
                bill_number TEXT,
                bill_type TEXT,
                settings_snapshot TEXT,
                net_amount REAL DEFAULT 0
            )
            """
        )
        db.execSQL("CREATE TABLE td_bills_delete (receipt_no INTEGER PRIMARY KEY, settings_snapshot TEXT)")

        // The migration ends by rebuilding the indexes the dropped tables took with them,
        // and createIndexes covers the whole schema rather than just these two tables. So
        // the rest have to exist for it to run - empty, and only the indexed columns,
        // because nothing here reads them. Keeping the real migration in the test is
        // worth this: it is the code that will run on a shop's database.
        listOf(
            "md_products(category_id INTEGER, store_id INTEGER)",
            "md_product_rates(product_id INTEGER)",
            "md_batch_stock(product_id INTEGER)",
            "td_purchase(product_id INTEGER, supp_id INTEGER)",
            "td_bill_items(bill_id INTEGER, product_id INTEGER)",
            "td_payments(bill_id INTEGER)",
            "td_stock_transactions(product_id INTEGER)",
            "td_customer_ledger(customer_id INTEGER)",
            "td_kot(bill_id INTEGER)",
            "td_kot_items(kot_id INTEGER)"
        ).forEach { db.execSQL("CREATE TABLE IF NOT EXISTS $it") }

        db.version = 22
    }

    @After
    fun tearDown() {
        if (::db.isInitialized && db.isOpen) db.close()
        if (::file.isInitialized) file.delete()
    }

    private fun insertBill(number: String, snapshot: String?) {
        db.execSQL(
            "INSERT INTO td_bills (bill_number, settings_snapshot) VALUES (?, ?)",
            arrayOf<Any?>(number, snapshot)
        )
    }

    /** Runs the real migration, exactly as opening an upgraded app would. */
    private fun migrate() {
        DatabaseHelper.getInstance(ctx).onUpgrade(db, 22, 23)
    }

    private fun settingsIdOf(billNumber: String): Long? = db.rawQuery(
        "SELECT settings_id FROM td_bills WHERE bill_number = ?", arrayOf(billNumber)
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    private fun snapshotOf(billNumber: String): BillSettingsSnapshot.Snapshot? =
        BillSettingsSnapshot.byId(db, settingsIdOf(billNumber))

    @Test
    fun everyBillReadsBackExactlyWhatItsJsonSaid() {
        // The heart of it: migrated settings must equal parsed settings, field for field.
        val corpus = mapOf(
            "B-CURRENT" to current,
            "B-OLD-NONE" to oldRegimeNone,
            "B-OLD-GST" to oldRegimeGst,
            "B-SPARSE" to sparse
        )
        corpus.forEach { (number, json) -> insertBill(number, json) }
        migrate()

        corpus.forEach { (number, json) ->
            val expected = BillSettingsSnapshot.parse(json)
            assertNotNull("parse() should read the fixture $number", expected)
            assertEquals("$number migrated to different settings than it parsed to", expected, snapshotOf(number))
        }
    }

    @Test
    fun billsSharingSettingsShareOneRow() {
        // Why the table is deduplicated: a shop's whole history is a handful of distinct
        // combinations, and one row per bill would just be the old column again.
        insertBill("A", current)
        insertBill("B", current)
        insertBill("C", sparse)
        migrate()

        assertEquals("two bills with identical settings should share a row", settingsIdOf("A"), settingsIdOf("B"))
        assertTrue("different settings need their own row", settingsIdOf("C") != settingsIdOf("A"))
        assertEquals("only the distinct combinations should exist", 2, countSettingsRows())
    }

    private fun countSettingsRows(): Int = db.rawQuery(
        "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_BILL_SETTINGS}", null
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

    @Test
    fun aBillWithNoReadableSnapshotKeepsNoSettingsAndStillExists() {
        // Both already had to be handled before this change - a bill older than snapshots
        // reads with today's settings - so the migration must produce the same NULL
        // rather than inventing a row or dropping the bill.
        insertBill("B-NULL", null)
        insertBill("B-BLANK", "")
        insertBill("B-RUBBISH", rubbish)
        migrate()

        listOf("B-NULL", "B-BLANK", "B-RUBBISH").forEach {
            assertNull("$it should have no settings row", settingsIdOf(it))
            assertNotNull("$it must still be in the table", billExists(it))
        }
    }

    private fun billExists(number: String): String? = db.rawQuery(
        "SELECT bill_number FROM td_bills WHERE bill_number = ?", arrayOf(number)
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    @Test
    fun theRebuildKeepsEveryBillAndItsOtherColumns() {
        // rebuildPreservingColumns drops and recreates the table. If its DDL named the
        // wrong table - the real one instead of the temp - this is where the bills would
        // vanish, so it is worth asserting outright.
        insertBill("KEEP-1", current)
        insertBill("KEEP-2", sparse)
        db.execSQL("UPDATE td_bills SET net_amount = 123.45 WHERE bill_number = 'KEEP-1'")
        migrate()

        assertEquals(2, countBills())
        val amount = db.rawQuery(
            "SELECT net_amount FROM td_bills WHERE bill_number = 'KEEP-1'", null
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else -1.0 }
        assertEquals("the bill's own figures must survive the rebuild", 123.45, amount, 0.001)
    }

    private fun countBills(): Int = db.rawQuery("SELECT COUNT(*) FROM td_bills", null)
        .use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

    @Test
    fun theOldColumnIsGoneAndTheNewOneIsThere() {
        insertBill("X", current)
        migrate()
        assertFalse("settings_snapshot should have been dropped", hasColumn("td_bills", "settings_snapshot"))
        assertTrue("settings_id should have replaced it", hasColumn("td_bills", "settings_id"))
        assertFalse(hasColumn("td_bills_delete", "settings_snapshot"))
        assertTrue(hasColumn("td_bills_delete", "settings_id"))
    }

    @Test
    fun deletedBillsAreMigratedToo() {
        // td_bills_delete is a shop's archive of cancelled bills and carried the same
        // column; leaving it behind would strand those reprints.
        db.execSQL(
            "INSERT INTO td_bills_delete (receipt_no, settings_snapshot) VALUES (1, ?)",
            arrayOf<Any>(current)
        )
        migrate()
        val id = db.rawQuery("SELECT settings_id FROM td_bills_delete WHERE receipt_no = 1", null)
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        assertNotNull("a cancelled bill should keep its settings too", id)
        assertEquals(BillSettingsSnapshot.parse(current), BillSettingsSnapshot.byId(db, id))
    }

    private fun hasColumn(table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            generateSequence { if (c.moveToNext()) c.getString(nameIdx) else null }
                .any { it.equals(column, ignoreCase = true) }
        }
}
