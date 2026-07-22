package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import at.favre.lib.crypto.bcrypt.BCrypt
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data-access layer for the [DatabaseHelper.Tables.MD_USERS] master table.
 *
 * The login `user_id` is entered by the operator; the human-facing "Operator ID"
 * is derived from the row id via [formatOperatorId] so it stays stable.
 */
class UserDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_USERS

    /** Roles allowed by the md_users CHECK constraint, with dropdown labels. */
    enum class Role(val stored: String, val label: String) {
        SUPER("S", "Super Admin"),
        ADMIN("A", "Admin"),
        GENERAL("G", "General User");

        companion object {
            fun fromStored(v: String?): Role = values().firstOrNull { it.stored == v } ?: GENERAL
            fun fromLabel(v: String?): Role = values().firstOrNull { it.label == v } ?: GENERAL
        }
    }

    /** A single user row (password intentionally excluded from listing). */
    data class AppUser(
        val id: Long,
        val userId: String,
        val userName: String,
        val phone: String,
        val role: Role,
        val blocked: Boolean
    ) {
        val operatorId: String get() = formatOperatorId(id)
    }

    /** All users, oldest first. */
    fun getAll(): List<AppUser> {
        val list = mutableListOf<AppUser>()
        helper.readableDatabase.query(
            table, arrayOf("id", "user_id", "user_name", "phone_no", "role", "is_blocked"),
            null, null, null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    AppUser(
                        id = c.getLong(0),
                        userId = c.getString(1).orEmpty(),
                        userName = c.getString(2).orEmpty(),
                        phone = c.getString(3).orEmpty(),
                        role = Role.fromStored(c.getString(4)),
                        blocked = c.getInt(5) == 1
                    )
                )
            }
        }
        return list
    }

    /** True if [userId] is already taken (optionally ignoring row [excludeId]). */
    fun userIdExists(userId: String, excludeId: Long? = null): Boolean {
        val sel = if (excludeId != null) "user_id=? AND id<>?" else "user_id=?"
        val args = if (excludeId != null) arrayOf(userId, excludeId.toString()) else arrayOf(userId)
        helper.readableDatabase.query(table, arrayOf("id"), sel, args, null, null, null, "1").use { c ->
            return c.moveToFirst()
        }
    }

    /** Inserts a new user and returns its new row id (or -1 on failure). */
    fun insert(
        userId: String, password: String, userName: String,
        phone: String, role: Role, blocked: Boolean
    ): Long {
        val values = ContentValues().apply {
            put("store_id", currentStoreId())
            put("user_id", userId)
            put("password", hash(password))
            put("user_name", userName)
            put("phone_no", phone)
            put("role", role.stored)
            put("is_blocked", if (blocked) 1 else 0)
            put("created_by", currentUser())
        }
        return helper.writableDatabase.insert(table, null, values)
    }

    /** Updates profile fields (not the password) for [id]. */
    fun update(
        id: Long, userName: String, phone: String, role: Role, blocked: Boolean
    ): Int {
        val values = ContentValues().apply {
            put("user_name", userName)
            put("phone_no", phone)
            put("role", role.stored)
            put("is_blocked", if (blocked) 1 else 0)
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        return helper.writableDatabase.update(table, values, "id=?", arrayOf(id.toString()))
    }

    /** Blocks or unblocks the user [id]. */
    fun setBlocked(id: Long, blocked: Boolean): Int {
        val values = ContentValues().apply {
            put("is_blocked", if (blocked) 1 else 0)
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        return helper.writableDatabase.update(table, values, "id=?", arrayOf(id.toString()))
    }

    /** Row id of the user with the given login [userId], or null if none. */
    fun idOf(userId: String): Long? {
        helper.readableDatabase.query(
            table, arrayOf("id"), "user_id=?", arrayOf(userId), null, null, null, "1"
        ).use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    /** Verifies [password] against the stored bcrypt hash for login [userId]. */
    fun verifyPassword(userId: String, password: String): Boolean {
        helper.readableDatabase.query(
            table, arrayOf("password"), "user_id=?", arrayOf(userId), null, null, null, "1"
        ).use { c ->
            if (!c.moveToFirst()) return false
            val hash = c.getString(0) ?: return false
            return try {
                BCrypt.verifyer().verify(password.toCharArray(), hash).verified
            } catch (_: Exception) { false }
        }
    }

    /** Sets a new password for the user [id]. */
    fun resetPassword(id: Long, newPassword: String): Int {
        val values = ContentValues().apply {
            put("password", hash(newPassword))
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        return helper.writableDatabase.update(table, values, "id=?", arrayOf(id.toString()))
    }

    /** Bcrypt-hashes a password so it verifies against the login screen's check. */
    private fun hash(password: String): String =
        BCrypt.withDefaults().hashToString(10, password.toCharArray())

    /** Deletes every user in [ids]. */
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
        val db = helper.readableDatabase
        // Prefer a verified store (verify_flag = 1) so the new user can actually
        // log in — the login screen only accepts users of a verified store.
        db.rawQuery(
            "SELECT store_id FROM ${DatabaseHelper.Tables.MD_REGISTRATION} " +
                "WHERE verify_flag = 1 ORDER BY store_id ASC LIMIT 1", null
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        // Fallback: any store, if none is marked verified yet.
        db.query(
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
        /** Renders a stable operator id from a row id, e.g. 7 -> "OPR007". */
        fun formatOperatorId(id: Long): String = "OPR" + id.toString().padStart(3, '0')
    }
}
