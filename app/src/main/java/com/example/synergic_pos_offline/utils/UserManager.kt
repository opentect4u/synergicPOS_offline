package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.models.User
import com.example.synergic_pos_offline.models.UserRole

object UserManager {
    // Fixed Admin Credentials
    private const val ADMIN_ID = "admin"
    private const val ADMIN_PASSWORD = "password123"

    // Mock Database
    private val users = mutableListOf<User>(
        User(ADMIN_ID, ADMIN_PASSWORD, UserRole.ADMIN)
    )

    fun authenticate(userId: String, password: String): User? {
        val user = users.find { it.userId == userId && it.password == password }
        if (user != null && user.isBlocked) return null // Or throw exception for "Blocked"
        return user
    }

    fun createUser(user: User): Boolean {
        if (users.any { it.userId == user.userId }) return false
        users.add(user)
        return true
    }

    fun blockUser(userId: String, block: Boolean) {
        users.find { it.userId == userId }?.isBlocked = block
    }

    fun resetPassword(userId: String, newPassword: String) {
        users.find { it.userId == userId }?.password = newPassword
    }

    fun getAllUsers(): List<User> = users.filter { it.role != UserRole.ADMIN }
}