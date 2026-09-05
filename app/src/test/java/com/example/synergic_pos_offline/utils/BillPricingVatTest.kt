package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a line is charged: whatever rate(s) it carries in the master, gated only by
 * whether tax is switched on at all - never by a store-wide GST-vs-VAT choice, which
 * does not exist any more (see [GstCalculator.regimeOf]). A GST-rated and a VAT-rated
 * product can sit on the same bill and each is charged its own tax.
 */
class BillPricingVatTest {

    private val delta = 0.005

    private fun price(
        rate: Double,
        cgst: Double = 0.0,
        sgst: Double = 0.0,
        vat: Double = 0.0,
        taxEnabled: Boolean = true,
        inclusive: Boolean = false
    ) = BillPricing.price(
        rate = rate, quantity = 1.0,
        cgstRate = cgst, sgstRate = sgst, vatRate = vat,
        discountAmount = 0.0, taxEnabled = taxEnabled, inclusive = inclusive, discountPreTax = true
    )

    @Test
    fun `a VAT-rated item is charged VAT`() {
        val line = price(rate = 240.0, vat = 5.0)
        assertEquals(12.0, line.vat, delta)
        assertEquals(0.0, line.cgst, delta)
        assertEquals(252.0, line.itemTotal, delta)
    }

    @Test
    fun `a GST-rated item is charged GST`() {
        val line = price(rate = 100.0, cgst = 2.5, sgst = 2.5)
        assertEquals(2.5, line.cgst, delta)
        assertEquals(2.5, line.sgst, delta)
        assertEquals(0.0, line.vat, delta)
        assertEquals(105.0, line.itemTotal, delta)
    }

    @Test
    fun `tax switched off charges nothing, whatever the product is rated`() {
        // Switching tax off is a deliberate choice to charge nothing - not an
        // invitation to read rates off the master anyway.
        val line = price(rate = 100.0, cgst = 2.5, sgst = 2.5, vat = 5.0, taxEnabled = false)
        assertEquals(0.0, line.cgst, delta)
        assertEquals(0.0, line.sgst, delta)
        assertEquals(0.0, line.vat, delta)
        assertEquals(100.0, line.itemTotal, delta)
    }

    @Test
    fun `an inclusive VAT price is stripped at its own rate`() {
        val line = price(rate = 252.0, vat = 5.0, inclusive = true)
        assertEquals(240.0, line.taxable, delta)
        assertEquals(12.0, line.vat, delta)
        assertEquals(252.0, line.itemTotal, delta)
    }

    @Test
    fun `an untaxed item is untouched`() {
        val line = price(rate = 50.0)
        assertEquals(0.0, line.cgst + line.sgst + line.vat, delta)
        assertEquals(50.0, line.itemTotal, delta)
    }

    /**
     * The bill this pins down: a ₹600 MRP (inclusive of 5% GST) with a post-tax 5%
     * discount. [itemTotal] - what the customer actually pays - is ₹570.00 either
     * way, but GST is charged on the DISCOUNTED value (₹542.86, not the full
     * ₹571.43 base): a post-tax discount only decides which price the 5% was taken
     * off of (the listed ₹600 here, rather than the ₹571.43 pre-tax base a pre-tax
     * discount would use), never whether tax is charged before or after it comes
     * off - see [GstCalculator.priceItem]'s own note on why the two positions land
     * on the same taxable value for a percentage discount on an inclusive price.
     */
    @Test
    fun `a post-tax discount on an inclusive price is exact, not a paisa short`() {
        val line = BillPricing.price(
            rate = 600.0, quantity = 1.0,
            cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0,
            // 5% of the ₹600 MRP, expressed against the pre-tax base - the shape
            // GstCalculator.itemDiscountAgainstRawBase stores it in.
            discountAmount = 30.0 / 1.05,
            taxEnabled = true, inclusive = true, discountPreTax = false
        )
        assertEquals(13.57, line.cgst, delta)
        assertEquals(13.57, line.sgst, delta)
        assertEquals(570.00, line.itemTotal, delta)
    }

    /**
     * The same bill, taken further: a bill-wise discount never touches a line's own
     * `discountAmount` - it comes off the bill's total once, at the end, not per
     * line - so this ₹600 line has [postTax] false and none of the fix above ever
     * runs for it. It printed AMOUNT 600.01 anyway, because `taxable + taxSum` has
     * the identical fault with no discount in sight: CGST and SGST are each rounded
     * to their own paisa (14.29 apiece) before being added, 0.86 paisa more than
     * ₹571.43 taxed at 5% taken as one figure - enough to carry a line with nothing
     * discounted off it past its own listed price.
     */
    @Test
    fun `an undiscounted inclusive line reconstructs its own listed price exactly`() {
        val line = BillPricing.price(
            rate = 600.0, quantity = 1.0,
            cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0,
            discountAmount = 0.0, taxEnabled = true, inclusive = true, discountPreTax = false
        )
        assertEquals(14.29, line.cgst, delta)
        assertEquals(14.29, line.sgst, delta)
        assertEquals(600.00, line.itemTotal, delta)
    }
}
