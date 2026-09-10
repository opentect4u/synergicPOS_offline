package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.DatabaseHelper

/**
 * Why a master row would not delete: which other tables still hold rows pointing
 * at it, named plainly enough to put in front of an operator.
 *
 * A master screen that only ever says "cannot delete: still in use" is telling the
 * operator there is a reason without telling them what to go and fix. This reads
 * the reason straight off SQLite's own `PRAGMA foreign_key_list` for every table in
 * the database, so a screen adopting it needs to know nothing about the schema
 * beyond its own table name - and a foreign key added to some other table later is
 * covered without this file being taught about it.
 *
 * [DataTableFragment.deleteBlockedReason] is the seam every master screen already
 * has for exactly this: called before a delete is attempted, so the operator is
 * told up front rather than after a database exception. A screen wires this up by
 * returning [explain] from that override - see [ProductsFragment] for the case
 * this was written for. [MasterWipe] is the same idea at the scale of a whole
 * table rather than a handful of rows, for Restore Defaults' own factory reset.
 */
object MasterEngagement {

    /** One table still holding rows that point at the ones being deleted. */
    private data class Holder(val table: String, val count: Int)

    /**
     * A short, human summary of what is still pointing at [ids] in [table] - "used
     * by 3 bill(s) and 1 stock record(s)" rather than a table name and a number the
     * operator has to translate themselves. Null when nothing is, which is the
     * caller's cue that the rows are free to delete.
     */
    fun explain(context: Context, table: String, ids: Collection<String>): String? {
        if (ids.isEmpty()) return null
        val holders = holdersOf(context, table, ids)
        if (holders.isEmpty()) return null
        return "still used by " + holders.joinToString(", ") { h ->
            "${h.count} ${label(h.table, h.count)}"
        }
    }

    /** Every table with rows pointing into [table] at one of [ids], and how many. */
    private fun holdersOf(context: Context, table: String, ids: Collection<String>): List<Holder> {
        val db = DatabaseHelper.getInstance(context).readableDatabase
        val idList = ids.joinToString(",") { "'" + it.replace("'", "''") + "'" }
        val holders = mutableListOf<Holder>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { tables ->
            while (tables.moveToNext()) {
                val other = tables.getString(0) ?: continue
                if (other == table) continue
                runCatching {
                    db.rawQuery("PRAGMA foreign_key_list($other)", null).use { fk ->
                        val toIdx = fk.getColumnIndex("table")
                        val fromIdx = fk.getColumnIndex("from")
                        while (fk.moveToNext()) {
                            if (fk.getString(toIdx) != table) continue
                            val column = fk.getString(fromIdx) ?: continue
                            val count = runCatching {
                                db.rawQuery(
                                    "SELECT COUNT(*) FROM $other WHERE $column IN ($idList)", null
                                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                            }.getOrDefault(0)
                            if (count > 0) holders.add(Holder(other, count))
                        }
                    }
                }
            }
        }
        return holders
    }

    /**
     * A plain-English name for a table's rows - "bill(s)" rather than
     * "td_bill_items". Falls back to the table's own name, prefix stripped and
     * underscores turned to spaces, for a table this has not been taught the
     * shop's word for yet - still readable, just not idiomatic.
     */
    private fun label(table: String, count: Int): String {
        val known = mapOf(
            DatabaseHelper.Tables.TD_BILL_ITEMS to "bill",
            DatabaseHelper.Tables.TD_BILLS to "bill",
            DatabaseHelper.Tables.TD_RETURN_ITEMS to "return",
            DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS to "stock record",
            DatabaseHelper.Tables.TD_WRITE_OFF to "write-off",
            DatabaseHelper.Tables.TD_PURCHASE to "purchase",
            DatabaseHelper.Tables.TD_PURCHASE_RETURN to "purchase return",
            DatabaseHelper.Tables.TD_KOT_ITEMS to "kitchen order",
            DatabaseHelper.Tables.TD_RUNNING_ORDER_ITEMS to "order on an open table",
            DatabaseHelper.Tables.MD_PRODUCT_RATES to "rate",
            DatabaseHelper.Tables.MD_BATCH_STOCK to "stock batch",
            DatabaseHelper.Tables.TD_CUSTOMER_LEDGER to "ledger entry",
            DatabaseHelper.Tables.TD_ADVANCE_PAYMENTS to "credit recovery",
            DatabaseHelper.Tables.TD_SALE_RETURNS to "sale return",
            DatabaseHelper.Tables.TD_ASSIGN_WAITER to "table assignment"
        )
        val word = known[table] ?: table
            .removePrefix("td_").removePrefix("md_")
            .replace('_', ' ')
            .removeSuffix("s")
        return if (count == 1) word else "${word}s"
    }
}
