package com.example.synergic_pos_offline.utils

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/**
 * Rounds a bill to whole rupees, the way a counter actually settles one - nobody
 * hands over 25 paise.
 *
 * The adjustment is kept alongside the rounded figure rather than folded silently
 * into the total, so a receipt can show what was added or knocked off and the two
 * still reconcile against the taxed value.
 */
object BillRounding {

    /**
     * [amount] to the nearest paisa, halves up - the precision every money figure is
     * reported at, and the precision totals have to be assembled from if the parts
     * printed on a bill are to add up to the total printed under them.
     *
     * Taken through [BigDecimal.valueOf], which works from the number's shortest
     * decimal form: a figure landing exactly on half a paisa (2.5% of 106.70 is
     * 2.6675) then rounds up, as arithmetic says. Both `"%.2f"` and
     * `round(x * 100) / 100` instead round it *down*, because the nearest double to
     * 2.6675 sits just below it.
     */
    fun toPaise(amount: Double): Double =
        BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toDouble()

    /** What the customer pays: [amount] taken to the nearest rupee, halves up. */
    fun payable(amount: Double): Double {
        // Normalise to paise first: a total assembled from percentages can land on
        // 78.499999, which would otherwise round the wrong way.
        return floor(toPaise(amount) + 0.5)
    }

    /** The adjustment applied to reach [payable]: positive when rounded up. */
    fun roundOff(amount: Double): Double = toPaise(payable(amount) - toPaise(amount))

    /** True when the bill needed no adjustment. */
    fun isExact(amount: Double): Boolean = abs(roundOff(amount)) < 0.001
}
