package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
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
 * with the two columns next to it. So the amount is the line's own total less the tax
 * booked on it, which lands on the taxable value whichever way the price was quoted:
 * an inclusive line's total already carries its tax, an exclusive line's has it added
 * on, and taking the tax off reaches the same figure either way.
 *
 * ## Why it READS the tax rather than working it out again
 *
 * It used to re-price every line through [BillPricing], from the rates and the rules
 * frozen onto its bill. [BillWiseReportDao] does no such thing - it sums the totals
 * each bill was saved with - so the two reports were arriving at the period's tax by
 * two different routes, and nothing held those routes to each other. A paisa of
 * rounding, a line whose bill had no settings snapshot, an IGST line that
 * [BillPricing] does not model: any of it put the two reports a figure apart over the
 * same books.
 *
 * Every column here is now the money that was written at the sale. The per-item tax
 * columns are the very figures that were summed into td_bills' own totals - see
 * BillDao, which stores the priced line on td_bill_items and that same run's total on
 * td_bills - so this report's CGST, SGST, IGST and VAT equal bill-wise's by
 * construction rather than by coincidence.
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
        val charges: TaxReportDao.BillCharges = TaxReportDao.BillCharges(0.0, 0.0),
        /**
         * The period's whole-bill discount, off `td_bills.tot_discount_amount` -
         * NON-MRP bills only.
         *
         * [Line.amount] already nets a discount out - it is the item's line total
         * less its own tax, and a discounted line's total is the discounted one - so
         * every figure above still adds up without this. What it does not do is let
         * the report SAY what was discounted, which is why the same-period Bill Wise
         * Report's own Bill Amount could look smaller than this report's total for no
         * reason the reader could see: the discount was already inside AMOUNT,
         * invisible.
         *
         * NON-MRP billing only. Under MRP the discount comes off a price that
         * already has its tax folded in, worked out per line rather than stated as
         * one bill-level figure the way a non-MRP bill's `tot_discount_amount` is -
         * so a single period total here would answer a different question than the
         * one this line is meant to.
         */
        val totalDiscount: Double = 0.0
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
        val totalRoundOff: Double get() = charges.roundOff

        /**
         * What the period came to - the figure BillWiseReportDao calls Total Amount.
         *
         * The same sum a bill is settled by, from the same parts: what the goods were
         * taxed on, plus that tax, plus the shop's own charges, plus the rounding. So
         * the two reports can be read against each other on their headline figure and
         * not only on their columns.
         *
         * [totalAmount] beside it is the TAXABLE value - what was sold, before tax -
         * which is what the AMOUNT column adds up to and what bill-wise's own Bill
         * Amount now matches. The two are different questions and the report answers
         * both rather than making the reader add the columns up.
         */
        val totalNetAmount: Double get() = BillRounding.toPaise(
            totalAmount + totalSgst + totalCgst + totalIgst + totalVat +
                totalServiceCharge + totalOtherCharges + totalParcelCharge + totalRoundOff -
                totalDiscount
        )

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

        // EVERY FIGURE READ, NOT RECOMPUTED.
        //
        // This used to re-price each line from its rates through BillPricing, using
        // the bill's settings snapshot. Bill-wise does not: it sums the totals the
        // bill was SAVED with. Two reports over one period, one reading stored money
        // and the other working it out again, will disagree the moment the two
        // calculations differ by a paisa - and they had no reason to agree, since
        // nothing held them to each other.
        //
        // So both now sum what was written at the sale. The per-item tax columns are
        // the same figures that were added up into td_bills' own totals (see BillDao,
        // which stores priced.cgst on the line and the same run's total on the bill),
        // so the tax on this report equals the tax on bill-wise by construction rather
        // than by coincidence - CGST, SGST, IGST and VAT alike.
        val sql = """
            SELECT COALESCE(NULLIF(TRIM(p.product_name), ''), NULLIF(TRIM(i.product_name), ''), 'Item #' || i.product_id, 'Unnamed item'),
                   COALESCE(i.product_id, -i.id),
                   COALESCE(i.quantity, 0),
                   COALESCE(i.cgst_amount, 0), COALESCE(i.sgst_amount, 0),
                   COALESCE(i.igst_amount, 0), COALESCE(i.vat_amount, 0),
                   COALESCE(i.item_total, 0)
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
                val cgstAmount = c.getDouble(3)
                val sgstAmount = c.getDouble(4)
                val igstAmount = c.getDouble(5)
                val vatAmount = c.getDouble(6)
                val itemTotal = c.getDouble(7)

                sum.quantity += c.getDouble(2)
                sum.cgst += cgstAmount
                sum.sgst += sgstAmount
                sum.igst += igstAmount
                sum.vat += vatAmount

                // What the item sold for, before tax: its line total less the tax on
                // it. True whichever way the price was quoted - an inclusive line's
                // total already carries its tax and an exclusive line's has it added
                // on, so taking the tax off lands on the same figure either way.
                //
                // An UNTAXED line has no tax to take off and contributes its whole
                // total, which is the point: it is worth exactly what it sold for, and
                // the report is short by that much if it is left out.
                //
                // It also gives the report an arithmetic it can be checked by:
                // AMOUNT + SGST + CGST + IGST + VAT adds up to what the items came to.
                sum.amount += itemTotal - (cgstAmount + sgstAmount + igstAmount + vatAmount)
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
        // THE SAME GRAIN THE LINES WERE READ AT, passed on to both. These two totals
        // are read by their own queries rather than off the lines above - a charge and
        // a whole-bill discount belong to a bill, not to any one item on it - and each
        // of them used to compare a bill's DATE against whatever bounds it was handed.
        // With a date range that agreed with the lines; with the Time Wise report's
        // `2026-09-09 00:00` it matched NO bill, so the charges and the discount came
        // back zero and the two reports stated different totals for one period.
        return Report(
            fromDate, toDate, lines,
            TaxReportDao.billCharges(helper.readableDatabase, fromDate, toDate, store, grain = grain),
            exclusiveDiscount(fromDate, toDate, store, grain)
        )
    }

    /**
     * The period's own whole-bill discount, off `td_bills.tot_discount_amount` -
     * NON-MRP bills only. See [Report.totalDiscount] for why: under MRP the
     * discount is already worked into every line's own [Line.amount], so a
     * bill-level figure here would be counted a second time.
     */
    private fun exclusiveDiscount(
        fromDate: String,
        toDate: String,
        store: Long?,
        // Cut to the SAME length the range was asked at - see the note on
        // TaxReportDao.billCharges. A 10-character cut against a minute's bounds
        // matches nothing rather than matching loosely.
        grain: CalendarGrain
    ): Double {
        val storeClause = if (store != null) "AND store_id = ?" else ""
        val args = mutableListOf(fromDate, toDate).apply { if (store != null) add(store.toString()) }
        helper.readableDatabase.rawQuery(
            """
            SELECT COALESCE(SUM(tot_discount_amount), 0)
            FROM ${DatabaseHelper.Tables.TD_BILLS}
            WHERE substr(COALESCE(NULLIF(TRIM(bill_date_time), ''), bill_date || ' 00:00'),
                         1, ${grain.storedLength}) BETWEEN ? AND ?
              AND COALESCE(is_voided, 0) = 0
              AND COALESCE(bill_status, 'COMPLETED') <> 'CANCELLED'
              AND COALESCE(is_mrp_billing, 0) = 0
              $storeClause
            """.trimIndent(),
            args.toTypedArray()
        ).use { c -> if (c.moveToFirst()) return BillRounding.toPaise(c.getDouble(0)) }
        return 0.0
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
