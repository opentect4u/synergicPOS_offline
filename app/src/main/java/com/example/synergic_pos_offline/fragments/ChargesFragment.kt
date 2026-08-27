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

    override val columns = listOf("Charge Name", "Value", "Status")

    private val dao: ChargeDao by lazy { ChargeDao(requireContext()) }

    override fun loadRows(): MutableList<DataRow> =
        dao.getAll().map {
            val displayValue = when (it.type) {
                ChargeDao.Type.PERCENTAGE -> "${trimPct(it.value)}%"
                ChargeDao.Type.AMOUNT -> "₹${trimPct(it.value)}"
            }
            DataRow(
                it.id.toString(),
                listOf(
                    it.name,
                    displayValue,
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
        val currentType = current?.type ?: ChargeDao.Type.PERCENTAGE
        DialogUtils.showForm(
            context = requireContext(),
            title = if (existing == null) "Add Extra Charge" else "Edit Extra Charge",
            fields = listOf(
                DialogUtils.FormField(label = "Charge Name", value = current?.name.orEmpty()),
                DialogUtils.FormField(
                    label = "Value",
                    value = current?.let { trimPct(it.value) }.orEmpty(),
                    inputType = "decimal"
                ),
                DialogUtils.FormField(
                    label = "Type",
                    value = if (currentType == ChargeDao.Type.PERCENTAGE) "Percentage" else "Amount",
                    fieldType = "dropdown",
                    options = listOf("Percentage", "Amount")
                ),
                DialogUtils.FormField(
                    label = "Enabled",
                    value = if (current?.enabled != false) "Yes" else "No",
                    fieldType = "toggle"
                )
            ),
            positiveText = if (existing == null) "Add" else "Update",
            mandatoryFields = listOf(0, 1, 2)
        ) { values ->
            val name = values.getOrNull(0)?.trim().orEmpty()
            val value = values.getOrNull(1)?.trim()?.toDoubleOrNull()
            val typeStr = values.getOrNull(2)?.trim()?.uppercase().orEmpty()
            val type = try {
                ChargeDao.Type.valueOf(typeStr)
            } catch (e: Exception) {
                if (typeStr.startsWith("A")) ChargeDao.Type.AMOUNT else ChargeDao.Type.PERCENTAGE
            }
            val enabled = values.getOrNull(3)?.equals("Yes", ignoreCase = true) ?: true
            when {
                name.isEmpty() -> { toast("Charge name is required"); return@showForm }
                value == null || value < 0.0 -> { toast("Enter a valid value"); return@showForm }
                type == ChargeDao.Type.PERCENTAGE && value > 100.0 -> { toast("Percentage cannot exceed 100%"); return@showForm }
            }
            if (existing == null) {
                if (dao.insert(name, value!!, type, enabled) == -1L) { toast("Save failed"); return@showForm }
                toast("Added $name")
            } else {
                dao.update(existing.id.toLong(), name, value!!, type, enabled)
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
