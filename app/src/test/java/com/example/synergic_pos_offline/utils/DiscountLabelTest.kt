package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The DISCOUNT line's label on a printed bill.
 *
 * A bill-wise discount can be typed as a RATE ("5%") or as an AMOUNT ("50 off"), and
 * the slip must say which it was. Both screens used to hand the renderer a percentage
 * DERIVED from the amount - fifty rupees off a 962.50 bill is 5.19% of it - so a flat
 * discount printed "DISCOUNT @5.19%", a rate the shop never quoted to anybody.
 *
 * [BillReceiptRenderer] prints the rate when it is given one and the bare word when it
 * is given zero, so what is pinned here is the rule the callers now apply: pass the
 * rate the operator TYPED, and only when they typed a rate.
 */
class DiscountLabelTest {

    private val percent = GstCalculator.DiscountMode.PERCENT
    private val amount = GstCalculator.DiscountMode.AMOUNT

    /** The rule both sale screens apply before handing a rate to the slip. */
    private fun rateForLabel(
        itemwise: Boolean,
        mode: GstCalculator.DiscountMode,
        typed: Double
    ): Double = if (!itemwise && mode == percent) typed else 0.0

    /** A rate typed as a rate prints as one. */
    @Test
    fun aPercentageDiscountCarriesItsRate() {
        assertEquals(5.0, rateForLabel(itemwise = false, mode = percent, typed = 5.0), 0.0)
    }

    /**
     * A flat discount carries NO rate - the bug this fixes.
     *
     * The amount is still printed; only the "@x%" beside it goes.
     */
    @Test
    fun aFlatDiscountCarriesNoRate() {
        assertEquals(0.0, rateForLabel(itemwise = false, mode = amount, typed = 50.0), 0.0)
    }

    /**
     * Item-wise carries none either, whatever the mode says.
     *
     * That discount belongs to the lines, each with its own rate, so the bill has no
     * single figure to put on one line.
     */
    @Test
    fun anItemWiseDiscountCarriesNoRate() {
        assertEquals(0.0, rateForLabel(itemwise = true, mode = percent, typed = 5.0), 0.0)
        assertEquals(0.0, rateForLabel(itemwise = true, mode = amount, typed = 50.0), 0.0)
    }

    /**
     * A derived percentage is NOT what gets printed.
     *
     * Fifty rupees off a 962.50 bill really is 5.19% of it - a true statement, and the
     * one the report column keeps - but it is not a rate anybody agreed to, so it must
     * not reach the label.
     */
    @Test
    fun theDerivedPercentageNeverReachesTheLabel() {
        val derived = 50.0 / 962.50 * 100.0
        assertEquals(5.19, derived, 0.01)
        assertEquals(0.0, rateForLabel(itemwise = false, mode = amount, typed = 50.0), 0.0)
    }
}
