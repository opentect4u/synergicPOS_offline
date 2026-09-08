package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.models.User
import com.example.synergic_pos_offline.models.UserRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who a printed document credits.
 *
 * The bill used to print the login id - "IS78" - which names an account rather than a
 * person: neither the customer holding the slip nor the shop reading its own copy can
 * turn it back into who served them. Every receipt now asks [SessionManager] for the
 * name, so the fallback chain behind it is worth pinning: a shop with a name filled in
 * must never see the id again, and one with the name left blank must still credit
 * somebody.
 */
class SessionCashierNameTest {

    @After
    fun signOut() {
        SessionManager.currentUser = null
    }

    private fun signIn(userId: String, userName: String) {
        SessionManager.currentUser = User(
            userId = userId, password = "", role = UserRole.GENERAL_USER, userName = userName
        )
    }

    /** The person, not the account - the whole point of the change. */
    @Test
    fun theNameIsWhatPrints() {
        signIn(userId = "Is78", userName = "Ishani")
        assertEquals("ISHANI", SessionManager.cashierName)
    }

    /** An account with no name still credits somebody, by its login id. */
    @Test
    fun theLoginIdStandsInWhenNoNameIsFilledIn() {
        signIn(userId = "Is78", userName = "")
        assertEquals("IS78", SessionManager.cashierName)
    }

    /** A name of nothing but spaces is not a name. */
    @Test
    fun aBlankNameIsTreatedAsNoName() {
        signIn(userId = "Is78", userName = "   ")
        assertEquals("IS78", SessionManager.cashierName)
    }

    /**
     * Nobody signed in prints the placeholder rather than an empty space, so a slip
     * that somehow escapes a session still shows that the field was not filled.
     */
    @Test
    fun nobodySignedInPrintsThePlaceholder() {
        SessionManager.currentUser = null
        assertEquals("---", SessionManager.cashierName)
    }
}
