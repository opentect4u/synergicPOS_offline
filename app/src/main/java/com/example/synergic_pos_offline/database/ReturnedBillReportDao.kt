package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.BillSettingsSnapshot
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Returned Bill Report: the bill wise report read over returns instead of sales.
 *
 * A bill-wise return starts from a bill and gives lines back off it, so every return
 * it records points at the bill it came off - which is what makes this report
 * possible in the shape the bill wise report has: a row per return, the bill it
 * reversed, what came back, and the tax that came back with it.
 *
 * Only bill-wise returns appear here, because only they have a bill. An item-wise
 * return is taken from the item with no bill behind it (see [ReturnDao]), and listing
 * it under a blank bill number would say the till had lost the bill rather than that
 * there never was one. The screen says as much when a period holds nothing else - see
 * [Report.itemWiseCount].
 *
 * ## Where the tax figures come from
 *
 * A return records what it refunded ([DatabaseHelper.Tables.TD_SALE_RETURNS]) and how
 * much of each line came back ([DatabaseHelper.Tables.TD_RETURN_ITEMS]), but not how
 * that refund split into tax - there is no column for it. So the split is taken from
 * the bill line the return came off, in the proportion that came back: return two of
 * five and two fifths of that line's CGST is reversed with them.
 *
 * That is the same pro-rating [ReturnDao.BillLine.discountFor] applies when the
 * refund is worked out in the first place, so the tax reported as reversed is the tax
 * the customer was actually given back rather than a second, independent calculation
 * that could differ from it.
 */
class ReturnedBillReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One return on the report - a row of the table, and a block of the printout. */
    data class Line(
        /** The return's own number, off the shared bill sequence. */
        val returnNumber: String,
        /** yyyy-MM-dd, as stored. */
        val returnDate: String,
        /** The bill this came off. */
        val billNumber: String,
        /** When that bill was raised - not when the goods came back. */
        val billDate: String,
        /** How many of the bill's lines came back, in whole or in part. */
        val lineCount: Int,
        /** How much of the goods came back, summed across those lines. */
        val quantity: Double,
        /** Value of the returned goods at the rate they were sold at, before tax and discount. */
        val gross: Double,
        /** The share of the original discount that came back with them. */
        val discount: Double,
        val cgst: Double,
        val sgst: Double,
        val igst: Double,
        val vat: Double,
        /** What the customer got back - the return's own recorded total. */
        val refund: Double,
        /**
         * The tax regime the *original bill* was raised under, from the settings
         * snapshot frozen onto it. A return reverses what that bill charged, so it
         * is reported under that bill's regime however the till is configured now.
         */
        val regime: GstCalculator.TaxRegime
    ) {
        /** True where the original bill's tax was VAT - the CGST / SGST columns do not apply. */
        val isVat: Boolean get() = regime == GstCalculator.TaxRegime.VAT

        /** Everything reversed in tax, however the regime splits it. */
        val totalTax: Double get() = BillRounding.toPaise(cgst + sgst + igst + vat)
    }

    /**
     * The whole report: the period asked for and every bill-wise return inside it.
     *
     * Totalled from the listed lines rather than by a second query, so the summary
     * and the rows above it cannot disagree. Normalised to paise, since a sum of
     * doubles lands on 1234.5600000000002 often enough to print.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val lines: List<Line>,
        /**
         * Item-wise returns taken in the same period, which this report cannot show.
         *
         * Counted only so an empty report can say *why* it is empty: a till set to
         * item-wise returns will never fill this report however many goods come back,
         * and "no returns in this period" would be a plainly wrong answer to give it.
         */
        val itemWiseCount: Int
    ) {
        val returnCount: Int get() = lines.size
        val totalQuantity: Double get() = total { it.quantity }
        val totalLines: Int get() = lines.sumOf { it.lineCount }
        val totalGross: Double get() = total { it.gross }
        val totalDiscount: Double get() = total { it.discount }
        val totalCgst: Double get() = total { it.cgst }
        val totalSgst: Double get() = total { it.sgst }
        val totalIgst: Double get() = total { it.igst }
        val totalVat: Double get() = total { it.vat }
        val totalRefund: Double get() = total { it.refund }

        /** Every tax reversed over the period. */
        val totalTax: Double
            get() = BillRounding.toPaise(totalCgst + totalSgst + totalIgst + totalVat)

        /** Whether any return in the period reversed a VAT bill. */
        val hasVat: Boolean get() = lines.any { it.isVat || it.vat > 0.0 }

        /** Whether any return reversed IGST - an inter-state sale, which many tills never make. */
        val hasIgst: Boolean get() = lines.any { it.igst > 0.0 }

        /** Whether anything was discounted on the bills these returns came off. */
        val hasDiscount: Boolean get() = lines.any { it.discount > 0.005 }

        val isEmpty: Boolean get() = lines.isEmpty()

        private fun total(pick: (Line) -> Double): Double =
            BillRounding.toPaise(lines.sumOf { pick(it) })
    }

    /**
     * Every bill-wise return dated between [fromDate] and [toDate] inclusive, both
     * `yyyy-MM-dd`, oldest first.
     *
     * Dated by when the goods came back, not by when they were sold: a return is an
     * event of the day it was taken, and a report of last week's returns should not
     * change because one of them was against a bill from the month before.
     *
     * Rejected returns are left out - nothing was given back on them. The tax split
     * is pro-rated off the bill lines the return came from; see the class notes for
     * why it is not read from the return itself.
     */
    fun between(fromDate: String, toDate: String): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // The two joins fan the return out to a row per returned line, so every
        // figure taken from them is SUMmed back down and the whole thing grouped by
        // the return. r.total_return_amount would be multiplied by that fan-out if
        // it were summed too, so it is taken with MAX - it is one value per group,
        // and MAX of one value is that value.
        val sql = """
            SELECT r.return_bill_number,
                   substr(r.return_date, 1, 10),
                   COALESCE(b.bill_number, '-'),
                   substr(b.bill_date, 1, 10),
                   COUNT(ri.id),
                   COALESCE(SUM(ri.return_quantity), 0),
                   COALESCE(SUM(COALESCE(i.rate, 0) * ri.return_quantity), 0),
                   COALESCE(SUM(COALESCE(i.discount_amount, 0) * ri.return_quantity
                                / NULLIF(i.quantity, 0)), 0),
                   COALESCE(SUM(COALESCE(i.cgst_amount, 0) * ri.return_quantity
                                / NULLIF(i.quantity, 0)), 0),
                   COALESCE(SUM(COALESCE(i.sgst_amount, 0) * ri.return_quantity
                                / NULLIF(i.quantity, 0)), 0),
                   COALESCE(SUM(COALESCE(i.igst_amount, 0) * ri.return_quantity
                                / NULLIF(i.quantity, 0)), 0),
                   COALESCE(SUM(COALESCE(i.vat_amount, 0) * ri.return_quantity
                                / NULLIF(i.quantity, 0)), 0),
                   MAX(COALESCE(r.total_return_amount, 0)),
                   b.settings_snapshot
            FROM ${DatabaseHelper.Tables.TD_SALE_RETURNS} r
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = r.original_bill_id
            LEFT JOIN ${DatabaseHelper.Tables.TD_RETURN_ITEMS} ri ON ri.return_id = r.id
            LEFT JOIN ${DatabaseHelper.Tables.TD_BILL_ITEMS} i ON i.id = ri.bill_item_id
            WHERE substr(r.return_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(r.return_status, 'COMPLETED') <> 'REJECTED'
              $storeClause
            GROUP BY r.id
            ORDER BY substr(r.return_date, 1, 10) ASC, r.id ASC
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate).apply {
            if (store != null) add(store.toString())
        }

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val cgst = BillRounding.toPaise(c.getDouble(8))
                val sgst = BillRounding.toPaise(c.getDouble(9))
                val igst = BillRounding.toPaise(c.getDouble(10))
                val vat = BillRounding.toPaise(c.getDouble(11))
                lines.add(
                    Line(
                        returnNumber = c.getString(0).orEmpty().ifBlank { "-" },
                        returnDate = c.getString(1).orEmpty(),
                        billNumber = c.getString(2).orEmpty().ifBlank { "-" },
                        billDate = c.getString(3).orEmpty(),
                        lineCount = c.getInt(4),
                        quantity = c.getDouble(5),
                        gross = BillRounding.toPaise(c.getDouble(6)),
                        discount = BillRounding.toPaise(c.getDouble(7)),
                        cgst = cgst,
                        sgst = sgst,
                        igst = igst,
                        vat = vat,
                        refund = c.getDouble(12),
                        regime = regimeOf(c.getString(13), cgst + sgst + igst, vat)
                    )
                )
            }
        }
        return Report(fromDate, toDate, lines, itemWiseCount(fromDate, toDate))
    }

    /**
     * Item-wise returns taken in the period - the ones with no bill behind them, and
     * so no place on this report. Counted for the empty state's sake only.
     */
    private fun itemWiseCount(fromDate: String, toDate: String): Int =
        helper.readableDatabase.rawQuery(
            """
            SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_SALE_RETURNS}
            WHERE substr(return_date, 1, 10) BETWEEN ? AND ?
              AND original_bill_id IS NULL
              AND COALESCE(return_status, 'COMPLETED') <> 'REJECTED'
            """.trimIndent(),
            arrayOf(fromDate, toDate)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * Which regime the original bill was raised under.
     *
     * The settings snapshot frozen onto it is the answer wherever there is one - it
     * is the record of how the sale was actually taxed, and it is the same source
     * [ReturnDao.basisForBill] priced the refund from, so the report and the refund
     * cannot disagree about which taxes were in play.
     *
     * A bill saved before snapshots existed has none, and the live settings are no
     * guide to how it was taxed. So it is read off the money that came back instead.
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
