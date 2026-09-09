package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding

/**
 * The Waiter Wise Report: one waiter's bills over a period, itemised the way the
 * Operator Billed Report itemises an operator's - or, with every waiter read at
 * once, one row per waiter the way the UDF-Wise Report gives one row per table -
 * the same two shapes asked of a different person. Restaurant only - a grocery
 * bill carries no waiter to group by.
 *
 * ## Which bills belong to a waiter
 *
 * `td_bills.waiter_id` directly - set once at order creation from whichever waiter
 * the table was assigned to (see `RestaurantOrdersFragment.openNewOrder`). Unlike
 * [ShiftWiseReportDao] this needs no join-through-another-table: the waiter is a
 * fact recorded on the bill itself, not inferred from who is on it today.
 *
 * ## Where the figures come from
 *
 * Off the bill's own stored totals, exactly as [BillWiseReportDao] reads them, so
 * this report cannot disagree with Bill Wise about a bill they both list.
 */
class WaiterWiseReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val appContext = context.applicationContext

    /** One bill of one waiter's - the Operator Billed Report's own row shape. */
    data class Line(
        val billNumber: String,
        /** How much was on it, summed across its lines. */
        val items: Double,
        val cgst: Double,
        val sgst: Double,
        /** Zero on a shop that never sells inter-state. */
        val igst: Double = 0.0,
        /** Zero on a GST-only shop. */
        val vat: Double = 0.0,
        val discount: Double,
        val serviceCharge: Double = 0.0,
        /** The shop's other extra charges, Parcel Charge excluded - see [parcelCharge]. */
        val otherCharges: Double = 0.0,
        /** Parcel Charge's own share, broken out from [otherCharges] - see
         *  ChargeDao.Kind.PARCEL. Zero on a bill sold before this was tracked. */
        val parcelCharge: Double = 0.0,
        /** What this bill was rounded by - signed, and already inside [total]. */
        val roundOff: Double = 0.0,
        /** What the customer paid - the bill's net. */
        val total: Double
    ) {
        /** Everything this bill charged in tax, however the regime split it. */
        val tax: Double get() = cgst + sgst + igst + vat
    }

    /** One waiter, summed across the period - the UDF-Wise Report's own row shape. */
    data class WaiterRow(
        val waiterName: String,
        val bills: Int,
        val cgst: Double = 0.0,
        val sgst: Double = 0.0,
        val igst: Double = 0.0,
        val vat: Double = 0.0,
        val discount: Double,
        val serviceCharge: Double = 0.0,
        val otherCharges: Double = 0.0,
        val parcelCharge: Double = 0.0,
        /** What this waiter's bills were rounded by, summed - see [Line.roundOff]. */
        val roundOff: Double = 0.0,
        val billAmount: Double
    ) {
        val taxAmount: Double get() = cgst + sgst + igst + vat
    }

    /**
     * The whole report: the period, and either one waiter's bills ([lines], read
     * one bill at a time) or every waiter's totals ([rows], one row each) - never
     * both. Which one is live is [allWaiters].
     *
     * Totalled from whichever list is populated rather than by a second query, so
     * the summary and the rows above it agree by construction.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        /** The one waiter this was run for - null when [allWaiters] is true, or
         *  when nothing has been picked yet (an empty report either way). */
        val waiter: WaiterDao.Waiter?,
        /** Whether this covers every waiter at once, picked via the "All" entry
         *  in the dropdown. */
        val allWaiters: Boolean = false,
        /** One row per bill - populated only when running for a single waiter. */
        val lines: List<Line> = emptyList(),
        /** One row per waiter - populated only under [allWaiters]. */
        val rows: List<WaiterRow> = emptyList()
    ) {
        val billCount: Int get() = if (allWaiters) rows.sumOf { it.bills } else lines.size
        val waiterCount: Int get() = rows.size

        val totalItems: Double get() = totalLines { it.items }
        val totalCgst: Double get() = if (allWaiters) totalRows { it.cgst } else totalLines { it.cgst }
        val totalSgst: Double get() = if (allWaiters) totalRows { it.sgst } else totalLines { it.sgst }
        val totalIgst: Double get() = if (allWaiters) totalRows { it.igst } else totalLines { it.igst }
        val totalVat: Double get() = if (allWaiters) totalRows { it.vat } else totalLines { it.vat }
        val totalDiscount: Double get() = if (allWaiters) totalRows { it.discount } else totalLines { it.discount }
        val totalServiceCharge: Double get() = if (allWaiters) totalRows { it.serviceCharge } else totalLines { it.serviceCharge }
        val totalOtherCharges: Double get() = if (allWaiters) totalRows { it.otherCharges } else totalLines { it.otherCharges }
        val totalParcelCharge: Double get() = if (allWaiters) totalRows { it.parcelCharge } else totalLines { it.parcelCharge }
        /** What the period's bills were rounded by, summed - already inside
         *  [totalAmount], not a charge on top of it. */
        val totalRoundOff: Double get() = if (allWaiters) totalRows { it.roundOff } else totalLines { it.roundOff }
        /** The one figure the report is read for - a bill's net, summed. */
        val totalAmount: Double get() = if (allWaiters) totalRows { it.billAmount } else totalLines { it.total }

        /** The IGST column earns its place only where a bill in the period carried it. */
        val hasIgst: Boolean get() = if (allWaiters) rows.any { it.igst > 0.0 } else lines.any { it.igst > 0.0 }

        /** The VAT column earns its place only where a bill in the period carried it. */
        val hasVat: Boolean get() = if (allWaiters) rows.any { it.vat > 0.0 } else lines.any { it.vat > 0.0 }

        val isEmpty: Boolean get() = if (allWaiters) rows.isEmpty() else lines.isEmpty()

        private fun totalLines(pick: (Line) -> Double): Double = BillRounding.toPaise(lines.sumOf(pick))
        private fun totalRows(pick: (WaiterRow) -> Double): Double = BillRounding.toPaise(rows.sumOf(pick))
    }

    /** Every waiter on the master, for the report's picker. */
    fun waiters(): List<WaiterDao.Waiter> = WaiterDao(appContext).getAll()

    /**
     * [waiter]'s bills between [fromDate] and [toDate] inclusive (both `yyyy-MM-dd`),
     * one row each - or, when [waiter] is null, every waiter's bills for the period
     * summed into one row per waiter, so the floor's whole night can be read as one
     * report rather than one waiter at a time.
     */
    fun between(fromDate: String, toDate: String, waiter: WaiterDao.Waiter?): Report =
        if (waiter != null) forWaiter(fromDate, toDate, waiter) else allWaitersGrouped(fromDate, toDate)

    /**
     * One waiter's bills, oldest first - not ranked: this is one waiter's own run of
     * work, and it is read down the way the shift happened. The same resolution and
     * exclusions [OperatorBilledReportDao.between] applies, with `waiter_id` standing
     * in for the operator: voided and cancelled bills are left out, as they are on
     * every other sales report.
     */
    private fun forWaiter(fromDate: String, toDate: String, waiter: WaiterDao.Waiter): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // The join fans a bill out to one row per line, so its own totals are taken
        // with MAX - one value per group, and MAX of one value is that value - while
        // only the quantity is genuinely summed across the lines.
        val sql = """
            SELECT b.bill_number,
                   COALESCE(SUM(i.quantity), 0),
                   MAX(COALESCE(b.tot_cgst_amount, 0)),
                   MAX(COALESCE(b.tot_sgst_amount, 0)),
                   MAX(COALESCE(b.tot_igst_amount, 0)),
                   MAX(COALESCE(b.tot_vat_amount, 0)),
                   MAX(COALESCE(b.tot_discount_amount, 0)),
                   MAX(COALESCE(b.service_charge_amount, 0)),
                   MAX(COALESCE(b.tot_other_charges_amount, 0)),
                   MAX(COALESCE(b.parcel_charge_amount, 0)),
                   MAX(COALESCE(b.tot_round_off_amount, 0)),
                   MAX(COALESCE(b.net_amount, 0))
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            LEFT JOIN ${DatabaseHelper.Tables.TD_BILL_ITEMS} i ON i.bill_id = b.receipt_no
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND b.waiter_id = ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
            GROUP BY b.receipt_no
            ORDER BY b.receipt_no ASC
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate, waiter.id.toString()).apply {
            if (store != null) add(store.toString())
        }

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                lines.add(
                    Line(
                        billNumber = c.getString(0).orEmpty().ifBlank { "-" },
                        items = BillRounding.toPaise(c.getDouble(1)),
                        cgst = BillRounding.toPaise(c.getDouble(2)),
                        sgst = BillRounding.toPaise(c.getDouble(3)),
                        igst = BillRounding.toPaise(c.getDouble(4)),
                        vat = BillRounding.toPaise(c.getDouble(5)),
                        discount = BillRounding.toPaise(c.getDouble(6)),
                        serviceCharge = BillRounding.toPaise(c.getDouble(7)),
                        otherCharges = (BillRounding.toPaise(c.getDouble(8)) - BillRounding.toPaise(c.getDouble(9))).coerceAtLeast(0.0),
                        parcelCharge = BillRounding.toPaise(c.getDouble(9)),
                        roundOff = BillRounding.toPaise(c.getDouble(10)),
                        total = c.getDouble(11)
                    )
                )
            }
        }
        return Report(fromDate, toDate, waiter, allWaiters = false, lines = lines)
    }

    /**
     * Every waiter who served a bill in the period, one row each - the same
     * grouping [UdfWiseReportDao.between] does by table/section, done here by
     * waiter instead. A bill with no waiter on it is left out: there is nobody
     * for it to be counted against.
     */
    private fun allWaitersGrouped(fromDate: String, toDate: String): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        val sql = """
            SELECT COALESCE(w.waiter_name, '-') AS waiter_name,
                   COUNT(*) AS bills,
                   SUM(COALESCE(b.tot_cgst_amount, 0)) AS cgst,
                   SUM(COALESCE(b.tot_sgst_amount, 0)) AS sgst,
                   SUM(COALESCE(b.tot_igst_amount, 0)) AS igst,
                   SUM(COALESCE(b.tot_vat_amount, 0)) AS vat,
                   SUM(COALESCE(b.tot_discount_amount, 0)) AS disc,
                   SUM(COALESCE(b.service_charge_amount, 0)) AS svc,
                   SUM(COALESCE(b.tot_other_charges_amount, 0)) AS other,
                   SUM(COALESCE(b.parcel_charge_amount, 0)) AS parcel,
                   SUM(COALESCE(b.tot_round_off_amount, 0)) AS roundoff,
                   SUM(COALESCE(b.net_amount, 0)) AS billamt
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            LEFT JOIN ${DatabaseHelper.Tables.MD_WAITERS} w ON w.id = b.waiter_id
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND b.waiter_id IS NOT NULL
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
            GROUP BY b.waiter_id
            ORDER BY waiter_name COLLATE NOCASE
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate).apply { if (store != null) add(store.toString()) }

        val rows = mutableListOf<WaiterRow>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    WaiterRow(
                        waiterName = c.getString(0).orEmpty().ifBlank { "-" },
                        bills = c.getInt(1),
                        cgst = BillRounding.toPaise(c.getDouble(2)),
                        sgst = BillRounding.toPaise(c.getDouble(3)),
                        igst = BillRounding.toPaise(c.getDouble(4)),
                        vat = BillRounding.toPaise(c.getDouble(5)),
                        discount = BillRounding.toPaise(c.getDouble(6)),
                        serviceCharge = BillRounding.toPaise(c.getDouble(7)),
                        otherCharges = (BillRounding.toPaise(c.getDouble(8)) - BillRounding.toPaise(c.getDouble(9))).coerceAtLeast(0.0),
                        parcelCharge = BillRounding.toPaise(c.getDouble(9)),
                        roundOff = BillRounding.toPaise(c.getDouble(10)),
                        billAmount = BillRounding.toPaise(c.getDouble(11))
                    )
                )
            }
        }
        return Report(fromDate, toDate, waiter = null, allWaiters = true, rows = rows)
    }

    private fun currentStoreId(): Long? {
        com.example.synergic_pos_offline.utils.SessionManager.currentUser?.storeId
            ?.takeIf { it != 0 }?.let { return it.toLong() }
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }
}
