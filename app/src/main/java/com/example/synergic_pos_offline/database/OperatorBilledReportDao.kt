package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Operator Billed Report: one operator's bills over a period, itemised.
 *
 * Where the Operator Wise Report gives a line per operator and totals them against
 * each other, this gives a line per *bill* for one operator - what each carried, what
 * it was taxed, what came off it and what it came to. The report a shift is settled
 * with in hand, rather than the one that says which shift to look at.
 *
 * The operator is resolved the same way [OperatorWiseReportDao] resolves it, from
 * `operator_id` where a bill has one and from `created_by` where it does not, so the
 * two reports cannot disagree about whose bill a bill was.
 */
class OperatorBilledReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** Someone who can be reported on - a row of `md_users`. */
    data class Operator(
        /** `md_users.id`, the operator code that heads the slip. */
        val serialNo: Long,
        /** `md_users.user_id`, the login they sign in with. */
        val userId: String,
        val userName: String
    ) {
        /**
         * How the picker lists them: code, login and name on one line.
         *
         * All three, because an operator is looked up by whichever of them the person
         * searching happens to know - the code off a slip, the login off a rota, the
         * name off the floor. The dropdown matches on any word of it.
         */
        val label: String get() = listOf(
            serialNo.toString(),
            userId.ifBlank { "-" },
            userName.ifBlank { "Unknown user" }
        ).joinToString("   ")
    }

    /** One bill of that operator's. */
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
        /** The restaurant section's own flat charge - zero on a grocery bill. */
        val serviceCharge: Double = 0.0,
        /** The shop's other extra charges, Parcel Charge excluded - see [parcelCharge]. */
        val otherCharges: Double = 0.0,
        /** Parcel Charge's own share, broken out from [otherCharges] - see
         *  ChargeDao.Kind.PARCEL. Zero on a bill sold before this was tracked. */
        val parcelCharge: Double = 0.0,
        /** What the customer paid - the bill's net. */
        val total: Double
    ) {
        /** Everything this bill charged in tax, however the regime split it. */
        val tax: Double get() = cgst + sgst + igst + vat
    }

    /**
     * The whole report: the period, the operator it was run for, and their bills.
     *
     * Totalled from the listed lines rather than by a second query, so the summary
     * and the rows above it cannot disagree.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val operator: Operator?,
        val lines: List<Line>
    ) {
        val billCount: Int get() = lines.size
        val totalItems: Double get() = total { it.items }
        val totalCgst: Double get() = total { it.cgst }
        val totalSgst: Double get() = total { it.sgst }
        val totalIgst: Double get() = total { it.igst }
        val totalVat: Double get() = total { it.vat }
        /** Every tax the period's bills charged, however the regimes split it. */
        val totalTax: Double get() = BillRounding.toPaise(totalCgst + totalSgst + totalIgst + totalVat)
        /** The IGST column earns its place only where a bill in the period carried it. */
        val hasIgst: Boolean get() = lines.any { it.igst > 0.0 }
        /** The VAT column earns its place only where a bill in the period carried it. */
        val hasVat: Boolean get() = lines.any { it.vat > 0.0 }
        val totalDiscount: Double get() = total { it.discount }
        val totalServiceCharge: Double get() = total { it.serviceCharge }
        val totalOtherCharges: Double get() = total { it.otherCharges }
        val totalParcelCharge: Double get() = total { it.parcelCharge }
        val grandTotal: Double get() = total { it.total }

        val isEmpty: Boolean get() = lines.isEmpty()

        private fun total(pick: (Line) -> Double): Double =
            BillRounding.toPaise(lines.sumOf { pick(it) })
    }

    /**
     * Everyone on the user master, for the picker to search.
     *
     * The whole list rather than only those who have billed: an operator is chosen
     * before the period is read, so there is no way to know yet which of them will
     * turn out to have sold anything - and "no bills in this period" is a perfectly
     * good answer for one who did not.
     */
    fun operators(): List<Operator> {
        val store = currentStoreId()
        val where = if (store != null) "WHERE store_id = ?" else ""
        val args = if (store != null) arrayOf(store.toString()) else null

        val list = mutableListOf<Operator>()
        helper.readableDatabase.rawQuery(
            """
            SELECT id, COALESCE(user_id, ''), COALESCE(user_name, '')
            FROM ${DatabaseHelper.Tables.MD_USERS} $where
            ORDER BY id ASC
            """.trimIndent(),
            args
        ).use { c ->
            while (c.moveToNext()) {
                list.add(Operator(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty()))
            }
        }
        return list
    }

    /**
     * [operator]'s bills between [fromDate] and [toDate] inclusive, both
     * `yyyy-MM-dd`, in the order they were rung up.
     *
     * Oldest first, not ranked: this is one operator's own run of work, and it is
     * read down the way the shift happened.
     *
     * Voided and cancelled bills are left out, exactly as the other reports leave
     * them out - they are not sales, and crediting them to an operator would have
     * them answering for takings that were never taken.
     */
    fun between(fromDate: String, toDate: String, operator: Operator): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // The same resolution OperatorWiseReportDao groups by: the column where the
        // bill has one, the audit user where it does not.
        //
        // Matched with CAST(? AS INTEGER), and it has to be. rawQuery binds every
        // argument as text, and this is an *expression* rather than a column - only a
        // column carries the affinity that would convert the text back to a number.
        // Left as a plain `= ?` the comparison is 1 = '1', which in SQLite is false,
        // and the report comes back empty however many bills the operator rang up.
        val whose = "COALESCE(b.operator_id, CAST(NULLIF(TRIM(b.created_by), '') AS INTEGER))"

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
                   MAX(COALESCE(b.net_amount, 0))
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            LEFT JOIN ${DatabaseHelper.Tables.TD_BILL_ITEMS} i ON i.bill_id = b.receipt_no
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              AND $whose = CAST(? AS INTEGER)
              $storeClause
            GROUP BY b.receipt_no
            ORDER BY b.receipt_no ASC
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate, operator.serialNo.toString()).apply {
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
                        total = c.getDouble(10)
                    )
                )
            }
        }
        return Report(fromDate, toDate, operator, lines)
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
