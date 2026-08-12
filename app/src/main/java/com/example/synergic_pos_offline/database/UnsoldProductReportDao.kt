package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Unsold Product Report: what did *not* sell over a period.
 *
 * The Item Wise Report read from the other end. That one lists what moved; this one
 * lists what did not - the products the shop is carrying that nobody bought between
 * two dates, which is the question asked before a shelf is cleared, a line dropped
 * or a discount put on.
 *
 * A product counts as sold if any bill line in the period names it, so anything with
 * no such line is unsold. Lines on voided and cancelled bills do not count, exactly
 * as they do not count on the Item Wise Report: a sale that was voided did not
 * happen, and the goods went back on the shelf unsold.
 */
class UnsoldProductReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /**
     * One product nobody bought.
     *
     * The name and nothing else. There are no figures to report against a product
     * that did not sell: a rate is what it would have fetched rather than what it
     * did, and what is on the shelf is the Stock Report's question.
     */
    data class Line(
        val serial: Int,
        val name: String
    )

    /**
     * The whole report: the period asked for, and every product that saw no sale.
     *
     * [productCount] is the whole master, so the summary can say how much of the
     * range sold rather than only how much did not - "80 unsold" means one thing in
     * a shop of 90 lines and quite another in a shop of 2000.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val lines: List<Line>,
        /** Every product on the master, sold or not. */
        val productCount: Int
    ) {
        val unsoldCount: Int get() = lines.size

        /** Products that did sell in the period - the master less the unsold. */
        val soldCount: Int get() = (productCount - unsoldCount).coerceAtLeast(0)

        val isEmpty: Boolean get() = lines.isEmpty()
    }

    /**
     * Every product with no bill line against it between [fromDate] and [toDate]
     * inclusive, both `yyyy-MM-dd`, by name.
     *
     * By name rather than by value: there is no value to rank these by - that is the
     * whole point of them - and an operator checking a shelf against this list is
     * reading it alphabetically.
     *
     * `NOT EXISTS` rather than a `LEFT JOIN ... IS NULL`: it stops at the first sale
     * it finds instead of building every bill line of the period and then discarding
     * the ones that matched, which over a year of a busy till is most of them.
     */
    fun between(fromDate: String, toDate: String): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND p.store_id = ?" else ""

        // substr(...,1,10): bill_date is written as yyyy-MM-dd, but a row that ever
        // carried a time would sort outside the range on its final day - and here
        // that would mean calling a product unsold on the strength of the one sale
        // that fell off the end.
        val sql = """
            SELECT p.product_name
            FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
            WHERE NOT EXISTS (
                    SELECT 1
                      FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i
                      JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = i.bill_id
                     WHERE i.product_id = p.id
                       AND substr(b.bill_date, 1, 10) BETWEEN ? AND ?
                       AND COALESCE(b.is_voided, 0) = 0
                       AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
                  )
              $storeClause
            ORDER BY p.product_name COLLATE NOCASE ASC
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate).apply {
            if (store != null) add(store.toString())
        }

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                lines.add(
                    Line(
                        serial = lines.size + 1,
                        name = c.getString(0).orEmpty().ifBlank { "Unnamed item" }
                    )
                )
            }
        }
        return Report(fromDate, toDate, lines, productCount(store))
    }

    /** How many products the master holds, for the summary to measure the list against. */
    private fun productCount(store: Long?): Int {
        val where = if (store != null) "WHERE store_id = ?" else ""
        val args = if (store != null) arrayOf(store.toString()) else null
        return helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.MD_PRODUCTS} $where", args
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** The signed-in user's store; the registration row is the fallback. */
    private fun currentStoreId(): Long? {
        SessionManager.currentUser?.storeId?.takeIf { it != 0 }?.let { return it.toLong() }
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }
}
