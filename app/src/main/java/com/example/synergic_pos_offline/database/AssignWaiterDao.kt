package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data-access layer for [DatabaseHelper.Tables.TD_ASSIGN_WAITER].
 *
 * Each row records an assigned waiter — only the waiter id and name are kept
 * (no table). Waiters are chosen from the [DatabaseHelper.Tables.MD_WAITERS]
 * master. Store-scoped by the signed-in user's store.
 */
class AssignWaiterDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.TD_ASSIGN_WAITER

    /** One assignment row. [waiterId] is the md_waiters id; [code] is its display code. */
    data class Assignment(val id: Long, val waiterId: Long, val waiterName: String) {
        val code: String get() = WaiterDao.formatCode(waiterId)
    }

    /** A waiter available to assign (from the waiter master). */
    data class WaiterOption(val id: Long, val name: String) {
        val code: String get() = WaiterDao.formatCode(id)
    }

    /**
     * Backfills assign rows for any waiter (current store) not yet mirrored —
     * covers waiters added before the master started syncing.
     */
    fun ensureForWaiters() {
        val db = helper.writableDatabase
        val store = currentStoreId()
        val where = if (store != null) "store_id = ?" else null
        val args = store?.let { arrayOf(it.toString()) }
        db.query(
            DatabaseHelper.Tables.MD_WAITERS, arrayOf("id", "waiter_name"),
            where, args, null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val wid = c.getLong(0)
                val exists = db.query(
                    table, arrayOf("id"), "waiter_id = ?", arrayOf(wid.toString()),
                    null, null, null, "1"
                ).use { it.moveToFirst() }
                if (!exists) insert(wid, c.getString(1).orEmpty())
            }
        }
    }

    /** All assignments, newest first. */
    fun getAll(): List<Assignment> {
        val list = mutableListOf<Assignment>()
        helper.readableDatabase.query(
            table, arrayOf("id", "waiter_id", "waiter_name"),
            (if (currentStoreId() != null) "store_id = ?" else null),
            currentStoreId()?.let { arrayOf(it.toString()) },
            null, null, "id DESC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(Assignment(c.getLong(0), c.getLong(1), c.getString(2).orEmpty()))
            }
        }
        return list
    }

    /** Existing waiters (current store) to populate the assign dropdown. */
    fun waiters(): List<WaiterOption> {
        val list = mutableListOf<WaiterOption>()
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_WAITERS, arrayOf("id", "waiter_name"),
            (if (currentStoreId() != null) "store_id = ?" else null),
            currentStoreId()?.let { arrayOf(it.toString()) },
            null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(WaiterOption(c.getLong(0), c.getString(1).orEmpty()))
            }
        }
        return list
    }

    /**
     * Assigns a waiter. Upserts by waiter id so the same waiter is never
     * duplicated (whether it arrives from the master sync or a manual assign).
     * Returns the affected row id (or -1 on failure).
     */
    fun insert(waiterId: Long, waiterName: String): Long {
        val db = helper.writableDatabase
        val existingId = db.query(
            table, arrayOf("id"), "waiter_id = ?", arrayOf(waiterId.toString()),
            null, null, null, "1"
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (existingId != null) {
            db.update(table, ContentValues().apply {
                put("waiter_name", waiterName)
                put("modified_at", now())
                put("modified_by", currentUser())
            }, "id = ?", arrayOf(existingId.toString()))
            return existingId
        }
        return db.insert(table, null, ContentValues().apply {
            put("store_id", currentStoreId())
            put("waiter_id", waiterId)
            put("waiter_name", waiterName)
            put("created_by", currentUser())
        })
    }

    /** Updates the waiter for [id]. */
    fun update(id: Long, waiterId: Long, waiterName: String): Int {
        val values = ContentValues().apply {
            put("waiter_id", waiterId)
            put("waiter_name", waiterName)
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        return helper.writableDatabase.update(table, values, "id=?", arrayOf(id.toString()))
    }

    /** Deletes every assignment in [ids]. */
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
}
