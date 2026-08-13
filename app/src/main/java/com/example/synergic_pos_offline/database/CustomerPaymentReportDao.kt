package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding

/**
 * Reads the customer-payment collections (advance-payment receipts) taken in a date
 * range, for the Customer Payment Report. Each row is one collection made against a
 * customer's dues, from [DatabaseHelper.Tables.TD_ADVANCE_PAYMENTS] joined to the
 * customer master for the name.
 */
class CustomerPaymentReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One collection on the report. */
    data class Entry(
        val customerId: Long,
        val customerName: String,
        /** "yyyy-MM-dd HH:mm:ss" as stored. */
        val paymentDateTime: String,
        /** The B.NO printed - the collection's own sequence number. */
        val billNo: String,
        val paidAmount: Double,
        val balanceAmount: Double
    )

    /** The whole report for a period. */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val entries: List<Entry>,
        val totalPaid: Double
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()
    }

    /** Every collection dated between [from] and [to] (inclusive), oldest first. */
    fun between(from: String, to: String): Report {
        val entries = mutableListOf<Entry>()
        helper.readableDatabase.rawQuery(
            """
            SELECT a.customer_id, c.customer_name, a.payment_date,
                   COALESCE(a.bill_seq_no, a.receipt_number) AS bno,
                   a.advance_amount, a.remaining_balance
            FROM ${DatabaseHelper.Tables.TD_ADVANCE_PAYMENTS} a
            LEFT JOIN ${DatabaseHelper.Tables.MD_CUSTOMERS} c ON c.id = a.customer_id
            WHERE substr(a.payment_date, 1, 10) BETWEEN ? AND ?
            ORDER BY a.payment_date ASC, a.id ASC
            """.trimIndent(),
            arrayOf(from, to)
        ).use { c ->
            while (c.moveToNext()) {
                entries.add(
                    Entry(
                        customerId = c.getLong(0),
                        customerName = c.getString(1)?.takeIf { it.isNotBlank() } ?: "-",
                        paymentDateTime = c.getString(2).orEmpty(),
                        billNo = c.getString(3)?.takeIf { it.isNotBlank() } ?: "-",
                        paidAmount = if (c.isNull(4)) 0.0 else c.getDouble(4),
                        balanceAmount = if (c.isNull(5)) 0.0 else c.getDouble(5)
                    )
                )
            }
        }
        val total = BillRounding.toPaise(entries.sumOf { it.paidAmount })
        return Report(from, to, entries, total)
    }
}
