package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.models.User

object SessionManager {
    var currentUser: User? = null

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

    fun logout() {
        currentUser = null
    }
}