package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding

/**
 * The calculator-mode bills of a period, for the Calculator Report: each bill's number
 * and amount, and the total. A calculator bill is an ordinary td_bills row (saved
 * through the same path as any sale), so in Calculator mode this simply lists them.
 */
class CalculatorReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One bill on the report. */
    data class Row(val billNumber: String, val amount: Double)

    data class Report(
        val fromDate: String,
        val toDate: String,
        val rows: List<Row>,
        val totalAmount: Double
    ) {
        val isEmpty: Boolean get() = rows.isEmpty()
    }

    /** Every bill dated between [from] and [to] (inclusive), oldest first. */
    fun between(from: String, to: String): Report {
        val rows = mutableListOf<Row>()
        helper.readableDatabase.rawQuery(
            """
            SELECT COALESCE(bill_number, CAST(bill_seq_no AS TEXT), CAST(receipt_no AS TEXT)) AS bno,
                   COALESCE(net_amount, 0) AS amount
            FROM ${DatabaseHelper.Tables.TD_BILLS}
            WHERE substr(bill_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(bill_status, '') <> 'CANCELLED'
            ORDER BY bill_seq_no ASC, receipt_no ASC
            """.trimIndent(),
            arrayOf(from, to)
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(Row(billNumber = c.getString(0).orEmpty(), amount = BillRounding.toPaise(c.getDouble(1))))
            }
        }
        return Report(from, to, rows, BillRounding.toPaise(rows.sumOf { it.amount }))
    }
}
