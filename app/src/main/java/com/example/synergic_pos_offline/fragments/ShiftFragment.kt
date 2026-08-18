package com.example.synergic_pos_offline.fragments

import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.ShiftDao
import com.example.synergic_pos_offline.utils.FixedFontScale
import com.example.synergic_pos_offline.utils.InputLimits
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * "Shifts" master - the named stretches of the day this shop runs.
 *
 * A [DataTableFragment] like Units and Rate Names, and deliberately the same shape:
 * the list, the Add/Edit popup and the delete behave as the other masters do, because
 * a shop that has added a unit already knows how to add a shift.
 *
 * Only reachable while App Settings' Shift toggle is on - see [MasterFragment], which
 * asks the same question before it shows the tile.
 */
class ShiftFragment : DataTableFragment() {

    override val screenTitle = "Shifts"

    override val columns = listOf("Shift Code", "Shift Name", "From Time", "To Time")

    private companion object {
        const val COL_CODE = 0
        const val COL_NAME = 1
        const val COL_FROM = 2
        const val COL_TO = 3

        /** What a shift's times default to when one is being added from nothing. */
        const val DEFAULT_FROM = "09:00"
        const val DEFAULT_TO = "18:00"
    }

    private val dao: ShiftDao by lazy { ShiftDao(requireContext()) }

    // ---- Data ---------------------------------------------------------------

    override fun loadRows(): MutableList<DataRow> =
        dao.getAll().map { it.toRow() }.toMutableList()

    private fun ShiftDao.Shift.toRow(): DataRow =
        DataRow(id.toString(), listOf(code, name, fromTime, toTime))

    // ---- Add / Edit ----------------------------------------------------------

    override fun onAddRow() = showShiftDialog(null)

    override fun onEditRow(row: DataRow) = showShiftDialog(row)

    override fun onRowsDeleted(ids: Set<String>) {
        // Takes the shift off the users who were on it too - see [ShiftDao.delete].
        dao.delete(ids.mapNotNull { it.toLongOrNull() })
    }

    /**
     * The Add / Edit popup: a derived code, a name, and the two times.
     *
     * The time fields are not typed into. A clock picker is the one way to be sure
     * what is stored is a real "HH:mm" - the format every screen that reads a shift
     * expects - and it also saves an operator working out what "half six in the
     * evening" is in twenty-four hours.
     */
    private fun showShiftDialog(existing: DataRow?) {
        val ctx = FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_shift, null)
        InputLimits.applyDefaults(view)
        val dialog = AlertDialog.Builder(ctx).setView(view).create()
            .also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
        }

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val etCode = view.findViewById<TextInputEditText>(R.id.etShiftCode)
        val etName = view.findViewById<TextInputEditText>(R.id.etShiftName)
        val etFrom = view.findViewById<TextInputEditText>(R.id.etShiftFrom)
        val etTo = view.findViewById<TextInputEditText>(R.id.etShiftTo)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        val code = existing?.cells?.getOrNull(COL_CODE).orEmpty()
            .ifBlank { ShiftDao.formatCode(dao.nextId()) }
        tvTitle.text = if (existing == null) "Add Shift" else "Edit Shift"
        etCode.setText(code)
        etName.setText(existing?.cells?.getOrNull(COL_NAME).orEmpty())
        etFrom.setText(existing?.cells?.getOrNull(COL_FROM).orEmpty().ifBlank { DEFAULT_FROM })
        etTo.setText(existing?.cells?.getOrNull(COL_TO).orEmpty().ifBlank { DEFAULT_TO })
        btnSave.text = if (existing == null) "Add" else "Update"

        etFrom.setOnClickListener { pickTime(etFrom) }
        etTo.setOnClickListener { pickTime(etTo) }

        ThemeManager.applyTheme(view)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        // ThemeManager fills every MaterialButton; restore the outlined Cancel.
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnSave.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                etName.error = "Shift name is required"
                return@setOnClickListener
            }
            val from = etFrom.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_FROM }
            val to = etTo.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_TO }

            if (existing == null) {
                val id = dao.insert(name, from, to)
                if (id == -1L) {
                    toast("Save failed")
                    return@setOnClickListener
                }
                dialog.dismiss()
                addRow(DataRow(id.toString(), listOf(ShiftDao.formatCode(id), name, from, to)))
                toast("Added ${ShiftDao.formatCode(id)}")
            } else {
                dao.update(existing.id.toLong(), name, from, to)
                dialog.dismiss()
                updateRow(existing.id, listOf(code, name, from, to))
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

    /** Opens the clock on whatever the field already reads, and writes back "HH:mm". */
    private fun pickTime(field: TextInputEditText) {
        val current = field.text?.toString()?.trim().orEmpty()
        val hour = current.substringBefore(':').toIntOrNull()?.coerceIn(0, 23) ?: 9
        val minute = current.substringAfter(':', "").toIntOrNull()?.coerceIn(0, 59) ?: 0
        TimePickerDialog(
            requireContext(),
            { _, h, m -> field.setText("%02d:%02d".format(h, m)) },
            hour, minute,
            // Twenty-four hour, whatever the device is set to: a shift master read at
            // a glance should not need AM and PM picked out of it, and "HH:mm" is
            // what every screen downstream stores and compares.
            true
        ).show()
    }
}
