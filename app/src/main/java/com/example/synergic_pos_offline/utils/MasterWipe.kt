package com.example.synergic_pos_offline.utils

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.example.synergic_pos_offline.database.DatabaseHelper

/**
 * The master-and-transaction half of Restore Defaults: every shop-entered table
 * emptied, so what is left behind is the same blank till a fresh install would be.
 *
 * [DefaultSettings.restore] already puts every SETTING back to factory - General,
 * Bill, Tax and App settings, the theme, the print template, the printers. This is
 * the other half: the DATA those settings describe. Restore Defaults used to leave
 * it alone on purpose - see [DefaultSettings]'s own history - but a full factory
 * reset is a full factory reset, and a shop asking for one wants both.
 *
 * ## What survives, and why
 *
 * [DatabaseHelper.Tables.MD_USERS] and [DatabaseHelper.Tables.MD_REGISTRATION] are
 * the one thing this deliberately does not touch: the operator has to be able to
 * log back in afterwards to see that the reset actually happened, and a wipe that
 * took the users with it would lock the till against itself. [DatabaseHelper.Tables]
 * `.MD_VERSION` is left alone too - it is the app's own record of the schema it is
 * running, not shop data, and clearing it would only confuse the next migration
 * about what it is migrating from.
 *
 * ## How a table that will not empty is handled
 *
 * Not by working out the schema's foreign-key graph by hand and deleting in that
 * order - a list of forty-odd tables is exactly the kind of thing a hand-kept order
 * quietly falls out of date the day a column is added. Instead every table in
 * [TABLES] is tried, in a loop, for as many passes as still make progress: a table
 * blocked because another table in this same list still holds rows pointing into
 * it clears on a later pass, once that other table has emptied. The order [TABLES]
 * happens to be written in does not have to be the database's own dependency order,
 * only for that order to eventually converge - which it does, because every real
 * foreign key runs from a row this wipe removes to another row this wipe also
 * removes.
 *
 * A table that is STILL not empty once no further pass makes progress is a genuine
 * exception - something outside [TABLES] is holding it, most likely [MD_USERS] or
 * [MD_REGISTRATION] carrying a reference into it. Rather than fail the whole reset
 * or delete it anyway and leave a dangling reference, that table is left as it is
 * and reported: which table would not clear, and which other table's rows are the
 * reason, read straight off SQLite's own `PRAGMA foreign_key_list` rather than a
 * second, hand-kept map of the same schema the table list already risks going stale
 * against.
 */
object MasterWipe {

    /** One table the wipe could not empty, and why. */
    data class Blocked(val table: String, val reason: String)

    /** What the wipe did. */
    data class Outcome(
        /** How many of [TABLES] ended up empty. */
        val tablesCleared: Int,
        /** Rows removed across all of them. */
        val rowsDeleted: Int,
        /** Whatever would not empty, and why - see the class doc. Empty on an
         *  ordinary reset, where every table in [TABLES] clears in one pass or a few. */
        val blocked: List<Blocked>
    )

    /**
     * Every table this wipes. Masters first, then the transactions built on them -
     * grouped for a reader's sake only; the retry loop in [wipe] does not depend on
     * this order being the database's own dependency order, see the class doc.
     *
     * [DatabaseHelper.Tables.MD_USERS], `.MD_REGISTRATION`, `.MD_VERSION` and every
     * settings table ([DatabaseHelper.Tables.MD_APP_SETTINGS] carries General, Bill,
     * Tax and App settings together) are deliberately absent - see the class doc.
     */
    private val TABLES: List<String> = listOf(
        // ---- Masters ----
        DatabaseHelper.Tables.MD_CATEGORY,
        DatabaseHelper.Tables.MD_UNITS,
        DatabaseHelper.Tables.MD_SHIFTS,
        DatabaseHelper.Tables.MD_RATE_NAME,
        DatabaseHelper.Tables.MD_CHARGES,
        DatabaseHelper.Tables.MD_PRODUCTS,
        DatabaseHelper.Tables.MD_PRODUCT_RATES,
        DatabaseHelper.Tables.MD_CUSTOMERS,
        DatabaseHelper.Tables.MD_DESCRIPTION,
        DatabaseHelper.Tables.MD_WAITERS,
        DatabaseHelper.Tables.MD_HEADERS,
        DatabaseHelper.Tables.MD_FOOTERS,
        DatabaseHelper.Tables.MD_CAPTIONS,
        DatabaseHelper.Tables.MD_LOGOS,
        DatabaseHelper.Tables.MD_QR,
        DatabaseHelper.Tables.MD_SUPPLIER,
        DatabaseHelper.Tables.MD_BATCH_STOCK,
        DatabaseHelper.Tables.MD_PRINTER,
        DatabaseHelper.Tables.MD_OPERATING_PRINTER,
        DatabaseHelper.Tables.MD_SECTION,
        DatabaseHelper.Tables.MD_TABLE,
        DatabaseHelper.Tables.MD_TABLE_UNIT,
        DatabaseHelper.Tables.MD_SUBTABLE,
        DatabaseHelper.Tables.MD_PRODUCT_NAMES,
        // ---- Transactions ----
        DatabaseHelper.Tables.TD_PURCHASE,
        DatabaseHelper.Tables.TD_PURCHASE_RETURN,
        DatabaseHelper.Tables.TD_WRITE_OFF,
        DatabaseHelper.Tables.TD_BILLS,
        DatabaseHelper.Tables.TD_BILL_ITEMS,
        DatabaseHelper.Tables.TD_BILLS_DELETE,
        DatabaseHelper.Tables.TD_BILL_ITEMS_DELETE,
        DatabaseHelper.Tables.TD_PAYMENTS,
        DatabaseHelper.Tables.TD_SALE_RETURNS,
        DatabaseHelper.Tables.TD_RETURN_ITEMS,
        DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS,
        DatabaseHelper.Tables.TD_CUSTOMER_LEDGER,
        DatabaseHelper.Tables.TD_ADVANCE_PAYMENTS,
        DatabaseHelper.Tables.TD_KOT,
        DatabaseHelper.Tables.TD_KOT_ITEMS,
        DatabaseHelper.Tables.TD_BILL_PRINTS,
        DatabaseHelper.Tables.TD_ASSIGN_WAITER,
        DatabaseHelper.Tables.TD_RUNNING_ORDER,
        DatabaseHelper.Tables.TD_RUNNING_ORDER_ITEMS
    )

    /**
     * Empties every table in [TABLES], in one transaction - the whole reset commits
     * together, or (on some exception other than a table refusing to empty) none of
     * it does.
     *
     * Blocking; the caller is expected to have taken a backup first, the same rule
     * [BillErase.erase] follows - see [AutoBackup.backupBefore].
     */
    fun wipe(context: Context): Outcome {
        val db = DatabaseHelper.getInstance(context).writableDatabase
        val remaining = TABLES.toMutableList()
        var rowsDeleted = 0
        var cleared = 0

        db.beginTransaction()
        try {
            var progress = true
            while (progress && remaining.isNotEmpty()) {
                progress = false
                val iter = remaining.iterator()
                while (iter.hasNext()) {
                    val table = iter.next()
                    val before = countOf(db, table)
                    try {
                        db.execSQL("DELETE FROM $table")
                        rowsDeleted += before
                        cleared++
                        iter.remove()
                        progress = true
                    } catch (e: SQLiteConstraintException) {
                        // Something still points into it - left for a later pass,
                        // or reported in [Outcome.blocked] if nothing ever does.
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val blocked = remaining.map { table -> Blocked(table, explain(db, table)) }
        return Outcome(cleared, rowsDeleted, blocked)
    }

    private fun countOf(db: SQLiteDatabase, table: String): Int = runCatching {
        db.rawQuery("SELECT COUNT(*) FROM $table", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.getOrDefault(0)

    /**
     * Why [table] would not empty: every other table in the database that still
     * holds rows pointing into it, read off SQLite's own `PRAGMA foreign_key_list`
     * for each table rather than a hand-kept map of this schema - a table added
     * later is covered by this without anything here having to be taught about it.
     */
    private fun explain(db: SQLiteDatabase, table: String): String {
        val holders = mutableListOf<String>()
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
                                    "SELECT COUNT(*) FROM $other WHERE $column IS NOT NULL", null
                                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                            }.getOrDefault(0)
                            if (count > 0) holders.add("$count row(s) in $other.$column")
                        }
                    }
                }
            }
        }
        return if (holders.isEmpty()) "still in use, for a reason this could not identify"
        else "still referenced by " + holders.joinToString("; ")
    }
}
