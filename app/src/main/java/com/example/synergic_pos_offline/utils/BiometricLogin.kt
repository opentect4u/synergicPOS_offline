package com.example.synergic_pos_offline.utils

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.database.AppSettingsDao

/**
 * Signing in with the fingerprint reader instead of typing a password.
 *
 * ## What it actually proves, and what it does not
 *
 * A fingerprint identifies **the device**, not the operator. Android will tell this
 * app that *somebody enrolled on this tablet* put a finger on the reader; it will not
 * say which of them, and it has no idea this app has users at all. So a fingerprint on
 * its own cannot answer "who is signing in" - it can only confirm that whoever is
 * holding the tablet is one of the people the tablet already trusts.
 *
 * The identity therefore has to come from somewhere else, and it comes from the last
 * password login: a user who signs in with their password while the setting is on is
 * remembered here, and the fingerprint afterwards signs that user back in. The login
 * screen says whose name it will use, so nobody presses it expecting to become
 * somebody else.
 *
 * ## The trade a shop is making
 *
 * On a till shared by several people, **any** fingerprint enrolled on the tablet opens
 * the remembered operator's session - the owner's finger will sign in as the cashier
 * if the cashier logged in last. That is not a flaw in this code; it is what device
 * biometrics are. It is why the setting is off until somebody turns it on, why the
 * button on the login screen is an offer rather than the only way in, and why the
 * password field never goes away.
 *
 * ## What it still checks
 *
 * The remembered user is looked up again at the moment the finger lands, not trusted
 * from when it was stored. An operator removed from the till, or blocked since, does
 * not get in on a fingerprint that was valid last week - the caller re-reads them from
 * `md_users` exactly as a typed password would.
 *
 * ## Turning it off
 *
 * Switching the setting off [forget]s the remembered user, so it revokes rather than
 * merely hides: turning it on again offers nobody until somebody signs in with a
 * password first.
 */
object BiometricLogin {

    private const val PREF = "biometric_login"
    private const val KEY_USER = "remembered_user"

    /** The strength asked for - see [canUseDevice] for why it is the weaker one. */
    private const val STRENGTH = BiometricManager.Authenticators.BIOMETRIC_WEAK

    /** Whether the shop has switched this on in App Settings. */
    fun enabledInSettings(context: Context): Boolean = runCatching {
        // From the login cache where there is one, so the login screen does not open a
        // settings read on every keystroke; the database is the fallback for the first
        // run, before anything has been cached.
        SettingsCache.value(context, "A", AppSettingsDao.KEY_BIOMETRIC_LOGIN)
            ?.let { it == "1" || it.equals("true", true) }
            ?: AppSettingsDao(context).load().biometricLogin
    }.getOrDefault(false)

    /**
     * Whether this tablet can actually take a fingerprint right now.
     *
     * [BiometricManager.Authenticators.BIOMETRIC_WEAK] rather than the strong class:
     * nothing here is being decrypted, the fingerprint only stands in for a password
     * this app checks itself, and the strong class rules out the face and iris readers
     * a good many tablets ship with. A shop that wanted a cryptographic guarantee
     * would want a different feature than this one.
     */
    fun canUseDevice(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(STRENGTH) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Why the reader cannot be offered, in the words a shopkeeper needs to fix it -
     * or null when it can.
     *
     * Said on the settings screen rather than discovered at the login screen, which is
     * the wrong moment to learn that nothing has been enrolled.
     */
    fun unavailableReason(context: Context): String? =
        when (BiometricManager.from(context).canAuthenticate(STRENGTH)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "No fingerprint is set up on this device yet. Add one in the device's own settings."
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                "This device has no fingerprint reader."
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                "The fingerprint reader is not available at the moment."
            else -> "The fingerprint reader cannot be used on this device."
        }

    // ---- Who the fingerprint signs in as ---------------------------------------

    /** Remembers [userId] as the operator a fingerprint signs in. */
    fun remember(context: Context, userId: String) {
        prefs(context).edit().putString(KEY_USER, userId).apply()
    }

    /** Forgets whoever was remembered - on logout of the setting, not of the session. */
    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_USER).apply()
    }

    /**
     * The operator the login screen should offer to sign in, or null for no offer.
     *
     * Every condition has to hold: the shop has switched it on, the tablet has a
     * usable reader, and somebody has signed in with a password since. Any one of them
     * missing and the login screen simply shows the password form, which is what it
     * has always shown.
     */
    fun offeredUser(context: Context): String? {
        if (!enabledInSettings(context)) return null
        if (!canUseDevice(context)) return null
        return prefs(context).getString(KEY_USER, null)?.takeIf { it.isNotBlank() }
    }

    // ---- Asking ------------------------------------------------------------------

    /**
     * Puts the system's fingerprint sheet up, and calls back on the main thread.
     *
     * [onFailed] is for a reader that said no or an operator who backed out; the
     * caller's job is then to leave them on the password form rather than to explain
     * anything, since the sheet has already said its piece. A message is passed only
     * where there is something the operator did not already see.
     */
    fun prompt(
        fragment: Fragment,
        userId: String,
        onSucceeded: () -> Unit,
        onFailed: (String?) -> Unit
    ) {
        val context = fragment.requireContext()
        val prompt = BiometricPrompt(
            fragment,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSucceeded()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // Backing out is a choice, not a fault, and saying so over the
                    // password form the operator just chose would be noise.
                    val silent = code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        code == BiometricPrompt.ERROR_USER_CANCELED ||
                        code == BiometricPrompt.ERROR_CANCELED
                    onFailed(if (silent) null else message.toString())
                }

                // A finger the reader did not recognise. The sheet says so itself and
                // stays up for another try, so there is nothing to add and nothing to
                // close - onAuthenticationError is what ends it.
                override fun onAuthenticationFailed() = Unit
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Sign in to Synergic POS")
                // Named, so nobody presses the reader expecting to become somebody
                // else - the fingerprint says "this tablet's owner", and this line is
                // the only thing saying whose session it opens.
                .setSubtitle("Continue as $userId")
                .setNegativeButtonText("Use password")
                .setAllowedAuthenticators(STRENGTH)
                .setConfirmationRequired(false)
                .build()
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
