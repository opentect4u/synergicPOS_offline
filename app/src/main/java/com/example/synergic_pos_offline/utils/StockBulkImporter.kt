package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.StockDao

/**
 * Books a filled-in [StockCsvTemplate] sheet in as a Stock In entry.
 *
 * Every quantity **adds** to what the item already holds - this is a delivery being
 * received, not a stocktake being declared. That is the whole difference between
 * this and the item master's opening-stock column, which sets the figure a product
 * starts from; a sheet uploaded here twice books the delivery twice, exactly as
 * entering it twice by hand would.
 *
 * It goes in through [StockDao.receive], the same call the Stock In modal uses, so
 * a bulk delivery lands in md_batch_stock and the stock history the same way a
 * hand-entered one does. Nothing here writes stock itself.
 */
object StockBulkImporter {

    /** The headings a sheet may give the received quantity under. */
    private val QUANTITY_COLUMNS =
        listOf(StockCsvTemplate.STOCK_COLUMN, "quantity", "qty", "stock_qty", "received")

    /** The headings a sheet may give the item's name under. */
    private val NAME_COLUMNS = listOf(StockCsvTemplate.NAME_COLUMN, "item_name", "name", "item")

    /**
     * What a run did.
     *
     * [unknown] and [invalid] are named rather than counted because the operator has
     * to go and fix those lines in the sheet, and "3 rows were not imported" does not
     * tell them which three.
     */
    data class Result(
        val received: Int,
        val totalQuantity: Double,
        val blank: Int,
        val unknown: List<String>,
        val invalid: List<String>
    ) {
        val hasProblems: Boolean get() = unknown.isNotEmpty() || invalid.isNotEmpty()
    }

    /**
     * Reads [rows] against the till's catalogue without writing anything.
     *
     * Split from [import] so the screen can say what is about to happen - and name
     * the rows that will not import - before a delivery is booked in. Stock that has
     * been received cannot be un-received except by writing it off, which lands in
     * the history as a write-off that never happened.
     */
    fun preview(context: Context, rows: List<Map<String, String>>): Result =
        resolve(context, rows).first

    /** Books every readable row in, and reports the same [Result] [preview] did. */
    fun import(context: Context, rows: List<Map<String, String>>): Result {
        val (result, movements) = resolve(context, rows)
        if (movements.isNotEmpty()) {
            StockDao(context).receive(movements, note = "Bulk stock in")
        }
        return result
    }

    /**
     * Matches each row to a product and a quantity.
     *
     * Names are matched case- and space-insensitively: a sheet that has been through
     * a spreadsheet comes back with stray spaces and inconsistent capitals, and
     * refusing "basmati rice " when the till holds "Basmati Rice" would fail a row
     * the operator has no way to see is wrong.
     *
     * Duplicate names are left as separate movements rather than summed. Two lines
     * for one item is how a delivery of two cartons is written, and the stock history
     * should show it the way it was received.
     */
    private fun resolve(
        context: Context,
        rows: List<Map<String, String>>
    ): Pair<Result, List<StockDao.Movement>> {
        val items = runCatching {
            StockDao(context).items(SessionManager.currentUser?.storeId ?: 0)
        }.getOrDefault(emptyList())
        return resolveAgainst(items, rows)
    }

    /**
     * The reading itself, against a catalogue passed in rather than read from the
     * database.
     *
     * Split out so the rules a sheet is judged by - which names match, which cells
     * are quantities, what counts as a blank - can be tested without a device and a
     * populated till behind them. [resolve] supplies the real catalogue.
     */
    internal fun resolveAgainst(
        items: List<StockDao.StockItem>,
        rows: List<Map<String, String>>
    ): Pair<Result, List<StockDao.Movement>> {
        val byName = items.associateBy { it.name.trim().lowercase() }

        val movements = mutableListOf<StockDao.Movement>()
        val unknown = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        var blank = 0
        var total = 0.0

        rows.forEach { row ->
            val name = NAME_COLUMNS.firstNotNullOfOrNull { row[it]?.trim()?.takeIf(String::isNotBlank) }
            if (name == null) { blank++; return@forEach }

            val cell = QUANTITY_COLUMNS.firstNotNullOfOrNull { row[it]?.trim()?.takeIf(String::isNotBlank) }
            // A name with no quantity beside it is a line the operator did not fill
            // in - most of the sheet, on a delivery of a dozen items out of hundreds.
            // It is skipped quietly rather than reported as a problem.
            if (cell == null) { blank++; return@forEach }

            val product = byName[name.lowercase()]
            if (product == null) { unknown.add(name); return@forEach }

            val quantity = cell.toDoubleOrNull()
            if (quantity == null || quantity <= 0.0) { invalid.add("$name ($cell)"); return@forEach }

            movements.add(StockDao.Movement(product.productId, quantity))
            total += quantity
        }

        return Result(movements.size, total, blank, unknown, invalid) to movements
    }
}
