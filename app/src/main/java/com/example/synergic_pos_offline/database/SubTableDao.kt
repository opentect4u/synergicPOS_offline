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

    /**
     * Removes ONE part of a split (e.g. "4 C"), leaving its siblings alone.
     *
     * How a split is given up a part at a time: the parts nobody used are dropped by
     * the operator rather than swept away by the till, and dropping the last one is
     * what ends the split. See RestaurantOrdersFragment.freeParentIfSplitDone.
     */
    fun remove(subCode: String, section: String) {
        val (where, args) = parentScope(subCode.substringBeforeLast(" ").trim(), section)
        helper.writableDatabase.delete(table, "$where AND sub_code = ?", args + subCode)
    }

    /** Removes all sub-table rows for a parent (once the whole table is settled). */
    fun clearForParent(parentCode: String, section: String) {
        val (where, args) = parentScope(parentCode, section)
        helper.writableDatabase.delete(table, where, args)
    }

    /**
     * Narrows to the parts of ONE physical table.
     *
     * Sub-codes are built from the parent code ("1 A"), and a parent code repeats
     * across sections - Ac, No Ac and Cabin can each have a table 1 - so "1 A" on its
     * own names a part in every room at once. The table's own row id is what tells
     * them apart, and it is what the parts are scoped by.
     *
     * The code is used ONLY for parts that carry no table id, and only alongside the
     * id rather than instead of it. Falling back to the bare code whenever the id
     * matched nothing was what let one room's split reach into another's: a No Ac
     * table 1 with no parts of its own fell through to "parent_code = 1" and found
     * Ac's - so setting a status touched the wrong room's part, and clearing a parent
     * deleted a split that was still being served in the next room.
     */
    private fun parentScope(parentCode: String, section: String): Pair<String, Array<String>> {
        val store = currentStoreId()
        val tableId = tableIdForCode(parentCode, section)
        val where = StringBuilder()
        val args = mutableListOf<String>()
        if (tableId != null) {
            // This table's parts, plus any written before ids were recorded - those
            // carry no id at all, so they cannot belong to a different room's table.
            where.append("(table_id = ? OR (table_id IS NULL AND parent_code = ?))")
            args.add(tableId.toString()); args.add(parentCode)
        } else {
            // No master row to resolve (an unknown code, or a store with no sections):
            // the code is all there is to go on.
            where.append("parent_code = ?")
            args.add(parentCode)
        }
        if (store != null) { where.append(" AND store_id = ?"); args.add(store.toString()) }
        return where.toString() to args.toTypedArray()
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
