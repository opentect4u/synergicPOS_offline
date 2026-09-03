package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.BillSettingsSnapshot
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Bill Wise Report: every bill raised over a period, with what it was made up
 * of, and the totals of the whole period under them.
 *
 * One report serves Restaurant and Grocery alike, because one table does: a
 * restaurant order settled on the Orders screen is persisted through the very same
 * [BillDao.createBill] a grocery sale is, into td_bills / td_bill_items /
 * td_payments. A mode-aware report would be two readings of one set of books, and
 * they would drift.
 *
 * Reading only, and off columns the bill already totalled at the time it was saved
 * rather than re-adding its items: the bill's own figures are what was printed and
 * what the customer paid, and a report that recomputed them would quietly disagree
 * with the receipt whenever a tax or rounding rule had since been changed.
 */
class BillWiseReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One bill on the report - a row of the table, and a block of the printout. */
    data class Line(
        val billNumber: String,
        /** yyyy-MM-dd, as stored. */
        val date: String,
        /**
         * How it was paid, from the payment row; the bill's own type is the fallback
         * for a bill saved before a payment was recorded against it.
         */
        val payMode: String,
        /** Value of the goods as listed, before tax was added and before discount. */
        val mrp: Double,
        val cgst: Double,
        val sgst: Double,
        val igst: Double,
        val vat: Double,
        val discount: Double,
        val roundOff: Double,
        /** What the customer actually paid - the bill's net. */
        val netAmount: Double,
        /**
         * The tax regime this bill was raised under, read from the settings snapshot
         * frozen onto it at the time - not from what the till is set to now.
         *
         * A shop that has since moved from VAT to GST still has VAT bills in its
         * books, and their tax is a VAT amount however the till is configured today.
         * Reporting them under today's regime would put a figure in the wrong column
         * or, worse, in none at all.
         */
        val regime: GstCalculator.TaxRegime
    ) {
        /** True where this bill's tax is GST - the CGST / SGST / IGST columns apply. */
        val isGst: Boolean get() = regime == GstCalculator.TaxRegime.GST

        /** True where this bill's tax is VAT - the CGST / SGST / IGST columns do not. */
        val isVat: Boolean get() = regime == GstCalculator.TaxRegime.VAT
    }

    /**
     * The whole report: the period asked for and every bill inside it.
     *
     * The totals are summed here rather than in a second query, so the figures on
     * the summary are the figures on the rows above it by construction - a SUM()
     * over its own WHERE clause could differ from the listed rows for any reason
     * the two clauses ever came to differ, and nobody would see it.
     *
     * Every total is normalised to paise, since a sum of doubles lands on
     * 1234.5600000000002 often enough to print.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val lines: List<Line>
    ) {
        val billCount: Int get() = lines.size
        val totalMrp: Double get() = total { it.mrp }
        val totalCgst: Double get() = total { it.cgst }
        val totalSgst: Double get() = total { it.sgst }
        val totalIgst: Double get() = total { it.igst }
        val totalVat: Double get() = total { it.vat }
        val totalDiscount: Double get() = total { it.discount }
        val totalRoundOff: Double get() = total { it.roundOff }
        val totalAmount: Double get() = total { it.netAmount }

        /**
         * Whether any bill in the period was raised under VAT.
         *
         * The VAT column and its total only appear when there is VAT to show, the
         * way the bill's own DISC column only appears when a line was discounted: a
         * GST-only shop should not be reading a column of zeroes, and a shop with
         * VAT bills in the period must not have that tax quietly left off.
         */
        val hasVat: Boolean get() = lines.any { it.isVat || it.vat > 0.0 }

        val isEmpty: Boolean get() = lines.isEmpty()

        private fun total(pick: (Line) -> Double): Double =
            BillRounding.toPaise(lines.sumOf { pick(it) })
    }

    /**
     * Every completed bill dated between [fromDate] and [toDate] inclusive, both
     * `yyyy-MM-dd`, oldest first.
     *
     * Voided and cancelled bills are left out. They are not sales - counting them
     * would overstate the day's takings - and the menu carries a Void Bill Report
     * for the separate question of what was voided.
     *
     * The pay mode comes from a subquery rather than a join to td_payments: a join
     * would repeat the bill once per payment row against it, and each repeat would
     * be summed into the period totals as though it were another sale.
     */
    fun between(fromDate: String, toDate: String): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // substr(...,1,10) rather than a plain comparison: bill_date is written as
        // yyyy-MM-dd, but a row that ever carried a time on it would sort outside
        // the range on its final day and silently drop off the report.
        val sql = """
            SELECT b.bill_number,
                   substr(b.bill_date, 1, 10),
                   COALESCE(
                       (SELECT p.payment_mode FROM ${DatabaseHelper.Tables.TD_PAYMENTS} p
                         WHERE p.bill_id = b.receipt_no AND p.payment_mode IS NOT NULL
                         ORDER BY p.id ASC LIMIT 1),
                       b.bill_type, ''),
                   COALESCE(b.tot_price, 0), COALESCE(b.tot_cgst_amount, 0),
                   COALESCE(b.tot_sgst_amount, 0), COALESCE(b.tot_igst_amount, 0),
                   COALESCE(b.tot_vat_amount, 0),
                   COALESCE(b.tot_discount_amount, 0), COALESCE(b.tot_round_off_amount, 0),
                   COALESCE(b.net_amount, 0), b.settings_snapshot
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
            ORDER BY substr(b.bill_date, 1, 10) ASC, b.receipt_no ASC
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate).apply {
            if (store != null) add(store.toString())
        }

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val cgst = c.getDouble(4)
                val sgst = c.getDouble(5)
                val igst = c.getDouble(6)
                val vat = c.getDouble(7)
                lines.add(
                    Line(
                        billNumber = c.getString(0).orEmpty().ifBlank { "-" },
                        date = c.getString(1).orEmpty(),
                        payMode = c.getString(2).orEmpty().ifBlank { "-" }.uppercase(),
                        mrp = c.getDouble(3),
                        cgst = cgst,
                        sgst = sgst,
                        igst = igst,
                        vat = vat,
                        discount = c.getDouble(8),
                        roundOff = c.getDouble(9),
                        netAmount = c.getDouble(10),
                        regime = regimeOf(c.getString(11), cgst + sgst + igst, vat)
                    )
                )
            }
        }
        return Report(fromDate, toDate, lines)
    }

    /**
     * Which regime a bill was raised under.
     *
     * The settings snapshot frozen onto the bill is the answer wherever there is
     * one - it is the record of how the sale was actually taxed, and it survives the
     * till being reconfigured afterwards. This is the same source
     * [com.example.synergic_pos_offline.utils.BillReceiptRenderer] reads when it
     * reprints the bill, so a reprint and the report cannot disagree about it.
     *
     * A bill saved before snapshots existed has none, and the *live* settings are no
     * guide to how it was taxed - they may have changed in between, which is the
     * whole reason snapshots were added. So it is read off the money instead: tax
     * booked as VAT means it was a VAT bill, tax booked as CGST/SGST/IGST means GST,
     * and no tax at all means neither applied.
     */

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
        /**
         * Which regime a bill was raised under - see the note above [between].
         *
         * On the companion rather than the instance because it reads nothing but its
         * arguments, and because a second sales report now needs the same answer:
         * [ShiftWiseReportDao] lists the same bills narrowed to one shift, and two
         * copies of this rule would be two reports that could come to disagree about
         * which column a bill's tax belongs in.
         */
        fun regimeOf(snapshotJson: String?, gstAmount: Double, vatAmount: Double):
            GstCalculator.TaxRegime {
            BillSettingsSnapshot.parse(snapshotJson)?.let { return it.taxRegime }
            return when {
                vatAmount > 0.0 -> GstCalculator.TaxRegime.VAT
                gstAmount > 0.0 -> GstCalculator.TaxRegime.GST
                else -> GstCalculator.TaxRegime.NONE
            }
        }
    }
}
