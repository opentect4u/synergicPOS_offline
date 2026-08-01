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

    private fun currentUser(): String? = SessionManager.currentUser?.userId
    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
