package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillPricing
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.BillSettingsSnapshot
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Tax Report: what was taxed over a period, at what rate, and what that came to.
 *
 * A line per tax and slab - SGST at 2.5%, CGST at 2.5%, SGST at 6% and so on - which
 * is the shape a return is filed in. Not a line per bill: a filing is made against
 * rates, and a bill carrying three rates has no single rate of its own to report.
 *
 * ## How the figures are worked out
 *
 * By running each bill line back through [BillPricing] - **the same function that
 * priced it when it was sold**, given the same inputs - rather than by inferring
 * anything from what it came to. The line stores its rate, quantity, tax rates and
 * discount; the bill stores the rules those were priced under. Put the two together
 * and the result is not an approximation of the bill's arithmetic, it *is* the bill's
 * arithmetic, and it cannot drift from the receipt because there is only one copy of
 * it.
 *
 * That matters because two of the rules genuinely change the answer, and neither can
 * be recovered from the stored totals alone:
 *
 * - **Inclusive or exclusive.** An inclusive line's rate was applied to
 *   `gross / (1 + r)`, an exclusive line's to the gross itself. The same listed price
 *   is a different taxable value under each.
 * - **Discount before or after tax.** A pre-tax discount comes off the base and the
 *   rate applies to what remains. A post-tax discount leaves the base whole - tax is
 *   charged on the full value - and reduces only what the customer pays. Same
 *   discount, same rate, different base and different tax.
 *
 * Both come from `settings_snapshot`, frozen onto the bill at the moment of sale.
 * Today's Tax Settings must never be consulted here: a shop that has since moved from
 * inclusive to exclusive pricing, or moved its discount across the tax line, still has
 * the old bills in its books, and they were taxed the way they were taxed.
 *
 * A line on a bill with no snapshot, or one carrying IGST (which [BillPricing] does
 * not model), falls back to what was stored, with the base recovered by inverting the
 * rate off the booked tax - `tax x 100 / rate`, which needs no rules at all.
 */
class TaxReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One tax at one rate, over the whole period. */
    data class Line(
        /** SGST, CGST, IGST or VAT. */
        val tax: String,
        /** The rate it was charged at, as a percentage. */
        val rate: Double,
        /** The value that rate was charged on. */
        val amount: Double,
        /** What it came to. */
        val taxAmount: Double
    )

    /**
     * The whole report: the period asked for and every tax slab inside it.
     *
     * Totalled from the listed lines rather than by a second query, so the figure at
     * the foot is the figures above it by construction.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val lines: List<Line>,
        /**
         * The period's Service Charge and other extra charges (Parcel Charge
         * among them), read off `td_bills` rather than folded into the slabs
         * above: a charge is not sold at a rate of its own to file against, and
         * [Line]'s per-slab shape - which per-line `td_bill_items` storage never
         * carried a charge-tax share for in the first place - has no row for one
         * to join. Bolted on as one flat pair of totals instead, for the
         * period's own bills, the same way every other report now shows them.
         */
        val charges: BillCharges = BillCharges(0.0, 0.0)
    ) {
        val slabCount: Int get() = lines.size

        /** Every tax charged over the period - what the report is read for. */
        val totalTax: Double get() = BillRounding.toPaise(lines.sumOf { it.taxAmount })

        val isEmpty: Boolean get() = lines.isEmpty()
    }

    /** A period's Service Charge and other extra charges, summed from `td_bills`. */
    data class BillCharges(val service: Double, val other: Double)

    /** What one slab has accumulated so far, before it becomes a [Line]. */
    private class Sum {
        var amount = 0.0
        var tax = 0.0
    }

    /**
     * Every tax slab charged between [fromDate] and [toDate] inclusive, both
     * `yyyy-MM-dd`, in the order the taxes are read and by rate within each.
     *
     * Voided and cancelled bills are left out: they are not sales, and no tax is
     * owed on them. A line that carried no tax contributes to no slab - there is
     * nothing to file against it - so a zero-rated sale is simply absent rather than
     * listed at 0%.
     */
    fun between(fromDate: String, toDate: String): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        // substr(...,1,10) rather than a plain comparison: bill_date is written as
        // yyyy-MM-dd, but a row that ever carried a time on it would sort outside
        // the range on its final day and silently drop off the report.
        val sql = """
            SELECT COALESCE(i.rate, 0), COALESCE(i.quantity, 0),
                   COALESCE(i.cgst_rate, 0), COALESCE(i.sgst_rate, 0),
                   COALESCE(i.igst_rate, 0), COALESCE(i.vat_rate, 0),
                   COALESCE(i.discount_amount, 0),
                   COALESCE(i.cgst_amount, 0), COALESCE(i.sgst_amount, 0),
                   COALESCE(i.igst_amount, 0), COALESCE(i.vat_amount, 0),
                   COALESCE(i.item_total, 0),
                   b.settings_snapshot
            FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = i.bill_id
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate).apply {
            if (store != null) add(store.toString())
        }

        val slabs = LinkedHashMap<Pair<String, Double>, Sum>()
        fun add(tax: String, rate: Double, amount: Double, taxAmount: Double) {
            // A tax that was never in play on this line has no slab to join. Rate
            // without amount would list a 0% slab; amount without rate would put a
            // taxable value against a tax that was not charged.
            if (rate <= 0.0 && taxAmount <= 0.0) return
            val sum = slabs.getOrPut(tax to rate) { Sum() }
            sum.amount += amount
            sum.tax += taxAmount
        }

        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val cgstRate = c.getDouble(2)
                val sgstRate = c.getDouble(3)
                val igstRate = c.getDouble(4)
                val vatRate = c.getDouble(5)
                var cgst = c.getDouble(7)
                var sgst = c.getDouble(8)
                val igst = c.getDouble(9)
                var vat = c.getDouble(10)
                val snapshot = BillSettingsSnapshot.parse(c.getString(12))

                val base: Double
                if (snapshot != null && igstRate <= 0.0 && igst <= 0.0) {
                    val priced = BillPricing.price(
                        rate = c.getDouble(0),
                        quantity = c.getDouble(1),
                        cgstRate = cgstRate,
                        sgstRate = sgstRate,
                        vatRate = vatRate,
                        discountAmount = c.getDouble(6),
                        taxEnabled = snapshot.taxEnabled,
                        inclusive = snapshot.inclusive,
                        discountPreTax = snapshot.discountPreTax
                    )
                    base = priced.taxable
                    cgst = priced.cgst
                    sgst = priced.sgst
                    vat = priced.vat
                } else {
                    val rate = cgstRate + sgstRate + igstRate + vatRate
                    val tax = cgst + sgst + igst + vat
                    base = if (rate > 0.0) tax * 100.0 / rate else c.getDouble(11)
                }

                // SGST first, as the slip has always set them.
                add(SGST, sgstRate, base, sgst)
                add(CGST, cgstRate, base, cgst)
                add(IGST, igstRate, base, igst)
                add(VAT, vatRate, base, vat)
            }
        }

        val order = listOf(SGST, CGST, IGST, VAT)
        val lines = slabs.entries
            .sortedWith(compareBy({ order.indexOf(it.key.first) }, { it.key.second }))
            .map { (key, sum) ->
                Line(
                    tax = key.first,
                    rate = key.second,
                    amount = BillRounding.toPaise(sum.amount),
                    taxAmount = BillRounding.toPaise(sum.tax)
                )
            }
        return Report(fromDate, toDate, lines, billCharges(helper.readableDatabase, fromDate, toDate, store))
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

    companion object {
        private const val SGST = "SGST"
        private const val CGST = "CGST"
        private const val IGST = "IGST"
        private const val VAT = "VAT"

        /**
         * A period's Service Charge and other extra charges, summed straight off
         * `td_bills` - one query, not per item or per bill, since neither figure is
         * a fact that belongs to a single line. Shared rather than reworked out per
         * report: [ItemWiseReportDao] and [CustomerItemWiseReportDao] bolt the same
         * pair of totals onto reports grouped by something other than the bill -
         * product, or customer-and-product - which have no per-bill row of their
         * own for a charge to join, the same reason this report's own per-rate-slab
         * rows do not carry one either.
         *
         * [customerId] narrows to one customer's bills, for a report scoped to one
         * - null reads every bill in the period, matching this report's own use.
         */
        fun billCharges(
            db: android.database.sqlite.SQLiteDatabase,
            fromDate: String,
            toDate: String,
            store: Long?,
            customerId: Long? = null
        ): BillCharges {
            val storeClause = if (store != null) "AND store_id = ?" else ""
            val customerClause = if (customerId != null) "AND customer_id = ?" else ""
            val args = mutableListOf(fromDate, toDate).apply {
                if (store != null) add(store.toString())
                if (customerId != null) add(customerId.toString())
            }
            db.rawQuery(
                """
                SELECT COALESCE(SUM(service_charge_amount), 0), COALESCE(SUM(tot_other_charges_amount), 0)
                FROM ${DatabaseHelper.Tables.TD_BILLS}
                WHERE substr(bill_date, 1, 10) BETWEEN ? AND ?
                  AND COALESCE(is_voided, 0) = 0
                  AND COALESCE(bill_status, 'COMPLETED') <> 'CANCELLED'
                  $storeClause
                  $customerClause
                """.trimIndent(),
                args.toTypedArray()
            ).use { c ->
                return if (c.moveToFirst()) {
                    BillCharges(BillRounding.toPaise(c.getDouble(0)), BillRounding.toPaise(c.getDouble(1)))
                } else {
                    BillCharges(0.0, 0.0)
                }
            }
        }
    }
}
