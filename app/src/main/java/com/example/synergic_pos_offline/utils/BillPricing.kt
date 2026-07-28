package com.example.synergic_pos_offline.utils

/**
 * Prices one bill line the single way the app prices one, so a line costs the same
 * whether it is being written to `td_bill_items` or previewed on the checkout screen
 * before the sale is completed.
 *
 * Split out of [com.example.synergic_pos_offline.database.BillDao] for that second
 * caller: a preview that priced lines its own way would drift from the bill it is
 * supposed to be showing, which is the whole point of a preview.
 */
object BillPricing {

    /**
     * A priced line, in the shape `td_bill_items` stores it. [subtotal] is the gross
     * (quantity x listed rate), [taxable] the value tax was charged on, and
     * [itemTotal] what the line comes to with that tax on and its discount off.
     */
    data class Line(
        val subtotal: Double,
        val taxable: Double,
        val cgst: Double,
        val sgst: Double,
        val vat: Double,
        val itemTotal: Double
    )

    /**
     * Works [discountAmount] - always expressed against the line's raw pre-tax base -
     * into the line under the active regime.
     *
     * A post-tax discount (inclusive or exclusive) is the case where tax is NOT
     * charged on the discounted value: the rate is applied to the full base and the
     * discount then comes off the taxed price, so the tax reported matches the full
     * MRP (a "cash discount" that does not reduce the taxable value). A pre-tax
     * discount instead comes off the base first and tax is worked out on what remains.
     *
     * Every figure is returned to the paisa it is reported at, so a bill's lines add
     * up to the total stored against them - see [BillRounding.toPaise].
     */
    fun price(
        rate: Double,
        quantity: Double,
        cgstRate: Double,
        sgstRate: Double,
        vatRate: Double,
        discountAmount: Double,
        regime: GstCalculator.TaxRegime,
        inclusive: Boolean,
        discountPreTax: Boolean
    ): Line {
        val subtotal = rate * quantity
        val combinedRate = when (regime) {
            GstCalculator.TaxRegime.GST -> cgstRate + sgstRate
            GstCalculator.TaxRegime.VAT -> vatRate
            GstCalculator.TaxRegime.NONE -> 0.0
        }
        // The listed price is stripped of any tax it already includes to reach the
        // base the rate works on.
        val rawBase = GstCalculator.taxableBase(subtotal, combinedRate, inclusive)
        val postTax = !discountPreTax && discountAmount > 0.0
        val taxable = BillRounding.toPaise(
            if (postTax) rawBase else GstCalculator.taxableValue(rawBase, discountAmount)
        )
        val cgst = BillRounding.toPaise(
            if (regime == GstCalculator.TaxRegime.GST) GstCalculator.taxAmount(taxable, cgstRate) else 0.0
        )
        val sgst = BillRounding.toPaise(
            if (regime == GstCalculator.TaxRegime.GST) GstCalculator.taxAmount(taxable, sgstRate) else 0.0
        )
        val vat = BillRounding.toPaise(
            if (regime == GstCalculator.TaxRegime.VAT) GstCalculator.taxAmount(taxable, vatRate) else 0.0
        )
        val taxSum = cgst + sgst + vat
        // discountAmount is a pre-tax-base amount; grossed up by the rate it is the
        // discount off the taxed price the customer actually gets. (rawBase + taxSum
        // is the full taxed MRP - the listed price for an inclusive line, MRP + tax
        // for an exclusive one.)
        val itemTotal = BillRounding.toPaise(
            if (postTax) {
                (rawBase + taxSum - discountAmount * (1.0 + combinedRate / 100.0)).coerceAtLeast(0.0)
            } else {
                taxable + taxSum
            }
        )
        return Line(BillRounding.toPaise(subtotal), taxable, cgst, sgst, vat, itemTotal)
    }
}
