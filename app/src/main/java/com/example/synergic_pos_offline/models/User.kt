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
    val serialNo: Long = 0,
    /**
     * The person's name (md_users.user_name) — "Ishani", where [userId] is "Is78".
     *
     * The login id identifies an ACCOUNT and the name identifies a PERSON, and a
     * document a customer reads across a counter wants the person. Carried on the
     * session so the receipt screens do not each have to go back to md_users for a
     * fact the login already had in its hand.
     *
     * Blank where the account has no name filled in, which is why nothing reads this
     * directly — see [SessionManager.cashierName], which falls back to the login id.
     */
    val userName: String = ""
)