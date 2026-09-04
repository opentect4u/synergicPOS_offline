package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A product's name in one language - [DatabaseHelper.Tables.MD_PRODUCT_NAMES].
 *
 * One row per product per language, so a shop can hold the Hindi name and the Bangla
 * name of the same product at once. See the table's own note for why that is a table
 * rather than a column: with one slot, writing the second language destroys the first,
 * and a catalogue caught half-way through prints both on one bill.
 *
 * Names are the shop's own. Where there is none for the language being printed, the
 * renderers fall back to translating the English name - see RegionalName.
 */
class ProductNameDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_PRODUCT_NAMES

    /** This product's name in [lang], or null where the shop has not given one. */
    fun nameFor(productId: Int, lang: String): String? = runCatching {
        helper.readableDatabase.query(
            table, arrayOf("regional_name"),
            "product_id = ? AND lang_code = ?", arrayOf(productId.toString(), lang),
            null, null, null, "1"
        ).use { c ->
            if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
        }
    }.getOrNull()

    /**
     * Writes this product's name in [lang]. A blank [name] removes the row.
     *
     * Removing rather than storing "" is what keeps "the shop has not named this in
     * Bangla yet" a single state. Emptying the box is how a name is taken back, and
     * the product then falls back to the translation like any other.
     *
     * Only this language's row is touched: clearing the Bangla name leaves the Hindi
     * one exactly where it was.
     */
    fun save(productId: Int, lang: String, name: String) {
        val db = helper.writableDatabase
        val clean = name.trim()
        if (clean.isEmpty()) {
            runCatching {
                db.delete(table, "product_id = ? AND lang_code = ?",
                    arrayOf(productId.toString(), lang))
            }
            return
        }
        runCatching {
            val updated = db.update(
                table,
                ContentValues().apply {
                    put("regional_name", clean)
                    put("modified_at", now())
                    put("modified_by", SessionManager.auditUser)
                },
                "product_id = ? AND lang_code = ?", arrayOf(productId.toString(), lang)
            )
            if (updated == 0) {
                db.insert(table, null, ContentValues().apply {
                    put("store_id", currentStoreId())
                    put("product_id", productId)
                    put("lang_code", lang)
                    put("regional_name", clean)
                    put("created_by", SessionManager.auditUser)
                })
            }
        }
    }

    /**
     * Every name this store has written in [lang], keyed by the PRODUCT NAME in upper
     * case - the shape the renderers look names up by.
     *
     * One query for a whole document. A lookup per line would be a query per item on
     * the slip, with a printer waiting; see RegionalName, which calls this once.
     *
     * Keyed on the name rather than the id because that is all a rendered line carries:
     * a saved bill keeps the name each item was sold under, not a reference to a master
     * row that may since have been renamed or deleted.
     */
    fun namesFor(lang: String): Map<String, String> {
        val out = HashMap<String, String>()
        runCatching {
            helper.readableDatabase.rawQuery(
                "SELECT p.product_name, n.regional_name " +
                    "FROM $table n JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON p.id = n.product_id " +
                    "WHERE n.lang_code = ? AND n.regional_name IS NOT NULL " +
                    "AND TRIM(n.regional_name) <> ''",
                arrayOf(lang)
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val regional = c.getString(1)?.trim().orEmpty()
                    if (name.isNotEmpty() && regional.isNotEmpty()) {
                        out[name.uppercase()] = regional
                    }
                }
            }
        }
        return out
    }

    /**
     * Drops every language's name for [productIds] - for when the products themselves go.
     *
     * Not tidying up after the fact. These rows are md_products' CHILDREN by foreign
     * key, so a product the shop has named cannot be deleted while they stand: SQLite
     * refuses, and the master reports a perfectly ordinary product as one that is used
     * in existing records. Naming a product must not be what makes it undeletable.
     *
     * Every language at once, and not only the one the master happens to be on. The
     * product is going; a Hindi name left behind belongs to nothing.
     *
     * Deliberately not wrapped in runCatching, unlike the rest of this class: the
     * caller runs this inside its own transaction and needs a failure here to reach
     * it, so the product and its names go together or neither does.
     */
    fun deleteFor(productIds: List<String>) {
        if (productIds.isEmpty()) return
        helper.writableDatabase.delete(
            table,
            "product_id IN (${productIds.joinToString(",") { "?" }})",
            productIds.toTypedArray()
        )
    }

    private fun currentStoreId(): Long? =
        SessionManager.currentUser?.storeId?.takeIf { it != 0 }?.toLong()

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
