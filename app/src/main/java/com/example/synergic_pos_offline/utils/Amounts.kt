package com.example.synergic_pos_offline.utils

import java.util.Locale

/**
 * Money in and out of a text field.
 *
 * A figure that is READ and a figure that is TYPED BACK are not the same string, and
 * treating them as one is what put "1,491.00" into Amount Tendered and then read it
 * back as nothing: Kotlin's toDouble stops at the comma, the parse returned null, the
 * fallback made it 0, and a counter that had touched nothing was told the exact total
 * it was shown was less than the amount due.
 *
 * So there are two formatters. [display] groups the thousands, because that is how a
 * total is read at a glance. [editable] does not, because that value has to survive
 * being read back. And [parse] accepts either, plus whatever a person types - a shop
 * that enters "1,500" or "₹1500" means 1500.
 */
object Amounts {

    /** Grouped, for a figure that is only ever read: "1,491.00". */
    fun display(v: Double): String = String.format(Locale.US, "%,.2f", v)

    /** Ungrouped, for a field whose value will be read back: "1491.00". */
    fun editable(v: Double): String = String.format(Locale.US, "%.2f", v)

    /**
     * The number in [s], or null when there isn't one.
     *
     * Grouping separators, spaces and currency marks are stripped before parsing, so
     * this reads back anything [display] or [editable] wrote and anything a person is
     * likely to type. Null means "no figure here" - an empty box, or letters - and is
     * left to the caller to interpret; it deliberately does NOT collapse to zero,
     * because "nothing entered" and "zero tendered" are different answers and the bug
     * this replaced came from treating them as the same.
     */
    fun parse(s: String?): Double? {
        val cleaned = s?.trim().orEmpty()
            .replace(",", "")
            .replace(" ", "")   // non-breaking space, as some locales group with it
            .replace(" ", "")
            .replace("₹", "")
            .replace("Rs.", "", ignoreCase = true)
            .replace("Rs", "", ignoreCase = true)
        return cleaned.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    }
}
