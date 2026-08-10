package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Stock Report: every item and what is on the shelf right now.
 *
 * No date range, because stock is not a period: it is a standing count, true as of
 * the moment it is read. The other two reports ask what happened between two dates;
 * this one asks what is there.
 *
 * The count itself comes from [StockDao.items] rather than a query of its own, so
 * the report, the Stock In / Write Off screens and the low-stock badges on the sale
 * screen are all reading one definition of "on hand".
 */
class StockReportDao(context: Context) {

    private val appContext = context.applicationContext
    private val helper = DatabaseHelper.getInstance(context)

    /** One item on the report. */
    data class Line(
        val serial: Int,
        val name: String,
        /** Quantity on hand across all of the item's batches. */
        val quantity: Double
    )

    /**
     * The whole report. [takenAt] is when the count was read - a stock figure with
     * no time on it says nothing, since the next sale changes it.
     */
    data class Report(
        val takenAt: String,
        val lines: List<Line>
    ) {
        val itemCount: Int get() = lines.size
        val totalQuantity: Double get() = BillRounding.toPaise(lines.sumOf { it.quantity })

        /** Items with nothing left - what the report is most often opened to find. */
        val outOfStock: Int get() = lines.count { it.quantity <= 0.0 }

        val isEmpty: Boolean get() = lines.isEmpty()
    }

    /**
     * Stock as it stands, by item name.
     *
     * Items sitting at zero are listed rather than left out: an item that has run
     * out is exactly what someone opens a stock report to find, and a report that
     * silently omitted it would read as though it had never been stocked.
     */
    fun current(takenAt: String): Report {
        val items = StockDao(appContext).items(currentStoreId())
        return Report(
            takenAt = takenAt,
            lines = items.mapIndexed { i, it -> Line(i + 1, it.name, it.stock) }
        )
    }

    /** The signed-in user's store; the registration row is the fallback. */
    private fun currentStoreId(): Int {
        SessionManager.currentUser?.storeId?.takeIf { it != 0 }?.let { return it }
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getInt(0)
        }
        return 0
    }
}
