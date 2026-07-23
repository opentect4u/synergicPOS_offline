package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context

/**
 * Access to [DatabaseHelper.Tables.MD_PRINTER]. Each print purpose (BILL / KOT /
 * OTHERS) has several rows - one per connection type (WIFI / LAN / BLUETOOTH / USB) -
 * and one of them per purpose is the selected connection.
 */
class PrinterDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_PRINTER

    /**
     * One printer option. [ip] is null until an address has been saved; [paperMm]
     * is the paper width (58 or 80), null until chosen; [selected] marks the option
     * currently chosen for its purpose.
     */
    data class Printer(
        val slNo: Int,
        val purpose: String,
        val type: String,
        val ip: String?,
        val paperMm: Int?,
        val selected: Boolean
    )

    /** All printer options, ordered by serial number. */
    fun getAll(): List<Printer> {
        val list = mutableListOf<Printer>()
        helper.readableDatabase.query(
            table,
            arrayOf("sl_no", "printer_purpose", "printer_type", "printer_ip", "paper_mm", "is_selected"),
            null, null, null, null, "sl_no ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Printer(
                        slNo = c.getInt(0),
                        purpose = c.getString(1).orEmpty(),
                        type = c.getString(2).orEmpty(),
                        ip = if (c.isNull(3)) null else c.getString(3),
                        paperMm = if (c.isNull(4)) null else c.getInt(4),
                        selected = c.getInt(5) == 1
                    )
                )
            }
        }
        return list
    }

    /** The connection options for a purpose (BILL / KOT / OTHERS). */
    fun typesFor(purpose: String): List<Printer> =
        getAll().filter { it.purpose.equals(purpose, ignoreCase = true) }

    /** The chosen option for a purpose (falls back to the first if none is flagged). */
    fun getSelected(purpose: String): Printer? {
        val options = typesFor(purpose)
        return options.firstOrNull { it.selected } ?: options.firstOrNull()
    }

    /** The chosen option for a purpose - used by the print path. */
    fun get(purpose: String): Printer? = getSelected(purpose)

    /** Makes [type] the chosen connection for [purpose]. */
    fun setSelectedType(purpose: String, type: String) {
        val db = helper.writableDatabase
        db.execSQL("UPDATE $table SET is_selected = 0 WHERE printer_purpose = ?", arrayOf(purpose))
        db.execSQL(
            "UPDATE $table SET is_selected = 1 WHERE printer_purpose = ? AND printer_type = ?",
            arrayOf(purpose, type)
        )
    }

    /** Saves the address and paper width for one option (blank ip clears the address). */
    fun updateConfig(slNo: Int, ip: String?, paperMm: Int): Int {
        val values = ContentValues().apply {
            if (ip.isNullOrBlank()) putNull("printer_ip") else put("printer_ip", ip.trim())
            put("paper_mm", paperMm)
        }
        return helper.writableDatabase.update(table, values, "sl_no = ?", arrayOf(slNo.toString()))
    }

    /** Saves only the paper width for one option (used where there is no IP, e.g. BT/USB). */
    fun updatePaper(slNo: Int, paperMm: Int): Int {
        val values = ContentValues().apply { put("paper_mm", paperMm) }
        return helper.writableDatabase.update(table, values, "sl_no = ?", arrayOf(slNo.toString()))
    }
}
