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
 * Tax is a single on/off switch with one Exclusive/MRP mode. "MRP" is what the
 * screen calls [GstMode.INCLUSIVE] - a price with the tax already inside it is the
 * MRP, which is the word on the box and the word a shopkeeper uses. The stored
 * value and the id (rbInclusive) are unchanged; only the label is.
 *
 * Which tax a sale carries - GST or VAT - is no longer chosen here: it follows the
 * product, from whichever rate fields that product has set. See
 * [GstCalculator.regimeOf].
 *
 * Discount position: Pre-tax is offered only under EXCLUSIVE tax. Under MRP the tax
 * is already in the price, so there is no before-tax figure to discount, and the
 * option is greyed - see [syncDiscountPosition]. It was disabled outright until now,
 * with Post-tax pinned whatever was saved; both radios are live again, bounded by
 * the mode.
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

        rgDiscountType.setOnCheckedChangeListener { _, _ -> syncDiscountPosition() }

        // Mode shows only when tax is on - and it decides whether Pre-tax is offered,
        // so the position block is re-read whenever either moves.
        swTax.setOnCheckedChangeListener { _, on ->
            rgTaxMode.isVisible = on
            syncDiscountPosition()
        }
        rgTaxMode.setOnCheckedChangeListener { _, _ -> syncDiscountPosition() }
        // Discount options visible only when discount is on; the position block inside
        // them is settled by the same call.
        swDiscount.setOnCheckedChangeListener { _, on ->
            llDiscountOptions.isVisible = on
            syncDiscountPosition()
        }

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
        // The saved position is restored now that Pre-tax can be chosen. syncDiscount-
        // Position runs straight after and moves it to Post-tax if the mode it was
        // saved under no longer allows it.
        rgDiscountPosition.check(
            if (s.discountPosition == DiscountPosition.PRE_TAX) R.id.rbPosPre else R.id.rbPosPost
        )
        syncDiscountPosition()
    }

    /**
     * Shows the Pre-tax / Post-tax block while discount is on, and decides whether
     * Pre-tax may be picked at all.
     *
     * ## Pre-tax needs an exclusive price
     *
     * Under MRP the tax is already inside the price on the shelf, so there is no
     * "before tax" to take a discount off - the figure the customer sees IS the taxed
     * one. Taking a discount pre-tax there would mean stripping the tax out, cutting
     * the remainder and adding tax back on, which is not what a shop means by a
     * discount on an MRP item: they mean money off the price on the box.
     *
     * Exclusive prices have both moments, so the choice is real and offered.
     *
     * ## Greyed rather than hidden, and moved off rather than left sitting
     *
     * Greyed, because it is a choice that comes back the moment the mode changes -
     * an option that vanishes reads as one that never existed. Moved off, because a
     * disabled radio can still be the CHECKED one: switching from Exclusive with
     * Pre-tax picked to MRP would otherwise leave the selection on a greyed button and
     * save PRE_TAX for a mode that cannot honour it.
     */
    private fun syncDiscountPosition() {
        llDiscountPosition.isVisible = swDiscount.isChecked

        val exclusive = rgTaxMode.checkedRadioButtonId != R.id.rbInclusive
        val rbPre = requireView().findViewById<android.widget.RadioButton>(R.id.rbPosPre)
        rbPre.isEnabled = exclusive
        rbPre.alpha = if (exclusive) 1f else 0.5f
        if (!exclusive && rgDiscountPosition.checkedRadioButtonId == R.id.rbPosPre) {
            rgDiscountPosition.check(R.id.rbPosPost)
        }
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
            // What is actually checked. This was pinned to POST_TAX while Pre-tax was
            // disabled outright; it is a real choice now, and pinning it would throw
            // away the one the operator just made.
            //
            // It cannot arrive as PRE_TAX under MRP: syncDiscountPosition moves the
            // selection off Pre-tax whenever the mode stops allowing it, so the two can
            // never be saved together.
            discountPosition = if (rgDiscountPosition.checkedRadioButtonId == R.id.rbPosPre)
                DiscountPosition.PRE_TAX else DiscountPosition.POST_TAX,
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
