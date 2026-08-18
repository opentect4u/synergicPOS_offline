package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The shift master - the named stretches of the day a shop runs, and the clock times
 * that bound them.
 *
 * A shift is a shape of the working day rather than an event: "Morning, 06:00 to
 * 14:00" describes every morning, not one of them. So the times are stored as "HH:mm"
 * clock faces and never as timestamps, and a shift whose end reads earlier than its
 * start is simply one that crosses midnight - which the master accepts without
 * resolving, because nothing downstream reads the times to decide anything. What a
 * bill is counted under is the *operator* who rang it up and the shift they are on;
 * the times are there so a person can tell the shifts apart.
 */
class ShiftDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_SHIFTS

    /** One shift. [code] is derived from the row id, as the other masters' are. */
    data class Shift(
        val id: Long,
        val name: String,
        val fromTime: String,
        val toTime: String
    ) {
        val code: String get() = formatCode(id)

        /** "Morning  ·  06:00 - 14:00" - the shift as a person picks it from a list. */
        val label: String
            get() = if (fromTime.isBlank() && toTime.isBlank()) name
            else "$name  ·  $fromTime - $toTime"
    }

    /** Every shift for this store, oldest first so the codes read in order. */
    fun getAll(): List<Shift> {
        val list = mutableListOf<Shift>()
        val store = currentStoreId()
        helper.readableDatabase.query(
            table, arrayOf("id", "shift_name", "from_time", "to_time"),
            if (store != null) "store_id = ?" else null,
            store?.let { arrayOf(it.toString()) },
            null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Shift(
                        id = c.getLong(0),
                        name = c.getString(1).orEmpty(),
                        fromTime = c.getString(2).orEmpty(),
                        toTime = c.getString(3).orEmpty()
                    )
                )
            }
        }
        return list
    }

    /** One shift by id, or null - for naming the shift a user is on. */
    fun byId(id: Long?): Shift? {
        if (id == null) return null
        return getAll().firstOrNull { it.id == id }
    }

    /** Inserts a shift and returns its new row id, or -1. */
    fun insert(name: String, fromTime: String, toTime: String): Long =
        helper.writableDatabase.insert(table, null, ContentValues().apply {
            put("store_id", currentStoreId())
            put("shift_name", name)
            put("from_time", fromTime)
            put("to_time", toTime)
            put("created_by", currentUser())
        })

    /** Updates the name and times of [id]. */
    fun update(id: Long, name: String, fromTime: String, toTime: String): Int =
        helper.writableDatabase.update(table, ContentValues().apply {
            put("shift_name", name)
            put("from_time", fromTime)
            put("to_time", toTime)
            put("modified_at", now())
            put("modified_by", currentUser())
        }, "id=?", arrayOf(id.toString()))

    /**
     * Removes the shifts in [ids], and takes them off the users who were on them.
     *
     * The second half is the part worth doing here rather than leaving to a foreign
     * key: a user pointing at a shift that no longer exists would show a blank shift
     * on a screen that calls it mandatory, and there would be nothing on that screen
     * to explain why. Clearing it makes the next edit of that user ask again.
     */
    fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        val list = ids.joinToString(",")
        helper.writableDatabase.apply {
            execSQL("UPDATE ${DatabaseHelper.Tables.MD_USERS} SET shift_id = NULL WHERE shift_id IN ($list)")
            delete(table, "id IN ($list)", null)
        }
    }

    /** The id the next inserted shift will take, for previewing its code. */
    fun nextId(): Long = (lastId() ?: 0L) + 1

    /** The highest id in use, or null on an empty master. */
    fun lastId(): Long? =
        helper.readableDatabase.rawQuery("SELECT MAX(id) FROM $table", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    private fun currentStoreId(): Long? = SessionManager.currentUser?.storeId?.toLong()

    private fun currentUser(): String? = SessionManager.auditUser

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        /** "SH001" - the same shape the other masters' derived codes take. */
        fun formatCode(id: Long): String = "SH%03d".format(id)

        /**
         * Whether this till runs shifts at all - the App Settings switch, and nothing
         * else.
         *
         * Asked from the login cache the way every other App Settings flag is, so a
         * menu can be built without a database read. Every screen the shift touches
         * asks this one question, so there is one answer.
         */
        fun isEnabled(context: Context): Boolean = runCatching {
            com.example.synergic_pos_offline.utils.SettingsCache
                .value(context, "A", AppSettingsDao.KEY_SHIFT)
                ?.let { it == "1" || it.equals("true", true) }
                ?: AppSettingsDao(context).load().shift
        }.getOrDefault(false)
    }
}
