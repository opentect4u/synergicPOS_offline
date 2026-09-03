package com.example.synergic_pos_offline

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.AutoBackup
import com.example.synergic_pos_offline.utils.BillErase
import com.example.synergic_pos_offline.utils.DatabaseBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Erasing the bills empties the book and starts the numbering again - and says
 * honestly when it cannot.
 *
 * The numbering is the part worth pinning. Bills, sale returns and credit
 * recoveries are numbered from one shared run, so "start again from the Start No."
 * is only true when none of the others are left; a version that promised it
 * regardless would have an operator expecting bill 1 and printing bill 94.
 */
@RunWith(AndroidJUnit4::class)
class BillEraseTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    /**
     * Every row of the two tables that say who this device is, as one comparable
     * string - so a test can assert nothing about them moved.
     */
    private fun identity(): String {
        val out = StringBuilder()
        listOf(DatabaseHelper.Tables.MD_REGISTRATION, DatabaseHelper.Tables.MD_USERS)
            .forEach { table ->
                out.append("-- $table\n")
                db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { c ->
                    while (c.moveToNext()) {
                        (0 until c.columnCount).joinTo(out, "|") { c.getString(it) ?: "" }
                        out.append('\n')
                    }
                }
            }
        return out.toString()
    }

    /**
     * Starts each test from a till with no sales of any kind.
     *
     * The returns and recoveries are cleared as well as the bills: they share the
     * counter, so leaving whatever the device happened to have would make the
     * numbering assertions depend on the order the tests had been run in.
     */
    @Before
    fun emptyTheBook() {
        BillSettingsDao(ctx).clearAllBills()
        db.delete(DatabaseHelper.Tables.TD_RETURN_ITEMS, null, null)
        db.delete(DatabaseHelper.Tables.TD_SALE_RETURNS, null, null)
        db.delete(DatabaseHelper.Tables.TD_ADVANCE_PAYMENTS, null, null)
    }

    /** Puts [count] bills in the book, numbered from [firstSeq]. */
    private fun addBills(count: Int, firstSeq: Int = 1) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        repeat(count) { i ->
            val seq = firstSeq + i
            db.execSQL(
                "INSERT INTO ${DatabaseHelper.Tables.TD_BILLS} " +
                    "(bill_number, bill_seq_no, bill_date, bill_type, net_amount, bill_status) " +
                    "VALUES (?, ?, ?, 'CASH', 100.0, 'COMPLETED')",
                arrayOf(seq.toString(), seq, today)
            )
        }
    }

    /**
     * The Start No. only decides the first number when numbering runs on
     * indefinitely, so the reset mode is pinned here rather than inherited from
     * whatever the device was last left set to.
     */
    private fun settings(startBillNo: Int) {
        val dao = BillSettingsDao(ctx)
        dao.save(
            dao.load().copy(
                startBillNo = startBillNo, resetMode = BillSettingsDao.ResetMode.CONTINUE
            )
        )
    }

    @Test
    fun theBillsGoAndTheNumberingStartsFromTheStartNo() {
        settings(startBillNo = 100)
        addBills(3)
        assertEquals(3, BillErase.preview(ctx).bills)

        val outcome = BillErase.erase(ctx)

        assertEquals(3, outcome.bills)
        assertEquals("no bill should be left", 0, BillErase.preview(ctx).bills)
        assertEquals("the next bill continues from the Start No.", "101", outcome.nextNumber)
        assertTrue("hasBills should agree with the erase", !BillSettingsDao(ctx).hasBills())
    }

    /** The bill no. prefix is part of the number an operator is quoted afterwards. */
    @Test
    fun theNumberQuotedCarriesTheBillNoPrefix() {
        val dao = BillSettingsDao(ctx)
        dao.save(dao.load().copy(startBillNo = 0, billNoCharEnabled = true, billNoCharPrefix = "INV"))
        addBills(2)

        assertEquals("INV1", BillErase.erase(ctx).nextNumber)

        dao.save(dao.load().copy(billNoCharEnabled = false, billNoCharPrefix = ""))
    }

    /**
     * A sale return left behind holds the counter up, and the preview says so - that
     * flag is what the warning uses to stop promising a fresh start.
     */
    @Test
    fun aKeptSaleReturnHoldsTheCounter() {
        settings(startBillNo = 0)
        addBills(2)
        assertFalse(
            "nothing shares the counter yet", BillErase.preview(ctx).sharesCounter
        )

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        db.execSQL(
            "INSERT INTO ${DatabaseHelper.Tables.TD_SALE_RETURNS} " +
                "(bill_seq_no, return_date) VALUES (?, ?)",
            arrayOf(50, today)
        )

        val preview = BillErase.preview(ctx)
        assertEquals(1, preview.saleReturns)
        assertTrue("a kept sale return shares the counter", preview.sharesCounter)

        assertEquals(
            "the next bill carries on from the return, not from the Start No.",
            "51", BillErase.erase(ctx).nextNumber
        )
    }

    /**
     * Erasing the book does not touch who the device is.
     *
     * Compared row by row rather than counted: an operator who erased the bills and
     * found they could no longer sign in would have no way back into the till, and a
     * password quietly rewritten would look the same to a count.
     */
    @Test
    fun whoTheDeviceIsIsNotTouched() {
        val store = db.insert(
            DatabaseHelper.Tables.MD_REGISTRATION, null,
            ContentValues().apply {
                put("store_name", "Erase Test Store")
                put("store_gstin", "19ABCDE1234F1Z5")
            }
        )
        val user = db.insert(
            DatabaseHelper.Tables.MD_USERS, null,
            ContentValues().apply {
                put("user_id", "ERASE_IDENTITY_USER")
                put("user_name", "Erase Test")
            }
        )
        try {
            addBills(3)
            val before = identity()

            BillErase.erase(ctx)

            assertEquals(
                "the users and the registration should be left exactly as they were",
                before, identity()
            )
        } finally {
            db.delete(DatabaseHelper.Tables.MD_USERS, "id = ?", arrayOf(user.toString()))
            db.delete(
                DatabaseHelper.Tables.MD_REGISTRATION, "store_id = ?", arrayOf(store.toString())
            )
        }
    }

    /**
     * The safety backup is named after what it was taken before, so the file can be
     * found by what it protects rather than by its timestamp.
     */
    @Test
    fun aSafetyBackupIsNamedAfterWhatItPrecedes() {
        val at = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).parse("2026-08-10_14-30-00")!!

        assertEquals(
            "synergic_backup_2026-08-10_14-30-00.sql", AutoBackup.fileName(at)
        )
        assertEquals(
            "synergic_backup_2026-08-10_14-30-00_before_erase_bills.sql",
            AutoBackup.fileName(at, "erase bills")
        )
        assertEquals(
            "anything a file name cannot carry should be turned into an underscore",
            "synergic_backup_2026-08-10_14-30-00_before_restore_defaults.sql",
            AutoBackup.fileName(at, "  Restore Defaults!  ")
        )
        // The date and time still lead, so a day's files sort into the order taken.
        assertTrue(AutoBackup.fileName(at, "erase bills").startsWith("synergic_backup_2026-08-10"))
    }

    /**
     * A safety backup carries the till but not who the till is.
     *
     * The users and the registration are left out so that restoring one to undo a
     * reset does not also roll the login list back to that moment - and the file
     * says so in its own header, which is what the restore dialog reads to describe
     * itself honestly.
     */
    @Test
    fun aSafetyBackupLeavesTheUsersAndRegistrationBehind() {
        addBills(1)
        // Seeded rather than assumed: this runs on a device that may never have been
        // registered or logged into, and a table with nothing in it is left out of
        // every backup anyway - which would pass the test for the wrong reason.
        val store = db.insert(
            DatabaseHelper.Tables.MD_REGISTRATION, null,
            ContentValues().apply { put("store_name", "Erase Test Store") }
        )
        val user = db.insert(
            DatabaseHelper.Tables.MD_USERS, null,
            ContentValues().apply {
                put("user_id", "ERASE_TEST_USER")
                put("user_name", "Erase Test")
            }
        )
        try {
            val safety = DatabaseBackup.export(ctx, DatabaseBackup.DEVICE_IDENTITY).sql

            assertFalse(
                "a safety backup should not carry the users",
                safety.contains("INSERT INTO ${DatabaseHelper.Tables.MD_USERS} ")
            )
            assertFalse(
                "a safety backup should not carry the store registration",
                safety.contains("INSERT INTO ${DatabaseHelper.Tables.MD_REGISTRATION} ")
            )
            assertTrue(
                "it should still carry the bills",
                safety.contains("INSERT INTO ${DatabaseHelper.Tables.TD_BILLS} ")
            )
            assertEquals(
                "the file should say what it was taken without",
                DatabaseBackup.DEVICE_IDENTITY, DatabaseBackup.excludedIn(safety)
            )

            // And the backup an operator takes from the Backup button is unchanged.
            val full = DatabaseBackup.export(ctx).sql
            assertTrue(
                "the ordinary backup should still carry the users",
                full.contains("INSERT INTO ${DatabaseHelper.Tables.MD_USERS} ")
            )
            assertTrue(
                "the ordinary backup should still carry the registration",
                full.contains("INSERT INTO ${DatabaseHelper.Tables.MD_REGISTRATION} ")
            )
            assertTrue(
                "and should not claim to have left anything out",
                DatabaseBackup.excludedIn(full).isEmpty()
            )
        } finally {
            // Left behind, the registration would give every later test a store to be
            // scoped by that it did not ask for.
            db.delete(DatabaseHelper.Tables.MD_USERS, "id = ?", arrayOf(user.toString()))
            db.delete(
                DatabaseHelper.Tables.MD_REGISTRATION, "store_id = ?", arrayOf(store.toString())
            )
        }
    }
}
