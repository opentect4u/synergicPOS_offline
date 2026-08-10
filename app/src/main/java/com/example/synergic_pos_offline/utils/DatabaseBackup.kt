package com.example.synergic_pos_offline.utils

import android.content.Context
import android.database.Cursor
import com.example.synergic_pos_offline.database.DatabaseHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Takes the till's data off one installation and puts it back on another.
 *
 * The client's case is a reinstall: everything sold, every product, every setting
 * has to survive the app being removed and put back. Android's own Auto Backup is
 * not that - it restores a snapshot of its own choosing, at a time of its choosing,
 * and only on a fresh install - so this is an export the operator takes and keeps.
 *
 * ## The format
 *
 * Plain SQL: a header of comments, then one `INSERT` per row, one statement per
 * line. It is readable, it can be opened in any text editor to see what is in it,
 * and restoring it is executing it. Values are written the way SQLite reads them -
 * numbers bare, text quoted with `''` for an embedded quote, blobs as `X'hex'`, and
 * newlines inside text as `char(10)` so a statement never runs past its own line.
 *
 ## What is carried
 *
 * Everything - see [EXCLUDED], which is empty. The registration and the users go
 * with the rest, so the restored device *is* the device the backup came from: the
 * store the rows refer to exists, and there is an operator belonging to it to log in
 * as. It follows that after a restore the old device's logins are the ones that work.
 *
 * `sqlite_sequence` is the one thing left out. It is not data but SQLite's own bookkeeping,
 * and it repairs itself: inserting a row with an explicit id carries the counter up
 * to it, so the ids in the backup are what the counters end up at.
 */
object DatabaseBackup {

    /** What the exported file is called; the timestamp keeps two of them apart. */
    fun fileName(): String =
        "synergic_backup_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".sql"

    /**
     * Tables the backup never carries. Nothing: the whole database goes.
     *
     * `md_users` and `md_registration` were held back at first, on the reasoning that
     * they describe the device rather than the business. That was wrong in practice.
     * Nearly every table carries a `store_id` and nearly every screen reads its rows
     * back with `WHERE store_id = <this store>`; leaving the registration behind left
     * the restored rows pointing at a store the new device was not, so the data was
     * all present and none of it appeared anywhere.
     *
     * Carrying the registration keeps the store the rows already refer to, and the
     * users alongside it so there is an operator who belongs to that store to log in
     * as. The consequence is deliberate and worth knowing: after a restore the device
     * is the old device, and the old device's logins are the ones that work.
     */
    val EXCLUDED = emptySet<String>()

    /** Marks the line that records the schema the backup was taken from. */
    private const val VERSION_TAG = "-- schema-version:"

    // ---- Backup --------------------------------------------------------------

    /**
     * How the export went.
     *
     * [scanned] is every table the backup looked at; [tables] is how many of them
     * actually held anything, and [rows] how much. The two are reported separately
     * because they are different facts and one of them reads alarmingly on its own:
     * "12 tables" sounds like twelve were chosen, when it means twenty-odd were read
     * and twelve had records in them.
     */
    data class Export(
        val sql: String,
        val tables: Int,
        val rows: Int,
        val scanned: Int,
        val empty: List<String>
    )

    /**
     * Writes the backup straight to [out], a table at a time.
     *
     * Tables are read from `sqlite_master` rather than from a list in the code, so a
     * table added to the schema later is in the backup without anyone remembering to
     * add it here - the failure that would otherwise be silent, and only discovered
     * on the restore that needed it.
     *
     * Nothing is held in memory but the row being written. A shop's whole database
     * assembled into one String first would be tens of megabytes on the heap before
     * a byte of it reached the file, and the tills this runs on do not have it to
     * spare.
     *
     * The header has to be written before the body but is not known until the body
     * has been counted, so the tables are read once to count them and again to write
     * them. Reading twice is cheap; holding the whole thing is not.
     */
    fun exportTo(context: Context, out: java.io.Writer): Export {
        val db = DatabaseHelper.getInstance(context).readableDatabase
        val all = tablesIn(db)

        val counts = LinkedHashMap<String, Int>()
        all.forEach { table ->
            db.rawQuery("SELECT count(*) FROM $table", null).use { c ->
                counts[table] = if (c.moveToFirst()) c.getInt(0) else 0
            }
        }
        val empty = counts.filterValues { it == 0 }.keys.toList()
        val carried = counts.filterValues { it > 0 }
        val rowCount = carried.values.sum()

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        out.write("-- Synergic POS data backup\n")
        out.write("-- taken: $stamp\n")
        out.write("$VERSION_TAG ${db.version}\n")
        out.write(
            "-- read every table in the database: ${all.size} of them, " +
                "${carried.size} holding $rowCount record(s)\n"
        )
        if (empty.isNotEmpty()) {
            out.write("-- these were read and held no records:\n")
            empty.chunked(6).forEach { out.write("--   " + it.joinToString(", ") + "\n") }
        }
        if (EXCLUDED.isEmpty()) {
            out.write("-- the whole database, store registration and users included\n")
        } else {
            out.write("-- not included: ${EXCLUDED.joinToString(", ")}\n")
        }
        out.write("-- restore this through Settings > About App > Restore\n\n")

        carried.keys.forEach { table ->
            out.write("-- $table (${counts[table]} rows)\n")
            db.rawQuery("SELECT * FROM $table", null).use { c ->
                val columns = c.columnNames
                val header = "INSERT INTO $table (" +
                    columns.joinToString(", ") { "\"$it\"" } + ") VALUES ("
                while (c.moveToNext()) {
                    out.write(header)
                    out.write(columns.indices.joinToString(", ") { literal(c, it) })
                    out.write(");\n")
                }
            }
            out.write("\n")
        }
        return Export("", carried.size, rowCount, all.size, empty)
    }

    /**
     * The same backup, returned as a string.
     *
     * For callers small enough to hold it - the tests, mainly. It runs [exportTo] so
     * there is one description of what a backup looks like rather than two that can
     * drift apart.
     */
    fun export(context: Context): Export {
        val writer = java.io.StringWriter()
        val summary = exportTo(context, writer)
        return summary.copy(sql = writer.toString())
    }

    /** Everything the database holds, less SQLite's own internals. */
    private fun tablesIn(db: android.database.sqlite.SQLiteDatabase): List<String> {
        val names = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY name",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                if (name !in EXCLUDED) names.add(name)
            }
        }
        return names
    }

    /** One value, written the way SQLite will read it back. */
    private fun literal(c: Cursor, index: Int): String = when (c.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> "NULL"
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(index).toString()
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index).toString()
        // A product image or a logo. Hex is long but it is exact, and it keeps the
        // file to one line per row like everything else.
        Cursor.FIELD_TYPE_BLOB -> "X'" + c.getBlob(index).joinToString("") { "%02x".format(it) } + "'"
        else -> text(c.getString(index))
    }

    /**
     * A text value as a SQL literal.
     *
     * Newlines are spliced in with `char(10)` rather than written raw: the restore
     * reads a statement per line, and an address with a line break in it would
     * otherwise split its own INSERT in two.
     */
    private fun text(value: String?): String {
        if (value == null) return "NULL"
        val quoted = value.replace("'", "''")
        if (!quoted.contains('\n') && !quoted.contains('\r')) return "'$quoted'"
        return quoted.split("\r\n", "\n", "\r")
            .joinToString(" || char(10) || ") { "'$it'" }
    }

    // ---- Restore -------------------------------------------------------------

    /**
     * How a restore went. [skipped] counts statements for tables this installation
     * does not have - a backup from a newer build, restored onto an older one.
     */
    data class Result(
        val tables: Int,
        val rows: Int,
        val skipped: Int,
        val schemaVersion: Int?,
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null
    }

    /**
     * Puts [sql] back into the database, replacing whatever is there.
     *
     * Everything happens in one transaction: a restore that stopped half way would
     * leave the till holding part of one shop's books and part of another's, which
     * is worse than a restore that did not happen. Foreign keys are switched off for
     * the duration because the rows arrive table by table and a bill's items land
     * before - or after - the bill itself depending on alphabetical order.
     *
     * Only the tables the file actually carries are cleared. A backup that predates
     * a table leaves that table alone rather than emptying it.
     */
    fun restore(context: Context, lines: Sequence<String>, schemaVersion: Int? = null): Result {
        val db = DatabaseHelper.getInstance(context).writableDatabase
        val present = tablesIn(db).toSet()

        var rows = 0
        var skipped = 0
        val cleared = LinkedHashSet<String>()

        // Everything, including turning foreign keys off and opening the
        // transaction, is inside the try. Those can throw as readily as the inserts
        // can, and an exception escaping this function reaches the operator as a
        // crashed app rather than as a message saying what went wrong.
        try {
            db.setForeignKeyConstraintsEnabled(false)
            db.beginTransaction()
            try {
                for (raw in lines) {
                    val statement = raw.trim()
                    if (!statement.startsWith("INSERT INTO ", ignoreCase = true)) continue
                    val table = tableOf(statement)
                    if (table == null || table !in present || table in EXCLUDED) {
                        skipped++
                        continue
                    }
                    // Cleared the first time its own rows arrive, so the file is read
                    // once from start to end rather than twice - a whole shop's
                    // database does not fit in memory as a list of statements.
                    if (cleared.add(table)) db.execSQL("DELETE FROM $table")
                    db.execSQL(statement)
                    rows++
                }
                if (rows == 0) {
                    return Result(0, 0, skipped, schemaVersion, "That file holds no data to restore")
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            android.util.Log.e("DatabaseBackup", "Restore failed", e)
            return Result(0, 0, 0, schemaVersion, e.message ?: "the file could not be read")
        } finally {
            runCatching { db.setForeignKeyConstraintsEnabled(true) }
        }

        // The settings cache is a copy of rows that have just been replaced; left
        // alone it would answer for the old installation until the next login.
        runCatching { SettingsCache.storeFromDb(context, "database restore") }
        return Result(cleared.size, rows, skipped, schemaVersion)
    }

    /** The table an `INSERT INTO <name> (...)` statement writes to. */
    private fun tableOf(statement: String): String? =
        Regex("""^INSERT\s+INTO\s+["']?([A-Za-z0-9_]+)["']?\s*\(""", RegexOption.IGNORE_CASE)
            .find(statement)?.groupValues?.get(1)

    /** The schema version the backup was taken from, if it says. */
    fun schemaVersionOf(sql: String): Int? = sql.lineSequence()
        .firstOrNull { it.trimStart().startsWith(VERSION_TAG) }
        ?.substringAfter(VERSION_TAG)?.trim()?.toIntOrNull()
}
