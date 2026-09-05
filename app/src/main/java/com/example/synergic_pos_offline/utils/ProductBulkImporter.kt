package com.example.synergic_pos_offline.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.database.ProductNameDao
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

    /**
     * How an import went: [imported] rows written, [skipped] rows that could not
     * be, [removed] products a Replace cleared first.
     *
     * [languageApplied] is what the till's screen language was set to - always
     * something, since [regionalLanguageOf] resolves a blank column to English
     * rather than leaving the setting as it found it. [languageWarning] is set only
     * where that resolution was not a plain, exact read of the sheet - see
     * [regionalLanguageOf].
     */
    data class Result(
        val imported: Int,
        val skipped: Int,
        val removed: Int = 0,
        val languageApplied: String = PrintLanguage.Language.ENGLISH.englishName,
        val languageWarning: String? = null,
        /** Codes or rate ids the sheet named that this till has no row for. */
        val referenceWarning: String? = null
    )

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
        // Codes and rate ids resolve to the same handful of rows across a whole sheet,
        // same as the name caches above - and a miss is cached too, so a sheet naming
        // one wrong code on 400 rows asks the database about it once.
        val categoryCodeIds = HashMap<String, Int?>()
        val rateNames = HashMap<Long, String?>()
        var unknownCategoryCodes = 0
        var unknownRateNameIds = 0
        // Asked once for the sheet, not once per row: the setting cannot change
        // half way through an import, and every row has to be treated the same way
        // whichever half of the file it is in.
        val stockDao = if (GeneralSettingsDao.isStockEnabled(context)) StockDao(context) else null
        var imported = 0
        var skipped = 0
        var removed = 0

        val languageMatch = regionalLanguageOf(rows)
        // The sheet's regional names are filed under the language the sheet named, and
        // only when it named one that can HAVE regional names. English is the absence
        // of a regional name rather than a language to file one under - RegionalName.map
        // does not even query in English - so a name given without a language would be
        // written somewhere nothing would ever read it. That is warned about below
        // instead of being written and silently ignored.
        val namesLanguage = languageMatch.language.takeIf { ProductName.applies(it) }
        val nameDao = if (namesLanguage != null) ProductNameDao(context) else null
        val regionalNamesGiven = rows.any {
            !it[ProductCsvTemplate.REGIONAL_NAME_COLUMN].isNullOrBlank()
        }

        db.beginTransaction()
        try {
            if (mode == Mode.REPLACE) removed = clearProducts(db)
            for (r in rows) {
                val name = (r["product_name"] ?: r["item_name"]).orEmpty().trim()
                if (name.isBlank()) { skipped++; continue }

                // BY CODE FIRST - "DEPT007" names the department master's row, and
                // names it exactly. The older `category` heading is still read for a
                // sheet that has one, and still creates the department it names; a
                // code cannot do that, since a code IS the row id and there is no
                // such thing as inventing one. An unknown code leaves the product
                // uncategorised and is counted for the report at the end.
                val codeCell = r[ProductCsvTemplate.CATEGORY_CODE_COLUMN]?.trim().orEmpty()
                val categoryId = if (codeCell.isNotEmpty()) {
                    categoryIdForCode(db, codeCell, categoryCodeIds)
                        .also { if (it == null) unknownCategoryCodes++ }
                } else {
                    r["category"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { categoryIdFor(db, it, storeId, categoryIds) }
                }

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

                // BY ID FIRST, and the NAME is read off the master rather than off
                // the sheet - so a rate uploaded in bulk is the same rate the
                // Add/Edit form would have picked, joined to the same master row.
                // md_product_rates has carried rate_name_id all along and the import
                // never filled it; a bulk-uploaded rate held loose text linked to
                // nothing. The older `rate_name` heading still works and is still
                // taken as the name it says, with no id to link.
                val rateNameCell = r[ProductCsvTemplate.RATE_NAME_ID_COLUMN]?.trim().orEmpty()
                val rateNameId = rateNameCell.toLongOrNull()
                    ?.let { id -> rateNameFor(db, id, rateNames)?.let { id } }
                if (rateNameCell.isNotEmpty() && rateNameId == null) unknownRateNameIds++
                val rateNameText = rateNameId?.let { rateNames[it] }
                    ?: r["rate_name"]?.ifBlank { null }
                val rate = ContentValues().apply {
                    if (storeId != null) put("store_id", storeId) else putNull("store_id")
                    if (outletId != null) put("outlet_id", outletId) else putNull("outlet_id")
                    put("product_id", productId)
                    put("rate_name", rateNameText)
                    if (rateNameId != null) put("rate_name_id", rateNameId) else putNull("rate_name_id")
                    put("rate", rateValue)
                    if (unitId != null) put("unit_id", unitId) else putNull("unit_id")
                    put("cgst_rate", r["cgst"]?.toDoubleOrNull() ?: 0.0)
                    put("sgst_rate", r["sgst"]?.toDoubleOrNull() ?: 0.0)
                    put("igst_rate", r["igst"]?.toDoubleOrNull() ?: 0.0)
                    put("vat_rate", r["vat"]?.toDoubleOrNull() ?: 0.0)
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
                // The shop's own name for this product, in the language the sheet
                // named - through the same DAO the Add/Edit form writes through, so a
                // bulk-uploaded name and a typed one are the same row in the same
                // table. A blank cell writes nothing and the product falls back to the
                // machine translation, exactly as it did before this column existed.
                if (nameDao != null && namesLanguage != null) {
                    r[ProductCsvTemplate.REGIONAL_NAME_COLUMN]?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { nameDao.save(productId.toInt(), namesLanguage.code, it) }
                }
                imported++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // Applied only once the import itself has actually gone in - an exception
        // partway through the loop above reaches the caller through this same
        // function without a Result ever being built, and the till's screen
        // language should not change out from under a sheet that failed to import.
        android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(AppLanguage.SETTING_KEY, languageMatch.language.code).commit()
        // Rows whose code or id named nothing on this till. Reported rather than
        // silently absorbed: the product imported, but without the department or the
        // rate the sheet asked for, and only the operator can tell which is wrong -
        // the sheet, or a master that has not been set up yet.
        val referenceWarning = listOfNotNull(
            if (unknownCategoryCodes > 0)
                "$unknownCategoryCodes row(s) named a " +
                    "${ProductCsvTemplate.CATEGORY_CODE_COLUMN} this till has no department " +
                    "for - those products came in uncategorised."
            else null,
            if (unknownRateNameIds > 0)
                "$unknownRateNameIds row(s) named a " +
                    "${ProductCsvTemplate.RATE_NAME_ID_COLUMN} this till has no rate name " +
                    "for - those rates came in unnamed."
            else null
        ).joinToString(separator = "\n").takeIf { it.isNotEmpty() }
        val languageWarning = when {
            languageMatch.conflicting ->
                "The regional language column names more than one language - " +
                    "\"${languageMatch.raw}\" (the first) was applied. Fill every row with the " +
                    "same language, or leave the rest blank."
            languageMatch.raw.isNotEmpty() && !languageMatch.exact ->
                "\"${languageMatch.raw}\" does not spell a language exactly - " +
                    "${languageMatch.language.englishName} was applied to the app language instead."
            // Names with nothing to file them under. Said plainly rather than written
            // to a language that never reads them back: the operator filled the column
            // in and would otherwise see an import report success with no name saved.
            regionalNamesGiven && namesLanguage == null ->
                "The ${ProductCsvTemplate.REGIONAL_NAME_COLUMN} column was filled in but no " +
                    "regional language was named, so those names were not saved. Name the " +
                    "language in the ${ProductCsvTemplate.REGIONAL_LANGUAGE_COLUMN} column " +
                    "and upload again."
            else -> null
        }
        return Result(
            imported, skipped, removed,
            languageMatch.language.englishName, languageWarning, referenceWarning
        )
    }

    /**
     * The single language a bulk-upload sheet asks the app itself to run in - see
     * [ProductCsvTemplate.REGIONAL_LANGUAGE_COLUMN].
     *
     * Every row that names one is read, not just the first, because the column
     * stands for one till-wide setting and not a fact that can differ line to
     * line: a sheet is only really asking for one language when every filled cell
     * names the *same* one. Where they resolve to different languages, the first
     * row's is kept and [LanguageMatch.conflicting] says so, rather than the
     * import silently picking whichever one happened to be read last. A column
     * left entirely blank resolves to English outright, [LanguageMatch.raw] empty -
     * the language is always decided one way or another on an import, never left
     * as whatever the till already had, so a sheet that says nothing about it
     * cannot leave a till stuck in a language nobody chose for it this time.
     */
    fun regionalLanguageOf(rows: List<Map<String, String>>): LanguageMatch {
        val matches = rows.mapNotNull { r ->
            r[ProductCsvTemplate.REGIONAL_LANGUAGE_COLUMN]?.trim()?.takeIf { it.isNotEmpty() }
        }.map { raw -> raw to PrintLanguage.Language.nearest(raw)!! }

        val first = matches.firstOrNull()
            ?: return LanguageMatch("", PrintLanguage.Language.ENGLISH, exact = true, conflicting = false)
        val conflicting = matches.map { (_, m) -> m.first }.distinct().size > 1
        return LanguageMatch(first.first, first.second.first, first.second.second, conflicting)
    }

    /**
     * [raw] as the sheet wrote it on the row that decided it, matched to
     * [language] - exactly if [exact]. [conflicting] is true where some other row
     * named a different language rather than repeating this one or leaving its
     * cell blank.
     */
    data class LanguageMatch(
        val raw: String,
        val language: PrintLanguage.Language,
        val exact: Boolean,
        val conflicting: Boolean
    )

    /**
     * The id of the category called [name], adding it to the category master first
     * if this till has never seen that name.
     *
     * Matched without regard to case, so "dairy" in one row and "Dairy" in the next
     * are the one category rather than two that read alike. Not scoped to a store,
     * matching the categories the upload page's own dropdown lists.
     */
    /**
     * The department a Dept Code names, or null where this till has no such row.
     *
     * The code is [CategoryDao.formatCode] run backwards: "DEPT007" is row 7. Read
     * by taking the digits rather than by matching the prefix, so "dept7", "DEPT007"
     * and a spreadsheet's helpful "7" all land on the same department - the operator
     * copying a code off the Category master should not have to reproduce its
     * padding to be understood.
     *
     * Nothing is created here. A code is a row id, so an unknown one names a
     * department that does not exist rather than one to make - see
     * [ProductCsvTemplate.CATEGORY_CODE_COLUMN].
     */
    private fun categoryIdForCode(
        db: SQLiteDatabase,
        code: String,
        cache: MutableMap<String, Int?>
    ): Int? = cache.getOrPut(code.lowercase()) {
        val id = code.filter { it.isDigit() }.toIntOrNull() ?: return@getOrPut null
        db.rawQuery(
            "SELECT id FROM ${DatabaseHelper.Tables.MD_CATEGORY} WHERE id = ? LIMIT 1",
            arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }
    }

    /**
     * The rate name carried by the master row [id], or null where there is none.
     *
     * Looked up so the NAME written onto the product's rate is the master's own,
     * not whatever the sheet spelled beside the id. Only active rows count: a rate
     * name retired from the master is not one a new product should be filed under.
     */
    private fun rateNameFor(
        db: SQLiteDatabase,
        id: Long,
        cache: MutableMap<Long, String?>
    ): String? = cache.getOrPut(id) {
        db.rawQuery(
            "SELECT rate_name FROM ${DatabaseHelper.Tables.MD_RATE_NAME} " +
                "WHERE id = ? AND is_active = 1 LIMIT 1",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
        }
    }

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
            // The full text becomes the unit's NAME; the short name is cut to the
            // three characters a slip has room for - see UnitDao.SHORT_NAME_MAX. A
            // sheet saying "Litres" makes a unit named Litres, short name "Lit",
            // rather than a short name too long to print beside a quantity.
            put("unit_name", symbol)
            put("unit_symbol", symbol.take(com.example.synergic_pos_offline.database.UnitDao.SHORT_NAME_MAX))
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
