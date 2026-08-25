package com.example.synergic_pos_offline.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.synergic_pos_offline.database.AppSettingsDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cloud backup: the settings, and the rules about when a copy may be sent.
 *
 * A backup that only ever lands in Downloads is sitting on the one device the backup
 * exists to survive - the tablet that gets dropped, stolen or reset is the tablet
 * holding the only copy of the shop. This puts a second copy somewhere else.
 *
 * IT IS A COPY, NEVER A MOVE. [AutoBackup] writes the local file first and keeps its
 * own rolling window whatever happens here. A shop with no signal must not stop having
 * backups because a switch is on, and an upload that fails must not take the local
 * copy down with it - so the upload is the last thing that happens and the only thing
 * that can fail.
 *
 * OFF BY DEFAULT, and deliberately. This sends the shop's whole database - its prices,
 * its customers, every bill it has ever rung - to a third party. That is a decision an
 * owner makes on purpose, not one they inherit from whoever set the till up.
 *
 * WHAT IS BUILT SO FAR: everything except the transfer itself. The settings persist,
 * the state is reported, and [canUploadNow] answers whether a copy should be sent -
 * so the About App screen is complete and honest about where it stands. [upload] is
 * the single seam the three services plug into; see the note on it for what each one
 * needs before it can work.
 */
object CloudBackup {

    /** Whether a copy of each backup is sent to the cloud. Stored as "1" / "0". */
    private const val KEY_ENABLED = "Cloud Backup"

    /** Which service, as one of [Provider.code]. */
    private const val KEY_PROVIDER = "Cloud Backup Provider"

    /** The folder inside that account, e.g. "SynergicPOS/Backups". */
    private const val KEY_PATH = "Cloud Backup Path"

    /** The signed-in account, for showing on the settings screen. */
    private const val KEY_ACCOUNT = "Cloud Backup Account"

    /** Epoch millis of the last successful upload. */
    private const val KEY_LAST_OK = "Cloud Backup Last Ok"

    /** What went wrong last time, or blank. */
    private const val KEY_LAST_ERROR = "Cloud Backup Last Error"

    /**
     * The services offered.
     *
     * Each needs its own sign-in and its own upload call, which is why this is a
     * closed list rather than a free-typed name: a service nobody has written the
     * transfer for would be a setting that silently never works.
     */
    enum class Provider(val code: String, val label: String) {
        GOOGLE_DRIVE("G", "Google Drive"),
        ONEDRIVE("O", "OneDrive"),
        DROPBOX("D", "Dropbox");

        companion object {
            fun fromStored(v: String?): Provider? =
                entries.firstOrNull { it.code == v || it.label.equals(v?.trim(), ignoreCase = true) }
        }
    }

    data class Settings(
        val enabled: Boolean = false,
        val provider: Provider = Provider.GOOGLE_DRIVE,
        /** Folder inside the account. Blank means the setting is not finished. */
        val path: String = "",
        /** The connected account, or blank when nobody has signed in. */
        val account: String = "",
        val lastOk: Long = 0L,
        val lastError: String = ""
    ) {
        /**
         * Whether this is set up enough to try. On + a folder + an account: the three
         * things an upload needs, and each one is separately missable, which is why
         * the screen shows them as three rows rather than one switch.
         *
         */
        val ready: Boolean get() = enabled && path.isNotBlank() && account.isNotBlank()
    }

    fun settings(context: Context): Settings {
        val dao = AppSettingsDao(context)
        return Settings(
            enabled = dao.get(KEY_ENABLED) == "1",
            provider = Provider.fromStored(dao.get(KEY_PROVIDER)) ?: Provider.GOOGLE_DRIVE,
            path = dao.get(KEY_PATH).orEmpty(),
            account = dao.get(KEY_ACCOUNT).orEmpty(),
            lastOk = dao.get(KEY_LAST_OK)?.toLongOrNull() ?: 0L,
            lastError = dao.get(KEY_LAST_ERROR).orEmpty()
        )
    }

    fun save(context: Context, enabled: Boolean, provider: Provider, path: String) {
        val dao = AppSettingsDao(context)
        dao.put(KEY_ENABLED, if (enabled) "1" else "0")
        dao.put(KEY_PROVIDER, provider.code)
        // Stored without surrounding slashes so the folder reads the same however it
        // was typed - "/Backups/" and "Backups" are one folder, not two.
        dao.put(KEY_PATH, path.trim().trim('/'))
    }

    /** Records the account a sign-in produced, or clears it on disconnect. */
    fun saveAccount(context: Context, account: String) {
        AppSettingsDao(context).put(KEY_ACCOUNT, account.trim())
    }

    /**
     * Whether a copy should go up right now.
     *
     * Network is checked HERE rather than left to the upload to discover, because the
     * answer decides whether anything is attempted at all: a till in a shop with no
     * broadband would otherwise spend every backup waiting on a connection that is
     * never coming, and record a failure each time for the owner to worry about.
     *
     * An unmetered connection is not required. A database backup is small and a shop
     * on a mobile dongle still wants its backup off the premises.
     */
    fun canUploadNow(context: Context): Boolean =
        settings(context).ready && isOnline(context)

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Remembers how the last attempt went, for the line on the settings screen. */
    fun recordResult(context: Context, ok: Boolean, error: String = "") {
        val dao = AppSettingsDao(context)
        if (ok) {
            dao.put(KEY_LAST_OK, System.currentTimeMillis().toString())
            dao.put(KEY_LAST_ERROR, "")
        } else {
            dao.put(KEY_LAST_ERROR, error)
        }
    }

    /**
     * The last attempt, in a line an operator can act on.
     *
     * A cloud upload is the one part of the backup screen that cannot be checked by
     * looking - the file is not on the tablet to go and find - so what happened has to
     * be written down, including that nothing has happened yet.
     */
    fun lastSyncDescription(context: Context): String {
        val s = settings(context)
        val when_ = if (s.lastOk > 0L) {
            SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(s.lastOk))
        } else null
        return when {
            s.lastError.isNotBlank() && when_ != null ->
                "Last sent $when_. The attempt after that failed: ${s.lastError}"
            s.lastError.isNotBlank() -> "Nothing has been sent yet. Last attempt failed: ${s.lastError}"
            when_ != null -> "Last sent $when_."
            else -> "No backup has been sent yet."
        }
    }

    /**
     * Sends [file] to the configured service. NOT YET IMPLEMENTED - see below.
     *
     * This is the single seam the three services plug into, and it is deliberately
     * the only thing left unbuilt: everything around it - the settings, the readiness
     * rules, the network check, the reporting - is finished and can be seen working.
     *
     * WHAT EACH SERVICE STILL NEEDS. All three are OAuth 2: the shop signs in once,
     * the app keeps a refresh token, and every upload is an HTTPS call carrying an
     * access token. None of that can be faked convincingly, and none of it is small:
     *
     *   Google Drive  play-services-auth + the Drive REST API, an OAuth client id
     *                 registered against this app's signing certificate, and the
     *                 app's Play Console entry - Drive scopes are restricted and
     *                 Google verifies the app before real accounts may grant them.
     *   OneDrive      MSAL (com.microsoft.identity.client) and an app registration
     *                 in Azure AD, then Microsoft Graph for the upload itself.
     *   Dropbox       the Dropbox Java SDK and an app key from their console. The
     *                 lightest of the three by some distance.
     *
     * Each brings a sign-in dependency, an internet permission this app has never
     * needed, and a developer account that only the owner of this product can create.
     * Which is why this stops here: the screen is real and the settings persist, and
     * the transfer is one function away once those accounts exist.
     */
    fun upload(context: Context, name: String, body: String): Result<Unit> =
        Result.failure(UnsupportedOperationException(NOT_WIRED))

    const val NOT_WIRED =
        "Cloud upload is not connected yet - backups are still saved on this device."
}
