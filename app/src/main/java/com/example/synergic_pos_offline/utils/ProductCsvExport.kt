package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.database.StockDao

/**
 * Writes the product table out as CSV: what the Download button on the Products
 * screen produces.
 *
 * The catalogue is spread over four tables - the product, its rate, the category it
 * belongs to and the unit it sells in - and the operator wants it as one sheet, so
 * they are joined back together here.
 *
 * It is exported in exactly the [ProductCsvTemplate] format, which makes the
 * download and the bulk upload two halves of the same round trip: export the
 * catalogue, edit it in a spreadsheet, upload it back. Category and unit go out as
 * the names they were resolved from rather than the ids they are stored under,
 * because those are what the upload reads back and what a person can actually edit.
 */
object ProductCsvExport {

    /** What the exported file is called. */
    const val FILE_NAME = "product_master.csv"

    /**
     * One row per rate, not per product.
     *
     * A product sold at more than one rate is more than one sellable line, and
     * collapsing them would lose every rate but one. A product with no rate at all
     * still gets a row - it exists, and leaving it out of its own catalogue would
     * quietly delete it on the next Replace upload.
     */
    /**
     * The catalogue joined back together, one row per rate.
     *
     * [withStock] adds the quantity on hand as a last column, matching
     * [ProductCsvTemplate.STOCK_COLUMN] - so what comes out of a till that tracks
     * stock is a sheet the operator can correct the counts on and upload back.
     *
     * That figure is put against a product's *first* rate row only, and left blank
     * on any further one. The upload reads a row as a product, so a product sold at
     * two rates comes back as two products; repeating its count on both rows would
     * have the till end up holding twice the stock the sheet said it had.
     */
    private fun sql(withStock: Boolean): String {
        val products = DatabaseHelper.Tables.MD_PRODUCTS
        val rates = DatabaseHelper.Tables.MD_PRODUCT_RATES
        val categories = DatabaseHelper.Tables.MD_CATEGORY
        val units = DatabaseHelper.Tables.MD_UNITS
        val batches = DatabaseHelper.Tables.MD_BATCH_STOCK

        val stockCell = if (!withStock) "" else """
            , CASE WHEN r.id IS NULL
                        OR r.id = (SELECT MIN(r2.id) FROM $rates r2 WHERE r2.product_id = p.id)
                   THEN (SELECT COALESCE(SUM(s.current_quantity), 0) FROM $batches s
                          WHERE s.product_id = p.id)
                   ELSE NULL END
        """.trimIndent()

        return """
            SELECT p.product_name, c.category_name, p.hsn_code, p.bar_code,
                   r.rate_name, r.rate, u.unit_symbol, r.cgst_rate, r.sgst_rate,
                   r.discount, r.discount_type, COALESCE(r.sell_price, r.sale_price), r.purchase_price
                   $stockCell
            FROM $products p
            LEFT JOIN $rates r ON r.product_id = p.id
            LEFT JOIN $categories c ON c.id = p.category_id
            LEFT JOIN $units u ON u.id = r.unit_id
            ORDER BY p.id ASC, r.id ASC
        """.trimIndent()
    }

    /** The whole product master as CSV, header included. */
    fun content(context: Context): String {
        val withStock = GeneralSettingsDao.isStockEnabled(context)
        val columns = ProductCsvTemplate.columns(context)
        val out = StringBuilder(columns.joinToString(",")).append("\n")
        DatabaseHelper.getInstance(context).readableDatabase.rawQuery(sql(withStock), null).use { c ->
            val stockIndex = if (withStock) c.columnCount - 1 else -1
            while (c.moveToNext()) {
                val cells = (0 until c.columnCount).map { i ->
                    // A quantity is read as a number and written as one a person
                    // would: the raw column would hand over "12.0", and a sheet full
                    // of those invites the operator to "fix" every line of it.
                    if (i == stockIndex) {
                        field(if (c.isNull(i)) "" else StockDao.trim(c.getDouble(i)))
                    } else {
                        field(c.getString(i))
                    }
                }
                out.append(cells.joinToString(",")).append("\n")
            }
        }
        return out.toString()
    }

    /**
     * One cell, quoted where it has to be.
     *
     * A product named "Rice, Basmati" would otherwise split into two columns and
     * shift every figure on the line one place left - the kind of damage that is
     * only noticed after the sheet has been uploaded back.
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
