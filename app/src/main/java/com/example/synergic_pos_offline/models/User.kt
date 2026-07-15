package com.example.synergic_pos_offline.models

data class User(
    val userId: String,
    var password: String,
    val role: UserRole,
    var isBlocked: Boolean = false,
    var assignedFeatures: List<String> = emptyList()
)