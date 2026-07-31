package com.example.synergic_pos_offline.utils

import android.content.Context

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
     * The sheet to hand the operator.
     *
     * A file shipped at `assets/`[FILE_NAME] wins, so a real catalogue can be put in
     * front of them without this having to change. Absent one, the header and
     * [sampleRows] are written out - still a correct sheet to fill in, just a short
     * one.
     */
    fun content(context: Context): String =
        runCatching { context.assets.open(FILE_NAME).bufferedReader().use { it.readText() } }
            .getOrElse { (listOf(header.joinToString(",")) + sampleRows).joinToString("\n") + "\n" }
}
