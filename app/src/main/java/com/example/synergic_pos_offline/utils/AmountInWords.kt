package com.example.synergic_pos_offline.utils

import kotlin.math.floor
import kotlin.math.round

/**
 * Spells a rupee amount the way an Indian invoice prints it, for the
 * `amount_in_words` column on a bill.
 *
 * Grouping is crore / lakh / thousand / hundred rather than western thousands,
 * so 1234567.5 reads "Rupees Twelve Lakh Thirty Four Thousand Five Hundred
 * Sixty Seven and Fifty Paise Only".
 */
object AmountInWords {

    private val ONES = arrayOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
        "Seventeen", "Eighteen", "Nineteen"
    )

    private val TENS = arrayOf(
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    )

    /** Indian place values, largest first. */
    private val PLACES = listOf(
        10_000_000L to "Crore",
        100_000L to "Lakh",
        1_000L to "Thousand",
        100L to "Hundred"
    )

    fun of(amount: Double): String {
        val safe = if (amount.isNaN() || amount < 0) 0.0 else amount
        var rupees = floor(safe).toLong()
        var paise = round((safe - rupees) * 100).toLong()
        // Rounding can tip the fraction to a full rupee: 9.999 is 10.00, not 9 and 100 paise.
        if (paise >= 100) {
            rupees += paise / 100
            paise %= 100
        }

        return buildString {
            append("Rupees ").append(if (rupees == 0L) "Zero" else toWords(rupees))
            // The unit trails the number for paise - "Seventy Five Paise" - while
            // rupees lead with it, which is how an Indian invoice reads.
            if (paise > 0) append(" and ").append(toWords(paise)).append(" Paise")
            append(" Only")
        }
    }

    private fun toWords(value: Long): String {
        if (value == 0L) return "Zero"
        val parts = mutableListOf<String>()
        var n = value
        PLACES.forEach { (unit, label) ->
            if (n >= unit) {
                // The count of a place can itself exceed 99 (e.g. 250 crore), so recurse.
                parts.add("${toWords(n / unit)} $label")
                n %= unit
            }
        }
        if (n > 0) parts.add(belowHundred(n.toInt()))
        return parts.joinToString(" ")
    }

    private fun belowHundred(n: Int): String {
        if (n < 20) return ONES[n]
        val tens = TENS[n / 10]
        return if (n % 10 == 0) tens else "$tens ${ONES[n % 10]}"
    }
}
