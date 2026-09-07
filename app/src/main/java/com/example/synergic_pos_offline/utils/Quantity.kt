package com.example.synergic_pos_offline.utils

import java.util.Locale

/**
 * How much of something was sold, written down.
 *
 * ## Why this is one function and not eight
 *
 * It was eight, and they did not agree. Every SCREEN wrote a quantity to three
 * decimals - the cart, the checkout, the restaurant panel, the quantity popup - and
 * every PRINTED document wrote it to two: the bill, the KOT, the coupons, the return
 * slip. So a shop weighing 0.125 kg was quoted 0.125 on the till and handed a slip
 * saying 0.13, and the paper disagreed with the screen about what had been sold.
 *
 * The entry field accepts [DECIMALS] places (see ProductEntryDialog's own filter), so
 * three is the number the shop can actually type - and a figure that can be typed and
 * cannot be printed is a figure the bill is rounding off behind the operator's back.
 *
 * ## Not rounded, and not padded
 *
 * A quantity is a count of goods, not money: it is never "made up to" a fixed number
 * of places the way a price is. A whole one prints whole - 2, not 2.000 - and a
 * fractional one prints exactly the places it needs, so 0.5 stays 0.5 and 0.125 stays
 * 0.125. That is what the screens already did, and what the paper now does too.
 */
object Quantity {

    /**
     * The places a quantity may carry - the same number the quantity field accepts.
     *
     * Held here rather than beside the field, because the field is where it is TYPED
     * and this is where it is READ BACK, and those two have to be the same number.
     */
    const val DECIMALS = 3

    /** [qty] as it should be shown or printed, wherever that is. */
    fun text(qty: Double): String =
        if (qty % 1.0 == 0.0) qty.toLong().toString()
        else String.format(Locale.US, "%.${DECIMALS}f", qty).trimEnd('0').trimEnd('.')

    /**
     * The widest a quantity column ever has to be, as a sample string to measure.
     *
     * Four figures and every decimal place - what a column has to reserve if the
     * numbers are to line up on every ticket rather than wherever this one's happen
     * to end.
     */
    val WIDEST_SAMPLE: String = "0000." + "0".repeat(DECIMALS)
}
