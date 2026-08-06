package com.example.synergic_pos_offline.models

data class User(
    val userId: String,
    var password: String,
    val role: UserRole,
    var isBlocked: Boolean = false,
    var assignedFeatures: List<String> = emptyList(),
    /** Store this user belongs to (md_users.store_id); 0 when unknown. */
    val storeId: Int = 0,
    /** The user's serial no. (md_users.id) — recorded in created_by / modified_by. */
    val serialNo: Long = 0
)