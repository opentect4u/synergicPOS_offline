package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class GstCalculatorTest {

    private val delta = 0.0001

    /** PROD1 in the product master: rate 15.00, CGST 5%, SGST 10%. */
    @Test
    fun `charges each side at its own rate rather than halving a combined slab`() {
        val taxable = GstCalculator.taxableValue(price = 15.0, qty = 5, discountPct = 0)
        assertEquals(75.0, taxable, delta)

        val cgst = GstCalculator.taxAmount(taxable, 5.0)
        val sgst = GstCalculator.taxAmount(taxable, 10.0)

        assertEquals(3.75, cgst, delta)
        assertEquals(7.50, sgst, delta)
        assertEquals(11.25, cgst + sgst, delta)
        assertEquals(86.25, taxable + cgst + sgst, delta)
    }

    @Test
    fun `taxes the discounted value, not the gross`() {
        // 100.00 gross, 10% off -> GST applies to 90.00.
        val taxable = GstCalculator.taxableValue(price = 50.0, qty = 2, discountPct = 10)
        assertEquals(90.0, taxable, delta)
        assertEquals(4.5, GstCalculator.taxAmount(taxable, 5.0), delta)
    }

    @Test
    fun `accepts a discount already expressed as an amount`() {
        assertEquals(90.0, GstCalculator.taxableValue(gross = 100.0, discountAmount = 10.0), delta)
    }

    @Test
    fun `never returns a negative taxable value`() {
        assertEquals(0.0, GstCalculator.taxableValue(gross = 20.0, discountAmount = 50.0), delta)
        assertEquals(0.0, GstCalculator.taxableValue(price = 10.0, qty = 1, discountPct = 100), delta)
    }

    @Test
    fun `a zero-rated product attracts no tax`() {
        val taxable = GstCalculator.taxableValue(price = 15.0, qty = 5, discountPct = 0)
        assertEquals(0.0, GstCalculator.taxAmount(taxable, 0.0), delta)
    }

    @Test
    fun `discount percentage is clamped to a sane range`() {
        assertEquals(100.0, GstCalculator.taxableValue(price = 100.0, qty = 1, discountPct = -5), delta)
        assertEquals(0.0, GstCalculator.taxableValue(price = 100.0, qty = 1, discountPct = 150), delta)
    }

    /**
     * Pins the grocery till's own bill-wise pre-tax MRP pipeline - the exact sequence
     * [PosCheckoutFragment.discountBase]/[PosBillingFragment.discountBase] and
     * [PosCheckoutFragment.lineTaxRaw]/[PosBillingFragment.lineTaxRaw] call, not
     * [CartMath] - to the same textbook worked example [CartMathTest] pins for the
     * restaurant: a ₹1,180 MRP line, 18% GST, 20% bill-wise pre-tax discount.
     *
     * Base = 1,180 / 1.18 = 1,000; discount = 20% of 1,000 = 200; discounted base =
     * 800; GST re-added on that = 144; final = 944 - not 236 off the ₹1,180 itself,
     * which is what the base used to be measured against before this was fixed.
     */
    @Test
    fun `grocery's own bill-wise pre-tax pipeline strips MRP to its base before discounting`() {
        val mrp = 1180.0
        val rate = 18.0 // 9% CGST + 9% SGST, as the product master would carry it

        // discountBase(): the base a pre-tax % is taken of, under MRP.
        val discountBase = GstCalculator.taxableBase(mrp, rate, inclusive = true)
        assertEquals(1000.0, discountBase, delta)

        // discountAmt(): 20% of that base.
        val discountAmt = GstCalculator.discountAmount(discountBase, GstCalculator.DiscountMode.PERCENT, 20.0)
        assertEquals(200.0, discountAmt, delta)

        // lineTaxRaw(): the line's own raw base, less its share of the whole-bill
        // discount (the whole of it, for a single-line bill) - GST is then charged
        // on what's left.
        val rawBase = GstCalculator.taxableBase(mrp, rate, inclusive = true)
        val taxable = GstCalculator.taxableValueSpread(rawBase, gross = mrp, grossSubtotal = mrp, discountAmount = discountAmt)
        assertEquals(800.0, taxable, delta)

        val cgst = GstCalculator.taxAmount(taxable, 9.0)
        val sgst = GstCalculator.taxAmount(taxable, 9.0)
        assertEquals(72.0, cgst, delta)
        assertEquals(72.0, sgst, delta)
        assertEquals(944.0, taxable + cgst + sgst, delta)
    }

    /**
     * IGST - the inter-state shape of GST - classifies as GST the same way CGST/SGST
     * does, so a purely inter-state product (no CGST/SGST on it at all) still reads
     * as GST rather than falling through to NONE.
     */
    @Test
    fun `regimeOf classifies a pure-IGST product as GST`() {
        assertEquals(GstCalculator.TaxRegime.GST, GstCalculator.regimeOf(0.0, 0.0, 0.0, igstRate = 18.0))
        // Still NONE when nothing at all is set, IGST included.
        assertEquals(GstCalculator.TaxRegime.NONE, GstCalculator.regimeOf(0.0, 0.0, 0.0, igstRate = 0.0))
        // Unaffected when a caller passes no IGST at all - the default keeps every
        // existing CGST/SGST/VAT-only call site reading exactly as it did.
        assertEquals(GstCalculator.TaxRegime.GST, GstCalculator.regimeOf(9.0, 9.0, 0.0))
        assertEquals(GstCalculator.TaxRegime.VAT, GstCalculator.regimeOf(0.0, 0.0, 12.5))
    }

    /** A mixed cart must not be flattened to one blended rate. */
    @Test
    fun `sums a cart whose products carry different rates`() {
        data class L(val price: Double, val qty: Int, val cgst: Double, val sgst: Double)
        val cart = listOf(
            L(15.0, 5, 5.0, 10.0),   // 75.00 -> 3.75 + 7.50
            L(100.0, 1, 9.0, 9.0),   // 100.00 -> 9.00 + 9.00
            L(50.0, 2, 0.0, 0.0)     // 100.00 -> exempt
        )
        val tax = cart.sumOf {
            val t = GstCalculator.taxableValue(it.price, it.qty, 0)
            GstCalculator.taxAmount(t, it.cgst) + GstCalculator.taxAmount(t, it.sgst)
        }
        assertEquals(29.25, tax, delta)
    }
}
