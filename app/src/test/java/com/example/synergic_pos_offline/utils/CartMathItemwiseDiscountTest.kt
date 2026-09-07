package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Item-wise discount: a line that was not discounted carries no discount.
 *
 * From a real take-away slip - PANEER TIKKA at 260.00 with nothing off it, TANDOORI
 * CHICKEN HALF at 360.00 with 5% off, item-wise, pre-tax, exclusive, GST 2.5+2.5.
 *
 * The paneer printed a discount of 7.55 that nobody had given it: it fell through to
 * the bill-wise share, and the "bill discount" the restaurant slip passes in is the
 * SUM of the item discounts, so the untouched line took 260/620 of the chicken's 18.00.
 * Tax was then charged on a base 7.55 short, and the bill's own DISCOUNT line said
 * 18.00 while the lines above it added up to 25.55.
 */
class CartMathItemwiseDiscountTest {

    private val cfg = CartMath.Config(
        taxEnabled = true,
        inclusive = false,
        discountPreTax = true,
        itemwiseDiscount = true,
        billwiseDiscount = false
    )

    /** 260.00, no discount configured against the product. */
    private val paneer = CartMath.Line(qty = 1.0, rate = 260.0, cgstRate = 2.5, sgstRate = 2.5)

    /** 360.00, 5% off, set on the product itself. */
    private val chicken = CartMath.Line(
        qty = 1.0, rate = 360.0, cgstRate = 2.5, sgstRate = 2.5,
        discValue = 5.0, discType = "P"
    )

    private val subtotal = CartMath.subtotal(listOf(paneer, chicken))

    /** The headline discount the restaurant slip hands to [CartMath.lineDiscount]. */
    private val billHeadline = 18.00

    @Test
    fun anUndiscountedItemTakesNoShareOfAnotherItemsDiscount() {
        assertEquals(
            0.0,
            CartMath.lineDiscount(paneer, cfg, subtotal, billHeadline),
            0.005
        )
    }

    /** The discounted line is untouched by the fix - 5% of 360.00. */
    @Test
    fun theDiscountedItemStillCarriesItsOwn() {
        assertEquals(
            18.00,
            CartMath.lineDiscount(chicken, cfg, subtotal, billHeadline),
            0.005
        )
    }

    /**
     * The lines add up to the bill's own DISCOUNT line, which is what the slip prints.
     * 25.55 was the sum before - 18.00 of it real, 7.55 invented.
     */
    @Test
    fun theLinesAddUpToTheBillsDiscount() {
        val sum = listOf(paneer, chicken)
            .sumOf { CartMath.lineDiscount(it, cfg, subtotal, billHeadline) }
        assertEquals(billHeadline, sum, 0.005)
    }

    /**
     * And so the taxable base is the full 602.00, not 594.45 - the slip charged
     * 29.72 of GST where 30.10 was due.
     */
    @Test
    fun theTaxableBaseLosesOnlyTheRealDiscount() {
        val taxable = listOf(paneer, chicken).sumOf {
            it.gross - CartMath.lineDiscount(it, cfg, subtotal, billHeadline)
        }
        assertEquals(602.00, taxable, 0.005)
        assertEquals(30.10, taxable * 0.05, 0.005)
    }

    /**
     * BILL-WISE IS UNTOUCHED. The same two lines under a bill-wise discount still
     * split it by their share of the subtotal - which is the branch the item-wise
     * case was wrongly borrowing, and it has to keep working.
     */
    @Test
    fun billWiseStillSplitsAcrossEveryLine() {
        val billWise = cfg.copy(itemwiseDiscount = false, billwiseDiscount = true)
        assertEquals(
            260.0 / 620.0 * 18.00,
            CartMath.lineDiscount(paneer, billWise, subtotal, billHeadline),
            0.005
        )
        assertEquals(
            360.0 / 620.0 * 18.00,
            CartMath.lineDiscount(chicken, billWise, subtotal, billHeadline),
            0.005
        )
    }

    // ---- The same two products, item-wise POST-TAX on MRP -------------------
    //
    // From the second slip: PANEER TIKKA printed a discount of 7.19 it was never
    // given. Same fall-through, a different arm of it - the share reaches the
    // `inclusive` post-tax line and is divided by the tax factor, so the invented
    // figure came out as 7.5484 / 1.05 rather than as 7.5484. That the two slips
    // showed different wrong numbers is what made this look like a second fault; it
    // is one, and the item-wise branch returning early settles both.

    /** Tax already inside the price, discount off the taxed price. */
    private val mrpPostTax = cfg.copy(inclusive = true, discountPreTax = false)

    @Test
    fun anUndiscountedItemTakesNoShareUnderPostTaxMrp() {
        assertEquals(
            0.0,
            CartMath.lineDiscount(paneer, mrpPostTax, subtotal, billHeadline),
            0.005
        )
    }

    /**
     * The discounted line is untouched here too: 5% of the 360.00 MRP, carried back
     * across the tax factor into the raw-base shape this function returns in -
     * 18.00 / 1.05. Pinned as a number rather than by calling the same helper the
     * code does, so the test would notice if that conversion changed.
     */
    @Test
    fun theDiscountedItemKeepsItsOwnUnderPostTaxMrp() {
        assertEquals(
            18.00 / 1.05,
            CartMath.lineDiscount(chicken, mrpPostTax, subtotal, billHeadline),
            0.005
        )
    }

    /**
     * What the customer is charged: 260.00 untouched plus 342.00 discounted, against
     * the 594.81 the slip printed. The 0.19 round-off it showed was rounding a total
     * that was 7.19 light to begin with.
     */
    @Test
    fun thePostTaxMrpBillComesToTheFullSixHundredAndTwo() {
        val paneerNet = paneer.gross - CartMath.lineDiscount(paneer, mrpPostTax, subtotal, billHeadline)
        // The chicken's own discount is MRP-denominated on the slip - 18.00 off 360.
        val chickenNet = chicken.gross - 18.00
        assertEquals(260.00, paneerNet, 0.005)
        assertEquals(602.00, paneerNet + chickenNet, 0.005)
    }

    /**
     * BILL-WISE POST-TAX MRP still divides the share by the tax factor - the arm the
     * item-wise case was borrowing, which has to go on working for the mode it
     * actually belongs to. 7.19 is the right answer HERE, and only here.
     */
    @Test
    fun billWisePostTaxMrpStillDividesTheShareByTheTaxFactor() {
        val billWise = mrpPostTax.copy(itemwiseDiscount = false, billwiseDiscount = true)
        assertEquals(
            260.0 / 620.0 * 18.00 / 1.05,
            CartMath.lineDiscount(paneer, billWise, subtotal, billHeadline),
            0.005
        )
    }
}
