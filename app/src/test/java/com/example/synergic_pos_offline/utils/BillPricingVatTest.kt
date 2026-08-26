package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.utils.GstCalculator.TaxRegime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a line is charged when its own rates disagree with the till's regime.
 *
 * The rule these pin down: a product is taxed at the rates it carries in the master,
 * whichever tax the shop is set up for. The regime used to decide it, which meant a
 * VAT-rated product sold on a GST till had its rate read, written to the bill, and
 * then multiplied by nothing - the tax silently vanished.
 */
class BillPricingVatTest {

    private val delta = 0.005

    private fun price(
        rate: Double,
        cgst: Double = 0.0,
        sgst: Double = 0.0,
        vat: Double = 0.0,
        regime: TaxRegime,
        inclusive: Boolean = false
    ) = BillPricing.price(
        rate = rate, quantity = 1.0,
        cgstRate = cgst, sgstRate = sgst, vatRate = vat,
        discountAmount = 0.0, regime = regime, inclusive = inclusive, discountPreTax = true
    )

    @Test
    fun `a VAT-rated item is charged VAT on a GST till`() {
        // The case in the report: GST is on, the product carries VAT in the master,
        // and the VAT has to be calculated rather than dropped.
        val line = price(rate = 240.0, vat = 5.0, regime = TaxRegime.GST)
        assertEquals(12.0, line.vat, delta)
        assertEquals(0.0, line.cgst, delta)
        assertEquals(252.0, line.itemTotal, delta)
    }

    @Test
    fun `a GST-rated item is charged GST on a VAT till`() {
        // The other way round, which was broken the same way.
        val line = price(rate = 100.0, cgst = 2.5, sgst = 2.5, regime = TaxRegime.VAT)
        assertEquals(2.5, line.cgst, delta)
        assertEquals(2.5, line.sgst, delta)
        assertEquals(0.0, line.vat, delta)
        assertEquals(105.0, line.itemTotal, delta)
    }

    @Test
    fun `a GST-rated item on a GST till is unchanged`() {
        // The ordinary case, which must not move.
        val line = price(rate = 100.0, cgst = 2.5, sgst = 2.5, regime = TaxRegime.GST)
        assertEquals(2.5, line.cgst, delta)
        assertEquals(2.5, line.sgst, delta)
        assertEquals(105.0, line.itemTotal, delta)
    }

    @Test
    fun `a VAT-rated item on a VAT till is unchanged`() {
        val line = price(rate = 240.0, vat = 5.0, regime = TaxRegime.VAT)
        assertEquals(12.0, line.vat, delta)
        assertEquals(252.0, line.itemTotal, delta)
    }

    @Test
    fun `both taxes off charges nothing, whatever the product is rated`() {
        // Switching both off is a deliberate choice to charge no tax - not an
        // invitation to read rates off the master anyway.
        val line = price(rate = 100.0, cgst = 2.5, sgst = 2.5, vat = 5.0, regime = TaxRegime.NONE)
        assertEquals(0.0, line.cgst, delta)
        assertEquals(0.0, line.sgst, delta)
        assertEquals(0.0, line.vat, delta)
        assertEquals(100.0, line.itemTotal, delta)
    }

    @Test
    fun `an inclusive VAT price on a GST till is stripped at its own rate`() {
        // The base has to come off the rate the line actually carries. Stripping at
        // the regime's rate - zero - would have treated the whole 252 as taxable and
        // then charged VAT on top of a price that already included it.
        val line = price(rate = 252.0, vat = 5.0, regime = TaxRegime.GST, inclusive = true)
        assertEquals(240.0, line.taxable, delta)
        assertEquals(12.0, line.vat, delta)
        assertEquals(252.0, line.itemTotal, delta)
    }

    @Test
    fun `an untaxed item is untouched`() {
        val line = price(rate = 50.0, regime = TaxRegime.GST)
        assertEquals(0.0, line.cgst + line.sgst + line.vat, delta)
        assertEquals(50.0, line.itemTotal, delta)
    }
}
