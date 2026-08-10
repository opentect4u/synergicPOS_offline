package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.DatabaseHelper

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

    /** What erasing did, and the number the next bill will carry. */
    data class Outcome(val bills: Int, val nextNumber: String)

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
     * Blocking, and the caller is expected to have taken a backup first - see
     * [AutoBackup.backupBefore].
     */
    fun erase(context: Context): Outcome {
        val bills = preview(context).bills
        BillSettingsDao(context).clearAllBills()
        return Outcome(bills = bills, nextNumber = BillDao(context).nextBillNumber())
    }
}
