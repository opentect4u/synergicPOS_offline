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
         * back. THE WHOLE ROW becomes the sheet's, not the columns the sheet
         * happens to fill: its fields, its rates, its picture, its regional names
         * and its stock. A common id does not edit a product, it replaces what that
         * id MEANS - so anything the previous product left in a column this sheet
         * does not carry would otherwise be attached to a product it was never
         * about, and the picture and the stock count are the two that show.
         *
         * The id itself is kept, which is the point of matching on it: the row is
         * reused rather than stepped around into a duplicate, which is what asking
         * for this mode used to do.
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
         * erased by this upload - see [clearEveryBill]. Active and cancelled alike.
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
         * How many bills the upload would take with it - EVERY bill on the till.
         *
         * Counted BEFORE the upload runs, so the confirmation can name the number
         * rather than the operator meeting it in the summary afterwards.
         *
         * This used to count only the bills naming a product the sheet replaced. It
         * counts the lot now, live and cancelled, because that is what the upload
         * does - see the note on [clearEveryBill].
         */
        val billsToDelete: Int = 0
    )


    /**
     * Every bill on the till, cancelled ones included - what the upload will take.
     *
     * Read on its own so the confirmation can state the number BEFORE the upload
     * runs, and counted from the same two tables the erase empties, so the figure
     * the operator agrees to is the figure that goes.
     */
    private fun countEveryBill(db: SQLiteDatabase): Int {
        fun count(table: String): Int = runCatching {
            db.rawQuery("SELECT COUNT(*) FROM $table", null)
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }.getOrDefault(0)
        return count(DatabaseHelper.Tables.TD_BILLS) + count(DatabaseHelper.Tables.TD_BILLS_DELETE)
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
            billsToDelete = countEveryBill(db)
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
        // in and acted on once at the end.
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
                    // THE WHOLE ROW, not the columns the sheet happens to fill.
                    //
                    // A common id REPLACES what that id means - the row becomes a
                    // different product - so anything the previous one left behind in
                    // a column this sheet does not carry is now attached to a product
                    // it was never about. The photograph is the one that shows: upload
                    // "Paneer Chilly" over the id that used to be "Paneer Tikka" and
                    // the till went on printing and displaying Paneer Tikka's picture
                    // beside the new name, on screen and in the product grid, with
                    // nothing anywhere to say why.
                    //
                    // Cleared rather than left, because a blank is honest and a
                    // stale value is not. The sheet has no column for any of these,
                    // so there is nothing to put back in their place.
                    putNull("product_image")
                    putNull("sku")
                    putNull("brand")
                    put("stock_alert_qty", 0)
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
                    // THE OLD PRODUCT'S STOCK GOES WITH ITS NAME.
                    //
                    // Fifty Paneer Tikka on the shelf are not fifty Paneer Chilly. The
                    // count and the movements behind it belong to the product that
                    // held this id, and leaving them made the new product open with a
                    // quantity nobody had ever counted and a history it had no part
                    // in - which the stock report then showed as fact.
                    //
                    // Movements before batches: td_stock_transactions keys onto both
                    // the product and its batch, so clearing the batches first is
                    // refused by the constraint.
                    db.delete(
                        DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS,
                        "product_id = ?", arrayOf(existingId.toString())
                    )
                    db.delete(
                        DatabaseHelper.Tables.MD_BATCH_STOCK,
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
                // EVERY ROW THE SHEET OPENS A COUNT FOR, replaced or new.
                //
                // This was new products only, to stop a re-upload crediting the same
                // opening quantity again on top of what a product's own trading had
                // left it at. That reasoning held while an upload EDITED a product;
                // it does not now that a common id replaces one - the stock this
                // product would have been credited on top of has just been cleared
                // with the product that owned it, so the sheet's figure is the only
                // count there is, and withholding it would leave the new product at
                // zero however many the shop typed in.
                stockDao?.let { dao ->
                    openingStockOf(r)?.let { dao.recordOpening(db, productId, it, storeId, outletId) }
                }
                // The shop's own name for this product, in the language the sheet
                // named - through the same DAO the Add/Edit form writes through, so a
                // bulk-uploaded name and a typed one are the same row in the same
                // table. A blank cell writes nothing and the product falls back to the
                // machine translation, exactly as it did before this column existed.
                // THE OLD PRODUCT'S REGIONAL NAMES GO WITH ITS PICTURE, and for the
                // same reason: they name a product this id is no longer for. Every
                // language, not just the one the sheet wrote in - a till carrying
                // Hindi and Tamil names would otherwise keep the Tamil one describing
                // the product that used to be here.
                //
                // Dropped before the new one is written, so a sheet whose cell is
                // blank leaves the product with no regional name at all rather than
                // with the previous product's.
                if (existingId != null) {
                    runCatching { ProductNameDao(context).deleteFor(listOf(productId.toString())) }
                }
                if (nameDao != null && namesLanguage != null) {
                    regionalNameOf(r)
                        ?.let { nameDao.save(productId.toInt(), namesLanguage.code, it) }
                }
                if (isNewProduct) imported++
            }
            // THE BOOKS OF EVERY PRODUCT THE SHEET TOOK OVER.
            //
            // EVERY BILL, not only the ones naming a replaced product.
            //
            // A bulk upload redraws the catalogue the books were written against. It
            // used to take just the bills naming an id the sheet replaced, on the
            // reasoning that those were the only ones the till could no longer
            // describe - but a sheet also moves prices, tax rates, units and
            // categories on products it does not replace, and every report over the
            // old bills reads them through the catalogue as it is NOW. What was left
            // was a set of books that only looked intact.
            //
            // So the upload clears them the way Erase Bills does, cancelled ones
            // included - see [clearEveryBill]. The count is named in the
            // confirmation before the operator agrees to it, and a backup is taken
            // first by the caller.
            billsDeleted = clearEveryBill(db)
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
     * Throws away every bill on the till - the same thing Erase Bills does.
     *
     * ## Why an upload takes ALL of them
     *
     * A bulk upload redraws the catalogue the books were written against, and every
     * report on this till reads its bills THROUGH that catalogue as it stands now.
     *
     * It used to take only the bills naming an id the sheet replaced, which sounds
     * narrower and safer and is neither: a sheet moves prices, tax rates, units and
     * categories on products it does not replace, so the bills left behind reported
     * under figures that were not what was sold. What survived was a set of books
     * that only LOOKED intact - worse than no books, because it reads like a record.
     *
     * Cancelled bills go with them, out of td_bills_delete, for the same reason they
     * go in Erase Bills: a cancelled bill is still a record of a sale, and one that
     * can no longer be read correctly is not worth keeping.
     *
     * ## What is NOT touched
     *
     * **What customers owe.** `md_customers.balance_amount` is a figure on the
     * customer, not a sum over the ledger, so clearing the ledger's rows takes the
     * history of a debt and leaves the debt.
     *
     * **Stock movements.** They record what physically left the shelf, which happened
     * whatever the catalogue now says, and they name the product rather than the
     * bill - so they stay readable and stay true.
     *
     * The one exception is a product the sheet REPLACES: its movements go with it,
     * because they are the previous product's trading and this id is not that
     * product any more. That happens where the row is written, not here.
     *
     * **Products, customers and every setting**, this upload's own changes aside. In
     * particular the shop's Tax Settings, which Erase Bills resets and this must not:
     * see the `resetTaxSettings` flag on BillErase.erase.
     *
     * Runs inside the caller's transaction: these deletions and the import that
     * caused them land together or not at all.
     *
     * @return how many bills there were to erase, live and cancelled together
     */
    private fun clearEveryBill(db: SQLiteDatabase): Int {
        val t = DatabaseHelper.Tables

        fun count(table: String): Int = runCatching {
            db.rawQuery("SELECT COUNT(*) FROM $table", null)
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }.getOrDefault(0)

        // COUNTED FIRST. Every table below is about to be emptied, so anything read
        // afterwards would report nothing went.
        val erased = count(t.TD_BILLS) + count(t.TD_BILLS_DELETE)
        if (erased == 0) return 0

        // CHILDREN BEFORE PARENTS, all the way down, with foreign keys left enforced.
        //
        // Six tables carry a key onto td_bills, so the order is not a tidiness
        // preference - a table missed here does not orphan a row, it fails the
        // statement and rolls the whole upload back with it.
        //
        // The RETURN and LEDGER rows go too, which is the one place this parts
        // company with BillSettingsDao.clearAllBills. That leaves them standing, and
        // on a till that has ever taken a sale return it therefore cannot delete the
        // bills at all - the key refuses it. Nothing is written off by taking them:
        // what a customer owes is `md_customers.balance_amount`, a figure on the
        // customer, not a sum over the ledger, so the debt survives its history.
        db.execSQL(
            "DELETE FROM ${t.TD_RETURN_ITEMS} WHERE return_id IN " +
                "(SELECT id FROM ${t.TD_SALE_RETURNS})"
        )
        db.execSQL("DELETE FROM ${t.TD_RETURN_ITEMS}")
        db.execSQL("DELETE FROM ${t.TD_SALE_RETURNS}")
        db.execSQL("DELETE FROM ${t.TD_CUSTOMER_LEDGER}")
        db.execSQL("DELETE FROM ${t.TD_BILL_PRINTS}")
        db.execSQL("DELETE FROM ${t.TD_PAYMENTS}")
        db.execSQL("DELETE FROM ${t.TD_KOT_ITEMS}")
        db.execSQL("DELETE FROM ${t.TD_KOT}")
        db.execSQL("DELETE FROM ${t.TD_BILL_ITEMS}")
        db.execSQL("DELETE FROM ${t.TD_BILLS}")

        // The cancelled bills, in their own pair of tables. Neither carries a foreign
        // key - a cancelled bill's row has already left td_bills - so they can go
        // last without anything above them having to know.
        db.execSQL("DELETE FROM ${t.TD_BILL_ITEMS_DELETE}")
        db.execSQL("DELETE FROM ${t.TD_BILLS_DELETE}")
        return erased
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
     * `product_department`/`department` are what the Category master's own dialog
     * calls this ("Add Category / Department") - a sheet headed to match the screen
     * it came from is a reasonable sheet, and one of these went unrecognised: every
     * row naming a genuinely new department under it read as naming NONE, so the
     * department was never created and the product landed uncategorised instead of
     * pointing at it - see [categoryIdFor], which is the only one of the three ways
     * to read a department that can create one.
     */
    val CATEGORY_NAME_COLUMNS = listOf(
        ProductCsvTemplate.CATEGORY_NAME_COLUMN, "category", "product_department", "department"
    )

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
