package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the General Settings as key/value rows in
 * [DatabaseHelper.Tables.MD_APP_SETTINGS], scoped to the current store.
 *
 * Every row uses setting_type 'G' (general settings). Booleans are stored as "1"/"0".
 */
class GeneralSettingsDao(context: Context) {

    private val appContext = context.applicationContext
    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_APP_SETTINGS

    /** Business mode of the app. Persisted as a single-letter [code] (G / R). */
    enum class Mode(val code: String, val label: String) {
        GROCERY("G", "Grocery"), RESTAURANT("R", "Restaurant");
        companion object {
            /** Accepts the stored code (G/R) or the display label. */
            fun fromStored(value: String?): Mode? = value?.let { v ->
                values().firstOrNull { it.code.equals(v, true) || it.label.equals(v, true) }
            }
        }
    }

    /**
     * How a sale return is taken. Persisted as a single-letter [code] (B / I).
     *
     * BILL_WISE starts from the original bill and returns lines off it, so it can be
     * held to a time limit - the bill has a date to measure from. ITEM_WISE starts
     * from the item instead, with no bill behind it and so nothing to measure a
     * limit against, which is why Sale Return Days only applies to the former.
     */
    enum class ReturnMode(val code: String, val label: String) {
        BILL_WISE("B", "Bill-wise"), ITEM_WISE("I", "Item-wise");
        companion object {
            /** Accepts the stored code (B/I) or the display label. */
            fun fromStored(value: String?): ReturnMode? = value?.let { v ->
                values().firstOrNull { it.code.equals(v, true) || it.label.equals(v, true) }
            }
        }
    }

    /** How many rates an item can carry. Persisted as a single-letter [code] (S / M). */
    enum class ItemRate(val code: String, val label: String) {
        SINGLE("S", "Single"), MULTIPLE("M", "Multiple");
        companion object {
            /** Accepts the stored code (S/M) or the display label. */
            fun fromStored(value: String?): ItemRate? = value?.let { v ->
                values().firstOrNull { it.code.equals(v, true) || it.label.equals(v, true) }
            }
        }
    }

    /**
     * Where the app lands once a user logs in. Persisted as a single-letter [code]
     * (S / H).
     *
     * [SALE] is the default: a cashier signs in to sell, and the till opening on the
     * Sale screen saves them a tap every shift. [HOME] is for a till that is also
     * someone's back office - the Dashboard first, with Sale a menu away - and is how
     * the app behaved before this setting existed.
     */
    enum class LandingScreen(val code: String, val label: String) {
        SALE("S", "Sale"), HOME("H", "Home");
        companion object {
            /** Accepts the stored code (S/H) or the display label. */
            fun fromStored(value: String?): LandingScreen? = value?.let { v ->
                values().firstOrNull { it.code.equals(v, true) || it.label.equals(v, true) }
            }
        }
    }

    /** The general-settings configuration with defaults. */
    data class GeneralSettings(
        val mode: Mode = Mode.GROCERY,
        val saleReturn: Boolean = false,
        val returnMode: ReturnMode = ReturnMode.BILL_WISE,
        /** Only meaningful under [ReturnMode.BILL_WISE] - see [ReturnMode]. */
        val saleReturnDays: Int = 0,
        val lastBillStatus: Boolean = false,
        val quantityStatus: Boolean = false,
        val itemRate: ItemRate = ItemRate.SINGLE,
        /**
         * Whether a sale captures the customer at all.
         *
         * Off, the till never asks: the sale carries no customer, `customer_id` is
         * left null and the receipt prints no customer block. A credit sale is the
         * exception - it is collected later, so it has to be attributable, and it
         * asks for the customer and prints them whatever this says.
         *
         * Defaults on, which is how the till behaved before the setting existed.
         */
        val customerInfo: Boolean = true,
        /** Which screen a login lands on - see [LandingScreen]. */
        val landingScreen: LandingScreen = LandingScreen.SALE
    )

    /** Reads every general setting for the current store, applying defaults. */
    fun load(): GeneralSettings {
        val m = readAll()
        val d = GeneralSettings()
        return GeneralSettings(
            mode = Mode.fromStored(m[KEY_MODE]) ?: d.mode,
            saleReturn = m[KEY_SALE_RETURN]?.toBool() ?: d.saleReturn,
            returnMode = ReturnMode.fromStored(m[KEY_RETURN_MODE]) ?: d.returnMode,
            saleReturnDays = m[KEY_SALE_RETURN_DAYS]?.toIntOrNull() ?: d.saleReturnDays,
            lastBillStatus = m[KEY_LAST_BILL_STATUS]?.toBool() ?: d.lastBillStatus,
            quantityStatus = m[KEY_QUANTITY_STATUS]?.toBool() ?: d.quantityStatus,
            itemRate = ItemRate.fromStored(m[KEY_ITEM_RATE]) ?: d.itemRate,
            customerInfo = m[KEY_CUSTOMER_INFO]?.toBool() ?: d.customerInfo,
            landingScreen = LandingScreen.fromStored(m[KEY_LANDING_SCREEN]) ?: d.landingScreen
        )
    }

    /** Writes every general setting for the current store (upsert per key). When
     *  Sale Return is off, the days value is stored as 0. */
    fun save(s: GeneralSettings) {
        put(KEY_MODE, s.mode.code)
        put(KEY_SALE_RETURN, s.saleReturn.b())
        put(KEY_RETURN_MODE, s.returnMode.code)
        // Days only bound a bill-wise return; item-wise has no bill to date from.
        val daysApply = s.saleReturn && s.returnMode == ReturnMode.BILL_WISE
        put(KEY_SALE_RETURN_DAYS, if (daysApply) s.saleReturnDays.toString() else "0")
        put(KEY_LAST_BILL_STATUS, s.lastBillStatus.b())
        put(KEY_QUANTITY_STATUS, s.quantityStatus.b())
        put(KEY_ITEM_RATE, s.itemRate.code)
        put(KEY_CUSTOMER_INFO, s.customerInfo.b())
        put(KEY_LANDING_SCREEN, s.landingScreen.code)
        helper.regroupAppSettingsByType()
        com.example.synergic_pos_offline.utils.SettingsCache.storeFromDb(appContext, "General settings save (type G)")
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

    /** Inserts or updates a single setting row for the current store (type 'G'). */
    private fun put(name: String, value: String) {
        val db = helper.writableDatabase
        val store = currentStoreId()
        val values = ContentValues().apply {
            put("setting_name", name)
            put("setting_value", value)
            put("setting_type", "G")
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

    private fun Boolean.b(): String = if (this) "1" else "0"
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

    private companion object {
        const val KEY_MODE = "Mode"
        const val KEY_SALE_RETURN = "Sale Return"
        const val KEY_SALE_RETURN_DAYS = "Sale Return Days"
        const val KEY_RETURN_MODE = "Sale Return Mode"
        const val KEY_LAST_BILL_STATUS = "Last Bill Status"
        const val KEY_QUANTITY_STATUS = "Quantity Status"
        const val KEY_ITEM_RATE = "Item Rate"
        const val KEY_CUSTOMER_INFO = "Customer Info"
        const val KEY_LANDING_SCREEN = "Landing Screen"
    }
}
