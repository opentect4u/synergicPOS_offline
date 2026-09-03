package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.GstCalculator

/**
 * The Waiter Wise Report: every bill one waiter served over a period. Restaurant only
 * - a grocery bill carries no waiter to group by.
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
        /** The shop's other extra charges - Parcel Charge among them - added
         *  together, since only the sum is stored per bill, not each by name. */
        val otherCharges: Double = 0.0,
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
        val waiter: WaiterDao.Waiter?,
        val lines: List<Line>
    ) {
        val billCount: Int get() = lines.size
        val totalMrp: Double get() = total { it.mrp }
        val totalCgst: Double get() = total { it.cgst }
        val totalSgst: Double get() = total { it.sgst }
        val totalIgst: Double get() = total { it.igst }
        val totalVat: Double get() = total { it.vat }
        val totalDiscount: Double get() = total { it.discount }
        val totalServiceCharge: Double get() = total { it.serviceCharge }
        val totalOtherCharges: Double get() = total { it.otherCharges }
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
     * both `yyyy-MM-dd`, oldest first.
     *
     * Voided and cancelled bills are left out, as they are on every other sales
     * report - they are not takings, and counting them would overstate the waiter.
     */
    fun between(fromDate: String, toDate: String, waiter: WaiterDao.Waiter): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // Left joined to the user, not filtered through it - the operator here is a
        // display column, not what selects the bills (that's b.waiter_id directly).
        val sql = """
            SELECT b.bill_number,
                   substr(b.bill_date, 1, 10),
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
                   COALESCE(b.net_amount, 0)
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            LEFT JOIN ${DatabaseHelper.Tables.MD_USERS} u ON u.id = b.operator_id
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND b.waiter_id = ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
            ORDER BY substr(b.bill_date, 1, 10) ASC, b.receipt_no ASC
        """.trimIndent()

        // Sent as text like every bound id here - safe, since b.waiter_id is a
        // declared INTEGER column and SQLite applies numeric affinity to the bound
        // text before comparing.
        val args = mutableListOf(fromDate, toDate, waiter.id.toString()).apply {
            if (store != null) add(store.toString())
        }

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val cgst = c.getDouble(5)
                val sgst = c.getDouble(6)
                val igst = c.getDouble(7)
                val vat = c.getDouble(8)
                lines.add(
                    Line(
                        billNumber = c.getString(0).orEmpty().ifBlank { "-" },
                        date = c.getString(1).orEmpty(),
                        operator = c.getString(2).orEmpty().ifBlank { "-" },
                        payMode = c.getString(3).orEmpty().ifBlank { "-" }.uppercase(),
                        mrp = c.getDouble(4),
                        cgst = cgst,
                        sgst = sgst,
                        igst = igst,
                        vat = vat,
                        discount = c.getDouble(9),
                        roundOff = c.getDouble(10),
                        serviceCharge = c.getDouble(11),
                        otherCharges = c.getDouble(12),
                        netAmount = c.getDouble(13),
                        // The same rule the Bill Wise Report applies, from the one
                        // place it lives - see [BillWiseReportDao.regimeOf].
                        regime = BillWiseReportDao.regimeOf(cgst + sgst + igst, vat)
                    )
                )
            }
        }
        return Report(fromDate, toDate, waiter, lines)
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
