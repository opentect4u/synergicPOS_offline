package com.example.synergic_pos_offline.utils

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/**
 * The span count for a sale screen's product grid - the grocery's shelf and the
 * restaurant's menu, which are the same grid in two trades.
 *
 * Both used to be pinned at seven across. Seven is the right FLOOR, not the right
 * number: it is what makes the catalogue read as a shelf rather than a list, and on
 * the tablet these tills run on it lands each tile at a comfortable size. But a fixed
 * seven means a wider screen just gets wider tiles - a 15-inch till showing seven
 * products the size of playing cards - while the operator scrolls for the eighth.
 *
 * So seven is the minimum and the width decides the rest: as many tiles of about
 * [TARGET_TILE_DP] as fit, but never fewer than [MIN_SPANS]. On a narrow screen the
 * floor wins and the tiles shrink to make seven fit, which is the trade worth making -
 * a sale screen that shows less of the shop is worse than one whose tiles are small.
 */
object ProductGrid {

    /**
     * Never fewer than this, whatever the width. Below seven the grid stops looking
     * like a shelf and starts looking like a list with pictures.
     */
    const val MIN_SPANS = 7

    /**
     * The size a tile wants to be. Wide enough for a two-line product name and a
     * price at arm's length across a counter; narrow enough that a normal tablet
     * still lands on seven or eight rather than on the floor.
     */
    const val TARGET_TILE_DP = 128

    /**
     * Gives [rv] a grid that re-counts its columns whenever its width changes - which
     * covers a rotation, a fold, and the cart panel beside it being resized.
     *
     * The count is applied in a post rather than inline: this fires FROM a layout
     * pass, and changing a LayoutManager's span count during one is what
     * "Cannot call this method while RecyclerView is computing a layout" is.
     */
    fun attach(rv: RecyclerView) {
        val lm = GridLayoutManager(rv.context, MIN_SPANS)
        rv.layoutManager = lm
        rv.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width <= 0 || width == oldRight - oldLeft) return@addOnLayoutChangeListener
            val spans = spansFor(rv.context, width)
            if (spans != lm.spanCount) rv.post { lm.spanCount = spans }
        }
    }

    /** How many tiles of [TARGET_TILE_DP] fit across [widthPx], floored at [MIN_SPANS]. */
    fun spansFor(context: Context, widthPx: Int): Int {
        val widthDp = widthPx / context.resources.displayMetrics.density
        return max(MIN_SPANS, (widthDp / TARGET_TILE_DP).toInt())
    }
}
