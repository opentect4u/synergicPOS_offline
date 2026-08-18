package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.GstCalculator

/**
 * The Shift Wise Report: every bill one shift raised over a period.
 *
 * ## Which bills belong to a shift
 *
 * The ones rung up by the operators on it. A shift is attached to a **user** in the
 * user master, and a bill records the operator who raised it, so a bill belongs to
 * whichever shift its operator was on.
 *
 * That is worth stating because there is an obvious alternative: reading the bill's
 * own clock time and matching it against the shift's from/to times. It is not what
 * this does, and deliberately - a bill raised at 14:05 by the morning cashier
 * finishing off a queue is a morning-shift sale in every sense a shopkeeper cares
 * about, and counting it against the afternoon would put it in a total nobody could
 * reconcile against the person who took the money. The shift's times describe the
 * shift; the operator decides the bill.
 *
 * The consequence to know about: a user's shift is where they are *now*, not where
 * they were. Move somebody from mornings to evenings and last month's report moves
 * their bills with them. A shop that reorganises its rota mid-period should read the
 * report before it does, or expect the older figures to follow the change.
 *
 * ## Where the figures come from
 *
 * Off the bill's own stored totals, exactly as [BillWiseReportDao] reads them, so the
 * two reports cannot disagree about a bill they both list. See that class for why the
 * figures are read rather than recomputed, and why the tax regime comes from the
 * snapshot frozen onto the bill.
 */
class ShiftWiseReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val appContext = context.applicationContext

    /** One bill on the report - the same shape a Bill Wise row has. */
    data class Line(
        val billNumber: String,
        /** yyyy-MM-dd, as stored. */
        val date: String,
        /** Who rang it up, so a shift's total can be read back to a person. */
        val operator: String,
        val payMode: String,
        val mrp: Double,
        val cgst: Double,
        val sgst: Double,
        val igst: Double,
        val vat: Double,
        val discount: Double,
        val roundOff: Double,
        val netAmount: Double,
        val regime: GstCalculator.TaxRegime
    ) {
        val isVat: Boolean get() = regime == GstCalculator.TaxRegime.VAT
    }

    /**
     * The whole report: the period, the shift it was run for, and its bills.
     *
     * Totalled from the listed lines rather than by a second query, so the summary
     * and the rows above it agree by construction.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val shift: ShiftDao.Shift?,
        val lines: List<Line>
    ) {
        val billCount: Int get() = lines.size
        val totalMrp: Double get() = total { it.mrp }
        val totalCgst: Double get() = total { it.cgst }
        val totalSgst: Double get() = total { it.sgst }
        val totalVat: Double get() = total { it.vat }
        val totalDiscount: Double get() = total { it.discount }
        val totalAmount: Double get() = total { it.netAmount }

        /** How many people on the shift actually billed over the period. */
        val operatorCount: Int get() = lines.map { it.operator }.distinct().size

        /** The VAT column earns its place only where a bill in the period carried it. */
        val hasVat: Boolean get() = lines.any { it.isVat || it.vat > 0.0 }

        val isEmpty: Boolean get() = lines.isEmpty()

        private fun total(pick: (Line) -> Double): Double =
            BillRounding.toPaise(lines.sumOf { pick(it) })
    }

    /** Every shift on the master, for the report's picker. */
    fun shifts(): List<ShiftDao.Shift> = ShiftDao(appContext).getAll()

    /**
     * Every completed bill raised by [shift]'s operators between [fromDate] and
     * [toDate] inclusive, both `yyyy-MM-dd`, oldest first.
     *
     * Voided and cancelled bills are left out, as they are on every other sales
     * report - they are not takings, and counting them would overstate the shift.
     */
    fun between(fromDate: String, toDate: String, shift: ShiftDao.Shift): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // Joined to the user rather than filtered on a column of the bill: a bill
        // stores who raised it, and the shift is a property of that person. See the
        // class note for why the bill's own clock time is not consulted.
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
                   COALESCE(b.net_amount, 0), b.settings_snapshot
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            JOIN ${DatabaseHelper.Tables.MD_USERS} u ON u.id = b.operator_id
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND u.shift_id = ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
            ORDER BY substr(b.bill_date, 1, 10) ASC, b.receipt_no ASC
        """.trimIndent()

        // The shift id goes over as a bind argument, which rawQuery sends as text.
        // Safe here, and worth saying why: `u.shift_id` is a declared INTEGER column,
        // so SQLite applies numeric affinity to the text before comparing. It is only
        // where the left-hand side is an *expression* - which carries no affinity -
        // that a bound number silently compares as a string and matches everything.
        val args = mutableListOf(fromDate, toDate, shift.id.toString()).apply {
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
                        netAmount = c.getDouble(11),
                        // The same rule the Bill Wise Report applies, from the one
                        // place it lives - see [BillWiseReportDao.regimeOf].
                        regime = BillWiseReportDao.regimeOf(
                            c.getString(12), cgst + sgst + igst, vat
                        )
                    )
                )
            }
        }
        return Report(fromDate, toDate, shift, lines)
    }

    private fun currentStoreId(): Int? =
        com.example.synergic_pos_offline.utils.SessionManager.currentUser?.storeId
}
