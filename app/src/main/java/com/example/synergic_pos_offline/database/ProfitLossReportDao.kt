package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Profit-Loss Report: what each item made over a period.
 *
 * ## How profit is worked out here
 *
 * The gap between the item's listed rate and what it was actually rung up at, times
 * the quantity that went:
 *
 * ```
 * profit = (master rate - rate charged) x quantity
 * ```
 *
 * summed over every line of every bill in the period. An item sold at its listed rate
 * makes nothing by this measure, which is why most of a shop's lines read 0.00 - the
 * figure only moves where the till charged something other than the master rate.
 *
 * That is a margin against the price list, not against cost. This app records no
 * purchase cost per product, so cost-based profit is not available to compute; the
 * master rate is the only reference price there is.
 *
 * An item with no rate on its master contributes nothing rather than its whole sale
 * value: a missing price is not a hundred per cent margin, and reporting it as one
 * would put a large invented figure at the top of the report.
 */
class ProfitLossReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One item on the report. */
    data class Line(
        val name: String,
        /** How much of it went over the period. */
        val quantity: Double,
        /** What that came to, by the measure above. */
        val profit: Double
    )

    /**
     * The whole report: the period asked for, and every item sold inside it.
     *
     * Totalled from the listed lines rather than by a second query, so the row at the
     * foot is the rows above it by construction.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val lines: List<Line>
    ) {
        val itemCount: Int get() = lines.size
        val totalQuantity: Double get() = total { it.quantity }
        val totalProfit: Double get() = total { it.profit }

        val isEmpty: Boolean get() = lines.isEmpty()

        private fun total(pick: (Line) -> Double): Double =
            BillRounding.toPaise(lines.sumOf { pick(it) })
    }

    /**
     * Every item sold between [fromDate] and [toDate] inclusive, both `yyyy-MM-dd`,
     * by name.
     *
     * By name rather than by profit: most lines make nothing by this measure, so a
     * ranked list would be one or two items followed by a long tail of zeroes in no
     * order anybody could scan. Alphabetical, an item can be found.
     *
     * Lines from voided and cancelled bills are left out, exactly as every sales
     * report leaves them out: they are not sales.
     */
    fun between(fromDate: String, toDate: String): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // The master's own rate for the product - its default one, the same row the
        // sale screen prices from. Null where the product has no rate at all, which
        // the CASE below turns into no profit rather than into all of it.
        val listed = """
            (SELECT r.rate FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES} r
              WHERE r.product_id = i.product_id
              ORDER BY "default" DESC, r.id ASC LIMIT 1)
        """.trimIndent()

        val sql = """
            SELECT COALESCE(NULLIF(TRIM(p.product_name), ''), 'Item #' || i.product_id, 'Unnamed item'),
                   COALESCE(SUM(i.quantity), 0),
                   COALESCE(SUM(
                       CASE WHEN COALESCE($listed, 0) > 0
                            THEN (COALESCE($listed, 0) - COALESCE(i.rate, 0)) * COALESCE(i.quantity, 0)
                            ELSE 0 END), 0)
            FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = i.bill_id
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON p.id = i.product_id
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
            GROUP BY COALESCE(i.product_id, -i.id)
            ORDER BY 1 COLLATE NOCASE ASC
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate).apply {
            if (store != null) add(store.toString())
        }

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                lines.add(
                    Line(
                        name = c.getString(0).orEmpty().ifBlank { "Unnamed item" },
                        quantity = BillRounding.toPaise(c.getDouble(1)),
                        profit = BillRounding.toPaise(c.getDouble(2))
                    )
                )
            }
        }
        return Report(fromDate, toDate, lines)
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
