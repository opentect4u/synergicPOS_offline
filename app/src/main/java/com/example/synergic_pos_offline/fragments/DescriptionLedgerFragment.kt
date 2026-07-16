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
import com.example.synergic_pos_offline.database.DescriptionDao
import com.example.synergic_pos_offline.database.DescriptionDao.DescType
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

/**
 * "Description / Ledger" management screen — a concrete [DataTableFragment]
 * backed by the [DescriptionDao] SQLite table.
 *
 * Each row is a receipt/payment description head with an auto-generated,
 * persisted `description_id_auto`.
 */
class DescriptionLedgerFragment : DataTableFragment() {

    override val screenTitle = "Description / Ledger"

    // Table columns. Cell layout per row: [autoId, name, typeLabel].
    override val columns = listOf("Desc ID", "Desc Name", "Type")

    private companion object {
        const val COL_ID = 0
        const val COL_NAME = 1
        const val COL_TYPE = 2
    }

    private val dao: DescriptionDao by lazy { DescriptionDao(requireContext()) }
    private val typeLabels = DescType.values().map { it.label }

    // ---- Data --------------------------------------------------------------

    override fun loadRows(): MutableList<DataRow> =
        dao.getAll().map { it.toRow() }.toMutableList()

    private fun DescriptionDao.Description.toRow(): DataRow =
        DataRow(id.toString(), listOf(autoId, name, type.label))

    // ---- Custom Add / Edit popups -----------------------------------------

    override fun onAddRow() = showDescriptionDialog(null)

    override fun onEditRow(row: DataRow) = showDescriptionDialog(row)

    override fun onRowsDeleted(ids: Set<String>) {
        dao.delete(ids.mapNotNull { it.toLongOrNull() })
    }

    private fun showDescriptionDialog(existing: DataRow?) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_description, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val etId = view.findViewById<TextInputEditText>(R.id.etDescId)
        val etName = view.findViewById<TextInputEditText>(R.id.etDescName)
        val actvType = view.findViewById<MaterialAutoCompleteTextView>(R.id.actvDescType)
        val tvLast = view.findViewById<TextView>(R.id.tvLastGenerated)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        actvType.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, typeLabels))

        val descId = existing?.cells?.getOrNull(COL_ID).orEmpty()
            .ifBlank { DescriptionDao.formatCode(dao.nextId()) }
        tvTitle.text = if (existing == null) "Add Description" else "Edit Description"
        etId.setText(descId)
        etName.setText(existing?.cells?.getOrNull(COL_NAME).orEmpty())
        actvType.setText(existing?.cells?.getOrNull(COL_TYPE) ?: DescType.RECEIPT.label, false)
        tvLast.text = "Last generated: ${dao.lastId()?.let(DescriptionDao::formatCode) ?: "—"}"
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
            val type = DescType.fromLabel(actvType.text?.toString())

            if (existing == null) {
                val created = dao.insert(name, type)
                if (created == null) { toast("Save failed"); return@setOnClickListener }
                dialog.dismiss()
                addRow(DataRow(created.id.toString(), listOf(created.autoId, created.name, created.type.label)))
                toast("Added ${created.autoId}")
            } else {
                dao.update(existing.id.toLong(), name, type)
                dialog.dismiss()
                updateRow(existing.id, listOf(descId, name, type.label))
                toast("Updated $descId")
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
