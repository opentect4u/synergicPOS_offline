package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.WaiterDao
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * "Waiter" management screen — a concrete [DataTableFragment] backed by the
 * [WaiterDao] SQLite table.
 *
 * The bespoke Add/Edit popup supports:
 *  - an auto-generated, read-only waiter code derived from the row id,
 *  - a reminder of the last generated code value,
 *  - a Table No. exposed-dropdown selection.
 */
class WaiterFragment : DataTableFragment() {

    override val screenTitle = "Waiter"

    // Table columns. Cell layout per row: [code, name].
    override val columns = listOf("Waiter Code", "Waiter Name")

    private companion object {
        const val COL_CODE = 0
        const val COL_NAME = 1
    }

    private val dao: WaiterDao by lazy { WaiterDao(requireContext()) }

    // ---- Data --------------------------------------------------------------

    override fun loadRows(): MutableList<DataRow> =
        dao.getAll().map { it.toRow() }.toMutableList()

    private fun WaiterDao.Waiter.toRow(): DataRow =
        DataRow(id.toString(), listOf(code, name))

    // ---- Custom Add / Edit popups -----------------------------------------

    override fun onAddRow() = showWaiterDialog(null)

    override fun onEditRow(row: DataRow) = showWaiterDialog(row)

    override fun onRowsDeleted(ids: Set<String>) {
        dao.delete(ids.mapNotNull { it.toLongOrNull() })
    }

    /**
     * Shows the Waiter popup. When [existing] is null the dialog is in "Add" mode
     * and previews the next waiter code; otherwise it edits the given row while
     * keeping its original code.
     */
    private fun showWaiterDialog(existing: DataRow?) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_waiter, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val etCode = view.findViewById<TextInputEditText>(R.id.etWaiterCode)
        val etName = view.findViewById<TextInputEditText>(R.id.etWaiterName)
        val tvLast = view.findViewById<TextView>(R.id.tvLastGenerated)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        val code = existing?.cells?.getOrNull(COL_CODE).orEmpty()
            .ifBlank { WaiterDao.formatCode(dao.nextId()) }
        tvTitle.text = if (existing == null) "Add Waiter" else "Edit Waiter"
        etCode.setText(code)
        etName.setText(existing?.cells?.getOrNull(COL_NAME).orEmpty())
        tvLast.text = "Last generated: ${dao.lastId()?.let(WaiterDao::formatCode) ?: "—"}"
        btnSave.text = if (existing == null) "Add" else "Update"

        ThemeManager.applyTheme(view)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        // ThemeManager fills every MaterialButton's background; restore the
        // outlined (border) look for the negative/Cancel button.
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnSave.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                etName.error = "Name is required"
                return@setOnClickListener
            }
            if (existing == null) {
                val id = dao.insert(name, "")
                if (id == -1L) { toast("Save failed"); return@setOnClickListener }
                dialog.dismiss()
                addRow(DataRow(id.toString(), listOf(WaiterDao.formatCode(id), name)))
                toast("Added ${WaiterDao.formatCode(id)}")
            } else {
                dao.update(existing.id.toLong(), name, "")
                dialog.dismiss()
                updateRow(existing.id, listOf(code, name))
                toast("Updated $code")
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
