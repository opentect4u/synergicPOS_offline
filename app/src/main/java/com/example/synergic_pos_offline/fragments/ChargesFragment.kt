package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.synergic_pos_offline.database.AppSettingsDao
import com.example.synergic_pos_offline.database.ChargeDao
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton

/**
 * "Extra Charges" master - the shop's own additions to a bill: service, packing,
 * delivery. A concrete [DataTableFragment] backed by [ChargeDao] (md_charges), so it
 * looks and behaves like every other master.
 *
 * Three columns, because a charge is three facts: what it is called, what it takes,
 * and whether it is being taken at all. The last is a word rather than a switch in
 * the row - this table lists, and editing happens in the form - so the list still
 * says at a glance which charges a customer will actually see.
 *
 * ## Parcel Charge
 *
 * A second tab, [ChargeDao.Kind.PARCEL] - the shop's one packing/delivery charge for
 * a restaurant order, saved in this same table under the same fields as any Extra
 * Charge (see [ChargeDao]), told apart only by [ChargeDao.Kind]. Its name is fixed
 * rather than typed - see [showChargeForm] - and it can only ever be a Takeaway or
 * Dine In audience, never Both: unlike an Extra Charge, it has no meaning on a
 * grocery bill at all.
 *
 * The tab itself only shows when App Settings' "Parcel Charge" toggle is on - see
 * [buildHeaderExtra]. That toggle decides nothing about whether an already-defined
 * Parcel Charge is charged; that is its own row's Enabled switch, same as any Extra
 * Charge.
 */
class ChargesFragment : DataTableFragment() {

    override val screenTitle = "Extra Charges"

    override val columns = listOf("Charge Name", "Value", "Status")

    private val dao: ChargeDao by lazy { ChargeDao(requireContext()) }

    /** Which tab is showing - Extra Charge unless Parcel Charge's tab was tapped. */
    private var currentKind: ChargeDao.Kind = ChargeDao.Kind.EXTRA

    override fun loadRows(): MutableList<DataRow> =
        dao.getAll().filter { it.kind == currentKind }.map {
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
     * The Extra Charge / Parcel Charge tab strip, built only when App Settings'
     * Parcel Charge toggle is on - off, the container stays empty and GONE and this
     * screen behaves exactly as it always has, one table, no tabs.
     */
    override fun buildHeaderExtra(container: FrameLayout) {
        val parcelOn = runCatching { AppSettingsDao(requireContext()).load().parcelCharge }.getOrDefault(false)
        if (!parcelOn) return

        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)
        val white = Color.WHITE
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        lateinit var btnExtra: MaterialButton
        lateinit var btnParcel: MaterialButton
        fun paint() {
            val extraOn = currentKind == ChargeDao.Kind.EXTRA
            fun style(btn: MaterialButton, on: Boolean) {
                btn.backgroundTintList = ColorStateList.valueOf(if (on) accent else white)
                btn.setTextColor(if (on) white else accent)
                btn.strokeColor = ColorStateList.valueOf(accent)
            }
            style(btnExtra, extraOn)
            style(btnParcel, !extraOn)
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        btnExtra = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Extra Charge"
            isAllCaps = false
            cornerRadius = dp(10)
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (currentKind != ChargeDao.Kind.EXTRA) {
                    currentKind = ChargeDao.Kind.EXTRA
                    paint()
                    refreshRows()
                }
            }
        }
        btnParcel = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Parcel Charge"
            isAllCaps = false
            cornerRadius = dp(10)
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) }
            setOnClickListener {
                if (currentKind != ChargeDao.Kind.PARCEL) {
                    currentKind = ChargeDao.Kind.PARCEL
                    paint()
                    refreshRows()
                }
            }
        }
        row.addView(btnExtra)
        row.addView(btnParcel)
        container.addView(row)
        container.visibility = android.view.View.VISIBLE
        paint()
    }

    /**
     * Refuses a fourth EXTRA charge, and says why - or, on the Parcel Charge tab,
     * opens the shop's one Parcel Charge for editing if it already has one, since
     * there is only ever one to define.
     *
     * The limit is about the printed slip rather than the table - see
     * [ChargeDao.MAX_CHARGES]. Refused here, at the moment somebody tries to add one,
     * rather than by hiding the Add button: a greyed button with no explanation is a
     * question the operator cannot answer.
     */
    override fun onAddRow() {
        if (currentKind == ChargeDao.Kind.PARCEL) {
            val existing = dao.getAll().firstOrNull { it.kind == ChargeDao.Kind.PARCEL }
            showChargeForm(existing?.let { DataRow(it.id.toString(), emptyList()) })
            return
        }
        val extraCount = dao.getAll().count { it.kind == ChargeDao.Kind.EXTRA }
        if (extraCount >= ChargeDao.MAX_CHARGES) {
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
        val isParcel = currentKind == ChargeDao.Kind.PARCEL

        // Parcel Charge's audience is never Both - it never reaches a grocery bill,
        // so an option that would mean "grocery too" for an Extra Charge would be
        // meaningless here. A parcel row somehow left at Both (there is no way to set
        // one - defensive only) falls back to Takeaway rather than show an option
        // that is not on the list.
        val forOptions = if (isParcel) listOf("Takeaway", "Dine In", "None") else listOf("Both", "Takeaway", "Dine In", "None")
        val forValue = when (current?.applicability) {
            ChargeDao.Applicability.TAKEAWAY -> "Takeaway"
            ChargeDao.Applicability.DINE_IN -> "Dine In"
            ChargeDao.Applicability.NONE -> "None"
            ChargeDao.Applicability.BOTH -> if (isParcel) "Takeaway" else "Both"
            null -> if (isParcel) "Takeaway" else "Both"
        }

        // Field order: Name, Value, Enabled, Type, For
        val fields = listOf(
            DialogUtils.FormField(
                label = "Charge Name",
                value = if (isParcel) PARCEL_CHARGE_NAME else current?.name.orEmpty(),
                locked = isParcel
            ),
            DialogUtils.FormField(
                label = "Value",
                value = current?.let { trimPct(it.value) }.orEmpty(),
                inputType = "decimal"
            ),
            DialogUtils.FormField(
                label = "Enabled",
                value = if (current?.enabled != false) "Yes" else "No",
                fieldType = "toggle"
            ),
            DialogUtils.FormField(
                label = "Type",
                value = if (currentType == ChargeDao.Type.PERCENTAGE) "Percentage" else "Amount",
                fieldType = "dropdown",
                options = listOf("Percentage", "Amount")
            ),
            DialogUtils.FormField(
                label = "For",
                value = forValue,
                fieldType = "dropdown",
                options = forOptions
            )
        )

        DialogUtils.showForm(
            context = requireContext(),
            title = when {
                isParcel && existing == null -> "Add Parcel Charge"
                isParcel -> "Edit Parcel Charge"
                existing == null -> "Add Extra Charge"
                else -> "Edit Extra Charge"
            },
            fields = fields,
            positiveText = if (existing == null) "Add" else "Update",
            mandatoryFields = listOf(0, 1, 3)
        ) { values ->
            val name = if (isParcel) PARCEL_CHARGE_NAME else values.getOrNull(0)?.trim().orEmpty()
            val value = values.getOrNull(1)?.trim()?.toDoubleOrNull()
            val enabled = values.getOrNull(2)?.equals("Yes", ignoreCase = true) ?: true
            val typeStr = values.getOrNull(3)?.trim()?.uppercase().orEmpty()
            val type = try {
                ChargeDao.Type.valueOf(typeStr)
            } catch (e: Exception) {
                if (typeStr.startsWith("A")) ChargeDao.Type.AMOUNT else ChargeDao.Type.PERCENTAGE
            }

            val applicabilityStr = values.getOrNull(4)?.trim().orEmpty()
            val applicability = try {
                ChargeDao.Applicability.valueOf(applicabilityStr.uppercase().replace(" ", "_"))
            } catch (e: Exception) {
                when (applicabilityStr.lowercase()) {
                    "takeaway" -> ChargeDao.Applicability.TAKEAWAY
                    "dine in" -> ChargeDao.Applicability.DINE_IN
                    "both" -> ChargeDao.Applicability.BOTH
                    "none" -> ChargeDao.Applicability.NONE
                    else -> ChargeDao.Applicability.BOTH
                }
            }

            when {
                name.isEmpty() -> { toast("Charge name is required"); return@showForm }
                value == null || value < 0.0 -> { toast("Enter a valid value"); return@showForm }
                type == ChargeDao.Type.PERCENTAGE && value > 100.0 -> { toast("Percentage cannot exceed 100%"); return@showForm }
            }
            if (existing == null) {
                if (dao.insert(name, value!!, type, enabled, applicability, currentKind) == -1L) {
                    toast("Save failed"); return@showForm
                }
                toast("Added $name")
            } else {
                dao.update(existing.id.toLong(), name, value!!, type, enabled, applicability, currentKind)
                toast("Updated $name")
            }
            refreshRows()
        }
    }

    /** 5.0 prints as "5", 2.5 stays "2.5" - a rate reads as it was typed. */
    private fun trimPct(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else v.toString().trimEnd('0').trimEnd('.')

    private companion object {
        const val PARCEL_CHARGE_NAME = "Parcel Charge"
    }
}
