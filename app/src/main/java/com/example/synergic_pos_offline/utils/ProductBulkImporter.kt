package com.example.synergic_pos_offline.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.database.StockDao

/**
 * Writes the rows of a bulk-upload sheet into the product master.
 *
 * Each row becomes a product plus its default rate, as the Add-Product popup would
 * have created them one at a time.
 *
 * The sheet names its category and unit in words - "Dairy", "Ltr" - because that is
 * what the person filling it in knows; the ids they are stored under are this
 * database's own business. Resolving those names, and inventing the master records
 * for the ones this till has never seen, is what this does that a plain insert
 * would not.
 *
 * Kept out of the upload screen so it can be tested against a real database: it
 * writes to the masters, and a mistake here is not one an operator can easily undo.
 */
object ProductBulkImporter {

    /** md_product_rates.discount_type for a percentage discount. */
    private const val DISCOUNT_TYPE_PERCENT = "P"

    /** What an import does with the products already on the till. */
    enum class Mode {
        /** Leaves them alone and adds the sheet's rows alongside. */
        APPEND,

        /**
         * Clears the product master first, so the till ends up holding the sheet and
         * nothing else - except the products it is not allowed to forget, see
         * [replaceCounts].
         */
        REPLACE
    }

    /** How an import went: [imported] rows written, [skipped] rows that could not be. */
    data class Result(val imported: Int, val skipped: Int, val removed: Int = 0)

    /**
     * Every product id that some transaction still refers to.
     *
     * A product that has been sold, returned, purchased, written off, counted in a
     * stock movement or sent to a kitchen is part of the record of what happened. It
     * cannot be deleted without either breaking that record or taking it down too,
     * so Replace leaves those products where they are - it clears the catalogue, not
     * the books.
     *
     * A product whose *batch* is on a transaction counts as in use for the same
     * reason: the batch cannot go, and a batch cannot outlive its product.
     *
     * Every branch excludes nulls deliberately. `id NOT IN (…)` is never true once
     * the list contains one, so a single null would quietly protect every product on
     * the till and Replace would delete nothing at all.
     */
    private val SQL_PRODUCTS_IN_USE = """
        SELECT product_id FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} WHERE product_id IS NOT NULL
        UNION SELECT product_id FROM ${DatabaseHelper.Tables.TD_RETURN_ITEMS} WHERE product_id IS NOT NULL
        UNION SELECT product_id FROM ${DatabaseHelper.Tables.TD_PURCHASE} WHERE product_id IS NOT NULL
        UNION SELECT prod_id FROM ${DatabaseHelper.Tables.TD_WRITE_OFF} WHERE prod_id IS NOT NULL
        UNION SELECT product_id FROM ${DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS} WHERE product_id IS NOT NULL
        UNION SELECT product_id FROM ${DatabaseHelper.Tables.TD_KOT_ITEMS} WHERE product_id IS NOT NULL
        UNION SELECT b.product_id FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK} b
               WHERE b.product_id IS NOT NULL AND (
                   b.id IN (SELECT batch_id FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} WHERE batch_id IS NOT NULL)
                OR b.id IN (SELECT batch_id FROM ${DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS} WHERE batch_id IS NOT NULL)
               )
    """.trimIndent()

    /** What a Replace would do: [removable] products cleared, [kept] held back. */
    data class ReplaceCounts(val total: Int, val removable: Int, val kept: Int)

    /**
     * How many products a Replace would clear, and how many it would have to keep.
     *
     * Read before the operator confirms, so the warning states what will actually
     * happen on this till rather than promising a clean sweep it cannot deliver.
     */
    fun replaceCounts(context: Context): ReplaceCounts {
        val db = DatabaseHelper.getInstance(context).readableDatabase
        fun count(sql: String): Int = db.rawQuery(sql, null).use { c -> c.moveToFirst(); c.getInt(0) }
        val total = count("SELECT count(*) FROM ${DatabaseHelper.Tables.MD_PRODUCTS}")
        val kept = count(
            "SELECT count(*) FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE id IN ($SQL_PRODUCTS_IN_USE)"
        )
        return ReplaceCounts(total = total, removable = total - kept, kept = kept)
    }

    /**
     * Clears every product the till is free to forget, with its rates and its stock.
     *
     * Runs inside the caller's transaction, before the new rows go in, so a sheet
     * that fails to import leaves the old catalogue standing rather than wiping it
     * and putting nothing in its place.
     *
     * @return how many products were removed
     */
    private fun clearProducts(db: SQLiteDatabase): Int {
        val doomed = "SELECT id FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE id NOT IN ($SQL_PRODUCTS_IN_USE)"
        // Children first: both point at md_products, and foreign keys are enforced.
        db.execSQL("DELETE FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES} WHERE product_id IN ($doomed)")
        db.execSQL("DELETE FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK} WHERE product_id IN ($doomed)")
        val removed = db.rawQuery("SELECT count(*) FROM ($doomed)", null)
            .use { c -> c.moveToFirst(); c.getInt(0) }
        db.execSQL("DELETE FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE id NOT IN ($SQL_PRODUCTS_IN_USE)")
        restartIdsIfEmptied(db)
        return removed
    }

    /**
     * Puts the id counter back to the start, so the first product of a fresh
     * catalogue is 1 rather than carrying on from whatever the old one reached.
     *
     * `AUTOINCREMENT` keeps a high-water mark in `sqlite_sequence` precisely so a
     * deleted id is never handed out again. That is the right default - it stops an
     * old reference silently pointing at a new record - but on a Replace it means a
     * till that has imported a catalogue three times starts its serial numbers in the
     * thousands, and the operator reads the product master as their own numbering.
     *
     * Only done for a table that is now completely empty. With even one product left
     * - one kept because it has been sold - restarting the count would walk the new
     * ids straight into the surviving one, and the insert would fail on its primary
     * key. Emptiness is checked per table rather than assumed from the other, since
     * a kept product keeps its rates too.
     */
    private fun restartIdsIfEmptied(db: SQLiteDatabase) {
        listOf(DatabaseHelper.Tables.MD_PRODUCTS, DatabaseHelper.Tables.MD_PRODUCT_RATES)
            .forEach { table ->
                val empty = db.rawQuery("SELECT count(*) FROM $table", null)
                    .use { c -> c.moveToFirst(); c.getInt(0) } == 0
                // sqlite_sequence only exists once something in the database has used
                // AUTOINCREMENT, and holds no row for a table that has never had one.
                if (empty) {
                    runCatching {
                        db.execSQL("DELETE FROM sqlite_sequence WHERE name = ?", arrayOf(table))
                    }
                }
            }
    }

    /**
     * Imports [rows], returning how many were written.
     *
     * Every row's category and unit come from the row itself. A row naming no
     * category imports uncategorised: the upload is the whole catalogue and there is
     * nothing sensible to file it under instead - guessing would put products in a
     * category the sheet never asked for, which is worse than leaving it blank and
     * visible.
     *
     * [mode] decides what happens to the products already on the till:
     * [Mode.APPEND] leaves them, [Mode.REPLACE] clears them first - see
     * [clearProducts] for what "clears" can and cannot reach.
     *
     * A row's `stock` column opens that product's count, but only on a till that
     * tracks stock; with Stock off the column is read past and nothing is written.
     * The setting, not the sheet, decides whether this till counts anything - so a
     * file filled in while Stock was on cannot start creating batches on a till
     * where the stock screens are not even reachable, and the operator would have no
     * way to see, correct or spend what it had booked in.
     *
     * The whole sheet goes in one transaction - the clearing, the products, the
     * rates, the opening stock, and any category or unit invented along the way - so
     * a failure part-way leaves the masters as they were rather than half-populated
     * with categories for products that never landed, stock against products that
     * did not, or emptied with nothing put back.
     */
    fun import(
        context: Context,
        rows: List<Map<String, String>>,
        mode: Mode = Mode.APPEND
    ): Result {
        val db = DatabaseHelper.getInstance(context).writableDatabase
        val (storeId, outletId) = storeAndOutlet(context)
        // Resolved once per name rather than per row: a sheet of 500 products holds
        // a handful of categories between them, and looking each up again would be
        // 500 queries to learn the same ten answers. It also stops ten rows of one
        // new category from creating ten copies of it.
        val categoryIds = HashMap<String, Int?>()
        val unitIds = HashMap<String, Int?>()
        // Asked once for the sheet, not once per row: the setting cannot change
        // half way through an import, and every row has to be treated the same way
        // whichever half of the file it is in.
        val stockDao = if (GeneralSettingsDao.isStockEnabled(context)) StockDao(context) else null
        var imported = 0
        var skipped = 0
        var removed = 0

        db.beginTransaction()
        try {
            if (mode == Mode.REPLACE) removed = clearProducts(db)
            for (r in rows) {
                val name = (r["product_name"] ?: r["item_name"]).orEmpty().trim()
                if (name.isBlank()) { skipped++; continue }

                val categoryId = r["category"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { categoryIdFor(db, it, storeId, categoryIds) }

                val product = ContentValues().apply {
                    if (storeId != null) put("store_id", storeId) else putNull("store_id")
                    put("product_name", name)
                    put("hsn_code", r["hsn_code"]?.ifBlank { null })
                    put("bar_code", r["bar_code"]?.ifBlank { null })
                    if (categoryId != null) put("category_id", categoryId) else putNull("category_id")
                }
                val productId = db.insert(DatabaseHelper.Tables.MD_PRODUCTS, null, product)
                if (productId == -1L) { skipped++; continue }

                // The two figures are read from their own columns and neither stands
                // in for the other: the rate is what the product is rated at, the
                // selling price is what it sells for, and they are different numbers
                // that happen to coincide on an untaxed, undiscounted line. Filling
                // one from the other would quietly invent a price the sheet never
                // gave, which is worse than a blank the operator can see and correct.
                val rateValue = (r["rate"] ?: r["price"])?.toDoubleOrNull() ?: 0.0
                // "selling_price" is the current template's column; the older names
                // are still read so a sheet filled in against a previous template
                // imports - they are other spellings of this column, not other
                // columns to fall back on.
                val sell = r["selling_price"]?.toDoubleOrNull()
                    ?: r["sell_price"]?.toDoubleOrNull()
                    ?: r["sale_price"]?.toDoubleOrNull()
                    ?: 0.0
                val unitId = unitNameOf(r)?.let { unitIdFor(db, it, storeId, unitIds) }

                val rate = ContentValues().apply {
                    if (storeId != null) put("store_id", storeId) else putNull("store_id")
                    if (outletId != null) put("outlet_id", outletId) else putNull("outlet_id")
                    put("product_id", productId)
                    put("rate_name", r["rate_name"]?.ifBlank { null })
                    put("rate", rateValue)
                    if (unitId != null) put("unit_id", unitId) else putNull("unit_id")
                    put("cgst_rate", r["cgst"]?.toDoubleOrNull() ?: 0.0)
                    put("sgst_rate", r["sgst"]?.toDoubleOrNull() ?: 0.0)
                    put("igst_rate", 0.0)
                    put("vat_rate", 0.0)
                    put("discount", r["discount"]?.toDoubleOrNull() ?: 0.0)
                    // A bulk-uploaded discount is always a percentage, whatever the
                    // sheet's discount_type column says: the figures in the template
                    // are percentages, and reading a "5" meant as 5% as five rupees
                    // off would misprice the product with nothing to show for it.
                    put("discount_type", DISCOUNT_TYPE_PERCENT)
                    put("sale_price", sell)
                    put("sell_price", sell)
                    put("purchase_price", r["purchase_price"]?.toDoubleOrNull() ?: 0.0)
                }
                val rateId = db.insert(DatabaseHelper.Tables.MD_PRODUCT_RATES, null, rate)
                if (rateId != -1L) {
                    db.execSQL(
                        "UPDATE ${DatabaseHelper.Tables.MD_PRODUCT_RATES} SET \"default\" = 1 WHERE id = ?",
                        arrayOf<Any>(rateId)
                    )
                }

                // The quantity the sheet says this item starts at, booked in exactly
                // as the Add Product form's own opening stock is, and on the same
                // transaction the product itself went in on.
                stockDao?.let { dao ->
                    openingStockOf(r)?.let { dao.recordOpening(db, productId, it, storeId, outletId) }
                }
                imported++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return Result(imported, skipped, removed)
    }

    /**
     * The id of the category called [name], adding it to the category master first
     * if this till has never seen that name.
     *
     * Matched without regard to case, so "dairy" in one row and "Dairy" in the next
     * are the one category rather than two that read alike. Not scoped to a store,
     * matching the categories the upload page's own dropdown lists.
     */
    private fun categoryIdFor(
        db: SQLiteDatabase,
        name: String,
        storeId: Int?,
        cache: MutableMap<String, Int?>
    ): Int? = cache.getOrPut(name.lowercase()) {
        db.rawQuery(
            "SELECT id FROM ${DatabaseHelper.Tables.MD_CATEGORY} " +
                "WHERE category_name = ? COLLATE NOCASE LIMIT 1",
            arrayOf(name)
        ).use { c -> if (c.moveToFirst()) return@getOrPut c.getInt(0) }

        val values = ContentValues().apply {
            if (storeId != null) put("store_id", storeId) else putNull("store_id")
            put("category_name", name)
            put("created_by", currentUserId())
        }
        db.insert(DatabaseHelper.Tables.MD_CATEGORY, null, values)
            .takeIf { it != -1L }?.toInt()
    }

    /**
     * The column headings a sheet may name its unit under.
     *
     * The template calls it `unit_id`, which is a misleading name for a column
     * holding "Ltr" - it is kept only so a sheet filled in against an older template
     * still imports. Someone writing their own sheet reasonably heads it `unit`, and
     * a column named for what it holds should not be the one that fails to import.
     */
    private val UNIT_COLUMNS = listOf("unit_id", "unit", "unit_name", "unit_symbol")

    /** The unit a row names, under whichever of [UNIT_COLUMNS] it used. */
    fun unitNameOf(row: Map<String, String>): String? = UNIT_COLUMNS
        .firstNotNullOfOrNull { row[it]?.trim()?.takeIf { name -> name.isNotEmpty() } }

    /**
     * The column headings a sheet may give its opening stock under.
     *
     * [ProductCsvTemplate.STOCK_COLUMN] is what the download hands over; the longer
     * spellings are what someone writing their own sheet is likely to head the
     * column, and a quantity should not be dropped over which of them they chose.
     */
    private val STOCK_COLUMNS = listOf(ProductCsvTemplate.STOCK_COLUMN, "opening_stock", "stock_qty")

    /**
     * The opening quantity a row declares, or null where it declares none.
     *
     * Null for a blank cell and null for text that is not a number: a row that says
     * nothing about stock is a product with no stock yet, not one opening at zero,
     * and inventing a batch for it would put a line on the stock screen that the
     * sheet never asked for. A negative quantity is refused for the same reason - a
     * count cannot open below empty, and reading "-5" as five in hand would be worse
     * than ignoring it.
     */
    fun openingStockOf(row: Map<String, String>): Double? = STOCK_COLUMNS
        .firstNotNullOfOrNull { row[it]?.trim()?.takeIf { value -> value.isNotEmpty() } }
        ?.toDoubleOrNull()
        ?.takeIf { it >= 0.0 }

    /**
     * The id of the unit written as [symbol] - "Ltr", "PCS", "KG" - adding it to the
     * unit master first if this till has never seen it.
     *
     * Matched on either the symbol or the full name, since a sheet may spell out
     * "Kilogram" where the master holds "KG". A unit created here takes the sheet's
     * own text for both, and is marked whole rather than fractional: nothing in a
     * name says whether halves of it can be sold, and that is a question for the
     * Unit master, where the operator can answer it.
     */
    private fun unitIdFor(
        db: SQLiteDatabase,
        symbol: String,
        storeId: Int?,
        cache: MutableMap<String, Int?>
    ): Int? = cache.getOrPut(symbol.lowercase()) {
        db.rawQuery(
            "SELECT id FROM ${DatabaseHelper.Tables.MD_UNITS} " +
                "WHERE unit_symbol = ? COLLATE NOCASE OR unit_name = ? COLLATE NOCASE LIMIT 1",
            arrayOf(symbol, symbol)
        ).use { c -> if (c.moveToFirst()) return@getOrPut c.getInt(0) }

        val values = ContentValues().apply {
            if (storeId != null) put("store_id", storeId) else putNull("store_id")
            put("unit_name", symbol)
            put("unit_symbol", symbol)
            put("fraction_flag", 0)
            put("created_by", currentUserId())
        }
        db.insert(DatabaseHelper.Tables.MD_UNITS, null, values)
            .takeIf { it != -1L }?.toInt()
    }

    /** Every category name on the master, lower-cased for comparison. */
    fun knownCategoryNames(context: Context): Set<String> {
        val names = mutableSetOf<String>()
        DatabaseHelper.getInstance(context).readableDatabase.query(
            DatabaseHelper.Tables.MD_CATEGORY, arrayOf("category_name"),
            null, null, null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { names.add(it.lowercase()) }
            }
        }
        return names
    }

    /** Every unit name and symbol on the master, lower-cased for comparison. */
    fun knownUnitNames(context: Context): Set<String> {
        val names = mutableSetOf<String>()
        DatabaseHelper.getInstance(context).readableDatabase.query(
            DatabaseHelper.Tables.MD_UNITS, arrayOf("unit_name", "unit_symbol"),
            null, null, null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { names.add(it.lowercase()) }
                c.getString(1)?.takeIf { it.isNotBlank() }?.let { names.add(it.lowercase()) }
            }
        }
        return names
    }

    private fun currentUserId(): String? = SessionManager.auditUser

    /** store_id (signed-in user's store) + outlet_id, so uploads land in the list. */
    private fun storeAndOutlet(context: Context): Pair<Int?, Int?> {
        val db = DatabaseHelper.getInstance(context).readableDatabase
        val sessionStore = SessionManager.currentUser?.storeId?.takeIf { it != 0 }
        if (sessionStore != null) {
            val outlet = db.rawQuery(
                "SELECT outlet_id FROM ${DatabaseHelper.Tables.MD_REGISTRATION} WHERE store_id = ? LIMIT 1",
                arrayOf(sessionStore.toString())
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }
            return sessionStore to outlet
        }
        db.rawQuery(
            "SELECT store_id, outlet_id FROM ${DatabaseHelper.Tables.MD_REGISTRATION} " +
                "ORDER BY verify_flag DESC, store_id ASC LIMIT 1", null
        ).use { c ->
            if (c.moveToFirst()) {
                val s = if (c.isNull(0)) null else c.getInt(0)
                val o = if (c.isNull(1)) null else c.getInt(1)
                return s to o
            }
        }
        return null to null
    }
}
