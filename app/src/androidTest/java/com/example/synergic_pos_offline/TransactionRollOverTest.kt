package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.TransactionRollOver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Transactions age out of the rolling window, and nothing else goes with them.
 *
 * The rule is worth stating plainly because it is the whole feature: on any given
 * day a till keeps the last N days of transactions counting today, and the day that
 * falls off the back is deleted at the next login. A shop that opened on 01-07-2026
 * loses that opening day on 01-07-2027, the next day on 02-07-2027, and so on.
 *
 * These tests write real rows into the real database and run the real roll-over.
 * The two things that could go wrong here are both expensive: a cutoff off by one
 * day deletes a day of books early, and a delete in the wrong order leaves rows
 * pointing at bills that no longer exist. Both are checked directly.
 */
@RunWith(AndroidJUnit4::class)
class TransactionRollOverTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    /** Bills this test made, so a failure part-way does not leave them behind. */
    private val planted = mutableListOf<Long>()

    @After
    fun clearUpAfterItself() {
        planted.forEach { id ->
            runCatching { db.execSQL("DELETE FROM td_bill_items WHERE bill_id = $id") }
            runCatching { db.execSQL("DELETE FROM td_bills WHERE receipt_no = $id") }
        }
        planted.clear()
        TransactionRollOver.save(ctx, TransactionRollOver.DEFAULT)
    }

    // ---- The rule ---------------------------------------------------------------

    /**
     * The worked example, to the day.
     *
     * A shop trading from 01-07-2026 keeping one year: on 01-07-2027 the cutoff is
     * 01-07-2026, so the opening day is deleted and 02-07-2026 onward is kept. On
     * 02-07-2027 the cutoff has moved on exactly one day.
     *
     * This is the assertion an off-by-one has to get past, and an off-by-one here
     * costs a shop a day of its books a day early.
     */
    @Test
    fun theCutoffMovesOnOneDayADay() {
        val year = TransactionRollOver.Window.ONE_YEAR
        assertEquals("2026-07-01", TransactionRollOver.cutoff(year, on("2027-07-01")))
        assertEquals("2026-07-02", TransactionRollOver.cutoff(year, on("2027-07-02")))
        assertEquals("2026-07-03", TransactionRollOver.cutoff(year, on("2027-07-03")))

        // And nothing is old enough to lose until the shop has traded a full year:
        // the day before, the opening day was still inside the window.
        assertEquals("2026-06-30", TransactionRollOver.cutoff(year, on("2027-06-30")))
    }

    /** 365 days stand when the roll-over has finished, today included. */
    @Test
    fun exactlyAYearIsKept() {
        val cutoff = TransactionRollOver.cutoff(TransactionRollOver.Window.ONE_YEAR, on("2027-07-01"))
        val oldestKept = on(cutoff).let { Calendar.getInstance().apply { time = it; add(Calendar.DAY_OF_YEAR, 1) }.time }
        assertEquals("2026-07-02", day(oldestKept))
        assertEquals(
            "the window should hold 365 days counting today",
            365, daysBetween(oldestKept, on("2027-07-01")) + 1
        )
    }

    /** The longer window is the same rule with a bigger number. */
    @Test
    fun theOtherPeriodIsTheSameRule() {
        val long = TransactionRollOver.Window.EIGHTEEN_MONTHS
        assertEquals(548, long.days)
        val cutoff = TransactionRollOver.cutoff(long, on("2028-01-01"))
        assertEquals(
            "the cutoff should be exactly ${long.days} days back",
            548, daysBetween(on(cutoff), on("2028-01-01"))
        )
    }

    // ---- The setting -------------------------------------------------------------

    @Test
    fun theWindowIsRememberedAndDefaultsToAYear() {
        TransactionRollOver.save(ctx, TransactionRollOver.Window.EIGHTEEN_MONTHS)
        assertEquals(TransactionRollOver.Window.EIGHTEEN_MONTHS, TransactionRollOver.window(ctx))

        TransactionRollOver.save(ctx, TransactionRollOver.Window.ONE_YEAR)
        assertEquals(TransactionRollOver.Window.ONE_YEAR, TransactionRollOver.window(ctx))

        // A till from before this setting existed has nothing stored, and must not be
        // left with no window at all - it keeps a year, the shorter of the two.
        assertEquals(TransactionRollOver.Window.ONE_YEAR, TransactionRollOver.Window.fromStored(null))
        assertEquals(TransactionRollOver.Window.ONE_YEAR, TransactionRollOver.Window.fromStored(""))
        assertEquals(TransactionRollOver.Window.ONE_YEAR, TransactionRollOver.Window.fromStored("nonsense"))
        assertEquals(TransactionRollOver.DEFAULT, TransactionRollOver.Window.ONE_YEAR)
    }

    // ---- The deleting ------------------------------------------------------------

    /**
     * The day that has aged out goes; the day after it, and today, stay.
     *
     * THREE BILLS, one on each side of the line and one right on it. The bill dated
     * exactly on the cutoff is the one that decides whether the boundary is right,
     * and it is the one that must go: the cutoff is the last date too old to keep.
     */
    @Test
    fun onlyWhatHasAgedOutIsDeleted() {
        TransactionRollOver.save(ctx, TransactionRollOver.Window.ONE_YEAR)
        val cutoff = TransactionRollOver.cutoff(TransactionRollOver.Window.ONE_YEAR)

        val wellPast = plantBill(daysAgo(400))
        val onTheLine = plantBill(cutoff)
        val justInside = plantBill(dayAfter(cutoff))
        val today = plantBill(day(Date()))

        val outcome = TransactionRollOver.runOnLogin(ctx)
        assertTrue("the roll-over should not have failed: ${outcome.error}", outcome.error == null)

        assertFalse("a bill from 400 days ago should be gone", exists(wellPast))
        assertFalse("a bill dated on the cutoff is too old and should be gone", exists(onTheLine))
        assertTrue("the day after the cutoff is inside the window and must stay", exists(justInside))
        assertTrue("today's bill must stay", exists(today))
    }

    /**
     * A deleted bill takes its lines with it, and leaves nothing pointing at it.
     *
     * `foreign_key_check` is the real assertion. Deleting a parent before its
     * children does not silently orphan a row on this database - foreign keys are
     * enforced, so it throws and rolls back - but a table MISSED from the chain
     * entirely would leave rows behind referring to a bill that has gone, and only
     * this catches that.
     */
    @Test
    fun nothingIsLeftPointingAtADeletedBill() {
        TransactionRollOver.save(ctx, TransactionRollOver.Window.ONE_YEAR)
        val old = plantBill(daysAgo(400))
        db.execSQL(
            "INSERT INTO td_bill_items (bill_id, product_id, product_name, quantity, rate, item_subtotal) " +
                "VALUES ($old, NULL, 'Aged line', 1, 10, 10)"
        )
        assertTrue("the line should be there to start with", lineCount(old) > 0)

        val outcome = TransactionRollOver.runOnLogin(ctx)
        assertTrue("the roll-over should not have failed: ${outcome.error}", outcome.error == null)

        assertFalse("the aged bill should be gone", exists(old))
        assertEquals("its lines should have gone with it", 0, lineCount(old))

        val orphans = mutableListOf<String>()
        db.rawQuery("PRAGMA foreign_key_check", null).use { c ->
            while (c.moveToNext()) orphans.add(c.getString(0) + " row " + c.getString(1))
        }
        assertTrue("nothing should be left pointing at a deleted row: $orphans", orphans.isEmpty())
    }

    /**
     * A till with nothing old enough takes no backup and deletes nothing.
     *
     * The quiet half of the feature, and the half that runs on almost every login:
     * a roll-over that backed the whole database up every time somebody signed in
     * would be a daily cost for nothing, and would push the shop's real backups out
     * of the three that are kept.
     */
    @Test
    fun anOrdinaryLoginCostsNothing() {
        TransactionRollOver.save(ctx, TransactionRollOver.Window.ONE_YEAR)
        // Cleared first, so any aged rows this device already carries do not make
        // this test look like it took a backup of its own accord.
        TransactionRollOver.runOnLogin(ctx)

        val today = plantBill(day(Date()))
        val outcome = TransactionRollOver.runOnLogin(ctx)

        assertTrue("nothing should have failed: ${outcome.error}", outcome.error == null)
        assertFalse("nothing had aged out, so nothing should have been deleted", outcome.deletedAnything)
        assertTrue("no backup should be taken when there is nothing to delete", outcome.backupTo == null)
        assertTrue("today's bill must still be there", exists(today))
    }

    // ---- Helpers ------------------------------------------------------------------

    /** A minimal completed bill dated [date], remembered for cleaning up. */
    private fun plantBill(date: String): Long {
        db.execSQL(
            "INSERT INTO td_bills (bill_number, bill_date, bill_date_time, bill_type, " +
                "bill_status, net_amount) VALUES ('ROLLOVER-TEST', ?, ?, 'CASH', 'COMPLETED', 1)",
            arrayOf(date, "$date 10:00:00")
        )
        var id = 0L
        db.rawQuery("SELECT last_insert_rowid()", null).use { c -> if (c.moveToFirst()) id = c.getLong(0) }
        planted.add(id)
        return id
    }

    private fun exists(billId: Long): Boolean =
        db.rawQuery("SELECT COUNT(*) FROM td_bills WHERE receipt_no = $billId", null)
            .use { c -> c.moveToFirst() && c.getInt(0) > 0 }

    private fun lineCount(billId: Long): Int =
        db.rawQuery("SELECT COUNT(*) FROM td_bill_items WHERE bill_id = $billId", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private val format get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun day(date: Date): String = format.format(date)
    private fun on(date: String): Date = format.parse(date)!!

    private fun daysAgo(n: Int): String =
        day(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }.time)

    private fun dayAfter(date: String): String =
        day(Calendar.getInstance().apply { time = on(date); add(Calendar.DAY_OF_YEAR, 1) }.time)

    /** Whole days from [from] to [to], read off the calendar rather than by dividing
     *  milliseconds, so a daylight-saving change in between cannot shift it. */
    private fun daysBetween(from: Date, to: Date): Int {
        var days = 0
        val walk = Calendar.getInstance().apply { time = from }
        val end = Calendar.getInstance().apply { time = to }
        while (walk.before(end)) {
            walk.add(Calendar.DAY_OF_YEAR, 1)
            days++
        }
        return days
    }
}
