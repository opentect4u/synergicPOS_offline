package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.SectionDao
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

/**
 * "Section" master (restaurant only) — a [DataTableFragment] backed by [SectionDao]
 * (md_section). The Price List dropdown is fed from the product-rate tiers.
 */
class SectionFragment : DataTableFragment() {

    override val screenTitle = "Section"

    override val columns = listOf("Section Name", "Tables", "Price List", "Service", "Active")

    private val dao: SectionDao by lazy { SectionDao(requireContext()) }
    private val cache = mutableMapOf<String, SectionDao.Section>()
    private var priceLists: List<SectionDao.PriceList> = emptyList()

    override fun loadRows(): MutableList<DataRow> {
        cache.clear()
        priceLists = dao.priceLists()
        val nameOf = priceLists.associate { it.id to it.name }
        val sections = dao.getAll()
        for (s in sections) cache[s.id.toString()] = s
        return sections.map { s ->
            DataRow(
                s.id.toString(),
                listOf(
                    s.name,
                    s.noOfTables.toString(),
                    s.priceListId?.let { nameOf[it] } ?: "—",
                    trimNumber(s.serviceCharge),
                    if (s.isActive) "Yes" else "No"
                )
            )
        }.toMutableList()
    }

    override fun onAddRow() = showSectionDialog(null)

    override fun onEditRow(row: DataRow) = showSectionDialog(row)

    override fun onRowsDeleted(ids: Set<String>) {
        dao.delete(ids.mapNotNull { it.toLongOrNull() })
    }

    private fun showSectionDialog(row: DataRow?) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)
        val existing = row?.let { cache[it.id] }

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_section, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val etName = view.findViewById<TextInputEditText>(R.id.etSectionName)
        val etTables = view.findViewById<TextInputEditText>(R.id.etNoTables)
        val etService = view.findViewById<TextInputEditText>(R.id.etServiceCharge)
        val actPriceList = view.findViewById<MaterialAutoCompleteTextView>(R.id.actPriceList)
        val swActive = view.findViewById<SwitchMaterial>(R.id.swActive)
        val tvActiveState = view.findViewById<TextView>(R.id.tvActiveState)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        tvTitle.text = if (existing == null) "Add Section" else "Edit Section"
        etName.setText(existing?.name.orEmpty())
        etTables.setText(existing?.noOfTables?.takeIf { it != 0 }?.toString().orEmpty())
        etService.setText(existing?.let { trimNumber(it.serviceCharge) }.orEmpty())
        btnSave.text = if (existing == null) "Add" else "Update"

        // Price List dropdown from the product-rate tiers; value stored as its id.
        actPriceList.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, priceLists.map { it.name }))
        actPriceList.setOnItemClickListener { _, _, pos, _ -> actPriceList.tag = priceLists[pos].id }
        existing?.priceListId?.let { pid ->
            priceLists.firstOrNull { it.id == pid }?.let {
                actPriceList.setText(it.name, false)
                actPriceList.tag = it.id
            }
        }

        swActive.isChecked = existing?.isActive ?: true
        tvActiveState.text = if (swActive.isChecked) "Yes" else "No"
        swActive.setOnCheckedChangeListener { _, on -> tvActiveState.text = if (on) "Yes" else "No" }

        ThemeManager.applyTheme(view)
        swActive.thumbTintList = ColorStateList.valueOf(accent)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) { etName.error = "Section name is required"; return@setOnClickListener }
            val priceListId = actPriceList.tag as? Long
            if (priceListId == null) { actPriceList.error = "Price list is required"; return@setOnClickListener }

            val section = SectionDao.Section(
                id = existing?.id ?: 0L,
                name = name,
                noOfTables = etTables.text?.toString()?.toIntOrNull() ?: 0,
                priceListId = priceListId,
                serviceCharge = etService.text?.toString()?.toDoubleOrNull() ?: 0.0,
                isActive = swActive.isChecked
            )

            if (existing == null) {
                val id = dao.insert(section)
                if (id == -1L) { toast("Save failed"); return@setOnClickListener }
                dialog.dismiss()
                refreshRows()
                toast("Section added")
            } else {
                dao.update(existing.id, section)
                dialog.dismiss()
                refreshRows()
                toast("Section updated")
            }
        }

        dialog.show()
        dialog.window?.apply {
            setLayout(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(android.view.Gravity.CENTER)
        }
    }

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
