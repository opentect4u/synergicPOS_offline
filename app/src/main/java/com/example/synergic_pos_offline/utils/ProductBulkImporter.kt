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

    /**
     * What an import does with the products already on the till.
     *
     * The Bulk Upload screen asks for [APPEND] now - see its own doc for what that
     * means for a row whose [ProductCsvTemplate.PRODUCT_ID_COLUMN] names a product
     * already here. [REPLACE] is kept for a caller that genuinely wants a clean
     * sweep, but nothing in the app currently asks for it.
     */
    enum class Mode {
        /**
         * Merges the sheet into what is already here, rather than replacing it.
         *
         * A row is COMMON where its [ProductCsvTemplate.PRODUCT_ID_COLUMN] names a
         * product this till already has - the shape a sheet takes when it was
         * downloaded from the Products screen's own export, edited, and brought
         * back. That product's own row is UPDATED in place - its fields and its
         * rates become the sheet's - rather than stepping around the taken id into
         * a duplicate, which is what asking for this mode used to do. Its id, and
         * every bill, return or stock record that names it, are untouched: nothing
         * is deleted to make room for it.
         *
         * A row naming no id, or an id this till has never used, is UNCOMMON and is
         * simply added - the sheet's new products, alongside whatever is already
         * here. A product on the till that the sheet never mentions at all is left
         * exactly as it is; an Append only ever adds or updates, never removes.
         */
        APPEND,

        /**
         * Clears the product master first, so the till ends up holding the sheet and
         * nothing else - except the products it is not allowed to forget, see
         * [replaceCounts].
         */
        REPLACE
    }

    /**
     * How an import went: [imported] new products written, [replaced] existing
     * ones matched by id and updated in place instead (see [Mode.APPEND]),
     * [skipped] rows that could not be written at all, [removed] products a
     * Replace cleared first.
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
        val replaced: Int = 0,
        /**
         * Bills thrown away because the products they were rung up against have been
         * replaced by this upload - see [deleteBillsFor]. Active and cancelled alike.
         */
        val billsDeleted: Int = 0,
        val languageApplied: String = PrintLanguage.Language.ENGLISH.englishName,
        val languageWarning: String? = null,
        /** Codes or rate ids the sheet named that this till has no row for. */
        val referenceWarning: String? = null
    )

    /**
     * Every product id that some transaction still refers to.
     *
     * A product that has been sold, returned, purchased, written off or sent to a
     * kitchen is part of the record of what happened. It cannot be deleted without
     * either breaking that record or taking it down too, so Replace leaves those
     * products where they are - it clears the catalogue, not the books.
     *
     * ## Not every stock movement is a record of trade
     *
     * This used to protect a product for ANY row in td_stock_transactions, and that
     * made Replace do nothing at all on a till that tracks stock. Opening stock writes
     * a PURCHASE movement for every product the moment it is created (see
     * StockDao.recordOpening), so every product ever added was protected by its own
     * creation - a Replace on a real shop's till removed nothing and the catalogue
     * only ever grew.
     *
     * SALE, RETURN and DAMAGE_WRITEOFF are records of trade and still protect. A bare
     * PURCHASE or ADJUSTMENT is the product's own stock bookkeeping - what it opened
     * at, and corrections the operator made to that - and belongs to the product the
     * way its rates and its batches do. It goes when the product goes.
     *
     * A REAL purchase document is a different thing and still protects, through the
     * td_purchase clause above - so nothing that was actually bought from a supplier
     * is at risk here.
     *
     * A product whose *batch* is on a transaction counts as in use for the same
     * reason: the batch cannot go, and a batch cannot outlive its product. That clause
     * reads stock movements the same way and for the same reason - opening stock names
     * the batch it opened, so counting every movement there protected every product
     * through its own creation a second time, by the back door.
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
        UNION SELECT product_id FROM ${DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS}
               WHERE product_id IS NOT NULL
                 AND transaction_type IN ('SALE', 'RETURN', 'DAMAGE_WRITEOFF')
        UNION SELECT product_id FROM ${DatabaseHelper.Tables.TD_KOT_ITEMS} WHERE product_id IS NOT NULL
        UNION SELECT b.product_id FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK} b
               WHERE b.product_id IS NOT NULL AND (
                   b.id IN (SELECT batch_id FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} WHERE batch_id IS NOT NULL)
                OR b.id IN (SELECT batch_id FROM ${DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS}
                             WHERE batch_id IS NOT NULL
                               AND transaction_type IN ('SALE', 'RETURN', 'DAMAGE_WRITEOFF'))
               )
    """.trimIndent()

    /**
     * What a Replace would do: [removable] products cleared, [kept] held back.
     *
     * [removableNames] and [keptNames] carry the products BY NAME, up to
     * [NAMES_LISTED] of each, so the alert shown before an upload can say which
     * products it is about to override rather than only how many. A count tells an
     * operator that something is going; a name tells them WHETHER IT SHOULD.
     *
     * Capped because the list is read on a dialog, and a shop with four hundred
     * products would otherwise build a message nobody can read past. The counts are
     * always the true totals - only the naming is trimmed.
     */
    data class ReplaceCounts(
        val total: Int,
        val removable: Int,
        val kept: Int,
        val removableNames: List<String> = emptyList(),
        val keptNames: List<String> = emptyList()
    )

    /** How many products the override alert names before it says "and N more". */
    const val NAMES_LISTED = 12

    /**
     * What a Replace would do to THIS till - counts, and the products by name.
     *
     * Read before the operator confirms, so the alert states what will actually
     * happen on this till rather than promising a clean sweep it cannot deliver.
     */
    fun replaceCounts(context: Context): ReplaceCounts {
        val db = DatabaseHelper.getInstance(context).readableDatabase
        fun count(sql: String): Int = db.rawQuery(sql, null).use { c -> c.moveToFirst(); c.getInt(0) }
        fun names(where: String): List<String> = db.rawQuery(
            "SELECT product_name FROM ${DatabaseHelper.Tables.MD_PRODUCTS} " +
                "WHERE $where ORDER BY id ASC LIMIT ${NAMES_LISTED + 1}",
            null
        ).use { c ->
            buildList { while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { add(it) } }
        }

        // EVERY product goes, so every product is removable and none is kept.
        //
        // There used to be a "kept" half here: products already on a bill were held
        // back, because deleting one takes the bill line's link to it. That made an
        // upload of 50 products over a shop's 200 leave 50 plus however many of the
        // 200 had ever been sold - which is not what "replace my product list" means
        // to the person who said it. See [clearProducts]: the history keeps its rows
        // and its item names, and only the link goes.
        val total = count("SELECT count(*) FROM ${DatabaseHelper.Tables.MD_PRODUCTS}")
        return ReplaceCounts(
            total = total,
            removable = total,
            kept = 0,
            removableNames = names("1 = 1")
        )
    }

    /**
     * What an Append would do to THIS till before it runs - how many sheet rows
     * update a product already here, and how many are new.
     *
     * [toUpdateNames] carries the ones being updated BY NAME, up to [NAMES_LISTED],
     * for the same reason [replaceCounts] names what a Replace removes: a count
     * says something is about to change, a name is what lets the operator tell
     * whether it should. Read against the ROW's own name from the sheet, not the
     * master's current one, since the sheet's is what it is about to become.
     */
    data class MergeCounts(
        val total: Int,
        val toUpdate: Int,
        val toAdd: Int,
        val toUpdateNames: List<String> = emptyList(),
        /**
         * How many bills the update would take with it - see [deleteBillsFor].
         *
         * Counted BEFORE the upload runs, so the confirmation can name the number
         * rather than the operator meeting it in the summary afterwards.
         */
        val billsToDelete: Int = 0
    )


    /**
     * How many bills name one of [productIds] - what [deleteBillsFor] would take.
     *
     * Read on its own so the confirmation can state the number BEFORE the upload
     * runs. Counted the same way the deletion selects, so the figure the operator
     * agrees to is the figure that goes.
     */
    private fun countBillsFor(db: SQLiteDatabase, productIds: Set<Long>): Int {
        if (productIds.isEmpty()) return 0
        return db.rawQuery(
            "SELECT COUNT(DISTINCT bill_id) FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} " +
                "WHERE bill_id IS NOT NULL AND product_id IN (${productIds.joinToString(",")})",
            null
        ).use { c -> c.moveToFirst(); c.getInt(0) }
    }
    /** What an Append (see [Mode.APPEND]) would do to THIS till, before it runs. */
    fun mergeCounts(context: Context, rows: List<Map<String, String>>): MergeCounts {
        val db = DatabaseHelper.getInstance(context).readableDatabase
        var validRows = 0
        var toUpdate = 0
        val toUpdateNames = mutableListOf<String>()
        val updatedIds = LinkedHashSet<Long>()
        for (r in rows) {
            val name = (r["product_name"] ?: r["item_name"]).orEmpty().trim()
            if (name.isBlank()) continue
            validRows++
            val wantedId = cell(r, ProductCsvTemplate.PRODUCT_ID_COLUMN, "id")?.toLongOrNull()
                ?.takeIf { it > 0 } ?: continue
            if (productIdTaken(db, wantedId)) {
                toUpdate++
                updatedIds.add(wantedId)
                if (toUpdateNames.size < NAMES_LISTED) toUpdateNames.add(name)
            }
        }
        return MergeCounts(
            validRows, toUpdate, validRows - toUpdate, toUpdateNames,
            billsToDelete = countBillsFor(db, updatedIds)
        )
    }

    /**
     * Clears every product the till is free to forget, with its rates, its stock and
     * its names.
     *
     * Runs inside the caller's transaction, before the new rows go in, so a sheet
     * that fails to import leaves the old catalogue standing rather than wiping it
     * and putting nothing in its place.
     *
     * EVERY child table that points at md_products has to be listed here. Foreign keys
     * are enforced, so one that is missed does not leave an orphan - it fails the
     * delete, rolls the whole transaction back, and the upload imports nothing at all.
     * That is what md_product_names did: the regional-name table was added later and
     * never added here, so any till where a single product had been given a name in
     * the shop's own language could not run a Replace. It went unnoticed while Replace
     * was one of two choices and Append was the default.
     *
     * @return how many products were removed
     */
    private fun clearProducts(db: SQLiteDatabase): Int {
        val products = DatabaseHelper.Tables.MD_PRODUCTS
        val removed = db.rawQuery("SELECT count(*) FROM $products", null)
            .use { c -> c.moveToFirst(); c.getInt(0) }

        // 1. SAVE THE NAMES THE BOOKS WILL NEED, before the rows that hold them go.
        //
        // A bill line keeps its own product_name so a reprint can name an item without
        // the product still existing - but only rows written since that column was
        // added actually carry one. Filling the gaps here is what makes the deletion
        // below safe: after this, every bill line in the database can name its own item
        // from itself. Do it the other way round and a shop's oldest bills reprint as
        // blank lines.
        db.execSQL(
            """
            UPDATE ${DatabaseHelper.Tables.TD_BILL_ITEMS}
               SET product_name = (SELECT p.product_name FROM $products p
                                    WHERE p.id = ${DatabaseHelper.Tables.TD_BILL_ITEMS}.product_id)
             WHERE (product_name IS NULL OR TRIM(product_name) = '')
               AND product_id IS NOT NULL
            """.trimIndent()
        )

        // 2. DETACH THE HISTORY. The rows stay - they are the record of what happened,
        // and nothing here deletes a sale - but they stop pointing at products that are
        // about to be gone. Foreign keys are enforced, so this is what lets step 3
        // succeed at all.
        //
        // The batch columns go with them: a batch belongs to its product and is deleted
        // below, so a line still naming one would be pointing at nothing.
        listOf(
            "${DatabaseHelper.Tables.TD_BILL_ITEMS} SET product_id = NULL, batch_id = NULL",
            "${DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS} SET product_id = NULL, batch_id = NULL",
            "${DatabaseHelper.Tables.TD_RETURN_ITEMS} SET product_id = NULL",
            "${DatabaseHelper.Tables.TD_KOT_ITEMS} SET product_id = NULL",
            "${DatabaseHelper.Tables.TD_PURCHASE} SET product_id = NULL",
            "${DatabaseHelper.Tables.TD_WRITE_OFF} SET prod_id = NULL"
        ).forEach { db.execSQL("UPDATE $it") }

        // 3. THE CATALOGUE ITSELF, children before parents. Rates, stock batches and
        // the shop's own regional names all belong to the product and die with it.
        db.execSQL("DELETE FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES}")
        db.execSQL("DELETE FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK}")
        db.execSQL("DELETE FROM ${DatabaseHelper.Tables.MD_PRODUCT_NAMES}")
        db.execSQL("DELETE FROM $products")
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
     * [Mode.APPEND] updates a row's product where its id is already here and adds
     * it where it is not, [Mode.REPLACE] clears them all first - see
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
        var unknownCategoryIds = 0
        var unknownRateNameIds = 0
        // Asked once for the sheet, not once per row: the setting cannot change
        // half way through an import, and every row has to be treated the same way
        // whichever half of the file it is in.
        val stockDao = if (GeneralSettingsDao.isStockEnabled(context)) StockDao(context) else null
        var imported = 0
        var skipped = 0
        var removed = 0
        var replaced = 0
        // Every product the sheet took over an existing id for. Collected as the rows go
        // in and acted on once at the end - see [deleteBillsFor].
        val replacedIds = LinkedHashSet<Long>()
        var billsDeleted = 0

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
            regionalNameOf(it) != null
        }

        db.beginTransaction()
        try {
            if (mode == Mode.REPLACE) removed = clearProducts(db)
            for (r in rows) {
                val name = (r["product_name"] ?: r["item_name"]).orEmpty().trim()
                if (name.isBlank()) { skipped++; continue }

                // BY NAME FIRST - what the sheet's CATEGORY_NAME column carries and
                // what a person filling it in can actually read and check. Then the
                // plain id, then the "DEPT007" code, for sheets filled in against
                // previous templates.
                //
                // The NAME is the only one of the three that can create the department
                // it names; an id and a code are both row ids, and there is no such
                // thing as inventing one, so an id or code this till has no department
                // for leaves the product uncategorised and is counted for the report at
                // the end. That is the trade named in ProductCsvTemplate's own note on
                // the column: a misspelt name makes a category rather than failing
                // loudly, and the upload preview lists every category it is about to
                // create so the typo is visible before anything is written.
                val nameCell = categoryNameOf(r)
                val idCell = r[ProductCsvTemplate.CATEGORY_ID_COLUMN]?.trim().orEmpty()
                val codeCell = r[ProductCsvTemplate.CATEGORY_CODE_COLUMN]?.trim().orEmpty()
                val categoryId = when {
                    nameCell != null -> categoryIdFor(db, nameCell, storeId, categoryIds)
                    idCell.isNotEmpty() -> categoryIdForId(db, idCell, categoryCodeIds)
                        .also { if (it == null) unknownCategoryIds++ }
                    codeCell.isNotEmpty() -> categoryIdForCode(db, codeCell, categoryCodeIds)
                        .also { if (it == null) unknownCategoryCodes++ }
                    else -> null
                }

                // The id the sheet asks this product to be. COMMON where this till
                // already has a product under it - see [Mode.APPEND] - in which case
                // that product is updated rather than stepped around into a
                // duplicate, which is what asking for a taken id used to do.
                val wantedId = cell(r, ProductCsvTemplate.PRODUCT_ID_COLUMN, "id")?.toLongOrNull()
                    ?.takeIf { it > 0 }
                val existingId = wantedId?.takeIf { mode == Mode.APPEND && productIdTaken(db, it) }

                val product = ContentValues().apply {
                    if (existingId == null && wantedId != null) put("id", wantedId)
                    if (storeId != null) put("store_id", storeId) else putNull("store_id")
                    put("product_name", name)
                    put("hsn_code", cell(r, "hsn_number", "hsn_code"))
                    put("bar_code", cell(r, "bar_code", "barcode"))
                    if (categoryId != null) put("category_id", categoryId) else putNull("category_id")
                }

                val productId: Long
                val isNewProduct: Boolean
                if (existingId != null) {
                    db.update(
                        DatabaseHelper.Tables.MD_PRODUCTS, product,
                        "id = ?", arrayOf(existingId.toString())
                    )
                    // Rates are replaced wholesale, not merged - the sheet's
                    // RATE_1..4 rows ARE this product's rates now, the same way the
                    // Add/Edit form replaces them when a rate is edited. Nothing on
                    // a transaction points at a rate row directly (only at the
                    // product and its batch), so deleting these cannot orphan a
                    // bill, a return or a KOT line - see SQL_PRODUCTS_IN_USE's own
                    // note on what a product row itself is protected by.
                    db.delete(
                        DatabaseHelper.Tables.MD_PRODUCT_RATES,
                        "product_id = ?", arrayOf(existingId.toString())
                    )
                    productId = existingId
                    isNewProduct = false
                    replacedIds.add(existingId)
                    replaced++
                } else {
                    val newId = db.insert(DatabaseHelper.Tables.MD_PRODUCTS, null, product)
                    if (newId == -1L) { skipped++; continue }
                    productId = newId
                    isNewProduct = true
                }

                // The two figures are read from their own columns and neither stands
                // in for the other: the rate is what the product is rated at, the
                // selling price is what it sells for, and they are different numbers
                // that happen to coincide on an untaxed, undiscounted line. Filling
                // one from the other would quietly invent a price the sheet never
                // gave, which is worse than a blank the operator can see and correct.
                // "selling_price" is the current template's column; the older names
                // are still read so a sheet filled in against a previous template
                // imports - they are other spellings of this column, not other
                // columns to fall back on.
                val sell = cell(r, "selling_price", "sell_price", "sale_price")
                    ?.toDoubleOrNull() ?: 0.0

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

                // The tax, discount and price figures are the PRODUCT's - one set of
                // columns on the sheet - so every unit this product sells under
                // carries them. Only the unit and the rate differ slot to slot, which
                // is exactly the shape UNIT_1..4/RATE_1..4 describes.
                for ((slot, line) in rateLinesOf(r).withIndex()) {
                    val unitId = line.unit?.let { unitIdForSlot(db, it, storeId, unitIds) }
                    val rate = ContentValues().apply {
                        if (storeId != null) put("store_id", storeId) else putNull("store_id")
                        if (outletId != null) put("outlet_id", outletId) else putNull("outlet_id")
                        put("product_id", productId)
                        put("rate_name", rateNameText)
                        if (rateNameId != null) put("rate_name_id", rateNameId) else putNull("rate_name_id")
                        put("rate", line.rate)
                        if (unitId != null) put("unit_id", unitId) else putNull("unit_id")
                        put("cgst_rate", cell(r, "product_cgst", "cgst")?.toDoubleOrNull() ?: 0.0)
                        put("sgst_rate", cell(r, "product_sgst", "sgst")?.toDoubleOrNull() ?: 0.0)
                        put("igst_rate", cell(r, "product_igst", "igst")?.toDoubleOrNull() ?: 0.0)
                        put("vat_rate", cell(r, "vat_flag", "vat")?.toDoubleOrNull() ?: 0.0)
                        put("discount", cell(r, "product_discount", "discount")?.toDoubleOrNull() ?: 0.0)
                        // A bulk-uploaded discount is always a percentage, whatever the
                        // sheet's discount_type column says: the figures in the template
                        // are percentages, and reading a "5" meant as 5% as five rupees
                        // off would misprice the product with nothing to show for it.
                        put("discount_type", DISCOUNT_TYPE_PERCENT)
                        // The selling price belongs to the product, but only the FIRST
                        // slot may claim it: it is one figure and the further slots are
                        // other units at other rates, so copying it onto a half plate
                        // would price the half at the full plate's price.
                        val slotSell = if (slot == 0) sell else line.rate
                        put("sale_price", slotSell)
                        put("sell_price", slotSell)
                        put("purchase_price", cell(r, "purchase_price")?.toDoubleOrNull() ?: 0.0)
                    }
                    val rateId = db.insert(DatabaseHelper.Tables.MD_PRODUCT_RATES, null, rate)
                    // The first slot is the one the till reaches for. Marked here
                    // rather than after the loop so a product whose later slots failed
                    // to insert still has a default.
                    if (rateId != -1L && slot == 0) {
                        db.execSQL(
                            "UPDATE ${DatabaseHelper.Tables.MD_PRODUCT_RATES} SET \"default\" = 1 WHERE id = ?",
                            arrayOf<Any>(rateId)
                        )
                    }
                }

                // The quantity the sheet says this item starts at, booked in exactly
                // as the Add Product form's own opening stock is, and on the same
                // transaction the product itself went in on.
                //
                // NEW PRODUCTS ONLY. A row that replaced a common product already
                // has whatever stock its own sales and adjustments left it at - re-
                // booking the sheet's STOCK column on every re-upload would credit
                // it that quantity again each time, on top of what is already there.
                if (isNewProduct) {
                    stockDao?.let { dao ->
                        openingStockOf(r)?.let { dao.recordOpening(db, productId, it, storeId, outletId) }
                    }
                }
                // The shop's own name for this product, in the language the sheet
                // named - through the same DAO the Add/Edit form writes through, so a
                // bulk-uploaded name and a typed one are the same row in the same
                // table. A blank cell writes nothing and the product falls back to the
                // machine translation, exactly as it did before this column existed.
                if (nameDao != null && namesLanguage != null) {
                    regionalNameOf(r)
                        ?.let { nameDao.save(productId.toInt(), namesLanguage.code, it) }
                }
                if (isNewProduct) imported++
            }
            // THE BOOKS OF EVERY PRODUCT THE SHEET TOOK OVER.
            //
            // A common id does not add a product, it REPLACES one: the row that was
            // there is now a different product wearing the same id. Every bill that
            // named that id is therefore a bill for goods this till can no longer
            // describe - it would report and reprint under the new product's name,
            // price and tax, none of which were what was sold.
            //
            // So those bills go, active and cancelled alike, inside the same
            // transaction as the import that caused them to go. See [deleteBillsFor].
            billsDeleted = deleteBillsFor(db, replacedIds)
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
            if (unknownCategoryIds > 0)
                "$unknownCategoryIds row(s) named a " +
                    "${ProductCsvTemplate.CATEGORY_ID_COLUMN} this till has no department " +
                    "for - those products came in uncategorised."
            else null,
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
            imported, skipped, removed, replaced, billsDeleted,
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
     * Throws away every bill rung up against one of [productIds] - active and
     * cancelled alike - and returns how many went.
     *
     * ## Why a replaced product takes its bills with it
     *
     * A common id does not add a product beside the old one, it REPLACES it: the row
     * under that id is now a different product, with a different name, price and tax
     * rate. Every bill that named the id is a bill for goods the till can no longer
     * describe. Left alone it would reprint and report under the NEW product's
     * details, quietly restating what a customer was actually sold - which is worse
     * than the bill not being there, because it looks like a record.
     *
     * Cancelled bills go too. A cancelled bill is still a record of the same sale, and
     * a record that can no longer be read correctly is not one worth keeping.
     *
     * ## What goes with each bill
     *
     * Everything that is PART of the bill, children before parents: its returns, its
     * print record, its payments, its kitchen order, its ledger entry and its lines.
     * Foreign keys are enforced, so anything missed here does not orphan a row - it
     * fails the delete and rolls the whole upload back.
     *
     * A sale RETURN against such a bill goes with it. The return is a document about
     * this bill, and a credit note whose original no longer exists points at nothing.
     *
     * Stock movements are NOT touched. They record what physically left the shelf,
     * which happened whatever the catalogue now says, and they name the product rather
     * than the bill - so they stay readable and stay true.
     *
     * Runs inside the caller's transaction: these deletions and the import that caused
     * them land together or not at all.
     */
    private fun deleteBillsFor(db: SQLiteDatabase, productIds: Set<Long>): Int {
        if (productIds.isEmpty()) return 0

        // WHICH BILLS, decided once and held.
        //
        // Read into a list rather than left as a subquery over td_bill_items, because
        // the deletions below empty that very table: a subquery would still be true
        // for the first delete and false by the third, taking the lines out and
        // leaving the bills they belonged to standing.
        val doomedIds = mutableListOf<Long>()
        db.rawQuery(
            "SELECT DISTINCT bill_id FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} " +
                "WHERE bill_id IS NOT NULL AND product_id IN (${productIds.joinToString(",")})",
            null
        ).use { c -> while (c.moveToNext()) doomedIds.add(c.getLong(0)) }
        if (doomedIds.isEmpty()) return 0
        val bills = doomedIds.joinToString(",")

        // CHILDREN BEFORE PARENTS, all the way down. Foreign keys are enforced, so a
        // table missed here does not orphan a row - it fails the delete and rolls the
        // whole upload back with it.
        //
        // The return chain first: a return LINE points at a bill line, and the return
        // it belongs to points at the bill.
        db.execSQL(
            "DELETE FROM ${DatabaseHelper.Tables.TD_RETURN_ITEMS} WHERE bill_item_id IN " +
                "(SELECT id FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} WHERE bill_id IN ($bills))"
        )
        db.execSQL(
            "DELETE FROM ${DatabaseHelper.Tables.TD_RETURN_ITEMS} WHERE return_id IN " +
                "(SELECT id FROM ${DatabaseHelper.Tables.TD_SALE_RETURNS} WHERE original_bill_id IN ($bills))"
        )
        db.execSQL(
            "DELETE FROM ${DatabaseHelper.Tables.TD_SALE_RETURNS} WHERE original_bill_id IN ($bills)"
        )

        db.execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_BILL_PRINTS} WHERE bill_id IN ($bills)")
        db.execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_PAYMENTS} WHERE bill_id IN ($bills)")
        db.execSQL(
            "DELETE FROM ${DatabaseHelper.Tables.TD_KOT_ITEMS} WHERE kot_id IN " +
                "(SELECT id FROM ${DatabaseHelper.Tables.TD_KOT} WHERE bill_id IN ($bills))"
        )
        db.execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_KOT} WHERE bill_id IN ($bills)")
        db.execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_CUSTOMER_LEDGER} WHERE bill_id IN ($bills)")
        db.execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} WHERE bill_id IN ($bills)")
        db.execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_BILLS} WHERE receipt_no IN ($bills)")
        return doomedIds.size
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
     * The department the plain id [cell] names, or null where this till has no such
     * row.
     *
     * The number on the sheet is the number `md_products.category_id` stores, so this
     * is a check that the department exists rather than a translation of anything.
     * Nothing is created: an id is a row id, and an unknown one names a department
     * that does not exist rather than one to make - see
     * [ProductCsvTemplate.CATEGORY_ID_COLUMN].
     *
     * Shares [categoryIdForCode]'s cache under its own key shape, so a sheet naming
     * one department on four hundred rows asks the database about it once.
     */
    private fun categoryIdForId(
        db: SQLiteDatabase,
        cell: String,
        cache: MutableMap<String, Int?>
    ): Int? = cache.getOrPut("#$cell") {
        val id = cell.toIntOrNull()?.takeIf { it > 0 } ?: return@getOrPut null
        db.rawQuery(
            "SELECT id FROM ${DatabaseHelper.Tables.MD_CATEGORY} WHERE id = ? LIMIT 1",
            arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }
    }

    /**
     * Whether some product already holds [id].
     *
     * Asked per row rather than cached, because the answer CHANGES as the sheet goes
     * in: the row above may have just taken the id this row is asking for. Reading it
     * fresh is what makes a sheet that repeats an id import as two products instead of
     * failing on a primary key half way through.
     */
    private fun productIdTaken(db: SQLiteDatabase, id: Long): Boolean = db.rawQuery(
        "SELECT 1 FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE id = ? LIMIT 1",
        arrayOf(id.toString())
    ).use { it.moveToFirst() }

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

    /**
     * The first of [keys] this row actually fills in, or null where it fills none.
     *
     * Every figure on the sheet is asked for through this, because every figure has
     * more than one spelling now: the shop's own master heads its tax columns
     * `PRODUCT_CGST` where the previous template said `cgst`, and both have to import.
     * They are alternative SPELLINGS of one column, tried in order, not separate
     * columns to add up or fall back through - the first one present wins even if the
     * value it holds is a zero the operator meant.
     *
     * Blank counts as absent: a spreadsheet fills every cell of a row it touches, so
     * an empty `PRODUCT_CGST` beside a filled `cgst` should read as the filled one.
     */
    private fun cell(row: Map<String, String>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { row[it]?.trim()?.takeIf { v -> v.isNotEmpty() } }

    /**
     * The headings a sheet may give a product's regional name under.
     *
     * `PRODUCT_UNI_NAME` is what the shop's own master calls it and what the current
     * template hands out; `regional_name` is the previous template's heading, kept so
     * a file filled in against it still imports.
     */
    val REGIONAL_NAME_COLUMNS = listOf(ProductCsvTemplate.REGIONAL_NAME_COLUMN, "regional_name")

    /** The shop's own name for this row's product, or null where it gives none. */
    fun regionalNameOf(row: Map<String, String>): String? =
        cell(row, *REGIONAL_NAME_COLUMNS.toTypedArray())

    /**
     * The headings a sheet may name its department under.
     *
     * `CATEGORY_NAME` is the current template's; `category` is what the template
     * carried before the column ever became an id, and a shop that has been filling
     * that sheet in for months should not have to rename a column to upload it.
     */
    val CATEGORY_NAME_COLUMNS = listOf(ProductCsvTemplate.CATEGORY_NAME_COLUMN, "category")

    /** The department this row names, or null where it names none. */
    fun categoryNameOf(row: Map<String, String>): String? =
        cell(row, *CATEGORY_NAME_COLUMNS.toTypedArray())

    /** One way this product is sold: at [rate], under [unit] where it names one. */
    data class RateLine(val unit: String?, val rate: Double)

    /**
     * The ways this row says its product is sold - one [RateLine] per priced slot.
     *
     * `UNIT_1..UNIT_4` with `RATE_1..RATE_4` beside them is the shop's own master
     * describing a dish sold by plate, by half plate and by quarter, each at its own
     * price. Each priced slot becomes a rate row; the first is the default.
     *
     * It is the RATE that decides a slot is used. A unit with no price beside it is
     * not something this shop sells - the master leaves those cells filled from an
     * older edit - while a price with no unit is still a price, and refusing it would
     * lose the product's only rate over a blank cell nothing needs.
     *
     * A sheet with no numbered slots at all is the previous template, whose single
     * `rate`/`unit` pair is read instead. That case always yields a line even at zero,
     * because it always did: every product it imported got a rate row, and a product
     * with none would not appear on the sales screen at all. A slotted sheet whose
     * every rate cell is blank gets the same one empty line, for the same reason.
     */
    fun rateLinesOf(row: Map<String, String>): List<RateLine> {
        val slots = (1..ProductCsvTemplate.RATE_SLOTS).mapNotNull { slot ->
            val (unitKey, rateKey) = ProductCsvTemplate.slotColumns(slot)
            cell(row, rateKey)?.toDoubleOrNull()?.let { RateLine(cell(row, unitKey), it) }
        }
        if (slots.isNotEmpty()) return slots
        val (unitKey, rateKey) = ProductCsvTemplate.slotColumns(1)
        return listOf(
            RateLine(
                unit = unitNameOf(row) ?: cell(row, unitKey),
                rate = cell(row, "rate", "price", rateKey)?.toDoubleOrNull() ?: 0.0
            )
        )
    }

    /**
     * The unit id a slot's cell names - resolving it as an ID where it is a whole
     * number, and as a symbol otherwise.
     *
     * A sheet a person filled in heads that cell "PLT" or "KG". A sheet exported from
     * the shop's other system carries that system's unit id there instead - "5", "2" -
     * and taking those for symbols would fill this till's Unit master with units
     * called "5" and "2" and put them on the bill. So a whole number is looked up as
     * an id, and an id this till has no unit for leaves the rate's unit blank: an id
     * can only REFER to a unit, never create one, the same trade
     * [ProductCsvTemplate.CATEGORY_CODE_COLUMN] makes.
     *
     * A decimal is not an id and is treated as a symbol, so a unit genuinely named
     * "0.5" is not silently dropped.
     */
    private fun unitIdForSlot(
        db: SQLiteDatabase,
        cell: String,
        storeId: Int?,
        cache: MutableMap<String, Int?>
    ): Int? {
        val asId = cell.toIntOrNull()
        if (asId != null) {
            return cache.getOrPut("#$asId") {
                db.rawQuery(
                    "SELECT id FROM ${DatabaseHelper.Tables.MD_UNITS} WHERE id = ? LIMIT 1",
                    arrayOf(asId.toString())
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }
            }
        }
        return unitIdFor(db, cell, storeId, cache)
    }

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
