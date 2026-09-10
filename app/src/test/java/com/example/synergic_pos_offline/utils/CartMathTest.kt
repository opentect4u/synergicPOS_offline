package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.database.ChargeDao
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shop's own extra charges (Service, Packing, Delivery, Parcel Charge) carry
 * no tax of their own, whatever GST/VAT rate(s) the goods on the bill carry -
 * they join the bill once, at their own flat value, and nothing is worked out
 * against them. These pin down [CartMath.totals]' own share of that: cgst/sgst/vat
 * are the goods' tax alone, and the charge principal is added to the total
 * untouched - never taxed, never discounted, never counted twice.
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
    fun `a charge on a single-rate cart adds nothing to cgst or sgst`() {
        // 100 + 200 = 300 subtotal, both lines 2.5% CGST + 2.5% SGST.
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5),
            CartMath.Line(qty = 1.0, rate = 200.0, cgstRate = 2.5, sgstRate = 2.5)
        )
        // Goods tax: 300 * 5% = 15.00 (7.50 CGST + 7.50 SGST). The 30.00 charge on
        // top of it contributes nothing further - a 40% charge would read the same.
        val totals = CartMath.totals(
            lines, cfg(), GstCalculator.DiscountMode.PERCENT, 0.0,
            charges = listOf(charge(30.0))
        )
        assertEquals(7.5, totals.cgst, delta)
        assertEquals(7.5, totals.sgst, delta)
        assertEquals(15.0, totals.tax, delta)
    }

    @Test
    fun `a charge on a cart mixing rates still adds nothing to cgst or sgst`() {
        // 100 at 5% (2.5+2.5), 300 at 12% (6+6) - unequal shares AND unequal
        // rates, so a regression that starts spreading the charge across lines
        // again (by any rate, blended or not) would move this away from the
        // goods-only figure.
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5),
            CartMath.Line(qty = 1.0, rate = 300.0, cgstRate = 6.0, sgstRate = 6.0)
        )
        // Goods tax only: 100*5% + 300*12% = 5.00 + 36.00 = 41.00, split evenly
        // CGST/SGST since every rate here is. The 40.00 charge adds nothing to it.
        val totals = CartMath.totals(
            lines, cfg(), GstCalculator.DiscountMode.PERCENT, 0.0,
            charges = listOf(charge(40.0))
        )
        assertEquals(20.5, totals.cgst, delta)
        assertEquals(20.5, totals.sgst, delta)
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

    /**
     * IGST - the inter-state shape of GST - is charged instead of CGST/SGST on the
     * line that carries it, never alongside it, the same way a VAT-rated line sits
     * beside a GST-rated one above.
     */
    @Test
    fun `a cart mixing a CGST-SGST line and an IGST line taxes each at its own rate`() {
        // 100 intra-state at 5% (2.5+2.5), 200 inter-state at 18% IGST.
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5),
            CartMath.Line(qty = 1.0, rate = 200.0, igstRate = 18.0)
        )
        // Line 1: 100 * 5% = 5.00 (2.50 CGST + 2.50 SGST). Line 2: 200 * 18% = 36.00 IGST.
        val totals = CartMath.totals(lines, cfg(), GstCalculator.DiscountMode.PERCENT, 0.0)
        assertEquals(2.5, totals.cgst, delta)
        assertEquals(2.5, totals.sgst, delta)
        assertEquals(0.0, totals.vat, delta)
        assertEquals(36.0, totals.igst, delta)
        assertEquals(41.0, totals.tax, delta)
        assertEquals(341.0, totals.total, delta)
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
    fun `the charge's principal is added exactly once, and never taxed`() {
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
        // goods carrying the goods' own tax alone - no charge tax anywhere to
        // land in it, and none added again via chargesTotal either.
        assertEquals(totals.goods + totals.service + totals.chargesTotal, totals.total, delta)
        assertEquals(345.0, totals.total, delta)
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

    /**
     * A ₹600 + ₹200 exclusive cart, 5% GST, 5% bill-wise PRE-tax: the discount is a
     * share of the raw ₹800 the two lines list for (₹40.00), taken off before tax -
     * so GST is charged on the remaining ₹760.00 (₹19.00 CGST + ₹19.00 SGST), not
     * on the ₹840 those lines come to once taxed (which is what a POST-tax bill-
     * wise discount - a different, already-correct calculation - is a share of).
     */
    @Test
    fun `a bill-wise pre-tax discount on an exclusive cart is a share of the raw subtotal`() {
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 600.0, cgstRate = 2.5, sgstRate = 2.5),
            CartMath.Line(qty = 1.0, rate = 200.0, cgstRate = 2.5, sgstRate = 2.5)
        )
        val c = cfg(discountPreTax = true, billwiseDiscount = true)
        val totals = CartMath.totals(lines, c, GstCalculator.DiscountMode.PERCENT, 5.0)
        assertEquals(40.0, totals.discount, delta)
        assertEquals(19.0, totals.cgst, delta)
        assertEquals(19.0, totals.sgst, delta)
        assertEquals(798.0, totals.total, delta)
    }

    /**
     * A ₹1,180 INCLUSIVE (MRP) line, 18% GST (9% CGST + 9% SGST), 20% bill-wise
     * PRE-tax: the discount is a share of the ₹1,000 base that MRP strips down to
     * (₹200.00), not the ₹1,180 on the shelf (which would give ₹236.00) - so GST is
     * re-charged on the discounted ₹800.00 base (₹72.00 CGST + ₹72.00 SGST) for a
     * final ₹944.00, exactly the textbook MRP pre-tax-discount worked example: MRP
     * ÷ 1.18 = base 1,000 → 20% off = 800 → +18% GST = 944.
     */
    @Test
    fun `a bill-wise pre-tax discount on an inclusive cart is a share of the stripped base`() {
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 1180.0, cgstRate = 9.0, sgstRate = 9.0)
        )
        val c = CartMath.Config(
            taxEnabled = true, inclusive = true, discountPreTax = true,
            itemwiseDiscount = false, billwiseDiscount = true
        )
        val totals = CartMath.totals(lines, c, GstCalculator.DiscountMode.PERCENT, 20.0)
        assertEquals(200.0, totals.discount, delta)
        assertEquals(72.0, totals.cgst, delta)
        assertEquals(72.0, totals.sgst, delta)
        assertEquals(944.0, totals.total, delta)
    }

    /**
     * A ₹600 + ₹200 INCLUSIVE (MRP) cart, 5% GST, 5% bill-wise POST-tax: GST is
     * charged on the discounted value - the same rule an item-wise post-tax
     * discount already follows - so each line's own CGST comes to 13.57 and 4.52
     * (₹18.09 summed, each line already rounded to its own paisa - the same
     * summing convention every other CGST/SGST total already uses), not the
     * ₹19.05+₹19.05 = ₹38.10 combined tax that charging on the full, undiscounted
     * ₹800 gives. The bill-wise discount total itself is unaffected - still
     * ₹40.00, since MRP pricing already collapses pre/post-tax to the same figure
     * (see [CartMath.Totals.discount]'s own doc).
     */
    @Test
    fun `a bill-wise post-tax discount on an inclusive cart is charged on the discounted value`() {
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 600.0, cgstRate = 2.5, sgstRate = 2.5),
            CartMath.Line(qty = 1.0, rate = 200.0, cgstRate = 2.5, sgstRate = 2.5)
        )
        val c = CartMath.Config(
            taxEnabled = true, inclusive = true, discountPreTax = false,
            itemwiseDiscount = false, billwiseDiscount = true
        )
        val totals = CartMath.totals(lines, c, GstCalculator.DiscountMode.PERCENT, 5.0)
        assertEquals(40.0, totals.discount, delta)
        assertEquals(18.09, totals.cgst, delta)
        assertEquals(18.09, totals.sgst, delta)
        assertEquals(760.0, totals.total, delta)
    }

    /**
     * The same ₹600 + ₹200 INCLUSIVE cart, 10% bill-wise POST-tax discount, plus a
     * ₹40 extra charge - its principal joins the total once, untouched, at exactly
     * what it was configured as, the same as under every other tax setting (see
     * the class doc).
     */
    @Test
    fun `a charge is not taxed on an inclusive post-tax bill`() {
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 600.0, cgstRate = 2.5, sgstRate = 2.5),
            CartMath.Line(qty = 1.0, rate = 200.0, cgstRate = 2.5, sgstRate = 2.5)
        )
        val c = CartMath.Config(
            taxEnabled = true, inclusive = true, discountPreTax = false,
            itemwiseDiscount = false, billwiseDiscount = true
        )
        val totals = CartMath.totals(
            lines, c, GstCalculator.DiscountMode.PERCENT, 10.0, charges = listOf(charge(40.0))
        )
        assertEquals(40.0, totals.chargesTotal, delta)
        assertEquals(760.0, totals.total, delta)
    }

    /**
     * The cart this pins down: a ₹200 line and a ₹600 line, both 5% off item-wise,
     * inclusive of 5% GST, post-tax - showed "Discount (item-wise)" as -₹40.01
     * instead of -₹40.00 on screen.
     *
     * The cause: [CartMath.Totals.discount] used to be worked out as
     * `taxable + cgst + sgst + vat - itemTotal` - CGST and SGST each rounded to
     * their own paisa before being added back, which can land a paisa off that same
     * line's own itemTotal (the identical fault [BillPricing.price]'s own note
     * fixed itemTotal against). One line absorbed the excess and the aggregate read
     * a paisa short of the ₹30 + ₹10 the two lines were actually discounted by.
     */
    @Test
    fun `an item-wise post-tax discount matches to the paisa across the whole cart`() {
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 200.0, cgstRate = 2.5, sgstRate = 2.5, discValue = 5.0, discType = "P"),
            CartMath.Line(qty = 1.0, rate = 600.0, cgstRate = 2.5, sgstRate = 2.5, discValue = 5.0, discType = "P")
        )
        val c = CartMath.Config(
            taxEnabled = true, inclusive = true, discountPreTax = false,
            itemwiseDiscount = true, billwiseDiscount = false
        )
        val totals = CartMath.totals(lines, c, GstCalculator.DiscountMode.PERCENT, 0.0)
        assertEquals(40.0, totals.discount, delta)
        assertEquals(760.0, totals.total, delta)
    }

    /**
     * The same ₹200/₹600 item-wise 5% cart, EXCLUSIVE PRE-TAX this time, printed
     * ₹42.00 as DISCOUNT - the operator configured 5% off each product's own
     * ₹200/₹600 rate as typed in (₹10 + ₹30 = ₹40.00, exactly what the DISC column
     * beside each line already printed), not 5% off a line grossed up by its own
     * tax on top of the discount. Pre-tax has nothing to do with a taxed price at
     * all - see [CartMath.Totals.discount]'s own doc for why POST-tax exclusive is
     * different (the next test).
     */
    @Test
    fun `an item-wise pre-tax discount on an exclusive cart is not grossed up by its own tax`() {
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 200.0, cgstRate = 2.5, sgstRate = 2.5, discValue = 5.0, discType = "P"),
            CartMath.Line(qty = 1.0, rate = 600.0, cgstRate = 2.5, sgstRate = 2.5, discValue = 5.0, discType = "P")
        )
        val c = CartMath.Config(
            taxEnabled = true, inclusive = false, discountPreTax = true,
            itemwiseDiscount = true, billwiseDiscount = false
        )
        val totals = CartMath.totals(lines, c, GstCalculator.DiscountMode.PERCENT, 0.0)
        assertEquals(40.0, totals.discount, delta)
    }

    /**
     * The same cart, EXCLUSIVE POST-TAX: "post-tax" means the discount comes off a
     * price that already has tax figured into it, so 5% off ₹200/₹600 is measured
     * against ₹210/₹630 (each rate plus its own 5% GST) - ₹10.50 + ₹31.50 = ₹42.00,
     * not the ₹40.00 a pre-tax discount on the same cart comes to.
     */
    @Test
    fun `an item-wise post-tax discount on an exclusive cart is measured against the taxed price`() {
        val lines = listOf(
            CartMath.Line(qty = 1.0, rate = 200.0, cgstRate = 2.5, sgstRate = 2.5, discValue = 5.0, discType = "P"),
            CartMath.Line(qty = 1.0, rate = 600.0, cgstRate = 2.5, sgstRate = 2.5, discValue = 5.0, discType = "P")
        )
        val c = CartMath.Config(
            taxEnabled = true, inclusive = false, discountPreTax = false,
            itemwiseDiscount = true, billwiseDiscount = false
        )
        val totals = CartMath.totals(lines, c, GstCalculator.DiscountMode.PERCENT, 0.0)
        assertEquals(42.0, totals.discount, delta)
    }
}
