package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CRUD for the restaurant table master ([DatabaseHelper.Tables.MD_TABLE]).
 *
 * Each row is one individual table (section, code, floor, seating, status).
 * Adding a range expands into one row per table code. Store-scoped by the
 * signed-in user's store.
 */
class TableDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_TABLE

    data class TableRow(
        val id: Long,
        val sectionId: Long?,
        val tableCode: String,
        val floorNo: String,
        val seatingCapacity: Int,
        val status: String
    )

    /** A selectable section for the dropdown, with its total table count. */
    data class SectionOption(val id: Long, val name: String, val noOfTables: Int)

    /** For the add dialog: tables already created for a section + its highest code. */
    data class SectionUsage(val count: Int, val maxCode: Int?)

    /** A waiter available to assign to a table. */
    data class WaiterOption(val id: Long, val name: String)

    /** Result of a table-code lookup: its section and assigned waiter (if any). */
    data class TableLookup(val sectionName: String, val waiterName: String?)

    /**
     * Finds a table by its code (current store) and returns its section name and
     * assigned waiter name. Null when no such table exists.
     */
    fun lookupByCode(code: String): TableLookup? {
        val store = currentStoreId()
        val where = if (store != null) "t.table_code = ? AND t.store_id = ?" else "t.table_code = ?"
        val args = if (store != null) arrayOf(code, store.toString()) else arrayOf(code)
        helper.readableDatabase.rawQuery(
            "SELECT s.section_name, w.waiter_name FROM $table t " +
                "LEFT JOIN ${DatabaseHelper.Tables.MD_SECTION} s ON s.id = t.section_id " +
                "LEFT JOIN ${DatabaseHelper.Tables.MD_WAITERS} w ON w.id = t.waiter_id " +
                "WHERE $where LIMIT 1",
            args
        ).use { c ->
            if (c.moveToFirst()) {
                return TableLookup(
                    sectionName = c.getString(0).orEmpty(),
                    waiterName = c.getString(1)?.takeIf { it.isNotBlank() }
                )
            }
        }
        return null
    }

    /**
     * One list row: a section+waiter group summarised. The same section can appear
     * more than once when different table ranges are assigned different waiters.
     * [sectionCapacity] is the section's total; [count] is this group's tables.
     */
    data class Allocation(
        val sectionId: Long?,
        val sectionName: String,
        val sectionCapacity: Int,
        val count: Int,
        val fromCode: Int?,
        val toCode: Int?,
        val waiterId: Long?
    )

    /** Tables grouped by section AND waiter — one row per (section, waiter). */
    fun allocations(): List<Allocation> {
        val list = mutableListOf<Allocation>()
        val store = currentStoreId()
        val where = if (store != null) "WHERE t.store_id = ?" else ""
        val args = if (store != null) arrayOf(store.toString()) else null
        helper.readableDatabase.rawQuery(
            "SELECT t.section_id, s.section_name, s.no_of_tables, COUNT(*), " +
                "MIN(CAST(t.table_code AS INTEGER)), MAX(CAST(t.table_code AS INTEGER)), t.waiter_id " +
                "FROM $table t LEFT JOIN ${DatabaseHelper.Tables.MD_SECTION} s ON s.id = t.section_id " +
                "$where GROUP BY t.section_id, t.waiter_id " +
                "ORDER BY s.section_name COLLATE NOCASE, MIN(CAST(t.table_code AS INTEGER))",
            args
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Allocation(
                        sectionId = if (c.isNull(0)) null else c.getLong(0),
                        sectionName = c.getString(1).orEmpty(),
                        sectionCapacity = c.getInt(2),
                        count = c.getInt(3),
                        fromCode = if (c.isNull(4)) null else c.getInt(4),
                        toCode = if (c.isNull(5)) null else c.getInt(5),
                        waiterId = if (c.isNull(6)) null else c.getLong(6)
                    )
                )
            }
        }
        return list
    }

    /** Individual tables of one section+waiter group (current store), ordered by code. */
    fun tablesForGroup(sectionId: Long, waiterId: Long?): List<TableRow> {
        val list = mutableListOf<TableRow>()
        val store = currentStoreId()
        val cond = StringBuilder("section_id = ?")
        val args = mutableListOf(sectionId.toString())
        if (waiterId != null) { cond.append(" AND waiter_id = ?"); args.add(waiterId.toString()) }
        else cond.append(" AND waiter_id IS NULL")
        if (store != null) { cond.append(" AND store_id = ?"); args.add(store.toString()) }
        helper.readableDatabase.query(
            table,
            arrayOf("id", "section_id", "table_code", "floor_no", "seating_capacity", "table_status"),
            cond.toString(), args.toTypedArray(), null, null, "CAST(table_code AS INTEGER) ASC, id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    TableRow(
                        id = c.getLong(0),
                        sectionId = if (c.isNull(1)) null else c.getLong(1),
                        tableCode = c.getString(2).orEmpty(),
                        floorNo = c.getString(3).orEmpty(),
                        seatingCapacity = c.getInt(4),
                        status = c.getString(5).orEmpty().ifBlank { "Available" }
                    )
                )
            }
        }
        return list
    }

    /** Replaces one section+waiter group's tables, re-inserting them under [newWaiterId]. */
    fun replaceGroupTables(sectionId: Long, oldWaiterId: Long?, tables: List<TableRow>, newWaiterId: Long?) {
        val db = helper.writableDatabase
        val store = currentStoreId()
        db.beginTransaction()
        try {
            val whereArgs = mutableListOf(sectionId.toString())
            val where = StringBuilder("section_id = ?")
            if (oldWaiterId != null) { where.append(" AND waiter_id = ?"); whereArgs.add(oldWaiterId.toString()) }
            else where.append(" AND waiter_id IS NULL")
            db.delete(table, where.toString(), whereArgs.toTypedArray())
            for (t in tables) {
                val v = ContentValues().apply {
                    put("store_id", store)
                    put("section_id", sectionId)
                    put("table_code", t.tableCode.ifBlank { null })
                    put("floor_no", t.floorNo.ifBlank { null })
                    put("seating_capacity", t.seatingCapacity)
                    put("table_status", t.status)
                    if (newWaiterId != null) put("waiter_id", newWaiterId) else putNull("waiter_id")
                    put("created_by", currentUser())
                }
                db.insert(table, null, v)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Deletes one section+waiter group's tables. */
    fun deleteGroup(sectionId: Long, waiterId: Long?) {
        val whereArgs = mutableListOf(sectionId.toString())
        val where = StringBuilder("section_id = ?")
        if (waiterId != null) { where.append(" AND waiter_id = ?"); whereArgs.add(waiterId.toString()) }
        else where.append(" AND waiter_id IS NULL")
        helper.writableDatabase.delete(table, where.toString(), whereArgs.toTypedArray())
    }

    /** Individual tables of one section (current store), ordered by code. */
    fun tablesForSection(sectionId: Long): List<TableRow> {
        val list = mutableListOf<TableRow>()
        val store = currentStoreId()
        val where = if (store != null) "section_id = ? AND store_id = ?" else "section_id = ?"
        val args = if (store != null) arrayOf(sectionId.toString(), store.toString()) else arrayOf(sectionId.toString())
        helper.readableDatabase.query(
            table,
            arrayOf("id", "section_id", "table_code", "floor_no", "seating_capacity", "table_status"),
            where, args, null, null, "CAST(table_code AS INTEGER) ASC, id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    TableRow(
                        id = c.getLong(0),
                        sectionId = if (c.isNull(1)) null else c.getLong(1),
                        tableCode = c.getString(2).orEmpty(),
                        floorNo = c.getString(3).orEmpty(),
                        seatingCapacity = c.getInt(4),
                        status = c.getString(5).orEmpty().ifBlank { "Available" }
                    )
                )
            }
        }
        return list
    }

    /** The section's currently assigned waiter (current store), or null. */
    fun sectionWaiter(sectionId: Long): Long? {
        val store = currentStoreId()
        val where = if (store != null) "section_id = ? AND store_id = ?" else "section_id = ?"
        val args = if (store != null) arrayOf(sectionId.toString(), store.toString()) else arrayOf(sectionId.toString())
        helper.readableDatabase.rawQuery(
            "SELECT MAX(waiter_id) FROM $table WHERE $where", args
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }

    /** Replaces every table of [sectionId] with [tables] (delete then insert), each with [waiterId]. */
    fun replaceSectionTables(sectionId: Long, tables: List<TableRow>, waiterId: Long?) {
        val db = helper.writableDatabase
        val store = currentStoreId()
        db.beginTransaction()
        try {
            db.delete(table, "section_id = ?", arrayOf(sectionId.toString()))
            for (t in tables) {
                val v = ContentValues().apply {
                    put("store_id", store)
                    put("section_id", sectionId)
                    put("table_code", t.tableCode.ifBlank { null })
                    put("floor_no", t.floorNo.ifBlank { null })
                    put("seating_capacity", t.seatingCapacity)
                    put("table_status", t.status)
                    if (waiterId != null) put("waiter_id", waiterId) else putNull("waiter_id")
                    put("created_by", currentUser())
                }
                db.insert(table, null, v)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Deletes all tables belonging to [sectionIds]. */
    fun deleteSections(sectionIds: List<Long>) {
        if (sectionIds.isEmpty()) return
        val placeholders = sectionIds.joinToString(",") { "?" }
        helper.writableDatabase.delete(
            table, "section_id IN ($placeholders)", sectionIds.map { it.toString() }.toTypedArray()
        )
    }

    fun getAll(): List<TableRow> {
        val list = mutableListOf<TableRow>()
        val store = currentStoreId()
        helper.readableDatabase.query(
            table,
            arrayOf("id", "section_id", "table_code", "floor_no", "seating_capacity", "table_status"),
            (if (store != null) "store_id = ?" else null),
            store?.let { arrayOf(it.toString()) },
            null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    TableRow(
                        id = c.getLong(0),
                        sectionId = if (c.isNull(1)) null else c.getLong(1),
                        tableCode = c.getString(2).orEmpty(),
                        floorNo = c.getString(3).orEmpty(),
                        seatingCapacity = c.getInt(4),
                        status = c.getString(5).orEmpty().ifBlank { "Available" }
                    )
                )
            }
        }
        return list
    }

    /** Sections of the current store, for the Section dropdown. */
    fun sections(): List<SectionOption> {
        val list = mutableListOf<SectionOption>()
        val store = currentStoreId()
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_SECTION, arrayOf("id", "section_name", "no_of_tables"),
            (if (store != null) "store_id = ?" else null),
            store?.let { arrayOf(it.toString()) },
            null, null, "section_name COLLATE NOCASE"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(SectionOption(c.getLong(0), c.getString(1).orEmpty(), c.getInt(2)))
            }
        }
        return list
    }

    /** How many tables a section already has and its highest table code (current store). */
    fun sectionUsage(sectionId: Long): SectionUsage {
        val store = currentStoreId()
        val where = if (store != null) "section_id = ? AND store_id = ?" else "section_id = ?"
        val args = if (store != null) arrayOf(sectionId.toString(), store.toString()) else arrayOf(sectionId.toString())
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*), MAX(CAST(table_code AS INTEGER)) FROM $table WHERE $where", args
        ).use { c ->
            if (c.moveToFirst()) {
                return SectionUsage(c.getInt(0), if (c.isNull(1)) null else c.getInt(1))
            }
        }
        return SectionUsage(0, null)
    }

    /** Waiters of the current store, for the table's waiter dropdown. */
    fun waiters(): List<WaiterOption> {
        val list = mutableListOf<WaiterOption>()
        val store = currentStoreId()
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_WAITERS, arrayOf("id", "waiter_name"),
            (if (store != null) "store_id = ?" else null),
            store?.let { arrayOf(it.toString()) },
            null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) list.add(WaiterOption(c.getLong(0), c.getString(1).orEmpty()))
        }
        return list
    }

    /**
     * Inserts one individual table per code in [from]..[to] (inclusive), each with
     * table_code = the code and the given [waiterId]. Returns the rows created.
     */
    fun insertRange(sectionId: Long?, floorNo: String, from: Int, to: Int, waiterId: Long?): Int {
        if (to < from) return 0
        val db = helper.writableDatabase
        val store = currentStoreId()
        var count = 0
        db.beginTransaction()
        try {
            for (code in from..to) {
                val v = ContentValues().apply {
                    put("store_id", store)
                    if (sectionId != null) put("section_id", sectionId) else putNull("section_id")
                    put("table_code", code.toString())
                    if (floorNo.isBlank()) putNull("floor_no") else put("floor_no", floorNo)
                    if (waiterId != null) put("waiter_id", waiterId) else putNull("waiter_id")
                    put("seating_capacity", 0)
                    put("table_status", "Available")
                    put("created_by", currentUser())
                }
                if (db.insert(table, null, v) != -1L) count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    /** Updates a single table row. */
    fun update(id: Long, row: TableRow): Int {
        val v = ContentValues().apply {
            if (row.sectionId != null) put("section_id", row.sectionId) else putNull("section_id")
            put("table_code", row.tableCode.ifBlank { null })
            put("floor_no", row.floorNo.ifBlank { null })
            put("seating_capacity", row.seatingCapacity)
            put("table_status", row.status)
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        return helper.writableDatabase.update(table, v, "id=?", arrayOf(id.toString()))
    }

    fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        helper.writableDatabase.delete(table, "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
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
