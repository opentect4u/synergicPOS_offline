package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.models.User
import com.example.synergic_pos_offline.models.UserRole

object SessionManager {
    var currentUser: User? = null

    private const val PREFS_NAME = "session_prefs"
    private const val KEY_USER_ID = "signed_in_user_id"

    /**
     * The value written to created_by / modified_by across every table: the signed-in
     * user's serial no. (md_users.id) as a string, or null when unknown.
     */
    val auditUser: String? get() = currentUser?.serialNo?.takeIf { it > 0 }?.toString()

    /**
     * Who to print as the cashier: the signed-in person's NAME, not their login id.
     *
     * "ISHANI", not "IS78". A receipt is read across a counter by a customer and kept
     * by the shop as its own record of who served them, and a login id answers neither
     * question - it names an account, and only somebody with the user master open can
     * turn it back into a person.
     *
     * Falls back to the login id for an account with no name filled in, and to "---"
     * with nobody signed in. Upper case to match the rest of the slip, where the
     * customer name and the payment mode are set the same way.
     *
     * Every printed document that credits an operator reads this, so the bill, the
     * return slip and the advance-payment receipt cannot drift apart on it. A bill
     * being REPRINTED resolves its own operator from the row instead - see
     * BillReceiptRenderer.cashierName - because the session is whoever is standing
     * there now, not whoever rang the sale up.
     */
    val cashierName: String
        get() = currentUser?.let { u ->
            u.userName.takeIf { it.isNotBlank() } ?: u.userId.takeIf { it.isNotBlank() }
        }?.uppercase() ?: "---"

    fun isAdmin(): Boolean = currentUser?.role?.name == "ADMIN"
    
    fun hasPermission(feature: String): Boolean {
        if (isAdmin()) return true
        return currentUser?.assignedFeatures?.contains(feature) == true
    }

    /**
     * Remembers who is signed in, on disk rather than only in this in-memory object.
     *
     * A back press that empties the activity's task can let Android kill this process
     * to reclaim memory; the next launch starts a fresh process with [currentUser]
     * back to null. Without this, that ordinary bit of housekeeping looked to the
     * operator like being signed out.
     */
    fun persist(context: Context) {
        val user = currentUser ?: return
        prefs(context).edit().putString(KEY_USER_ID, user.userId).apply()
    }

    /**
     * Re-signs in whoever [persist] remembered, if nobody is signed in yet in this
     * process - i.e. this is a fresh process rather than merely a fresh activity.
     * Re-reads md_users instead of trusting the remembered copy, so an account
     * blocked or removed since is not let back in on a stale session.
     */
    fun restore(context: Context): User? {
        currentUser?.let { return it }
        val userId = prefs(context).getString(KEY_USER_ID, null) ?: return null
        val user = loadUser(context, userId) ?: return null
        if (user.isBlocked) return null
        currentUser = user
        return user
    }

    fun logout(context: Context) {
        currentUser = null
        // The sale screens' kept catalogues belong to this login - see CatalogueSignature.
        CatalogueSignature.newSession()
        prefs(context).edit().remove(KEY_USER_ID).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Same lookup [com.example.synergic_pos_offline.fragments.LoginFragment.loadUser]
     *  makes for a fingerprint sign-in: a login id, the verified-store join, no password. */
    private fun loadUser(context: Context, userId: String): User? {
        val db = DatabaseHelper.getInstance(context.applicationContext).readableDatabase
        val sql = """
            SELECT u.role, u.is_blocked, u.store_id, u.id, u.user_name
            FROM ${DatabaseHelper.Tables.MD_USERS} u
            JOIN ${DatabaseHelper.Tables.MD_REGISTRATION} r ON r.store_id = u.store_id
            WHERE u.user_id = ? AND r.verify_flag = 1
            LIMIT 1
        """.trimIndent()
        db.rawQuery(sql, arrayOf(userId)).use { c ->
            if (!c.moveToFirst()) return null
            return User(
                userId = userId,
                password = "",
                role = if (c.getString(c.getColumnIndexOrThrow("role")) == "G")
                    UserRole.GENERAL_USER else UserRole.ADMIN,
                isBlocked = c.getInt(c.getColumnIndexOrThrow("is_blocked")) == 1,
                storeId = c.getInt(c.getColumnIndexOrThrow("store_id")),
                serialNo = c.getLong(c.getColumnIndexOrThrow("id")),
                userName = c.getString(c.getColumnIndexOrThrow("user_name")).orEmpty()
            )
        }
    }
}