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

    /** The full set of bill settings with sensible defaults. */
    data class BillSettings(
        val roundOff: Boolean = false,
        val amountInWords: Boolean = false,
        val twoCopyBill: Boolean = false,
        val startBillNo: Int = 0,
        val resetMode: ResetMode = ResetMode.CONTINUE,
        val billNoCharEnabled: Boolean = false,
        val billNoCharPrefix: String = "",
        val hsnCode: Boolean = false
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
            hsnCode = map[KEY_HSN_CODE]?.toBool() ?: d.hsnCode
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
        val map = hashMapOf<String, String>()
        val store = currentStoreId()
        val (where, args) = if (store != null) "store_id=?" to arrayOf(store.toString()) else null to null
        helper.readableDatabase.query(
            table, arrayOf("setting_name", "setting_value"),
            where, args, null, null, null
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

    private fun currentUser(): String? = SessionManager.currentUser?.userId

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private companion object {
        const val KEY_ROUND_OFF = "Bill Round Off"
        const val KEY_AMOUNT_IN_WORDS = "Bill Amount In Words"
        const val KEY_TWO_COPY = "Bill Two copy"
        const val KEY_START_NO = "Bill Start No"
        const val KEY_RESET_MODE = "Bill Reset Mode"
        const val KEY_CHAR_ENABLED = "Bill No Char Enabled"
        const val KEY_CHAR_PREFIX = "Bill No Char Prefix"
        const val KEY_HSN_CODE = "Bill Hsn Code"
    }
}
