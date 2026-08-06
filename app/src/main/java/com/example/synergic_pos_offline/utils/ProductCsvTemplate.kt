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
     * The columns, in order.
     *
     * `category` and `unit_id` are filled in by name - "Dairy", "Ltr" - not by id:
     * the operator writing the sheet knows what the thing is called and has no way
     * to know what number this database gave it. [ProductBulkImporter] resolves each
     * to an id, creating the master record where the till has never seen that name
     * before.
     *
     * `unit_id` keeps its name rather than becoming `unit`, and the import still
     * reads the older `sell_price`, so a sheet filled in against a previous template
     * imports rather than being rejected over a heading.
     */
    val header = listOf(
        "product_name", "category", "hsn_code", "bar_code",
        "rate_name", "rate", "unit_id", "cgst", "sgst",
        "discount", "discount_type", "selling_price", "purchase_price"
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
     * filled in at least once.
     */
    val sampleRows = listOf(
        "Amul Premium Pack 100L,Dairy,40120,,Regular,498.75,Ltr,2.5,2.5,5,P,473.81,399",
        "Britannia Premium Refill 200g,Bakery & Biscuits,190531,,Regular,393.75,PCS,9,9,5,P,374.06,315",
        "Tata Sampann Premium Value 300g,Dry Fruits & Cereals,110412,,Regular,31.25,PCS,2.5,2.5,10,P,29.69,25",
        "Fortune Premium Combo 400L,Oil & Ghee Masala,151219,,Regular,158.75,Ltr,2.5,2.5,5,P,150.81,127",
        "Aashirvaad Premium Regular 500KG,Atta Rice & Dal,110100,,Regular,81.25,KG,2.5,2.5,10,P,77.19,65"
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
