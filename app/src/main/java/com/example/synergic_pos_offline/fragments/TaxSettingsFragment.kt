package com.example.synergic_pos_offline.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.TaxSettingsDao
import com.example.synergic_pos_offline.database.TaxSettingsDao.DiscountPosition
import com.example.synergic_pos_offline.database.TaxSettingsDao.DiscountType
import com.example.synergic_pos_offline.database.TaxSettingsDao.GstMode
import com.example.synergic_pos_offline.database.TaxSettingsDao.TaxSettings
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Tax & Discount settings, backed by [TaxSettingsDao] (md_app_settings, type 'T').
 * Discount options appear only when discount is on.
 *
 * Tax is a single on/off switch with one Inclusive/Exclusive mode. Which tax a
 * sale carries - GST or VAT - is no longer chosen here: it follows the product,
 * from whichever rate fields that product has set. See [GstCalculator.regimeOf].
 *
 * Discount position: Pre-tax is disabled on this screen - greyed out and
 * unselectable - and Post-tax is always the one checked, regardless of what an
 * older save on this store may hold. Kept as two radio buttons rather than
 * removed, so the choice can be handed back to the operator without rebuilding
 * the screen.
 */
class TaxSettingsFragment : Fragment(), TitledScreen {

    override val screenTitle = "Tax Settings"

    private val dao by lazy { TaxSettingsDao(requireContext()) }

    private lateinit var swDiscount: SwitchMaterial
    private lateinit var llDiscountOptions: View
    private lateinit var rgDiscountType: RadioGroup
    private lateinit var llDiscountPosition: View
    private lateinit var rgDiscountPosition: RadioGroup

    private lateinit var swTax: SwitchMaterial
    private lateinit var rgTaxMode: RadioGroup

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_tax_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swDiscount = view.findViewById(R.id.swDiscount)
        llDiscountOptions = view.findViewById(R.id.llDiscountOptions)
        rgDiscountType = view.findViewById(R.id.rgDiscountType)
        llDiscountPosition = view.findViewById(R.id.llDiscountPosition)
        rgDiscountPosition = view.findViewById(R.id.rgDiscountPosition)
        swTax = view.findViewById(R.id.swTax)
        rgTaxMode = view.findViewById(R.id.rgTaxMode)

        bind(dao.load())

        // Discount options visible only when discount is on.
        swDiscount.setOnCheckedChangeListener { _, on -> llDiscountOptions.isVisible = on }

        // Picking a type fixes the position with it, so only the radio moves.
        rgDiscountType.setOnCheckedChangeListener { _, _ -> syncDiscountPosition() }

        // Mode shows only when tax is on.
        swTax.setOnCheckedChangeListener { _, on -> rgTaxMode.isVisible = on }

        view.findViewById<MaterialButton>(R.id.btnSaveTax).setOnClickListener { onSave() }

        // Theme accent for switches, radios, headers, button, inputs.
        ThemeManager.applyTheme(view)
        com.example.synergic_pos_offline.utils.SettingsHighlighter.apply(
            view, arguments?.getString(com.example.synergic_pos_offline.utils.SettingsHighlighter.ARG_SETTING)
        )
    }

    private fun bind(s: TaxSettings) {
        swDiscount.isChecked = s.discountEnabled
        llDiscountOptions.isVisible = s.discountEnabled
        rgDiscountType.check(
            when (s.discountType) {
                DiscountType.ITEM_WISE -> R.id.rbTypeItem
                DiscountType.BILL_WISE -> R.id.rbTypeBill
            }
        )
        swTax.isChecked = s.taxEnabled
        rgTaxMode.isVisible = s.taxEnabled
        rgTaxMode.check(if (s.taxMode == GstMode.INCLUSIVE) R.id.rbInclusive else R.id.rbExclusive)
        // Pre-tax is disabled on this screen, so Post-tax is the only one that can
        // ever be checked here - regardless of what an older save on this store holds.
        rgDiscountPosition.check(R.id.rbPosPost)
        syncDiscountPosition()
    }

    /** Shows the Pre-tax / Post-tax block whenever discount is on - Post-tax is the
     *  only one selectable there for now, see the class doc. */
    private fun syncDiscountPosition() {
        llDiscountPosition.isVisible = swDiscount.isChecked
    }

    private fun selectedDiscountType(): DiscountType = when (rgDiscountType.checkedRadioButtonId) {
        R.id.rbTypeBill -> DiscountType.BILL_WISE
        else -> DiscountType.ITEM_WISE
    }

    private fun collect(): TaxSettings {
        val type = selectedDiscountType()
        return TaxSettings(
            discountEnabled = swDiscount.isChecked,
            discountType = type,
            // Pre-tax is disabled on this screen (see the class doc), so this is
            // always Post-tax - the radio group can never land on rbPosPre.
            discountPosition = DiscountPosition.POST_TAX,
            taxEnabled = swTax.isChecked,
            taxMode = if (rgTaxMode.checkedRadioButtonId == R.id.rbInclusive) GstMode.INCLUSIVE else GstMode.EXCLUSIVE
        )
    }

    private fun onSave() {
        dao.save(collect())
        DialogUtils.showSuccess(
            context = requireContext(),
            title = "Saved",
            message = "Tax settings saved successfully."
        )
    }
}
