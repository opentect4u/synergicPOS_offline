package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Payment Wise Report: how the period's takings were paid for.
 *
 * A line per payment mode - how many bills were settled that way and what was
 * actually collected against them - for one mode or for all of them, as asked.
 *
 * ## What "paid amount" means here
 *
 * `td_payments.amount_paid` is what was **tendered**, not what the shop kept: hand
 * over a 500 note for a 470 bill and the row records 500 paid with 30 as
 * `change_amount` (see `BillDao.createBill` and the tills that call it). So the
 * figure reported is `amount_paid - change_amount`, which is the money that stayed
 * in the drawer. Summing `amount_paid` alone would overstate a cash day by exactly
 * the change given out, and would do it invisibly - the total would simply be too
 * big, with nothing on the report to say why.
 *
 * ## Which bills are in the period
 *
 * Dated by the bill rather than by the payment row. A payment carries its own
 * timestamp, but reporting off it would put a bill in one period on this report and
 * another period on the Bill Wise Report, and the two would stop reconciling for
 * reasons nobody could see. Voided and cancelled bills are left out here for the
 * same reason they are left out there: they are not sales.
 */
class PaymentWiseReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One payment mode on the report. */
    data class Line(
        /** CASH, CARD, UPI, ONLINE, CHEQUE, CREDIT - as stored, upper case. */
        val mode: String,
        /**
         * Bills settled this way. A bill split across two modes counts on both
         * lines, because it was genuinely paid for both ways - see [Report.totalBills].
         */
        val billCount: Int,
        /** What was collected - tendered less change given back. */
        val paidAmount: Double
    )

    /**
     * The whole report: the period asked for, the mode asked for, and the modes
     * found inside it.
     *
     * Totalled from the listed lines rather than by a second query, so the summary
     * and the rows above it cannot disagree.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        /** The mode asked for, or [ALL] where every mode was wanted. */
        val mode: String,
        val lines: List<Line>
    ) {
        val modeCount: Int get() = lines.size

        /**
         * Bills paid across every mode listed.
         *
         * The sum of the lines, not a count of distinct bills: a bill settled half in
         * cash and half by card is one bill but two payments, and a payment-wise
         * report that counted it once would have to decide which mode to leave it
         * off - which is the one thing this report exists to say.
         */
        val totalBills: Int get() = lines.sumOf { it.billCount }

        val totalPaid: Double get() = BillRounding.toPaise(lines.sumOf { it.paidAmount })

        /**
         * What was billed on credit - money owed rather than money taken.
         *
         * Reported apart from the rest because it is not takings: a day that billed
         * ten thousand of which three was on credit had seven in the drawer, and a
         * single total would say otherwise. The slip states both and then their sum,
         * which is what the bills came to.
         */
        val creditAmount: Double
            get() = BillRounding.toPaise(
                lines.filter { it.mode == CREDIT }.sumOf { it.paidAmount }
            )

        /** What was actually collected - everything that was not billed on credit. */
        val collectedAmount: Double
            get() = BillRounding.toPaise(
                lines.filterNot { it.mode == CREDIT }.sumOf { it.paidAmount }
            )

        val isEmpty: Boolean get() = lines.isEmpty()
    }

    /**
     * Payments taken between [fromDate] and [toDate] inclusive, both `yyyy-MM-dd`,
     * grouped by mode, biggest first.
     *
     * [mode] is a single stored mode ("CASH", "CARD" …) or [ALL] for every one of
     * them. Asking for a mode the period holds none of gives an empty report rather
     * than a line of zeroes - there is nothing to report, and a zero row would read
     * as though there had been.
     *
     * A payment saved with no mode on it falls back to the bill's own type, exactly
     * as [BillWiseReportDao] does when it fills the PAY MODE column, so one bill
     * cannot be called CASH on one report and nothing at all on the other.
     */
    fun between(fromDate: String, toDate: String, mode: String = ALL): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // Resolved once and used for the grouping, the filter and the output, so the
        // three cannot disagree about what a payment's mode is.
        val modeExpr =
            "UPPER(COALESCE(NULLIF(TRIM(p.payment_mode), ''), NULLIF(TRIM(b.bill_type), ''), 'UNKNOWN'))"
        val modeClause = if (mode == ALL) "" else "AND $modeExpr = ?"

        // What stayed in the drawer: tendered less the change handed back.
        val collected = "(COALESCE(p.amount_paid, 0) - COALESCE(p.change_amount, 0))"

        // substr(...,1,10): bill_date is written as yyyy-MM-dd, but a row that ever
        // carried a time would sort outside the range on its final day.
        val sql = """
            SELECT $modeExpr AS mode,
                   COUNT(DISTINCT p.bill_id),
                   COALESCE(SUM($collected), 0)
            FROM ${DatabaseHelper.Tables.TD_PAYMENTS} p
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = p.bill_id
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
              $modeClause
            GROUP BY mode
            ORDER BY COALESCE(SUM($collected), 0) DESC, mode ASC
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate).apply {
            if (store != null) add(store.toString())
            if (mode != ALL) add(mode)
        }

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                lines.add(
                    Line(
                        mode = c.getString(0).orEmpty().ifBlank { "UNKNOWN" },
                        billCount = c.getInt(1),
                        paidAmount = BillRounding.toPaise(c.getDouble(2))
                    )
                )
            }
        }
        return Report(fromDate, toDate, mode, lines)
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

    companion object {
        /** Every mode, rather than one of them - what [between]'s `mode` takes to mean "all". */
        const val ALL = "ALL"

        /** The one mode that is owed rather than taken - see [Report.creditAmount]. */
        const val CREDIT = "CREDIT"

        /**
         * The modes the till can record, in the order the dropdown offers them.
         *
         * Taken from the `payment_mode` check constraint on td_payments rather than
         * from what happens to be in the books: a mode has to be offerable before any
         * payment is taken in it, and a shop that has not yet been paid by cheque
         * still needs to be able to ask whether it has.
         */
        val MODES = listOf("CASH", "CARD", "UPI", "ONLINE", "CHEQUE", "CREDIT")
    }
}
