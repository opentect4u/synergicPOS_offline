package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Void Bill Report: the bills that were taken out of the takings.
 *
 * Two things end up here, and they are the same thing to anyone reading the report:
 *
 * - **Deleted bills** - moved to [DatabaseHelper.Tables.TD_BILLS_DELETE] by
 *   [BillDeleteDao], which is what takes them out of every other report at a stroke.
 * - **Voided bills** - still in td_bills but flagged `is_voided`, which every sales
 *   report already excludes.
 *
 * Both are sales that did not happen. Neither appears anywhere else on this till, so
 * if they were not gathered here they would have left no trace at all - which is the
 * one thing a void report exists to prevent.
 */
class VoidBillReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One bill that was voided or deleted. */
    data class Line(
        val billNumber: String,
        /** Value of the goods as listed, before tax was added. */
        val amount: Double,
        /** Everything it would have charged in tax, however the regime split it. */
        val tax: Double,
        /** What it would have come to - the bill's own net. */
        val total: Double
    )

    /**
     * The whole report: the period asked for and every bill voided inside it.
     *
     * Totalled from the listed lines rather than by a second query, so the figure at
     * the foot is the figures above it by construction.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val lines: List<Line>
    ) {
        val billCount: Int get() = lines.size
        val totalAmount: Double get() = total { it.amount }
        val totalTax: Double get() = total { it.tax }

        /** What was taken out of the takings - what the report is read for. */
        val grandTotal: Double get() = total { it.total }

        val isEmpty: Boolean get() = lines.isEmpty()

        private fun total(pick: (Line) -> Double): Double =
            BillRounding.toPaise(lines.sumOf { pick(it) })
    }

    /**
     * Every voided or deleted bill dated between [fromDate] and [toDate] inclusive,
     * both `yyyy-MM-dd`, oldest first.
     *
     * By the bill's own date, not by when it was voided: nothing records when a bill
     * was deleted, and a void belongs to the day's takings it was taken out of - it
     * is read against that day's till, not against the day somebody noticed.
     *
     * Read off the columns the bill totalled when it was saved, so a voided bill
     * shows what it would have come to had it stood.
     */
    fun between(fromDate: String, toDate: String): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        val figures = """
            COALESCE(b.bill_number, ''), COALESCE(b.tot_price, 0),
            COALESCE(b.tot_cgst_amount, 0) + COALESCE(b.tot_sgst_amount, 0)
          + COALESCE(b.tot_igst_amount, 0) + COALESCE(b.tot_vat_amount, 0),
            COALESCE(b.net_amount, 0), b.receipt_no
        """.trimIndent()

        // substr(...,1,10): bill_date is written as yyyy-MM-dd, but a row that ever
        // carried a time would sort outside the range on its final day.
        //
        // A deleted bill can also have been flagged voided before it was deleted, but
        // it is only in one of the two tables, so no bill can reach this list twice.
        val sql = """
            SELECT $figures
            FROM ${DatabaseHelper.Tables.TD_BILLS_DELETE} b
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              $storeClause
            UNION ALL
            SELECT $figures
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(b.is_voided, 0) = 1
              $storeClause
            ORDER BY 5 ASC
        """.trimIndent()

        val half = mutableListOf(fromDate, toDate).apply {
            if (store != null) add(store.toString())
        }
        val args = (half + half).toTypedArray()

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                lines.add(
                    Line(
                        billNumber = c.getString(0).orEmpty().ifBlank { "-" },
                        amount = BillRounding.toPaise(c.getDouble(1)),
                        tax = BillRounding.toPaise(c.getDouble(2)),
                        total = c.getDouble(3)
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
