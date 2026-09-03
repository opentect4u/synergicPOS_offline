package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * The Stock Report: what came in, what went out, and what is left.
 *
 * Three figures per item over a period:
 *
 * - **PUR.STOCK** - everything that came *in* between the dates: deliveries taken
 *   through Stock In, and goods put back by a sale return. Read from
 *   [DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS], which is where every movement is
 *   logged whichever screen made it.
 * - **SLD.STK.** - what was sold between the dates, off the bill lines rather than
 *   the movement log. Bills are the record of a sale; the movement log only has a row
 *   where stock tracking was switched on at the time, and a report that disagreed
 *   with the Item Wise Report about how much went would be the one nobody trusted.
 * - **C.STOCK** - what is on the shelf *now*, from [StockDao.items], the same count
 *   the Stock In screen and the low-stock badges read.
 *
 * The first two belong to the period and the third does not, which is worth knowing
 * before the three are read as though they add up: opening stock plus purchases less
 * sales gives the closing count only for a range ending today, and only where nothing
 * was written off in between.
 *
 * A negative count is printed as it stands. Stock can go below zero on a till that
 * sells what it has not booked in, and rounding that up to zero would hide the one
 * thing the report is being read to find.
 */
class StockReportDao(context: Context) {

    private val appContext = context.applicationContext
    private val helper = DatabaseHelper.getInstance(context)

    /** One item on the report. */
    data class Line(
        val serial: Int,
        val name: String,
        /** What came in over the period. */
        val purchased: Double,
        /** What was sold over the period. */
        val sold: Double,
        /** What is on the shelf now, across all of the item's batches. */
        val current: Double
    )

    /**
     * The whole report: the period asked for, and every item on the master.
     *
     * Totalled from the listed lines rather than by a second query, so the summary
     * and the rows above it cannot disagree.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        val lines: List<Line>
    ) {
        val itemCount: Int get() = lines.size
        val totalPurchased: Double get() = total { it.purchased }
        val totalSold: Double get() = total { it.sold }
        val totalCurrent: Double get() = total { it.current }

        /** Items with nothing left - what the report is most often opened to find. */
        val outOfStock: Int get() = lines.count { it.current <= 0.0 }

        val isEmpty: Boolean get() = lines.isEmpty()

        private fun total(pick: (Line) -> Double): Double =
            BillRounding.toPaise(lines.sumOf { pick(it) })
    }

    /**
     * Every item, with what moved between [fromDate] and [toDate] inclusive, both
     * `yyyy-MM-dd`, in the master's own order.
     *
     * Items that did not move are listed rather than left out: an item nobody bought
     * and nobody restocked is exactly what a stock report is read to find, and one
     * that silently omitted it would read as though it had never been stocked.
     */
    fun between(fromDate: String, toDate: String): Report {
        val store = currentStoreId()
        val items = StockDao(appContext).items(store)
        val purchased = movedIn(fromDate, toDate)
        val sold = soldBetween(fromDate, toDate, store.takeIf { it != 0 })

        return Report(
            fromDate = fromDate,
            toDate = toDate,
            lines = items.mapIndexed { i, it ->
                Line(
                    serial = i + 1,
                    name = it.name,
                    purchased = BillRounding.toPaise(purchased[it.productId.toLong()] ?: 0.0),
                    sold = BillRounding.toPaise(sold[it.productId.toLong()] ?: 0.0),
                    current = it.stock
                )
            }
        )
    }

    /**
     * What came in per product over the period, from the movement log.
     *
     * Every inward movement, whatever put it there - a delivery or goods handed back
     * on a return. `quantity` is always positive and `stock_flow` carries the
     * direction, so this is a plain sum of the IN rows.
     */
    private fun movedIn(fromDate: String, toDate: String): Map<Long, Double> {
        val map = hashMapOf<Long, Double>()
        helper.readableDatabase.rawQuery(
            """
            SELECT product_id, COALESCE(SUM(quantity), 0)
            FROM ${DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS}
            WHERE substr(transaction_date, 1, 10) BETWEEN ? AND ?
              AND stock_flow = 'IN'
              AND product_id IS NOT NULL
            GROUP BY product_id
            """.trimIndent(),
            arrayOf(fromDate, toDate)
        ).use { c ->
            while (c.moveToNext()) map[c.getLong(0)] = c.getDouble(1)
        }
        return map
    }

    /**
     * What was sold per product over the period, from the bill lines.
     *
     * Voided and cancelled bills are left out, exactly as every sales report leaves
     * them out - the goods were never sold, and on a till that tracks stock they were
     * never taken off the shelf either.
     */
    private fun soldBetween(fromDate: String, toDate: String, store: Int?): Map<Long, Double> {
        val storeClause = if (store != null) "AND b.store_id = ?" else ""
        val args = mutableListOf(fromDate, toDate).apply {
            if (store != null) add(store.toString())
        }

        val map = hashMapOf<Long, Double>()
        helper.readableDatabase.rawQuery(
            """
            SELECT i.product_id, COALESCE(SUM(i.quantity), 0)
            FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = i.bill_id
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              AND i.product_id IS NOT NULL
              $storeClause
            GROUP BY i.product_id
            """.trimIndent(),
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) map[c.getLong(0)] = c.getDouble(1)
        }
        return map
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
