package com.example.synergic_pos_offline.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.synergic_pos_offline.database.AppSettingsDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Throws away transactions once they are older than the shop has chosen to keep
 * them, so a till does not carry every sale it has ever made for ever.
 *
 * ## The rule
 *
 * A ROLLING WINDOW, moved on at every login. On 01-07-2027 a till keeping one year
 * deletes everything dated 01-07-2026 and earlier; the next day it deletes
 * 02-07-2026, and so on - one day falling off the back each day, rather than a
 * year's worth going at once on some anniversary. What stands is always the last
 * [Window.days] days, today included.
 *
 * Nothing happens until the shop has been trading longer than the window: a till
 * that opened on 01-07-2026 has nothing old enough to lose until 01-07-2027, which
 * is the first day its opening day falls outside the window.
 *
 * ## When it runs
 *
 * At login - see [runOnLogin] - and only there. That is the one moment a till is
 * reliably awake, nobody is mid-sale, and no report is open on the rows about to go.
 * Running it more often would buy nothing: what ages out changes once a day.
 *
 * It is safe to run repeatedly. The second login of a day finds nothing left to
 * delete, does nothing, and in particular takes no backup.
 *
 * ## What goes, and what does not
 *
 * The transactions, and everything that is part of one: bills with their lines,
 * payments, prints and kitchen orders; the returns made against them; cancelled
 * bills; credit recoveries; ledger entries; and stock movements.
 *
 * What is NOT touched, deliberately, is everything describing the shop as it is
 * TODAY rather than what it did:
 *
 * - **What customers owe.** `balance_amount` is a figure held on the customer, not a
 *   sum over the ledger, so clearing old ledger rows cannot quietly write off a
 *   debt. The debt stays; only the history of how it was run up goes.
 * - **Stock on hand.** Also a figure held on the product, not a sum over the
 *   movements. The goods are where they are whatever became of the paperwork.
 * - **Open tables and running orders.** A running order is a sale that has not
 *   finished, not a transaction that has aged - it has no business being deleted
 *   however long the table has been sitting there.
 * - **Products, customers, users, settings.** None of it is a transaction.
 *
 * ## Why a backup comes first
 *
 * This is the only destructive thing in the app that nobody presses a button for,
 * and the one thing a mistake here cannot be is undone. So a full backup is taken
 * before the first deletion of the day, and IF IT CANNOT BE WRITTEN NOTHING IS
 * DELETED - the same contract the Erase Bills flow keeps. A shop that has run out of
 * room on the tablet keeps its old transactions until somebody makes room.
 */
object TransactionRollOver {

    /**
     * How long transactions are kept - counted in DAYS, not calendar years.
     *
     * A day count is what makes the window roll evenly: a calendar year would take
     * two days off at once each leap year and none the day before, and "keep 365
     * days" is what was actually asked for.
     *
     * 1.5 years is 365 + 183. The half year is rounded UP rather than down, because
     * the two directions are not equally wrong: keeping a day too long costs a few
     * rows on disk, deleting a day too early costs the shop a day of its books.
     */
    enum class Window(val label: String, val days: Int) {
        ONE_YEAR("1 year", 365),
        EIGHTEEN_MONTHS("1.5 years", 548);

        companion object {
            /** [label] read back, or [ONE_YEAR] for anything else - including nothing
             *  stored at all, which is every till before this setting existed. */
            fun fromStored(v: String?): Window =
                entries.firstOrNull { it.label.equals(v?.trim(), ignoreCase = true) } ?: ONE_YEAR
        }
    }

    /** What the About screen's dropdown offers, in the order it offers them. */
    val CHOICES: List<Window> = Window.entries.toList()

    /** The window a till keeps when nobody has chosen. */
    val DEFAULT = Window.ONE_YEAR

    private const val KEY_WINDOW = "Transaction Roll Over"

    /** How far back this till keeps its transactions. */
    fun window(context: Context): Window =
        Window.fromStored(AppSettingsDao(context).get(KEY_WINDOW))

    /** Stores the chosen window. Nothing is deleted here - that waits for a login. */
    fun save(context: Context, window: Window) {
        AppSettingsDao(context).put(KEY_WINDOW, window.label)
    }

    /**
     * The last date that is TOO OLD to keep - everything on or before it goes.
     *
     * `today - days`. On 01-07-2027 with a 365-day window that is 01-07-2026, so
     * 01-07-2026 is deleted and 02-07-2026 onwards stands: 365 days kept, counting
     * today. The day before, the cutoff was 30-06-2026 and 01-07-2026 was still
     * safe - which is the window moving on by exactly one day, once a day.
     *
     * Formatted `yyyy-MM-dd` to match how every transaction date is stored, so the
     * comparison is a plain string one and no date parsing is needed in SQL.
     */
    fun cutoff(window: Window, today: Date = Date()): String {
        val calendar = Calendar.getInstance().apply {
            time = today
            add(Calendar.DAY_OF_YEAR, -window.days)
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
    }

    /** What a roll-over did. [backupTo] is null when there was nothing to delete. */
    data class Outcome(
        val window: Window,
        val cutoff: String,
        val bills: Int = 0,
        val otherRows: Int = 0,
        val backupTo: String? = null,
        val error: String? = null
    ) {
        val deletedAnything: Boolean get() = bills > 0 || otherRows > 0
    }

    /**
     * Ages out whatever has fallen outside the window, backing up first.
     *
     * BLOCKING, and it reads and writes every transaction table - so it belongs on a
     * worker thread. LoginFragment runs it in the background once the operator has
     * already been let in, because nothing it deletes is anything the till is about
     * to show: the newest row it can touch is a year old.
     *
     * Never throws. A roll-over that fails is a till that keeps its old transactions
     * for another day, which is a great deal better than a till that will not let
     * anybody log in - so every failure comes back as [Outcome.error], for the log,
     * and the login carries on regardless.
     */
    fun runOnLogin(context: Context): Outcome {
        val window = window(context)
        val cutoff = cutoff(window)
        return try {
            val db = DatabaseHelper.getInstance(context).writableDatabase

            // ASKED BEFORE ANYTHING IS BACKED UP, so an ordinary login - which is
            // almost every login - costs two cheap counts and no more. Only the first
            // login of a day finds anything here, and only that one pays for a backup.
            val doomed = billsOlderThan(db, cutoff)
            val others = countOtherRows(db, cutoff)
            if (doomed.isEmpty() && others == 0) return Outcome(window, cutoff)

            // THE BACKUP IS A PRECONDITION, not a courtesy. If it cannot be written
            // nothing is deleted: this is the one destructive thing here that happens
            // without anybody asking for it, so the copy has to exist first.
            val backup = AutoBackup.backupBefore(context, "roll over transactions")

            var otherDeleted = 0
            db.beginTransaction()
            try {
                deleteBills(db, doomed)
                otherDeleted = deleteOtherRows(db, cutoff)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            android.util.Log.i(
                TAG,
                "kept ${window.days} days: deleted ${doomed.size} bill(s) and $otherDeleted " +
                    "other row(s) dated on or before $cutoff; backed up to $backup"
            )
            Outcome(window, cutoff, doomed.size, otherDeleted, backup)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Roll-over did not run", e)
            Outcome(window, cutoff, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private const val TAG = "RollOver"

    // ---- Finding what has aged out ---------------------------------------------

    /**
     * The receipt numbers of every bill dated on or before [cutoff].
     *
     * Read into a list rather than left as a subquery, because the deletions below
     * empty the very tables such a subquery would read: it would still be true for
     * the first statement and false by the fifth, taking a bill's lines away and
     * leaving the bill itself standing.
     */
    private fun billsOlderThan(db: SQLiteDatabase, cutoff: String): List<Long> {
        val ids = mutableListOf<Long>()
        db.rawQuery(
            "SELECT receipt_no FROM ${DatabaseHelper.Tables.TD_BILLS} " +
                "WHERE bill_date IS NOT NULL AND bill_date <= ?",
            arrayOf(cutoff)
        ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        return ids
    }

    /** The transaction rows that age out on their own date rather than a bill's. */
    private val STANDALONE = listOf(
        DatabaseHelper.Tables.TD_BILLS_DELETE to "bill_date",
        DatabaseHelper.Tables.TD_SALE_RETURNS to "return_date",
        DatabaseHelper.Tables.TD_ADVANCE_PAYMENTS to "payment_date",
        DatabaseHelper.Tables.TD_CUSTOMER_LEDGER to "transaction_date",
        DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS to "transaction_date"
    )

    private fun countOtherRows(db: SQLiteDatabase, cutoff: String): Int =
        STANDALONE.sumOf { (table, column) ->
            runCatching {
                db.rawQuery(
                    "SELECT COUNT(*) FROM $table WHERE $column IS NOT NULL AND $column <= ?",
                    arrayOf(cutoff)
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            }.getOrDefault(0)
        }

    // ---- Deleting it -----------------------------------------------------------

    /**
     * Deletes [bills] and everything hanging off them, children before parents.
     *
     * Foreign keys are enforced on this database, so a table missed here does not
     * leave an orphan behind - it fails the statement and rolls the whole roll-over
     * back, which is the outcome to want. The order is the one thing that has to be
     * right, and two steps in it are not obvious:
     *
     * - the LEDGER goes before the payments, because a ledger row points at the
     *   payment it recorded; deleting that payment first is the violation.
     * - the RETURN LINES go before both the returns and the bill lines, because each
     *   one points at both.
     */
    private fun deleteBills(db: SQLiteDatabase, bills: List<Long>) {
        if (bills.isEmpty()) return
        val ids = bills.joinToString(",")
        val t = DatabaseHelper.Tables

        db.execSQL(
            "DELETE FROM ${t.TD_RETURN_ITEMS} WHERE bill_item_id IN " +
                "(SELECT id FROM ${t.TD_BILL_ITEMS} WHERE bill_id IN ($ids))"
        )
        db.execSQL(
            "DELETE FROM ${t.TD_RETURN_ITEMS} WHERE return_id IN " +
                "(SELECT id FROM ${t.TD_SALE_RETURNS} WHERE original_bill_id IN ($ids))"
        )
        db.execSQL("DELETE FROM ${t.TD_SALE_RETURNS} WHERE original_bill_id IN ($ids)")
        db.execSQL("DELETE FROM ${t.TD_CUSTOMER_LEDGER} WHERE bill_id IN ($ids)")
        db.execSQL(
            "DELETE FROM ${t.TD_CUSTOMER_LEDGER} WHERE payment_id IN " +
                "(SELECT id FROM ${t.TD_PAYMENTS} WHERE bill_id IN ($ids))"
        )
        db.execSQL("DELETE FROM ${t.TD_BILL_PRINTS} WHERE bill_id IN ($ids)")
        db.execSQL("DELETE FROM ${t.TD_PAYMENTS} WHERE bill_id IN ($ids)")
        db.execSQL(
            "DELETE FROM ${t.TD_KOT_ITEMS} WHERE kot_id IN " +
                "(SELECT id FROM ${t.TD_KOT} WHERE bill_id IN ($ids))"
        )
        db.execSQL("DELETE FROM ${t.TD_KOT} WHERE bill_id IN ($ids)")
        db.execSQL("DELETE FROM ${t.TD_BILL_ITEMS} WHERE bill_id IN ($ids)")
        db.execSQL("DELETE FROM ${t.TD_BILLS} WHERE receipt_no IN ($ids)")
    }

    /**
     * Deletes the aged rows not reached through a bill, and reports how many.
     *
     * A sale return dated before the cutoff whose bill has already gone; a credit
     * recovery, which belongs to a customer and never to a bill; the ledger and the
     * stock movements; and the cancelled-bill archive. Return LINES go first, for the
     * same reason as above.
     */
    private fun deleteOtherRows(db: SQLiteDatabase, cutoff: String): Int {
        val t = DatabaseHelper.Tables
        db.execSQL(
            "DELETE FROM ${t.TD_RETURN_ITEMS} WHERE return_id IN " +
                "(SELECT id FROM ${t.TD_SALE_RETURNS} WHERE return_date IS NOT NULL " +
                "AND return_date <= ?)",
            arrayOf(cutoff)
        )
        var rows = 0
        STANDALONE.forEach { (table, column) ->
            rows += db.delete(table, "$column IS NOT NULL AND $column <= ?", arrayOf(cutoff))
        }
        return rows
    }
}
