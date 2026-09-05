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
     * Works [discountAmount] - always expressed against the line's raw pre-tax base,
     * whichever price it was actually configured against (see
     * [GstCalculator.itemDiscountAgainstRawBase]) - into the line under whether tax
     * is switched on.
     *
     * GST is always charged on the value AFTER the discount: [discountPreTax] is not
     * read here at all - it only decided, upstream, which price [discountAmount] was
     * measured against before being converted to this raw-base shape (see
     * [GstCalculator.priceItem]'s own note on why that is the only thing Discount
     * Position changes). Whichever position it came from, taking it off the same
     * line's own pre-tax base always reconstructs the correct taxable value.
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
        taxEnabled: Boolean,
        inclusive: Boolean,
        @Suppress("UNUSED_PARAMETER") discountPreTax: Boolean
    ): Line {
        val subtotal = rate * quantity
        // Which taxes this line carries is the *line's* business, not the till's -
        // GST and VAT are a fact about the product (see GstCalculator.regimeOf),
        // never a store-wide choice. So every rate the line actually has is
        // charged. NONE still means none: switching tax off is a deliberate choice
        // to charge nothing, not an invitation to read rates off the master.
        val taxed = taxEnabled
        val combinedRate = if (taxed) cgstRate + sgstRate + vatRate else 0.0
        // The listed price is stripped of any tax it already includes to reach the
        // base the rate works on.
        val rawBase = GstCalculator.taxableBase(subtotal, combinedRate, inclusive)
        val taxable = BillRounding.toPaise(GstCalculator.taxableValue(rawBase, discountAmount))
        val cgst = BillRounding.toPaise(if (taxed) GstCalculator.taxAmount(taxable, cgstRate) else 0.0)
        val sgst = BillRounding.toPaise(if (taxed) GstCalculator.taxAmount(taxable, sgstRate) else 0.0)
        val vat = BillRounding.toPaise(if (taxed) GstCalculator.taxAmount(taxable, vatRate) else 0.0)
        // NOT taxable + cgst + sgst + vat: CGST and SGST are each already rounded to
        // their own paisa apart, and their sum can land a paisa off the tax on the
        // taxable value taken as one figure - a discrepancy this line's total must
        // not inherit. A ₹600 inclusive MRP taxed at the combined rate ONCE and
        // rounded once comes back to exactly ₹600.00; taxable plus CGST and SGST
        // each already rounded to their own paisa apart landed on ₹600.01 instead.
        val itemTotal = BillRounding.toPaise(taxable * (1.0 + combinedRate / 100.0))
        return Line(BillRounding.toPaise(subtotal), taxable, cgst, sgst, vat, itemTotal)
    }
}
