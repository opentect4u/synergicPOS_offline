package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.utils.SettingsAutoSave
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

    /**
     * The tax mode the till is actually ON - what was loaded, or what was last agreed
     * to. The radio can be moved without this moving: a change that has not been
     * confirmed yet is a button pressed, not a setting changed.
     */
    private var savedTaxMode: GstMode = GstMode.EXCLUSIVE

    /** True while the radio is being put back, so the revert is not read as a choice. */
    private var revertingTaxMode = false

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

        // SAVED AS EACH CONTROL MOVES - there is no Save button any more. The three
        // that already had listeners of their own save from inside them rather than
        // through SettingsAutoSave, which would have replaced what they were doing.
        rgDiscountType.setOnCheckedChangeListener { _, _ -> syncDiscountPosition(); onSave() }

        // Mode shows only when tax is on - and it decides whether Pre-tax is offered,
        // so the position block is re-read whenever either moves.
        swTax.setOnCheckedChangeListener { _, on ->
            rgTaxMode.isVisible = on
            syncDiscountPosition()
            onSave()
        }
        rgTaxMode.setOnCheckedChangeListener { _, _ ->
            syncDiscountPosition()
            onTaxModeChosen()
        }
        // Discount options visible only when discount is on; the position block inside
        // them is settled by the same call.
        swDiscount.setOnCheckedChangeListener { _, on ->
            llDiscountOptions.isVisible = on
            syncDiscountPosition()
            onSave()
        }
        // The one control with no listener of its own.
        SettingsAutoSave.onChange(::onSave, rgDiscountPosition)

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
        savedTaxMode = s.taxMode
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
     * Shows the Pre-tax / Post-tax block while discount is on, and decides which of
     * the two may be picked.
     *
     * ## Item-wise is a PRE-TAX discount
     *
     * A discount configured against a product comes off that product, and the line is
     * then taxed on what is left - which is what pre-tax means. So under Item wise the
     * Post-tax option is greyed and the choice settles on Pre-tax.
     *
     * Bill-wise keeps both. A figure taken off the whole bill can honestly be applied
     * before the rate or after it, and shops differ on which they mean.
     *
     * ## Pre-tax needs an exclusive price
     *
     * Under MRP the tax is already inside the price on the shelf, so there is no
     * "before tax" to take a discount off - the figure the customer sees IS the taxed
     * one. Taking a discount pre-tax there would mean stripping the tax out, cutting
     * the remainder and adding tax back on, which is not what a shop means by a
     * discount on an MRP item: they mean money off the price on the box.
     *
     * ## Where the two rules meet
     *
     * Item-wise under MRP would grey BOTH, leaving a question with no answer to it. So
     * MRP wins: it is not a preference but an arithmetic fact - the before-tax figure
     * does not exist there - while the item-wise rule is about which of two real
     * moments a shop means. Under MRP the position is Post-tax whatever the type says,
     * exactly as it was before.
     *
     * ## Greyed rather than hidden, and moved off rather than left sitting
     *
     * Greyed, because it is a choice that comes back the moment the type or the mode
     * changes - an option that vanishes reads as one that never existed. Moved off,
     * because a disabled radio can still be the CHECKED one: switching to MRP with
     * Pre-tax picked would otherwise leave the selection on a greyed button and save
     * PRE_TAX for a mode that cannot honour it.
     */
    private fun syncDiscountPosition() {
        llDiscountPosition.isVisible = swDiscount.isChecked

        val exclusive = rgTaxMode.checkedRadioButtonId != R.id.rbInclusive
        val itemwise = rgDiscountType.checkedRadioButtonId == R.id.rbTypeItem
        // MRP first - see the note above on where the two rules meet.
        val preAllowed = exclusive
        val postAllowed = !exclusive || !itemwise

        val rbPre = requireView().findViewById<android.widget.RadioButton>(R.id.rbPosPre)
        val rbPost = requireView().findViewById<android.widget.RadioButton>(R.id.rbPosPost)
        rbPre.isEnabled = preAllowed
        rbPre.alpha = if (preAllowed) 1f else 0.5f
        rbPost.isEnabled = postAllowed
        rbPost.alpha = if (postAllowed) 1f else 0.5f

        // The selection cannot be left sitting on a button that has just been greyed.
        if (!preAllowed && rgDiscountPosition.checkedRadioButtonId == R.id.rbPosPre) {
            rgDiscountPosition.check(R.id.rbPosPost)
        } else if (!postAllowed && rgDiscountPosition.checkedRadioButtonId == R.id.rbPosPost) {
            rgDiscountPosition.check(R.id.rbPosPre)
        }
    }

    /**
     * The tax mode was moved - Exclusive to MRP, or MRP to Exclusive.
     *
     * ## Why the bills have to go
     *
     * The mode is not a display choice, it is what a price MEANS. Under Exclusive a
     * listed 100.00 is 100.00 of goods with tax added on top; under MRP the same
     * 100.00 already has the tax inside it and the goods are worth 95.24. Every bill
     * on the till was priced under one of those readings and its stored figures only
     * add up under that one.
     *
     * Leave them and the books stop agreeing with themselves: the reports re-total a
     * period across two incompatible readings, a reprint states a taxable value the
     * original never had, and a return credits tax that was never charged that way.
     * There is no migration to write either - the old bills are not wrong, they are
     * simply from a shop that priced differently, and nothing can restate them.
     *
     * So the change costs the bills, in either direction, and is refused until that is
     * agreed to. Same rule the start bill number follows for the same reason.
     *
     * ## Until it is agreed to, nothing has happened
     *
     * The radio goes back to the mode still in force on cancel, so a screen that
     * refused the change does not sit there reading as though it took it. And a till
     * with no bills yet simply switches - there is nothing to lose, so there is
     * nothing to ask about.
     */
    private fun onTaxModeChosen() {
        if (revertingTaxMode) return
        val chosen = if (rgTaxMode.checkedRadioButtonId == R.id.rbInclusive)
            GstMode.INCLUSIVE else GstMode.EXCLUSIVE
        if (chosen == savedTaxMode) {
            // Not a change - some other control moved and this one only re-reported.
            onSave()
            return
        }
        // The one erase flow, shared with the Start Bill No. change and About's own
        // Erase Bills - see [BillErasePrompt]. It clears the floor with the bills,
        // which the bare clearAllBills this used to call did not.
        com.example.synergic_pos_offline.utils.BillErasePrompt.confirm(
            fragment = this,
            reason = "Changing the tax mode to ${chosen.label()} changes what every listed " +
                "price means",
            action = "change the tax mode",
            onCancelled = { revertTaxMode() }
        ) {
            savedTaxMode = chosen
            onSave()
        }
    }

    /** Puts the radio back on the mode still in force, without re-asking. */
    private fun revertTaxMode() {
        revertingTaxMode = true
        rgTaxMode.check(
            if (savedTaxMode == GstMode.INCLUSIVE) R.id.rbInclusive else R.id.rbExclusive
        )
        revertingTaxMode = false
        syncDiscountPosition()
    }

    /** What the screen calls each mode - "MRP" is the word a shopkeeper uses. */
    private fun GstMode.label(): String =
        if (this == GstMode.INCLUSIVE) "MRP" else "Exclusive"

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

    /**
     * Writes the settings as they stand - called by every control on the screen.
     *
     * No "Saved" dialog: it was the Save button's receipt, and with the button gone a
     * box on every toggle would be one to dismiss for each switch touched. The control
     * showing its new position is the confirmation.
     */
    private fun onSave() {
        dao.save(collect())
    }
}
