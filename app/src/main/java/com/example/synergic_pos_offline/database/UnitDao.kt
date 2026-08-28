package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data-access layer for the [DatabaseHelper.Tables.MD_UNITS] master table.
 *
 * Like categories, units carry no explicit code column; the "Unit Code" is
 * derived from the row id via [formatCode] so it stays stable and gap-free.
 */
class UnitDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_UNITS

    /** A single unit row. [fraction] mirrors the fraction_flag column. */
    data class Unit(
        val id: Long,
        val name: String,
        val symbol: String,
        val fraction: Boolean
    ) {
        val code: String get() = formatCode(id)
    }

    /** All units, oldest first (so codes read in ascending order). */
    fun getAll(): List<Unit> {
        val list = mutableListOf<Unit>()
        helper.readableDatabase.query(
            table, arrayOf("id", "unit_name", "unit_symbol", "fraction_flag"),
            (if (currentStoreId() != null) "store_id = ?" else null),
            currentStoreId()?.let { arrayOf(it.toString()) },
            null, null, "id ASC"
        ).use { c ->
            val iId = c.getColumnIndexOrThrow("id")
            val iName = c.getColumnIndexOrThrow("unit_name")
            val iSym = c.getColumnIndexOrThrow("unit_symbol")
            val iFrac = c.getColumnIndexOrThrow("fraction_flag")
            while (c.moveToNext()) {
                list.add(
                    Unit(
                        c.getLong(iId),
                        c.getString(iName).orEmpty(),
                        c.getString(iSym).orEmpty(),
                        c.getInt(iFrac) == 1
                    )
                )
            }
        }
        return list
    }

    /** Inserts a new unit and returns its new row id (or -1 on failure). */
    fun insert(name: String, symbol: String, fraction: Boolean): Long {
        val values = ContentValues().apply {
            put("store_id", currentStoreId())
            put("unit_name", name)
            put("unit_symbol", symbol.take(SHORT_NAME_MAX))
            put("fraction_flag", if (fraction) 1 else 0)
            put("created_by", currentUser())
        }
        return helper.writableDatabase.insert(table, null, values)
    }

    /** Updates name, symbol and fraction flag for [id]. */
    fun update(id: Long, name: String, symbol: String, fraction: Boolean): Int {
        val values = ContentValues().apply {
            put("unit_name", name)
            put("unit_symbol", symbol.take(SHORT_NAME_MAX))
            put("fraction_flag", if (fraction) 1 else 0)
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        return helper.writableDatabase.update(table, values, "id=?", arrayOf(id.toString()))
    }

    /** Deletes every unit in [ids]. */
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
        // The signed-in user's store is the current store; the registration row is
        // only a fallback (e.g. seeding before anyone has logged in).
        SessionManager.currentUser?.storeId?.takeIf { it != 0 }?.let { return it.toLong() }
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    private fun currentUser(): String? = SessionManager.auditUser

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        /**
         * How long a unit's short name may be.
         *
         * Three. It is printed beside a quantity on a slip and shown on a cart line,
         * where there is room for an abbreviation and not for a word - "1.5 Ltr", not
         * "1.5 Litres". Named here rather than typed into the field, the save and the
         * seed separately, so the three places that enforce it cannot drift apart.
         */
        const val SHORT_NAME_MAX = 3

        /**
         * What to print beside a quantity for a unit: its short name, or - when the
         * shop never gave it one - the first [SHORT_NAME_MAX] characters of its name.
         *
         * The short name is optional, so a unit can perfectly well be "Litres" with
         * nothing in the short box. Printing nothing there leaves a bare number on the
         * slip, which is worse than an abbreviation nobody chose: "1.5 Lit" is read
         * correctly by anyone holding the bill, "1.5" is not.
         *
         * Derived rather than written back into the row. The blank is the shop's own
         * choice and quietly filling it in would take that choice away - and would
         * fix today's guess in place if the unit were renamed tomorrow.
         */
        fun shortNameOf(shortName: String?, unitName: String?): String {
            shortName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(SHORT_NAME_MAX) }
            return unitName?.trim()?.take(SHORT_NAME_MAX).orEmpty()
        }

        /** Renders a stable unit code from a row id, e.g. 7 -> "UNIT007". */
        fun formatCode(id: Long): String = "UNIT" + id.toString().padStart(3, '0')
    }
}
