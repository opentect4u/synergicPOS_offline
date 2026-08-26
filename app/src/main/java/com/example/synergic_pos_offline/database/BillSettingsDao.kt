package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the Bill Settings as key/value rows in
 * [DatabaseHelper.Tables.MD_APP_SETTINGS], scoped to the current store.
 *
 * Each setting is one row (setting_name / setting_value / setting_type). Booleans
 * are stored as "1"/"0" (type 'B'), integers as text (type 'I') and text as-is
 * (type 'T').
 */
class BillSettingsDao(context: Context) {

    private val appContext = context.applicationContext
    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_APP_SETTINGS

    /** How the bill number resets over time. Persisted as a single-letter [code]. */
    enum class ResetMode(val code: String) {
        DAILY("D"), MONTHLY("M"), YEARLY("Y"), CONTINUE("C");

        companion object {
            fun fromCode(value: String?): ResetMode? =
                value?.let { v -> values().firstOrNull { it.code.equals(v, true) || it.name.equals(v, true) } }
        }
    }

    /** Which customer details are captured/printed on a bill. Persisted as [code] 1-5. */
    enum class CustomerDetails(val code: Int, val label: String) {
        ONLY_MOBILE(1, "Only mobile no."),
        MOBILE_NAME(2, "Mobile no. & name"),
        MOBILE_NAME_GSTIN(3, "Mobile no., Name & GSTIN"),
        ONLY_NAME(4, "Only Name"),
        ONLY_GSTIN(5, "Only GSTIN");

        companion object {
            /** Accepts the stored code (1-5) or the display label. */
            fun fromStored(value: String?): CustomerDetails? = value?.let { v ->
                values().firstOrNull { it.code.toString() == v || it.label.equals(v, true) }
            }
        }
    }

    /** Font size of the total amount on the bill. Persisted as [code] (R / B). */
    enum class FontSize(val code: String, val label: String) {
        REGULAR("R", "Regular"), BIG("B", "Big");
        companion object {
            /** Accepts the stored code (R/B) or the display label. */
            fun fromStored(value: String?): FontSize? = value?.let { v ->
                values().firstOrNull { it.code.equals(v, true) || it.label.equals(v, true) }
            }
        }
    }

    /**
     * Layout format of the printed bill - what Printer Settings > Print Template
     * picks, and the format the whole app prints in. Persisted as [code] (N / L /
     * C / T); the codes are unchanged from when this was only a Bill Settings
     * dropdown, so a till that already chose one keeps it.
     *
     * [STANDARD], [CLASSIC] and [TAX_WISE_SHORT] are built; [CLUBBED] is named here
     * because the choice is the operator's to make and store now, but its layout is
     * still to come.
     */
    enum class BillFormat(val code: String, val label: String) {
        STANDARD("N", "Standard"),
        CLASSIC("L", "Classic"),
        CLUBBED("C", "Clubbed"),
        TAX_WISE_SHORT("T", "Tax wise short");

        /** True once the layout for this format exists and can be previewed/printed. */
        val implemented: Boolean get() = this != CLUBBED

        companion object {
            /** Accepts the stored code (N/L/C/T) or the display label. */
            fun fromStored(value: String?): BillFormat? = value?.let { v ->
                values().firstOrNull { it.code.equals(v, true) || it.label.equals(v, true) }
            }
        }
    }

    /** The full set of bill settings with sensible defaults. */
    data class BillSettings(
        val roundOff: Boolean = false,
        val amountInWords: Boolean = false,
        val twoCopyBill: Boolean = false,
        val startBillNo: Int = 0,
        val resetMode: ResetMode = ResetMode.CONTINUE,
        val billNoCharEnabled: Boolean = false,
        val billNoCharPrefix: String = "",
        val hsnCode: Boolean = false,
        /**
         * Whether each line is numbered on the printed bill - the SR.NO down the left
         * of the item table.
         *
         * Defaults ON, which is what every till did before this was a choice: a shop
         * that upgrades keeps printing the bill it printed yesterday, and only a shop
         * that turns it off sees a change.
         */
        val productSerialNumber: Boolean = true,
        /**
         * Whether the time of sale is printed beside the date.
         *
         * The DATE has no switch and never will: a bill is a dated record, and a shop
         * has to be able to say which day a sale happened on. The time of day is the
         * part that is a genuine preference - a restaurant wants it to tell two
         * sittings on the same table apart, a shop that bills a customer once a day
         * does not, and on 58mm paper it is a line's worth of width either way.
         *
         * Defaults ON, which is what every till did before this was a choice: a shop
         * that upgrades keeps printing the bill it printed yesterday, and only a shop
         * that turns it off sees a change.
         */
        val timeOnBill: Boolean = true,
        /**
         * Which customer fields the bill prints.
         *
         * Defaults to mobile AND name. A bill that names the customer is the one a
         * counter can hand to the right person out of three waiting, and a take-away
         * is called out by name rather than by number - so a till that has never been
         * asked should print both. The number alone was the old default and is still
         * a choice, for a shop that would rather not put a name on paper.
         */
        val customerDetails: CustomerDetails = CustomerDetails.MOBILE_NAME,
        val customerAddressPrinting: Boolean = false,
        val totalAmountFontSize: FontSize = FontSize.REGULAR,
        /**
         * The layout a bill prints in, until a till chooses otherwise.
         *
         * CLASSIC is the default: it is the narrow-roll receipt these tills actually
         * print on, with the bill number, date and time sharing one head line and the
         * item table sized for 58mm paper. Standard is the wider, more spacious slip -
         * a fine choice on 80mm, and the wrong thing to hand a shop that has not been
         * asked which paper it has.
         *
         * Only tills that have never stored a format are affected: a shop that has
         * picked one keeps it, and Print Template is where it is changed.
         */
        val billFormat: BillFormat = BillFormat.CLASSIC,
        /**
         * Whether a UPI payment QR is printed on a bill settled by UPI.
         *
         * Off by default, and it stays off until a [upiId] is entered - a code built
         * without one would be a square the customer cannot pay into.
         *
         * A bill settled Online prints the code regardless of this: that payment mode
         * says the customer is paying from their phone and has no other tender to pay
         * with, so the code is the bill doing its job rather than an extra the shop
         * opted into. This setting decides the UPI case, where the shop may already
         * have a code on the counter and a second one per slip is only paper.
         */
        val upiQrEnabled: Boolean = false,
        /** The shop's UPI address the printed code pays into - `shop@okaxis`. */
        val upiId: String = "",
        /**
         * The name the payment app shows the customer before they confirm. Blank is
         * allowed: the app then falls back to whatever the bank has on file for
         * [upiId], which is the shop's registered name.
         */
        val upiPayeeName: String = "",
        /**
         * Whether a bill also prints a coupon per category - see `CouponPrinter`.
         *
         * Off by default. A shop with one counter has nothing to split: the coupons
         * would be a second copy of the bill in pieces, on paper nobody asked for.
         */
        val couponSplit: Boolean = false
    )

    /** Reads every bill setting for the current store, applying defaults. */
    fun load(): BillSettings {
        val map = readAll()
        val d = BillSettings()
        return BillSettings(
            roundOff = map[KEY_ROUND_OFF]?.toBool() ?: d.roundOff,
            amountInWords = map[KEY_AMOUNT_IN_WORDS]?.toBool() ?: d.amountInWords,
            twoCopyBill = map[KEY_TWO_COPY]?.toBool() ?: d.twoCopyBill,
            startBillNo = map[KEY_START_NO]?.toIntOrNull() ?: d.startBillNo,
            resetMode = ResetMode.fromCode(map[KEY_RESET_MODE]) ?: d.resetMode,
            billNoCharEnabled = map[KEY_CHAR_ENABLED]?.toBool() ?: d.billNoCharEnabled,
            billNoCharPrefix = map[KEY_CHAR_PREFIX] ?: d.billNoCharPrefix,
            hsnCode = map[KEY_HSN_CODE]?.toBool() ?: d.hsnCode,
            productSerialNumber = map[KEY_PRODUCT_SERIAL]?.toBool() ?: d.productSerialNumber,
            timeOnBill = map[KEY_TIME_ON_BILL]?.toBool() ?: d.timeOnBill,
            customerDetails = CustomerDetails.fromStored(map[KEY_CUSTOMER_DETAILS]) ?: d.customerDetails,
            customerAddressPrinting = map[KEY_CUSTOMER_ADDRESS_PRINTING]?.toBool() ?: d.customerAddressPrinting,
            totalAmountFontSize = FontSize.fromStored(map[KEY_TOTAL_FONT_SIZE]) ?: d.totalAmountFontSize,
            billFormat = BillFormat.fromStored(map[KEY_BILL_FORMAT]) ?: d.billFormat,
            upiQrEnabled = map[KEY_UPI_QR_ENABLED]?.toBool() ?: d.upiQrEnabled,
            upiId = map[KEY_UPI_ID] ?: d.upiId,
            upiPayeeName = map[KEY_UPI_PAYEE_NAME] ?: d.upiPayeeName,
            couponSplit = map[KEY_COUPON_SPLIT]?.toBool() ?: d.couponSplit
        )
    }

    /** Writes every bill setting for the current store (upsert per key). All rows
     *  use setting_type 'B' since these are bill settings. */
    fun save(s: BillSettings) {
        put(KEY_ROUND_OFF, if (s.roundOff) "1" else "0")
        put(KEY_AMOUNT_IN_WORDS, if (s.amountInWords) "1" else "0")
        put(KEY_TWO_COPY, if (s.twoCopyBill) "1" else "0")
        put(KEY_START_NO, s.startBillNo.toString())
        put(KEY_RESET_MODE, s.resetMode.code)
        put(KEY_CHAR_ENABLED, if (s.billNoCharEnabled) "1" else "0")
        put(KEY_CHAR_PREFIX, s.billNoCharPrefix.take(3))
        put(KEY_HSN_CODE, if (s.hsnCode) "1" else "0")
        put(KEY_PRODUCT_SERIAL, if (s.productSerialNumber) "1" else "0")
        put(KEY_TIME_ON_BILL, if (s.timeOnBill) "1" else "0")
        put(KEY_CUSTOMER_DETAILS, s.customerDetails.code.toString())
        put(KEY_CUSTOMER_ADDRESS_PRINTING, if (s.customerAddressPrinting) "1" else "0")
        put(KEY_TOTAL_FONT_SIZE, s.totalAmountFontSize.code)
        put(KEY_BILL_FORMAT, s.billFormat.code)
        put(KEY_UPI_QR_ENABLED, if (s.upiQrEnabled) "1" else "0")
        put(KEY_UPI_ID, s.upiId.trim())
        put(KEY_UPI_PAYEE_NAME, s.upiPayeeName.trim())
        put(KEY_COUPON_SPLIT, if (s.couponSplit) "1" else "0")
        refreshCache()
    }

    /**
     * Writes just the bill format, leaving every other bill setting as it is.
     *
     * The Print Template screen changes this one setting on each tap, and has no UI
     * for the rest - going through [save] would write back whatever a stale
     * [BillSettings] happened to hold for them.
     */
    fun saveBillFormat(format: BillFormat) {
        put(KEY_BILL_FORMAT, format.code)
        refreshCache()
    }

    /**
     * Paper width the Print Template preview is drawn at, in mm.
     *
     * Kept apart from [BillSettings] deliberately: this chooses how the *preview* is
     * laid out, not how a bill is printed. The paper actually loaded in a printer is
     * that printer's own setting, in Printer Settings > Connections, since a till can
     * run an 80mm bill printer and a 58mm kitchen printer at once.
     */
    fun loadTemplatePaperMm(): Int =
        readAll()[KEY_TEMPLATE_PAPER]?.toIntOrNull()?.takeIf { it == 58 || it == 80 }
            ?: DEFAULT_TEMPLATE_PAPER_MM

    fun saveTemplatePaperMm(mm: Int) {
        put(KEY_TEMPLATE_PAPER, mm.toString())
        refreshCache()
    }

    /** Regroups the settings rows by type and republishes them to [SettingsCache]. */
    private fun refreshCache() {
        helper.regroupAppSettingsByType()
        com.example.synergic_pos_offline.utils.SettingsCache.storeFromDb(appContext, "Bill settings save (type B)")
    }

    /** True if any bill exists (used to warn before changing the start bill no). */
    fun hasBills(): Boolean {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_BILLS}", null
        ).use { c -> return c.moveToFirst() && c.getLong(0) > 0 }
    }

    /**
     * Deletes every bill and its related rows. Required when the start bill number
     * is changed while bills already exist, so numbering can restart cleanly.
     */
    fun clearAllBills() {
        helper.writableDatabase.apply {
            beginTransaction()
            try {
                execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_BILL_PRINTS}")
                execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_PAYMENTS}")
                execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_KOT_ITEMS}")
                execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_KOT}")
                execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS}")
                execSQL("DELETE FROM ${DatabaseHelper.Tables.TD_BILLS}")
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    // ---- Low-level key/value access ----------------------------------------

    private fun readAll(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        val store = currentStoreId()
        val (where, args) = if (store != null) "store_id=?" to arrayOf(store.toString()) else null to null
        helper.readableDatabase.query(
            table, arrayOf("setting_name", "setting_value"),
            where, args, null, null, "setting_type ASC, setting_name ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                map[name] = c.getString(1).orEmpty()
            }
        }
        return map
    }

    /** Inserts or updates a single setting row for the current store. Bill settings
     *  always use setting_type 'B'. */
    private fun put(name: String, value: String) {
        val db = helper.writableDatabase
        val store = currentStoreId()
        val values = ContentValues().apply {
            put("setting_name", name)
            put("setting_value", value)
            put("setting_type", "B")
            put("device_id", currentDeviceId())
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        val where = if (store != null) "setting_name=? AND store_id=?" else "setting_name=?"
        val args = if (store != null) arrayOf(name, store.toString()) else arrayOf(name)
        val updated = db.update(table, values, where, args)
        if (updated == 0) {
            values.put("store_id", store)
            values.put("created_by", currentUser())
            db.insert(table, null, values)
        }
    }

    private fun String.toBool(): Boolean = this == "1" || equals("true", true) || equals("yes", true)

    private fun currentStoreId(): Long? {
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    /** Device id captured at registration, mirrored onto each settings row. */
    private fun currentDeviceId(): String? {
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("device_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun currentUser(): String? = SessionManager.auditUser

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        private const val KEY_ROUND_OFF = "Bill Round Off"
        private const val KEY_AMOUNT_IN_WORDS = "Bill Amount In Words"
        private const val KEY_TWO_COPY = "Bill Two copy"
        private const val KEY_START_NO = "Bill Start No"
        private const val KEY_RESET_MODE = "Bill Reset Mode"
        private const val KEY_CHAR_ENABLED = "Bill No Char Enabled"
        private const val KEY_CHAR_PREFIX = "Bill No Char Prefix"
        private const val KEY_HSN_CODE = "Bill Hsn Code"
        private const val KEY_PRODUCT_SERIAL = "Product Serial Number"
        private const val KEY_TIME_ON_BILL = "Time On Bill"
        private const val KEY_CUSTOMER_DETAILS = "Customer Details"
        private const val KEY_CUSTOMER_ADDRESS_PRINTING = "Customer Address Printing"
        private const val KEY_TOTAL_FONT_SIZE = "Total Amount Font Size"
        private const val KEY_BILL_FORMAT = "Bill Format"
        private const val KEY_UPI_QR_ENABLED = "Bill Upi Qr Enabled"
        private const val KEY_UPI_ID = "Bill Upi Id"
        private const val KEY_UPI_PAYEE_NAME = "Bill Upi Payee Name"
        private const val KEY_COUPON_SPLIT = "Bill Coupon Split"
        private const val KEY_TEMPLATE_PAPER = "Bill Template Paper Width"

        /** 3-inch paper, the size most bill printers on this app run. */
        const val DEFAULT_TEMPLATE_PAPER_MM = 80
    }
}
