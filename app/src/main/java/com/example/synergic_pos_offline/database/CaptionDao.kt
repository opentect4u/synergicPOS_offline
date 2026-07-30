package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context

/**
 * Data-access layer for printed caption lines, which live in
 * [DatabaseHelper.Tables.MD_CAPTIONS].
 *
 * A caption is the same thing as a header or footer line - text, size, weight, an
 * enabled flag, ten to a set - but keyed to what the slip *is* rather than where
 * on it the line sits. So it reads the way [BillHeaderFooterDao] does, with
 * [Type] standing in for that class's section, and the whole set living in one
 * table rather than two (ids are therefore unique on their own, and a row is
 * addressed by its plain id).
 */
class CaptionDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_CAPTIONS

    /**
     * Which slips a caption prints on.
     *
     * BILL is every bill; CREDIT adds to one billed on account; DUPLICATE adds to
     * a copy of a bill already issued. They stack - a credit bill reprinted from
     * Bill history carries all three.
     */
    enum class Type(val stored: String, val label: String) {
        BILL("BILL", "Bill"),
        DUPLICATE("DUPLICATE", "Duplicate"),
        CREDIT("CREDIT", "Credit");

        companion object {
            fun fromStored(v: String?): Type = values().firstOrNull { it.stored == v } ?: BILL
            fun fromLabel(v: String?): Type = values().firstOrNull { it.label == v } ?: BILL
        }
    }

    /** Font sizes are the header/footer set - one scale for everything printed. */
    private val fontOf = BillHeaderFooterDao.FontSize::fromStored

    /** A single caption line. */
    data class Entry(
        val id: Long,
        val type: Type,
        val number: Int,
        val text: String,
        val fontSize: BillHeaderFooterDao.FontSize,
        val bold: Boolean,
        val enabled: Boolean
    ) {
        val rowKey: String get() = id.toString()
    }

    // ---- Read --------------------------------------------------------------

    /** Every caption, whatever its type, by type then line number. */
    fun getAll(): List<Entry> = read(null)

    /**
     * The enabled captions that apply to a slip of these [types], in the order they
     * print. Used by the receipt renderer; a disabled line is simply absent.
     *
     * Grouped in the order [types] was given rather than by type name, so the
     * caller decides which caption leads - what the slip *is* reads before how it
     * was settled, and "CREDIT" sorting before "DUPLICATE" alphabetically does not
     * quietly invert that.
     */
    fun enabledFor(types: Collection<Type>): List<Entry> {
        if (types.isEmpty()) return emptyList()
        val rank = types.withIndex().associate { (index, type) -> type to index }
        return read(types)
            .filter { it.enabled }
            .sortedWith(compareBy({ rank[it.type] ?: Int.MAX_VALUE }, { it.number }))
    }

    private fun read(types: Collection<Type>?): List<Entry> {
        val selection = types?.let {
            "caption_type IN (${it.joinToString(",") { "?" }})"
        }
        val args = types?.map { it.stored }?.toTypedArray()
        val list = mutableListOf<Entry>()
        helper.readableDatabase.query(
            table,
            arrayOf("id", "caption_type", "caption_number", "caption_text", "font_size", "is_bold", "is_enabled"),
            selection, args, null, null, "caption_type ASC, caption_number ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Entry(
                        id = c.getLong(0),
                        type = Type.fromStored(c.getString(1)),
                        number = c.getInt(2),
                        text = c.getString(3).orEmpty(),
                        fontSize = fontOf(c.getString(4)),
                        bold = c.getInt(5) == 1,
                        enabled = c.getInt(6) == 1
                    )
                )
            }
        }
        return list
    }

    // ---- Write -------------------------------------------------------------

    /** Inserts a new caption; returns its [Entry.rowKey], or null if the write failed. */
    fun insert(
        type: Type, text: String, fontSize: BillHeaderFooterDao.FontSize,
        bold: Boolean, enabled: Boolean
    ): String? {
        val values = ContentValues().apply {
            put("store_id", currentStoreId())
            put("caption_number", nextNumber(type))
            put("caption_text", text)
            put("font_size", fontSize.stored)
            put("is_bold", if (bold) 1 else 0)
            put("is_enabled", if (enabled) 1 else 0)
            put("caption_type", type.stored)
        }
        val id = helper.writableDatabase.insert(table, null, values)
        return if (id == -1L) null else id.toString()
    }

    /**
     * Updates the caption identified by [rowKey].
     *
     * Unlike a header/footer, a type change is an ordinary update: every caption is
     * in the one table, so nothing has to move. The line number is renumbered into
     * the new type's sequence so the two sets stay ordered independently.
     */
    fun update(
        rowKey: String, type: Type, text: String, fontSize: BillHeaderFooterDao.FontSize,
        bold: Boolean, enabled: Boolean
    ): Int {
        val id = rowKey.toLongOrNull() ?: return 0
        val existing = read(null).firstOrNull { it.id == id }
        val values = ContentValues().apply {
            put("caption_text", text)
            put("font_size", fontSize.stored)
            put("is_bold", if (bold) 1 else 0)
            put("is_enabled", if (enabled) 1 else 0)
            put("caption_type", type.stored)
            if (existing != null && existing.type != type) put("caption_number", nextNumber(type))
        }
        return helper.writableDatabase.update(table, values, "id = ?", arrayOf(id.toString()))
    }

    /** Toggles the enabled flag for the caption identified by [rowKey]. */
    fun setEnabled(rowKey: String, enabled: Boolean): Int {
        val id = rowKey.toLongOrNull() ?: return 0
        val values = ContentValues().apply { put("is_enabled", if (enabled) 1 else 0) }
        return helper.writableDatabase.update(table, values, "id = ?", arrayOf(id.toString()))
    }

    /** Number of captions of a type (used to cap at 10). */
    fun count(type: Type): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $table WHERE caption_type = ?", arrayOf(type.stored)
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Deletes every caption named in [rowKeys]. */
    fun delete(rowKeys: Collection<String>) {
        val db = helper.writableDatabase
        for (key in rowKeys) {
            val id = key.toLongOrNull() ?: continue
            db.delete(table, "id = ?", arrayOf(id.toString()))
        }
    }

    /** Next line number (1..10) for a type, based on the current max. */
    private fun nextNumber(type: Type): Int {
        helper.readableDatabase.rawQuery(
            "SELECT MAX(caption_number) FROM $table WHERE caption_type = ?", arrayOf(type.stored)
        ).use { c ->
            val max = if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0
            return (max + 1).coerceIn(1, 10)
        }
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
}
