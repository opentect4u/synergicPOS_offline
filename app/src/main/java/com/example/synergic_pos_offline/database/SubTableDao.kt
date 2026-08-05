package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager

/**
 * Master for split sub-tables ([DatabaseHelper.Tables.MD_SUBTABLE]). When a table
 * is split, one row per part is created — e.g. "101 A", "101 B" — recording the
 * parent table code and the suffix. Store-scoped by the signed-in user's store.
 */
class SubTableDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_SUBTABLE

    data class SubTable(val id: Long, val parentCode: String, val subCode: String, val suffix: String)

    /** Records a sub-table part under its parent (e.g. parent "101", suffix "A"). */
    fun create(parentCode: String, suffix: String, status: String = "Occupied"): String {
        val subCode = "$parentCode $suffix"
        helper.writableDatabase.insert(table, null, ContentValues().apply {
            put("store_id", currentStoreId())
            put("table_id", tableIdForCode(parentCode))
            put("parent_code", parentCode)
            put("sub_code", subCode)
            put("suffix", suffix)
            put("table_status", status)
            put("created_by", SessionManager.auditUser)
        })
        return subCode
    }

    /** Updates a sub-table's live status by its sub_code (current store). */
    fun setStatus(subCode: String, status: String) {
        val store = currentStoreId()
        val where = if (store != null) "sub_code = ? AND store_id = ?" else "sub_code = ?"
        val args = if (store != null) arrayOf(subCode, store.toString()) else arrayOf(subCode)
        helper.writableDatabase.update(table, ContentValues().apply { put("table_status", status) }, where, args)
    }

    /** All sub-table codes recorded for a parent (current store). */
    fun subCodesForParent(parentCode: String): List<String> {
        val store = currentStoreId()
        val where = if (store != null) "parent_code = ? AND store_id = ?" else "parent_code = ?"
        val args = if (store != null) arrayOf(parentCode, store.toString()) else arrayOf(parentCode)
        val out = mutableListOf<String>()
        helper.readableDatabase.query(table, arrayOf("sub_code"), where, args, null, null, "suffix ASC").use { c ->
            while (c.moveToNext()) c.getString(0)?.let { out.add(it) }
        }
        return out
    }

    /** Removes all sub-table rows for a parent (once the whole table is settled). */
    fun clearForParent(parentCode: String) {
        val store = currentStoreId()
        val where = if (store != null) "parent_code = ? AND store_id = ?" else "parent_code = ?"
        val args = if (store != null) arrayOf(parentCode, store.toString()) else arrayOf(parentCode)
        helper.writableDatabase.delete(table, where, args)
    }

    private fun tableIdForCode(code: String): Long? {
        val store = currentStoreId()
        val where = if (store != null) "table_code = ? AND store_id = ?" else "table_code = ?"
        val args = if (store != null) arrayOf(code, store.toString()) else arrayOf(code)
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_TABLE, arrayOf("id"), where, args, null, null, null, "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }

    private fun currentStoreId(): Long? {
        SessionManager.currentUser?.storeId?.takeIf { it != 0 }?.let { return it.toLong() }
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }
}
