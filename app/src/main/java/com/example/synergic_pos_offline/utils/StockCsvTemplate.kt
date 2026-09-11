package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.StockDao

/**
 * The Stock In upload sheet: the till's own item ids and names, and a column to
 * write the quantity received against each.
 */
object StockCsvTemplate {

    /** What the downloaded file is called, as a CSV. */
    const val FILE_NAME = "stock_in_template.csv"

    /**
     * What the download actually hands over now - a WORKBOOK.
     *
     * The sheet is filled in on a computer, and a CSV opened there is a file of
     * guesses: the app decides which delimiter was meant and what to do with an item
     * named "Rice, Basmati". A workbook has cells, so a name stays a name and a
     * quantity stays a number. The product template moved for the same reason.
     *
     * CSV is still READ on the way back in - see StockListFragment.
     */
    const val EXCEL_FILE_NAME = "stock_in_template.xlsx"

    /** The item's own id, exactly as the till holds it - what a row is matched by. */
    const val ID_COLUMN = "product_id"

    /** The item's name, exactly as the till holds it. Not to be edited. */
    const val NAME_COLUMN = "product_name"

    /** The quantity being received, added to whatever the item already holds. */
    const val STOCK_COLUMN = "stock"

    val header = listOf(ID_COLUMN, NAME_COLUMN, STOCK_COLUMN)

    /**
     * The sheet to hand the operator: every item the till knows, one per line, with
     * the quantity column left empty.
     */
    fun rows(context: Context): List<List<String>> {
        val items = runCatching {
            StockDao(context).items(SessionManager.currentUser?.storeId ?: 0)
        }.getOrDefault(emptyList())
        return listOf(header) + items.map { listOf(it.productId.toString(), it.name, "") }
    }

    /**
     * The same sheet as a CSV.
     */
    fun content(context: Context): String = buildString {
        rows(context).forEach { cells ->
            append(cells.joinToString(",") { field(it) })
            append('\n')
        }
    }

    /**
     * One cell, quoted where it has to be.
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
