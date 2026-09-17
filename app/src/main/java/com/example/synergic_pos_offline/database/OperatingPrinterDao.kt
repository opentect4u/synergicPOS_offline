package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context

/**
 * Data-access layer for [DatabaseHelper.Tables.MD_OPERATING_PRINTER] - the
 * printer master fed by the Operating Printer screen. Each row names one
 * physical printer and points it at a BILL/KOT connection row in
 * [DatabaseHelper.Tables.MD_PRINTER]: the "printer" column stores that row's
 * sl_no (a foreign key, looked up from the chosen purpose+type combo), not
 * the combo text - [OperatingPrinter.printerPurpose]/[OperatingPrinter.printerType]
 * (and the derived [OperatingPrinter.printerLabel], "BILL-WIFI" etc.) are
 * resolved via a join purely for display and for the checkout print path.
 * [OperatingPrinter.value] carries the address to use: an IP for WIFI/LAN, a
 * Bluetooth MAC for BLUETOOTH, and the "VVVV:PPPP" vendor/product pair of the
 * chosen device for USB (see
 * [com.example.synergic_pos_offline.utils.UsbPrinters.addressOf]).
 * [OperatingPrinter.paperMm] is
 * this printer's own paper width (58 = 2 inch, 80 = 3 inch) - independent of
 * whatever md_printer's connection row is set to.
 *
 * [OperatingPrinter.printFlag] is derived from the connection's purpose -
 * "K" for KOT, "B" for BILL - and [OperatingPrinter.isDefault] marks the
 * printer used by default for that flag; only one row per flag is kept
 * default, mirroring [PrinterDao.setSelectedType]'s exclusivity. [getDefault]
 * is what [ThermalPrinter] calls at checkout to resolve the printer to send to.
 */
class OperatingPrinterDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_OPERATING_PRINTER
    private val printerTable = DatabaseHelper.Tables.MD_PRINTER

    data class OperatingPrinter(
        val slNo: Long,
        val printerName: String,
        val printerSlNo: Int,
        val printerPurpose: String?,
        val printerType: String?,
        val paperMm: Int,
        val value: String?,
        val printFlag: String,
        val isDefault: Boolean
    ) {
        val printerLabel: String
            get() = if (printerPurpose != null && printerType != null) "$printerPurpose-$printerType" else ""

        /**
         * "2 inch (58mm)" / "3 inch (80mm)" - the operator-facing paper width label.
         *
         * A dash for the barcode printer. Its stored width is whatever the column
         * defaults to and nothing reads it (see [usesPaperWidth]); printing "3 inch
         * (80mm)" against a roll of 50x25 labels would be the table stating something
         * that is not true of the machine it names.
         */
        val paperLabel: String get() = if (usesPaperWidth(printerPurpose)) paperLabelFor(paperMm) else "—"
    }

    /** All operating printers, ordered by serial number; each row's md_printer combo is joined in for display. */
    fun getAll(): List<OperatingPrinter> {
        val list = mutableListOf<OperatingPrinter>()
        helper.readableDatabase.rawQuery(
            """
            SELECT op.sl_no, op.printer_name, op.printer, op.value, op.print_flag, op.default_flag, op.paper_mm,
                   mp.printer_purpose, mp.printer_type
            FROM $table op
            LEFT JOIN $printerTable mp ON mp.sl_no = op.printer
            ORDER BY op.sl_no ASC
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    OperatingPrinter(
                        slNo = c.getLong(0),
                        printerName = c.getString(1).orEmpty(),
                        printerSlNo = c.getInt(2),
                        value = if (c.isNull(3)) null else c.getString(3),
                        printFlag = c.getString(4).orEmpty(),
                        isDefault = c.getInt(5) == 1,
                        paperMm = if (c.isNull(6)) DEFAULT_PAPER_MM else c.getInt(6),
                        printerPurpose = if (c.isNull(7)) null else c.getString(7),
                        printerType = if (c.isNull(8)) null else c.getString(8)
                    )
                )
            }
        }
        return list
    }

    /** The operating printer marked default for [printFlag] ("B" or "K"), or null if none is. */
    fun getDefault(printFlag: String): OperatingPrinter? =
        getAll().firstOrNull { it.printFlag.equals(printFlag, ignoreCase = true) && it.isDefault }

    /** Inserts a new operating printer and returns its new row id (or -1 on failure). */
    fun insert(printerName: String, printerSlNo: Int, purpose: String, value: String?, paperMm: Int, isDefault: Boolean): Long {
        val flag = flagFor(purpose)
        if (isDefault) clearDefault(flag, exceptSlNo = null)
        val values = ContentValues().apply {
            put("printer_name", printerName)
            put("printer", printerSlNo)
            if (value.isNullOrBlank()) putNull("value") else put("value", value.trim())
            put("print_flag", flag)
            put("paper_mm", paperMm)
            put("default_flag", if (isDefault) 1 else 0)
        }
        return helper.writableDatabase.insert(table, null, values)
    }

    /** Updates an existing operating printer. */
    fun update(slNo: Long, printerName: String, printerSlNo: Int, purpose: String, value: String?, paperMm: Int, isDefault: Boolean): Int {
        val flag = flagFor(purpose)
        if (isDefault) clearDefault(flag, exceptSlNo = slNo)
        val values = ContentValues().apply {
            put("printer_name", printerName)
            put("printer", printerSlNo)
            if (value.isNullOrBlank()) putNull("value") else put("value", value.trim())
            put("print_flag", flag)
            put("paper_mm", paperMm)
            put("default_flag", if (isDefault) 1 else 0)
        }
        return helper.writableDatabase.update(table, values, "sl_no = ?", arrayOf(slNo.toString()))
    }

    /** Flips just the default flag for one row - used by the table's inline switch. */
    fun setDefault(slNo: Long, printFlag: String, isDefault: Boolean): Int {
        if (isDefault) clearDefault(printFlag, exceptSlNo = slNo)
        val values = ContentValues().apply { put("default_flag", if (isDefault) 1 else 0) }
        return helper.writableDatabase.update(table, values, "sl_no = ?", arrayOf(slNo.toString()))
    }

    /** Deletes every operating printer in [ids]. */
    fun delete(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        return helper.writableDatabase.delete(table, "sl_no IN ($placeholders)", args)
    }

    /** Clears default_flag on every other row sharing [printFlag] (one default per flag). */
    private fun clearDefault(printFlag: String, exceptSlNo: Long?) {
        val db = helper.writableDatabase
        if (exceptSlNo == null) {
            db.execSQL("UPDATE $table SET default_flag = 0 WHERE print_flag = ?", arrayOf(printFlag))
        } else {
            db.execSQL(
                "UPDATE $table SET default_flag = 0 WHERE print_flag = ? AND sl_no != ?",
                arrayOf(printFlag, exceptSlNo.toString())
            )
        }
    }

    companion object {
        const val DEFAULT_PAPER_MM = 80

        /** "K" for a KOT connection, "B" for a BILL connection. */
        /**
         * The one-letter flag a purpose is stored under in `print_flag`.
         *
         * BARCODE is the label printer - the TSC the Barcode screen sends TSPL to. It
         * used to fall through to "", and a blank flag is what
         * [com.example.synergic_pos_offline.utils.ThermalPrinter] treats as "this row
         * names no purpose", so a label printer added on the Connections screen saved
         * successfully and could then never be found again.
         *
         * Its letter is "O", not "R" or "L", because the purpose used to be called
         * OTHERS - see DatabaseHelper's v22 migration. The name on screen changed; the
         * letter did not, so a row saved before the rename still resolves.
         *
         * OTHERS is still accepted for the same reason: the migration renames the
         * md_printer rows, but a caller somewhere asking for the old name should get
         * the printer rather than silently nothing.
         */
        fun flagFor(purpose: String): String = when {
            purpose.equals("KOT", ignoreCase = true) -> "K"
            purpose.equals("BILL", ignoreCase = true) -> "B"
            purpose.equals("BARCODE", ignoreCase = true) -> "O"
            purpose.equals("OTHERS", ignoreCase = true) -> "O"
            else -> ""
        }

        /**
         * Whether [purpose] is the label printer, which has no paper WIDTH to set.
         *
         * A receipt printer is asked 58mm or 80mm because the roll is what it is and
         * every slip is scaled to it. A label printer's stock is not a width, it is a
         * width AND a height AND the gap between one label and the next - and those are
         * asked where a label is actually printed, on the Barcode screen, because the
         * operator changes rolls more often than they change printers.
         */
        fun usesPaperWidth(purpose: String?): Boolean =
            !(purpose.orEmpty().equals("BARCODE", ignoreCase = true) ||
                purpose.orEmpty().equals("OTHERS", ignoreCase = true))

        /** "2 inch (58mm)" / "3 inch (80mm)" - falls back to the raw width for anything else. */
        fun paperLabelFor(paperMm: Int): String = when (paperMm) {
            58 -> "2 inch (58mm)"
            80 -> "3 inch (80mm)"
            else -> "${paperMm}mm"
        }
    }
}
