package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the Tax & Discount settings as key/value rows in
 * [DatabaseHelper.Tables.MD_APP_SETTINGS], scoped to the current store.
 *
 * Every row uses setting_type 'T' (tax settings). Booleans are stored as "1"/"0"
 * and enum choices as their name.
 */
class TaxSettingsDao(context: Context) {

    private val appContext = context.applicationContext
    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_APP_SETTINGS

    /** Whether GST amounts are included in the price or added on top. Persisted as [code]. */
    enum class GstMode(val code: String) {
        INCLUSIVE("I"), EXCLUSIVE("E");
        companion object {
            fun fromCode(value: String?): GstMode? =
                value?.let { v -> values().firstOrNull { it.code.equals(v, true) || it.name.equals(v, true) } }
        }
    }

    /** The single selected discount type (radio). Persisted as its [code] 1-4. */
    enum class DiscountType(val code: Int) {
        ITEM_PERCENT(1), ITEM_AMOUNT(2), BILL_PERCENT(3), BILL_AMOUNT(4);
        companion object {
            fun fromCode(value: String?): DiscountType? =
                value?.toIntOrNull()?.let { c -> values().firstOrNull { it.code == c } }
        }
    }

    /** The single selected discount position (radio). Persisted as its [code] 1-4. */
    enum class DiscountPosition(val code: Int) {
        ITEM_PRE_TAX(1), ITEM_POST_TAX(2), BILL_PRE_TAX(3), BILL_POST_TAX(4);
        companion object {
            fun fromCode(value: String?): DiscountPosition? =
                value?.toIntOrNull()?.let { c -> values().firstOrNull { it.code == c } }
        }
    }

    /**
     * Full tax/discount configuration.
     *
     * Discount: [discountEnabled] gates a single [discountType] and [discountPosition]
     * (both mandatory, radio-selected, when discount is on).
     *
     * Tax: [gstEnabled], [igstEnabled] and [vatEnabled] are mutually exclusive
     * (enforced by the UI); [gstMode] only applies when GST is on.
     */
    data class TaxSettings(
        val discountEnabled: Boolean = false,
        val discountType: DiscountType = DiscountType.ITEM_PERCENT,
        val discountPosition: DiscountPosition = DiscountPosition.ITEM_PRE_TAX,
        // Tax
        val gstEnabled: Boolean = false,
        val gstMode: GstMode = GstMode.EXCLUSIVE,
        val igstEnabled: Boolean = false,
        val vatEnabled: Boolean = false
    )

    /** Reads every tax setting for the current store, applying defaults. */
    fun load(): TaxSettings {
        val m = readAll()
        val d = TaxSettings()
        return TaxSettings(
            discountEnabled = m[KEY_DISCOUNT_ENABLED]?.toBool() ?: d.discountEnabled,
            discountType = DiscountType.fromCode(m[KEY_DISCOUNT_TYPE]) ?: d.discountType,
            discountPosition = DiscountPosition.fromCode(m[KEY_DISCOUNT_POSITION]) ?: d.discountPosition,
            gstEnabled = m[KEY_GST_ENABLED]?.toBool() ?: d.gstEnabled,
            gstMode = GstMode.fromCode(m[KEY_GST_MODE]) ?: d.gstMode,
            igstEnabled = m[KEY_IGST_ENABLED]?.toBool() ?: d.igstEnabled,
            vatEnabled = m[KEY_VAT_ENABLED]?.toBool() ?: d.vatEnabled
        )
    }

    /**
     * Writes every tax setting for the current store (upsert per key). When discount
     * is disabled, the type and position are stored as 0; when enabled, they hold the
     * selected value (1-4).
     */
    fun save(s: TaxSettings) {
        put(KEY_DISCOUNT_ENABLED, s.discountEnabled.b())
        put(KEY_DISCOUNT_TYPE, if (s.discountEnabled) s.discountType.code.toString() else "0")
        put(KEY_DISCOUNT_POSITION, if (s.discountEnabled) s.discountPosition.code.toString() else "0")
        put(KEY_GST_ENABLED, s.gstEnabled.b())
        // GST type is only meaningful when GST is on; otherwise store null.
        put(KEY_GST_MODE, if (s.gstEnabled) s.gstMode.code else null)
        put(KEY_IGST_ENABLED, s.igstEnabled.b())
        put(KEY_VAT_ENABLED, s.vatEnabled.b())
        helper.regroupAppSettingsByType()
        com.example.synergic_pos_offline.utils.SettingsCache.storeFromDb(appContext, "Tax settings save (type T)")
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

    /** Inserts or updates a single setting row for the current store (type 'T').
     *  A null [value] is stored as SQL NULL. */
    private fun put(name: String, value: String?) {
        val db = helper.writableDatabase
        val store = currentStoreId()
        val values = ContentValues().apply {
            put("setting_name", name)
            if (value == null) putNull("setting_value") else put("setting_value", value)
            put("setting_type", "T")
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

    private fun currentUser(): String? = SessionManager.currentUser?.userId

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private companion object {
        const val KEY_DISCOUNT_ENABLED = "Discount"
        const val KEY_DISCOUNT_TYPE = "Discount Type"
        const val KEY_DISCOUNT_POSITION = "Discount Position"
        const val KEY_GST_ENABLED = "GST"
        const val KEY_GST_MODE = "GST Type"
        const val KEY_IGST_ENABLED = "IGST"
        const val KEY_VAT_ENABLED = "VAT"
    }
}
