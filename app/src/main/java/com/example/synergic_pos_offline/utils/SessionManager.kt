package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.models.User

object SessionManager {
    var currentUser: User? = null

    /**
     * The value written to created_by / modified_by across every table: the signed-in
     * user's serial no. (md_users.id) as a string, or null when unknown.
     */
    val auditUser: String? get() = currentUser?.serialNo?.takeIf { it > 0 }?.toString()

    fun isAdmin(): Boolean = currentUser?.role?.name == "ADMIN"
    
    fun hasPermission(feature: String): Boolean {
        if (isAdmin()) return true
        return currentUser?.assignedFeatures?.contains(feature) == true
    }

    fun logout() {
        currentUser = null
    }
}