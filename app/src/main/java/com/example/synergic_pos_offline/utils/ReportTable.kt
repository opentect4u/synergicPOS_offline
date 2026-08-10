package com.example.synergic_pos_offline.utils

/**
 * How the reports' on-screen tables share out the width they are given.
 *
 * Their columns are declared at the width their content needs, which is what a
 * narrow screen has to scroll sideways through. On a tablet that leaves the table
 * ending part way across its card with white space after it, which reads as a table
 * that failed to load rather than one that happens to be narrow.
 *
 * So the declared widths are minimums, not answers: where there is room to spare it
 * is handed out and the table fills the card, and where there is not the columns
 * keep their minimums and the table scrolls as before.
 *
 * Shared by the three reports because they are the same table three times over, and
 * a rule about how wide a column is should not be settled once per report.
 */
object ReportTable {

    /**
     * [minWidthsPx] stretched to fill [availablePx], or returned untouched when they
     * already need more than that.
     *
     * The surplus goes out in proportion, so a column declared twice as wide as
     * another stays twice as wide. Distributing it evenly instead would fatten the
     * narrow figure columns at the expense of the one column - the item name - that
     * can actually use the room.
     *
     * The last column takes any rounding remainder, so the total is exactly
     * [availablePx] and the table's right edge meets the card's.
     */
    fun stretch(minWidthsPx: IntArray, availablePx: Int): IntArray {
        val total = minWidthsPx.sum()
        if (total <= 0 || availablePx <= total) return minWidthsPx

        val out = IntArray(minWidthsPx.size)
        var used = 0
        for (i in minWidthsPx.indices) {
            out[i] = (minWidthsPx[i].toLong() * availablePx / total).toInt()
            used += out[i]
        }
        out[out.lastIndex] += availablePx - used
        return out
    }
}
