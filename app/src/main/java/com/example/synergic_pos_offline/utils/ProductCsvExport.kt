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
     * One row per RATE, which [rows] then folds into one row per product.
     *
     * The sheet gives a product four unit/rate slots on a single line, so the shape
     * the database holds - a rate per row - and the shape the sheet wants are not the
     * same shape. Reading rates and folding them is done rather than pivoting in SQL
     * because the fold has a rule ("the fifth rate does not fit") that is worth saying
     * in words, and a pivot of four correlated subqueries says nothing at all.
     *
     * A product with no rate at all still comes back, on the outer join, and still
     * gets a line: it exists, and leaving it out of its own catalogue would quietly
     * delete it on the next Replace upload.
     */
    private fun sql(): String {
        val products = DatabaseHelper.Tables.MD_PRODUCTS
        val rates = DatabaseHelper.Tables.MD_PRODUCT_RATES
        val units = DatabaseHelper.Tables.MD_UNITS
        val batches = DatabaseHelper.Tables.MD_BATCH_STOCK

        return """
            SELECT p.id AS product_id, p.category_id, p.product_name, p.hsn_code, p.bar_code,
                   r.rate, u.unit_symbol,
                   r.cgst_rate, r.sgst_rate, r.igst_rate, r.vat_rate,
                   r.discount, COALESCE(r.sell_price, r.sale_price) AS selling_price,
                   r.purchase_price,
                   (SELECT COALESCE(SUM(s.current_quantity), 0) FROM $batches s
                     WHERE s.product_id = p.id) AS stock,
                   -- The shop's own name for this product in the language the till is
                   -- on, so a downloaded catalogue comes back with the names already
                   -- in it rather than blank for every row that has one. Bound rather
                   -- than inlined; the code is an enum's, but this is still a query.
                   (SELECT n.regional_name FROM ${DatabaseHelper.Tables.MD_PRODUCT_NAMES} n
                     WHERE n.product_id = p.id AND n.lang_code = ?) AS regional_name
            FROM $products p
            LEFT JOIN $rates r ON r.product_id = p.id
            LEFT JOIN $units u ON u.id = r.unit_id
            ORDER BY p.id ASC, r."default" DESC, r.id ASC
        """.trimIndent()
    }

    /** The whole product master as CSV, header included. */
    fun content(context: Context): String =
        rows(context).joinToString("\n") { cells -> cells.joinToString(",") { field(it) } } + "\n"

    /** What the downloaded workbook is called. */
    const val EXCEL_FILE_NAME = "product_master.xlsx"

    /**
     * The catalogue as rows of plain cells - the heading row, then a row per PRODUCT.
     *
     * The one reading of the sheet, with [content] quoting it for CSV and [Xlsx]
     * writing it into a workbook. A cell here is the VALUE, unquoted: a workbook has
     * no delimiter to defend against, and quoting for one would put literal quotation
     * marks into the cell.
     *
     * A product's rates are folded into its `UNIT_n`/`RATE_n` slots, default first, so
     * the file that comes out is the file the upload reads back - export, edit in a
     * spreadsheet, upload. Past [ProductCsvTemplate.RATE_SLOTS] there is nowhere on the
     * sheet to put a rate, and the extra ones are left off rather than spilling into
     * another line: a second line for the same product would upload as a second
     * product. That is a real edge, so it is said plainly here - a product sold five
     * ways exports its first four.
     *
     * Two columns are per-FILE rather than per-product, and both are written on the
     * first row only. Stock, because the upload reads a row as a product and a
     * repeated count would book the quantity twice; and
     * [ProductCsvTemplate.REGIONAL_LANGUAGE_COLUMN], which carries the till's current
     * screen language so re-uploading an unedited export leaves that setting as it
     * found it rather than clearing it.
     *
     * The stock column is emptied entirely on a till that does not track stock: the
     * column is part of the sheet, but a figure under it there would be a count
     * nothing on this till keeps.
     */
    fun rows(context: Context): List<List<String>> {
        val withStock = GeneralSettingsDao.isStockEnabled(context)
        val out = mutableListOf(ProductCsvTemplate.columns(context))
        val slots = ProductCsvTemplate.RATE_SLOTS
        val units = ProductCsvTemplate.header.indexOf("UNIT_1")
        DatabaseHelper.getInstance(context).readableDatabase
            .rawQuery(sql(), arrayOf(AppLanguage.of(context).code)).use { c ->
                fun text(name: String): String =
                    c.getColumnIndex(name).let { if (it < 0 || c.isNull(it)) "" else c.getString(it) }

                // Every figure is written as a person would write it. The raw column
                // hands over "220.0" and "2.5" as "2.5", and a sheet where every rate
                // and every tax carries a pointless ".0" is one the operator sets
                // about "fixing" line by line.
                fun num(name: String): String =
                    c.getColumnIndex(name).let {
                        if (it < 0 || c.isNull(it)) "" else StockDao.trim(c.getDouble(it))
                    }

                var currentId = -1L
                // The row being built, held open while the product's further rates
                // arrive so each can be dropped into its own slot.
                var line: MutableList<String>? = null
                var filled = 0
                var firstProduct = true
                while (c.moveToNext()) {
                    val id = c.getLong(c.getColumnIndexOrThrow("product_id"))
                    if (line == null || id != currentId) {
                        currentId = id
                        filled = 0
                        firstProduct = line == null
                        // A quantity is written as a person would write it: the raw
                        // column hands over "12.0", and a sheet full of those invites
                        // the operator to "fix" every line.
                        line = (
                            listOf(
                                id.toString(), text("product_name"),
                                text("regional_name"), text("category_id")
                            ) +
                                List(slots * 2) { "" } +
                                listOf(
                                    num("sgst_rate"), num("cgst_rate"),
                                    num("igst_rate"), num("vat_rate"),
                                    num("discount"), if (withStock) num("stock") else "",
                                    text("hsn_code"), text("bar_code"),
                                    num("purchase_price"), num("selling_price"),
                                    if (firstProduct) AppLanguage.of(context).englishName else ""
                                )
                            ).toMutableList()
                        out.add(line)
                    }
                    // The unit and rate columns sit as two blocks of four, not four
                    // pairs - UNIT_1..4 then RATE_1..4 - so slot n is at units + n and
                    // units + slots + n. Where the blocks START is read off the header
                    // rather than counted here, so a column added ahead of them moves
                    // both without this having to be found and corrected.
                    if (filled < slots && c.getColumnIndex("rate") >= 0 && !c.isNull(c.getColumnIndexOrThrow("rate"))) {
                        line[units + filled] = text("unit_symbol")
                        line[units + slots + filled] = num("rate")
                        filled++
                    }
                }
            }
        return out
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
