package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.database.ChargeDao
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shop's own extra charges (Service, Packing, Delivery, Parcel Charge) are
 * taxable too, at whatever GST/VAT rate(s) the goods on the bill carry - not a
 * rate of their own. These pin down [CartMath.totals]' own share of that: the
 * charge principal spread across lines by gross share, taxed at each line's own
 * rate, folded into cgst/sgst/vat without ever taxing (or discounting) the
 * charge's principal itself twice.
 */
class CartMathTest {

    private val delta = 0.0001

    private fun cfg(
        taxEnabled: Boolean = true,
        discountPreTax: Boolean = true,
        itemwiseDiscount: Boolean = false,
        billwiseDiscount: Boolean = false
    ) = CartMath.Config(
        taxEnabled = taxEnabled,
        inclusive = false,
        discountPreTax = discountPreTax,
        itemwiseDiscount = itemwiseDiscount,
        billwiseDiscount = billwiseDiscount
    )

    private fun charge(amount: Double) = ChargeDao.Applied(
        name = "Parcel Charge", value = amount, type = ChargeDao.Type.AMOUNT, amount = amount
    )

    @Test
    fun `a single-rate cart taxes the charge at exactly that rate`() {
        // 100 + 200 = 300 subtotal, both lines 2.5% CGST + 2.5% SGST.
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5),
            CartMath.Line(qty = 1.0, rate = 200.0, cgstRate = 2.5, sgstRate = 2.5)
        )
        // Goods tax: 300 * 5% = 15.00 (7.50 CGST + 7.50 SGST).
        // Charge: 30.00 principal, taxed at the same 5% = 1.50 (0.75 + 0.75).
        val totals = CartMath.totals(
            lines, cfg(), GstCalculator.DiscountMode.PERCENT, 0.0,
            charges = listOf(charge(30.0))
        )
        assertEquals(8.25, totals.cgst, delta)
        assertEquals(8.25, totals.sgst, delta)
        assertEquals(16.5, totals.tax, delta)
    }

    @Test
    fun `each line's charge share is taxed at its OWN rate, not a blended one`() {
        // 100 at 5% (2.5+2.5), 300 at 12% (6+6) - unequal shares AND unequal
        // rates, so a bug that applies one line's rate to the whole charge, or
        // averages the two, lands on a different figure than taxing each line's
        // own share at its own rate.
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5),
            CartMath.Line(qty = 1.0, rate = 300.0, cgstRate = 6.0, sgstRate = 6.0)
        )
        // Charge: 40.00 (10% of 400). Line 1's share (10.00) taxed at 5% = 0.50;
        // line 2's share (30.00) taxed at 12% = 3.60. Charge tax = 4.10.
        // Goods tax: 100*5% + 300*12% = 5.00 + 36.00 = 41.00.
        // Total tax = 45.10, split evenly CGST/SGST since every rate here is.
        val totals = CartMath.totals(
            lines, cfg(), GstCalculator.DiscountMode.PERCENT, 0.0,
            charges = listOf(charge(40.0))
        )
        assertEquals(22.55, totals.cgst, delta)
        assertEquals(22.55, totals.sgst, delta)
    }

    @Test
    fun `tax switched off charges nothing on the charge either`() {
        // Rates left on the line even though the till charges nothing - a stale
        // master figure, not a live one - must not leak into a charge's tax.
        val lines = listOf(CartMath.Line(qty = 1.0, rate = 100.0, cgstRate = 5.0, sgstRate = 5.0))
        val totals = CartMath.totals(
            lines, cfg(taxEnabled = false), GstCalculator.DiscountMode.PERCENT, 0.0,
            charges = listOf(charge(10.0))
        )
        assertEquals(0.0, totals.cgst, delta)
        assertEquals(0.0, totals.sgst, delta)
        assertEquals(110.0, totals.total, delta)
    }

    @Test
    fun `a cart mixing a GST line and a VAT line taxes each at its own regime`() {
        // 100 GST-rated at 5% (2.5+2.5), 200 VAT-rated at 10% - one bill carrying
        // a product under each tax, which the old store-wide regime could never
        // represent: it would have zeroed whichever rate didn't match the till.
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5),
            CartMath.Line(qty = 1.0, rate = 200.0, vatRate = 10.0)
        )
        // Line 1: 100 * 5% = 5.00 (2.50 CGST + 2.50 SGST). Line 2: 200 * 10% = 20.00 VAT.
        val totals = CartMath.totals(lines, cfg(), GstCalculator.DiscountMode.PERCENT, 0.0)
        assertEquals(2.5, totals.cgst, delta)
        assertEquals(2.5, totals.sgst, delta)
        assertEquals(20.0, totals.vat, delta)
        assertEquals(325.0, totals.total, delta)
    }

    @Test
    fun `the same mixed cart charges nothing with tax switched off`() {
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5),
            CartMath.Line(qty = 1.0, rate = 200.0, vatRate = 10.0)
        )
        val totals = CartMath.totals(lines, cfg(taxEnabled = false), GstCalculator.DiscountMode.PERCENT, 0.0)
        assertEquals(0.0, totals.cgst, delta)
        assertEquals(0.0, totals.sgst, delta)
        assertEquals(0.0, totals.vat, delta)
        assertEquals(300.0, totals.total, delta)
    }

    @Test
    fun `the charge's principal is added exactly once, never taxed twice into itself`() {
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5),
            CartMath.Line(qty = 1.0, rate = 200.0, cgstRate = 2.5, sgstRate = 2.5)
        )
        val totals = CartMath.totals(
            lines, cfg(), GstCalculator.DiscountMode.PERCENT, 0.0,
            charges = listOf(charge(30.0))
        )
        // chargesTotal stays the bare, untaxed figure handed in...
        assertEquals(30.0, totals.chargesTotal, delta)
        // ...and total is built from exactly goods + service + chargesTotal, with
        // the charge's TAX already inside goods (via the inflated cgst/sgst) -
        // not a second time via chargesTotal itself.
        assertEquals(totals.goods + totals.service + totals.chargesTotal, totals.total, delta)
        assertEquals(346.5, totals.total, delta)
    }

    @Test
    fun `a bill-wise discount is unaffected by a charge on the same bill`() {
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5),
            CartMath.Line(qty = 1.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5)
        )
        val c = cfg(discountPreTax = false, billwiseDiscount = true)
        // discountBase/billDiscount take no charges parameter at all - a discount
        // never discounts the shop's own charge - so the amount they work out has
        // to come out the same whether or not a charge is also on the bill.
        val withoutCharge = CartMath.totals(lines, c, GstCalculator.DiscountMode.PERCENT, 10.0)
        val withCharge = CartMath.totals(
            lines, c, GstCalculator.DiscountMode.PERCENT, 10.0, charges = listOf(charge(15.0))
        )
        assertEquals(21.0, withoutCharge.discount, delta)
        assertEquals(withoutCharge.discount, withCharge.discount, delta)
    }
}
