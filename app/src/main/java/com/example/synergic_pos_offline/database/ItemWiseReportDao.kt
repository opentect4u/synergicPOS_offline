package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Item Wise Report: what was sold over a period, a line per item, with how much
 * of it went and what it came to.
 *
 * The [BillWiseReportDao] read of the same books from the other side - that one
 * answers "what did each bill come to", this one "what did each item sell". Both
 * cover Restaurant and Grocery alike, because a settled restaurant order is written
 * to td_bills / td_bill_items by the same call a grocery sale is.
 *
 * Read off the bill lines as they were saved, never recomputed: those figures are
 * what was printed and what the customer paid.
 */
class ItemWiseReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One item on the report - everything sold of it over the period. */
    data class Line(
        val serial: Int,
        val name: String,
        /** How much of it went, summed across every bill in the period. */
        val quantity: Double,
        /** What it came to - the bill lines' own totals, tax and discount included. */
        val price: Double
    )

    /**
     * The whole report: the period asked for, and every item sold inside it.
     *
     * Totalled from the listed lines rather than by a second query, so the summary
     * and the rows above it cannot disagree.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val lines: List<Line>
    ) {
        val itemCount: Int get() = lines.size
        val totalQuantity: Double get() = BillRounding.toPaise(lines.sumOf { it.quantity })
        val totalPrice: Double get() = BillRounding.toPaise(lines.sumOf { it.price })
        val isEmpty: Boolean get() = lines.isEmpty()
    }

    /**
     * Everything sold between [fromDate] and [toDate] inclusive, both `yyyy-MM-dd`,
     * biggest seller by value first - which is the order the question is usually
     * asked in, and puts what matters at the top of the roll.
     *
     * Grouped by product, so an item bought on ten bills is one line of ten. Lines
     * from voided and cancelled bills are left out, exactly as the bill wise report
     * leaves those bills out: they are not sales.
     *
     * A line whose product has since been deleted from the master still counts - the
     * sale happened - and is named for what it is rather than dropped, which would
     * quietly make the report's total smaller than the day's takings.
     */
    fun between(fromDate: String, toDate: String): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // substr(...,1,10): bill_date is written as yyyy-MM-dd, but a row that ever
        // carried a time would sort outside the range on its final day.
        val sql = """
            SELECT COALESCE(NULLIF(TRIM(p.product_name), ''), 'Item #' || i.product_id, 'Unnamed item'),
                   COALESCE(SUM(i.quantity), 0),
                   COALESCE(SUM(i.item_total), 0)
            FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = i.bill_id
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON p.id = i.product_id
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
            GROUP BY COALESCE(i.product_id, -i.id)
            ORDER BY COALESCE(SUM(i.item_total), 0) DESC
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
                        name = c.getString(0).orEmpty().ifBlank { "Unnamed item" },
                        quantity = c.getDouble(1),
                        price = c.getDouble(2)
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
