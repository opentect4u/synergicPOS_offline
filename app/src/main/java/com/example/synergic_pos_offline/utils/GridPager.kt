package com.example.synergic_pos_offline.utils

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Feeds a product grid a page at a time, and the next page as the bottom of the
 * current one comes into view.
 *
 * ## What this is for, and what RecyclerView already does
 *
 * RecyclerView recycles VIEWS: a thousand products never mean a thousand rows of
 * widgets. What it does not do is bound the WORK of binding them - and a product tile
 * binds a photo. On a catalogue of a few thousand, laying the whole filtered list on
 * the adapter means the first screen waits behind measuring every tile, and every
 * keystroke in the search box re-lays the lot.
 *
 * So the adapter is only ever given [PAGE_SIZE] rows to start with, and grows as the
 * grid is scrolled. The filtered list itself is untouched - searching, category tabs
 * and the counts still reason over all of it - which is what keeps this a change to
 * how much is DRAWN rather than to what the screen contains.
 *
 * The masters' table does the same thing by hand ([DataTableFragment]); this is the
 * grid's version of it, shared so both sale screens page the same way.
 */
class GridPager<T>(
    private val rv: RecyclerView,
    /** Hands the adapter the rows it should now show. */
    private val submit: (List<T>) -> Unit
) {

    /** The whole filtered list. Only [shown] of it has reached the adapter. */
    private var all: List<T> = emptyList()
    private var shown = 0

    init {
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // Only on the way down: paging in more while scrolling back up would
                // grow the list under a finger that is heading away from its end.
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                // Measured in ROWS, not items: a threshold of 10 items is barely one
                // row on a grid seven across, so the next page would arrive after the
                // end had already been reached rather than before it.
                val ahead = LOAD_AHEAD_ROWS * lm.spanCount
                if (lm.findLastVisibleItemPosition() >= shown - ahead) next()
            }
        })
    }

    /**
     * Replaces the list and starts again at the first page.
     *
     * Called on every search keystroke and category change, so it deliberately resets:
     * a new filter is a new list, and carrying the old scroll depth into it would show
     * a hundred rows of a result that only has three.
     */
    fun set(list: List<T>) {
        all = list
        shown = minOf(PAGE_SIZE, all.size)
        submit(all.take(shown))
        // A filter that leaves fewer items than a screen holds never scrolls, so the
        // listener above would never fire to load the rest.
        rv.post { fillViewport() }
    }

    /**
     * Grows the first page until the grid holds MORE than a screenful - [OVERSCAN]
     * screens of it - or the list runs out.
     *
     * Measured rather than assumed. A fixed page is either short on a tall tablet, so
     * the grid cannot be scrolled and the scroll listener never fires to load the
     * rest, or wasteful on a short one. Here the tile's own measured height and the
     * column count give how many rows a screen actually holds, and the target follows
     * from that - the same code lands on more rows on a big screen and fewer on a
     * small one.
     *
     * Why more than one screen: a grid filled to exactly its own height has nothing
     * below the fold, so the first flick has nowhere to go and the operator sees the
     * list stall before it grows. A second screen already in place means the next page
     * is always being fetched into slack rather than into a gap being looked at.
     */
    private fun fillViewport() {
        val lm = rv.layoutManager as? GridLayoutManager ?: return
        val viewport = rv.height
        if (viewport <= 0) return

        // The height of one row, from a tile that has actually been laid out. Nothing
        // is guessed from dp: the tile sizes itself off the column width, which is the
        // screen's business.
        val rowHeight = rv.getChildAt(0)?.height?.takeIf { it > 0 } ?: return
        val rowsWanted = kotlin.math.ceil(viewport * OVERSCAN / rowHeight.toDouble()).toInt()
        val target = (rowsWanted * lm.spanCount).coerceAtLeast(PAGE_SIZE)

        var guard = 0
        while (shown < all.size && shown < target && guard++ < MAX_FILL) next()
    }

    private fun next() {
        if (shown >= all.size) return
        shown = minOf(shown + PAGE_SIZE, all.size)
        submit(all.take(shown))
    }

    private companion object {
        /**
         * Rows handed over at a time.
         *
         * Comfortably more than a screen holds at seven or eight across, so a page
         * boundary is never visible as a pause while scrolling at a normal speed.
         */
        const val PAGE_SIZE = 60

        /** How many ROWS ahead of the end the next page is fetched. */
        const val LOAD_AHEAD_ROWS = 2

        /**
         * Screens of product loaded before scrolling starts.
         *
         * Two: one to look at and one already under it, so the first flick moves
         * through content that is there rather than arriving at an end that then
         * grows. Beyond that is work done for a screen nobody has reached.
         */
        const val OVERSCAN = 2.0

        /** A ceiling on the fill loop, so a layout that never reports a last position
         *  cannot spin. Far more pages than any viewport can want. */
        const val MAX_FILL = 20
    }
}
