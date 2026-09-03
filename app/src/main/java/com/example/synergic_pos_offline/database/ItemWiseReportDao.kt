package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillPricing
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.BillSettingsSnapshot
import com.example.synergic_pos_offline.utils.CalendarGrain
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Item Wise Sale Report: what was sold over a period, a line per item, with how
 * much of it went, what it was taxed on and what tax it carried.
 *
 * The [BillWiseReportDao] read of the same books from the other side - that one
 * answers "what did each bill come to", this one "what did each item sell". Both
 * cover Restaurant and Grocery alike, because a settled restaurant order is written
 * to td_bills / td_bill_items by the same call a grocery sale is.
 *
 * ## The amount is the taxable value
 *
 * Not the line total. A sale report is read against what was taxed, and the tax is
 * stated beside it - an amount that already had the tax inside it would not add up
 * with the two columns next to it. So each line is run back through [BillPricing] -
 * the same function that priced it when it was sold - given its own stored inputs and
 * the rules frozen onto its bill: the regime, whether the listed price included tax,
 * and whether the discount came off before or after the rate. See [TaxReportDao],
 * which recovers the same figure the same way, so the two reports agree about what
 * the period's taxable value was.
 *
 * A line on a bill with no snapshot, or one carrying IGST (which [BillPricing] does
 * not model), falls back to what was stored, with the base recovered by inverting the
 * rate off the booked tax.
 */
class ItemWiseReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One item on the report - everything sold of it over the period. */
    data class Line(
        val serial: Int,
        val name: String,
        /** How much of it went, summed across every bill in the period. */
        val quantity: Double,
        /** The value it was taxed on - see the class notes. */
        val amount: Double,
        val sgst: Double,
        val cgst: Double,
        val igst: Double,
        val vat: Double
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
        val lines: List<Line>,
        /**
         * The period's Service Charge and other extra charges (Parcel Charge
         * among them), read off `td_bills` rather than folded into the item
         * lines above: a charge is not sold as an item to attribute a share of
         * it to one, and [Line] - grouped by product across every bill it
         * appeared on - has no single bill for one to belong to. Bolted on as
         * one flat pair of totals instead, for the period's own bills, the same
         * way [TaxReportDao] does.
         */
        val charges: TaxReportDao.BillCharges = TaxReportDao.BillCharges(0.0, 0.0)
    ) {
        val itemCount: Int get() = lines.size
        val totalQuantity: Double get() = total { it.quantity }
        val totalAmount: Double get() = total { it.amount }
        val totalSgst: Double get() = total { it.sgst }
        val totalCgst: Double get() = total { it.cgst }
        val totalIgst: Double get() = total { it.igst }
        val totalVat: Double get() = total { it.vat }

        /** Whether the period holds any VAT at all - most tills never do. */
        val hasVat: Boolean get() = lines.any { it.vat > 0.0 }

        /** Whether anything sold inter-state - most tills never do. */
        val hasIgst: Boolean get() = lines.any { it.igst > 0.0 }

        val totalServiceCharge: Double get() = charges.service
        val totalOtherCharges: Double get() = charges.other
        val totalParcelCharge: Double get() = charges.parcel

        val isEmpty: Boolean get() = lines.isEmpty()

        private fun total(pick: (Line) -> Double): Double =
            BillRounding.toPaise(lines.sumOf { pick(it) })
    }

    /** What one item has accumulated so far, before it becomes a [Line]. */
    private class Sum(val name: String) {
        var quantity = 0.0
        var amount = 0.0
        var sgst = 0.0
        var cgst = 0.0
        var igst = 0.0
        var vat = 0.0
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
    fun between(
        fromDate: String,
        toDate: String,
        grain: CalendarGrain = CalendarGrain.DAY
    ): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // When the sale happened, to whatever precision the range was asked at: a
        // date range cuts this to its first ten characters and compares days, a
        // date-and-time range keeps the minute. One expression either way, because
        // `yyyy-MM-dd HH:mm` sorts in clock order as text.
        //
        // The bill's own date is the fallback for a row saved without a timestamp;
        // taken as midnight, which is where a bill with no time on it belongs.
        val moment = """
            substr(COALESCE(NULLIF(TRIM(b.bill_date_time), ''), b.bill_date || ' 00:00'),
                   1, ${grain.storedLength})
        """.trimIndent()

        val sql = """
            SELECT COALESCE(NULLIF(TRIM(p.product_name), ''), 'Item #' || i.product_id, 'Unnamed item'),
                   COALESCE(i.product_id, -i.id),
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
            WHERE $moment BETWEEN ? AND ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate).apply {
            if (store != null) add(store.toString())
        }

        // Insertion-ordered, so items that tie on value keep a stable order rather
        // than shuffling between one generation of the report and the next.
        val sums = LinkedHashMap<Long, Sum>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val key = c.getLong(1)
                val sum = sums.getOrPut(key) {
                    Sum(c.getString(0).orEmpty().ifBlank { "Unnamed item" })
                }
                val cgstRate = c.getDouble(4)
                val sgstRate = c.getDouble(5)
                val igstRate = c.getDouble(6)
                val vatRate = c.getDouble(7)
                val cgstAmount = c.getDouble(9)
                val sgstAmount = c.getDouble(10)
                val igstAmount = c.getDouble(11)
                val vatAmount = c.getDouble(12)
                val itemTotal = c.getDouble(13)
                val snapshot = BillSettingsSnapshot.parse(c.getString(14))

                sum.quantity += c.getDouble(3)
                sum.igst += igstAmount

                if (snapshot != null && igstRate <= 0.0 && igstAmount <= 0.0) {
                    val priced = BillPricing.price(
                        rate = c.getDouble(2),
                        quantity = c.getDouble(3),
                        cgstRate = cgstRate,
                        sgstRate = sgstRate,
                        vatRate = vatRate,
                        discountAmount = c.getDouble(8),
                        taxEnabled = snapshot.taxEnabled,
                        inclusive = snapshot.inclusive,
                        discountPreTax = snapshot.discountPreTax
                    )
                    sum.amount += priced.taxable
                    sum.cgst += priced.cgst
                    sum.sgst += priced.sgst
                    sum.vat += priced.vat
                } else {
                    val rate = cgstRate + sgstRate + igstRate + vatRate
                    val tax = cgstAmount + sgstAmount + igstAmount + vatAmount
                    sum.amount += if (rate > 0.0) tax * 100.0 / rate else itemTotal
                    sum.cgst += cgstAmount
                    sum.sgst += sgstAmount
                    sum.vat += vatAmount
                }
            }
        }

        val lines = sums.values
            .sortedByDescending { it.amount }
            .mapIndexed { index, sum ->
                Line(
                    serial = index + 1,
                    name = sum.name,
                    quantity = BillRounding.toPaise(sum.quantity),
                    amount = BillRounding.toPaise(sum.amount),
                    sgst = BillRounding.toPaise(sum.sgst),
                    cgst = BillRounding.toPaise(sum.cgst),
                    igst = BillRounding.toPaise(sum.igst),
                    vat = BillRounding.toPaise(sum.vat)
                )
            }
        return Report(
            fromDate, toDate, lines,
            TaxReportDao.billCharges(helper.readableDatabase, fromDate, toDate, store)
        )
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
