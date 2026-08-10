package com.example.synergic_pos_offline

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.DatabaseBackup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The backup written on one installation puts the books back on another.
 *
 * This is the one feature where being wrong destroys the client's data rather than
 * printing it oddly, so it is tested against the real database rather than reasoned
 * about: rows are written, exported, deliberately destroyed, restored, and compared
 * byte for byte.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseBackupTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    /** A name with the two things that break a hand-written SQL writer. */
    private val awkwardName = "Baker's \"Best\"\nSecond line"

    /** Stands in for a product image - the blob path. */
    private val image = ByteArray(256) { (it * 7 % 256).toByte() }

    /**
     * Clears anything a previous run left behind.
     *
     * `md_users.user_id` is UNIQUE, so a run that failed before its own cleanup
     * would block every run after it - a test that only passes once is not a test.
     */
    @org.junit.Before
    fun clearLeftovers() {
        db.delete(DatabaseHelper.Tables.MD_USERS, "user_id = ?", arrayOf("BACKUP_TEST_USER"))
    }

    @Test
    fun aBackupSurvivesTheDatabaseBeingWiped() {
        val category = ContentValues().apply {
            put("category_name", awkwardName)
        }
        val categoryId = db.insert(DatabaseHelper.Tables.MD_CATEGORY, null, category)
        assertTrue("could not seed a category", categoryId != -1L)

        val product = ContentValues().apply {
            put("product_name", awkwardName)
            put("category_id", categoryId)
            put("product_image", image)
            put("stock_alert_qty", 12.5)
        }
        val productId = db.insert(DatabaseHelper.Tables.MD_PRODUCTS, null, product)
        assertTrue("could not seed a product", productId != -1L)

        // A user is seeded too - the backup carries those now, so it has to survive
        // the round trip like everything else.
        val userId = db.insert(
            DatabaseHelper.Tables.MD_USERS, null,
            ContentValues().apply {
                put("user_id", "BACKUP_TEST_USER")
                put("user_name", "Backup Test")
            }
        )
        assertTrue("could not seed a user", userId != -1L)

        val export = DatabaseBackup.export(ctx)
        assertTrue("the export carried nothing", export.rows > 0)
        // Kept so the file itself can be pulled and read:
        //   adb exec-out run-as com.example.synergic_pos_offline cat files/backup-sample.sql
        java.io.File(ctx.filesDir, "backup-sample.sql").writeText(export.sql)
        // The whole database is carried now, the installation's own rows included:
        // without the registration the restored rows point at a store the device is
        // not, and every screen filters them out.
        assertTrue(
            "the users table must be in the backup",
            export.sql.contains("INSERT INTO ${DatabaseHelper.Tables.MD_USERS} ")
        )
        assertTrue(
            "the registration must be in the backup",
            export.sql.contains("INSERT INTO ${DatabaseHelper.Tables.MD_REGISTRATION} ")
        )

        // Destroy it the way an uninstall would. Foreign keys are switched off for
        // the wipe: rates and batches point at products, so a straight delete of the
        // product table is refused - which is the same reason the restore turns them
        // off around its own clear-and-reload.
        db.setForeignKeyConstraintsEnabled(false)
        try {
            db.delete(DatabaseHelper.Tables.MD_PRODUCT_RATES, null, null)
            db.delete(DatabaseHelper.Tables.MD_BATCH_STOCK, null, null)
            db.delete(DatabaseHelper.Tables.MD_PRODUCTS, null, null)
            db.delete(DatabaseHelper.Tables.MD_CATEGORY, null, null)
        } finally {
            db.setForeignKeyConstraintsEnabled(true)
        }
        assertEquals("products should be gone before the restore", 0, count(DatabaseHelper.Tables.MD_PRODUCTS))

        val result = DatabaseBackup.restore(ctx, export.sql.lineSequence())
        assertTrue("restore reported: ${result.error}", result.ok)
        assertEquals("every exported row should come back", export.rows, result.rows)

        // The awkward name, the id and the blob all come back exactly.
        db.rawQuery(
            "SELECT product_name, category_id, product_image, stock_alert_qty " +
                "FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE id = ?",
            arrayOf(productId.toString())
        ).use { c ->
            assertTrue("the product did not come back", c.moveToFirst())
            assertEquals("quotes and newlines must survive", awkwardName, c.getString(0))
            assertEquals("the category link must survive", categoryId, c.getLong(1))
            assertArrayEquals("the image must survive byte for byte", image, c.getBlob(2))
            assertEquals(12.5, c.getDouble(3), 0.0001)
        }

        // The seeded user is carried and comes back with everything else.
        db.rawQuery(
            "SELECT count(*) FROM ${DatabaseHelper.Tables.MD_USERS} WHERE user_id = ?",
            arrayOf("BACKUP_TEST_USER")
        ).use { c ->
            c.moveToFirst()
            assertEquals("the user must come back with the rest", 1, c.getInt(0))
        }

        // Tidy up after ourselves.
        db.delete(DatabaseHelper.Tables.MD_USERS, "user_id = ?", arrayOf("BACKUP_TEST_USER"))
        db.delete(DatabaseHelper.Tables.MD_PRODUCTS, "id = ?", arrayOf(productId.toString()))
        db.delete(DatabaseHelper.Tables.MD_CATEGORY, "id = ?", arrayOf(categoryId.toString()))
    }

    /**
     * Every table in the database is read, not just the ones with rows in them.
     *
     * The count shown after a backup is the number of tables that *held* records,
     * which reads as though the rest were skipped. This pins the actual behaviour:
     * the scan covers every table `sqlite_master` reports bar the excluded two, and
     * the ones with nothing in them are named in the file so it can be checked.
     */
    @Test
    fun everyTableIsRead() {
        val export = DatabaseBackup.export(ctx)

        val inDatabase = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata'", null
        ).use { c -> while (c.moveToNext()) inDatabase.add(c.getString(0)) }

        val expected = inDatabase.filter { it !in DatabaseBackup.EXCLUDED }
        assertEquals(
            "the backup must read every table bar the excluded two",
            expected.size, export.scanned
        )
        assertEquals(
            "carried + empty must account for every table read",
            export.scanned, export.tables + export.empty.size
        )
        // Each empty table is named in the file, so a reader can see it was looked at.
        export.empty.forEach {
            assertTrue("$it should be named in the file", export.sql.contains(it))
        }
        println(
            "BACKUP SCAN: ${export.scanned} tables read, ${export.tables} held " +
                "${export.rows} rows, ${export.empty.size} empty -> ${export.empty}"
        )
    }

    /**
     * A restored row keeps the store it was taken under, untouched.
     *
     * Nothing is rewritten on the way in: the backup carries the registration too,
     * so the store those rows refer to comes back with them and the pair still agree.
     * An earlier version re-tagged restored rows to whatever store the device was
     * already registered as - this pins that it no longer does, because the row and
     * the registration have to match and the registration is now the backup's.
     */
    @Test
    fun restoredRowsKeepTheirOwnStore() {
        // A product belonging to some other shop, as a backup off another device
        // carries. Foreign keys come off to plant it: md_products.store_id references
        // md_registration, and that store does not exist here - precisely the state a
        // restore passes through before the registration lands.
        val foreignStore = 4242
        db.setForeignKeyConstraintsEnabled(false)
        val productId = try {
            db.insert(
                DatabaseHelper.Tables.MD_PRODUCTS, null,
                ContentValues().apply {
                    put("product_name", "FOREIGN STORE ITEM")
                    put("store_id", foreignStore)
                }
            )
        } finally {
            db.setForeignKeyConstraintsEnabled(true)
        }
        assertTrue("could not seed the foreign product", productId != -1L)

        val export = DatabaseBackup.export(ctx)
        val result = DatabaseBackup.restore(ctx, export.sql.lineSequence())
        assertTrue("restore reported: ${result.error}", result.ok)

        db.rawQuery(
            "SELECT store_id FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE id = ?",
            arrayOf(productId.toString())
        ).use { c ->
            assertTrue("the product did not come back", c.moveToFirst())
            assertEquals(
                "a restored row must come back exactly as it was taken",
                foreignStore.toString(), c.getString(0)
            )
        }

        db.setForeignKeyConstraintsEnabled(false)
        try {
            db.delete(DatabaseHelper.Tables.MD_PRODUCTS, "id = ?", arrayOf(productId.toString()))
        } finally {
            db.setForeignKeyConstraintsEnabled(true)
        }
    }

    @Test
    fun aFileThatIsNotABackupChangesNothing() {
        val before = count(DatabaseHelper.Tables.MD_PRODUCTS)
        val result = DatabaseBackup.restore(ctx, sequenceOf("just some text", "not a backup at all"))
        assertTrue("a file with no rows should be refused", !result.ok)
        assertEquals("nothing may be touched", before, count(DatabaseHelper.Tables.MD_PRODUCTS))
    }

    private fun count(table: String): Int =
        db.rawQuery("SELECT count(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
}
