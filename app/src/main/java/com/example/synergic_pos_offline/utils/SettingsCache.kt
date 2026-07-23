package com.example.synergic_pos_offline.utils

import android.content.Context
import android.util.Log
import com.example.synergic_pos_offline.database.DatabaseHelper
import org.json.JSONObject

/**
 * Local-storage cache of md_app_settings, stored **chunk-wise by setting_type**:
 * all type 'B' (bill) rows in one entry, 'T' (tax) in another, 'G' (general),
 * 'A' (app). Populated at login so the app can read settings offline without
 * hitting the database each time.
 *
 * Each chunk is a JSON object of { setting_name : setting_value }, saved under the
 * key `settings_<TYPE>` in the [PREF] SharedPreferences file.
 */
object SettingsCache {

    private const val PREF = "app_settings_cache"
    private const val TAG = "SettingsCache"

    /**
     * Reads md_app_settings from the database and writes it to local storage,
     * grouped by type. Call this on a successful login and after any settings save.
     * [source] tags the log line so you can tell a login-load from a save-update.
     */
    fun storeFromDb(context: Context, source: String = "login") {
        Log.d(TAG, "── refreshing settings cache (trigger: $source) ──")
        val db = DatabaseHelper.getInstance(context).readableDatabase
        val byType = linkedMapOf<String, JSONObject>()
        var rowCount = 0

        db.query(
            DatabaseHelper.Tables.MD_APP_SETTINGS,
            arrayOf("setting_name", "setting_value", "setting_type"),
            null, null, null, null, "setting_type ASC, setting_name ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val value = c.getString(1)                 // may be null (e.g. GST Type)
                val type = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val chunk = byType.getOrPut(type) { JSONObject() }
                chunk.put(name, value ?: JSONObject.NULL)
                rowCount++
            }
        }

        Log.d(TAG, "Caching md_app_settings: read $rowCount row(s) across ${byType.size} type(s)")

        val editor = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        // Refresh all four known chunks so stale data can't linger.
        for (type in listOf("B", "T", "G", "A")) {
            val chunk = byType[type]
            if (chunk != null && chunk.length() > 0) {
                editor.putString(key(type), chunk.toString())
                Log.d(TAG, "  stored chunk '$type' (${chunk.length()} keys) -> $chunk")
            } else {
                editor.remove(key(type))
                Log.d(TAG, "  chunk '$type' is empty -> removed from cache")
            }
        }
        val ok = editor.commit()   // commit (not apply) so we can log the real result

        if (ok && rowCount > 0) {
            Log.i(TAG, "[$source] local storage UPDATED SUCCESSFULLY ($rowCount row(s) cached)")
        } else if (ok) {
            Log.w(TAG, "[$source] cache written, but md_app_settings had NO rows (nothing to store yet — save a settings screen first)")
        } else {
            Log.e(TAG, "[$source] FAILED to update local storage")
        }

        // Read-back verification so the log proves the data is actually persisted.
        for (type in listOf("B", "T", "G", "A")) {
            val readBack = chunk(context, type)
            Log.d(TAG, "  verify chunk '$type': ${readBack.length()} keys -> $readBack")
        }
    }

    /** The cached chunk for [type] (e.g. "B"), or an empty object if none. */
    fun chunk(context: Context, type: String): JSONObject {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key(type), null)
        return if (raw.isNullOrBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    /** Convenience getters for the four setting groups. */
    fun billSettings(context: Context): JSONObject = chunk(context, "B")
    fun taxSettings(context: Context): JSONObject = chunk(context, "T")
    fun generalSettings(context: Context): JSONObject = chunk(context, "G")
    fun appSettings(context: Context): JSONObject = chunk(context, "A")

    /** Reads a single cached value by type + name, or null. */
    fun value(context: Context, type: String, name: String): String? {
        val chunk = chunk(context, type)
        if (!chunk.has(name) || chunk.isNull(name)) return null
        return chunk.optString(name)
    }

    /** Clears the cache (e.g. on logout). */
    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun key(type: String) = "settings_$type"
}
