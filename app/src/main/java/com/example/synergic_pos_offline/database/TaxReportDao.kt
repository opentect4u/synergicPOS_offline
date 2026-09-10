package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.CalendarGrain
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
 * By reading the money that was written at the sale, and only that. Each line stores
 * the tax it was charged; the taxable value behind it is that line's total less that
 * tax, which is the base whichever way the price was quoted - an inclusive total
 * already carries its tax, an exclusive one has it added on top, and taking the tax
 * off lands on the same figure either way.
 *
 * ## Why it no longer re-prices the line
 *
 * It used to run every line back through [BillPricing], from its rates and the rules
 * frozen onto its bill, on the reasoning that re-deriving the arithmetic could not
 * drift from the receipt. The flaw was not in the arithmetic but in there being two
 * of them: [BillWiseReportDao] sums the totals each bill was SAVED with and does no
 * re-pricing at all, so the same books reached the same period's tax by two routes
 * with nothing holding them to each other. A paisa of rounding, a bill written before
 * settings_snapshot existed, an IGST line that [BillPricing] does not model - any of
 * it put two reports a figure apart, and neither could be shown to be wrong.
 *
 * The stored figures settle it. `td_bill_items`' tax columns are the very numbers
 * that were summed into `td_bills`' own totals (see BillDao, which writes the priced
 * line and that same run's bill total together), so this report, the item-wise report
 * and the bill-wise report now agree by construction rather than by coincidence -
 * CGST, SGST, IGST and VAT alike.
 *
 * The rules that were frozen onto the bill still decide what it was taxed - they did
 * so when it was priced, and their answer is what is stored. Nothing here consults
 * today's Tax Settings, for the same reason it never did: a shop that has since moved
 * from inclusive to exclusive pricing still has the old bills in its books, and they
 * were taxed the way they were taxed.
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

    /** A period's Service Charge and other extra charges, summed from `td_bills`.
     *  [other] is non-parcel - [parcel] is Parcel Charge's own share, broken out
     *  separately (see ChargeDao.Kind.PARCEL) rather than folded into [other]. */
    /**
     * The bill-level money a period carries that is not the goods themselves.
     *
     * [roundOff] rides along with the charges because it is wanted by the same
     * callers and for the same reason: a report that states what a period came to has
     * to add the same things the bill added, and the rounding was one of them.
     */
    data class BillCharges(
        val service: Double,
        val other: Double,
        val parcel: Double = 0.0,
        val roundOff: Double = 0.0
    )

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
            WHERE substr(
                      COALESCE(NULLIF(TRIM(b.bill_date_time), ''), b.bill_date || ' 00:00'),
                      1, 10
                  ) BETWEEN ? AND ?
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
                val cgst = c.getDouble(7)
                val sgst = c.getDouble(8)
                val igst = c.getDouble(9)
                val vat = c.getDouble(10)
                val itemTotal = c.getDouble(11)

                // READ, NOT RECOMPUTED - the same change ItemWiseReportDao made, and
                // for the same reason. This ran each line back through BillPricing
                // while BillWiseReportDao summed the totals the bill was saved with,
                // so the period's tax had two routes to arrive by and nothing held
                // them together.
                //
                // The taxable value is the line's total less the tax booked on it,
                // which is the taxable base whichever way the price was quoted: an
                // inclusive total already carries its tax, an exclusive one has it
                // added on. An untaxed line has none to take off and contributes its
                // whole total.
                val base = itemTotal - (cgst + sgst + igst + vat)

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
         *
         * [grain] MUST MATCH THE PRECISION OF [fromDate] AND [toDate], and is the
         * whole reason this parameter exists. The bill's moment is cut to the grain's
         * own length before it is compared, and a mismatch does not merely blur the
         * range - it empties it. This was fixed to 10 characters, a date, while the
         * Time Wise Item Report asked for `2026-09-09 00:00` to `2026-09-09 23:59`:
         * the cut left `2026-09-09`, and a string that is a PREFIX of another sorts
         * BEFORE it, so `'2026-09-09' >= '2026-09-09 00:00'` is false and the BETWEEN
         * matched no bill at all. Every charge came back zero, silently, and the same
         * period read one way on the Item Wise Report and another on the Time Wise.
         */
        fun billCharges(
            db: android.database.sqlite.SQLiteDatabase,
            fromDate: String,
            toDate: String,
            store: Long?,
            customerId: Long? = null,
            grain: CalendarGrain = CalendarGrain.DAY
        ): BillCharges {
            val storeClause = if (store != null) "AND store_id = ?" else ""
            val customerClause = if (customerId != null) "AND customer_id = ?" else ""
            val args = mutableListOf(fromDate, toDate).apply {
                if (store != null) add(store.toString())
                if (customerId != null) add(customerId.toString())
            }
            db.rawQuery(
                """
                SELECT COALESCE(SUM(service_charge_amount), 0), COALESCE(SUM(tot_other_charges_amount), 0),
                       COALESCE(SUM(parcel_charge_amount), 0),
                       COALESCE(SUM(tot_round_off_amount), 0)
                FROM ${DatabaseHelper.Tables.TD_BILLS}
                WHERE substr(
                          COALESCE(NULLIF(TRIM(bill_date_time), ''), bill_date || ' 00:00'),
                          1, ${grain.storedLength}
                      ) BETWEEN ? AND ?
                  AND COALESCE(is_voided, 0) = 0
                  AND COALESCE(bill_status, 'COMPLETED') <> 'CANCELLED'
                  $storeClause
                  $customerClause
                """.trimIndent(),
                args.toTypedArray()
            ).use { c ->
                return if (c.moveToFirst()) {
                    val parcel = BillRounding.toPaise(c.getDouble(2))
                    val other = (BillRounding.toPaise(c.getDouble(1)) - parcel).coerceAtLeast(0.0)
                    BillCharges(
                        BillRounding.toPaise(c.getDouble(0)), other, parcel,
                        BillRounding.toPaise(c.getDouble(3))
                    )
                } else {
                    BillCharges(0.0, 0.0)
                }
            }
        }
    }
}
