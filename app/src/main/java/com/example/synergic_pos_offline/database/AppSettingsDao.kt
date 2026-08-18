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
        val otherCharges: Boolean = false,
        /** Tap an item to add it straight to the cart (skip the quantity popup). */
        val directAddToCart: Boolean = false,
        /**
         * Offer the fingerprint reader on the login screen.
         *
         * Off by default, and deliberately: it is a convenience that trades away some
         * of what a password is for, and a shop should switch it on knowing that
         * rather than find it already on. See `BiometricLogin` for what the trade is.
         */
        val biometricLogin: Boolean = false,
        /**
         * Whether this shop runs shifts.
         *
         * On, a Shifts master appears, every user is put on one, and the shift-wise
         * billing report becomes available. Off, none of the three exists - a shop
         * with one person behind the counter has no shifts to divide anything by, and
         * a mandatory field asking which one they are on would be a question with no
         * answer.
         */
        val shift: Boolean = false,
        // Restaurant-only toggles.
        val couponMode: Boolean = false,
        val kot: Boolean = false,
        val tableMerge: Boolean = false,
        val tableShift: Boolean = false,
        val tableSplit: Boolean = false
    )

    fun load(): AppSettings {
        val m = readAll()
        return AppSettings(
            manualRate = m[KEY_MANUAL_RATE]?.toBool() ?: false,
            cashReception = m[KEY_CASH_RECEPTION]?.toBool() ?: false,
            paymentMode = m[KEY_PAYMENT_MODE]?.toBool() ?: false,
            otherCharges = m[KEY_OTHER_CHARGES]?.toBool() ?: false,
            directAddToCart = m[KEY_DIRECT_ADD_TO_CART]?.toBool() ?: false,
            biometricLogin = m[KEY_BIOMETRIC_LOGIN]?.toBool() ?: false,
            shift = m[KEY_SHIFT]?.toBool() ?: false,
            couponMode = m[KEY_COUPON_MODE]?.toBool() ?: false,
            kot = m[KEY_KOT]?.toBool() ?: false,
            tableMerge = m[KEY_TABLE_MERGE]?.toBool() ?: false,
            tableShift = m[KEY_TABLE_SHIFT]?.toBool() ?: false,
            tableSplit = m[KEY_TABLE_SPLIT]?.toBool() ?: false
        )
    }

    fun save(s: AppSettings) {
        upsertAppSetting(KEY_MANUAL_RATE, s.manualRate.b())
        upsertAppSetting(KEY_CASH_RECEPTION, s.cashReception.b())
        upsertAppSetting(KEY_PAYMENT_MODE, s.paymentMode.b())
        upsertAppSetting(KEY_OTHER_CHARGES, s.otherCharges.b())
        upsertAppSetting(KEY_DIRECT_ADD_TO_CART, s.directAddToCart.b())
        upsertAppSetting(KEY_BIOMETRIC_LOGIN, s.biometricLogin.b())
        upsertAppSetting(KEY_SHIFT, s.shift.b())
        upsertAppSetting(KEY_COUPON_MODE, s.couponMode.b())
        upsertAppSetting(KEY_KOT, s.kot.b())
        upsertAppSetting(KEY_TABLE_MERGE, s.tableMerge.b())
        upsertAppSetting(KEY_TABLE_SHIFT, s.tableShift.b())
        upsertAppSetting(KEY_TABLE_SPLIT, s.tableSplit.b())
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

    private fun currentUser(): String? = SessionManager.auditUser
    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /**
     * The setting_name each toggle is stored under.
     *
     * Visible rather than private, as [GeneralSettingsDao]'s are: a caller that reads
     * one of these out of the login cache instead of loading the whole group - see
     * `BiometricLogin` - has to name the same key this DAO writes, and a second copy
     * of the string is a second thing to keep in step.
     */
    companion object {
        const val KEY_MANUAL_RATE = "Manual Rate"
        const val KEY_CASH_RECEPTION = "Cash Reception"
        const val KEY_PAYMENT_MODE = "Payment Mode"
        const val KEY_OTHER_CHARGES = "Other Charges"
        const val KEY_DIRECT_ADD_TO_CART = "Direct Add to Cart"
        const val KEY_BIOMETRIC_LOGIN = "Biometric Login"
        const val KEY_SHIFT = "Shift"
        const val KEY_COUPON_MODE = "Coupon Mode"
        const val KEY_KOT = "KOT"
        const val KEY_TABLE_MERGE = "Table Merge"
        const val KEY_TABLE_SHIFT = "Table Shift"
        const val KEY_TABLE_SPLIT = "Table Split"
    }
}
