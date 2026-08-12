package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Operator Wise Report: who rang up what over a period.
 *
 * A line per operator who billed on this till inside the period - how many bills
 * they raised and what those bills came to. The question a shift is settled against,
 * and the one asked when a day's takings have to be attributed to the people who
 * took them.
 *
 * Only operators who actually billed appear. A user who signed in and sold nothing
 * has no line, because the report is read off the bills rather than off the user
 * list: a register of everyone with a login would be a list of staff, not a report.
 *
 * ## Which user a bill belongs to
 *
 * A bill records its operator twice over, and the two were written by different
 * paths. [BillDao.createBill] stores `operator_id` - `md_users.id`, looked up from
 * the signed-in user - while `created_by` carries that same serial number as text,
 * written from [SessionManager.auditUser]. The older checkout path deliberately
 * skips `operator_id` and writes only `created_by`.
 *
 * So the operator is read from `operator_id` where there is one and from
 * `created_by` where there is not. Reading either alone would silently drop a whole
 * era of bills off the report, and the total at the foot would quietly stop matching
 * the Bill Wise Report over the same days.
 */
class OperatorWiseReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One operator on the report - everything they billed over the period. */
    data class Line(
        /** `md_users.id`, the user's serial number. Null where the bills name no user. */
        val serialNo: Long?,
        /** `md_users.user_id` - the login the operator signs in with. */
        val userId: String,
        val userName: String,
        val billCount: Int,
        /** What those bills came to - their net, as each was totalled when it was saved. */
        val totalAmount: Double
    ) {
        /**
         * True for the one line that stands for bills whose operator cannot be
         * resolved - see [Report.hasUnattributed].
         */
        val isUnattributed: Boolean get() = serialNo == null
    }

    /**
     * The whole report: the period asked for, and every operator who billed in it.
     *
     * Totalled from the listed lines rather than by a second query, so the summary
     * and the rows above it cannot disagree.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val lines: List<Line>
    ) {
        val operatorCount: Int get() = lines.size
        val totalBills: Int get() = lines.sumOf { it.billCount }
        val totalAmount: Double get() = BillRounding.toPaise(lines.sumOf { it.totalAmount })

        /**
         * Whether the period holds bills that name no operator this till still knows.
         *
         * They are kept rather than dropped, on their own line: dropping them would
         * make this report's total quietly smaller than the same period's Bill Wise
         * Report, and the difference would be invisible - which is a worse problem
         * than a row that has to say it does not know who took the money.
         */
        val hasUnattributed: Boolean get() = lines.any { it.isUnattributed }

        val isEmpty: Boolean get() = lines.isEmpty()
    }

    /**
     * Every operator who billed between [fromDate] and [toDate] inclusive, both
     * `yyyy-MM-dd`, biggest takings first - which is the order the question is
     * usually asked in, and puts what matters at the top of the roll.
     *
     * Voided and cancelled bills are left out, exactly as the other reports leave
     * them out: they are not sales, and counting them would credit an operator with
     * takings that were never taken.
     */
    fun between(fromDate: String, toDate: String): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // The operator, resolved once and used for both the join and the grouping so
        // the two cannot disagree. CAST of a non-numeric created_by yields 0, which
        // matches no user - such a bill lands on the unattributed line rather than
        // being credited to whichever user happens to hold id 0.
        val operator = "COALESCE(b.operator_id, CAST(NULLIF(TRIM(b.created_by), '') AS INTEGER))"

        // substr(...,1,10): bill_date is written as yyyy-MM-dd, but a row that ever
        // carried a time would sort outside the range on its final day.
        val sql = """
            SELECT u.id, u.user_id, u.user_name,
                   COUNT(*), COALESCE(SUM(b.net_amount), 0)
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            LEFT JOIN ${DatabaseHelper.Tables.MD_USERS} u ON u.id = $operator
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
            GROUP BY u.id
            ORDER BY COALESCE(SUM(b.net_amount), 0) DESC, u.id ASC
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate).apply {
            if (store != null) add(store.toString())
        }

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val serial = if (c.isNull(0)) null else c.getLong(0)
                lines.add(
                    Line(
                        serialNo = serial,
                        // A user deleted from the master since they billed still has
                        // their sales on the report; there is simply no name left to
                        // put against them.
                        userId = c.getString(1).orEmpty().ifBlank { if (serial == null) "-" else "#$serial" },
                        userName = c.getString(2).orEmpty().ifBlank {
                            if (serial == null) "Operator not recorded" else "Unknown user"
                        },
                        billCount = c.getInt(3),
                        totalAmount = BillRounding.toPaise(c.getDouble(4))
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
