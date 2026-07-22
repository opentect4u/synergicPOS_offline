package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.database.GeneralSettingsDao.GeneralSettings
import com.example.synergic_pos_offline.database.UserDao
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import com.example.synergic_pos_offline.database.GeneralSettingsDao.Mode
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

/**
 * General Settings screen: change the signed-in user's password and toggle the
 * Sale Return feature (persisted via [GeneralSettingsDao], md_app_settings type 'G').
 */
class GeneralSettingsFragment : Fragment(), TitledScreen {

    override val screenTitle = "General Settings"

    private val dao by lazy { GeneralSettingsDao(requireContext()) }
    private val userDao by lazy { UserDao(requireContext()) }

    private lateinit var actMode: MaterialAutoCompleteTextView
    private lateinit var swSaleReturn: SwitchMaterial
    private lateinit var llSaleReturnDays: View
    private lateinit var etSaleReturnDays: TextInputEditText
    private lateinit var swLastBillStatus: SwitchMaterial

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_general_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        actMode = view.findViewById(R.id.actMode)
        swSaleReturn = view.findViewById(R.id.swSaleReturn)
        llSaleReturnDays = view.findViewById(R.id.llSaleReturnDays)
        etSaleReturnDays = view.findViewById(R.id.etSaleReturnDays)
        swLastBillStatus = view.findViewById(R.id.swLastBillStatus)

        val s = dao.load()
        swLastBillStatus.isChecked = s.lastBillStatus

        // Mode dropdown (always shows every option). Displays labels; stores G / R.
        actMode.setAdapter(NoFilterAdapter(requireContext(), Mode.values().map { it.label }))
        actMode.setText(s.mode.label, false)

        swSaleReturn.isChecked = s.saleReturn
        llSaleReturnDays.isVisible = s.saleReturn
        etSaleReturnDays.setText(if (s.saleReturn) s.saleReturnDays.toString() else "")

        // Days section is only shown while Sale Return is on.
        swSaleReturn.setOnCheckedChangeListener { _, on ->
            llSaleReturnDays.isVisible = on
        }

        view.findViewById<MaterialButton>(R.id.btnChangePassword).setOnClickListener {
            showChangePasswordDialog()
        }
        view.findViewById<MaterialButton>(R.id.btnSaveGeneral).setOnClickListener {
            val days = if (swSaleReturn.isChecked) etSaleReturnDays.text?.toString()?.toIntOrNull() ?: 0 else 0
            val mode = Mode.fromStored(actMode.text?.toString()) ?: Mode.GROCERY
            dao.save(
                GeneralSettings(
                    mode = mode,
                    saleReturn = swSaleReturn.isChecked,
                    saleReturnDays = days,
                    lastBillStatus = swLastBillStatus.isChecked
                )
            )
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Saved",
                message = "General settings saved successfully."
            )
        }

        ThemeManager.applyTheme(view)
    }

    private fun showChangePasswordDialog() {
        val userId = SessionManager.currentUser?.userId
        if (userId.isNullOrBlank()) { toast("No signed-in user"); return }

        val accent = ThemeManager.getThemeColor(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_change_password, null)
        val etCurrent = view.findViewById<TextInputEditText>(R.id.etCurrentPwd)
        val etNew = view.findViewById<TextInputEditText>(R.id.etNewPwd)
        val etConfirm = view.findViewById<TextInputEditText>(R.id.etConfirmPwd)

        val dialog = AlertDialog.Builder(requireContext()).setView(view).create()
        dialog.setCanceledOnTouchOutside(false)

        val btnCancel = view.findViewById<MaterialButton>(R.id.btnPwdCancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnPwdSave)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        btnSave.setTextColor(Color.WHITE)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val current = etCurrent.text?.toString()?.trim().orEmpty()
            val newPwd = etNew.text?.toString()?.trim().orEmpty()
            val confirm = etConfirm.text?.toString()?.trim().orEmpty()

            when {
                current.isEmpty() || newPwd.isEmpty() || confirm.isEmpty() ->
                    toast("Fill in all password fields")
                newPwd.length < 4 -> toast("New password must be at least 4 characters")
                newPwd != confirm -> toast("New passwords do not match")
                !userDao.verifyPassword(userId, current) -> toast("Current password is incorrect")
                else -> {
                    val id = userDao.idOf(userId)
                    if (id == null) { toast("User not found") }
                    else {
                        userDao.resetPassword(id, newPwd)
                        SessionManager.currentUser?.password = newPwd
                        dialog.dismiss()
                        DialogUtils.showSuccess(
                            context = requireContext(),
                            title = "Password changed",
                            message = "Your password has been updated successfully."
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    /** Dropdown adapter that never filters, so the full option list always shows. */
    private class NoFilterAdapter(context: android.content.Context, items: List<String>) :
        ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items.toList()) {

        private val all = items.toList()
        private val passthrough = object : android.widget.Filter() {
            override fun performFiltering(constraint: CharSequence?) =
                FilterResults().apply { values = all; count = all.size }
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
        }

        override fun getFilter(): android.widget.Filter = passthrough
    }
}
