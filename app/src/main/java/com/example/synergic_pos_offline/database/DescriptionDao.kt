package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data-access layer for the [DatabaseHelper.Tables.MD_DESCRIPTION] master table
 * (description/ledger heads used on receipts and payments).
 *
 * Unlike the other master screens, this table has a real `description_id_auto`
 * column, so the generated code is persisted (derived from the row id).
 */
class DescriptionDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_DESCRIPTION

    /** Allowed description types, paired with a human label for the dropdown. */
    enum class DescType(val stored: String, val label: String) {
        RECEIPT("RECEIPT", "Receipt"),
        PAYMENT("PAYMENT", "Payment");

        companion object {
            fun fromStored(v: String?): DescType = values().firstOrNull { it.stored == v } ?: RECEIPT
            fun fromLabel(v: String?): DescType = values().firstOrNull { it.label == v } ?: RECEIPT
        }
    }

    /** A single description row. [autoId] mirrors description_id_auto. */
    data class Description(
        val id: Long,
        val name: String,
        val type: DescType,
        val autoId: String
    )

    /** All descriptions, oldest first (so ids read in ascending order). */
    fun getAll(): List<Description> {
        val list = mutableListOf<Description>()
        helper.readableDatabase.query(
            table, arrayOf("id", "description_name", "description_type", "description_id_auto"),
            null, null, null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Description(
                        id = c.getLong(0),
                        name = c.getString(1).orEmpty(),
                        type = DescType.fromStored(c.getString(2)),
                        autoId = c.getString(3).orEmpty()
                    )
                )
            }
        }
        return list
    }

    /**
     * Inserts a new description, stamps its `description_id_auto` from the real
     * row id, and returns the created [Description] (or null on failure).
     */
    fun insert(name: String, type: DescType): Description? {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("store_id", currentStoreId())
            put("description_name", name)
            put("description_type", type.stored)
            put("created_by", currentUser())
        }
        val id = db.insert(table, null, values)
        if (id == -1L) return null
        val autoId = formatCode(id)
        db.update(table, ContentValues().apply { put("description_id_auto", autoId) },
            "id=?", arrayOf(id.toString()))
        return Description(id, name, type, autoId)
    }

    /** Updates name + type for [id]; the auto id stays fixed. */
    fun update(id: Long, name: String, type: DescType): Int {
        val values = ContentValues().apply {
            put("description_name", name)
            put("description_type", type.stored)
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        return helper.writableDatabase.update(table, values, "id=?", arrayOf(id.toString()))
    }

    /** Deletes every description in [ids]. */
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
        /** Renders a stable description code from a row id, e.g. 7 -> "DESC007". */
        fun formatCode(id: Long): String = "DESC" + id.toString().padStart(3, '0')
    }
}
