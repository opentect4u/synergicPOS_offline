package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.ChargeDao
import com.example.synergic_pos_offline.utils.DialogUtils

/**
 * "Extra Charges" master - the shop's own additions to a bill: service, packing,
 * delivery. A concrete [DataTableFragment] backed by [ChargeDao] (md_charges), so it
 * looks and behaves like every other master.
 *
 * Three columns, because a charge is three facts: what it is called, what it takes,
 * and whether it is being taken at all. The last is a word rather than a switch in
 * the row - this table lists, and editing happens in the form - so the list still
 * says at a glance which charges a customer will actually see.
 */
class ChargesFragment : DataTableFragment() {

    override val screenTitle = "Extra Charges"

    override val columns = listOf("Charge Name", "Percentage", "Status")

    private val dao: ChargeDao by lazy { ChargeDao(requireContext()) }

    override fun loadRows(): MutableList<DataRow> =
        dao.getAll().map {
            DataRow(
                it.id.toString(),
                listOf(
                    it.name,
                    "${trimPct(it.percentage)}%",
                    if (it.enabled) "Enabled" else "Disabled"
                )
            )
        }.toMutableList()

    /**
     * Refuses a fourth charge, and says why.
     *
     * The limit is about the printed slip rather than the table - see
     * [ChargeDao.MAX_CHARGES]. Refused here, at the moment somebody tries to add one,
     * rather than by hiding the Add button: a greyed button with no explanation is a
     * question the operator cannot answer.
     */
    override fun onAddRow() {
        if (dao.getAll().size >= ChargeDao.MAX_CHARGES) {
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Three charges is the limit",
                message = "A bill can carry up to ${ChargeDao.MAX_CHARGES} extra charges. " +
                    "Edit one of the existing charges, or delete one you no longer levy, " +
                    "to make room for another."
            )
            return
        }
        showChargeForm(null)
    }

    override fun onEditRow(row: DataRow) = showChargeForm(row)

    override fun onRowsDeleted(ids: Set<String>) {
        dao.delete(ids.mapNotNull { it.toLongOrNull() })
    }

    private fun showChargeForm(existing: DataRow?) {
        val current = existing?.id?.toLongOrNull()?.let { id -> dao.getAll().firstOrNull { it.id == id } }
        DialogUtils.showForm(
            context = requireContext(),
            title = if (existing == null) "Add Extra Charge" else "Edit Extra Charge",
            fields = listOf(
                DialogUtils.FormField(label = "Charge Name", value = current?.name.orEmpty()),
                DialogUtils.FormField(
                    label = "Percentage",
                    value = current?.let { trimPct(it.percentage) }.orEmpty(),
                    inputType = "decimal"
                ),
                // Typed rather than switched, because DialogUtils.showForm builds text
                // fields. Read leniently below: "yes", "y", "1" and "enabled" all mean
                // the same thing to somebody filling this in quickly.
                DialogUtils.FormField(
                    label = "Enabled (Yes / No)",
                    value = if (current?.enabled != false) "Yes" else "No"
                )
            ),
            positiveText = if (existing == null) "Add" else "Update",
            mandatoryFields = listOf(0, 1)
        ) { values ->
            val name = values.getOrNull(0)?.trim().orEmpty()
            val pct = values.getOrNull(1)?.trim()?.toDoubleOrNull()
            val enabled = readYesNo(values.getOrNull(2))
            when {
                name.isEmpty() -> { toast("Charge name is required"); return@showForm }
                pct == null || pct < 0.0 -> { toast("Enter a valid percentage"); return@showForm }
                // A charge over 100% is a arithmetic slip - a decimal point in the
                // wrong place - not a rate anybody levies, and it would more than
                // double the bill before anyone noticed.
                pct > 100.0 -> { toast("A charge cannot be more than 100%"); return@showForm }
            }
            if (existing == null) {
                if (dao.insert(name, pct!!, enabled) == -1L) { toast("Save failed"); return@showForm }
                toast("Added $name")
            } else {
                dao.update(existing.id.toLong(), name, pct!!, enabled)
                toast("Updated $name")
            }
            refreshRows()
        }
    }

    /** "Yes", "y", "1", "true", "enabled" - anything else is off. */
    private fun readYesNo(value: String?): Boolean {
        val v = value?.trim()?.lowercase().orEmpty()
        return v == "yes" || v == "y" || v == "1" || v == "true" || v == "enabled" || v == "on"
    }

    /** 5.0 prints as "5", 2.5 stays "2.5" - a rate reads as it was typed. */
    private fun trimPct(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else v.toString().trimEnd('0').trimEnd('.')
}
