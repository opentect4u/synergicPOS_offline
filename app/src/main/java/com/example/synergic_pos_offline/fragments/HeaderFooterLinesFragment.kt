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
 * The header/footer line management screen, for whichever document carries them - a
 * [DataTableFragment] backed by [BillHeaderFooterDao] (md_headers + md_footers).
 *
 * Each row is a printed line with text, section, font size, bold and enabled flags.
 * Add/Edit/Delete are fully persisted.
 *
 * ## One screen, two documents
 *
 * The bill and the kitchen ticket keep separate sets of lines, but the job of managing
 * them is the same job: the same columns, the same add/edit card, the same cap of ten a
 * section, the same inline enable switch. So there is one screen and the subclasses
 * supply only what actually differs - the [screenTitle] and the [type] of line.
 *
 * Copying it would have been the other way to do this, and the Bill screen and the KOT
 * screen would then have drifted apart the first time either was touched. See
 * [BillHeaderFooterFragment] and [KotHeaderFooterFragment], which are four lines each.
 */
abstract class HeaderFooterLinesFragment : DataTableFragment() {

    /** [BillHeaderFooterDao.TYPE_BILL] or [BillHeaderFooterDao.TYPE_KOT]. */
    protected abstract val type: String

    // Table columns. Cell layout per row: [text, section, font, status].
    override val columns = listOf("Text", "Section", "Font", "Status")

    // The Status column renders as an inline ON/OFF switch.
    override val switchColumn: Int? = COL_STATUS

    // A header or footer is a whole printed sentence, so a long one runs onto the
    // next line here rather than being cut off - what the row says is what the paper
    // will say, and it has to be readable without opening the row to check.
    override val wrappingColumns: Set<Int> = setOf(COL_TEXT)

    private companion object {
        const val COL_TEXT = 0
        const val COL_STATUS = 3
        const val MAX_PER_SECTION = 10
    }

    private val dao: BillHeaderFooterDao by lazy { BillHeaderFooterDao(requireContext(), type) }

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
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)
        val existing = row?.let { entryCache[it.id] }

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_bill_header_footer, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val etText = view.findViewById<TextInputEditText>(R.id.etText)
        val actvSection = view.findViewById<MaterialAutoCompleteTextView>(R.id.actvSection)
        val actvFont = view.findViewById<MaterialAutoCompleteTextView>(R.id.actvFontSize)
        val swBold = view.findViewById<SwitchMaterial>(R.id.swBold)
        val swEnabled = view.findViewById<SwitchMaterial>(R.id.swEnabled)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        actvSection.setAdapter(com.example.synergic_pos_offline.utils.Dropdowns.adapter(ctx, sectionLabels))
        actvFont.setAdapter(com.example.synergic_pos_offline.utils.Dropdowns.adapter(ctx, fontLabels))

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

/**
 * "Bill Header & Footer" - the lines printed above and below the customer's receipt.
 */
class BillHeaderFooterFragment : HeaderFooterLinesFragment() {
    override val screenTitle = "Bill Header & Footer"
    override val type = BillHeaderFooterDao.TYPE_BILL
}

/**
 * "KOT Header & Footer" - the lines printed above and below the kitchen ticket.
 *
 * A separate set from the bill's, and rightly so: the two documents go to different
 * people. A receipt footer thanking the customer for their visit has no business on a
 * ticket the kitchen reads, and "CHECK ALLERGIES" belongs only on the one going to the
 * pass.
 *
 * Restaurant mode only - a grocery till prints no kitchen ticket, so the tile and the
 * drawer entry are both hidden there (see [HeaderFooterFragment] and MainActivity's
 * menu tree).
 */
class KotHeaderFooterFragment : HeaderFooterLinesFragment() {
    override val screenTitle = "KOT Header & Footer"
    override val type = BillHeaderFooterDao.TYPE_KOT
}
