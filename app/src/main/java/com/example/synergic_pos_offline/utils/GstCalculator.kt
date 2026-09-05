package com.example.synergic_pos_offline.utils

/**
 * Single source of truth for how a bill line's GST is worked out, shared by the
 * billing screen, the checkout screen and the bill writer so the three cannot
 * drift apart and quote different tax for the same cart.
 *
 * CGST and SGST are always applied as separate rates taken from md_product_rates.
 * They are commonly equal halves of the GST slab, but the master permits them to
 * differ, so nothing here assumes a symmetric split.
 */
object GstCalculator {

    /** How a whole-bill discount is entered: a percentage of the subtotal, or a flat rupee amount. */
    enum class DiscountMode { PERCENT, AMOUNT }

    /**
     * Which tax a *product* is charged under. GST and VAT are mutually exclusive
     * per product - md_product_rates carries one or the other, never both - and
     * NONE when it carries neither. Not a store-wide setting any more: Tax
     * Settings only switches tax on or off (see [TaxSettingsDao.TaxSettings]);
     * which of GST/VAT applies is read straight off the product, via [regimeOf].
     */
    enum class TaxRegime { NONE, GST, VAT }

    /** Classifies a product/line by whichever of its own rates is set - GST if it
     *  carries CGST/SGST, VAT if it carries VAT, NONE if it carries neither. A
     *  product is never expected to carry both. */
    fun regimeOf(cgstRate: Double, sgstRate: Double, vatRate: Double): TaxRegime = when {
        cgstRate + sgstRate > 0.0 -> TaxRegime.GST
        vatRate > 0.0 -> TaxRegime.VAT
        else -> TaxRegime.NONE
    }

    /**
     * The value tax is actually calculated on: [gross] unchanged when the listed
     * price is exclusive of tax (tax is added on top), or with [rate]'s tax backed
     * out when the price already includes it. [rate] should be the *combined* rate
     * for whichever regime is active - CGST+SGST together for GST - so an inclusive
     * price is stripped once, not twice.
     */
    fun taxableBase(gross: Double, rate: Double, inclusive: Boolean): Double =
        if (inclusive && rate > 0.0) gross / (1.0 + rate / 100.0) else gross

    /**
     * The value a line is taxed on: its gross, less its share of the whole-bill
     * discount. GST is charged on what the customer actually pays, so the discount
     * has to come off before the rate is applied.
     */
    fun taxableValue(price: Double, qty: Int, discountPct: Int): Double =
        taxableValue(price * qty, price * qty * discountPct.coerceIn(0, 100) / 100.0)

    /** As above, when the line's discount is already known as an amount. */
    fun taxableValue(gross: Double, discountAmount: Double): Double =
        (gross - discountAmount).coerceAtLeast(0.0)

    /** Tax on [taxable] at [rate] percent. */
    fun taxAmount(taxable: Double, rate: Double): Double =
        if (rate <= 0.0) 0.0 else taxable * rate / 100.0

    /**
     * A whole-bill discount resolved to rupees, whichever way it was entered.
     * Never more than [subtotal] - a flat amount typed larger than the sale itself
     * cannot make the bill negative.
     */
    fun discountAmount(subtotal: Double, mode: DiscountMode, value: Double): Double = when (mode) {
        DiscountMode.PERCENT -> subtotal * value.coerceIn(0.0, 100.0) / 100.0
        DiscountMode.AMOUNT -> value.coerceIn(0.0, subtotal)
    }

    /**
     * [base] (a line's taxable value, already adjusted for inclusive/exclusive
     * pricing) with its share of a whole-bill [discountAmount] taken off before tax
     * is applied to it - the "pre-tax discount" position. The share is weighted by
     * [gross]'s proportion of [grossSubtotal] rather than of the base, since the
     * discount was entered against the listed (gross) subtotal shown on screen, not
     * the tax-stripped figure.
     */
    fun taxableValueSpread(base: Double, gross: Double, grossSubtotal: Double, discountAmount: Double): Double =
        taxableValue(base, if (grossSubtotal > 0) gross / grossSubtotal * discountAmount else 0.0)

    /**
     * A line's taxable value, tax and final sale price once an item-wise discount
     * is worked in. GST is always charged on what the customer actually pays -
     * [taxable]/[tax] are worked out on the value AFTER the discount, whichever
     * price the discount was measured against - so [taxable] + [tax] reconstructs
     * [salePrice] exactly; [discount] is kept only for a caller still expecting a
     * further deduction on top and is always (to rounding) zero.
     */
    data class ItemPricing(val taxable: Double, val tax: Double, val salePrice: Double) {
        val discount: Double get() = taxable + tax - salePrice
    }

    /**
     * Prices one line under Tax Settings' item-wise discount - a discount
     * pre-configured on the product's own rate row, applied directly against
     * [mrp], rather than the whole-bill discount entered on the cart page.
     *
     * Discount Position decides which price [value] is measured against, never
     * whether GST is charged before or after the discount - GST is always charged
     * on the discounted value, since that is what the customer is actually billed
     * for:
     *
     * - Pre-tax: [value] is a share of [mrp]'s own pre-tax base (its listed price
     *   with any tax it already includes stripped back out) - the discount comes
     *   off that base directly, then tax is charged on what's left.
     * - Post-tax: [value] is a share of the *listed, tax-inclusive* price - [mrp]
     *   itself when it is already inclusive, [mrp] plus its own tax when it is
     *   not - taken off that price, with tax then reported on whatever taxable
     *   value the discounted price itself works out to contain.
     *
     * A percentage discount on an inclusive price lands on the identical taxable
     * value either way, since scaling by a discount percentage and stripping a tax
     * percentage both commute - the two positions only diverge for a flat-amount
     * discount, or an exclusive price, where which price the amount is taken off
     * of actually matters.
     */
    fun priceItem(
        mrp: Double,
        rate: Double,
        inclusive: Boolean,
        preTax: Boolean,
        mode: DiscountMode,
        value: Double
    ): ItemPricing {
        if (preTax) {
            val base = taxableBase(mrp, rate, inclusive)
            val taxable = taxableValue(base, discountAmount(base, mode, value))
            val tax = taxAmount(taxable, rate)
            return ItemPricing(taxable, tax, taxable + tax)
        }
        val listedPrice = if (inclusive) mrp else mrp + taxAmount(mrp, rate)
        val salePrice = taxableValue(listedPrice, discountAmount(listedPrice, mode, value))
        val taxable = taxableBase(salePrice, rate, true)
        return ItemPricing(taxable, taxAmount(taxable, rate), salePrice)
    }

    /**
     * The item-wise discount [priceItem] applies, expressed instead as a plain
     * amount to subtract from [mrp]'s pre-tax base - the same shape as a bill-wise
     * line's `discount_amount`, so [BillDao] can price an item-wise line without a
     * second, parallel code path: `taxableValue(taxableBase(mrp, rate, inclusive),
     * this)` reproduces [priceItem]'s `salePrice` exactly, in all four cases.
     */
    fun itemDiscountAgainstRawBase(
        mrp: Double,
        rate: Double,
        inclusive: Boolean,
        preTax: Boolean,
        mode: DiscountMode,
        value: Double
    ): Double {
        val factor = 1.0 + rate / 100.0
        return when {
            preTax -> discountAmount(taxableBase(mrp, rate, inclusive), mode, value)
            inclusive -> discountAmount(mrp, mode, value) / factor
            else -> discountAmount(mrp + taxAmount(mrp, rate), mode, value) / factor
        }
    }
}
