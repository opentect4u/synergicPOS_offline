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
     * The heading a row carries its product's own id under - 1, 2, 3, 4.
     *
     * The product master's row id, the number the rest of this database refers to a
     * product by. Exported so the sheet in front of the operator is the till's
     * catalogue with its own numbering on it, rather than an anonymous list they have
     * to match up by name.
     *
     * On the way back in, a row's id is HONOURED WHERE IT IS FREE and quietly
     * reassigned where it is not. That is the whole rule, and it is worth being plain
     * about both halves. Honouring it is what makes download-edit-upload a round trip:
     * a Replace clears the catalogue and puts it back under the same numbers, so
     * anything holding a product id still points at the same product. Reassigning is
     * what stops a sheet from ever landing on top of a product that is still there -
     * an id already taken belongs to a product that has been sold, and writing over it
     * would re-label somebody's till history. The upload says how many rows that
     * happened to.
     *
     * A blank cell is the normal case for a sheet someone is filling in by hand, and
     * takes the next id the way it always did.
     */
    const val PRODUCT_ID_COLUMN = "product_id"

    /**
     * The heading a row names its category under: the department's NAME.
     *
     * "Dairy", "Beverages" - what the Category/Department master calls it and what the
     * operator filling the sheet in actually knows. This is the column on the
     * handed-out sheet and the first one read.
     *
     * ## Why a name rather than the id
     *
     * The sheet carried the plain id for a while, on the reasoning that an id is exact
     * where a name is not. It IS exact - and it is also unreadable. A spreadsheet
     * column of 1, 2, 3 tells whoever is filling it in nothing about which department
     * each row is going into, cannot be checked by eye against the file, and cannot be
     * typed at all without the Category master open beside them to look each number
     * up. A catalogue is edited by people, and the column has to say what it means.
     *
     * The trade is real and worth stating: a NAME CAN CREATE a department. A row
     * naming one this till does not have gets that department made for it, so a
     * misspelt "Diary" quietly becomes a second category rather than landing in the
     * one that was meant. The upload's preview names every category it is about to
     * create, before anything is written, which is where a typo is caught - see
     * BulkUploadProductFragment.
     *
     * Matching is case-insensitive and trimmed, so "dairy " lands in "Dairy".
     * [CATEGORY_ID_COLUMN], [CATEGORY_CODE_COLUMN] and the older `category` heading
     * are all still read after this one, for files filled in against previous
     * templates.
     */
    const val CATEGORY_NAME_COLUMN = "category_name"

    /**
     * The heading a row names its category under by ID - 1, 2, 3, 4.
     *
     * NOT ON THE HANDED-OUT SHEET any more - [CATEGORY_NAME_COLUMN] carries the
     * department now, because a number is not something a person can fill in or check.
     * This is still READ, after that one, so a file filled in against the previous
     * template still lands its products in the right department.
     *
     * It is what `md_products.category_id` actually holds, so a sheet naming it needs
     * nothing resolved or matched - but an id can only REFER to a category, never
     * create one, so an id this till has no department for leaves the product
     * uncategorised and is counted for the operator.
     */
    const val CATEGORY_ID_COLUMN = "category_id"

    /**
     * The heading a row names its category under: the Dept Code, not the name.
     *
     * NOT ON THE HANDED-OUT SHEET any more - [CATEGORY_ID_COLUMN] carries the
     * department now, as the plain id the database stores. This is still READ, after
     * that one, so a file filled in against a previous template still lands its
     * products in the right department.
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
     * Off the handed-out sheet and still read, for the same reason as
     * [CATEGORY_CODE_COLUMN]. The shop's master names a rate by the unit beside it -
     * `UNIT_1` with `RATE_1` - rather than by a rate-name row, so a sheet in that
     * format leaves the rate unnamed.
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
     * `PRODUCT_UNI_NAME` on the shop's own master - the second column, beside the
     * English one - which is what this sheet is now reproduced from. The older
     * `regional_name` heading is still read, so a file filled in against a previous
     * template still imports; see [ProductBulkImporter.REGIONAL_NAME_COLUMNS].
     *
     * A per-product fact, unlike [REGIONAL_LANGUAGE_COLUMN] - one names the language
     * the sheet is written in, this gives each product's name in it.
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
    const val REGIONAL_NAME_COLUMN = "product_uni_name"

    /**
     * How many unit/rate pairs one product row can carry - `UNIT_1..UNIT_4` with
     * `RATE_1..RATE_4` beside them.
     *
     * Four because four is what the shop's master has, and this sheet is that master.
     * The number is written once here so the columns, the export and the import all
     * agree on it rather than each counting to four separately.
     */
    const val RATE_SLOTS = 4

    /**
     * The columns, in order - the shop's own sheet, with IGST added.
     *
     * This is not a layout invented here. It is the item master these tills have
     * always been filled in from, reproduced heading for heading so an operator can
     * carry on using the file they already keep, and so a sheet exported from the
     * shop's other system uploads without being rearranged first.
     *
     * ## Four units, four rates
     *
     * UNIT_1..UNIT_4 with RATE_1..RATE_4 beside them: one product row can describe up
     * to [RATE_SLOTS] ways of selling the same item - by piece, by half, by kilo -
     * each with its own price. Each becomes a rate row on the product and the first
     * becomes the default. It is the RATE that decides a slot is used: a slot with no
     * rate is not a price this shop sells at, whatever its unit cell says, and a
     * priced slot with no unit is still a price and still imports.
     *
     * ## PRODUCT_ID and CATEGORY_NAME
     *
     * The two lead, because they are what the row IS rather than what it says: which
     * product this is, and which department it belongs to.
     *
     * PRODUCT_ID is a plain number - 1, 2, 3, 4 - the same one the Products screen
     * shows and the database stores. CATEGORY is the department's NAME rather than its
     * number, because that column is filled in and checked by a person: a column of
     * bare ids cannot be read, and cannot be typed without the Category master open
     * beside the spreadsheet. See [PRODUCT_ID_COLUMN] and [CATEGORY_NAME_COLUMN] for
     * what each does on the way back in, and what naming a department costs.
     *
     * ## PRODUCT_IGST is the addition
     *
     * Placed with the other two rates rather than at the end, because SGST, CGST and
     * IGST are three answers to one question and belong read together. A row uses the
     * pair or the single, never both - the same rule the Add Product form follows.
     *
     * VAT_FLAG holds a VAT rate where the goods are under VAT instead. STOCK is the
     * opening quantity and is ignored on a till that does not track stock.
     */
    val header: List<String> =
        listOf(
            PRODUCT_ID_COLUMN.uppercase(), "PRODUCT_NAME",
            REGIONAL_NAME_COLUMN.uppercase(), CATEGORY_NAME_COLUMN.uppercase()
        ) +
            (1..RATE_SLOTS).map { "UNIT_$it" } +
            (1..RATE_SLOTS).map { "RATE_$it" } +
            listOf(
                "PRODUCT_SGST", "PRODUCT_CGST", "PRODUCT_IGST", "VAT_FLAG",
                "PRODUCT_DISCOUNT", STOCK_COLUMN, "HSN_NUMBER", "BAR_CODE",
                "PURCHASE_PRICE", "selling_price", REGIONAL_LANGUAGE_COLUMN
            )

    /**
     * The unit and rate cells of slot [slot] (1-based), as the pair of headings they
     * are read back under - lower case, the way a parsed sheet is keyed.
     *
     * Both the export and the import ask for a slot's columns here rather than
     * building "unit_$slot" in three places, so a sheet written under one spelling
     * cannot be read back under another.
     */
    fun slotColumns(slot: Int): Pair<String, String> = "unit_$slot" to "rate_$slot"

    /**
     * The heading the opening stock is filled in under - the quantity of the item
     * the till is to start counting from.
     *
     * A column every sheet carries, because it is a column of the shop's own master -
     * but only a till that tracks stock reads it back. [ProductBulkImporter] reads
     * past it whatever the sheet says when Stock is off: the setting, not the file,
     * decides whether this till counts anything, and a sheet filled in while Stock
     * was on must not quietly start writing batches once it has been turned off.
     */
    const val STOCK_COLUMN = "stock"

    /**
     * The columns for this till.
     *
     * STOCK is part of the sheet now rather than appended for a till that tracks it -
     * it is a column of the shop's own master, and a sheet whose shape depends on a
     * setting is a sheet that stops lining up when the setting moves. A till with
     * stock off simply ignores what is in it; see [ProductBulkImporter], which will
     * not write batches for a till that keeps no count.
     */
    fun columns(context: Context): List<String> = header

    /**
     * Example rows, in the shop's own master format.
     *
     * They are the shop's real dishes at their real rates, taken from the sheet this
     * template was reproduced from - a template of invented products teaches the
     * operator nothing about how their own file should look.
     *
     * Between them they show what each column is for: a product sold three ways, one
     * sold two ways, ones sold a single way, a CGST/SGST pair, a lone IGST, a VAT
     * line and an untaxed line.
     *
     * The ids run 1, 2, 3, 4, 5 and the first three rows share one department, which is
     * what those two columns look like on a real sheet: [PRODUCT_ID_COLUMN] counts the
     * rows, [CATEGORY_NAME_COLUMN] repeats wherever products belong together - and it
     * repeats as the same WORD, which is what makes a mistyped one visible in a
     * spreadsheet where a mistyped number would not be.
     *
     * The unit cells are written as the symbols the Unit master holds, since that is
     * what someone filling this in by hand should write. A sheet exported from another
     * system often carries that system's unit ID there instead, and a whole number is
     * read as one - see [ProductBulkImporter.unitIdForSlot].
     *
     * ## The two language cells are left EMPTY here
     *
     * [REGIONAL_NAME_COLUMN] and [REGIONAL_LANGUAGE_COLUMN] are filled in by
     * [sampleRows] (the Context one) against whatever language the till is on, not
     * written into these strings. They used to be hard-coded Marathi, so a shop
     * working in Hindi downloaded a template whose examples were in a script they do
     * not use and whose language column said MARATHI - and uploading it unedited set
     * the till to Marathi. A template is meant to be an example of THIS shop's sheet.
     */
    val sampleRows = listOf(
        "1,GINGER CRISPY,,Chinese,PLT,HALF,,,220,120,,,2.5,2.5,,,0,100,21069099,8901234500011,150,220,",
        "2,VEG MANCHURIAN,,Chinese,PLT,HALF,QTR,,160,90,50,,2.5,2.5,,,0,100,21069099,,100,160,",
        "3,PANEER CHILLY,,Chinese,PLT,,,,210,,,,,,5,,5,100,21069099,8901234500028,140,210,",
        "4,SCHEZWAN SAUCE,,Sauces,PCS,,,,25,,,,,,,5,0,250,21039090,,18,25,",
        "5,COLD DRINK,,Beverages,BTL,,,,40,,,,,,,,0,60,22021010,8901234500042,30,40,"
    )

    /**
     * [sampleRows] with the two language cells filled in for THIS till.
     *
     * The regional name is written on every row, in the language picked at the top of
     * the Products master - the same translation that screen shows in its Regional
     * Name column, through the same [ProductName.inPrintLanguage] the download uses.
     * So the example the operator opens is an example of their own sheet: it shows
     * what goes in that column, in the script they actually read.
     *
     * The LANGUAGE is named on the first row only, since one is all a sheet needs -
     * see [REGIONAL_LANGUAGE_COLUMN]. Filling every row would be filling in the same
     * answer five times and would teach the operator to do the same.
     *
     * In English both cells stay empty, and that is right rather than a gap: English
     * is the absence of a regional name (see [ProductName.applies]), so a template on
     * an English till shows the column present and unfilled, which is exactly how an
     * English shop's own sheet looks.
     */
    private fun sampleRows(context: Context): List<String> = sampleRows(AppLanguage.of(context))

    /**
     * [sampleRows] filled in for [language] - the testable half of the Context one
     * above, which only reads which language the till is on.
     */
    fun sampleRows(language: PrintLanguage.Language): List<String> {
        val nameAt = header.indexOf(REGIONAL_NAME_COLUMN.uppercase())
        val languageAt = header.indexOf(REGIONAL_LANGUAGE_COLUMN)
        val englishAt = header.indexOf("PRODUCT_NAME")
        return sampleRows.mapIndexed { row, line ->
            val cells = splitCsvRow(line).toMutableList()
            cells[nameAt] = ProductName.inPrintLanguage(language, cells[englishAt])
            if (row == 0 && ProductName.applies(language)) cells[languageAt] = language.englishName
            cells.joinToString(",")
        }
    }

    /**
     * The sheet to hand the operator.
     *
     * A file shipped at `assets/`[FILE_NAME] wins, so a real catalogue can be put in
     * front of them without this having to change - such a file is taken exactly as
     * written, since only whoever shipped it knows what its columns mean. Absent one,
     * the columns and the sample rows are written out - still a correct sheet to fill
     * in, just a short one.
     */
    fun content(context: Context): String =
        runCatching { context.assets.open(FILE_NAME).bufferedReader().use { it.readText() } }
            .getOrElse {
                (listOf(columns(context).joinToString(",")) + sampleRows(context))
                    .joinToString("\n") + "\n"
            }

    /** What the downloaded workbook is called. */
    const val EXCEL_FILE_NAME = "item_master_template.xlsx"

    /**
     * The same sheet as [content], as rows of cells rather than as one comma string -
     * which is what a workbook is written from. See [Xlsx].
     *
     * Split from the CSV rather than parsed back out of it: a template cell may
     * legitimately hold a comma (a category once did), and splitting the finished CSV
     * on commas would tear such a row into the wrong number of columns. The rows are
     * built from the same two lists the CSV is built from, so the two files carry the
     * same sheet by construction.
     *
     * A sheet SHIPPED as an asset stays the caller's business: it is text, it may have
     * been written by hand, and it is handed over as it is rather than being taken
     * apart and rebuilt here.
     */
    fun rows(context: Context): List<List<String>> =
        listOf(columns(context)) + sampleRows(context).map { splitCsvRow(it) }

    /**
     * One template row, split on the commas that separate cells.
     *
     * The sample rows are written as plain strings in this file and hold no quoted
     * commas, so a plain split is right for them - but it is written as its own
     * function so that a row which ever does gain one fails here, where it can be
     * seen, rather than silently shifting every column after it.
     */
    private fun splitCsvRow(row: String): List<String> = row.split(",")
}
