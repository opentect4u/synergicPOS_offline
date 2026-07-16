package com.example.synergic_pos_offline.fragments

import android.content.ContentValues
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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

    /** Validity period, in months, granted from the registration date. */
    private val validityMonths = 15

    // Captured automatically and persisted without being shown on screen.
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
                performRegistration()
            }
        }

        tvBackToLogin.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        ThemeManager.applyTheme(view)
    }

    /**
     * Gathers the values that are stored but never shown: the device identifier,
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

    private fun performRegistration() {
        val values = ContentValues().apply {
            put("store_name", etStoreName.text.toString().trim())
            put("address", etAddress.text.toString().trim())
            put("phone_no", etPhone.text.toString().trim())
            put("store_gstin", etGstin.text.toString().trim().ifEmpty { null })
            put("device_id", deviceId)
            put("registration_dt", registrationDt)
            put("registration_upto", registrationUpto)
        }

        val db = DatabaseHelper.getInstance(requireContext()).writableDatabase
        val storeId = db.insert(DatabaseHelper.Tables.MD_REGISTRATION, null, values)

        if (storeId == -1L) {
            Toast.makeText(requireContext(), "Registration failed. Please try again.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "Store registered successfully!", Toast.LENGTH_SHORT).show()
        requireActivity().supportFragmentManager.popBackStack()
    }
}
