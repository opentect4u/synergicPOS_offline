package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.GstCalculator

/**
 * The Waiter Wise Report: every bill one waiter served over a period, or every
 * waiter's at once. Restaurant only - a grocery bill carries no waiter to group by.
 *
 * ## Which bills belong to a waiter
 *
 * `td_bills.waiter_id` directly - set at settlement from whichever waiter the table
 * was assigned to when the order was opened (see `RestaurantOrdersFragment`). Unlike
 * [ShiftWiseReportDao] this needs no join-through-another-table: the waiter is a fact
 * recorded on the bill itself, not inferred from who is on it today.
 *
 * ## Where the figures come from
 *
 * Off the bill's own stored totals, exactly as [BillWiseReportDao] reads them, so this
 * report cannot disagree with Bill Wise about a bill they both list.
 */
class WaiterWiseReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val appContext = context.applicationContext

    /** One bill on the report - the same shape a Bill Wise row has. */
    data class Line(
        val billNumber: String,
        /** yyyy-MM-dd, as stored. */
        val date: String,
        /** The waiter this bill was served by - "-" where none is on file. Only
         *  printed when the report is run for every waiter at once; a single-waiter
         *  run already says which one in the picker above. */
        val waiterName: String = "-",
        /** Who rang it up - the cashier, not the waiter, since the two are usually
         *  different people and both are worth reading back. */
        val operator: String,
        val payMode: String,
        val mrp: Double,
        val cgst: Double,
        val sgst: Double,
        val igst: Double,
        val vat: Double,
        val discount: Double,
        val roundOff: Double,
        val serviceCharge: Double = 0.0,
        /** The shop's other extra charges, Parcel Charge excluded - see [parcelCharge]. */
        val otherCharges: Double = 0.0,
        /** Parcel Charge's own share, broken out from [otherCharges] - see
         *  ChargeDao.Kind.PARCEL. Zero on a bill sold before this was tracked. */
        val parcelCharge: Double = 0.0,
        val netAmount: Double,
        val regime: GstCalculator.TaxRegime
    ) {
        val isVat: Boolean get() = regime == GstCalculator.TaxRegime.VAT
    }

    /**
     * The whole report: the period, the waiter it was run for, and their bills.
     *
     * Totalled from the listed lines rather than by a second query, so the summary
     * and the rows above it agree by construction.
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
        val lines: List<Line>
    ) {
        val billCount: Int get() = lines.size

        /** How many different waiters served a bill in the period - only worth
         *  reading when [allWaiters]. */
        val waiterCount: Int get() = lines.map { it.waiterName }.distinct().size
        val totalMrp: Double get() = total { it.mrp }
        val totalCgst: Double get() = total { it.cgst }
        val totalSgst: Double get() = total { it.sgst }
        val totalIgst: Double get() = total { it.igst }
        val totalVat: Double get() = total { it.vat }
        val totalDiscount: Double get() = total { it.discount }
        val totalServiceCharge: Double get() = total { it.serviceCharge }
        val totalOtherCharges: Double get() = total { it.otherCharges }
        val totalParcelCharge: Double get() = total { it.parcelCharge }
        val totalAmount: Double get() = total { it.netAmount }

        /** How many different cashiers rang up this waiter's tables over the period. */
        val operatorCount: Int get() = lines.map { it.operator }.distinct().size

        /** The VAT column earns its place only where a bill in the period carried it. */
        val hasVat: Boolean get() = lines.any { it.isVat || it.vat > 0.0 }

        /** The IGST column earns its place only where a bill in the period carried it. */
        val hasIgst: Boolean get() = lines.any { it.igst > 0.0 }

        val isEmpty: Boolean get() = lines.isEmpty()

        private fun total(pick: (Line) -> Double): Double =
            BillRounding.toPaise(lines.sumOf { pick(it) })
    }

    /** Every waiter on the master, for the report's picker. */
    fun waiters(): List<WaiterDao.Waiter> = WaiterDao(appContext).getAll()

    /**
     * Every completed bill [waiter] served between [fromDate] and [toDate] inclusive,
     * both `yyyy-MM-dd`, oldest first - or, when [waiter] is null, every such bill
     * from every waiter, so the report can be run for the whole floor at once.
     *
     * Voided and cancelled bills are left out, as they are on every other sales
     * report - they are not takings, and counting them would overstate the waiter.
     * A bill with no waiter on it is left out too, in both modes: there is no waiter
     * for a single-waiter run to match, and nothing for an all-waiters run to name.
     */
    fun between(fromDate: String, toDate: String, waiter: WaiterDao.Waiter?): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""
        val waiterClause = if (waiter != null) "AND b.waiter_id = ?" else ""

        // Left joined to the user, not filtered through it - the operator here is a
        // display column, not what selects the bills (that's b.waiter_id directly,
        // or its mere presence when every waiter is being read at once).
        //
        // Left joined to the waiter too, for the same reason: naming who served a
        // bill is this query's business only when there is more than one waiter in
        // the result to tell apart, never what selects which bills come back.
        val sql = """
            SELECT b.bill_number,
                   substr(b.bill_date, 1, 10),
                   COALESCE(w.waiter_name, '-'),
                   COALESCE(u.user_name, u.user_id, ''),
                   COALESCE(
                       (SELECT p.payment_mode FROM ${DatabaseHelper.Tables.TD_PAYMENTS} p
                         WHERE p.bill_id = b.receipt_no AND p.payment_mode IS NOT NULL
                         ORDER BY p.id ASC LIMIT 1),
                       b.bill_type, ''),
                   COALESCE(b.tot_price, 0), COALESCE(b.tot_cgst_amount, 0),
                   COALESCE(b.tot_sgst_amount, 0), COALESCE(b.tot_igst_amount, 0),
                   COALESCE(b.tot_vat_amount, 0),
                   COALESCE(b.tot_discount_amount, 0), COALESCE(b.tot_round_off_amount, 0),
                   COALESCE(b.service_charge_amount, 0), COALESCE(b.tot_other_charges_amount, 0),
                   COALESCE(b.parcel_charge_amount, 0),
                   COALESCE(b.net_amount, 0)
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            LEFT JOIN ${DatabaseHelper.Tables.MD_USERS} u ON u.id = b.operator_id
            LEFT JOIN ${DatabaseHelper.Tables.MD_WAITERS} w ON w.id = b.waiter_id
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND b.waiter_id IS NOT NULL
              $waiterClause
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
            ORDER BY substr(b.bill_date, 1, 10) ASC, b.receipt_no ASC
        """.trimIndent()

        // Sent as text like every bound id here - safe, since b.waiter_id is a
        // declared INTEGER column and SQLite applies numeric affinity to the bound
        // text before comparing.
        val args = mutableListOf(fromDate, toDate).apply {
            if (waiter != null) add(waiter.id.toString())
            if (store != null) add(store.toString())
        }

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val cgst = c.getDouble(6)
                val sgst = c.getDouble(7)
                val igst = c.getDouble(8)
                val vat = c.getDouble(9)
                lines.add(
                    Line(
                        billNumber = c.getString(0).orEmpty().ifBlank { "-" },
                        date = c.getString(1).orEmpty(),
                        waiterName = c.getString(2).orEmpty().ifBlank { "-" },
                        operator = c.getString(3).orEmpty().ifBlank { "-" },
                        payMode = c.getString(4).orEmpty().ifBlank { "-" }.uppercase(),
                        mrp = c.getDouble(5),
                        cgst = cgst,
                        sgst = sgst,
                        igst = igst,
                        vat = vat,
                        discount = c.getDouble(10),
                        roundOff = c.getDouble(11),
                        serviceCharge = c.getDouble(12),
                        otherCharges = (c.getDouble(13) - c.getDouble(14)).coerceAtLeast(0.0),
                        parcelCharge = c.getDouble(14),
                        netAmount = c.getDouble(15),
                        // The same rule the Bill Wise Report applies, from the one
                        // place it lives - see [BillWiseReportDao.regimeOf].
                        regime = BillWiseReportDao.regimeOf(cgst + sgst + igst, vat)
                    )
                )
            }
        }
        return Report(fromDate, toDate, waiter, allWaiters = waiter == null, lines)
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
