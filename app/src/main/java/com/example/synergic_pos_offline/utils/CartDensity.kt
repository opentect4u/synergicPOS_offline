package com.example.synergic_pos_offline.utils

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.example.synergic_pos_offline.R

/**
 * Sizes a cart row so a target number of them fits the panel it is scrolling in.
 *
 * The restaurant order panel must show ten lines without scrolling, and the height it
 * has to do that in is not knowable up front - a 7" phone in landscape and a 12"
 * tablet differ by hundreds of dp, and the panel itself grows and shrinks as the tax
 * fold opens. So the row is not given a fixed height in XML. It is laid out at its
 * comfortable size, and this squeezes it towards a tight one by however much the
 * available height demands.
 *
 * What gets squeezed, in order of how little it costs to lose: the padding around the
 * line, the gap between its two lines, then the tap targets, and last - and least -
 * the type. Text stops shrinking well before it stops being readable; a row that fits
 * but cannot be read at a glance is no use to somebody working a counter.
 */
object CartDensity {

    /** Lines that should fit the panel without scrolling. */
    const val TARGET_ROWS = 10

    // Comfortable (scale 1) and tight (scale 0) ends of each dimension, in dp/sp.
    private const val PAD_MAX = 10f
    private const val PAD_MIN = 3f
    private const val GAP_MAX = 6f
    private const val GAP_MIN = 2f
    private const val STEP_MAX = 28f
    private const val STEP_MIN = 22f
    private const val DEL_MAX = 32f
    private const val DEL_MIN = 24f
    private const val NAME_MAX = 15f
    private const val NAME_MIN = 13f
    private const val RATE_MAX = 12f
    private const val RATE_MIN = 11f
    private const val NOTE_MAX = 11f
    private const val NOTE_MIN = 10f

    /** Height of the name line's text, which is not squeezed away, at either end. */
    private const val NAME_LINE_MAX = 20f
    private const val NAME_LINE_MIN = 18f

    /** Row height at each end, the sum of everything stacked vertically. */
    private const val ROW_MAX = PAD_MAX * 2 + NAME_LINE_MAX + GAP_MAX + DEL_MAX
    private const val ROW_MIN = PAD_MIN * 2 + NAME_LINE_MIN + GAP_MIN + DEL_MIN

    /**
     * How hard rows must be squeezed for [TARGET_ROWS] of them to fit [availablePx].
     *
     * 1 means the panel is roomy enough to leave rows alone; 0 means it is at or past
     * the tightest they go. Returning 0 rather than going further is deliberate - past
     * this point the honest answer is that ten lines do not fit, and the list scrolls,
     * which is better than ten unreadable ones.
     */
    fun scaleFor(availablePx: Int, density: Float): Float {
        if (availablePx <= 0 || density <= 0f) return 1f
        val perRowDp = availablePx / density / TARGET_ROWS
        return ((perRowDp - ROW_MIN) / (ROW_MAX - ROW_MIN)).coerceIn(0f, 1f)
    }

    /** Applies [scale] (1 comfortable … 0 tight) to one inflated compact cart row. */
    fun apply(row: View, scale: Float) {
        val d = row.resources.displayMetrics.density
        fun px(min: Float, max: Float) = ((min + (max - min) * scale) * d).toInt()
        fun sp(min: Float, max: Float) = min + (max - min) * scale

        val pad = px(PAD_MIN, PAD_MAX)
        row.setPadding(row.paddingLeft, pad, row.paddingRight, pad)

        val body = row as? LinearLayout ?: return
        // Line 2 carries the gap between the two lines as its top margin.
        (body.getChildAt(1)?.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = px(GAP_MIN, GAP_MAX)
        }

        val step = px(STEP_MIN, STEP_MAX)
        listOf(R.id.btnMinus, R.id.btnPlus).forEach { id ->
            row.findViewById<ImageButton>(id)?.layoutParams?.apply { width = step; height = step }
        }
        val del = px(DEL_MIN, DEL_MAX)
        row.findViewById<ImageButton>(R.id.btnRemoveLine)?.layoutParams?.apply {
            width = del; height = del
        }

        row.findViewById<TextView>(R.id.tvLineName)?.textSize = sp(NAME_MIN, NAME_MAX)
        row.findViewById<TextView>(R.id.tvLineAmount)?.textSize = sp(NAME_MIN, NAME_MAX)
        row.findViewById<TextView>(R.id.tvLineQty)?.textSize = sp(NAME_MIN, NAME_MAX)
        row.findViewById<TextView>(R.id.tvLineRate)?.textSize = sp(RATE_MIN, RATE_MAX)
        row.findViewById<TextView>(R.id.tvLineNote)?.textSize = sp(NOTE_MIN, NOTE_MAX)
    }
}
