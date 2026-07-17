package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.UserDao
import com.example.synergic_pos_offline.database.UserDao.Role
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * "User Management" screen — a concrete [DataTableFragment] backed by the
 * [UserDao] SQLite table.
 *
 * Managed users are always created as General User. Supports Add, Edit,
 * Block/Unblock (with confirmation), and Change/Reset password. The Operator ID
 * is auto-generated from the row id; the login User ID is fixed after creation.
 * A signed-in user cannot block themselves.
 */
class UserManagementFragment : DataTableFragment() {

    override val screenTitle = "User Management"

    // Table columns. Cell layout per row: [operatorId, userId, name, status].
    override val columns = listOf("Operator ID", "User ID", "Name", "Status")

    private val dao: UserDao by lazy { UserDao(requireContext()) }

    /** Full users keyed by row id, for edit prefill. */
    private val cache = mutableMapOf<String, UserDao.AppUser>()

    // ---- Data --------------------------------------------------------------

    override fun loadRows(): MutableList<DataRow> {
        cache.clear()
        val users = dao.getAll()
        for (u in users) cache[u.id.toString()] = u
        return users.map { it.toRow() }.toMutableList()
    }

    private fun UserDao.AppUser.toRow(): DataRow = DataRow(
        id.toString(),
        listOf(operatorId, userId, userName, if (blocked) "Blocked" else "Active")
    )

    // ---- Custom Add / Edit popups -----------------------------------------

    override fun onAddRow() = showUserDialog(null)

    override fun onEditRow(row: DataRow) = showUserDialog(cache[row.id])

    override fun onRowsDeleted(ids: Set<String>) {
        dao.delete(ids.mapNotNull { it.toLongOrNull() })
    }

    /** Only an admin may delete users. */
    override fun onBulkDelete() {
        if (!SessionManager.isAdmin()) {
            toast("Only admin can delete users")
            return
        }
        super.onBulkDelete()
    }

    private fun showUserDialog(existing: UserDao.AppUser?) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_user, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val etOperatorId = view.findViewById<TextInputEditText>(R.id.etOperatorId)
        val tilUserId = view.findViewById<TextInputLayout>(R.id.tilUserId)
        val etUserId = view.findViewById<TextInputEditText>(R.id.etUserId)
        val etPhone = view.findViewById<TextInputEditText>(R.id.etPhone)
        val etUserName = view.findViewById<TextInputEditText>(R.id.etUserName)
        val tilPassword = view.findViewById<TextInputLayout>(R.id.tilPassword)
        val etPassword = view.findViewById<TextInputEditText>(R.id.etPassword)
        val btnResetPassword = view.findViewById<MaterialButton>(R.id.btnResetPassword)
        val swBlock = view.findViewById<SwitchMaterial>(R.id.swBlock)
        val tvBlockState = view.findViewById<TextView>(R.id.tvBlockState)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        val operatorId = existing?.operatorId ?: UserDao.formatOperatorId(dao.nextId())
        tvTitle.text = if (existing == null) "Add User" else "Edit User"
        etOperatorId.setText(operatorId)
        etUserId.setText(existing?.userId.orEmpty())
        etPhone.setText(existing?.phone.orEmpty())
        etUserName.setText(existing?.userName.orEmpty())
        btnSave.text = if (existing == null) "Add" else "Update"

        // Password is set inline on add; changed via a separate dialog on edit.
        if (existing == null) {
            tilPassword.visibility = View.VISIBLE
            btnResetPassword.visibility = View.GONE
        } else {
            tilPassword.visibility = View.GONE
            btnResetPassword.visibility = View.VISIBLE
            // User ID is the login identity; keep it fixed once created.
            tilUserId.isEnabled = false
        }

        fun applyBlockState(blocked: Boolean) {
            tvBlockState.text = if (blocked) "Blocked" else "Active"
        }

        // Only an admin may block, and never themselves.
        val isSelf = existing != null &&
            existing.userId.equals(SessionManager.currentUser?.userId, ignoreCase = false)
        val isAdmin = SessionManager.isAdmin()
        swBlock.isChecked = existing?.blocked ?: false
        if (!isAdmin || isSelf) {
            swBlock.isEnabled = false
            tvBlockState.text = when {
                !isAdmin -> "Only admin can block"
                else -> "You can't block yourself"
            }
        } else {
            applyBlockState(swBlock.isChecked)
            var suppressBlock = false
            swBlock.setOnCheckedChangeListener { _, checked ->
                if (suppressBlock) return@setOnCheckedChangeListener
                val who = existing?.userId ?: "this user"
                DialogUtils.showConfirm(
                    context = ctx,
                    title = if (checked) "Block User" else "Unblock User",
                    message = if (checked) "Block \"$who\"? They will not be able to log in."
                    else "Unblock \"$who\"? They will be able to log in again.",
                    positiveText = if (checked) "Block" else "Unblock",
                    negativeText = "Cancel",
                    iconRes = android.R.drawable.ic_lock_lock,
                    destructive = checked,
                    onCancel = {
                        suppressBlock = true
                        swBlock.isChecked = !checked
                        suppressBlock = false
                        applyBlockState(!checked)
                    },
                    onConfirm = {
                        applyBlockState(checked)
                        // Persist immediately so a blocked user is locked out of
                        // login right away, without waiting for the Update button.
                        existing?.let { u ->
                            dao.setBlocked(u.id, checked)
                            val key = u.id.toString()
                            val updated = u.copy(blocked = checked)
                            cache[key] = updated
                            updateRow(key, updated.toRow().cells)
                            toast(if (checked) "\"${u.userId}\" blocked" else "\"${u.userId}\" unblocked")
                        }
                    }
                )
            }
        }

        btnResetPassword.setOnClickListener {
            existing?.let { showResetPasswordDialog(it) }
        }

        ThemeManager.applyTheme(view)
        swBlock.thumbTintList = ColorStateList.valueOf(accent)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        // ThemeManager fills every MaterialButton's background; restore the
        // outlined (border) look for the negative / secondary buttons.
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)
        btnResetPassword.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnResetPassword.setTextColor(accent)
        btnResetPassword.strokeColor = ColorStateList.valueOf(accent)
        btnResetPassword.iconTint = ColorStateList.valueOf(accent)

        btnSave.setOnClickListener {
            val userId = etUserId.text?.toString()?.trim().orEmpty()
            val userName = etUserName.text?.toString()?.trim().orEmpty()
            val phone = etPhone.text?.toString()?.trim().orEmpty()
            val blocked = swBlock.isChecked

            if (userId.isEmpty()) { etUserId.error = "User ID is required"; return@setOnClickListener }
            if (userName.isEmpty()) { etUserName.error = "Name is required"; return@setOnClickListener }

            if (existing == null) {
                val password = etPassword.text?.toString().orEmpty()
                if (password.isEmpty()) { etPassword.error = "Password is required"; return@setOnClickListener }
                if (dao.userIdExists(userId)) { etUserId.error = "User ID already exists"; return@setOnClickListener }
                // Managed users are always created as General User.
                val id = dao.insert(userId, password, userName, phone, Role.GENERAL, blocked)
                if (id == -1L) { toast("Save failed"); return@setOnClickListener }
                dialog.dismiss()
                val created = UserDao.AppUser(id, userId, userName, phone, Role.GENERAL, blocked)
                cache[id.toString()] = created
                addRow(created.toRow())
                toast("User added (${created.operatorId})")
            } else {
                // Keep the existing role; managed edits don't change it.
                dao.update(existing.id, userName, phone, existing.role, blocked)
                dialog.dismiss()
                val updated = existing.copy(userName = userName, phone = phone, blocked = blocked)
                cache[existing.id.toString()] = updated
                updateRow(existing.id.toString(), updated.toRow().cells)
                toast("User updated")
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
        }
    }

    private fun showResetPasswordDialog(user: UserDao.AppUser) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_reset_password, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvSubtitle = view.findViewById<TextView>(R.id.tvResetSubtitle)
        val etNew = view.findViewById<TextInputEditText>(R.id.etNewPassword)
        val etConfirm = view.findViewById<TextInputEditText>(R.id.etConfirmPassword)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnResetPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnResetNegative)

        tvSubtitle.text = "Set a new password for ${user.userId}"

        ThemeManager.applyTheme(view)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnSave.setOnClickListener {
            val pw = etNew.text?.toString().orEmpty()
            val confirm = etConfirm.text?.toString().orEmpty()
            if (pw.isEmpty()) { etNew.error = "Password is required"; return@setOnClickListener }
            if (pw != confirm) { etConfirm.error = "Passwords do not match"; return@setOnClickListener }
            dao.resetPassword(user.id, pw)
            dialog.dismiss()
            toast("Password reset for ${user.userId}")
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
        }
    }
}
