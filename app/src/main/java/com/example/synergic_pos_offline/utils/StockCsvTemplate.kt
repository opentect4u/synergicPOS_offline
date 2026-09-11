package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.StockDao

/**
 * The Stock In upload sheet: the till's own item ids and names, and a column to
 * write the quantity received against each.
 *
 * Deliberately not the item master template. That sheet describes products - rates,
 * tax, units - and its `stock` column is an *opening* figure, the count a brand new
 * product starts life at. This one describes a delivery: nothing about the item is
 * being edited, and the number written against each is added to what is on the
 * shelf. Handing over the master sheet for a delivery would invite an operator to
 * edit a price while booking in stock.
 *
 * The id is what [StockBulkImporter] actually matches a row against - see its own
 * note on why. It is REQUIRED: a sheet with no id column at all is refused outright
 * rather than read against names, which is what invited the mismatch this column
 * exists to rule out. The name still rides along beside it, filled in and not to be
 * edited, so a row on the sheet still reads like the product it is without being
 * what the import actually goes by.
 */
object StockCsvTemplate {

    /** What the downloaded file is called. */
    const val FILE_NAME = "stock_in_template.csv"

    /** The item's own id, exactly as the till holds it - what a row is matched by.
     *  See [StockBulkImporter]. */
    const val ID_COLUMN = "product_id"

    /** The item's name, exactly as the till holds it. Not to be edited; carried for
     *  readability only - matching goes by [ID_COLUMN], not this. */
    const val NAME_COLUMN = "product_name"

    /** The quantity being received, added to whatever the item already holds. */
    const val STOCK_COLUMN = "stock"

    val header = listOf(ID_COLUMN, NAME_COLUMN, STOCK_COLUMN)

    /**
     * The sheet to hand the operator: every item the till knows, one per line, with
     * the quantity column left empty.
     *
     * Empty rather than pre-filled with the current count, because the figure being
     * asked for is what has *arrived*, not what is there. A sheet that came back
     * with the existing counts still in it would double every item on the shelf, and
     * an operator scanning down a column of plausible-looking numbers has no way to
     * tell which ones they meant to type. [StockBulkImporter] skips an empty cell,
     * so a sheet where only the delivered lines are filled in is the normal case
     * rather than an incomplete one.
     */
    fun content(context: Context): String {
        val items = runCatching {
            StockDao(context).items(SessionManager.currentUser?.storeId ?: 0)
        }.getOrDefault(emptyList())

        val out = StringBuilder()
        out.append(header.joinToString(",")).append('\n')
        items.forEach {
            out.append(field(it.productId.toString())).append(',')
                .append(field(it.name)).append(",\n")
        }
        return out.toString()
    }

    /**
     * One cell, quoted where it has to be - the same rule [ProductCsvExport] applies.
     *
     * An item named "Rice, Basmati" would otherwise split across two columns and
     * push the quantity into a third that nothing reads.
     */
    private fun field(value: String?): String {
        val v = value.orEmpty()
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }
    }
}
