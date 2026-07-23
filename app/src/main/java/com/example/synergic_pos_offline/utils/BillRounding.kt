package com.example.synergic_pos_offline.utils

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * Rounds a bill to whole rupees, the way a counter actually settles one - nobody
 * hands over 25 paise.
 *
 * The adjustment is kept alongside the rounded figure rather than folded silently
 * into the total, so a receipt can show what was added or knocked off and the two
 * still reconcile against the taxed value.
 */
object BillRounding {

    /** What the customer pays: [amount] taken to the nearest rupee, halves up. */
    fun payable(amount: Double): Double {
        // Normalise to paise first: a total assembled from percentages can land on
        // 78.499999, which would otherwise round the wrong way.
        val paise = round(amount * 100.0) / 100.0
        return floor(paise + 0.5)
    }

    /** The adjustment applied to reach [payable]: positive when rounded up. */
    fun roundOff(amount: Double): Double {
        val paise = round(amount * 100.0) / 100.0
        return round((payable(amount) - paise) * 100.0) / 100.0
    }

    /** True when the bill needed no adjustment. */
    fun isExact(amount: Double): Boolean = abs(roundOff(amount)) < 0.001
}
