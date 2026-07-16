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

    /** A single waiter row. [tableNo] is the assigned table (may be blank). */
    data class Waiter(val id: Long, val name: String, val tableNo: String) {
        val code: String get() = formatCode(id)
    }

    /** All waiters, oldest first (so codes read in ascending order). */
    fun getAll(): List<Waiter> {
        val list = mutableListOf<Waiter>()
        helper.readableDatabase.query(
            table, arrayOf("id", "waiter_name", "table_no_from"),
            null, null, null, null, "id ASC"
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
        val values = ContentValues().apply {
            put("store_id", currentStoreId())
            put("waiter_name", name)
            put("table_no_from", tableNo)
            put("table_no_to", tableNo)
            put("created_by", currentUser())
        }
        return helper.writableDatabase.insert(table, null, values)
    }

    /** Updates name and assigned table for [id]. */
    fun update(id: Long, name: String, tableNo: String): Int {
        val values = ContentValues().apply {
            put("waiter_name", name)
            put("table_no_from", tableNo)
            put("table_no_to", tableNo)
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        return helper.writableDatabase.update(table, values, "id=?", arrayOf(id.toString()))
    }

    /** Deletes every waiter in [ids]. */
    fun delete(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        return helper.writableDatabase.delete(table, "id IN ($placeholders)", args)
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
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    private fun currentUser(): String? = SessionManager.currentUser?.userId

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        /** Renders a stable waiter code from a row id, e.g. 7 -> "WTR007". */
        fun formatCode(id: Long): String = "WTR" + id.toString().padStart(3, '0')
    }
}
