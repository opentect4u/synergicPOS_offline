package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.BillSettingsSnapshot
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Tax Report: what tax was collected over a period, and on which bills.
 *
 * The question a return filing asks - how much CGST, how much SGST, how much IGST -
 * answered off the bills that charged it, with each bill listed so the total can be
 * traced back to the sales it came from rather than taken on trust.
 *
 * Read off the columns the bill already totalled at the time it was saved, the same
 * columns [BillWiseReportDao] reads. Two reports over one set of books have to agree
 * about what a bill's CGST was, and the only way they can is by reading the same
 * figure: re-adding the bill's items would quietly disagree with both the receipt
 * and the bill wise report whenever a tax or rounding rule had since been changed.
 */
class TaxReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One bill on the report - what tax it charged, and what it came to. */
    data class Line(
        val billNumber: String,
        /** yyyy-MM-dd, as stored. */
        val date: String,
        val cgst: Double,
        val sgst: Double,
        val igst: Double,
        val vat: Double,
        /** What the customer actually paid - the bill's net, tax included. */
        val netAmount: Double,
        /**
         * The tax regime this bill was raised under, read from the settings snapshot
         * frozen onto it at the time - not from what the till is set to now.
         *
         * A shop that has since moved from VAT to GST still has VAT bills in its
         * books, and their tax is a VAT amount however the till is configured today.
         */
        val regime: GstCalculator.TaxRegime
    ) {
        /** True where this bill's tax is VAT - the CGST / SGST / IGST columns do not apply. */
        val isVat: Boolean get() = regime == GstCalculator.TaxRegime.VAT

        /** Everything this bill charged in tax, however the regime splits it. */
        val totalTax: Double get() = BillRounding.toPaise(cgst + sgst + igst + vat)
    }

    /**
     * The whole report: the period asked for and every bill inside it.
     *
     * The totals are summed here rather than in a second query, so the figures on
     * the summary are the figures on the rows above it by construction.
     *
     * Every total is normalised to paise, since a sum of doubles lands on
     * 1234.5600000000002 often enough to print - and a tax figure that prints a
     * hundredth out is a tax figure that has to be explained.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val lines: List<Line>
    ) {
        val billCount: Int get() = lines.size
        val totalCgst: Double get() = total { it.cgst }
        val totalSgst: Double get() = total { it.sgst }
        val totalIgst: Double get() = total { it.igst }
        val totalVat: Double get() = total { it.vat }
        val totalAmount: Double get() = total { it.netAmount }

        /** Every tax charged over the period - what the report is read for. */
        val totalTax: Double
            get() = BillRounding.toPaise(totalCgst + totalSgst + totalIgst + totalVat)

        /**
         * Whether any bill in the period was raised under VAT.
         *
         * The VAT column and its total only appear when there is VAT to show: a
         * GST-only shop should not be reading a column of zeroes, and a shop with
         * VAT bills in the period must not have that tax quietly left off.
         */
        val hasVat: Boolean get() = lines.any { it.isVat || it.vat > 0.0 }

        /** Whether any bill charged IGST - an inter-state sale, which many tills never make. */
        val hasIgst: Boolean get() = lines.any { it.igst > 0.0 }

        val isEmpty: Boolean get() = lines.isEmpty()

        private fun total(pick: (Line) -> Double): Double =
            BillRounding.toPaise(lines.sumOf { pick(it) })
    }

    /**
     * Every completed bill dated between [fromDate] and [toDate] inclusive, both
     * `yyyy-MM-dd`, oldest first.
     *
     * Voided and cancelled bills are left out: they are not sales, and no tax is
     * owed on them.
     *
     * A bill that charged no tax is still listed. It is tempting to drop it - a row
     * of zeroes says little - but then the report's bill count would not be the
     * period's bill count, and anyone reconciling this against the Bill Wise Report
     * would be chasing a difference that was only ever a filter.
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
                   COALESCE(b.tot_cgst_amount, 0), COALESCE(b.tot_sgst_amount, 0),
                   COALESCE(b.tot_igst_amount, 0), COALESCE(b.tot_vat_amount, 0),
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
                val cgst = c.getDouble(2)
                val sgst = c.getDouble(3)
                val igst = c.getDouble(4)
                val vat = c.getDouble(5)
                lines.add(
                    Line(
                        billNumber = c.getString(0).orEmpty().ifBlank { "-" },
                        date = c.getString(1).orEmpty(),
                        cgst = cgst,
                        sgst = sgst,
                        igst = igst,
                        vat = vat,
                        netAmount = c.getDouble(6),
                        regime = regimeOf(c.getString(7), cgst + sgst + igst, vat)
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
     * till being reconfigured afterwards.
     *
     * A bill saved before snapshots existed has none, and the *live* settings are no
     * guide to how it was taxed. So it is read off the money instead: tax booked as
     * VAT means it was a VAT bill, tax booked as CGST/SGST/IGST means GST, and no tax
     * at all means neither applied.
     */
    private fun regimeOf(snapshotJson: String?, gstAmount: Double, vatAmount: Double):
        GstCalculator.TaxRegime {
        BillSettingsSnapshot.parse(snapshotJson)?.let { return it.taxRegime }
        return when {
            vatAmount > 0.0 -> GstCalculator.TaxRegime.VAT
            gstAmount > 0.0 -> GstCalculator.TaxRegime.GST
            else -> GstCalculator.TaxRegime.NONE
        }
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
