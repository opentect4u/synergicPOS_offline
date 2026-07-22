package com.example.synergic_pos_offline.database

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens the real on-device database through [DatabaseHelper], which runs any
 * pending migration, and checks that the v2 upgrade both preserves the existing
 * rows and accepts the new 'CARD' bill type.
 */
@RunWith(AndroidJUnit4::class)
class BillTypeMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun upgradeKeepsRowsAndAcceptsCardBillType() {
        val db = DatabaseHelper.getInstance(context).writableDatabase

        // The migration must not have thrown away anything.
        assertTrue("bills were lost by the migration", countOf(db, "td_bills") > 0)
        assertTrue("users were lost by the migration", countOf(db, "md_users") > 0)
        assertTrue("registration was lost", countOf(db, "md_registration") > 0)

        // Referential integrity survives the parent-table rebuild.
        db.rawQuery("PRAGMA foreign_key_check", null).use { c ->
            assertEquals("dangling references after migration", 0, c.count)
        }

        // The whole point: 'CARD' is now a legal bill_type.
        val before = countOf(db, "td_bills")
        val id = db.insert(
            DatabaseHelper.Tables.TD_BILLS, null,
            ContentValues().apply {
                put("bill_number", "MIGRATION-TEST")
                put("bill_type", "CARD")
                put("net_amount", 1.0)
                put("bill_status", "COMPLETED")
            }
        )
        assertTrue("CARD bill_type was rejected by the CHECK constraint", id > 0)

        db.rawQuery(
            "SELECT bill_type FROM td_bills WHERE receipt_no = ?", arrayOf(id.toString())
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("CARD", c.getString(0))
        }

        // Leave the table as it was found.
        db.delete(DatabaseHelper.Tables.TD_BILLS, "receipt_no = ?", arrayOf(id.toString()))
        assertEquals(before, countOf(db, "td_bills"))
    }

    @Test
    fun allFourBillTypesAreAccepted() {
        val db = DatabaseHelper.getInstance(context).writableDatabase
        listOf("CASH", "CREDIT", "CARD", "ONLINE").forEach { type ->
            val id = db.insert(
                DatabaseHelper.Tables.TD_BILLS, null,
                ContentValues().apply {
                    put("bill_number", "TYPE-TEST-$type")
                    put("bill_type", type)
                    put("net_amount", 1.0)
                    put("bill_status", "COMPLETED")
                }
            )
            assertTrue("bill_type '$type' was rejected", id > 0)
            db.delete(DatabaseHelper.Tables.TD_BILLS, "receipt_no = ?", arrayOf(id.toString()))
        }
    }

    private fun countOf(db: android.database.sqlite.SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT count(*) FROM $table", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
}
