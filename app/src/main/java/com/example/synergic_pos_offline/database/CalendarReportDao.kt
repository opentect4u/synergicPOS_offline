package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.CalendarGrain
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Day Wise, Month Wise and Year Wise reports: takings totalled by the calendar.
 *
 * One DAO for the three of them, because they are one question asked at three
 * widths - how many bills, and what did they come to, per day / per month / per
 * year. A bill is dated `yyyy-MM-dd`, so each of those is a prefix of the same
 * column: [CalendarGrain] says how many characters, and the grouping and the range
 * comparison both fall out of that. Three DAOs would have been the same query
 * written three times over, free to drift in what they counted as a sale.
 *
 * Read off `net_amount`, the figure each bill was totalled to when it was saved -
 * the same column the Bill Wise Report reads - so a month on this report is the sum
 * of the days on it, and of the bills on that one.
 */
class CalendarReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One day, month or year on the report. */
    data class Line(
        /** The period as stored: "2026-08-11", "2026-08" or "2026". */
        val period: String,
        val billCount: Int,
        val cgst: Double,
        val sgst: Double,
        /** Zero on a shop that never sells inter-state. */
        val igst: Double = 0.0,
        /** Zero on a GST-only shop. */
        val vat: Double = 0.0,
        val totalDiscount: Double,
        /** The restaurant section's own flat charge - zero on a grocery bill. */
        val totalServiceCharge: Double = 0.0,
        /** The shop's other extra charges, Parcel Charge excluded - see
         *  [totalParcelCharge]. */
        val totalOtherCharges: Double = 0.0,
        /** Parcel Charge's own share, broken out from [totalOtherCharges] - see
         *  ChargeDao.Kind.PARCEL. Zero on a bill sold before this was tracked. */
        val totalParcelCharge: Double = 0.0,
        /** What those bills came to - their net. */
        val totalAmount: Double
    ) {
        /** Every tax this period's bills charged, however the regimes split it. */
        val totalTax: Double get() = cgst + sgst + igst + vat
    }

    /**
     * The whole report: the range asked for, at the width it was asked at, and every
     * period inside it that saw a bill.
     *
     * A day with no sales has no line. The alternative - filling the gaps with zero
     * rows - would turn a quiet fortnight into fourteen rows of nothing to scroll
     * past, and a year-wise report into a row for every year the shop did not exist.
     *
     * Totalled from the listed lines rather than by a second query, so the summary
     * and the rows above it cannot disagree.
     */
    data class Report(
        val fromPeriod: String,
        val toPeriod: String,
        val grain: CalendarGrain,
        val lines: List<Line>
    ) {
        val periodCount: Int get() = lines.size
        val totalBills: Int get() = lines.sumOf { it.billCount }
        val totalCgst: Double get() = BillRounding.toPaise(lines.sumOf { it.cgst })
        val totalSgst: Double get() = BillRounding.toPaise(lines.sumOf { it.sgst })
        val totalIgst: Double get() = BillRounding.toPaise(lines.sumOf { it.igst })
        val totalVat: Double get() = BillRounding.toPaise(lines.sumOf { it.vat })
        /** Every tax the range charged, however the regimes split it. */
        val totalTax: Double get() = BillRounding.toPaise(totalCgst + totalSgst + totalIgst + totalVat)
        /** The IGST column earns its place only where a bill in the range carried it. */
        val hasIgst: Boolean get() = lines.any { it.igst > 0.0 }
        /** The VAT column earns its place only where a bill in the range carried it. */
        val hasVat: Boolean get() = lines.any { it.vat > 0.0 }
        val totalDiscount: Double get() = BillRounding.toPaise(lines.sumOf { it.totalDiscount })
        val totalServiceCharge: Double get() = BillRounding.toPaise(lines.sumOf { it.totalServiceCharge })
        val totalOtherCharges: Double get() = BillRounding.toPaise(lines.sumOf { it.totalOtherCharges })
        val totalParcelCharge: Double get() = BillRounding.toPaise(lines.sumOf { it.totalParcelCharge })
        val totalAmount: Double get() = BillRounding.toPaise(lines.sumOf { it.totalAmount })

        /** The best period of the range, for the summary to name. Null when empty. */
        val busiest: Line? get() = lines.maxByOrNull { it.totalAmount }

        val isEmpty: Boolean get() = lines.isEmpty()
    }

    /**
     * Every period between [fromPeriod] and [toPeriod] inclusive that saw a bill,
     * oldest first.
     *
     * Both bounds are in [grain]'s stored form - "2026-08-11" for a day, "2026-08"
     * for a month, "2026" for a year - and are compared against the same prefix of
     * the bill's date. Those prefixes sort in calendar order as text, so this needs
     * no date arithmetic and cannot be caught out by month lengths or leap years.
     *
     * Oldest first rather than biggest first, unlike the reports that rank: these
     * read as a run of time, and a calendar out of order is not a calendar.
     *
     * Voided and cancelled bills are left out, exactly as the other reports leave
     * them out - they are not sales.
     */
    fun between(fromPeriod: String, toPeriod: String, grain: CalendarGrain): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // The one expression the grouping, the range and the output all use, so the
        // three cannot disagree about which period a bill falls in.
        val period = "substr(b.bill_date, 1, ${grain.storedLength})"

        val sql = """
            SELECT $period AS period,
                   COUNT(*),
                   COALESCE(SUM(b.tot_cgst_amount), 0),
                   COALESCE(SUM(b.tot_sgst_amount), 0),
                   COALESCE(SUM(b.tot_igst_amount), 0),
                   COALESCE(SUM(b.tot_vat_amount), 0),
                   COALESCE(SUM(b.tot_discount_amount), 0),
                   COALESCE(SUM(b.service_charge_amount), 0),
                   COALESCE(SUM(b.tot_other_charges_amount), 0),
                   COALESCE(SUM(b.parcel_charge_amount), 0),
                   COALESCE(SUM(b.net_amount), 0)
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            WHERE $period BETWEEN ? AND ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
            GROUP BY period
            ORDER BY period ASC
        """.trimIndent()

        val args = mutableListOf(fromPeriod, toPeriod).apply {
            if (store != null) add(store.toString())
        }

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                lines.add(
                    Line(
                        period = c.getString(0).orEmpty(),
                        billCount = c.getInt(1),
                        cgst = BillRounding.toPaise(c.getDouble(2)),
                        sgst = BillRounding.toPaise(c.getDouble(3)),
                        igst = BillRounding.toPaise(c.getDouble(4)),
                        vat = BillRounding.toPaise(c.getDouble(5)),
                        totalDiscount = BillRounding.toPaise(c.getDouble(6)),
                        totalServiceCharge = BillRounding.toPaise(c.getDouble(7)),
                        totalOtherCharges = (BillRounding.toPaise(c.getDouble(8)) - BillRounding.toPaise(c.getDouble(9))).coerceAtLeast(0.0),
                        totalParcelCharge = BillRounding.toPaise(c.getDouble(9)),
                        totalAmount = BillRounding.toPaise(c.getDouble(10))
                    )
                )
            }
        }
        return Report(fromPeriod, toPeriod, grain, lines)
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
