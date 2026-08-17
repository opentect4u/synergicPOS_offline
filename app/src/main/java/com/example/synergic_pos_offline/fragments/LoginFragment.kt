package com.example.synergic_pos_offline.fragments

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.models.User
import com.example.synergic_pos_offline.models.UserRole
import com.example.synergic_pos_offline.utils.ApiClient
import com.example.synergic_pos_offline.utils.BackupFiles
import com.example.synergic_pos_offline.utils.BusyDialog
import com.example.synergic_pos_offline.utils.DatabaseBackup
import com.example.synergic_pos_offline.utils.BiometricLogin
import com.example.synergic_pos_offline.utils.DeviceIdentity
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.NetworkBadge
import com.example.synergic_pos_offline.utils.NetworkMonitor
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.SettingsCache
import com.example.synergic_pos_offline.utils.ThemeManager
import at.favre.lib.crypto.bcrypt.BCrypt
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.util.concurrent.Executors

class LoginFragment : Fragment() {

    private lateinit var tilUsername: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var tvRegister: View
    private lateinit var tvRestoreData: View
    private lateinit var tvPending: View
    private lateinit var swipeRefresh: SwipeRefreshLayout

    /**
     * Picks the backup to restore from, when it is not one this app can still see
     * for itself (see [BackupFiles]).
     *
     * Registered here rather than at the tap: a launcher has to exist before the
     * fragment is started, and registering one from a click listener throws.
     */
    private val pickBackup: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { confirmRestore(it) }
        }

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private lateinit var networkMonitor: NetworkMonitor

    /** Guards against the connectivity callback stacking up retries - see
     *  [sendPendingDeviceMove]. */
    private val sendingDeviceMove = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_login, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tilUsername = view.findViewById(R.id.tilUsername)
        tilPassword = view.findViewById(R.id.tilPassword)
        etUsername = view.findViewById(R.id.etUsername)
        etPassword = view.findViewById(R.id.etPassword)
        btnLogin = view.findViewById(R.id.btnLogin)
        tvRegister = view.findViewById(R.id.tvRegister)
        tvRestoreData = view.findViewById(R.id.tvRestoreData)
        tvPending = view.findViewById(R.id.tvPending)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)

        // Pull down to re-check this device's verification status with the server.
        swipeRefresh.setColorSchemeColors(ThemeManager.getThemeColor(requireContext()))
        swipeRefresh.setOnRefreshListener { checkDeviceVerification() }

        setupTextWatchers()

        btnLogin.setOnClickListener {
            if (validateInputs()) {
                performLogin()
            }
        }

        // The fingerprint shortcut, where this till offers one. Beside the password
        // form and never instead of it - see [showBiometricOffer].
        showBiometricOffer(view)

        tvRegister.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, RegistrationFragment())
                .addToBackStack(null)
                .commit()
        }

        tvRestoreData.setOnClickListener { chooseBackup() }

        ThemeManager.applyTheme(view)

        networkMonitor = NetworkMonitor(requireContext())
        networkMonitor.register { online ->
            this.view?.let { NetworkBadge.bind(it, online) }
            if (online) sendPendingDeviceMove()
        }

        checkDeviceVerification()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        networkMonitor.unregister()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    /**
     * Asks the backend whether this device is registered/verified and reveals the
     * matching hint under the form:
     *  - no record (empty array / no flag) -> "Register here" link
     *  - verify_flag == 0 -> non-clickable "Pending verification"
     *  - verify_flag == 1 -> nothing (device is verified, just log in)
     */
    private fun checkDeviceVerification() {
        val appContext = requireContext().applicationContext
        val deviceId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()

        val payload = JSONObject().put("device_id", deviceId)

        ioExecutor.execute {
            // A device move that could not be sent when it happened goes out here,
            // before the check that would otherwise ask about the wrong tablet.
            // Costs nothing when there is nothing queued.
            runCatching { DeviceIdentity.publishPending(appContext) }

            val result = ApiClient.postJson(ApiClient.PATH_CHECK_USER, payload)
            // On success read the record; if the check fails, fall back to offering
            // registration (treat as an unknown / unregistered device).
            val record = if (result.ok) firstRecord(result.body) else null
            val flag = record?.let { verifyFlagOf(it) }

            // A verified device (flag == 1): mirror the store + user into SQLite so
            // the app has everything it needs to work offline. Guard it: a sync/DB
            // hiccup here must never crash the launch — the login screen can still open.
            if (record != null && flag == 1) {
                runCatching { saveVerifiedStore(appContext, record) }
                    .onFailure { android.util.Log.e("SynergicPOS", "saveVerifiedStore failed", it) }
            }

            view?.post {
                if (!isAdded) return@post
                applyVerifyState(flag)
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun applyVerifyState(verifyFlag: Int?) {
        when (verifyFlag) {
            null -> {
                // No record for this device -> allow registration.
                tvRegister.visibility = View.VISIBLE
                tvPending.visibility = View.GONE
            }
            0 -> {
                // Registered but awaiting admin verification.
                tvRegister.visibility = View.GONE
                tvPending.visibility = View.VISIBLE
            }
            else -> {
                // Verified (1) -> no hint, login is allowed.
                tvRegister.visibility = View.GONE
                tvPending.visibility = View.GONE
            }
        }
        // Restore Data is not part of this: it stays on screen whatever the server
        // says, so a device with no data can always be restored onto.
    }

    // ---- Restoring a backup onto a device with nothing on it -------------------

    /**
     * Offers the backups this app can still see, and the file picker for the ones it
     * cannot.
     *
     * On a replacement tablet the list is empty - Android does not let a fresh
     * installation read what the previous one wrote (see [BackupFiles]) - so this
     * goes straight to the picker, which is the route that always works. On a device
     * the app is still installed on, the operator gets their own backups by name and
     * date instead of hunting through folders.
     */
    private fun chooseBackup() {
        val found = BackupFiles.list(requireContext())
        if (found.isEmpty()) {
            browseForBackup()
            return
        }
        // Newest first, and capped: a till backing up hourly has hundreds, and the
        // one being looked for is nearly always among the most recent.
        val recent = found.take(MAX_LISTED_BACKUPS)
        val items = recent.map {
            DialogUtils.ListItem(
                title = it.name,
                subtitle = BackupFiles.timeLabel(it.takenAt),
                trailing = BackupFiles.sizeLabel(it.bytes)
            )
        } + DialogUtils.ListItem(
            title = "Choose another file…",
            subtitle = "Browse the device for a backup"
        )

        DialogUtils.showList(
            context = requireContext(),
            title = "Restore data",
            subtitle = "Pick the backup to restore from",
            items = items
        ) { index ->
            if (index == recent.size) browseForBackup() else confirmRestore(recent[index].uri)
        }
    }

    private fun browseForBackup() {
        // Anything, rather than a MIME filter: a .sql file is typed differently by
        // different file managers - text/plain here, application/octet-stream there -
        // and a filter that hides the operator's own backup is worse than one that
        // shows too much.
        runCatching { pickBackup.launch(arrayOf("*/*")) }
            .onFailure { toast("No app on this device can pick a file") }
    }

    /**
     * Says what restoring will do before it does it.
     *
     * The device id is the part worth spelling out. The backup names the tablet it
     * came from, and this one is not it; the restore adopts this device so the till
     * can be logged into here, which is the whole point of restoring onto new
     * hardware and is not something an operator would guess.
     */
    private fun confirmRestore(uri: Uri) {
        val head = BackupFiles.headOf(requireContext(), uri)
        if (head == null) {
            toast("That file could not be read")
            return
        }
        if (!BackupFiles.looksLikeBackup(head)) {
            toast("That file is not a Synergic POS backup")
            return
        }

        val taken = DatabaseBackup.schemaVersionOf(head)
        val here = DatabaseHelper.getInstance(requireContext()).readableDatabase.version
        val mismatch = if (taken != null && taken != here) {
            "\n\nThis backup was taken from database version $taken and this app is on " +
                "$here. Anything it does not recognise will be left as it is."
        } else ""

        val identity = if (DatabaseBackup.excludedIn(head).containsAll(DatabaseBackup.DEVICE_IDENTITY)) {
            "\n\nThis backup does not carry users or the store registration, so this " +
                "device keeps its own. Sign in with the login you use here."
        } else {
            "\n\nSign in afterwards with the login from the device the backup was taken " +
                "on - its users come across with the rest.\n\nThis tablet is registered " +
                "in place of the one the backup came from, so the till can be logged " +
                "into here."
        }

        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Restore data?",
            message = "This replaces everything on this device with the backup - products, " +
                "customers, bills and settings. It cannot be undone." +
                identity + mismatch,
            positiveText = "Restore",
            negativeText = "Cancel",
            destructive = true
        ) { runRestore(uri, taken) }
    }

    /** Streams the file into the database, then makes the till this device. */
    private fun runRestore(uri: Uri, schemaVersion: Int?) =
        BusyDialog.run(this, "Restoring…") {
            val context = requireContext().applicationContext
            val result = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().useLines { lines ->
                    DatabaseBackup.restore(context, lines, schemaVersion)
                }
            } ?: DatabaseBackup.Result(0, 0, 0, schemaVersion, "that file could not be opened")

            // The registration that just landed names the device the backup came
            // from. Adopting it is what lets this tablet be logged into at all, so it
            // happens before the operator is told the restore worked.
            val adoption = if (result.ok) DeviceIdentity.adopt(context) else null
            // Then the server is told, on this same background thread. It is allowed
            // to fail: the till is already correct locally, and a shop setting up a
            // replacement tablet somewhere with no signal still has a working POS.
            val published = adoption?.let { DeviceIdentity.publish(context, it) }

            BusyDialog.onMain(this) {
                if (!result.ok) {
                    DialogUtils.showSuccess(
                        context = requireContext(),
                        title = "Restore failed",
                        message = "${result.error}\n\nNothing was changed."
                    )
                    return@onMain
                }
                val skipped = if (result.skipped > 0) {
                    "\n\n${result.skipped} record(s) were for tables this version does not " +
                        "have, and were skipped."
                } else ""
                // Said plainly when the server has not been told - and said as
                // something already in hand, because it is: the move is queued and
                // goes out by itself on the next connection. A tablet being set up
                // before it has the shop's wifi is the ordinary case, not a fault.
                val moved = when {
                    adoption?.changed != true -> ""
                    published?.ok == true ->
                        "\n\nThis tablet is now the registered device for the store, here " +
                            "and on the server."
                    else ->
                        "\n\nThis tablet is now the registered device for the store. The " +
                            "office has not been told yet - ${published?.error}. Nothing " +
                            "more to do: the till works, and it will send that by itself " +
                            "once there is a connection."
                }
                DialogUtils.showSuccess(
                    context = requireContext(),
                    title = "Restored",
                    message = "${result.rows} record(s) into ${result.tables} table(s)." +
                        skipped + moved +
                        "\n\nSign in with the login from the device the backup came from.",
                    onDismiss = { afterRestore() }
                )
            }
        }

    /**
     * Sends a queued device move the moment the tablet comes online.
     *
     * The connectivity callback fires on capability changes as well as on connect,
     * which on a flaky connection is several times a minute; the flag keeps those
     * from stacking up requests, and the queued-state check means the common path
     * costs one preference read and no thread at all.
     */
    private fun sendPendingDeviceMove() {
        val context = context?.applicationContext ?: return
        if (!DeviceIdentity.hasPending(context)) return
        if (!sendingDeviceMove.compareAndSet(false, true)) return
        runCatching {
            ioExecutor.execute {
                try {
                    DeviceIdentity.publishPending(context)
                } finally {
                    sendingDeviceMove.set(false)
                }
            }
        }.onFailure { sendingDeviceMove.set(false) }   // executor already shut down
    }

    /** Clears the form and re-reads the device's state from the restored data. */
    private fun afterRestore() {
        etUsername.setText("")
        etPassword.setText("")
        tilUsername.error = null
        tilPassword.error = null
        checkDeviceVerification()
    }

    private fun toast(message: String) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    /** Returns the first store record from a { "suc": .., "msg": [ {...} ] } response. */
    private fun firstRecord(body: String): JSONObject? = try {
        val msg = JSONObject(body.trim()).optJSONArray("msg")
        if (msg == null || msg.length() == 0) null else msg.optJSONObject(0)
    } catch (_: Exception) {
        null
    }

    /** Reads verify_flag, which the backend sends as a string ("0"/"1"). */
    private fun verifyFlagOf(record: JSONObject): Int? {
        if (!record.has("verify_flag") || record.isNull("verify_flag")) return null
        return when (val v = record.get("verify_flag")) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }

    /**
     * Aligns the device to the store that just logged in — without removing any master
     * data. Rather than wiping/duplicating, it re-points the existing local rows to the
     * logged-in store_id (so a store registered under a placeholder id, or store-less
     * rows, become this store's), then updates the store record and its admin user in
     * place. Everything the device already holds is kept; only the store_id is moved.
     */
    private fun saveVerifiedStore(context: Context, record: JSONObject) {
        val db = DatabaseHelper.getInstance(context).writableDatabase

        val storeId = record.optInt("store_id")
        if (storeId == 0) return

        db.beginTransaction()
        try {
            val outletId = record.optInt("outlet_id")
            val storeName = str(record, "store_name")
            val phone = str(record, "phone_no")

            val registration = ContentValues().apply {
                put("store_id", storeId)
                put("outlet_id", outletId)
                put("store_name", storeName)
                put("address", str(record, "address"))
                put("phone_no", phone)
                put("store_gstin", str(record, "gstin"))
                put("device_id", str(record, "device_id"))
                put("registration_dt", str(record, "reg_dt"))
                put("registration_upto", str(record, "reg_upto"))
                put("verify_flag", verifyFlagOf(record) ?: 0)
                put("verified_by", str(record, "verified_by"))
                put("verified_at", str(record, "verified_at"))
            }
            // The store row is normally created by the registration flow, so on login
            // we just update it in place. But md_users has a FOREIGN KEY on store_id →
            // md_registration(store_id): if this store has no local registration row yet
            // (e.g. a fresh install of an already server-verified device), the user
            // insert below would fail the constraint. So insert the row when the update
            // matches nothing — still no duplicates, and the FK parent always exists.
            val storeUpdated = db.update(
                DatabaseHelper.Tables.MD_REGISTRATION, registration, "store_id=?",
                arrayOf(storeId.toString())
            )
            if (storeUpdated == 0) {
                db.insertWithOnConflict(
                    DatabaseHelper.Tables.MD_REGISTRATION, null, registration,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }

            val userId = str(record, "user_id")
            val user = ContentValues().apply {
                put("store_id", storeId)
                put("outlet_id", outletId)
                put("user_id", userId)
                put("password", str(record, "password"))
                put("user_name", storeName)
                put("phone_no", phone)
                put("role", "A")
                put("is_blocked", 0)
            }
            // Update the admin user in place (by user_id); insert if it's not there.
            val userUpdated = if (userId != null)
                db.update(DatabaseHelper.Tables.MD_USERS, user, "user_id=?", arrayOf(userId)) else 0
            if (userUpdated == 0) {
                db.insertWithOnConflict(DatabaseHelper.Tables.MD_USERS, null, user, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Sets every md_ table's store_id to the [storeId] the signed-in user belongs to,
     * so all local master data is owned by the logged-in store. No rows are removed.
     */
    private fun alignMasterDataToStore(storeId: Int) {
        if (storeId <= 0) return
        val db = DatabaseHelper.getInstance(requireContext()).writableDatabase
        db.beginTransaction()
        try {
            for (t in mdTablesWithStoreId(db)) {
                runCatching { db.execSQL("UPDATE $t SET store_id = ?", arrayOf<Any>(storeId)) }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Every md_ table that has a store_id column — so the re-point covers them all. */
    private fun mdTablesWithStoreId(db: SQLiteDatabase): List<String> {
        val tables = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'md\\_%' ESCAPE '\\'", null
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val hasStoreId = db.rawQuery("PRAGMA table_info($name)", null).use { info ->
                    var found = false
                    while (info.moveToNext()) {
                        if (info.getString(1) == "store_id") { found = true; break }
                    }
                    found
                }
                if (hasStoreId) tables.add(name)
            }
        }
        return tables
    }

    /** Returns a trimmed string field, or null when absent/blank/JSON null. */
    private fun str(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.optString(key).trim().ifBlank { null }
    }

    private fun storeExists(db: SQLiteDatabase, storeId: Int): Boolean {
        db.rawQuery(
            "SELECT 1 FROM ${DatabaseHelper.Tables.MD_REGISTRATION} WHERE store_id = ? LIMIT 1",
            arrayOf(storeId.toString())
        ).use { cursor -> return cursor.moveToFirst() }
    }

    private fun setupTextWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                tilUsername.error = null
                tilPassword.error = null
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        etUsername.addTextChangedListener(watcher)
        etPassword.addTextChangedListener(watcher)
    }

    private fun validateInputs(): Boolean {
        var isValid = true
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (username.isEmpty()) {
            tilUsername.error = "Username is required"
            isValid = false
        }

        if (password.isEmpty()) {
            tilPassword.error = "Password is required"
            isValid = false
        } else if (password.length < 4) {
            tilPassword.error = "Password must be at least 4 characters"
            isValid = false
        }

        return isValid
    }

    private fun performLogin() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString().trim()

        val user = authenticateLocal(username, password)

        if (user == null) {
            tilUsername.error = " "
            tilPassword.error = "Invalid username or password"
            return
        }
        if (user.isBlocked) {
            tilUsername.error = "This account is blocked"
            Toast.makeText(requireContext(), "User is blocked. Contact Admin.", Toast.LENGTH_SHORT).show()
            return
        }

        // A password login is what tells the fingerprint who to sign in next time -
        // the reader can only say "somebody this tablet trusts", never which operator,
        // so the name has to come from here. See [BiometricLogin].
        if (BiometricLogin.enabledInSettings(requireContext())) {
            BiometricLogin.remember(requireContext(), user.userId)
        }
        signIn(user)
    }

    /**
     * Everything a successful login does, once the operator is known.
     *
     * Split out because there are now two ways to become known - a password, and a
     * fingerprint over a remembered name - and the till has to do exactly the same
     * things afterwards either way. A second copy of this would be a second place for
     * the store alignment or the settings cache to be forgotten.
     */
    private fun signIn(user: User) {
        SessionManager.currentUser = user
        // Put all local master data under the store this user just logged in to — every
        // md_ table's store_id is set to the logged-in store id (not device-based).
        alignMasterDataToStore(user.storeId)
        // Cache md_app_settings to local storage, chunked by type (B / T / G / A).
        SettingsCache.storeFromDb(requireContext())
        val roleText = if (user.role == UserRole.ADMIN) "Admin" else "General User"
        Toast.makeText(requireContext(), "Welcome $roleText!", Toast.LENGTH_SHORT).show()

        // Whichever screen General Settings says to land on - the Sale screen by
        // default, since a cashier logs in to sell and that saves them a tap every
        // shift; Home for a till that is also a back office. Either way it is
        // committed as the root of the back stack, not pushed over the login form,
        // which is nowhere to go back to.
        val mode = GeneralSettingsDao(requireContext()).load().mode
        val landing: Fragment = when {
            // Calculator mode has one screen, and this is it. The Landing Screen
            // setting does not apply: there is nowhere else to land.
            mode == GeneralSettingsDao.Mode.CALCULATOR -> CalculatorFragment()

            GeneralSettingsDao(requireContext()).load().landingScreen ==
                GeneralSettingsDao.LandingScreen.HOME -> DashboardFragment()

            // Sale lands on the restaurant Orders screen in Restaurant mode, else grocery POS.
            mode == GeneralSettingsDao.Mode.RESTAURANT -> RestaurantOrdersFragment()
            else -> PosBillingFragment()
        }
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, landing)
            .commit()

        // What is out or running low, said once, now. Raised from the activity rather
        // than here because this fragment is being replaced as it asks - and it has to
        // be asked from somewhere that outlives the login, or the dialog would be
        // dismissed by the transaction that answered it.
        //
        // It reaches the operator whichever screen they land on, which the Dashboard
        // alone would not: Sale is the default landing screen, so a shop that never
        // opens the Dashboard would never have seen this.
        (activity as? com.example.synergic_pos_offline.MainActivity)
            ?.announceStockAlertsAfterLogin()
    }

    /**
     * Authenticates against the locally-stored md_users, but only for a store whose
     * md_registration.verify_flag is 1. The stored password is a bcrypt hash, so the
     * entered password is verified against it. Returns null when the user_id is unknown
     * or the password does not match.
     */
    // ---- Fingerprint --------------------------------------------------------

    /**
     * Shows the fingerprint shortcut, or leaves the form exactly as it was.
     *
     * Re-asked every time this screen appears rather than once, because all three of
     * the conditions behind it can change while the app is open: the setting is on the
     * App Settings screen, a fingerprint can be enrolled from the device's settings,
     * and the remembered operator arrives with the first password login.
     */
    private fun showBiometricOffer(view: View) {
        // Looked up leniently, and this is not belt-and-braces: this screen has a
        // portrait layout and a landscape one, and a shortcut that is only worth
        // having on one of them is not worth crashing the other over. The login
        // screen is the one screen a till cannot get past.
        val button = view.findViewById<MaterialButton>(R.id.btnBiometricLogin) ?: return
        val divider = view.findViewById<View>(R.id.tvBiometricOr)

        val offered = BiometricLogin.offeredUser(requireContext())
        if (offered == null) {
            button.visibility = View.GONE
            divider?.visibility = View.GONE
            return
        }
        button.visibility = View.VISIBLE
        divider?.visibility = View.VISIBLE
        // Named on the button as well as on the system sheet. An operator has to know
        // whose session this opens *before* deciding to press it.
        button.text = "Use fingerprint  ·  $offered"
        button.setOnClickListener { loginWithFingerprint(offered) }
    }

    /**
     * Takes a fingerprint, then signs in the operator it stands for.
     *
     * The operator is looked up again here rather than trusted from what was
     * remembered: somebody removed from the till, blocked since, or whose store has
     * been un-verified does not get in on a fingerprint. It is the same check a typed
     * password goes through, minus the password - which the reader has just stood in
     * for.
     */
    private fun loginWithFingerprint(userId: String) {
        BiometricLogin.prompt(
            fragment = this,
            userId = userId,
            onSucceeded = {
                val user = loadUser(userId)
                when {
                    user == null -> {
                        // The account has gone since it was remembered. Forget it, so
                        // the button stops offering somebody who cannot sign in.
                        BiometricLogin.forget(requireContext())
                        view?.let { showBiometricOffer(it) }
                        toast("That account no longer exists. Sign in with a password.")
                    }
                    user.isBlocked -> {
                        toast("User is blocked. Contact Admin.")
                    }
                    else -> signIn(user)
                }
            },
            onFailed = { message -> message?.let { toast(it) } }
        )
    }

    /**
     * One operator by login id, without a password.
     *
     * The same query [authenticateLocal] makes - the verified-store join included, so
     * a fingerprint cannot get into a store whose registration is not verified - with
     * the password comparison left out, because the fingerprint replaced it.
     */
    private fun loadUser(userId: String): User? {
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        val sql = """
            SELECT u.role, u.is_blocked, u.store_id, u.id
            FROM ${DatabaseHelper.Tables.MD_USERS} u
            JOIN ${DatabaseHelper.Tables.MD_REGISTRATION} r ON r.store_id = u.store_id
            WHERE u.user_id = ? AND r.verify_flag = 1
            LIMIT 1
        """.trimIndent()
        db.rawQuery(sql, arrayOf(userId)).use { c ->
            if (!c.moveToFirst()) return null
            return User(
                userId = userId,
                // Nothing here has the password and nothing downstream reads it: the
                // session carries the operator, not their credentials.
                password = "",
                role = if (c.getString(c.getColumnIndexOrThrow("role")) == "G")
                    UserRole.GENERAL_USER else UserRole.ADMIN,
                isBlocked = c.getInt(c.getColumnIndexOrThrow("is_blocked")) == 1,
                storeId = c.getInt(c.getColumnIndexOrThrow("store_id")),
                serialNo = c.getLong(c.getColumnIndexOrThrow("id"))
            )
        }
    }

    private fun authenticateLocal(userId: String, password: String): User? {
        if (userId.isEmpty() || password.isEmpty()) return null

        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        val sql = """
            SELECT u.password, u.role, u.is_blocked, u.store_id, u.id
            FROM ${DatabaseHelper.Tables.MD_USERS} u
            JOIN ${DatabaseHelper.Tables.MD_REGISTRATION} r ON r.store_id = u.store_id
            WHERE u.user_id = ? AND r.verify_flag = 1
            LIMIT 1
        """.trimIndent()

        db.rawQuery(sql, arrayOf(userId)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val storedHash = cursor.getString(cursor.getColumnIndexOrThrow("password")) ?: return null

            val matches = try {
                BCrypt.verifyer().verify(password.toCharArray(), storedHash).verified
            } catch (_: Exception) {
                false
            }
            if (!matches) return null

            val roleCode = cursor.getString(cursor.getColumnIndexOrThrow("role"))
            val isBlocked = cursor.getInt(cursor.getColumnIndexOrThrow("is_blocked")) == 1
            val storeId = cursor.getInt(cursor.getColumnIndexOrThrow("store_id"))
            val serialNo = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
            val role = if (roleCode == "G") UserRole.GENERAL_USER else UserRole.ADMIN
            return User(
                userId = userId,
                password = password,
                role = role,
                isBlocked = isBlocked,
                storeId = storeId,
                serialNo = serialNo
            )
        }
    }

    private companion object {
        /**
         * How many backups the picker lists before falling back to browsing.
         *
         * A till backing up hourly has hundreds of them, and a dialog that scrolls
         * past a screenful is not a shorter route to the file than the file manager.
         */
        const val MAX_LISTED_BACKUPS = 15
    }
}
