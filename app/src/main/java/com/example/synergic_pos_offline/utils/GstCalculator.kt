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
}
