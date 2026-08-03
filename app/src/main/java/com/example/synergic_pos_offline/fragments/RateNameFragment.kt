package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.RateNameDao
import com.example.synergic_pos_offline.utils.DialogUtils

/**
 * "Rate Name" master — the per-store list of rate names used on product rates.
 * A concrete [DataTableFragment] backed by [RateNameDao] (md_rate_name).
 */
class RateNameFragment : DataTableFragment() {

    override val screenTitle = "Rate Name"

    override val columns = listOf("Rate Name")

    private val dao: RateNameDao by lazy { RateNameDao(requireContext()) }

    override fun loadRows(): MutableList<DataRow> =
        dao.getAll().map { DataRow(it.id.toString(), listOf(it.name)) }.toMutableList()

    override fun onAddRow() = showRateNameForm(null)

    override fun onEditRow(row: DataRow) = showRateNameForm(row)

    override fun onRowsDeleted(ids: Set<String>) {
        dao.delete(ids.mapNotNull { it.toLongOrNull() })
    }

    private fun showRateNameForm(existing: DataRow?) {
        DialogUtils.showForm(
            context = requireContext(),
            title = if (existing == null) "Add Rate Name" else "Edit Rate Name",
            fields = listOf(
                DialogUtils.FormField(
                    label = "Rate Name",
                    value = existing?.cells?.firstOrNull().orEmpty()
                )
            ),
            positiveText = if (existing == null) "Add" else "Update",
            mandatoryFields = listOf(0)
        ) { values ->
            val name = values.getOrNull(0)?.trim().orEmpty()
            if (name.isEmpty()) { toast("Rate name is required"); return@showForm }
            if (existing == null) {
                if (dao.insert(name) == -1L) { toast("Save failed"); return@showForm }
                toast("Added $name")
            } else {
                dao.update(existing.id.toLong(), name)
                toast("Updated $name")
            }
            refreshRows()
        }
    }
}
