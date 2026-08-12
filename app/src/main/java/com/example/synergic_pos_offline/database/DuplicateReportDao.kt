package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Duplicate Receipt Report: which bills were printed again, and how often.
 *
 * Every print this till makes is logged against its bill in
 * [DatabaseHelper.Tables.TD_BILL_PRINTS] by `BillReceiptRenderer.recordPrint`, which
 * every printing path calls: the checkout's own auto-print, the bill screen, and the
 * Print button in Bill history. So a duplicate is not something this report infers;
 * it is something the till recorded at the moment it happened.
 *
 * ## What counts as a duplicate
 *
 * A copy run off from Bill history, and only that. The one that came out with the
 * sale is not a duplicate of anything - it is the receipt - so it is logged ORIGINAL
 * and never counted here. A bill printed once at the counter and three times from
 * history reads 3.
 *
 * That distinction is recorded by whichever screen did the printing rather than
 * worked out afterwards. It used to be inferred from the order the prints arrived in,
 * which quietly undercounted every bill whose sale-time print had never reached the
 * log - see `BillReceiptRenderer.recordPrint`.
 *
 * ## Which date the range means
 *
 * When the duplicate was *printed*, not when the bill was raised. A duplicate report
 * is read to see what was reprinted over a period - a month-old bill run off again
 * this morning is this morning's event, and dating it to last month would hide it in
 * a range nobody thinks to look at.
 */
class DuplicateReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One bill that was run off again from Bill history. */
    data class Line(
        val billNumber: String,
        /** How many duplicates of it were printed inside the period. */
        val times: Int
    )

    /**
     * The whole report: the period asked for and every bill reprinted inside it.
     *
     * Totalled from the listed lines rather than by a second query, so the summary
     * and the rows above it cannot disagree.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val lines: List<Line>
    ) {
        val billCount: Int get() = lines.size

        /** Duplicates printed across every bill listed. */
        val totalTimes: Int get() = lines.sumOf { it.times }

        val isEmpty: Boolean get() = lines.isEmpty()
    }

    /**
     * Every bill duplicated between [fromDate] and [toDate] inclusive, both
     * `yyyy-MM-dd`, most duplicated first.
     *
     * Most first because that is the question: a bill run off six times is what an
     * audit is looking for, and it should not be somewhere down a list ordered by
     * something else.
     *
     * The count is of duplicates made *inside the range*. One taken yesterday and two
     * today reads 2 on a report of today - the third piece of paper belongs to
     * yesterday, and a range that silently counted outside itself would make two
     * reports of adjoining days add up to more than the days held.
     */
    fun between(fromDate: String, toDate: String): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // substr(...,1,10): print_date carries a time, and comparing the whole of it
        // against a plain date would drop every print made after midnight on the
        // final day of the range.
        //
        // Only the copies run off from Bill history. No HAVING is needed to keep the
        // report to duplicates - every row counted here already is one, so a bill
        // duplicated a single time belongs on the report reading 1.
        val sql = """
            SELECT COALESCE(NULLIF(TRIM(b.bill_number), ''), '-'), COUNT(*)
            FROM ${DatabaseHelper.Tables.TD_BILL_PRINTS} p
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = p.bill_id
            WHERE substr(p.print_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(p.print_type, 'ORIGINAL') <> 'ORIGINAL'
              $storeClause
            GROUP BY p.bill_id
            ORDER BY COUNT(*) DESC, b.receipt_no ASC
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate).apply {
            if (store != null) add(store.toString())
        }

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                lines.add(Line(c.getString(0).orEmpty().ifBlank { "-" }, c.getInt(1)))
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
