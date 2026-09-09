package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding

/**
 * Groups the restaurant bills of a period by UDF - "<section>-<table>", e.g.
 * "AC-1", "BAR-2" - for the UDF-Wise Report. Each row is one table/section: how
 * many bills it raised and their tax, discount and bill totals.
 *
 * The section comes off the bill itself, which records the NAME it was billed in
 * (the shop's own short section name, not a number nothing but this table would
 * mean anything by). A bill raised before that was kept falls back to the old
 * reading - the table master ([DatabaseHelper.Tables.MD_TABLE_UNIT] by table_code,
 * joined to [DatabaseHelper.Tables.MD_SECTION] for its name) - so historical rows
 * still get a name rather than a bare table number. That fallback can only guess
 * when a table number is used in more than one section, which is why the bill now
 * carries its own name directly.
 *
 * Dine-In only: a UDF is a table, and Take Away and QSR orders are never seated at
 * one - `td_bills.order_type` is blank/NULL for a Dine-In bill (see
 * `RestaurantOrdersFragment`'s own note on the three order types, "Dine In" being
 * the unmarked default rather than a stored label), so a bill is excluded here
 * whenever that column actually names one of the other two.
 */
class UdfWiseReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    companion object {
        /**
         * A bill rung up at the COUNTER rather than served at a table.
         *
         * Written once and used by both UDF reports - this one and
         * [UdfWiseItemReportDao] - because they have to draw the same line. The item
         * report did not have this test at all, so a take-away token ("TA-ABC6", which
         * a counter bill stores in `table_number` exactly as a table stores its number)
         * came through as a UDF group of its own and sat in the table beside the real
         * ones.
         *
         * "Dine In" is the unmarked default: a bill from before order_type was stored
         * has it blank, and blank means a table. So the test names the two that ARE
         * marked rather than looking for the one that is not.
         */
        const val SQL_COUNTER_ORDER = "UPPER(COALESCE(b.order_type, '')) IN ('TAKE AWAY', 'QSR')"

        /** The other side of [SQL_COUNTER_ORDER] - what the rows of a UDF report are. */
        const val SQL_DINE_IN = "NOT ($SQL_COUNTER_ORDER)"
    }

    /**
     * What the counter took over the same period - the take-away and QSR bills the
     * rows deliberately leave out.
     *
     * Carried so the summary can be the period's whole takings while the table stays
     * what a UDF report is for: a table. Both halves are needed together, because a
     * summary that quietly exceeds the sum of the rows in front of it is the thing an
     * operator reports as a mismatch - so the screen names this figure rather than
     * folding it in silently.
     */
    data class Counter(
        val bills: Int = 0,
        val cgst: Double = 0.0,
        val sgst: Double = 0.0,
        val igst: Double = 0.0,
        val vat: Double = 0.0,
        val discount: Double = 0.0,
        val serviceCharge: Double = 0.0,
        val otherCharges: Double = 0.0,
        val parcelCharge: Double = 0.0,
        val billAmount: Double = 0.0
    ) {
        val any: Boolean get() = bills > 0

        /** Everything this counter charged in tax, however the regime split it -
         *  the same shape [Row.taxAmount] reports for a table's own group. */
        val taxAmount: Double get() = cgst + sgst + igst + vat
    }

    /** One UDF (section-table) group. */
    data class Row(
        /** "<section>-<table>", e.g. "AC-1" - the table number alone where no
         *  section could be resolved for it at all. */
        val udf: String,
        val bills: Int,
        val cgst: Double = 0.0,
        val sgst: Double = 0.0,
        /** Zero on a shop that never sells inter-state. */
        val igst: Double = 0.0,
        /** Zero on a GST-only shop. */
        val vat: Double = 0.0,
        val discount: Double,
        /** The section's own flat charge. */
        val serviceCharge: Double = 0.0,
        /** The shop's other extra charges, Parcel Charge excluded - see
         *  [parcelCharge]. */
        val otherCharges: Double = 0.0,
        /** Parcel Charge's own share, broken out from [otherCharges] - see
         *  ChargeDao.Kind.PARCEL. Zero on a bill sold before this was tracked. */
        val parcelCharge: Double = 0.0,
        val billAmount: Double
    ) {
        /** Everything this group charged in tax, however the regime split it. */
        val taxAmount: Double get() = cgst + sgst + igst + vat
    }

    data class Report(
        val fromDate: String,
        val toDate: String,
        val rows: List<Row>,
        val totalBills: Int,
        val totalCgst: Double = 0.0,
        val totalSgst: Double = 0.0,
        val totalIgst: Double = 0.0,
        val totalVat: Double = 0.0,
        val totalDiscount: Double,
        val totalServiceCharge: Double = 0.0,
        val totalOtherCharges: Double = 0.0,
        val totalParcelCharge: Double = 0.0,
        val totalBillAmount: Double,
        /**
         * The take-away and QSR bills folded into the totals above but kept out of
         * [rows] - see [Counter]. Zero on a shop that only serves tables.
         */
        val counter: Counter = Counter(),
        /** [counter], split to just its QSR half - see [counterTakeaway]. */
        val counterQsr: Counter = Counter(),
        /** [counter], split to just its take-away half - see [counterQsr]. */
        val counterTakeaway: Counter = Counter()
    ) {
        /** Every tax the range charged, however the regimes split it. */
        val totalTax: Double get() = BillRounding.toPaise(totalCgst + totalSgst + totalIgst + totalVat)
        /**
         * The IGST column earns its place only where a bill in the range carried it -
         * a COUNTER bill included, since the summary now totals those too. Reading
         * only the rows would hide a line the total below it was already counting.
         */
        val hasIgst: Boolean get() = rows.any { it.igst > 0.0 } || counter.igst > 0.0
        /** The VAT column earns its place only where a bill in the range carried it. */
        val hasVat: Boolean get() = rows.any { it.vat > 0.0 } || counter.vat > 0.0
        /**
         * Nothing to show at all - no table billed AND the counter took nothing.
         *
         * A day of counter sales only still has a summary worth reading, so it is
         * not reported as an empty period just because no table was seated.
         */
        val isEmpty: Boolean get() = rows.isEmpty() && !counter.any
    }

    /** Every table/section that billed between [from] and [to] (inclusive). */
    fun between(from: String, to: String): Report {
        val rows = mutableListOf<Row>()
        helper.readableDatabase.rawQuery(
            """
            SELECT b.table_number,
                   COALESCE(
                     NULLIF(TRIM(b.table_section), ''),
                     (SELECT s.section_name FROM ${DatabaseHelper.Tables.MD_TABLE_UNIT} tu
                      JOIN ${DatabaseHelper.Tables.MD_SECTION} s ON s.id = tu.section_id
                      WHERE tu.table_code = b.table_number LIMIT 1)
                   ) AS section_name,
                   COUNT(*) AS bills,
                   SUM(COALESCE(b.tot_cgst_amount,0)) AS cgst,
                   SUM(COALESCE(b.tot_sgst_amount,0)) AS sgst,
                   SUM(COALESCE(b.tot_igst_amount,0)) AS igst,
                   SUM(COALESCE(b.tot_vat_amount,0)) AS vat,
                   SUM(COALESCE(b.tot_discount_amount,0)) AS disc,
                   SUM(COALESCE(b.service_charge_amount,0)) AS svc,
                   SUM(COALESCE(b.tot_other_charges_amount,0)) AS other,
                   SUM(COALESCE(b.parcel_charge_amount,0)) AS parcel,
                   SUM(COALESCE(b.net_amount,0)) AS billamt
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND b.table_number IS NOT NULL AND TRIM(b.table_number) <> ''
              AND $SQL_DINE_IN
              AND COALESCE(b.bill_status, '') <> 'CANCELLED'
            GROUP BY b.table_number, section_name
            ORDER BY section_name, CAST(b.table_number AS INTEGER), b.table_number
            """.trimIndent(),
            arrayOf(from, to)
        ).use { c ->
            while (c.moveToNext()) {
                val table = c.getString(0).orEmpty()
                val section = c.getString(1)?.trim()?.takeIf { it.isNotEmpty() }
                rows.add(
                    Row(
                        udf = if (section != null) "$section-$table" else table,
                        bills = c.getInt(2),
                        cgst = BillRounding.toPaise(c.getDouble(3)),
                        sgst = BillRounding.toPaise(c.getDouble(4)),
                        igst = BillRounding.toPaise(c.getDouble(5)),
                        vat = BillRounding.toPaise(c.getDouble(6)),
                        discount = BillRounding.toPaise(c.getDouble(7)),
                        serviceCharge = BillRounding.toPaise(c.getDouble(8)),
                        otherCharges = (BillRounding.toPaise(c.getDouble(9)) - BillRounding.toPaise(c.getDouble(10))).coerceAtLeast(0.0),
                        parcelCharge = BillRounding.toPaise(c.getDouble(10)),
                        billAmount = BillRounding.toPaise(c.getDouble(11))
                    )
                )
            }
        }
        // THE TOTALS ARE THE PERIOD'S, THE ROWS ARE THE TABLES'.
        //
        // A UDF is a table, so a counter order has no row to sit in - but it is still
        // money the shop took that day, and a summary that leaves it out is not the
        // day's takings and does not agree with any other report of the same period.
        // So the counter's own figures are read separately and added to every total.
        val counterQsr = counterTotals(from, to, "QSR")
        val counterTakeaway = counterTotals(from, to, "TAKE AWAY")
        val counter = combine(counterQsr, counterTakeaway)
        return Report(
            fromDate = from,
            toDate = to,
            rows = rows,
            totalBills = rows.sumOf { it.bills } + counter.bills,
            totalCgst = BillRounding.toPaise(rows.sumOf { it.cgst } + counter.cgst),
            totalSgst = BillRounding.toPaise(rows.sumOf { it.sgst } + counter.sgst),
            totalIgst = BillRounding.toPaise(rows.sumOf { it.igst } + counter.igst),
            totalVat = BillRounding.toPaise(rows.sumOf { it.vat } + counter.vat),
            totalDiscount = BillRounding.toPaise(rows.sumOf { it.discount } + counter.discount),
            totalServiceCharge = BillRounding.toPaise(rows.sumOf { it.serviceCharge } + counter.serviceCharge),
            totalOtherCharges = BillRounding.toPaise(rows.sumOf { it.otherCharges } + counter.otherCharges),
            totalParcelCharge = BillRounding.toPaise(rows.sumOf { it.parcelCharge } + counter.parcelCharge),
            totalBillAmount = BillRounding.toPaise(rows.sumOf { it.billAmount } + counter.billAmount),
            counter = counter,
            counterQsr = counterQsr,
            counterTakeaway = counterTakeaway
        )
    }

    /** Every field of two [Counter]s, added together. */
    private fun combine(a: Counter, b: Counter): Counter = Counter(
        bills = a.bills + b.bills,
        cgst = BillRounding.toPaise(a.cgst + b.cgst),
        sgst = BillRounding.toPaise(a.sgst + b.sgst),
        igst = BillRounding.toPaise(a.igst + b.igst),
        vat = BillRounding.toPaise(a.vat + b.vat),
        discount = BillRounding.toPaise(a.discount + b.discount),
        serviceCharge = BillRounding.toPaise(a.serviceCharge + b.serviceCharge),
        otherCharges = BillRounding.toPaise(a.otherCharges + b.otherCharges),
        parcelCharge = BillRounding.toPaise(a.parcelCharge + b.parcelCharge),
        billAmount = BillRounding.toPaise(a.billAmount + b.billAmount)
    )

    /**
     * What the counter's bills of one order type - "QSR" or "TAKE AWAY" - came to
     * over the period.
     *
     * A separate query rather than a second pass over the row query, because those two
     * ask different questions: the rows are grouped by table and a counter order has no
     * table to group by. Read with no grouping at all - one line for the whole counter,
     * which is all the summary shows of it. Split by [orderType] rather than read once
     * under [SQL_COUNTER_ORDER], so QSR and Take Away can each be its own summary line
     * instead of one blended figure neither tax authority nor the operator's own till
     * roll actually reports as a single thing.
     *
     * The same date, cancellation and rounding rules as the rows, so every reading of
     * the same period agrees.
     */
    private fun counterTotals(from: String, to: String, orderType: String): Counter {
        helper.readableDatabase.rawQuery(
            """
            SELECT COUNT(*) AS bills,
                   SUM(COALESCE(b.tot_cgst_amount,0)) AS cgst,
                   SUM(COALESCE(b.tot_sgst_amount,0)) AS sgst,
                   SUM(COALESCE(b.tot_igst_amount,0)) AS igst,
                   SUM(COALESCE(b.tot_vat_amount,0)) AS vat,
                   SUM(COALESCE(b.tot_discount_amount,0)) AS disc,
                   SUM(COALESCE(b.service_charge_amount,0)) AS svc,
                   SUM(COALESCE(b.tot_other_charges_amount,0)) AS other,
                   SUM(COALESCE(b.parcel_charge_amount,0)) AS parcel,
                   SUM(COALESCE(b.net_amount,0)) AS billamt
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND UPPER(COALESCE(b.order_type, '')) = ?
              AND COALESCE(b.bill_status, '') <> 'CANCELLED'
            """.trimIndent(),
            arrayOf(from, to, orderType)
        ).use { c ->
            if (!c.moveToFirst()) return Counter()
            val other = BillRounding.toPaise(c.getDouble(7))
            val parcel = BillRounding.toPaise(c.getDouble(8))
            return Counter(
                bills = c.getInt(0),
                cgst = BillRounding.toPaise(c.getDouble(1)),
                sgst = BillRounding.toPaise(c.getDouble(2)),
                igst = BillRounding.toPaise(c.getDouble(3)),
                vat = BillRounding.toPaise(c.getDouble(4)),
                discount = BillRounding.toPaise(c.getDouble(5)),
                serviceCharge = BillRounding.toPaise(c.getDouble(6)),
                // Parcel is carried inside other_charges on the bill and broken out
                // here, exactly as the rows do it - so the two never double-count.
                otherCharges = (other - parcel).coerceAtLeast(0.0),
                parcelCharge = parcel,
                billAmount = BillRounding.toPaise(c.getDouble(9))
            )
        }
    }
}
