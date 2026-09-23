package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.database.BillSettingsDao
import org.json.JSONObject

/**
 * The Bill Settings fields that change how a bill is *displayed* - HSN column,
 * line numbering, customer details mode, address line, total amount font size, the
 * round-off row, amount-in-words, and whether tax was switched on at all - as
 * opposed to the
 * ones that only affect how it was *calculated* (already baked into the stored
 * amounts, so nothing to snapshot there).
 *
 * Written once per bill at creation time and read back by [BillReceiptRenderer],
 * so a later reprint reads exactly as it did on the day it was made, even after
 * these settings have since changed for new sales.
 */
object BillSettingsSnapshot {

    data class Snapshot(
        val hsnCode: Boolean,
        /** Whether the item lines were numbered when this bill was made. */
        val productSerialNumber: Boolean,
        /** Whether the time of sale was printed beside the date. */
        val timeOnBill: Boolean,
        val customerDetails: BillSettingsDao.CustomerDetails,
        val customerAddressPrinting: Boolean,
        val totalAmountFontSize: BillSettingsDao.FontSize,
        val roundOff: Boolean,
        val amountInWords: Boolean,
        /** Whether tax was switched on at all when this bill was made. Which of
         *  GST/VAT a line carries is not part of this snapshot - it is read straight
         *  off that line's own stored rates, same as it always has been. */
        val taxEnabled: Boolean,
        /** Whether the discount was taken before tax - drives where the DISCOUNT line
         *  sits in the summary (above tax for pre-tax, below it for post-tax). */
        val discountPreTax: Boolean,
        /** Whether the listed price already included tax - decides whether the
         *  displayed discount is read off the price alone or the price plus tax. */
        val inclusive: Boolean,
        /** Whether the discount was item-wise (one product's own configured share)
         *  or bill-wise (one figure entered against the whole sale) when this bill
         *  was made - decides which of [CartMath]'s two discount calculations a
         *  reprint's DISC column and DISC-column visibility follow, the same way
         *  [discountPreTax]/[inclusive] decide the calculation itself. */
        val itemwiseDiscount: Boolean
    )

    /**
     * The twelve columns of `td_bill_settings`, in the order [fromCursor] reads them.
     *
     * One list, used by every query that joins the table, so a column added here cannot
     * be added to four of the five readers and forgotten in the fifth. [alias] is the
     * name the query gives the joined table.
     */
    fun columns(alias: String): String = COLUMN_NAMES.joinToString(", ") { "$alias.$it" }

    private val COLUMN_NAMES = listOf(
        "hsn_code", "product_serial_number", "time_on_bill", "customer_details",
        "customer_address_printing", "total_amount_font_size", "round_off",
        "amount_in_words", "tax_enabled", "discount_pre_tax", "inclusive",
        "itemwise_discount"
    )

    /**
     * The snapshot starting at column [from] of [c], as selected by [columns].
     *
     * Null when the bill has no settings row - a LEFT JOIN that matched nothing, which
     * is a bill made before any of this was recorded. That is the same null [parse]
     * returned for an empty snapshot, and the renderer has always handled it.
     */
    fun fromCursor(c: android.database.Cursor, from: Int): Snapshot? {
        if (c.isNull(from)) return null
        return Snapshot(
            hsnCode = c.getInt(from) == 1,
            productSerialNumber = c.getInt(from + 1) == 1,
            timeOnBill = c.getInt(from + 2) == 1,
            customerDetails = runCatching {
                BillSettingsDao.CustomerDetails.valueOf(c.getString(from + 3))
            }.getOrDefault(BillSettingsDao.CustomerDetails.ONLY_MOBILE),
            customerAddressPrinting = c.getInt(from + 4) == 1,
            totalAmountFontSize = runCatching {
                BillSettingsDao.FontSize.valueOf(c.getString(from + 5))
            }.getOrDefault(BillSettingsDao.FontSize.REGULAR),
            roundOff = c.getInt(from + 6) == 1,
            amountInWords = c.getInt(from + 7) == 1,
            taxEnabled = c.getInt(from + 8) == 1,
            discountPreTax = c.getInt(from + 9) == 1,
            inclusive = c.getInt(from + 10) == 1,
            itemwiseDiscount = c.getInt(from + 11) == 1
        )
    }

    /**
     * The id of the row describing these settings, adding one if this combination is new.
     *
     * INSERT OR IGNORE then SELECT, leaning on the table's UNIQUE constraint across every
     * column: it is the constraint that decides whether this combination has been seen,
     * so letting it decide avoids both a second query and the gap between checking and
     * inserting. Two tills billing at once therefore cannot create a duplicate row.
     */
    fun idFor(
        db: android.database.sqlite.SQLiteDatabase,
        settings: BillSettingsDao.BillSettings,
        taxEnabled: Boolean,
        discountPreTax: Boolean,
        inclusive: Boolean,
        itemwiseDiscount: Boolean
    ): Long? {
        val values = arrayOf<Any>(
            if (settings.hsnCode) 1 else 0,
            if (settings.productSerialNumber) 1 else 0,
            if (settings.timeOnBill) 1 else 0,
            settings.customerDetails.name,
            if (settings.customerAddressPrinting) 1 else 0,
            settings.totalAmountFontSize.name,
            if (settings.roundOff) 1 else 0,
            if (settings.amountInWords) 1 else 0,
            if (taxEnabled) 1 else 0,
            if (discountPreTax) 1 else 0,
            if (inclusive) 1 else 0,
            if (itemwiseDiscount) 1 else 0
        )
        val names = COLUMN_NAMES.joinToString(", ")
        val placeholders = COLUMN_NAMES.joinToString(", ") { "?" }
        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE ($names) VALUES ($placeholders)", values
        )
        val where = COLUMN_NAMES.joinToString(" AND ") { "$it = ?" }
        return db.rawQuery(
            "SELECT id FROM $TABLE WHERE $where LIMIT 1",
            values.map { it.toString() }.toTypedArray()
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    /**
     * The settings row [id] names, or null for a bill that has none.
     *
     * A lookup of its own rather than a join, because its caller reads its bill by fixed
     * column INDEX: widening that query from one column to twelve would renumber every
     * column after it, and a reprint that silently reads the wrong ones is a worse fault
     * than one extra keyed lookup per reprint.
     */
    fun byId(db: android.database.sqlite.SQLiteDatabase, id: Long?): Snapshot? {
        if (id == null || id <= 0L) return null
        return db.rawQuery(
            "SELECT ${COLUMN_NAMES.joinToString(", ")} FROM $TABLE WHERE id = ?",
            arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) fromCursor(c, 0) else null }
    }

    private const val TABLE = "td_bill_settings"

    fun serialize(
        settings: BillSettingsDao.BillSettings,
        taxEnabled: Boolean,
        discountPreTax: Boolean,
        inclusive: Boolean,
        itemwiseDiscount: Boolean
    ): String =
        JSONObject().apply {
            put("hsnCode", settings.hsnCode)
            put("productSerialNumber", settings.productSerialNumber)
            put("timeOnBill", settings.timeOnBill)
            put("customerDetails", settings.customerDetails.name)
            put("customerAddressPrinting", settings.customerAddressPrinting)
            put("totalAmountFontSize", settings.totalAmountFontSize.name)
            put("roundOff", settings.roundOff)
            put("amountInWords", settings.amountInWords)
            put("taxEnabled", taxEnabled)
            put("discountPreTax", discountPreTax)
            put("inclusive", inclusive)
            put("itemwiseDiscount", itemwiseDiscount)
        }.toString()

    /** Null when [json] is blank or unreadable - an older bill saved before this existed. */
    fun parse(json: String?): Snapshot? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            Snapshot(
                hsnCode = o.optBoolean("hsnCode"),
                // Bills made before this was a choice were all numbered, so their
                // reprints stay numbered rather than quietly changing shape.
                productSerialNumber = o.optBoolean("productSerialNumber", true),
                // Bills taken before this was a setting were all printed WITH the
                // time, so that is what a reprint of one has to show.
                timeOnBill = o.optBoolean("timeOnBill", true),
                customerDetails = runCatching { BillSettingsDao.CustomerDetails.valueOf(o.getString("customerDetails")) }
                    .getOrDefault(BillSettingsDao.CustomerDetails.ONLY_MOBILE),
                customerAddressPrinting = o.optBoolean("customerAddressPrinting"),
                totalAmountFontSize = runCatching { BillSettingsDao.FontSize.valueOf(o.getString("totalAmountFontSize")) }
                    .getOrDefault(BillSettingsDao.FontSize.REGULAR),
                roundOff = o.optBoolean("roundOff"),
                amountInWords = o.optBoolean("amountInWords"),
                // New bills write "taxEnabled" directly. An older bill's JSON only has
                // "taxRegime" (GST/VAT/NONE) - read that instead, so a bill sold before
                // this change keeps recomputing exactly as it did on the day of sale.
                taxEnabled = if (o.has("taxEnabled")) o.optBoolean("taxEnabled", true) else {
                    runCatching { GstCalculator.TaxRegime.valueOf(o.getString("taxRegime")) }
                        .getOrNull()?.let { it != GstCalculator.TaxRegime.NONE } ?: true
                },
                discountPreTax = o.optBoolean("discountPreTax", true),
                inclusive = o.optBoolean("inclusive", false),
                // Bills made before this was captured default to item-wise, matching
                // TaxSettingsDao.TaxSettings' own default - the choice most likely to
                // have been active before this field existed to record it.
                itemwiseDiscount = o.optBoolean("itemwiseDiscount", true)
            )
        }.getOrNull()
    }
}
