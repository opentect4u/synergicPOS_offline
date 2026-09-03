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
import com.example.synergic_pos_offline.database.BillHeaderFooterDao.FontSize
import com.example.synergic_pos_offline.database.CaptionDao
import com.example.synergic_pos_offline.database.CaptionDao.Type
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * "Captions" management screen — a concrete [DataTableFragment] backed by
 * [CaptionDao] (md_captions).
 *
 * Deliberately the same screen as [BillHeaderFooterFragment], down to sharing its
 * dialog layout: a caption is a header/footer line keyed to what the slip is
 * rather than where the line sits, so anyone who can add a header can add a
 * caption without learning a second form. Only the type dropdown differs, and its
 * label is set here rather than in a second copy of the XML.
 */
class CaptionFragment : DataTableFragment() {

    override val screenTitle = "Captions"

    // Table columns. Cell layout per row: [text, type, font, status].
    override val columns = listOf("Text", "Type", "Font", "Status")

    // The Status column renders as an inline ON/OFF switch.
    override val switchColumn: Int? = COL_STATUS

    private companion object {
        const val COL_STATUS = 3
        const val MAX_PER_TYPE = 10
    }

    private val dao: CaptionDao by lazy { CaptionDao(requireContext()) }

    /** Full entries keyed by row id, for edit prefill. */
    private val entryCache = mutableMapOf<String, CaptionDao.Entry>()

    private val typeLabels = Type.values().map { it.label }
    private val fontLabels = FontSize.values().map { it.label }

    // ---- Data --------------------------------------------------------------

    override fun loadRows(): MutableList<DataRow> {
        entryCache.clear()
        val entries = dao.getAll()
        for (e in entries) entryCache[e.rowKey] = e
        return entries.map { it.toRow() }.toMutableList()
    }

    private fun CaptionDao.Entry.toRow(): DataRow = DataRow(
        rowKey,
        listOf(text, type.label, fontSize.label, if (enabled) "Enabled" else "Disabled")
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

    private fun showEntryDialog(row: DataRow?) {
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)
        val existing = row?.let { entryCache[it.id] }

        // The header/footer form, relabelled: same fields, same order, same look.
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_bill_header_footer, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val etText = view.findViewById<TextInputEditText>(R.id.etText)
        val tilType = view.findViewById<TextInputLayout>(R.id.tilSection)
        val actvType = view.findViewById<MaterialAutoCompleteTextView>(R.id.actvSection)
        val actvFont = view.findViewById<MaterialAutoCompleteTextView>(R.id.actvFontSize)
        val swBold = view.findViewById<SwitchMaterial>(R.id.swBold)
        val swEnabled = view.findViewById<SwitchMaterial>(R.id.swEnabled)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        tilType.hint = "Caption Type"
        actvType.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, typeLabels))
        actvFont.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, fontLabels))

        tvTitle.text = if (existing == null) "Add Caption" else "Edit Caption"
        etText.setText(existing?.text.orEmpty())
        actvType.setText((existing?.type ?: Type.BILL).label, false)
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
            val type = Type.fromLabel(actvType.text?.toString())
            val font = FontSize.fromLabel(actvFont.text?.toString())
            val bold = swBold.isChecked
            val enabled = swEnabled.isChecked

            // The cap is per type, so it only bites when a line is joining that type -
            // on a new caption, or on one being moved onto it.
            if ((existing == null || existing.type != type) && dao.count(type) >= MAX_PER_TYPE) {
                toast("Maximum $MAX_PER_TYPE ${type.label.lowercase()} captions allowed")
                return@setOnClickListener
            }

            if (existing == null) {
                if (dao.insert(type, text, font, bold, enabled) == null) {
                    toast("Save failed")
                    return@setOnClickListener
                }
                dialog.dismiss()
                reload()
                toast("Added")
            } else {
                dao.update(existing.rowKey, type, text, font, bold, enabled)
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
