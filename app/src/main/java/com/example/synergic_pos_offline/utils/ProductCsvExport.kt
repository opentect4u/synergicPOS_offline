package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.DatabaseHelper

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
    private const val SQL = """
        SELECT p.product_name, c.category_name, p.hsn_code, p.bar_code,
               r.rate_name, r.rate, u.unit_symbol, r.cgst_rate, r.sgst_rate,
               r.discount, r.discount_type, COALESCE(r.sell_price, r.sale_price), r.purchase_price
        FROM %s p
        LEFT JOIN %s r ON r.product_id = p.id
        LEFT JOIN %s c ON c.id = p.category_id
        LEFT JOIN %s u ON u.id = r.unit_id
        ORDER BY p.id ASC, r.id ASC
    """

    /** The whole product master as CSV, header included. */
    fun content(context: Context): String {
        val sql = SQL.format(
            DatabaseHelper.Tables.MD_PRODUCTS,
            DatabaseHelper.Tables.MD_PRODUCT_RATES,
            DatabaseHelper.Tables.MD_CATEGORY,
            DatabaseHelper.Tables.MD_UNITS
        )
        val out = StringBuilder(ProductCsvTemplate.header.joinToString(",")).append("\n")
        DatabaseHelper.getInstance(context).readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val cells = (0 until c.columnCount).map { field(c.getString(it)) }
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
