package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the App Settings as key/value rows in
 * [DatabaseHelper.Tables.MD_APP_SETTINGS], scoped to the current store.
 *
 * Every row uses setting_type 'A' (app settings). Booleans are stored as "1"/"0".
 */
class AppSettingsDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_APP_SETTINGS

    /** The full app-settings configuration with defaults. */
    data class AppSettings(
        val manualRate: Boolean = false,
        val cashReception: Boolean = false,
        val paymentMode: Boolean = false,
        val otherCharges: Boolean = false
    )

    /** Reads every app setting for the current store, applying defaults. */
    fun load(): AppSettings {
        val m = readAll()
        val d = AppSettings()
        return AppSettings(
            manualRate = m[KEY_MANUAL_RATE]?.toBool() ?: d.manualRate,
            cashReception = m[KEY_CASH_RECEPTION]?.toBool() ?: d.cashReception,
            paymentMode = m[KEY_PAYMENT_MODE]?.toBool() ?: d.paymentMode,
            otherCharges = m[KEY_OTHER_CHARGES]?.toBool() ?: d.otherCharges
        )
    }

    /** Writes every app setting for the current store (upsert per key). */
    fun save(s: AppSettings) {
        put(KEY_MANUAL_RATE, s.manualRate.b())
        put(KEY_CASH_RECEPTION, s.cashReception.b())
        put(KEY_PAYMENT_MODE, s.paymentMode.b())
        put(KEY_OTHER_CHARGES, s.otherCharges.b())
        helper.regroupAppSettingsByType()
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

    /** Inserts or updates a single setting row for the current store (type 'A'). */
    private fun put(name: String, value: String) {
        val db = helper.writableDatabase
        val store = currentStoreId()
        val values = ContentValues().apply {
            put("setting_name", name)
            put("setting_value", value)
            put("setting_type", "A")
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
        const val KEY_MANUAL_RATE = "Manual Rate"
        const val KEY_CASH_RECEPTION = "Cash Reception"
        const val KEY_PAYMENT_MODE = "Payment Mode"
        const val KEY_OTHER_CHARGES = "Other Charges"
    }
}
