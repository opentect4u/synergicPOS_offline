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
