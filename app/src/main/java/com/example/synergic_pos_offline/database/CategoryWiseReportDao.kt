package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillPricing
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.BillSettingsSnapshot
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Department Report: what each part of the shop sold over a period.
 *
 * A line per category - how much of it went and what that came to. The Item Wise
 * Report gathered one level up: that one says which items moved, this one says which
 * *parts of the shop* they moved out of, which is the question asked when space,
 * buying or staff are being decided.
 *
 * ## The amount is the taxable value
 *
 * Not the line total. It is the figure the Bill Wise Report totals as BILL AMOUNT and
 * the Item Wise Report prints as AMOUNT, so a department's takings can be added
 * against either without one of them quietly carrying tax the other does not.
 *
 * Each line is run back through [BillPricing] - the same function that priced it when
 * it was sold - given its own stored inputs and the rules frozen onto its bill: the
 * regime, whether the listed price included tax, and whether the discount came off
 * before or after the rate. A line on a bill with no snapshot, or one carrying IGST
 * (which [BillPricing] does not model), falls back to what was stored, with the base
 * recovered by inverting the rate off the booked tax. See [TaxReportDao], which
 * recovers the same figure the same way.
 */
class CategoryWiseReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One department on the report. */
    data class Line(
        val category: String,
        /** How much of it went, summed across every bill in the period. */
        val quantity: Double,
        /** The value it was taxed on - see the class notes. */
        val amount: Double
    )

    /**
     * The whole report: the period asked for, and every department that sold in it.
     *
     * A department nothing sold from has no line, the same way the Item Wise Report
     * lists no item that did not move. What did not sell is the Unsold Product
     * Report's question, and it answers it per product rather than per department.
     *
     * Totalled from the listed lines rather than by a second query, so the summary
     * and the rows above it cannot disagree.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val lines: List<Line>
    ) {
        val categoryCount: Int get() = lines.size
        val totalQuantity: Double get() = total { it.quantity }
        val totalAmount: Double get() = total { it.amount }

        /** The best-selling department of the period. Null when the period is empty. */
        val best: Line? get() = lines.firstOrNull()

        val isEmpty: Boolean get() = lines.isEmpty()

        private fun total(pick: (Line) -> Double): Double =
            BillRounding.toPaise(lines.sumOf { pick(it) })
    }

    /** What one department has accumulated so far, before it becomes a [Line]. */
    private class Sum {
        var quantity = 0.0
        var amount = 0.0
    }

    /**
     * Every department that sold between [fromDate] and [toDate] inclusive, both
     * `yyyy-MM-dd`, biggest takings first - which is the order the question is asked
     * in, and puts what matters at the top of the roll.
     *
     * Lines from voided and cancelled bills are left out, exactly as the Item Wise
     * Report leaves them out: they are not sales.
     *
     * A line whose product has since been deleted from the master, or which was never
     * filed under a department, still counts - the sale happened - and is gathered
     * under one "Uncategorised" line rather than dropped, which would quietly make
     * this report's total smaller than the Item Wise Report's over the same days.
     */
    fun between(fromDate: String, toDate: String): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // substr(...,1,10): bill_date is written as yyyy-MM-dd, but a row that ever
        // carried a time would sort outside the range on its final day.
        val sql = """
            SELECT COALESCE(NULLIF(TRIM(c.category_name), ''), '$UNCATEGORISED'),
                   COALESCE(i.rate, 0), COALESCE(i.quantity, 0),
                   COALESCE(i.cgst_rate, 0), COALESCE(i.sgst_rate, 0),
                   COALESCE(i.igst_rate, 0), COALESCE(i.vat_rate, 0),
                   COALESCE(i.discount_amount, 0),
                   COALESCE(i.cgst_amount, 0), COALESCE(i.sgst_amount, 0),
                   COALESCE(i.igst_amount, 0), COALESCE(i.vat_amount, 0),
                   COALESCE(i.item_total, 0),
                   b.settings_snapshot
            FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = i.bill_id
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON p.id = i.product_id
            LEFT JOIN ${DatabaseHelper.Tables.MD_CATEGORY} c ON c.id = p.category_id
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate).apply {
            if (store != null) add(store.toString())
        }

        // Insertion-ordered, so departments that tie on value keep a stable order
        // rather than shuffling between one generation of the report and the next.
        val sums = LinkedHashMap<String, Sum>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val sum = sums.getOrPut(c.getString(0).orEmpty().ifBlank { UNCATEGORISED }) { Sum() }
                val igstRate = c.getDouble(5)
                val igstAmount = c.getDouble(10)
                sum.quantity += c.getDouble(2)

                val snapshot = BillSettingsSnapshot.parse(c.getString(13))
                if (snapshot != null && igstRate <= 0.0 && igstAmount <= 0.0) {
                    sum.amount += BillPricing.price(
                        rate = c.getDouble(1),
                        quantity = c.getDouble(2),
                        cgstRate = c.getDouble(3),
                        sgstRate = c.getDouble(4),
                        vatRate = c.getDouble(6),
                        discountAmount = c.getDouble(7),
                        taxEnabled = snapshot.taxEnabled,
                        inclusive = snapshot.inclusive,
                        discountPreTax = snapshot.discountPreTax
                    ).taxable
                } else {
                    val rate = c.getDouble(3) + c.getDouble(4) + igstRate + c.getDouble(6)
                    val tax = c.getDouble(8) + c.getDouble(9) + igstAmount + c.getDouble(11)
                    sum.amount += if (rate > 0.0) tax * 100.0 / rate else c.getDouble(12)
                }
            }
        }

        val lines = sums.entries
            .map { (name, sum) ->
                Line(
                    category = name,
                    quantity = BillRounding.toPaise(sum.quantity),
                    amount = BillRounding.toPaise(sum.amount)
                )
            }
            .sortedByDescending { it.amount }
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

    private companion object {
        /** Where a sale lands when its product has no department, or no product left. */
        const val UNCATEGORISED = "Uncategorised"
    }
}
