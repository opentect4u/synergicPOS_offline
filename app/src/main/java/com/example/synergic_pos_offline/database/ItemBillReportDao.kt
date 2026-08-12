package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillPricing
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.BillSettingsSnapshot
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Item Bill Report: one item's bills over a period.
 *
 * Where the Item Wise Report gives a line per item and ranks them against each other,
 * this gives a line per *bill* for one item - how much of it went on each, at what
 * rate, and what that came to. The report reached for when a single line is being
 * queried: who bought it, how often, and whether the price held.
 *
 * The amount is the taxable value, worked out by running each line back through
 * [BillPricing] with the rules frozen onto its bill - see [ItemWiseReportDao], which
 * totals the same figure, so one item's bills here add up to its line there.
 */
class ItemBillReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** Something that can be reported on - a row of `md_products`. */
    data class Item(
        val productId: Long,
        val name: String,
        val barcode: String,
        val hsn: String
    ) {
        /**
         * How the picker lists it: name, barcode and HSN on one line.
         *
         * All three, because an item is looked up by whichever of them the person
         * searching has to hand - the name off the shelf, the barcode off the packet,
         * the HSN off a tax return. The dropdown matches on any word of it.
         */
        val label: String get() = listOf(
            name.ifBlank { "Unnamed item" },
            barcode.ifBlank { "-" },
            hsn.ifBlank { "-" }
        ).joinToString("   ")
    }

    /** One bill this item appeared on. */
    data class Line(
        val billNumber: String,
        val quantity: Double,
        /** The rate it was rung up at on that bill. */
        val rate: Double,
        /** The value it was taxed on - see the class notes. */
        val amount: Double
    )

    /**
     * The whole report: the period, the item it was run for, and its bills.
     *
     * Totalled from the listed lines rather than by a second query, so the summary
     * and the rows above it cannot disagree.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val item: Item?,
        val lines: List<Line>
    ) {
        val billCount: Int get() = lines.size
        val totalQuantity: Double get() = total { it.quantity }

        /**
         * The rate column added up.
         *
         * Not an average and not a price - the sum of what the item was rung up at on
         * each bill, which is what this slip has always totalled. It is read as a
         * check that the rate held: four bills at one rate total four times it, and
         * anything else says the price moved.
         */
        val totalRate: Double get() = total { it.rate }

        val totalAmount: Double get() = total { it.amount }

        val isEmpty: Boolean get() = lines.isEmpty()

        private fun total(pick: (Line) -> Double): Double =
            BillRounding.toPaise(lines.sumOf { pick(it) })
    }

    /**
     * Everything on the product master, for the picker to search.
     *
     * The whole list rather than only what has sold: an item is chosen before the
     * period is read, so there is no way to know yet which will turn out to have
     * moved - and "no bills in this period" is a perfectly good answer for one that
     * did not.
     */
    fun items(): List<Item> {
        val store = currentStoreId()
        val where = if (store != null) "WHERE store_id = ?" else ""
        val args = if (store != null) arrayOf(store.toString()) else null

        val list = mutableListOf<Item>()
        helper.readableDatabase.rawQuery(
            """
            SELECT id, COALESCE(product_name, ''), COALESCE(bar_code, ''), COALESCE(hsn_code, '')
            FROM ${DatabaseHelper.Tables.MD_PRODUCTS} $where
            ORDER BY product_name COLLATE NOCASE ASC
            """.trimIndent(),
            args
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Item(
                        productId = c.getLong(0),
                        name = c.getString(1).orEmpty(),
                        barcode = c.getString(2).orEmpty(),
                        hsn = c.getString(3).orEmpty()
                    )
                )
            }
        }
        return list
    }

    /**
     * [item]'s bills between [fromDate] and [toDate] inclusive, both `yyyy-MM-dd`,
     * in the order they were rung up.
     *
     * Oldest first, not ranked: this is one item's run through the period, and it is
     * read down the way the days happened.
     *
     * A bill that carried the item on two lines - the same thing at two rates, say -
     * is two rows, because that is two prices and the report exists to show them.
     *
     * Voided and cancelled bills are left out, exactly as the other reports leave
     * them out: they are not sales.
     */
    fun between(fromDate: String, toDate: String, item: Item): Report {
        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""

        val sql = """
            SELECT b.bill_number,
                   COALESCE(i.rate, 0), COALESCE(i.quantity, 0),
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
              AND i.product_id = CAST(? AS INTEGER)
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
            ORDER BY b.receipt_no ASC, i.id ASC
        """.trimIndent()

        val args = mutableListOf(fromDate, toDate, item.productId.toString()).apply {
            if (store != null) add(store.toString())
        }

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val igstRate = c.getDouble(5)
                val igst = c.getDouble(10)
                val snapshot = BillSettingsSnapshot.parse(c.getString(13))

                val amount = if (snapshot != null && igstRate <= 0.0 && igst <= 0.0) {
                    BillPricing.price(
                        rate = c.getDouble(1),
                        quantity = c.getDouble(2),
                        cgstRate = c.getDouble(3),
                        sgstRate = c.getDouble(4),
                        vatRate = c.getDouble(6),
                        discountAmount = c.getDouble(7),
                        regime = snapshot.taxRegime,
                        inclusive = snapshot.inclusive,
                        discountPreTax = snapshot.discountPreTax
                    ).taxable
                } else {
                    val rate = c.getDouble(3) + c.getDouble(4) + igstRate + c.getDouble(6)
                    val tax = c.getDouble(8) + c.getDouble(9) + igst + c.getDouble(11)
                    if (rate > 0.0) tax * 100.0 / rate else c.getDouble(12)
                }

                lines.add(
                    Line(
                        billNumber = c.getString(0).orEmpty().ifBlank { "-" },
                        quantity = BillRounding.toPaise(c.getDouble(2)),
                        rate = BillRounding.toPaise(c.getDouble(1)),
                        amount = BillRounding.toPaise(amount)
                    )
                )
            }
        }
        return Report(fromDate, toDate, item, lines)
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
