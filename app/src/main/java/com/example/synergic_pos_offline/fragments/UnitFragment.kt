package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.UnitDao
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

/**
 * "Units" management screen — a concrete [DataTableFragment] backed by the
 * [UnitDao] SQLite table.
 *
 * The bespoke Add/Edit popup supports:
 *  - an auto-generated, read-only unit code derived from the row id,
 *  - a reminder of the last generated code value,
 *  - a fraction Enable/Disable toggle stored as fraction_flag.
 */
class UnitFragment : DataTableFragment() {

    override val screenTitle = "Units"

    // Table columns. Cell layout per row: [code, name, symbol, fractionState].
    override val columns = listOf("Unit Code", "Unit Name", "Symbol", "Fraction")

    private companion object {
        const val COL_CODE = 0
        const val COL_NAME = 1
        const val COL_SYMBOL = 2
        const val COL_FRACTION = 3
    }

    private val dao: UnitDao by lazy { UnitDao(requireContext()) }

    // ---- Data --------------------------------------------------------------

    override fun loadRows(): MutableList<DataRow> =
        dao.getAll().map { it.toRow() }.toMutableList()

    private fun UnitDao.Unit.toRow(): DataRow =
        DataRow(id.toString(), listOf(code, name, symbol, if (fraction) "Enabled" else "Disabled"))

    // ---- Custom Add / Edit popups -----------------------------------------

    override fun onAddRow() = showUnitDialog(null)

    override fun onEditRow(row: DataRow) = showUnitDialog(row)

    override fun onRowsDeleted(ids: Set<String>) {
        dao.delete(ids.mapNotNull { it.toLongOrNull() })
    }

    /**
     * Shows the Unit popup. When [existing] is null the dialog is in "Add" mode
     * and previews the next unit code; otherwise it edits the given row while
     * keeping its original code.
     */
    private fun showUnitDialog(existing: DataRow?) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_unit, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val etCode = view.findViewById<TextInputEditText>(R.id.etUnitCode)
        val etName = view.findViewById<TextInputEditText>(R.id.etUnitName)
        val etSymbol = view.findViewById<TextInputEditText>(R.id.etUnitSymbol)
        val swFraction = view.findViewById<SwitchMaterial>(R.id.swFraction)
        val tvFractionState = view.findViewById<TextView>(R.id.tvFractionState)
        val tvLast = view.findViewById<TextView>(R.id.tvLastGenerated)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        val code = existing?.cells?.getOrNull(COL_CODE).orEmpty()
            .ifBlank { UnitDao.formatCode(dao.nextId()) }
        tvTitle.text = if (existing == null) "Add Unit" else "Edit Unit"
        etCode.setText(code)
        etName.setText(existing?.cells?.getOrNull(COL_NAME).orEmpty())
        etSymbol.setText(existing?.cells?.getOrNull(COL_SYMBOL).orEmpty())
        swFraction.isChecked = existing?.cells?.getOrNull(COL_FRACTION) == "Enabled"
        tvFractionState.text = if (swFraction.isChecked) "Enabled" else "Disabled"
        tvLast.text = "Last generated: ${dao.lastId()?.let(UnitDao::formatCode) ?: "—"}"
        btnSave.text = if (existing == null) "Add" else "Update"

        swFraction.setOnCheckedChangeListener { _, checked ->
            tvFractionState.text = if (checked) "Enabled" else "Disabled"
        }

        ThemeManager.applyTheme(view)
        swFraction.thumbTintList = ColorStateList.valueOf(accent)
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
            val symbol = etSymbol.text?.toString()?.trim().orEmpty()
            val fraction = swFraction.isChecked
            val state = if (fraction) "Enabled" else "Disabled"

            if (existing == null) {
                val id = dao.insert(name, symbol, fraction)
                if (id == -1L) { toast("Save failed"); return@setOnClickListener }
                dialog.dismiss()
                addRow(DataRow(id.toString(), listOf(UnitDao.formatCode(id), name, symbol, state)))
                toast("Added ${UnitDao.formatCode(id)}")
            } else {
                dao.update(existing.id.toLong(), name, symbol, fraction)
                dialog.dismiss()
                updateRow(existing.id, listOf(code, name, symbol, state))
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
