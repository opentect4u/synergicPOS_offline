package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * Key/value access to [DatabaseHelper.Tables.MD_APP_SETTINGS].
 *
 * Settings live in the database rather than SharedPreferences so they travel with
 * a restored backup - a till rebuilt from a copy keeps its printer, its store id
 * and everything else it was configured with.
 */
class AppSettingsDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_APP_SETTINGS

    /** The stored value for [name], or null when unset. */
    fun get(name: String): String? {
        helper.readableDatabase.query(
            table, arrayOf("setting_value"),
            "setting_name = ?", arrayOf(name), null, null, "id DESC", "1"
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    /** Writes [value] against [name], replacing any existing entry. */
    fun put(name: String, value: String) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("store_id", currentStoreId())
            put("outlet_id", 0)
            put("setting_name", name)
            put("setting_value", value)
            // 'T' is the text kind in the schema's setting_type check.
            put("setting_type", "T")
            put("modified_by", SessionManager.currentUser?.userId)
        }
        val updated = db.update(table, values, "setting_name = ?", arrayOf(name))
        if (updated == 0) {
            values.put("created_by", SessionManager.currentUser?.userId)
            db.insert(table, null, values)
        }
    }

    private fun currentStoreId(): Long? {
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }
}
