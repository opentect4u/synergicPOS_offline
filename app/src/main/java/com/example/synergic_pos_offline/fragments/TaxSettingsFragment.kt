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
 * Enforces the rules: discount options appear only when discount is on, and
 * GST / IGST / VAT are mutually exclusive.
 *
 * Discount position is no longer offered. It follows from the discount type -
 * item-wise is always pre-tax, bill-wise always post-tax - so the block stays
 * hidden whether or not a tax is on, and the value is derived on save. See
 * [TaxSettingsDao.effectivePosition].
 */
class TaxSettingsFragment : Fragment(), TitledScreen {

    override val screenTitle = "Tax Settings"

    private val dao by lazy { TaxSettingsDao(requireContext()) }

    private lateinit var swDiscount: SwitchMaterial
    private lateinit var llDiscountOptions: View
    private lateinit var rgDiscountType: RadioGroup
    private lateinit var llDiscountPosition: View
    private lateinit var rgDiscountPosition: RadioGroup

    private lateinit var swGst: SwitchMaterial
    private lateinit var rgGstMode: RadioGroup
    private lateinit var swVat: SwitchMaterial
    private lateinit var rgVatMode: RadioGroup

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
        swGst = view.findViewById(R.id.swGst)
        rgGstMode = view.findViewById(R.id.rgGstMode)
        swVat = view.findViewById(R.id.swVat)
        rgVatMode = view.findViewById(R.id.rgVatMode)

        bind(dao.load())

        // Discount options visible only when discount is on.
        swDiscount.setOnCheckedChangeListener { _, on -> llDiscountOptions.isVisible = on }

        // Picking a type fixes the position with it, so only the radio moves.
        rgDiscountType.setOnCheckedChangeListener { _, _ -> syncDiscountPosition() }

        // GST and VAT are mutually exclusive; each shows its own mode when on.
        swGst.setOnCheckedChangeListener { _, on ->
            rgGstMode.isVisible = on
            if (on) swVat.isChecked = false
        }
        swVat.setOnCheckedChangeListener { _, on ->
            rgVatMode.isVisible = on
            if (on) swGst.isChecked = false
        }

        view.findViewById<MaterialButton>(R.id.btnSaveTax).setOnClickListener { onSave() }

        // Theme accent for switches, radios, headers, button, inputs.
        ThemeManager.applyTheme(view)
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
        swGst.isChecked = s.gstEnabled
        rgGstMode.isVisible = s.gstEnabled
        rgGstMode.check(if (s.gstMode == GstMode.INCLUSIVE) R.id.rbInclusive else R.id.rbExclusive)
        swVat.isChecked = s.vatEnabled
        rgVatMode.isVisible = s.vatEnabled
        rgVatMode.check(if (s.vatMode == GstMode.INCLUSIVE) R.id.rbVatInclusive else R.id.rbVatExclusive)
        syncDiscountPosition()
    }

    /**
     * Keeps the (hidden) position radio on whatever the selected type implies, so it
     * never contradicts what is saved. The block itself stays hidden: the position is
     * no longer a choice - see [TaxSettingsDao.effectivePosition].
     */
    private fun syncDiscountPosition() {
        llDiscountPosition.isVisible = false
        val type = selectedDiscountType()
        rgDiscountPosition.check(
            when (TaxSettingsDao.effectivePosition(type)) {
                DiscountPosition.PRE_TAX -> R.id.rbPosPre
                DiscountPosition.POST_TAX -> R.id.rbPosPost
            }
        )
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
            // Fixed by the type: item-wise pre-tax, bill-wise post-tax.
            discountPosition = TaxSettingsDao.effectivePosition(type),
            gstEnabled = swGst.isChecked,
            gstMode = if (rgGstMode.checkedRadioButtonId == R.id.rbInclusive) GstMode.INCLUSIVE else GstMode.EXCLUSIVE,
            vatEnabled = swVat.isChecked,
            vatMode = if (rgVatMode.checkedRadioButtonId == R.id.rbVatInclusive) GstMode.INCLUSIVE else GstMode.EXCLUSIVE
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
