package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data-access layer for the [DatabaseHelper.Tables.MD_LOGOS] master table.
 *
 * Each row is an image (BLOB) tagged with a [LogoType]. The same table serves
 * both the Bill and KOT logo screens; callers pass the subset of types they own.
 */
class LogoDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_LOGOS

    /** Allowed logo slots, paired with a human label for the dropdown. */
    enum class LogoType(val stored: String, val label: String) {
        BILL_HEADER("BILL_HEADER", "Bill Header"),
        BILL_FOOTER("BILL_FOOTER", "Bill Footer"),
        KOT_HEADER("KOT_HEADER", "KOT Header"),
        KOT_FOOTER("KOT_FOOTER", "KOT Footer");

        companion object {
            fun fromStored(v: String?): LogoType? = values().firstOrNull { it.stored == v }
            fun fromLabel(v: String?): LogoType? = values().firstOrNull { it.label == v }
        }
    }

    /** A single logo row. [image] holds the raw JPEG bytes, or null. */
    data class Logo(val id: Long, val type: LogoType, val image: ByteArray?) {
        val hasImage: Boolean get() = image != null && image.isNotEmpty()
    }

    /** Every logo whose type is in [types], oldest first. */
    fun getAll(types: List<LogoType>): List<Logo> {
        if (types.isEmpty()) return emptyList()
        val placeholders = types.joinToString(",") { "?" }
        val args = types.map { it.stored }.toTypedArray()
        val list = mutableListOf<Logo>()
        helper.readableDatabase.query(
            table, arrayOf("id", "logo_type", "logo_image"),
            "logo_type IN ($placeholders)", args, null, null, "id ASC"
        ).use { c ->
            val iId = c.getColumnIndexOrThrow("id")
            val iType = c.getColumnIndexOrThrow("logo_type")
            val iImg = c.getColumnIndexOrThrow("logo_image")
            while (c.moveToNext()) {
                val type = LogoType.fromStored(c.getString(iType)) ?: continue
                val img = if (c.isNull(iImg)) null else c.getBlob(iImg)
                list.add(Logo(c.getLong(iId), type, img))
            }
        }
        return list
    }

    /** The stored image bytes for one row, or null when none/absent. */
    fun getImage(id: Long): ByteArray? {
        helper.readableDatabase.query(
            table, arrayOf("logo_image"), "id=?", arrayOf(id.toString()),
            null, null, null
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getBlob(0)
        }
        return null
    }

    /** Inserts a new logo and returns its new row id (or -1 on failure). */
    fun insert(type: LogoType, image: ByteArray?): Long {
        val values = ContentValues().apply {
            put("store_id", currentStoreId())
            put("logo_type", type.stored)
            if (image != null) put("logo_image", image) else putNull("logo_image")
            put("created_by", currentUser())
        }
        return helper.writableDatabase.insert(table, null, values)
    }

    /** Updates type + image for [id]. Passing a null image clears the stored one. */
    fun update(id: Long, type: LogoType, image: ByteArray?): Int {
        val values = ContentValues().apply {
            put("logo_type", type.stored)
            if (image != null) put("logo_image", image) else putNull("logo_image")
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        return helper.writableDatabase.update(table, values, "id=?", arrayOf(id.toString()))
    }

    /** Deletes every logo in [ids]. */
    fun delete(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        return helper.writableDatabase.delete(table, "id IN ($placeholders)", args)
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
}
