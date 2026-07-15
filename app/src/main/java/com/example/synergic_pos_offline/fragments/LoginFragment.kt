package com.example.synergic_pos_offline.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.models.UserRole
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager
import com.example.synergic_pos_offline.utils.UserManager
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class LoginFragment : Fragment() {

    private lateinit var tilUsername: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: Button

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

        setupTextWatchers()

        btnLogin.setOnClickListener {
            if (validateInputs()) {
                performLogin()
            }
        }

        ThemeManager.applyTheme(view)
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

        val user = UserManager.authenticate(username, password)
        
        if (user != null) {
            if (user.isBlocked) {
                tilUsername.error = "This account is blocked"
                Toast.makeText(requireContext(), "User is blocked. Contact Admin.", Toast.LENGTH_SHORT).show()
            } else {
                SessionManager.currentUser = user
                val roleText = if (user.role == UserRole.ADMIN) "Admin" else "General User"
                Toast.makeText(requireContext(), "Welcome $roleText!", Toast.LENGTH_SHORT).show()
                
                val nextFragment = MenuFragment()

                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, nextFragment)
                    .addToBackStack(null)
                    .commit()
            }
        } else {
            tilUsername.error = " "
            tilPassword.error = "Invalid username or password"
        }
    }
}