package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.AssignWaiterDao
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

/**
 * "Assign Waiter" screen (restaurant only), backed by [AssignWaiterDao]
 * (td_assign_waiter). Reuses the Waiter design but keeps only Waiter ID and
 * Waiter Name — no table. Waiters are picked from the waiter master.
 */
class AssignWaiterFragment : DataTableFragment() {

    override val screenTitle = "Assign Waiter"

    // Cell layout per row: [waiterId(code), name].
    override val columns = listOf("Waiter ID", "Waiter Name")

    private companion object {
        const val COL_ID = 0
        const val COL_NAME = 1
    }

    private val dao: AssignWaiterDao by lazy { AssignWaiterDao(requireContext()) }
    private var waiters: List<AssignWaiterDao.WaiterOption> = emptyList()

    override fun loadRows(): MutableList<DataRow> {
        dao.ensureForWaiters()   // mirror any waiters not yet assigned
        return dao.getAll().map { DataRow(it.id.toString(), listOf(it.code, it.waiterName)) }.toMutableList()
    }

    override fun onAddRow() = showAssignDialog(null)

    override fun onEditRow(row: DataRow) = showAssignDialog(row)

    override fun onRowsDeleted(ids: Set<String>) {
        dao.delete(ids.mapNotNull { it.toLongOrNull() })
    }

    private fun showAssignDialog(existing: DataRow?) {
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)
        waiters = dao.waiters()

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_assign_waiter, null)
        com.example.synergic_pos_offline.utils.InputLimits.applyDefaults(view)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val actvName = view.findViewById<MaterialAutoCompleteTextView>(R.id.actvWaiterName)
        val etId = view.findViewById<TextInputEditText>(R.id.etWaiterId)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        actvName.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, waiters.map { it.name }))
        actvName.setOnItemClickListener { _, _, pos, _ ->
            val w = waiters[pos]
            actvName.tag = w.id
            etId.setText(w.code)
        }

        tvTitle.text = if (existing == null) "Assign Waiter" else "Edit Assignment"
        btnSave.text = if (existing == null) "Assign" else "Update"
        // Prefill in edit mode by matching the selected waiter name.
        existing?.cells?.getOrNull(COL_NAME)?.let { name ->
            waiters.firstOrNull { it.name == name }?.let { w ->
                actvName.setText(w.name, false)
                actvName.tag = w.id
                etId.setText(w.code)
            }
        }

        ThemeManager.applyTheme(view)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnSave.setOnClickListener {
            val waiterId = actvName.tag as? Long
            val name = actvName.text?.toString()?.trim().orEmpty()
            if (waiterId == null || name.isEmpty()) {
                actvName.error = "Select a waiter"
                return@setOnClickListener
            }
            if (existing == null) {
                val id = dao.insert(waiterId, name)
                if (id == -1L) { toast("Save failed"); return@setOnClickListener }
                dialog.dismiss()
                refreshRows()
                toast("Assigned $name")
            } else {
                dao.update(existing.id.toLong(), waiterId, name)
                dialog.dismiss()
                refreshRows()
                toast("Updated")
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
        }
    }
}
