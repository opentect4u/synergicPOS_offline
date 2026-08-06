package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.BillPricing
import com.example.synergic_pos_offline.utils.BillSettingsSnapshot
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sale returns, taken either way round.
 *
 * A bill-wise return starts from the original bill and gives back lines off it, so
 * every figure is already recorded and the return is a matter of choosing how much
 * of each line comes back. An item-wise return starts from the item instead, with
 * no bill behind it, so the line has to be priced here - which it is through the
 * same [BillPricing] a sale uses, under the Tax Settings live at the time, so a
 * returned item is valued exactly as it was sold.
 *
 * Both land in [DatabaseHelper.Tables.TD_SALE_RETURNS] with their lines in
 * [DatabaseHelper.Tables.TD_RETURN_ITEMS]; an item-wise return simply has no
 * original bill against it.
 */
class ReturnDao(context: Context) {

    private val appContext = context.applicationContext
    private val helper = DatabaseHelper.getInstance(context)
    private val taxSettingsDao = TaxSettingsDao(context)
    private val stockDao by lazy { StockDao(context) }

    /** A sellable item, with the rate, tax and discount it is currently sold at. */
    data class Item(
        val productId: Long,
        val name: String,
        val barcode: String,
        val hsn: String,
        val rate: Double,
        val cgstRate: Double,
        val sgstRate: Double,
        val vatRate: Double,
        /**
         * The rate's own pre-configured discount, and whether it is a percentage or
         * an amount ("P"/"A", null when none is set). Applied only when Tax Settings
         * has item-wise discount switched on, exactly as billing applies it - see
         * [discountFor].
         */
        val discountValue: Double = 0.0,
        val discountType: String? = null
    )

    /**
     * One line being returned, priced.
     *
     * The tax is reported split rather than as one figure because that is how it
     * was charged and how it has to be accounted for: a return reverses a CGST and
     * an SGST entry, not a combined one. [discount] is what came off this line on
     * the original sale - pro-rated when only part of the line is coming back - and
     * is already worked into [amount], so the refund is what was actually paid
     * rather than the listed price.
     */
    data class ReturnLine(
        val productId: Long?,
        /** The `td_bill_items` row this came off, on a bill-wise return. */
        val billItemId: Long?,
        val name: String,
        val quantity: Double,
        val rate: Double,
        val cgstRate: Double,
        val sgstRate: Double,
        val vatRate: Double,
        /** Quantity x rate, before discount or tax. */
        val gross: Double,
        /** The discount coming back with this line. */
        val discount: Double,
        /** The value tax was charged on. */
        val taxable: Double,
        val cgst: Double,
        val sgst: Double,
        val vat: Double,
        /** What the customer gets back for this line, tax included. */
        val amount: Double
    ) {
        /** Tax charged on the returned quantity, however the regime splits it. */
        val tax: Double get() = cgst + sgst + vat
    }

    /**
     * One line of a return's summary, as both the screen and the printed slip
     * state it.
     *
     * [isMoney] separates the item count from the figures - it is the one row that
     * is not an amount - and [emphasis] marks the refund, which is set larger and
     * bolder wherever it appears.
     */
    data class SummaryRow(
        val label: String,
        val value: Double,
        val isMoney: Boolean = true,
        val emphasis: Boolean = false
    )

    /** A saved return, and what its slip prints. */
    data class Result(
        val id: Long,
        val returnNumber: String,
        val dateTime: String,
        val originalBillNumber: String?,
        val lines: List<ReturnLine>,
        val totalGross: Double,
        val totalDiscount: Double,
        val totalCgst: Double,
        val totalSgst: Double,
        val totalVat: Double,
        val totalAmount: Double
    ) {
        val totalTax: Double get() = totalCgst + totalSgst + totalVat
    }

    /** A line of an original bill, as the bill-wise screen offers it back. */
    data class BillLine(
        val billItemId: Long,
        val productId: Long?,
        val name: String,
        /** How many were sold on the bill. */
        val soldQuantity: Double,
        /** How many of those have already come back on an earlier return. */
        val returnedQuantity: Double,
        val rate: Double,
        /**
         * The discount given on the whole line at sale time. Only part of it comes
         * back when only part of the line does - see [discountFor].
         */
        val discountAmount: Double,
        val cgstRate: Double,
        val sgstRate: Double,
        val vatRate: Double
    ) {
        /** The most that can still be returned off this line. */
        val returnableQuantity: Double get() = (soldQuantity - returnedQuantity).coerceAtLeast(0.0)

        /**
         * The share of the line's discount that belongs to [quantity] of it.
         *
         * Returning two of five discounted items gives back two fifths of the
         * discount, not all of it and not none: the customer paid the discounted
         * price, so that is what comes back.
         */
        fun discountFor(quantity: Double): Double =
            if (soldQuantity <= 0.0) 0.0
            else BillRounding.toPaise(discountAmount * (quantity / soldQuantity))
    }

    // ---- Item lookup (item-wise) -------------------------------------------

    /**
     * Items whose name, barcode or HSN code contains [term] - the three things an
     * operator has to hand when someone brings something back without a bill.
     *
     * The rate is the product's default one, with the tax split recorded against it,
     * so what the return is valued at is what the till would have sold it for.
     */
    fun searchItems(term: String, limit: Int = 25): List<Item> {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return emptyList()
        // LIKE's own wildcards in the typed text would silently widen the search.
        val pattern = "%" + trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        val list = mutableListOf<Item>()
        helper.readableDatabase.rawQuery(
            """
            SELECT p.id, p.product_name, p.bar_code, p.hsn_code,
                   r.rate, r.cgst_rate, r.sgst_rate, r.vat_rate, r.discount, r.discount_type
            FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCT_RATES} r
                   ON r.id = (
                        SELECT id FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES}
                        WHERE product_id = p.id
                        ORDER BY "default" DESC, id ASC LIMIT 1
                   )
            WHERE p.product_name LIKE ? ESCAPE '!'
               OR p.bar_code LIKE ? ESCAPE '!'
               OR p.hsn_code LIKE ? ESCAPE '!'
            ORDER BY p.product_name COLLATE NOCASE ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(pattern, pattern, pattern, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Item(
                        productId = c.getLong(0),
                        name = c.getString(1).orEmpty(),
                        barcode = c.getString(2).orEmpty(),
                        hsn = c.getString(3).orEmpty(),
                        rate = if (c.isNull(4)) 0.0 else c.getDouble(4),
                        cgstRate = if (c.isNull(5)) 0.0 else c.getDouble(5),
                        sgstRate = if (c.isNull(6)) 0.0 else c.getDouble(6),
                        vatRate = if (c.isNull(7)) 0.0 else c.getDouble(7),
                        discountValue = if (c.isNull(8)) 0.0 else c.getDouble(8),
                        discountType = c.getString(9)
                    )
                )
            }
        }
        return list
    }

    /**
     * The discount on [quantity] of [item], under the Tax Settings in force.
     *
     * Only applied when item-wise discount is switched on: with it off, a product's
     * configured discount is not what the till charges, so it is not what a return
     * gives back either. Worked out by the same routine billing uses, against the
     * line's raw pre-tax base, which is the shape [priceLine] expects.
     */
    fun discountFor(item: Item, quantity: Double): Double {
        val settings = taxSettingsDao.load()
        val itemWise = settings.discountEnabled &&
            settings.discountType == TaxSettingsDao.DiscountType.ITEM_WISE
        if (!itemWise || item.discountValue <= 0.0 || item.discountType == null) return 0.0

        val regime = GstCalculator.regimeFor(settings.gstEnabled, settings.vatEnabled)
        val combined = when (regime) {
            GstCalculator.TaxRegime.GST -> item.cgstRate + item.sgstRate
            GstCalculator.TaxRegime.VAT -> item.vatRate
            GstCalculator.TaxRegime.NONE -> 0.0
        }
        val inclusive = when (regime) {
            GstCalculator.TaxRegime.GST -> settings.gstMode == TaxSettingsDao.GstMode.INCLUSIVE
            GstCalculator.TaxRegime.VAT -> settings.vatMode == TaxSettingsDao.GstMode.INCLUSIVE
            GstCalculator.TaxRegime.NONE -> false
        }
        val mode = if (item.discountType == "A") GstCalculator.DiscountMode.AMOUNT
        else GstCalculator.DiscountMode.PERCENT
        return GstCalculator.itemDiscountAgainstRawBase(
            mrp = item.rate * quantity,
            rate = combined,
            inclusive = inclusive,
            preTax = settings.discountPosition == TaxSettingsDao.DiscountPosition.PRE_TAX,
            mode = mode,
            value = item.discountValue
        )
    }

    // ---- Bill lookup (bill-wise) -------------------------------------------

    /**
     * The lines of [receiptNo], each carrying how much of it has already been
     * returned - so a line cannot be given back twice over across two visits.
     */
    fun linesOfBill(receiptNo: Long): List<BillLine> {
        val list = mutableListOf<BillLine>()
        helper.readableDatabase.rawQuery(
            """
            SELECT i.id, i.product_id, COALESCE(p.product_name, 'Item'),
                   i.quantity, i.rate, i.cgst_rate, i.sgst_rate, i.vat_rate,
                   COALESCE(i.discount_amount, 0),
                   COALESCE((SELECT SUM(ri.return_quantity)
                             FROM ${DatabaseHelper.Tables.TD_RETURN_ITEMS} ri
                             WHERE ri.bill_item_id = i.id), 0)

            FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON p.id = i.product_id
            WHERE i.bill_id = ?
            ORDER BY i.id ASC
            """.trimIndent(),
            arrayOf(receiptNo.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    BillLine(
                        billItemId = c.getLong(0),
                        productId = if (c.isNull(1)) null else c.getLong(1),
                        name = c.getString(2).orEmpty(),
                        soldQuantity = c.getDouble(3),
                        rate = c.getDouble(4),
                        cgstRate = c.getDouble(5),
                        sgstRate = c.getDouble(6),
                        vatRate = c.getDouble(7),
                        discountAmount = c.getDouble(8),
                        returnedQuantity = c.getDouble(9)
                    )
                )
            }
        }
        return list
    }

    /**
     * The tax rules a line is priced under: which regime, whether the rate was
     * included in the listed price, and whether a discount came off before tax.
     *
     * Held as a value rather than read from settings inside the pricing, because
     * the two callers need different answers - see [basisForBill].
     */
    data class PricingBasis(
        val regime: GstCalculator.TaxRegime,
        val inclusive: Boolean,
        val discountPreTax: Boolean
    )

    /**
     * The rules [receiptNo] was actually billed under, taken from the settings
     * snapshot frozen onto it at the time of sale.
     *
     * A return gives back what was charged, and what was charged was worked out
     * under the settings in force on the day. Pricing it under today's instead
     * would refund the wrong amount whenever GST has been switched from inclusive
     * to exclusive, a rate has moved, or the discount position has changed - none
     * of which are rare over the life of a till.
     *
     * Bills raised before snapshots existed have none, and fall back to the live
     * settings: it is the only information there is for them.
     */
    fun basisForBill(receiptNo: Long): PricingBasis {
        val json = helper.readableDatabase.rawQuery(
            "SELECT settings_snapshot FROM ${DatabaseHelper.Tables.TD_BILLS} WHERE receipt_no = ?",
            arrayOf(receiptNo.toString())
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

        val snapshot = BillSettingsSnapshot.parse(json)
        return if (snapshot == null) liveBasis() else PricingBasis(
            regime = snapshot.taxRegime,
            inclusive = snapshot.inclusive,
            discountPreTax = snapshot.discountPreTax
        )
    }

    /** The rules in force now - what an item-wise return, having no bill, uses. */
    fun liveBasis(): PricingBasis {
        val settings = taxSettingsDao.load()
        val regime = GstCalculator.regimeFor(settings.gstEnabled, settings.vatEnabled)
        return PricingBasis(
            regime = regime,
            inclusive = when (regime) {
                GstCalculator.TaxRegime.GST -> settings.gstMode == TaxSettingsDao.GstMode.INCLUSIVE
                GstCalculator.TaxRegime.VAT -> settings.vatMode == TaxSettingsDao.GstMode.INCLUSIVE
                GstCalculator.TaxRegime.NONE -> false
            },
            discountPreTax = settings.discountPosition == TaxSettingsDao.DiscountPosition.PRE_TAX
        )
    }

    /** The printed number of [receiptNo], for the return slip to refer back to. */
    fun billNumberOf(receiptNo: Long): String? =
        helper.readableDatabase.rawQuery(
            "SELECT bill_number FROM ${DatabaseHelper.Tables.TD_BILLS} WHERE receipt_no = ?",
            arrayOf(receiptNo.toString())
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /**
     * Whether [receiptNo] is still inside the Sale Return Days window. Bill-wise
     * only - an item-wise return has no bill to date from.
     */
    fun withinReturnWindow(receiptNo: Long, days: Int): Boolean {
        if (days <= 0) return true
        val billDate = helper.readableDatabase.rawQuery(
            "SELECT bill_date FROM ${DatabaseHelper.Tables.TD_BILLS} WHERE receipt_no = ?",
            arrayOf(receiptNo.toString())
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return true

        return runCatching {
            withinReturnWindow(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(billDate), days)
        }.getOrDefault(true)
    }

    // ---- Pricing -----------------------------------------------------------

    /**
     * Prices [quantity] of an item for return, through the same [BillPricing] a
     * sale is priced with, so what goes back matches what was charged.
     *
     * [basis] is which rules to price under, and is the whole difference between
     * the two returns: a bill-wise return passes the bill's own frozen settings
     * ([basisForBill]) so it refunds what that bill actually charged, while an
     * item-wise return has no bill and falls back to the live settings.
     */
    fun priceLine(
        name: String,
        productId: Long?,
        billItemId: Long?,
        quantity: Double,
        rate: Double,
        cgstRate: Double,
        sgstRate: Double,
        vatRate: Double,
        discountAmount: Double = 0.0,
        basis: PricingBasis? = null
    ): ReturnLine {
        val rules = basis ?: liveBasis()
        val priced = BillPricing.price(
            rate = rate,
            quantity = quantity,
            cgstRate = cgstRate,
            sgstRate = sgstRate,
            vatRate = vatRate,
            // Whatever came off this line on the sale comes off the refund too - the
            // customer paid the discounted price, so that is what goes back. On a
            // partial return the caller has already pro-rated it.
            discountAmount = discountAmount,
            regime = rules.regime,
            inclusive = rules.inclusive,
            discountPreTax = rules.discountPreTax
        )
        return ReturnLine(
            productId = productId,
            billItemId = billItemId,
            name = name,
            quantity = quantity,
            rate = rate,
            cgstRate = cgstRate,
            sgstRate = sgstRate,
            vatRate = vatRate,
            gross = priced.subtotal,
            discount = BillRounding.toPaise(discountAmount),
            taxable = priced.taxable,
            cgst = priced.cgst,
            sgst = priced.sgst,
            vat = priced.vat,
            amount = priced.itemTotal
        )
    }

    // ---- Saving ------------------------------------------------------------

    /**
     * Files [lines] as one return, against [originalBillId] when there is one.
     *
     * Written in a single transaction: a return that recorded its lines but not its
     * header, or the other way round, would leave the till unable to say what came
     * back. Returns null when there is nothing to return or the write failed.
     */
    fun save(lines: List<ReturnLine>, originalBillId: Long? = null, reason: String? = null): Result? {
        val kept = lines.filter { it.quantity > 0.0 }
        if (kept.isEmpty()) return null

        val db = helper.writableDatabase
        val user = currentUser()
        val nowDateTime = now()
        val nowDate = nowDateTime.take(10)
        val nowTime = nowDateTime.substring(11)
        val totalAmount = BillRounding.toPaise(kept.sumOf { it.amount })

        db.beginTransaction()
        try {
            val id = db.insert(
                DatabaseHelper.Tables.TD_SALE_RETURNS, null,
                ContentValues().apply {
                    if (originalBillId != null) put("original_bill_id", originalBillId)
                    put("return_date", nowDate)
                    put("return_time", nowTime)
                    put("total_return_amount", totalAmount)
                    put("return_status", "COMPLETED")
                    reason?.takeIf { it.isNotBlank() }?.let { put("return_reason", it) }
                    put("created_by", user)
                }
            )
            if (id == -1L) return null

            // Numbered off its own row id, so the number on the paper is the row.
            val returnNumber = RETURN_PREFIX + String.format(Locale.US, "%06d", id)
            db.update(
                DatabaseHelper.Tables.TD_SALE_RETURNS,
                ContentValues().apply { put("return_bill_number", returnNumber) },
                "id = ?", arrayOf(id.toString())
            )

            // Returned goods go back on the shelf, inside this same transaction: a
            // return that refunded the customer without restoring what came back
            // would leave the count short by exactly what was handed over. Only while
            // stock is tracked - with the flag off a return touches nothing, the same
            // way a sale does not. Bill-wise and item-wise both come through here, so
            // neither can be left out; the original bill number, where there is one,
            // lets the stock go back into the batches that bill drew from.
            val restoresStock = GeneralSettingsDao.isStockEnabled(appContext)
            val soldUnder = originalBillId?.let { billNumberOf(it) }

            kept.forEach { line ->
                db.insert(
                    DatabaseHelper.Tables.TD_RETURN_ITEMS, null,
                    ContentValues().apply {
                        put("return_id", id)
                        line.billItemId?.let { put("bill_item_id", it) }
                        line.productId?.let { put("product_id", it) }
                        put("return_quantity", line.quantity)
                        put("return_amount", line.amount)
                        put("created_by", user)
                    }
                )
                val productId = line.productId?.toInt()
                if (restoresStock && productId != null) {
                    stockDao.restoreForReturn(
                        db = db,
                        productId = productId,
                        quantity = line.quantity,
                        reference = returnNumber,
                        soldUnderReference = soldUnder
                    )
                }
            }

            db.setTransactionSuccessful()
            return Result(
                id = id,
                returnNumber = returnNumber,
                dateTime = nowDateTime,
                originalBillNumber = originalBillId?.let { billNumberOf(it) },
                lines = kept,
                totalGross = BillRounding.toPaise(kept.sumOf { it.gross }),
                totalDiscount = BillRounding.toPaise(kept.sumOf { it.discount }),
                totalCgst = BillRounding.toPaise(kept.sumOf { it.cgst }),
                totalSgst = BillRounding.toPaise(kept.sumOf { it.sgst }),
                totalVat = BillRounding.toPaise(kept.sumOf { it.vat }),
                totalAmount = totalAmount
            )
        } finally {
            db.endTransaction()
        }
    }

    private fun currentUser(): String? = SessionManager.currentUser?.userId

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        private const val RETURN_PREFIX = "RT"
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /**
         * Whether a bill sold on [soldOn] can still be returned against, under a
         * limit of [days]. No limit set (0 or less) means no limit at all, and a bill
         * whose date cannot be read is allowed rather than refused - a return should
         * not be blocked by a date the till failed to parse.
         *
         * The one rule, shared: the return picker filters its list with it and the
         * bill-wise screen checks it again on the way in, so a bill cannot be listed
         * as returnable and then refused, or the other way round.
         */
        fun withinReturnWindow(soldOn: Date?, days: Int): Boolean {
            if (days <= 0 || soldOn == null) return true
            val elapsed = (System.currentTimeMillis() - soldOn.time) / MILLIS_PER_DAY
            return elapsed <= days
        }

        /**
         * How a return totals up: the count, what the goods came to, what came off,
         * each tax that was charged, and the refund those arrive at.
         *
         * Computed from the lines rather than stated twice, so the figures on the
         * screen and the figures on the printed slip are the same list - a row
         * added or a threshold changed here reaches both at once. A tax or discount
         * row only appears when it carries something, which is why this cannot
         * simply be a fixed set of fields.
         */
        fun summaryRows(lines: List<ReturnLine>): List<SummaryRow> = buildList {
            add(SummaryRow("ITEMS", lines.size.toDouble(), isMoney = false))
            add(SummaryRow("GROSS", BillRounding.toPaise(lines.sumOf { it.gross })))
            lines.sumOf { it.discount }.takeIf { it > 0.005 }
                ?.let { add(SummaryRow("DISCOUNT", BillRounding.toPaise(it))) }
            lines.sumOf { it.cgst }.takeIf { it > 0.005 }
                ?.let { add(SummaryRow("CGST", BillRounding.toPaise(it))) }
            lines.sumOf { it.sgst }.takeIf { it > 0.005 }
                ?.let { add(SummaryRow("SGST", BillRounding.toPaise(it))) }
            lines.sumOf { it.vat }.takeIf { it > 0.005 }
                ?.let { add(SummaryRow("VAT", BillRounding.toPaise(it))) }
            add(
                SummaryRow(
                    "REFUND",
                    BillRounding.toPaise(lines.sumOf { it.amount }),
                    emphasis = true
                )
            )
        }
    }
}
