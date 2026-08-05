package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data-access layer for the [DatabaseHelper.Tables.MD_WAITERS] master table.
 *
 * The "Waiter Code" is derived from the row id via [formatCode], keeping it
 * stable and gap-free without a dedicated column.
 *
 * The schema models a table range (table_no_from / table_no_to); this screen
 * assigns a single table, so both columns are stored with the same value.
 */
class WaiterDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_WAITERS
    private val assignTable = DatabaseHelper.Tables.TD_ASSIGN_WAITER

    /** A single waiter row. [tableNo] is the assigned table (may be blank). */
    data class Waiter(val id: Long, val name: String, val tableNo: String) {
        val code: String get() = formatCode(id)
    }

    /** All waiters, oldest first (so codes read in ascending order). */
    fun getAll(): List<Waiter> {
        val list = mutableListOf<Waiter>()
        helper.readableDatabase.query(
            table, arrayOf("id", "waiter_name", "table_no_from"),
            (if (currentStoreId() != null) "store_id = ?" else null),
            currentStoreId()?.let { arrayOf(it.toString()) },
            null, null, "id ASC"
        ).use { c ->
            val iId = c.getColumnIndexOrThrow("id")
            val iName = c.getColumnIndexOrThrow("waiter_name")
            val iTable = c.getColumnIndexOrThrow("table_no_from")
            while (c.moveToNext()) {
                list.add(Waiter(c.getLong(iId), c.getString(iName).orEmpty(), c.getString(iTable).orEmpty()))
            }
        }
        return list
    }

    /** Inserts a new waiter and returns its new row id (or -1 on failure). */
    fun insert(name: String, tableNo: String): Long {
        val db = helper.writableDatabase
        val id = db.insert(table, null, ContentValues().apply {
            put("store_id", currentStoreId())
            put("waiter_name", name)
            put("table_no_from", tableNo)
            put("table_no_to", tableNo)
            put("created_by", currentUser())
        })
        // Mirror the waiter into the assign-waiter table (id + name only).
        if (id != -1L) {
            db.insert(assignTable, null, ContentValues().apply {
                put("store_id", currentStoreId())
                put("waiter_id", id)
                put("waiter_name", name)
                put("created_by", currentUser())
            })
        }
        return id
    }

    /** Updates name and assigned table for [id]. */
    fun update(id: Long, name: String, tableNo: String): Int {
        val db = helper.writableDatabase
        val n = db.update(table, ContentValues().apply {
            put("waiter_name", name)
            put("table_no_from", tableNo)
            put("table_no_to", tableNo)
            put("modified_at", now())
            put("modified_by", currentUser())
        }, "id=?", arrayOf(id.toString()))
        // Keep the mirrored assign-waiter row's name in sync.
        db.update(assignTable, ContentValues().apply {
            put("waiter_name", name)
            put("modified_at", now())
            put("modified_by", currentUser())
        }, "waiter_id=?", arrayOf(id.toString()))
        return n
    }

    /** Deletes every waiter in [ids]. */
    fun delete(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        val db = helper.writableDatabase
        db.delete(assignTable, "waiter_id IN ($placeholders)", args)
        return db.delete(table, "id IN ($placeholders)", args)
    }

    /** The largest existing id, or null when the table is empty. */
    fun lastId(): Long? {
        helper.readableDatabase.rawQuery("SELECT MAX(id) FROM $table", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    /** The id the next inserted row will receive (matches AUTOINCREMENT). */
    fun nextId(): Long {
        helper.readableDatabase.rawQuery(
            "SELECT seq FROM sqlite_sequence WHERE name=?", arrayOf(table)
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) + 1
        }
        return 1L
    }

    private fun currentStoreId(): Long? {
        // The signed-in user's store is the current store; the registration row is
        // only a fallback (e.g. seeding before anyone has logged in).
        SessionManager.currentUser?.storeId?.takeIf { it != 0 }?.let { return it.toLong() }
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    private fun currentUser(): String? = SessionManager.auditUser

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        /** Renders a stable waiter code from a row id, e.g. 7 -> "WTR007". */
        fun formatCode(id: Long): String = "WTR" + id.toString().padStart(3, '0')
    }
}
