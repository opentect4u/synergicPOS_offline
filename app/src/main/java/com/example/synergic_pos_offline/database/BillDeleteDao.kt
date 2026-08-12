package com.example.synergic_pos_offline.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Deleting a bill.
 *
 * The bill is *moved*, not erased: its header goes to
 * [DatabaseHelper.Tables.TD_BILLS_DELETE] and its lines to
 * [DatabaseHelper.Tables.TD_BILL_ITEMS_DELETE], and the originals are removed.
 *
 * Moving rather than flagging is what makes this safe. Every sales report on this
 * till reads `td_bills` - a dozen of them, each with its own WHERE clause - and a
 * deleted bill leaves all of them the moment it leaves that table. A flag would have
 * meant finding every report and teaching it one more exclusion, and the one that was
 * missed would have gone on counting the bill with nothing to show that it had.
 *
 * ## Everything else that points at the bill
 *
 * Foreign keys are enforced on this database (see `DatabaseHelper.onOpen`), and six
 * tables reference `td_bills(receipt_no)`. The row cannot leave until nothing points
 * at it, so each of them has to be dealt with, and they fall into two kinds.
 *
 * **Parts of the bill**, which go with it: its lines, its payment, the kitchen
 * tickets it was made from and the log of times it was printed. None of these is a
 * document in its own right - they exist because the bill did.
 *
 * **Documents of their own**, which do not: a sale return taken against the bill, and
 * a customer ledger entry raised by it. A return has its own number and its own
 * refund; a ledger entry is money somebody owes. Deleting the sale under them would
 * either destroy a record that should outlive it or leave it pointing at nothing, so
 * the delete is refused instead and says why.
 */
class BillDeleteDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /**
     * What happened, and why not.
     *
     * A reason rather than a bare false: "could not delete the bill" tells an
     * operator nothing they can act on, and the reasons here are all things they can
     * - the bill has a return against it, or a balance outstanding.
     */
    data class Outcome(val deleted: Boolean, val reason: String? = null)

    /**
     * Moves [receiptNo] and its lines into the archive.
     *
     * All of it or none of it: a bill whose header archived but whose lines did not
     * would be a bill nobody could account for.
     */
    fun delete(receiptNo: Long): Outcome {
        if (receiptNo <= 0) return Outcome(false, "This bill cannot be deleted.")
        val db = helper.writableDatabase
        val id = arrayOf(receiptNo.toString())

        blockedBy(db, receiptNo)?.let { return Outcome(false, it) }

        db.beginTransaction()
        return try {
            val columns = archive(db, DatabaseHelper.Tables.TD_BILLS,
                DatabaseHelper.Tables.TD_BILLS_DELETE, "receipt_no = ?", id)
            if (!columns) return Outcome(false, "The deleted-bills table is missing.")

            archive(db, DatabaseHelper.Tables.TD_BILL_ITEMS,
                DatabaseHelper.Tables.TD_BILL_ITEMS_DELETE, "bill_id = ?", id)

            // The bill's own parts, children before parents so no key is left
            // dangling on the way out.
            db.execSQL(
                """
                DELETE FROM ${DatabaseHelper.Tables.TD_KOT_ITEMS}
                 WHERE kot_id IN (SELECT id FROM ${DatabaseHelper.Tables.TD_KOT} WHERE bill_id = ?)
                """.trimIndent(),
                id
            )
            listOf(
                DatabaseHelper.Tables.TD_KOT,
                DatabaseHelper.Tables.TD_PAYMENTS,
                DatabaseHelper.Tables.TD_BILL_PRINTS,
                DatabaseHelper.Tables.TD_BILL_ITEMS
            ).forEach { db.delete(it, "bill_id = ?", id) }

            db.delete(DatabaseHelper.Tables.TD_BILLS, "receipt_no = ?", id)
            db.setTransactionSuccessful()
            Outcome(true)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Could not delete bill $receiptNo", e)
            Outcome(false, e.message ?: "The bill could not be deleted.")
        } finally {
            db.endTransaction()
        }
    }

    /** Whether [receiptNo] has already been deleted - it is in the archive. */
    fun isDeleted(receiptNo: Long): Boolean =
        exists(helper.readableDatabase, DatabaseHelper.Tables.TD_BILLS_DELETE, "receipt_no", receiptNo)

    /**
     * Why this bill may not be deleted, or null if it may.
     *
     * Checked before the transaction opens rather than left to the foreign keys to
     * refuse: the constraint would report a violated key, and what the operator needs
     * to be told is which document is standing in the way.
     */
    private fun blockedBy(db: SQLiteDatabase, receiptNo: Long): String? {
        if (exists(db, DatabaseHelper.Tables.TD_SALE_RETURNS, "original_bill_id", receiptNo)) {
            return "This bill has a sale return against it. Delete the return first, " +
                "or leave the bill as it is - a return is its own document and cannot " +
                "be left pointing at a bill that is gone."
        }
        if (exists(db, DatabaseHelper.Tables.TD_CUSTOMER_LEDGER, "bill_id", receiptNo)) {
            return "This bill is on a customer's ledger. Settle or remove the ledger " +
                "entry first - deleting the sale under it would leave money owed " +
                "against nothing."
        }
        return null
    }

    private fun exists(db: SQLiteDatabase, table: String, column: String, value: Long): Boolean =
        db.rawQuery("SELECT 1 FROM $table WHERE $column = ? LIMIT 1", arrayOf(value.toString()))
            .use { it.moveToFirst() }

    /**
     * Copies the rows [where] matches from [from] into [to], naming every column.
     *
     * By name rather than `INSERT INTO ... SELECT *`, which would depend on the two
     * tables declaring their columns in the same order for ever. They are written to
     * match today; a column added to one and not the other would silently start
     * writing each value into its neighbour's field, and the archive would be quietly
     * wrong in a way nothing would report.
     *
     * The columns are those the *archive* declares, so a column added to the live
     * table and not yet mirrored is dropped rather than crashing the delete.
     */
    private fun archive(
        db: SQLiteDatabase,
        from: String,
        to: String,
        where: String,
        args: Array<String>
    ): Boolean {
        val columns = columnsOf(db, to).intersect(columnsOf(db, from).toSet()).toList()
        if (columns.isEmpty()) return false
        val names = columns.joinToString(", ")
        db.execSQL("INSERT OR REPLACE INTO $to ($names) SELECT $names FROM $from WHERE $where", args)
        return true
    }

    /** The column names [table] actually has, straight from SQLite. */
    private fun columnsOf(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildList {
                val name = c.getColumnIndex("name")
                while (c.moveToNext()) add(c.getString(name))
            }
        }

    private companion object {
        const val TAG = "BillDeleteDao"
    }
}
