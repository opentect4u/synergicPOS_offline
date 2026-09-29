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

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_RATE_NAME

    data class RateName(val id: Long, val name: String)

    fun getAll(): List<RateName> {
        // Rate names that arrived on product rates without a master row - a bulk
        // upload's `rate_name` text, most often - are adopted first, so the list this
        // returns (the Rate Name master, the product form's dropdown) shows them.
        adoptLooseRateNames()
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

    /**
     * Gives every rate name that product rates carry as loose text a row in the
     * master, and links those rates to it.
     *
     * A product rate names its tier two ways: `rate_name_id`, the master row, and
     * `rate_name`, the text. A rate uploaded in bulk under the sheet's `rate_name`
     * heading carried the text alone - so "Regular" or "Party" was on hundreds of
     * product rates and in neither the Rate Name master nor the Section form's Price
     * List dropdown, which both read the master. The importer now creates the master
     * row as it goes (see ProductBulkImporter); this catches everything uploaded
     * before that, and anything else that wrote the text without the id.
     *
     * Matched on the name ignoring case and surrounding spaces, within the rate's own
     * store, so "regular " and "Regular" land on one row rather than two. A name the
     * master already has - active or retired - is linked to, never duplicated; a name
     * the shop retired stays retired.
     *
     * Two statements, and cheap to repeat: with nothing loose left, both match no row.
     */
    fun adoptLooseRateNames() {
        val db = helper.writableDatabase
        val rates = DatabaseHelper.Tables.MD_PRODUCT_RATES
        val loose = "rate_name_id IS NULL AND trim(COALESCE(rate_name, '')) <> ''"
        runCatching {
            db.beginTransaction()
            try {
                db.execSQL(
                    """
                    INSERT INTO $table (store_id, rate_name, is_active, created_at, created_by)
                    SELECT r.store_id, trim(r.rate_name), 1, ?, ?
                    FROM $rates r
                    WHERE r.$loose
                      AND NOT EXISTS (
                          SELECT 1 FROM $table n
                          WHERE n.rate_name = trim(r.rate_name) COLLATE NOCASE
                            AND n.store_id IS r.store_id
                      )
                    GROUP BY r.store_id, lower(trim(r.rate_name))
                    """.trimIndent(),
                    arrayOf<Any?>(now(), currentUser())
                )
                db.execSQL(
                    """
                    UPDATE $rates SET rate_name_id = (
                        SELECT n.id FROM $table n
                        WHERE n.rate_name = trim($rates.rate_name) COLLATE NOCASE
                          AND n.store_id IS $rates.store_id
                        ORDER BY n.is_active DESC, n.id ASC LIMIT 1
                    )
                    WHERE $loose
                    """.trimIndent()
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }.onFailure { android.util.Log.w("RateNameDao", "Could not adopt loose rate names", it) }
    }

    /**
     * The master row for rate name [name] in [storeId] - found ignoring case, or
     * created - for the product importer, which runs inside its own transaction on
     * [db]. Returns the id and the master's own spelling of the name.
     */
    fun findOrCreate(
        db: android.database.sqlite.SQLiteDatabase, name: String, storeId: Int?
    ): Pair<Long, String>? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        // A bound argument cannot be null, so an unknown store is matched as IS NULL.
        val where = "rate_name = ? COLLATE NOCASE AND " +
            if (storeId != null) "store_id = ?" else "store_id IS NULL"
        val args = if (storeId != null) arrayOf(clean, storeId.toString()) else arrayOf(clean)
        db.rawQuery(
            "SELECT id, rate_name FROM $table WHERE $where ORDER BY is_active DESC, id ASC LIMIT 1",
            args
        ).use { c -> if (c.moveToFirst()) return c.getLong(0) to (c.getString(1) ?: clean) }
        val id = db.insert(table, null, ContentValues().apply {
            if (storeId != null) put("store_id", storeId) else putNull("store_id")
            put("rate_name", clean)
            put("is_active", 1)
            put("created_at", now())
            put("created_by", currentUser())
        })
        return if (id == -1L) null else id to clean
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
}
