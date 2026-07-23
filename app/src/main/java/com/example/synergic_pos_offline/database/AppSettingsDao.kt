package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Key/value access to [DatabaseHelper.Tables.MD_APP_SETTINGS].
 */
class AppSettingsDao(context: Context) {

    private val appContext = context.applicationContext
    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_APP_SETTINGS

    data class AppSettings(
        val manualRate: Boolean = false,
        val cashReception: Boolean = false,
        val paymentMode: Boolean = false,
        val otherCharges: Boolean = false
    )

    fun load(): AppSettings {
        val m = readAll()
        return AppSettings(
            manualRate = m[KEY_MANUAL_RATE]?.toBool() ?: false,
            cashReception = m[KEY_CASH_RECEPTION]?.toBool() ?: false,
            paymentMode = m[KEY_PAYMENT_MODE]?.toBool() ?: false,
            otherCharges = m[KEY_OTHER_CHARGES]?.toBool() ?: false
        )
    }

    fun save(s: AppSettings) {
        upsertAppSetting(KEY_MANUAL_RATE, s.manualRate.b())
        upsertAppSetting(KEY_CASH_RECEPTION, s.cashReception.b())
        upsertAppSetting(KEY_PAYMENT_MODE, s.paymentMode.b())
        upsertAppSetting(KEY_OTHER_CHARGES, s.otherCharges.b())
        helper.regroupAppSettingsByType()
        com.example.synergic_pos_offline.utils.SettingsCache.storeFromDb(appContext, "App settings save (type A)")
    }

    private fun readAll(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val store = currentStoreId()
        val where = if (store != null) "store_id=?" else null
        val args = if (store != null) arrayOf(store.toString()) else null
        
        helper.readableDatabase.query(
            table, arrayOf("setting_name", "setting_value"),
            where, args, null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                map[name] = c.getString(1).orEmpty()
            }
        }
        return map
    }

    private fun upsertAppSetting(name: String, value: String) {
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

    fun get(name: String): String? {
        helper.readableDatabase.query(
            table, arrayOf("setting_value"),
            "setting_name = ?", arrayOf(name), null, null, "id DESC", "1"
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    fun put(name: String, value: String) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("store_id", currentStoreId())
            put("setting_name", name)
            put("setting_value", value)
            put("setting_type", "T")
            put("modified_by", currentUser())
        }
        val updated = db.update(table, values, "setting_name = ?", arrayOf(name))
        if (updated == 0) {
            values.put("created_by", currentUser())
            db.insert(table, null, values)
        }
    }

    private fun Boolean.b(): String = if (this) "1" else "0"
    private fun String.toBool(): Boolean = this == "1" || equals("true", true)

    private fun currentStoreId(): Long? {
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

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
