package com.example.synergic_pos_offline.utils

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.database.GeneralSettingsDao

/**
 * Rearranging the sale screen's category tabs by holding one and dragging it.
 *
 * ## Why the shop gets to decide the order
 *
 * The tabs come out of the catalogue in the order the categories were created, which
 * is the order somebody happened to type them in on the day the till was set up. It is
 * nobody's selling order. A tea shop wants BEVERAGES first and CLEANING last, and until
 * now the only way to get that was to delete the categories and re-enter them in the
 * order wanted - taking every product's category with them.
 *
 * Holding a tab and dragging it is the whole feature. There is no edit mode to enter
 * and no Done button to find: the tabs are where the operator already is, and a long
 * press is the one gesture on that strip that meant nothing before, so nothing that
 * used to work stops working.
 *
 * ## One mechanism, both screens
 *
 * The grocery screen already drew its tabs in a RecyclerView; the restaurant screen
 * drew them as views in a scrolled LinearLayout, and was converted to a RecyclerView
 * to match. That conversion is the point, not incidental to it. Driving a strip of
 * plain sibling views means hand-rolling the drag shadow, the gap that opens as the
 * tab crosses its neighbours, and the scroll when it reaches the edge - three things
 * [ItemTouchHelper] already does, and does the same way on both screens.
 *
 * So there is one [attach] and one behaviour: the same hold to pick a tab up, the same
 * [lift] and [settle] on the tab in hand, the same tap on the phone as it comes away,
 * and the same order remembered afterwards.
 *
 * ## Two ways the order is kept
 *
 * [remember] only ever touches [remembered] - the in-memory list - and is the one
 * [ordered] itself reads, deliberately kept free of any database or Context so the
 * unit tests can drive it directly. It is enough on its own to survive leaving the
 * sale screen and coming back, and the catalogue being re-read underneath it, which
 * is what makes the gesture worth having at all inside one session.
 *
 * [persist] and [restore] are the pair that make it survive further than that - a
 * logout, or the app being closed. [persist] calls [remember] and then writes the
 * same list to [GeneralSettingsDao.saveCategoryOrder]; [restore] reads it back with
 * [GeneralSettingsDao.loadCategoryOrder] and seeds [remembered] with it. Each screen
 * calls [restore] once, when its tabs are first built after login, and [persist] -
 * never [remember] directly - from its own drag-drop handler, so a drag is written
 * down the moment it happens rather than waiting for some later save the sale screen
 * has no reason to otherwise make.
 */
object CategoryOrder {

    /**
     * The order the shop last dragged the tabs into, by category name.
     *
     * Names rather than row ids because the two screens do not agree on ids: the
     * grocery tabs are built from md_category rows and the restaurant's from the
     * category names carried on the products themselves. The name is what both have.
     */
    private val remembered = mutableListOf<String>()

    /**
     * [names] arranged the way the shop last dragged them.
     *
     * Anything not in [remembered] keeps its own relative order and goes to the END - a
     * category added since the last drag is new, and appending it is the one placement
     * that does not silently push something the shop positioned deliberately.
     *
     * Called on every rebuild of the tabs, so a catalogue re-read cannot quietly undo a
     * drag. Returns [names] untouched until something has actually been dragged.
     */
    fun ordered(names: List<String>): List<String> {
        if (remembered.isEmpty()) return names
        val rank = remembered.withIndex().associate { (i, name) -> name to i }
        return names.sortedBy { rank[it] ?: (remembered.size + names.indexOf(it)) }
    }

    /**
     * Records the order the tabs now stand in - the WHOLE strip, "All" included,
     * since it is dragged like any other tab and has to be able to come back to
     * wherever it was put.
     *
     * In-memory only, and deliberately: this is what [ordered] itself reads, and
     * keeping it free of a database or a Context is what lets the unit tests drive
     * it directly. A caller that wants the drag to survive a logout calls [persist]
     * instead, which does this and then saves it.
     */
    fun remember(names: List<String>) {
        remembered.clear()
        remembered.addAll(names)
    }

    /**
     * [remember]s the order, and writes it down so it is still there the next time
     * this shop logs in. Call this - not [remember] - from [attach]'s `onDropped`,
     * the one moment a drag is actually finished.
     */
    fun persist(context: Context, names: List<String>) {
        remember(names)
        GeneralSettingsDao(context).saveCategoryOrder(names)
    }

    /**
     * Seeds [remembered] with whatever this shop last dragged, read back off
     * [GeneralSettingsDao.loadCategoryOrder] - OVERWRITING whatever [remembered]
     * already held.
     *
     * The overwrite is deliberate, not merely tolerated: [CategoryOrder] is one
     * object shared for as long as the app process lives, and on a device more than
     * one shop signs into, a second login must not go on reading the first shop's
     * drag out of memory just because something was there already. Call this once,
     * at login (or wherever a sale screen first builds its tabs after one) - never
     * from inside a plain catalogue-reload rebuild, which would otherwise undo a
     * drag made seconds ago by racing [persist]'s own write back with a read of it.
     */
    fun restore(context: Context) {
        remembered.clear()
        remembered.addAll(GeneralSettingsDao(context).loadCategoryOrder())
    }

    // ---- What a tab in hand looks like ---------------------------------------

    /**
     * The tab comes off the strip: bigger, shadowed, and slightly see-through so the
     * gap opening up underneath it stays readable.
     *
     * Small numbers on purpose. This is a tab being carried, not a card being thrown
     * around - overdo the scale and the label stops lining up with the row it is about
     * to drop into, which is the one thing the operator is actually aiming with.
     */
    fun lift(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        view.animate().scaleX(LIFT_SCALE).scaleY(LIFT_SCALE).alpha(LIFT_ALPHA)
            .setDuration(LIFT_MS).start()
        view.elevation = view.resources.displayMetrics.density * LIFT_ELEVATION_DP
    }

    /** The tab settles back into the strip where it was dropped. */
    fun settle(view: View) {
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(LIFT_MS).start()
        view.elevation = 0f
    }

    private const val LIFT_SCALE = 1.06f
    private const val LIFT_ALPHA = 0.92f
    private const val LIFT_ELEVATION_DP = 8f
    private const val LIFT_MS = 120L

    // ---- The grocery strip: a RecyclerView -----------------------------------

    /**
     * Makes [recycler]'s tabs draggable by holding one.
     *
     * EVERY tab, "All" included. It was pinned first on the reasoning that it is not a
     * category but the way back to the whole catalogue, and that a drag could not put
     * it back - which was only true while it could not be dragged. Once it moves like
     * the rest, it comes back like the rest, and a shop that wants it somewhere other
     * than the far left is describing its own counter rather than making a mistake.
     *
     * [onMove] does the reordering in the caller's own list and tells the adapter; this
     * is only the gesture. [onDropped] fires once when the finger comes off, which is
     * where the caller records the result - not on every swap, because a drag across
     * six tabs is one decision, not six.
     */
    fun attach(
        recycler: RecyclerView,
        onMove: (from: Int, to: Int) -> Unit,
        onDropped: () -> Unit
    ) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.START or ItemTouchHelper.END, 0
        ) {
            override fun isLongPressDragEnabled() = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                onMove(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?,
                actionState: Int
            ) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.let { lift(it) }
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                settle(viewHolder.itemView)
                onDropped()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recycler)
    }

}
