package com.example.synergic_pos_offline.fragments

import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.ApiClient
import com.example.synergic_pos_offline.utils.NetworkBadge
import com.example.synergic_pos_offline.utils.NetworkMonitor
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/** Store onboarding screen, reachable from the login screen before any user is signed in. */
class RegistrationFragment : Fragment() {

    private lateinit var tilStoreName: TextInputLayout
    private lateinit var tilAddress: TextInputLayout
    private lateinit var tilPhone: TextInputLayout
    private lateinit var tilGstin: TextInputLayout

    private lateinit var etStoreName: TextInputEditText
    private lateinit var etAddress: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var etGstin: TextInputEditText
    private lateinit var btnRegister: MaterialButton
    private lateinit var tvBackToLogin: View

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private lateinit var networkMonitor: NetworkMonitor

    /** Validity period, in months, granted from the registration date. */
    private val validityMonths = 15

    // Captured automatically and submitted without being shown on screen.
    private var deviceId: String = ""
    private var registrationDt: String = ""
    private var registrationUpto: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_registration, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tilStoreName = view.findViewById(R.id.tilStoreName)
        tilAddress = view.findViewById(R.id.tilAddress)
        tilPhone = view.findViewById(R.id.tilPhone)
        tilGstin = view.findViewById(R.id.tilGstin)

        etStoreName = view.findViewById(R.id.etStoreName)
        etAddress = view.findViewById(R.id.etAddress)
        etPhone = view.findViewById(R.id.etPhone)
        etGstin = view.findViewById(R.id.etGstin)
        btnRegister = view.findViewById(R.id.btnRegister)
        tvBackToLogin = view.findViewById(R.id.tvBackToLogin)

        captureHiddenFields()
        setupTextWatchers()

        btnRegister.setOnClickListener {
            if (validateInputs()) {
                submitRegistration()
            }
        }

        tvBackToLogin.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        ThemeManager.applyTheme(view)

        // Registration requires the server, so the badge reflects connectivity and the
        // Register button stays disabled while offline.
        networkMonitor = NetworkMonitor(requireContext())
        networkMonitor.register { online ->
            this.view?.let { NetworkBadge.bind(it, online) }
            setRegisterEnabled(online)
        }
    }

    private fun setRegisterEnabled(enabled: Boolean) {
        btnRegister.isEnabled = enabled
        btnRegister.alpha = if (enabled) 1f else 0.5f
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
     * Gathers the values that are submitted but never shown: the device identifier,
     * the registration date (today) and the validity date (registration + 15 months).
     */
    private fun captureHiddenFields() {
        deviceId = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()

        val calendar = Calendar.getInstance()
        registrationDt = dateFormat.format(calendar.time)
        calendar.add(Calendar.MONTH, validityMonths)
        registrationUpto = dateFormat.format(calendar.time)
    }

    private fun setupTextWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                tilStoreName.error = null
                tilAddress.error = null
                tilPhone.error = null
                tilGstin.error = null
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        etStoreName.addTextChangedListener(watcher)
        etAddress.addTextChangedListener(watcher)
        etPhone.addTextChangedListener(watcher)
        etGstin.addTextChangedListener(watcher)
    }

    private fun validateInputs(): Boolean {
        var isValid = true

        if (etStoreName.text.toString().trim().isEmpty()) {
            tilStoreName.error = "Store name is required"
            isValid = false
        }

        val phone = etPhone.text.toString().trim()
        if (phone.isEmpty()) {
            tilPhone.error = "Phone number is required"
            isValid = false
        } else if (phone.length != 10) {
            tilPhone.error = "Enter a valid 10-digit phone number"
            isValid = false
        }

        val gstin = etGstin.text.toString().trim()
        if (gstin.isNotEmpty() && gstin.length != 15) {
            tilGstin.error = "GSTIN must be 15 characters"
            isValid = false
        }

        if (etAddress.text.toString().trim().isEmpty()) {
            tilAddress.error = "Address is required"
            isValid = false
        }

        return isValid
    }

    /** Builds the registration request payload from the form and the captured fields. */
    private fun buildPayload(): JSONObject {
        val gstin = etGstin.text.toString().trim()
        return JSONObject().apply {
            put("store_name", etStoreName.text.toString().trim())
            put("address", etAddress.text.toString().trim())
            put("phone_no", etPhone.text.toString().trim())
            put("store_gstin", if (gstin.isEmpty()) JSONObject.NULL else gstin)
            put("device_id", deviceId)
            put("registration_dt", registrationDt)
            put("registration_upto", registrationUpto)
        }
    }

    /** Posts the payload to the registration endpoint, showing a loader and then the response. */
    private fun submitRegistration() {
        val payload = buildPayload()
        val loader = showLoader()

        ioExecutor.execute {
            val result = ApiClient.postJson(ApiClient.PATH_REGISTER, payload)
            view?.post {
                if (!isAdded) return@post
                loader.dismiss()
                showResponse(result)
            }
        }
    }

    /** Small indeterminate spinner dialog shown while the request is in flight. */
    private fun showLoader(): AlertDialog {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(requireContext()))
            addView(TextView(requireContext()).apply {
                text = getString(R.string.registering)
                textSize = 16f
                setPadding(pad, 0, 0, 0)
            })
        }
        return AlertDialog.Builder(requireContext())
            .setView(row)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun showResponse(result: ApiClient.ApiResult) {
        if (result.ok) {
            showSuccessToast(successMessage(result.body))
            // Registration is done — return to the login screen.
            requireActivity().supportFragmentManager.popBackStack()
            return
        }

        val message = buildString {
            if (result.error != null) {
                append(result.error)
            } else {
                append("HTTP ${result.status}\n\n")
                append(prettyOrRaw(result.body))
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(if (result.error != null) "Request Failed" else "Registration Failed")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    /** Uses the server's message when present, otherwise a friendly default. */
    private fun successMessage(body: String): String = try {
        JSONObject(body).optString("message").ifBlank { "Registration successful!" }
    } catch (_: Exception) {
        "Registration successful!"
    }

    /** Shows a styled success toast (green card + check icon) near the top of the screen. */
    private fun showSuccessToast(message: String) {
        val toastView = layoutInflater.inflate(R.layout.toast_success, null).apply {
            findViewById<TextView>(R.id.tvToastMessage).text = message
        }
        Toast(requireContext().applicationContext).apply {
            duration = Toast.LENGTH_LONG
            @Suppress("DEPRECATION")
            view = toastView
            setGravity(
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                0,
                (72 * resources.displayMetrics.density).toInt()
            )
            show()
        }
    }

    /** Pretty-prints a JSON object/array response; falls back to the raw text. */
    private fun prettyOrRaw(body: String): String {
        if (body.isBlank()) return "(empty response)"
        return try {
            JSONObject(body).toString(2)
        } catch (_: Exception) {
            try {
                JSONArray(body).toString(2)
            } catch (_: Exception) {
                body
            }
        }
    }
}
