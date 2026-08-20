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
    fun create(parentCode: String, section: String, suffix: String, status: String = "Occupied"): String {
        val subCode = "$parentCode $suffix"
        helper.writableDatabase.insert(table, null, ContentValues().apply {
            put("store_id", currentStoreId())
            put("table_id", tableIdForCode(parentCode, section))
            put("parent_code", parentCode)
            put("sub_code", subCode)
            put("suffix", suffix)
            put("table_status", status)
            put("created_by", SessionManager.auditUser)
        })
        return subCode
    }

    /** Updates a sub-table's live status by its sub_code within [section]. */
    fun setStatus(subCode: String, section: String, status: String) {
        val (where, args) = parentScope(subCode.substringBeforeLast(" ").trim(), section)
        helper.writableDatabase.update(
            table, ContentValues().apply { put("table_status", status) },
            "$where AND sub_code = ?", args + subCode
        )
    }

    /** All sub-table codes recorded for a parent in [section] (current store). */
    fun subCodesForParent(parentCode: String, section: String): List<String> {
        val (where, args) = parentScope(parentCode, section)
        val out = mutableListOf<String>()
        helper.readableDatabase.query(table, arrayOf("sub_code"), where, args, null, null, "suffix ASC").use { c ->
            while (c.moveToNext()) c.getString(0)?.let { out.add(it) }
        }
        return out
    }

    /** Removes all sub-table rows for a parent (once the whole table is settled). */
    fun clearForParent(parentCode: String, section: String) {
        val (where, args) = parentScope(parentCode, section)
        helper.writableDatabase.delete(table, where, args)
    }

    /**
     * Narrows to the parts of ONE physical table. Sub-codes are built from the parent
     * code ("1 A"), and a parent code repeats across sections, so "1 A" alone names a
     * part in every room that has a table 1. Where the parent resolves to a master row
     * the parts are scoped by that row's id, which is unique; a parent with no master
     * row (or a store with no sections) falls back to the parent code.
     */
    private fun parentScope(parentCode: String, section: String): Pair<String, Array<String>> {
        val store = currentStoreId()
        val tableId = tableIdForCode(parentCode, section)
        val byId = tableId != null && hasRowsFor("table_id = ?", tableId.toString())
        val where = StringBuilder(if (byId) "table_id = ?" else "parent_code = ?")
        val args = mutableListOf(if (byId) tableId.toString() else parentCode)
        if (store != null) { where.append(" AND store_id = ?"); args.add(store.toString()) }
        return where.toString() to args.toTypedArray()
    }

    /**
     * Whether any part is already recorded under this scope. Parts split before table
     * ids were resolved per section carry whichever table row the code first matched,
     * so scoping them by the right table id would find nothing at all - those fall
     * back to the parent code, which is how they were written.
     */
    private fun hasRowsFor(where: String, arg: String): Boolean {
        helper.readableDatabase.query(table, arrayOf("id"), where, arrayOf(arg), null, null, null, "1")
            .use { c -> return c.moveToFirst() }
    }

    private fun tableIdForCode(code: String, section: String): Long? {
        val store = currentStoreId()
        val where = StringBuilder("table_code = ?")
        val args = mutableListOf(code)
        if (section.isNotBlank()) {
            where.append(
                " AND section_id IN (SELECT id FROM ${DatabaseHelper.Tables.MD_SECTION} " +
                    "WHERE section_name = ? COLLATE NOCASE"
            )
            args.add(section)
            if (store != null) { where.append(" AND store_id = ?"); args.add(store.toString()) }
            where.append(")")
        }
        if (store != null) { where.append(" AND store_id = ?"); args.add(store.toString()) }
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_TABLE, arrayOf("id"), where.toString(), args.toTypedArray(),
            null, null, null, "1"
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
