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
import com.example.synergic_pos_offline.database.BillHeaderFooterDao
import com.example.synergic_pos_offline.database.BillHeaderFooterDao.FontSize
import com.example.synergic_pos_offline.database.BillHeaderFooterDao.Section
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

/**
 * "Bill Header & Footer" management screen — a concrete [DataTableFragment]
 * backed by the [BillHeaderFooterDao] (md_headers + md_footers, type='BILL').
 *
 * Each row is a printed header/footer line with text, section, font size, bold
 * and enabled flags. Add/Edit/Delete are fully persisted.
 */
class BillHeaderFooterFragment : DataTableFragment() {

    override val screenTitle = "Bill Header & Footer"

    // Table columns. Cell layout per row: [text, section, font, status].
    override val columns = listOf("Text", "Section", "Font", "Status")

    // The Status column renders as an inline ON/OFF switch.
    override val switchColumn: Int? = COL_STATUS

    private companion object {
        const val COL_STATUS = 3
        const val MAX_PER_SECTION = 10
        const val MAX_TEXT_LEN = 32
    }

    private val dao: BillHeaderFooterDao by lazy { BillHeaderFooterDao(requireContext()) }

    /** Full entries keyed by rowKey ("H12"/"F3"), for edit prefill. */
    private val entryCache = mutableMapOf<String, BillHeaderFooterDao.Entry>()

    private val sectionLabels = listOf("Header", "Footer")
    private val fontLabels = FontSize.values().map { it.label }

    // ---- Data --------------------------------------------------------------

    override fun loadRows(): MutableList<DataRow> {
        entryCache.clear()
        val entries = dao.getAll()
        for (e in entries) entryCache[e.rowKey] = e
        return entries.map { it.toRow() }.toMutableList()
    }

    private fun BillHeaderFooterDao.Entry.toRow(): DataRow = DataRow(
        rowKey,
        listOf(
            text,
            if (section == Section.HEADER) "Header" else "Footer",
            fontSize.label,
            if (enabled) "Enabled" else "Disabled"
        )
    )

    // ---- Custom Add / Edit popups -----------------------------------------

    override fun onAddRow() = showEntryDialog(null)

    override fun onEditRow(row: DataRow) = showEntryDialog(row)

    override fun onRowsDeleted(ids: Set<String>) {
        dao.delete(ids)
    }

    /** Inline row switch flips the enabled flag directly, without opening the form. */
    override fun onSwitchToggled(row: DataRow, isOn: Boolean) {
        dao.setEnabled(row.id, isOn)
        entryCache[row.id]?.let { entryCache[row.id] = it.copy(enabled = isOn) }
        val cells = row.cells.toMutableList()
        if (COL_STATUS < cells.size) cells[COL_STATUS] = if (isOn) "Enabled" else "Disabled"
        updateRow(row.id, cells)
    }

    private fun sectionWord(section: Section): String =
        if (section == Section.HEADER) "headers" else "footers"

    private fun showEntryDialog(row: DataRow?) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)
        val existing = row?.let { entryCache[it.id] }

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_bill_header_footer, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val etText = view.findViewById<TextInputEditText>(R.id.etText)
        val actvSection = view.findViewById<MaterialAutoCompleteTextView>(R.id.actvSection)
        val actvFont = view.findViewById<MaterialAutoCompleteTextView>(R.id.actvFontSize)
        val swBold = view.findViewById<SwitchMaterial>(R.id.swBold)
        val swEnabled = view.findViewById<SwitchMaterial>(R.id.swEnabled)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        actvSection.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, sectionLabels))
        actvFont.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, fontLabels))

        tvTitle.text = if (existing == null) "Add Header / Footer" else "Edit Header / Footer"
        etText.setText(existing?.text.orEmpty())
        actvSection.setText(
            if (existing?.section == Section.FOOTER) "Footer" else "Header", false
        )
        actvFont.setText((existing?.fontSize ?: FontSize.MEDIUM).label, false)
        swBold.isChecked = existing?.bold ?: false
        swEnabled.isChecked = existing?.enabled ?: true
        btnSave.text = if (existing == null) "Add" else "Update"

        ThemeManager.applyTheme(view)
        swBold.thumbTintList = ColorStateList.valueOf(accent)
        swEnabled.thumbTintList = ColorStateList.valueOf(accent)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        // ThemeManager fills every MaterialButton's background; restore the
        // outlined (border) look for the negative/Cancel button.
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnSave.setOnClickListener {
            val text = etText.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                etText.error = "Text is required"
                return@setOnClickListener
            }
            val section = if (actvSection.text?.toString() == "Footer") Section.FOOTER else Section.HEADER
            val font = FontSize.fromLabel(actvFont.text?.toString())
            val bold = swBold.isChecked
            val enabled = swEnabled.isChecked

            if (existing == null) {
                if (dao.count(section) >= MAX_PER_SECTION) {
                    toast("Maximum $MAX_PER_SECTION ${sectionWord(section)} allowed")
                    return@setOnClickListener
                }
                val key = dao.insert(section, text, font, bold, enabled)
                if (key == null) { toast("Save failed"); return@setOnClickListener }
                dialog.dismiss()
                reload()
                toast("Added")
            } else if (section != existing.section) {
                if (dao.count(section) >= MAX_PER_SECTION) {
                    toast("Maximum $MAX_PER_SECTION ${sectionWord(section)} allowed")
                    return@setOnClickListener
                }
                // Section changed => the row moves tables: delete + re-insert.
                dao.delete(listOf(existing.rowKey))
                dao.insert(section, text, font, bold, enabled)
                dialog.dismiss()
                reload()
                toast("Updated")
            } else {
                dao.update(existing.rowKey, text, font, bold, enabled)
                dialog.dismiss()
                reload()
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
