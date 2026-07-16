package com.example.synergic_pos_offline.fragments

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.models.User
import com.example.synergic_pos_offline.models.UserRole
import com.example.synergic_pos_offline.utils.ApiClient
import com.example.synergic_pos_offline.utils.NetworkBadge
import com.example.synergic_pos_offline.utils.NetworkMonitor
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager
import at.favre.lib.crypto.bcrypt.BCrypt
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
    private lateinit var tvPending: View
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private lateinit var networkMonitor: NetworkMonitor

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

        tvRegister.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, RegistrationFragment())
                .addToBackStack(null)
                .commit()
        }

        ThemeManager.applyTheme(view)

        networkMonitor = NetworkMonitor(requireContext())
        networkMonitor.register { online ->
            this.view?.let { NetworkBadge.bind(it, online) }
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
            val result = ApiClient.postJson(ApiClient.PATH_CHECK_USER, payload)
            // On success read the record; if the check fails, fall back to offering
            // registration (treat as an unknown / unregistered device).
            val record = if (result.ok) firstRecord(result.body) else null
            val flag = record?.let { verifyFlagOf(it) }

            // A verified device (flag == 1): mirror the store + user into SQLite so
            // the app has everything it needs to work offline.
            if (record != null && flag == 1) {
                saveVerifiedStore(appContext, record)
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
    }

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

    /** Persists a verified store into md_registration and its admin user into md_users, once. */
    private fun saveVerifiedStore(context: Context, record: JSONObject) {
        val db = DatabaseHelper.getInstance(context).writableDatabase

        val storeId = record.optInt("store_id")
        // Write once: if this store is already stored locally, leave it untouched.
        if (storeExists(db, storeId)) return

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
        db.insertWithOnConflict(
            DatabaseHelper.Tables.MD_REGISTRATION, null, registration, SQLiteDatabase.CONFLICT_REPLACE
        )

        val user = ContentValues().apply {
            put("id", storeId)
            put("store_id", storeId)
            put("outlet_id", outletId)
            put("user_id", str(record, "user_id"))
            put("password", str(record, "password"))
            put("user_name", storeName)
            put("phone_no", phone)
            put("role", "A")
            put("is_blocked", 0)
        }
        db.insertWithOnConflict(
            DatabaseHelper.Tables.MD_USERS, null, user, SQLiteDatabase.CONFLICT_REPLACE
        )
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

        SessionManager.currentUser = user
        val roleText = if (user.role == UserRole.ADMIN) "Admin" else "General User"
        Toast.makeText(requireContext(), "Welcome $roleText!", Toast.LENGTH_SHORT).show()

        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, MenuFragment())
            .addToBackStack(null)
            .commit()
    }

    /**
     * Authenticates against the locally-stored md_users, but only for a store whose
     * md_registration.verify_flag is 1. The stored password is a bcrypt hash, so the
     * entered password is verified against it. Returns null when the user_id is unknown
     * or the password does not match.
     */
    private fun authenticateLocal(userId: String, password: String): User? {
        if (userId.isEmpty() || password.isEmpty()) return null

        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        val sql = """
            SELECT u.password, u.role, u.is_blocked, u.store_id
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
            val role = if (roleCode == "G") UserRole.GENERAL_USER else UserRole.ADMIN
            return User(
                userId = userId,
                password = password,
                role = role,
                isBlocked = isBlocked,
                storeId = storeId
            )
        }
    }
}
