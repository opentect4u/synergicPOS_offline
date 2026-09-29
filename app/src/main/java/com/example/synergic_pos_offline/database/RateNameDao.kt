package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CRUD for [DatabaseHelper.Tables.MD_RATE_NAME] — the per-store master list of
 * rate names (Rate 1 / Rate 2 / MRP …) chosen on each product rate. Store-scoped
 * by the signed-in user's store.
 */
class RateNameDao(context: Context) {

    private val appContext = context.applicationContext
    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_RATE_NAME

    data class RateName(val id: Long, val name: String)

    /**
     * Puts the default rate names - [DEFAULTS] - on this store's list.
     *
     * Called by everything that lists rate names (this master, the product form's
     * rate dropdown, the Section form's Price List), so whichever the shop opens first
     * already shows them. It used to happen only when the product form opened, which
     * left the Rate Name master and the Section dropdown empty until then.
     *
     * - A store with NO rate names gets all of them - on a fresh till, and again after
     *   the masters are wiped.
     * - A store that already has some gets whichever defaults it lacks, ONCE (a mark
     *   per store is kept in preferences). After that the list is the shop's: a
     *   default deleted from the master stays deleted.
     *
     * Matched ignoring case and spaces, so a store that already has "rate1" or
     * "RATE 1" is not given a second "Rate 1".
     */
    fun ensureDefaults() {
        val store = currentStoreId() ?: return
        runCatching {
            val db = helper.writableDatabase
            val have = mutableSetOf<String>()
            db.rawQuery(
                "SELECT rate_name FROM $table WHERE store_id = ?", arrayOf(store.toString())
            ).use { c -> while (c.moveToNext()) have.add(normalized(c.getString(0).orEmpty())) }

            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val markKey = "$KEY_DEFAULTS_ADDED$store"
            if (have.isNotEmpty() && prefs.getBoolean(markKey, false)) return

            DEFAULTS.filter { normalized(it) !in have }.forEach { name ->
                db.insert(table, null, ContentValues().apply {
                    put("store_id", store)
                    put("rate_name", name)
                    put("is_active", 1)
                    put("created_at", now())
                    put("created_by", currentUser())
                })
            }
            prefs.edit().putBoolean(markKey, true).apply()
        }.onFailure { android.util.Log.w("RateNameDao", "Could not add default rate names", it) }
    }

    private fun normalized(name: String) = name.replace(" ", "").lowercase()

    /**
     * The master row a product sheet's RATE_[slot] column belongs to - this store's
     * "Rate [slot]" - with its id and its own spelling of the name.
     *
     * Matched ignoring case, spaces and underscores, so "Rate 1", "rate1" and "RATE_1"
     * are one row. Created when the store has none - the sheet is pricing products
     * under that rate, and a rate linked to nothing would appear in no dropdown.
     *
     * Runs on [db], the importer's own transaction, so the row is written with the
     * products or not at all.
     */
    fun forSlot(
        db: android.database.sqlite.SQLiteDatabase, slot: Int, storeId: Int?
    ): Pair<Long, String>? {
        val name = "Rate $slot"
        // A bound argument cannot be null, so an unknown store is matched as IS NULL.
        val storeClause = if (storeId != null) "store_id = ?" else "store_id IS NULL"
        val args = listOfNotNull("rate$slot", storeId?.toString()).toTypedArray()
        db.rawQuery(
            "SELECT id, rate_name FROM $table " +
                "WHERE replace(replace(lower(rate_name), ' ', ''), '_', '') = ? AND $storeClause " +
                "ORDER BY is_active DESC, id ASC LIMIT 1",
            args
        ).use { c -> if (c.moveToFirst()) return c.getLong(0) to (c.getString(1) ?: name) }
        val id = db.insert(table, null, ContentValues().apply {
            if (storeId != null) put("store_id", storeId) else putNull("store_id")
            put("rate_name", name)
            put("is_active", 1)
            put("created_at", now())
            put("created_by", currentUser())
        })
        return if (id == -1L) null else id to name
    }

    fun getAll(): List<RateName> {
        ensureDefaults()
        val list = mutableListOf<RateName>()
        val store = currentStoreId()
        helper.readableDatabase.query(
            table, arrayOf("id", "rate_name"),
            (if (store != null) "store_id = ? AND is_active = 1" else "is_active = 1"),
            store?.let { arrayOf(it.toString()) },
            null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) list.add(RateName(c.getLong(0), c.getString(1).orEmpty()))
        }
        return list
    }

    fun insert(name: String): Long {
        val v = ContentValues().apply {
            put("store_id", currentStoreId())
            put("rate_name", name)
            put("is_active", 1)
            // Set explicitly: on a migrated table the added created_at column carries no
            // default, so relying on it would leave the stamp null.
            put("created_at", now())
            put("created_by", currentUser())
        }
        return helper.writableDatabase.insert(table, null, v)
    }

    fun update(id: Long, name: String): Int {
        val v = ContentValues().apply {
            put("rate_name", name)
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        return helper.writableDatabase.update(table, v, "id = ?", arrayOf(id.toString()))
    }

    fun delete(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        return helper.writableDatabase.delete(
            table, "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray()
        )
    }

    private fun currentStoreId(): Long? {
        SessionManager.currentUser?.storeId?.takeIf { it != 0 }?.let { return it.toLong() }
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }

    private fun currentUser(): String? = SessionManager.auditUser
    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        /** The rate names every store's list starts with - see [ensureDefaults]. */
        val DEFAULTS = listOf("Rate 1", "Rate 2", "Rate 3", "Rate 4")

        private const val PREFS = "rate_name_defaults"
        private const val KEY_DEFAULTS_ADDED = "defaults_added_store_"
    }
}
