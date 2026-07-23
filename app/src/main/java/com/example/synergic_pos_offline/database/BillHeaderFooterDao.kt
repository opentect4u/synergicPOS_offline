package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context

/**
 * Data-access layer for bill header/footer lines, which live in two tables:
 * [DatabaseHelper.Tables.MD_HEADERS] and [DatabaseHelper.Tables.MD_FOOTERS].
 *
 * Both are filtered to `*_type = 'BILL'`. Rows from the two tables share an id
 * space, so each entry is addressed by a [rowKey] that prefixes the id with the
 * section ("H12" / "F3").
 */
class BillHeaderFooterDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    enum class Section { HEADER, FOOTER }

    /**
     * Allowed font sizes, paired with a human label for the dropdown and the point
     * size they print at.
     *
     * [sp] lives here rather than in whatever is drawing the line, so the receipt,
     * a print preview and any future kitchen ticket all render a MEDIUM header at
     * the same size. MEDIUM matches the receipt's body text; the rest step around it.
     */
    enum class FontSize(val stored: String, val label: String, val sp: Float) {
        SMALL("SMALL", "Small", 10f),
        MEDIUM("MEDIUM", "Medium", 12f),
        BIG("BIG", "Big", 15f),
        EXTRA_LARGE("EXTRA_LARGE", "Extra Large", 19f);

        companion object {
            fun fromStored(v: String?): FontSize =
                values().firstOrNull { it.stored == v } ?: MEDIUM
            fun fromLabel(v: String?): FontSize =
                values().firstOrNull { it.label == v } ?: MEDIUM
        }
    }

    /** A single header/footer line. */
    data class Entry(
        val id: Long,
        val section: Section,
        val number: Int,
        val text: String,
        val fontSize: FontSize,
        val bold: Boolean,
        val enabled: Boolean
    ) {
        val rowKey: String get() = (if (section == Section.HEADER) "H" else "F") + id
    }

    // ---- Read --------------------------------------------------------------

    /** All BILL header lines followed by all BILL footer lines, by number. */
    fun getAll(): List<Entry> = readSection(Section.HEADER) + readSection(Section.FOOTER)

    private fun readSection(section: Section): List<Entry> {
        val cfg = config(section)
        val list = mutableListOf<Entry>()
        helper.readableDatabase.query(
            cfg.table,
            arrayOf("id", cfg.numberCol, cfg.textCol, "font_size", "is_bold", "is_enabled"),
            "${cfg.typeCol} = ?", arrayOf("BILL"), null, null, "${cfg.numberCol} ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Entry(
                        id = c.getLong(0),
                        section = section,
                        number = c.getInt(1),
                        text = c.getString(2).orEmpty(),
                        fontSize = FontSize.fromStored(c.getString(3)),
                        bold = c.getInt(4) == 1,
                        enabled = c.getInt(5) == 1
                    )
                )
            }
        }
        return list
    }

    // ---- Write -------------------------------------------------------------

    /** Inserts a new line into the section's table; returns its [Entry.rowKey]. */
    fun insert(
        section: Section, text: String, fontSize: FontSize,
        bold: Boolean, enabled: Boolean
    ): String? {
        val cfg = config(section)
        val values = ContentValues().apply {
            put("store_id", currentStoreId())
            put(cfg.numberCol, nextNumber(section))
            put(cfg.textCol, text)
            put("font_size", fontSize.stored)
            put("is_bold", if (bold) 1 else 0)
            put("is_enabled", if (enabled) 1 else 0)
            put(cfg.typeCol, "BILL")
        }
        val id = helper.writableDatabase.insert(cfg.table, null, values)
        return if (id == -1L) null else (if (section == Section.HEADER) "H" else "F") + id
    }

    /** Updates the line identified by [rowKey]. */
    fun update(
        rowKey: String, text: String, fontSize: FontSize,
        bold: Boolean, enabled: Boolean
    ): Int {
        val (section, id) = parseKey(rowKey) ?: return 0
        val cfg = config(section)
        val values = ContentValues().apply {
            put(cfg.textCol, text)
            put("font_size", fontSize.stored)
            put("is_bold", if (bold) 1 else 0)
            put("is_enabled", if (enabled) 1 else 0)
        }
        return helper.writableDatabase.update(cfg.table, values, "id = ?", arrayOf(id.toString()))
    }

    /** Toggles the enabled flag for the line identified by [rowKey]. */
    fun setEnabled(rowKey: String, enabled: Boolean): Int {
        val (section, id) = parseKey(rowKey) ?: return 0
        val values = ContentValues().apply { put("is_enabled", if (enabled) 1 else 0) }
        return helper.writableDatabase.update(config(section).table, values, "id = ?", arrayOf(id.toString()))
    }

    /** Number of BILL lines currently in a section (used to cap at 10). */
    fun count(section: Section): Int {
        val cfg = config(section)
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${cfg.table} WHERE ${cfg.typeCol} = 'BILL'", null
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Deletes every line named in [rowKeys], across both tables. */
    fun delete(rowKeys: Collection<String>) {
        val db = helper.writableDatabase
        for (key in rowKeys) {
            val (section, id) = parseKey(key) ?: continue
            db.delete(config(section).table, "id = ?", arrayOf(id.toString()))
        }
    }

    /** Next line number (1..10) for a section, based on the current max. */
    private fun nextNumber(section: Section): Int {
        val cfg = config(section)
        helper.readableDatabase.rawQuery(
            "SELECT MAX(${cfg.numberCol}) FROM ${cfg.table} WHERE ${cfg.typeCol} = 'BILL'", null
        ).use { c ->
            val max = if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0
            return (max + 1).coerceIn(1, 10)
        }
    }

    // ---- Helpers -----------------------------------------------------------

    private data class SectionConfig(
        val table: String, val numberCol: String, val textCol: String, val typeCol: String
    )

    private fun config(section: Section): SectionConfig = when (section) {
        Section.HEADER -> SectionConfig(
            DatabaseHelper.Tables.MD_HEADERS, "header_number", "header_text", "header_type"
        )
        Section.FOOTER -> SectionConfig(
            DatabaseHelper.Tables.MD_FOOTERS, "footer_number", "footer_text", "footer_type"
        )
    }

    private fun parseKey(rowKey: String): Pair<Section, Long>? {
        if (rowKey.length < 2) return null
        val section = when (rowKey.first()) {
            'H' -> Section.HEADER
            'F' -> Section.FOOTER
            else -> return null
        }
        val id = rowKey.substring(1).toLongOrNull() ?: return null
        return section to id
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
