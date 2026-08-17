package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.database.StockDao

/**
 * What is running out, for the till to say so at the start of a shift.
 *
 * ## What counts
 *
 * Two states, and they are not the same problem. An item that is **out** cannot be
 * sold at all; an item that is **low** still can, and is the one there is time to do
 * something about. Both are worth an operator's attention when they open the till and
 * neither is worth interrupting a sale for, which is why this is read once at login
 * and then sits in the header rather than appearing over the screen again.
 *
 * ## The threshold, and why it is the settings one
 *
 * Low is measured against General Settings' **Alert Quantity**, for every product
 * alike - exactly as [com.example.synergic_pos_offline.database.StockDao.levels] does
 * it for the badges on the sale screen. That matters more than which rule is chosen:
 * an operator told here that five items are low, who then finds four badges on the
 * grid, has no way to know which of the two the till means. So there is one rule.
 *
 * `md_products.stock_alert_qty` is deliberately not consulted. It is carried by
 * seeded and imported products, has no field on the product form to set or correct
 * it, and letting it decide made the setting look broken - see [StockDao.levels],
 * where the same reasoning is set out at length.
 *
 * ## What switches it off
 *
 * Stock tracking. With the Stock flag off there is no quantity on hand to measure
 * against and nothing here reports anything, which is what keeps this out of the way
 * of the shops that do not count their stock.
 *
 * Stock Alert is the narrower switch, and it governs *low* only: with it off there is
 * no quantity that means "running out", so nothing is low - but an item at zero is
 * still an item that cannot be sold, and it is still reported. That is the same split
 * [StockDao.StockLevel] already makes, where `isLow` needs an alert quantity and
 * `isOut` does not.
 */
object StockAlerts {

    /** One product that needs attention, and how much of it is left. */
    data class Item(val name: String, val quantity: Double, val isOut: Boolean)

    /**
     * What the till found. [out] and [low] are kept apart rather than added up,
     * because an operator does different things about them.
     */
    data class Summary(val out: List<Item>, val low: List<Item>) {
        val total: Int get() = out.size + low.size
        val isEmpty: Boolean get() = total == 0

        /** Everything, worst first - an empty shelf ahead of a thinning one. */
        val items: List<Item> get() = out + low

        /**
         * The one line for the header and the top of the dialog.
         *
         * Names both figures when there are both, because "7 items need attention"
         * hides the difference between seven that are merely low and seven that
         * cannot be sold at all.
         */
        val headline: String
            get() = when {
                isEmpty -> "Stock is fine"
                out.isEmpty() -> "${items(low.size)} running low"
                low.isEmpty() -> "${items(out.size)} out of stock"
                else -> "${items(out.size)} out of stock, ${low.size} running low"
            }
    }

    /** "1 item" / "4 items", so every screen counts the same way out loud. */
    fun items(n: Int): String = if (n == 1) "1 item" else "$n items"

    /** Nothing to report - what a till with stock tracking off always gets. */
    val NONE = Summary(emptyList(), emptyList())

    /**
     * The products that are out or running low, worst first.
     *
     * Reads the database, so it belongs off the main thread on a large catalogue -
     * the filtering is done in SQL precisely so that what comes back is the short
     * list rather than the whole master.
     */
    fun find(context: Context): Summary = runCatching {
        val settings = GeneralSettingsDao(context).load()
        // The Stock flag is the whole feature's switch: no tracking, no alerts.
        if (!settings.stockFlag) return NONE
        // …and Stock Alert is the narrower one, over "low" alone. See the class note.
        // Held as the whole number the setting stores, so it can go into the SQL as a
        // number - see [lowClause] for why that matters here.
        val alertQty = if (settings.stockAlert) settings.stockAlertQty else 0

        val storeClause = currentStoreId(context)?.let { " WHERE p.store_id = $it" } ?: ""
        val onHand =
            "COALESCE((SELECT SUM(s.current_quantity) FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK} s " +
                "WHERE s.product_id = p.id), 0)"

        /**
         * The "running low" half of the filter, written as a numeric literal.
         *
         * Not a bound `?`, and this is the whole reason the clause is built rather
         * than parameterised: `rawQuery` binds every argument as **text**, and in
         * SQLite a number always compares less than a string. `onHand` here is an
         * expression and so carries no affinity for SQLite to convert against, which
         * leaves `onHand <= '10.0'` true of *every* product - a jar with 1000 on the
         * shelf reported as running low against a threshold of 10.
         *
         * The value is the Alert Quantity from General Settings, an Int the settings
         * screen has already validated, so there is nothing here to inject.
         *
         * Left out entirely when there is no threshold, rather than compared against
         * zero: with Stock Alert off nothing is low, and `onHand <= 0` on the line
         * above is already the out-of-stock half.
         */
        val lowClause = if (alertQty > 0) " OR (onHand > 0 AND onHand <= $alertQty)" else ""

        // Filtered in SQL rather than in Kotlin: a shop's master runs to thousands of
        // products and its short list to a handful, and there is no reason to carry
        // the difference back across.
        val sql = """
            SELECT name, onHand FROM (
                SELECT p.product_name AS name, $onHand AS onHand
                FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p$storeClause
            )
            WHERE name IS NOT NULL AND name <> ''
              AND (onHand <= 0$lowClause)
            ORDER BY onHand ASC, name ASC
        """.trimIndent()

        val out = mutableListOf<Item>()
        val low = mutableListOf<Item>()
        DatabaseHelper.getInstance(context).readableDatabase
            .rawQuery(sql, null)
            .use { c ->
                while (c.moveToNext()) {
                    val quantity = c.getDouble(1)
                    val item = Item(c.getString(0), quantity, isOut = quantity <= 0.0)
                    if (item.isOut) out.add(item) else low.add(item)
                }
            }
        Summary(out, low)
    }.getOrElse {
        android.util.Log.e(TAG, "Could not read the stock alerts", it)
        NONE
    }

    // ---- Showing it ---------------------------------------------------------

    /**
     * How many products the list names before it stops.
     *
     * Long enough to be worth reading and short enough to be read. A till with more
     * than this many items out has a supply problem rather than a stock alert, and
     * scrolling forty rows would not help it - the count that opened the list still
     * reports the whole figure.
     */
    const val MAX_LISTED = 15

    /**
     * The list of what needs attention - the same dialog wherever it is opened from.
     *
     * Here rather than at its two call sites so the header badge and the dashboard's
     * inventory panel cannot drift into showing the same products two different ways.
     * An operator who reached this list from the dashboard and then from the header
     * should have no way to tell which route they took.
     *
     * [onOpenStock] runs when a row is tapped. Every row does the same thing, because
     * which item was tapped does not change what an operator does next - and a picker
     * that looked like it would open the product and did not would be worse than one
     * that plainly does one thing.
     */
    fun showList(
        context: Context,
        title: String,
        headline: String,
        items: List<Item>,
        onOpenStock: () -> Unit
    ) {
        if (items.isEmpty()) return
        val shown = items.take(MAX_LISTED)
        val more = items.size - shown.size
        DialogUtils.showList(
            context = context,
            title = title,
            subtitle = headline + if (more > 0) "  ·  showing the first ${shown.size}" else "",
            items = shown.map {
                DialogUtils.ListItem(
                    title = it.name,
                    subtitle = if (it.isOut) "Out of stock" else "Running low",
                    trailing = StockDao.trim(it.quantity)
                )
            },
            negativeText = "Close"
        ) { onOpenStock() }
    }

    /**
     * Whether this till alerts on stock at all - the Stock flag, and nothing else.
     *
     * Asked on its own so the header can decide whether the badge exists before doing
     * the work of finding out what would go in it.
     */
    fun enabled(context: Context): Boolean = GeneralSettingsDao.isStockEnabled(context)

    /** The store the till is registered to, so another store's products are not counted. */
    private fun currentStoreId(context: Context): Long? =
        DatabaseHelper.getInstance(context).readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    private const val TAG = "StockAlerts"
}
