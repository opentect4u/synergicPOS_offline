package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.TaxSettingsDao

/**
 * Throws away the bills and starts the book again from the Start No. in Bill
 * Settings.
 *
 * ## What goes
 *
 * The bills and the things that are part of a bill rather than merely connected to
 * one: its lines, the payments taken against it, the record of it having been
 * printed, and its kitchen orders. This is [BillSettingsDao.clearAllBills] - the
 * same set the Start Bill No. change has always cleared - so the two ways of
 * restarting the numbering leave the till in the same state.
 *
 * And the floor with them: the tables still open, their splits, and every table's
 * status back to Available - Blocked ones too. A running order is simply a bill that
 * has not been written yet, so leaving those behind left the till claiming it had no
 * bills while half its tables were mid-service on orders whose kitchen tickets had
 * just been deleted underneath them. See [clearFloor].
 *
 * ## What stays, deliberately
 *
 * Sale returns, credit recoveries, the customer ledger and every customer's
 * outstanding balance, and the stock movements the sales made. Two of those are
 * worth being clear about, because an operator will meet them afterwards:
 *
 * - **Money still owed is still owed.** A credit sale put a debt on the customer;
 *   erasing the bill does not collect it, so the ledger and the balance stay and
 *   the customer is still chased for it. The bill it came from will no longer be
 *   there to look at.
 * - **The stock stays sold.** The goods left the shop. Putting the quantities back
 *   because the paperwork was thrown away would make the on-hand figures describe
 *   a shop that no longer exists.
 *
 * ## The counter
 *
 * Bill numbers are [DatabaseHelper.Tables.TD_BILLS]`.bill_seq_no`, and sale returns
 * and credit recoveries carry the same counter so that a shop's documents are
 * numbered in one unbroken run. Numbering therefore restarts from the Start No.
 * only when none of those are left either - see [Preview.sharesCounter], which is
 * what the warning tells the operator before they commit to it.
 *
 * The internal `receipt_no` is deliberately *not* restarted. It is an id, not a
 * number anybody reads, and rows that outlive the bills - a ledger entry, a credit
 * recovery - still point at the ones already handed out. Reissuing them from 1
 * would quietly attach those records to new sales.
 */
object BillErase {

    /** What erasing would cost, read before anything is deleted. */
    data class Preview(
        val bills: Int,
        val saleReturns: Int,
        val creditRecoveries: Int
    ) {
        /** Whether anything is left that shares the bill counter and so holds it up. */
        val sharesCounter: Boolean get() = saleReturns > 0 || creditRecoveries > 0
    }

    /**
     * What erasing did, and the number the next bill will carry.
     *
     * [openTables] and [tablesFreed] are the floor's share of it - how many tables were
     * still mid-service when the bills went, and how many were left reading anything
     * but Available.
     */
    data class Outcome(
        val bills: Int,
        val nextNumber: String,
        val openTables: Int = 0,
        val tablesFreed: Int = 0
    )

    /** Counts what is there now, for the warning to quote. */
    fun preview(context: Context): Preview {
        val db = DatabaseHelper.getInstance(context).readableDatabase
        fun count(table: String): Int = runCatching {
            db.rawQuery("SELECT COUNT(*) FROM $table", null)
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }.getOrDefault(0)

        return Preview(
            bills = count(DatabaseHelper.Tables.TD_BILLS),
            saleReturns = count(DatabaseHelper.Tables.TD_SALE_RETURNS),
            creditRecoveries = count(DatabaseHelper.Tables.TD_ADVANCE_PAYMENTS)
        )
    }

    /**
     * Erases the bills and reports the number the next one will take.
     *
     * The number is read back from [BillDao.nextBillNumber] afterwards rather than
     * worked out in advance: it is the same call the sale screen will make, so what
     * the operator is told is what they will actually get.
     *
     * Tax Settings go back to a fresh till's own default too - MRP, tax on, any
     * discount post-tax - see [TaxSettingsDao.resetToFreshTillDefault]. A till with
     * no bills at all reads the same whichever of the three routes emptied it
     * (Erase Bills, a Start Bill No. change, or a Tax Mode change); the Tax Mode
     * change route saves its own chosen mode straight afterward, through the
     * ordinary screen save, so the operator's actual choice still wins there rather
     * than this default.
     *
     * Blocking, and the caller is expected to have taken a backup first - see
     * [AutoBackup.backupBefore].
     */
    fun erase(context: Context): Outcome {
        val bills = preview(context).bills
        BillSettingsDao(context).clearAllBills()
        val floor = clearFloor(context)
        TaxSettingsDao(context).resetToFreshTillDefault()
        return Outcome(
            bills = bills,
            nextNumber = BillDao(context).nextBillNumber(),
            openTables = floor.first,
            tablesFreed = floor.second
        )
    }

    /**
     * Puts the floor back to empty, and returns (open tables cleared, tables freed).
     *
     * The bills going is only half of it. A running order is a bill that has not been
     * written yet - its items, its KOT and the table it is sitting on - and
     * [BillSettingsDao.clearAllBills] takes the KOT rows out from under those orders
     * while leaving the orders themselves behind. What that left was a floor of tables
     * still reading Occupied and Billing, holding orders whose kitchen tickets no
     * longer existed, on a till that had just been told it had no bills at all.
     *
     * So the open tables go with them, the splits are dropped - a sub-table is a
     * division of service, and there is no service left to divide - and every table
     * comes back to Available.
     *
     * ## EVERY table, Blocked included
     *
     * Blocked was held back at first, on the reasoning that somebody set it in the
     * Table master because that table cannot be used, and clearing the books does not
     * mend a broken leg. That is not how this erase is meant to be read: it is a
     * factory reset of the trading side, and the floor it hands back is an empty one.
     * A table that genuinely is out of service is blocked again in the Table master,
     * which is where it was blocked in the first place.
     *
     * Foreign keys are off for the same reason [BillSettingsDao.clearAllBills] turns
     * them off: the rows are deleted parent-first and the order between the two tables
     * is not worth arranging for a wipe.
     */
    private fun clearFloor(context: Context): Pair<Int, Int> {
        val db = DatabaseHelper.getInstance(context).writableDatabase
        fun count(sql: String): Int = runCatching {
            db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }.getOrDefault(0)

        val open = count("SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_RUNNING_ORDER}")
        val busy = count(
            "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.MD_TABLE} " +
                "WHERE table_status IS NOT NULL AND table_status <> 'Available'"
        )

        // FOREIGN KEYS ARE LEFT ALONE, and the rows are deleted child-first instead.
        //
        // This used to switch constraints off around the deletes, and that is what
        // broke the Tax Mode change. Android refuses to reconfigure them while ANY
        // connection is in use - not just a transaction on this thread, any acquired
        // connection anywhere in the app - and throws:
        //
        //   IllegalStateException: Foreign Key Constraints cannot be enabled or
        //   disabled while there are transactions in progress.
        //
        // This runs on a worker thread with the till's UI still live behind a spinner,
        // so whether a cursor happened to be open somewhere decided whether it threw.
        // When it did, the erase stopped here: the bills were already gone, the floor
        // was left standing, the tax mode was never reset, and BillErasePrompt's
        // onErased never ran - so the mode the operator had chosen was never saved and
        // the screen sat there showing it as though it had been. It worked the first
        // time and failed the next four, which is exactly how it was reported.
        //
        // The switch was never needed. These deletes already run child before parent -
        // items before their orders, and md_table is only updated - so nothing here
        // ever violates a constraint. Ordering is the fix; disabling enforcement was
        // only ever hiding the question of whether the order was right.
        //
        // Ordering note: td_kot points at a running order, and BillErase.erase clears
        // the KOTs (through clearAllBills) before calling this. Anything that ever
        // calls this on its own has to do the same.
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_RUNNING_ORDER_ITEMS}")
            db.execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_RUNNING_ORDER}")
            db.execSQL("DELETE FROM ${DatabaseHelper.Tables.MD_SUBTABLE}")
            db.execSQL(
                "UPDATE ${DatabaseHelper.Tables.MD_TABLE} SET table_status = 'Available'"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return open to busy
    }
}
