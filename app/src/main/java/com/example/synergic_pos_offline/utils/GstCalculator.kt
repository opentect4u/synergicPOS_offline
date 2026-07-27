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
     * Which tax a sale is charged under. GST and VAT are mutually exclusive in Tax
     * Settings - a store is registered under one or the other, never both - and
     * NONE when neither is switched on.
     */
    enum class TaxRegime { NONE, GST, VAT }

    /** Resolves the active regime from Tax Settings' two on/off switches. */
    fun regimeFor(gstEnabled: Boolean, vatEnabled: Boolean): TaxRegime = when {
        gstEnabled -> TaxRegime.GST
        vatEnabled -> TaxRegime.VAT
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
     * is worked in. [taxable]/[tax] are the GST-compliant reporting figures - for a
     * post-tax, exclusive discount these are worked out on the *full* pre-discount
     * price, so [discount] (their shortfall against [salePrice]) is the one case
     * where the discount is not already folded into them and still needs
     * subtracting separately to reach what the customer actually pays.
     */
    data class ItemPricing(val taxable: Double, val tax: Double, val salePrice: Double) {
        val discount: Double get() = taxable + tax - salePrice
    }

    /**
     * Prices one line under Tax Settings' item-wise discount - a discount
     * pre-configured on the product's own rate row, applied directly against
     * [mrp], rather than the whole-bill discount entered on the cart page. Which of
     * the four rules below applies is fixed by two independent Tax Settings
     * choices: Discount Position (pre/post-tax) and whether the active tax is
     * Inclusive or Exclusive of the listed price.
     *
     * - Pre-tax, Exclusive: the discount comes off [mrp] itself, then tax is added
     *   on what's left.
     * - Pre-tax, Inclusive: [mrp] is stripped down to its pre-tax base first, the
     *   discount comes off that base, then it is re-taxed.
     * - Post-tax, Inclusive: the discount comes off [mrp] directly - it already
     *   includes tax, so nothing more needs adding.
     * - Post-tax, Exclusive: [mrp] is taxed up first, then the discount comes off
     *   that taxed price.
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
        if (inclusive) {
            val salePrice = taxableValue(mrp, discountAmount(mrp, mode, value))
            val taxable = taxableBase(salePrice, rate, true)
            return ItemPricing(taxable, salePrice - taxable, salePrice)
        }
        val taxedPrice = mrp + taxAmount(mrp, rate)
        val salePrice = taxableValue(taxedPrice, discountAmount(taxedPrice, mode, value))
        return ItemPricing(mrp, taxAmount(mrp, rate), salePrice)
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
