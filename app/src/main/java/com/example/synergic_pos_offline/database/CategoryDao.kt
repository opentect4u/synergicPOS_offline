package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data-access layer for the [DatabaseHelper.Tables.MD_CATEGORY] master table.
 *
 * Categories/departments do not carry an explicit code column; the human-facing
 * "Dept Code" is derived deterministically from the row id via [formatCode],
 * so the sequence stays stable and gap-free even across edits.
 */
class CategoryDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_CATEGORY

    /** A single category row. [image] holds the raw JPEG bytes, or null. */
    data class Category(val id: Long, val name: String, val image: ByteArray?) {
        val code: String get() = formatCode(id)
        val hasImage: Boolean get() = image != null && image.isNotEmpty()
    }

    /** All categories, oldest first (so codes read in ascending order). */
    fun getAll(): List<Category> {
        val list = mutableListOf<Category>()
        helper.readableDatabase.query(
            table, arrayOf("id", "category_name", "category_image"),
            null, null, null, null, "id ASC"
        ).use { c ->
            val iId = c.getColumnIndexOrThrow("id")
            val iName = c.getColumnIndexOrThrow("category_name")
            val iImg = c.getColumnIndexOrThrow("category_image")
            while (c.moveToNext()) {
                val img = if (c.isNull(iImg)) null else c.getBlob(iImg)
                list.add(Category(c.getLong(iId), c.getString(iName).orEmpty(), img))
            }
        }
        return list
    }

    /** The stored image bytes for one row, or null when none/absent. */
    fun getImage(id: Long): ByteArray? {
        helper.readableDatabase.query(
            table, arrayOf("category_image"), "id=?", arrayOf(id.toString()),
            null, null, null
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getBlob(0)
        }
        return null
    }

    /** Inserts a new category and returns its new row id (or -1 on failure). */
    fun insert(name: String, image: ByteArray?): Long {
        val values = ContentValues().apply {
            put("store_id", currentStoreId())
            put("category_name", name)
            if (image != null) put("category_image", image) else putNull("category_image")
            put("created_by", currentUser())
        }
        return helper.writableDatabase.insert(table, null, values)
    }

    /** Updates name + image for [id]. Passing a null image clears the stored one. */
    fun update(id: Long, name: String, image: ByteArray?): Int {
        val values = ContentValues().apply {
            put("category_name", name)
            if (image != null) put("category_image", image) else putNull("category_image")
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        return helper.writableDatabase.update(table, values, "id=?", arrayOf(id.toString()))
    }

    /** Deletes every category in [ids]. */
    fun delete(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        return helper.writableDatabase.delete(table, "id IN ($placeholders)", args)
    }

    /**
     * Names of the given categories that products are still filed under. Deleting
     * one is refused by the foreign key on md_products.category_id, so this lets the
     * caller say which category is holding things up instead of failing blindly.
     */
    fun namesInUse(ids: Collection<Long>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        val names = mutableListOf<String>()
        helper.readableDatabase.rawQuery(
            """
            SELECT c.category_name FROM $table c
            WHERE c.id IN ($placeholders)
              AND EXISTS (
                  SELECT 1 FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
                  WHERE p.category_id = c.id
              )
            ORDER BY c.category_name COLLATE NOCASE
            """.trimIndent(),
            args
        ).use { c ->
            while (c.moveToNext()) {
                names.add(c.getString(0)?.takeIf { it.isNotBlank() } ?: "Unnamed")
            }
        }
        return names
    }

    /** The largest existing id, or null when the table is empty. */
    fun lastId(): Long? {
        helper.readableDatabase.rawQuery("SELECT MAX(id) FROM $table", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    /**
     * The id the next inserted row will receive. Uses sqlite_sequence so the
     * preview matches AUTOINCREMENT exactly even after deletions.
     */
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
        /** Renders a stable department code from a row id, e.g. 7 -> "DEPT007". */
        fun formatCode(id: Long): String = "DEPT" + id.toString().padStart(3, '0')
    }
}
