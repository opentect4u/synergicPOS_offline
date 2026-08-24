package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.database.BillSettingsDao
import org.json.JSONObject

/**
 * The Bill Settings fields that change how a bill is *displayed* - HSN column,
 * line numbering, customer details mode, address line, total amount font size, the
 * round-off row, amount-in-words, and which tax regime (GST/VAT) was active - as
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
        val customerDetails: BillSettingsDao.CustomerDetails,
        val customerAddressPrinting: Boolean,
        val totalAmountFontSize: BillSettingsDao.FontSize,
        val roundOff: Boolean,
        val amountInWords: Boolean,
        val taxRegime: GstCalculator.TaxRegime,
        /** Whether the discount was taken before tax - drives where the DISCOUNT line
         *  sits in the summary (above tax for pre-tax, below it for post-tax). */
        val discountPreTax: Boolean,
        /** Whether the listed price already included tax - decides whether the
         *  displayed discount is read off the price alone or the price plus tax. */
        val inclusive: Boolean
    )

    fun serialize(
        settings: BillSettingsDao.BillSettings,
        taxRegime: GstCalculator.TaxRegime,
        discountPreTax: Boolean,
        inclusive: Boolean
    ): String =
        JSONObject().apply {
            put("hsnCode", settings.hsnCode)
            put("productSerialNumber", settings.productSerialNumber)
            put("customerDetails", settings.customerDetails.name)
            put("customerAddressPrinting", settings.customerAddressPrinting)
            put("totalAmountFontSize", settings.totalAmountFontSize.name)
            put("roundOff", settings.roundOff)
            put("amountInWords", settings.amountInWords)
            put("taxRegime", taxRegime.name)
            put("discountPreTax", discountPreTax)
            put("inclusive", inclusive)
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
                customerDetails = runCatching { BillSettingsDao.CustomerDetails.valueOf(o.getString("customerDetails")) }
                    .getOrDefault(BillSettingsDao.CustomerDetails.ONLY_MOBILE),
                customerAddressPrinting = o.optBoolean("customerAddressPrinting"),
                totalAmountFontSize = runCatching { BillSettingsDao.FontSize.valueOf(o.getString("totalAmountFontSize")) }
                    .getOrDefault(BillSettingsDao.FontSize.REGULAR),
                roundOff = o.optBoolean("roundOff"),
                amountInWords = o.optBoolean("amountInWords"),
                taxRegime = runCatching { GstCalculator.TaxRegime.valueOf(o.getString("taxRegime")) }
                    .getOrDefault(GstCalculator.TaxRegime.GST),
                discountPreTax = o.optBoolean("discountPreTax", true),
                inclusive = o.optBoolean("inclusive", false)
            )
        }.getOrNull()
    }
}
