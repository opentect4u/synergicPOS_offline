package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.GeneralSettingsDao

/**
 * The item master upload sheet: what it is called, what its columns are, and what
 * the operator gets when they download it.
 *
 * There is one definition because there are two Download Template buttons - the
 * icon beside the bin on the Products screen, and the one on the Bulk Upload page -
 * and they used to carry a template each. They drifted: one grew the `category`
 * column and the other did not, so which button the operator happened to press
 * decided whether the sheet they filled in could describe a category at all.
 *
 * Anything that emits or documents the template reads it from here, so that cannot
 * happen twice.
 */
object ProductCsvTemplate {

    /** What the downloaded file is called, and the asset it may ship as. */
    const val FILE_NAME = "item_master_template.csv"

    /**
     * The heading a row names its category under: the Dept Code, not the name.
     *
     * "DEPT007", as the Category/Department master shows it and as
     * [CategoryDao.formatCode] renders it from the row id. A code is exact where a
     * name is not - two shops spell "Dry Fruits & Cereals" three ways between them,
     * and a sheet that misspells one used to quietly create a SECOND category rather
     * than land in the one that was meant.
     *
     * That is the trade this column makes, and it is worth stating plainly: a code
     * can only REFER to a category, never create one. An unknown code leaves the
     * product uncategorised and the upload says how many rows that happened to,
     * where an unknown name would have created the category and carried on. Set the
     * departments up first, then upload against their codes.
     *
     * The older `category` heading is still read for a sheet that has one, so a file
     * filled in against a previous template still imports - see
     * [ProductBulkImporter].
     */
    const val CATEGORY_CODE_COLUMN = "category_code"

    /**
     * The heading a row names its rate under: the Rate Name master's id, not its name.
     *
     * The id from the Rate Name master - 1, 2, 3 - rather than "Regular" or "MRP".
     * Same reasoning as [CATEGORY_CODE_COLUMN]: the id is what the rate actually IS
     * to this database, and it is what the product's rate row has always been meant
     * to link to. `md_product_rates` has carried a `rate_name_id` column all along
     * and the bulk import never filled it, so an uploaded rate held a name with
     * nothing joining it to the master the Add/Edit form picks from.
     *
     * The name is written alongside the id, read off the master rather than off the
     * sheet, so a rate uploaded in bulk and one chosen in the form come out identical.
     *
     * The older `rate_name` heading is still read, and still taken as the name it
     * says, for a sheet filled in against a previous template.
     */
    const val RATE_NAME_ID_COLUMN = "rate_name_id"


    /**
     * The heading a sheet may set the till's own screen language under.
     *
     * Not a fact about the product on that row - every product in a shop is sold
     * under the one language the operator reads their screens in - but it rides
     * along on the same sheet because there is no second file to ask for it on.
     * Every row that fills this in has to name the *same* language: it is one
     * setting for the whole till, not a fact that can differ line to line, so a
     * sheet naming Hindi on one row and Marathi on another has not actually said
     * which one it wants - see [ProductBulkImporter.regionalLanguageOf]. Filling
     * only the first row and leaving the rest blank is the normal way to use it;
     * a column left blank throughout resolves to English.
     *
     * The name given has to spell one of [PrintLanguage.Language]'s own names
     * exactly - `nativeName` or `englishName` - or the closest one standing is
     * chosen instead and the operator is warned which. Guessing rather than
     * rejecting a near-miss is deliberate: "Marathi" typed as "Marthi" is still
     * obviously Marathi, and bouncing the whole upload over one misspelt word the
     * operator cannot even see misspelt - it is in a script most of the team does
     * not read - would be worse than picking the language that word was reaching for.
     */
    const val REGIONAL_LANGUAGE_COLUMN = "regional_language"

    /**
     * The heading a row gives THIS PRODUCT's name in the shop's own language under.
     *
     * A per-product fact, unlike [REGIONAL_LANGUAGE_COLUMN] beside it - which is why
     * the two sit together on the sheet: one names the language, the next gives each
     * product's name in it.
     *
     * It is the name the shop WRITES, not one the app guesses. Without it a bulk
     * upload could only ever produce machine-translated names - and a lexicon does
     * not know the local word for a regional sweet, nor how a brand is actually
     * spelled on the packet. Filling it in is how a catalogue arrives already saying
     * what the shop calls things, instead of being corrected one product at a time
     * through the Add/Edit form afterwards.
     *
     * Blank is the normal case and costs nothing: that product falls back to the
     * translation exactly as it did before, so a sheet filled in against an older
     * template imports unchanged.
     *
     * Saved against whichever language [REGIONAL_LANGUAGE_COLUMN] resolved to, one
     * row per product per language - see ProductNameDao. A sheet that fills this in
     * without naming a language has not said what language it is IN, so there is no
     * row to write and [ProductBulkImporter] says so rather than guessing.
     */
    const val REGIONAL_NAME_COLUMN = "regional_name"

    /**
     * The columns, in order.
     *
     * `category_code` and `rate_name_id` name their master ROW - "DEPT007", "2" -
     * not the text on it. The code and the id are what those things are to this
     * database, and they are exact: a name has to be spelled the shop's way to
     * match, and a near-miss used to create a second category rather than land in
     * the one that was meant. See each column's own doc.
     *
     * `unit_id` is the exception and stays a name - "Ltr", "PCS". It is the one of
     * the three whose master a sheet may legitimately extend: a shop weighing
     * something in a unit this till has never seen is describing its goods, not
     * misspelling a department, so that name is created where the others are not.
     *
     * `unit_id` also keeps its misleading heading rather than becoming `unit`, and
     * the import still reads the older `category`, `rate_name` and `sell_price`
     * headings, so a sheet filled in against a previous template imports rather
     * than being rejected over a name.
     *
     * `igst` and `vat` sit beside `cgst`/`sgst` as the other two ways a rate's tax
     * can be written down - a rate is under one of them, never more than one, same
     * as the Add Product dialog itself. A row is free to leave the ones it does not
     * use blank.
     *
     * [REGIONAL_LANGUAGE_COLUMN] is not a per-product fact - see its own doc.
     * [REGIONAL_NAME_COLUMN] is, and follows it: the first names the language, the
     * second gives each product's name in that language.
     *
     * The regional pair is APPENDED rather than slotted in beside `product_name`,
     * where it would read more naturally. Every column before it keeps the position
     * it has always had, so a sheet downloaded from an older till - or one an
     * operator has been filling in for months - still lines up. The import reads by
     * heading rather than by position (see CsvUtils.parse), so a sheet with no
     * regional columns at all imports exactly as before.
     */
    val header = listOf(
        "product_name", CATEGORY_CODE_COLUMN, "hsn_code", "bar_code",
        RATE_NAME_ID_COLUMN, "rate", "unit_id", "cgst", "sgst", "igst", "vat",
        "discount", "discount_type", "selling_price", "purchase_price",
        REGIONAL_LANGUAGE_COLUMN, REGIONAL_NAME_COLUMN
    )

    /**
     * The heading the opening stock is filled in under - the quantity of the item
     * the till is to start counting from.
     *
     * A column only a till that tracks stock is given, and only one such a till
     * reads back: with Stock off there is no count for a figure to open, so putting
     * the column on the sheet would ask the operator for a number nothing would ever
     * do anything with. [ProductBulkImporter] ignores it in that case whatever the
     * sheet says - a file filled in while Stock was on must not quietly start
     * writing batches once it has been turned off.
     */
    const val STOCK_COLUMN = "stock"

    /**
     * The columns for *this* till: [header], with [STOCK_COLUMN] appended where
     * stock is tracked.
     *
     * Appended rather than slotted in among the others so that both sheets stay
     * readable as the same sheet, and so a file downloaded from a till with stock on
     * still imports on one with it off - the columns before it have not moved.
     */
    fun columns(context: Context): List<String> =
        if (GeneralSettingsDao.isStockEnabled(context)) header + STOCK_COLUMN else header

    /**
     * Example rows, taken from the operator's own item master so the sheet shows
     * real products at real rates rather than "Apple, 120".
     *
     * Between them they cover the three units and the three GST rates in use, and
     * both a discounted line and an undiscounted one, so every column is shown
     * filled in at least once. `igst` and `vat` are left blank throughout - every
     * sample line already carries its tax as `cgst`/`sgst`, and a rate is under one
     * of the three, never more than one. [REGIONAL_LANGUAGE_COLUMN] is shown filled
     * in on the first row only and blank on the rest, since one is all a sheet needs.
     *
     * [REGIONAL_NAME_COLUMN] is filled in on EVERY row, because it is the one
     * regional column that is a fact about the product rather than about the sheet.
     * The samples are the brand names respelled in the language the first row names,
     * which is what a shop actually writes there - a name off the packet, not a
     * translation of one.
     */
    val sampleRows = listOf(
        "Amul Premium Pack 100L,DEPT001,40120,,1,498.75,Ltr,2.5,2.5,,,5,P,473.81,399,Hindi,अमूल प्रीमियम पैक 100L",
        "Britannia Premium Refill 200g,DEPT002,190531,,1,393.75,PCS,9,9,,,5,P,374.06,315,,ब्रिटानिया प्रीमियम रिफिल 200g",
        "Tata Sampann Premium Value 300g,DEPT003,110412,,1,31.25,PCS,2.5,2.5,,,10,P,29.69,25,,टाटा संपन्न प्रीमियम वैल्यू 300g",
        "Fortune Premium Combo 400L,DEPT004,151219,,1,158.75,Ltr,2.5,2.5,,,5,P,150.81,127,,फॉर्च्यून प्रीमियम कॉम्बो 400L",
        "Aashirvaad Premium Regular 500KG,DEPT005,110100,,1,81.25,KG,2.5,2.5,,,10,P,77.19,65,,आशीर्वाद प्रीमियम रेगुलर 500KG"
    )

    /**
     * An opening quantity for each of [sampleRows], shown only on a till that tracks
     * stock.
     *
     * Whole numbers against the PCS lines and a fractional one against a litre line,
     * so the sheet shows without saying it that the column takes the quantity the
     * item is actually counted in rather than a count of packets.
     */
    private val sampleStock = listOf("24", "40", "18", "12.5", "60")

    /** [sampleRows], each carrying its opening quantity, for a till that tracks stock. */
    private fun sampleRows(context: Context): List<String> =
        if (!GeneralSettingsDao.isStockEnabled(context)) sampleRows
        else sampleRows.mapIndexed { i, row -> "$row,${sampleStock.getOrElse(i) { "0" }}" }

    /**
     * The sheet to hand the operator.
     *
     * A file shipped at `assets/`[FILE_NAME] wins, so a real catalogue can be put in
     * front of them without this having to change - such a file is taken as written
     * and is the one place [STOCK_COLUMN] is not added for them, since only whoever
     * shipped it knows what its columns mean. Absent one, the columns for this till
     * and the sample rows are written out - still a correct sheet to fill in, just a
     * short one.
     */
    fun content(context: Context): String =
        runCatching { context.assets.open(FILE_NAME).bufferedReader().use { it.readText() } }
            .getOrElse {
                (listOf(columns(context).joinToString(",")) + sampleRows(context))
                    .joinToString("\n") + "\n"
            }
}
