package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.models.User

object SessionManager {
    var currentUser: User? = null

    fun isAdmin(): Boolean = currentUser?.role?.name == "ADMIN"
    
    fun hasPermission(feature: String): Boolean {
        if (isAdmin()) return true
        return currentUser?.assignedFeatures?.contains(feature) == true
    }

    fun logout() {
        currentUser = null
    }
}