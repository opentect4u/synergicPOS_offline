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
}
