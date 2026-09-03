package com.example.synergic_pos_offline.utils

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.example.synergic_pos_offline.database.StockDao

/**
 * The stock pill shown wherever an item is picked for a sale - the grocery grid, the
 * restaurant grid, and the add-to-cart dialog all show the same one.
 *
 * Kept in one place because the three would otherwise drift: an operator reading
 * "Low" on a tile and a plain count in the dialog that follows it would have no way
 * to tell which was right.
 *
 * The badge carries the count, not just the state - what is needed off a tile is how
 * many are left, and "Low stock" alone does not say whether that is one or nine.
 */
object StockBadge {

    /** Stock is not being tracked; the badge shows nothing at all. */
    const val OFF = "off"
    const val OK = "ok"
    const val LOW = "low"
    const val OUT = "out"

    /** Fills [tv] for [state] ([OFF]/[OK]/[LOW]/[OUT]), hiding it when untracked. */
    fun apply(tv: TextView, state: String, quantity: Double) {
        val onHand = StockDao.trim(quantity)
        val (text, color) = when (state) {
            LOW -> "Low: $onHand" to AMBER
            OUT -> "Out of stock" to RED
            OK -> "Stock: $onHand" to GREY
            else -> { tv.visibility = View.GONE; return }
        }
        tv.visibility = View.VISIBLE
        tv.text = text
        tv.background = GradientDrawable().apply {
            cornerRadius = 8 * tv.resources.displayMetrics.density
            setColor(Color.parseColor(color))
        }
        tv.setTextColor(Color.WHITE)
    }

    /** The state a [StockDao.StockLevel] reads as, or [OFF] when there is none. */
    fun stateOf(level: StockDao.StockLevel?): String = when {
        level == null -> OFF
        level.isOut -> OUT
        level.isLow -> LOW
        else -> OK
    }

    private const val AMBER = "#F9AB00"
    private const val RED = "#D93025"
    private const val GREY = "#5F6368"
}
