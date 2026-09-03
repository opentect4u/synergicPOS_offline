package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.DatabaseHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Carries a shop's catalogue - its products, their categories and rates, the rate
 * tiers and the units - from one till to another, without carrying the shop.
 *
 * ## What it is for
 *
 * A whole-database backup ([DatabaseBackup]) moves an installation: the same store,
 * the same books, onto replacement hardware. This moves something narrower and more
 * useful day to day - the catalogue on its own, so a second outlet, a new till, or a
 * shop of the same trade can be set up with the products already in it instead of
 * typed in again.
 *
 * ## Why store_id is written empty
 *
 * Nearly every row in this database carries a `store_id`, and nearly every screen
 * reads its rows back with `WHERE store_id = <this store>`. A catalogue exported with
 * the store it came from still stamped on it would load onto another till and be
 * invisible there - present in the tables, filtered out of every screen.
 *
 * So the export writes `store_id` as NULL: the file belongs to no shop. [restore]
 * then stamps it with whatever store this device is registered as, which is the step
 * that makes the catalogue *this* shop's. Between those two points the file is
 * portable, which is the whole point of it.
 *
 * ## What it does not carry
 *
 * Stock. Quantities on hand belong to a shop and a date, not to a catalogue: loading
 * one till's stock onto another would claim goods that are not on its shelves. The
 * products arrive knowing what they are and what they cost, and knowing nothing about
 * how many there are.
 *
 * ## Ids, and why the whole set has to travel together
 *
 * The rows keep their ids, and they refer to each other by them - a product names its
 * category, a rate names its product. That is why [restore] replaces all five tables
 * together rather than merging into what is already there: half a catalogue loaded
 * over another shop's half would leave rates priced against whatever product happened
 * to hold that id here. Replacing the set keeps the references inside it true.
 */
object MasterData {

    /**
     * The catalogue, parents before children.
     *
     * A product names a category and the rates name a product, so this is the order
     * the rows want to arrive in. The restore switches foreign keys off for its own
     * transaction and so does not depend on it, but a file that reads in dependency
     * order is one that can also be replayed by hand into any SQLite client.
     */
    val TABLES: Set<String> = linkedSetOf(
        DatabaseHelper.Tables.MD_UNITS,
        DatabaseHelper.Tables.MD_RATE_NAME,
        DatabaseHelper.Tables.MD_CATEGORY,
        DatabaseHelper.Tables.MD_PRODUCTS,
        DatabaseHelper.Tables.MD_PRODUCT_RATES
    )

    /** Written empty, so the file belongs to no one shop - see the class note. */
    private val STORE_COLUMNS = setOf("store_id")

    /** Where the exports go, under Downloads. */
    const val FOLDER = "masterbackup"

    /** The first line of the file, and how [looksLikeMasterExport] knows one. */
    const val TITLE = "Synergic POS master data"

    // ---- Export ---------------------------------------------------------------

    /**
     * The file's name, carrying the business mode it was taken in ("restaurant" /
     * "grocery") and the date and time - the same dated convention as the database
     * backups, plus the mode so a masters file says at a glance which kind of till it
     * came from.
     */
    fun fileName(at: Date, mode: String): String {
        val modeTag = mode.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
            .ifEmpty { "grocery" }
        return "synergic_masters_${modeTag}_" +
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(at) + ".sql"
    }

    /**
     * Writes the catalogue straight to [out].
     *
     * Streamed rather than assembled, like every other export here: a product master
     * with images in it is tens of megabytes, and the tills this runs on do not have
     * that to spare on the heap.
     */
    fun exportTo(context: Context, out: java.io.Writer): DatabaseBackup.Export =
        DatabaseBackup.exportTo(
            context = context,
            out = out,
            only = TABLES,
            nullColumns = STORE_COLUMNS,
            title = TITLE
        )

    /** The same export, as a string - for the tests. */
    fun export(context: Context): DatabaseBackup.Export =
        DatabaseBackup.export(
            context = context, only = TABLES, nullColumns = STORE_COLUMNS, title = TITLE
        )

    // ---- Restore --------------------------------------------------------------

    /** Whether [head] is a catalogue export rather than a whole-database backup. */
    fun looksLikeMasterExport(head: String): Boolean = head.contains(TITLE)

    /** What a restore did, and which store the catalogue now belongs to. */
    data class Result(
        val tables: Int,
        val rows: Int,
        val storeId: Long?,
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null
    }

    /**
     * Replaces this device's catalogue with the one in [lines], and makes it this
     * shop's.
     *
     * Two things worth knowing about the shape of this:
     *
     * The restore is bounded to [TABLES]. Whatever the file turns out to be - a
     * catalogue export, or a whole-database backup an operator picked by mistake -
     * nothing outside those four tables is touched, so this button cannot replace a
     * shop's bills.
     *
     * The store stamp is applied afterwards rather than during. The rows arrive with
     * `store_id` empty by design, and a catalogue full of NULL stores is a catalogue
     * no screen would show; stamping it in the same breath as loading it means the
     * two cannot get out of step.
     *
     * Blocking: it rewrites four tables. Callers run it off the main thread.
     */
    fun restore(context: Context, lines: Sequence<String>, schemaVersion: Int? = null): Result {
        val loaded = DatabaseBackup.restore(context, lines, schemaVersion, only = TABLES)
        if (!loaded.ok) return Result(0, 0, null, loaded.error)

        val storeId = registeredStoreId(context)
        if (storeId != null) {
            val db = DatabaseHelper.getInstance(context).writableDatabase
            db.beginTransaction()
            try {
                TABLES.forEach { table ->
                    runCatching {
                        db.execSQL(
                            "UPDATE $table SET store_id = ?", arrayOf<Any>(storeId)
                        )
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return Result(loaded.tables, loaded.rows, storeId)
    }

    /** The store this device is registered as - what the catalogue is stamped with. */
    private fun registeredStoreId(context: Context): Long? {
        DatabaseHelper.getInstance(context).readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }
}
