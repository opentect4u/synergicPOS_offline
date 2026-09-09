package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.utils.SettingsAutoSave
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.BillSettingsDao.BillSettings
import com.example.synergic_pos_offline.database.BillSettingsDao.BillFormat
import com.example.synergic_pos_offline.database.BillSettingsDao.CustomerDetails
import com.example.synergic_pos_offline.database.BillSettingsDao.FontSize
import com.example.synergic_pos_offline.database.BillSettingsDao.ResetMode
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.ThemeManager
import com.example.synergic_pos_offline.utils.UpiQr
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Bill Settings screen. Reads/writes the settings via [BillSettingsDao]
 * (persisted in md_app_settings). Changing the start bill number while bills
 * already exist prompts to erase all previous bills.
 */
class BillSettingsFragment : Fragment(), TitledScreen {

    override val screenTitle = "Bill Settings"

    private val dao by lazy { BillSettingsDao(requireContext()) }

    private lateinit var swRoundOff: SwitchMaterial
    private lateinit var swAmountWords: SwitchMaterial
    private lateinit var swHsn: SwitchMaterial
    private lateinit var swProductSerial: SwitchMaterial
    private lateinit var swBillTime: SwitchMaterial
    private lateinit var swTwoCopy: SwitchMaterial
    private lateinit var swCouponEnabled: SwitchMaterial
    private lateinit var llCouponSplit: View
    private lateinit var swCouponSplit: SwitchMaterial
    private lateinit var etStartBillNo: TextInputEditText
    private lateinit var rgReset: RadioGroup
    private lateinit var swBillNoChar: SwitchMaterial
    private lateinit var tilPrefix: TextInputLayout
    private lateinit var etPrefix: TextInputEditText

    // Token numbering — the same four controls for the take-away token.
    private lateinit var etStartTokenNo: TextInputEditText
    private lateinit var rgTokenReset: RadioGroup
    private lateinit var swTokenNoChar: SwitchMaterial
    private lateinit var tilTokenPrefix: TextInputLayout
    private lateinit var etTokenPrefix: TextInputEditText
    private lateinit var tvTokenPreview: TextView
    private lateinit var tvPreview: TextView
    private lateinit var actCustomerDetails: MaterialAutoCompleteTextView
    private lateinit var swCustomerAddress: SwitchMaterial
    private lateinit var actTotalFontSize: MaterialAutoCompleteTextView
    private lateinit var swUpiQr: SwitchMaterial
    private lateinit var llUpiFields: View
    private lateinit var tilUpiId: TextInputLayout
    private lateinit var etUpiId: TextInputEditText
    private lateinit var etUpiName: TextInputEditText
    private lateinit var ivUpiQrPreview: ImageView
    private lateinit var tvUpiPreviewNote: TextView
    // Bill format is no longer editable here; keep whatever was stored on save.
    private var currentFormat: BillFormat = BillFormat.CLASSIC

    /**
     * Reads the UPI ID out of a QR image the operator picked.
     *
     * Registered here rather than opened on demand because a launcher has to exist
     * before the fragment is STARTED - the same reason every other picker on this
     * app is a field.
     */
    private val pickUpiQr: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { onUpiQrPicked(it) }
        }

    /** The start number that was persisted when the screen opened. */
    private var savedStartNo = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_bill_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swRoundOff = view.findViewById(R.id.swRoundOff)
        swAmountWords = view.findViewById(R.id.swAmountWords)
        swHsn = view.findViewById(R.id.swHsn)
        swProductSerial = view.findViewById(R.id.swProductSerial)
        swBillTime = view.findViewById(R.id.swBillTime)
        swTwoCopy = view.findViewById(R.id.swTwoCopy)
        swCouponEnabled = view.findViewById(R.id.swCouponEnabled)
        llCouponSplit = view.findViewById(R.id.llCouponSplit)
        swCouponSplit = view.findViewById(R.id.swCouponSplit)
        etStartBillNo = view.findViewById(R.id.etStartBillNo)
        rgReset = view.findViewById(R.id.rgReset)
        swBillNoChar = view.findViewById(R.id.swBillNoChar)
        tilPrefix = view.findViewById(R.id.tilPrefix)
        etPrefix = view.findViewById(R.id.etPrefix)
        tvPreview = view.findViewById(R.id.tvBillNoPreview)
        etStartTokenNo = view.findViewById(R.id.etStartTokenNo)
        rgTokenReset = view.findViewById(R.id.rgTokenReset)
        swTokenNoChar = view.findViewById(R.id.swTokenNoChar)
        tilTokenPrefix = view.findViewById(R.id.tilTokenPrefix)
        etTokenPrefix = view.findViewById(R.id.etTokenPrefix)
        tvTokenPreview = view.findViewById(R.id.tvTokenNoPreview)
        actCustomerDetails = view.findViewById(R.id.actCustomerDetails)
        swCustomerAddress = view.findViewById(R.id.swCustomerAddress)
        actTotalFontSize = view.findViewById(R.id.actTotalFontSize)
        swUpiQr = view.findViewById(R.id.swUpiQr)
        llUpiFields = view.findViewById(R.id.llUpiFields)
        tilUpiId = view.findViewById(R.id.tilUpiId)
        etUpiId = view.findViewById(R.id.etUpiId)
        etUpiName = view.findViewById(R.id.etUpiName)
        ivUpiQrPreview = view.findViewById(R.id.ivUpiQrPreview)
        tvUpiPreviewNote = view.findViewById(R.id.tvUpiPreviewNote)

        // Dropdowns (always show every option).
        actCustomerDetails.setAdapter(
            NoFilterAdapter(requireContext(), CustomerDetails.values().map { it.label })
        )
        actTotalFontSize.setAdapter(
            NoFilterAdapter(requireContext(), FontSize.values().map { it.label })
        )

        bind(dao.load())

        // Prefix field visibility follows the "Bill No. Character" toggle.
        swBillNoChar.setOnCheckedChangeListener { _, on ->
            tilPrefix.isVisible = on
            updatePreview()
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = updatePreview()
            override fun afterTextChanged(s: Editable?) {}
        }
        etStartBillNo.addTextChangedListener(watcher)
        etPrefix.addTextChangedListener(watcher)

        // Token numbering, wired the same way as the bill's above.
        swTokenNoChar.setOnCheckedChangeListener { _, on ->
            tilTokenPrefix.isVisible = on
            updateTokenPreview()
        }
        val tokenWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = updateTokenPreview()
            override fun afterTextChanged(s: Editable?) {}
        }
        etStartTokenNo.addTextChangedListener(tokenWatcher)
        etTokenPrefix.addTextChangedListener(tokenWatcher)
        // The reset period changes which tokens count as already issued, so the
        // preview has to be recomputed when it moves.
        rgTokenReset.setOnCheckedChangeListener { _, _ -> updateTokenPreview() }

        // THE UPI FIELDS STAY ON SCREEN WHATEVER THE SWITCH SAYS.
        //
        // They used to fold away with it, which made one switch do two jobs: whether a
        // code is PRINTED on the bill, and whether the shop may set one up at all.
        // Those are different questions. A shop entering its UPI details had to turn
        // printing on to reach the boxes, and one that decided not to print a code on
        // every slip lost sight of details it had already saved - details that are
        // still in use, because the checkout screen shows the code from them.
        //
        // Left editable rather than greyed, for the same reason: an id can be typed,
        // corrected or re-uploaded before printing is ever switched on.
        swUpiQr.setOnCheckedChangeListener { _, _ -> updateUpiPreview() }
        val upiWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                tilUpiId.error = null
                updateUpiPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        etUpiId.addTextChangedListener(upiWatcher)
        etUpiName.addTextChangedListener(upiWatcher)
        view.findViewById<MaterialButton>(R.id.btnUploadUpiQr).setOnClickListener {
            pickUpiQr.launch("image/*")
        }

        // Splitting only means anything once coupons print at all - see
        // [applyCouponState]. Turning printing off takes splitting with it, the same
        // way Stock Alert follows Stock in General Settings: a dependent switch left
        // on but greyed out would still be "on" in the data nobody can see or change.
        applyCouponState()
        swCouponEnabled.setOnCheckedChangeListener { _, on ->
            if (!on) swCouponSplit.isChecked = false
            applyCouponState()
            autoSave()
        }

        // SAVED AS EACH CONTROL MOVES - there is no Save button any more.
        //
        // Attached after [bind], so loading the stored values is not mistaken for the
        // operator changing them. The controls that already have listeners above save
        // from inside those; only a switch's listener has to be the one listener, so
        // they are re-stated rather than added to. A text field can take a second
        // watcher, so those are simply given one.
        swBillNoChar.setOnCheckedChangeListener { _, on ->
            tilPrefix.isVisible = on
            updatePreview()
            autoSave()
        }
        swTokenNoChar.setOnCheckedChangeListener { _, on ->
            tilTokenPrefix.isVisible = on
            updateTokenPreview()
            autoSave()
        }
        rgTokenReset.setOnCheckedChangeListener { _, _ -> updateTokenPreview(); autoSave() }
        swUpiQr.setOnCheckedChangeListener { _, _ -> updateUpiPreview(); autoSave() }

        SettingsAutoSave.onChange(
            ::autoSave,
            swRoundOff, swAmountWords, swHsn, swProductSerial, swBillTime,
            swTwoCopy, swCouponSplit, swCustomerAddress,
            rgReset, actCustomerDetails, actTotalFontSize
        )
        SettingsAutoSave.onTyped(
            ::autoSave, etPrefix, etStartTokenNo, etTokenPrefix, etUpiId, etUpiName
        )

        // THE START BILL NUMBER IS NOT ONE OF THEM.
        //
        // Changing it erases every bill on the till, so it cannot be written because
        // typing paused - a half-typed "5" on the way to "500" would be a different
        // start number, and agreeing to wipe the books for it is not something anybody
        // asked. It is settled when the field is LEFT, which is the moment the operator
        // has finished saying what they meant, and it still asks before erasing
        // anything. See [onStartNoSettled].
        etStartBillNo.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onStartNoSettled() }

        // Coupon printing/splitting and the take-away token are a restaurant's own
        // settings - a grocery till has no counters to split a coupon between and
        // no take-away token to number. Hidden rather than removed: the settings
        // themselves are untouched (bind/collect still round-trip whatever is
        // stored), so a till switched from restaurant back to grocery does not lose
        // what it had, it just cannot see or change it while it is grocery.
        val isRestaurant = com.example.synergic_pos_offline.utils.SettingsCache
            .value(requireContext(), "G", "Mode") == "R"
        view.findViewById<View>(R.id.llCouponSection).isVisible = isRestaurant
        view.findViewById<View>(R.id.cardTokenNumbering).isVisible = isRestaurant

        // Applies the theme accent to switches, radios, headers, button, inputs.
        ThemeManager.applyTheme(view)
        com.example.synergic_pos_offline.utils.SettingsHighlighter.apply(
            view, arguments?.getString(com.example.synergic_pos_offline.utils.SettingsHighlighter.ARG_SETTING)
        )
    }

    private fun bind(s: BillSettings) {
        savedStartNo = s.startBillNo
        swRoundOff.isChecked = s.roundOff
        swAmountWords.isChecked = s.amountInWords
        swHsn.isChecked = s.hsnCode
        swProductSerial.isChecked = s.productSerialNumber
        swBillTime.isChecked = s.timeOnBill
        swTwoCopy.isChecked = s.twoCopyBill
        swCouponEnabled.isChecked = s.couponEnabled
        swCouponSplit.isChecked = s.couponSplit
        applyCouponState()
        etStartBillNo.setText(s.startBillNo.toString())
        swBillNoChar.isChecked = s.billNoCharEnabled
        tilPrefix.isVisible = s.billNoCharEnabled
        etPrefix.setText(s.billNoCharPrefix)
        rgReset.check(
            when (s.resetMode) {
                ResetMode.DAILY -> R.id.rbDaily
                ResetMode.MONTHLY -> R.id.rbMonthly
                ResetMode.YEARLY -> R.id.rbYearly
                ResetMode.CONTINUE -> R.id.rbContinue
            }
        )
        etStartTokenNo.setText(s.startTokenNo.toString())
        swTokenNoChar.isChecked = s.tokenNoCharEnabled
        tilTokenPrefix.isVisible = s.tokenNoCharEnabled
        etTokenPrefix.setText(s.tokenNoCharPrefix)
        rgTokenReset.check(
            when (s.tokenResetMode) {
                ResetMode.DAILY -> R.id.rbTokenDaily
                ResetMode.MONTHLY -> R.id.rbTokenMonthly
                ResetMode.YEARLY -> R.id.rbTokenYearly
                ResetMode.CONTINUE -> R.id.rbTokenContinue
            }
        )
        actCustomerDetails.setText(s.customerDetails.label, false)
        swCustomerAddress.isChecked = s.customerAddressPrinting
        actTotalFontSize.setText(s.totalAmountFontSize.label, false)
        swUpiQr.isChecked = s.upiQrEnabled
        // Always shown - see the switch listener above.
        llUpiFields.isVisible = true
        etUpiId.setText(s.upiId)
        etUpiName.setText(s.upiPayeeName)
        currentFormat = s.billFormat
        updatePreview()
        updateTokenPreview()
        updateUpiPreview()
    }

    private fun collect(): BillSettings = BillSettings(
        roundOff = swRoundOff.isChecked,
        amountInWords = swAmountWords.isChecked,
        twoCopyBill = swTwoCopy.isChecked,
        couponEnabled = swCouponEnabled.isChecked,
        couponSplit = swCouponSplit.isChecked,
        startBillNo = etStartBillNo.text?.toString()?.toIntOrNull() ?: 0,
        resetMode = when (rgReset.checkedRadioButtonId) {
            R.id.rbDaily -> ResetMode.DAILY
            R.id.rbMonthly -> ResetMode.MONTHLY
            R.id.rbYearly -> ResetMode.YEARLY
            else -> ResetMode.CONTINUE
        },
        billNoCharEnabled = swBillNoChar.isChecked,
        billNoCharPrefix = etPrefix.text?.toString()?.trim().orEmpty().take(3),
        startTokenNo = etStartTokenNo.text?.toString()?.toIntOrNull() ?: 0,
        // Daily is the fallback here, not Continue: an unreadable radio group should
        // land on what a counter actually wants, and it is the same answer the stored
        // default gives - see BillSettings.tokenResetMode.
        tokenResetMode = when (rgTokenReset.checkedRadioButtonId) {
            R.id.rbTokenContinue -> ResetMode.CONTINUE
            R.id.rbTokenMonthly -> ResetMode.MONTHLY
            R.id.rbTokenYearly -> ResetMode.YEARLY
            else -> ResetMode.DAILY
        },
        tokenNoCharEnabled = swTokenNoChar.isChecked,
        tokenNoCharPrefix = etTokenPrefix.text?.toString()?.trim().orEmpty().take(3),
        hsnCode = swHsn.isChecked,
        productSerialNumber = swProductSerial.isChecked,
        timeOnBill = swBillTime.isChecked,
        customerDetails = CustomerDetails.fromStored(actCustomerDetails.text?.toString()) ?: CustomerDetails.ONLY_MOBILE,
        customerAddressPrinting = swCustomerAddress.isChecked,
        totalAmountFontSize = FontSize.fromStored(actTotalFontSize.text?.toString()) ?: FontSize.REGULAR,
        billFormat = currentFormat,
        upiQrEnabled = swUpiQr.isChecked,
        upiId = upiIdText(),
        upiPayeeName = etUpiName.text?.toString()?.trim().orEmpty()
    )

    private fun upiIdText(): String = etUpiId.text?.toString()?.trim().orEmpty()

    /** Greys the splitting row out while coupon printing itself is off - splitting
     *  has nothing to decide until there is a coupon to split. */
    private fun applyCouponState() {
        val on = swCouponEnabled.isChecked
        llCouponSplit.setRowEnabled(on)
        swCouponSplit.isEnabled = on
    }

    /** Greys a settings row out when the flag it depends on is off. */
    private fun View.setRowEnabled(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
    }

    /** Shows what the next bill number will look like with the current inputs. */
    private fun updatePreview() {
        val start = etStartBillNo.text?.toString()?.toIntOrNull() ?: 0
        val prefix = if (swBillNoChar.isChecked) etPrefix.text?.toString()?.trim().orEmpty().take(3) else ""
        tvPreview.text = "Next bill no.: $prefix${start + 1}"
    }

    /**
     * Shows the token the next take-away order would actually take with the current
     * inputs - not start + 1.
     *
     * The two differ, and only for tokens. Under a reset period the Start No. is not
     * used at all once a token has been issued in that period: the counter carries on
     * from the highest one today. A preview that showed start + 1 under Daily would be
     * telling the operator a number the till will not hand out.
     */
    private fun updateTokenPreview() {
        val start = etStartTokenNo.text?.toString()?.toIntOrNull() ?: 0
        val prefix = if (swTokenNoChar.isChecked) etTokenPrefix.text?.toString()?.trim().orEmpty().take(3) else ""
        val mode = when (rgTokenReset.checkedRadioButtonId) {
            R.id.rbTokenContinue -> ResetMode.CONTINUE
            R.id.rbTokenMonthly -> ResetMode.MONTHLY
            R.id.rbTokenYearly -> ResetMode.YEARLY
            else -> ResetMode.DAILY
        }
        // Only the token fields matter to the count; the rest of the record is left at
        // its defaults rather than read off half-bound controls.
        val seq = runCatching {
            com.example.synergic_pos_offline.database.TokenNumberDao(requireContext()).nextSequence(
                BillSettings(
                    startTokenNo = start,
                    tokenResetMode = mode,
                    tokenNoCharEnabled = swTokenNoChar.isChecked,
                    tokenNoCharPrefix = prefix
                )
            )
        }.getOrDefault(start + 1)
        tvTokenPreview.text = "Next token no.: $prefix$seq"
    }

    /**
     * Fills the UPI ID in from a QR the shop already has, so nobody has to read a
     * payment address off a screen and retype it.
     *
     * Only the address is taken. The picture is not kept and never printed: a saved
     * QR is a static one with no amount in it, and printing it would leave the
     * customer typing the total by hand - which is the thing generating a code per
     * bill exists to avoid.
     */
    private fun onUpiQrPicked(uri: Uri) {
        val payee = UpiQr.readPayee(requireContext(), uri)
        if (payee == null) {
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "No UPI QR found",
                message = "That image does not hold a UPI payment QR. Pick the QR your " +
                    "payment app gave you, or type the UPI ID in below.",
                iconRes = android.R.drawable.ic_dialog_alert
            )
            return
        }
        etUpiId.setText(payee.vpa)
        // Only fill the name in when the code carried one and nothing is typed yet -
        // a name the operator entered is theirs, not the QR's to overwrite.
        if (payee.name.isNotBlank() && etUpiName.text?.toString().isNullOrBlank()) {
            etUpiName.setText(payee.name)
        }
        tilUpiId.error = null
        updateUpiPreview()
    }

    /**
     * Redraws the sample code under the fields.
     *
     * It is drawn for [PREVIEW_AMOUNT] rather than left blank, because the amount is
     * the point of the whole feature and the preview is where an operator can see
     * for themselves that it is carried: scan this one and the payment app opens
     * showing that figure.
     */
    private fun updateUpiPreview() {
        val vpa = upiIdText()
        if (!UpiQr.isValidVpa(vpa)) {
            ivUpiQrPreview.setImageDrawable(null)
            ivUpiQrPreview.isVisible = false
            tvUpiPreviewNote.text =
                if (vpa.isEmpty()) "Enter a UPI ID to see the code"
                else "That does not look like a UPI ID. It reads name@bank, e.g. shop@okaxis."
            return
        }
        val uri = UpiQr.payUri(
            vpa, etUpiName.text?.toString()?.trim().orEmpty(), PREVIEW_AMOUNT, "Bill 1"
        )
        val px = (PREVIEW_QR_DP * resources.displayMetrics.density).toInt()
        val bitmap = UpiQr.bitmap(uri, px)
        ivUpiQrPreview.setImageBitmap(bitmap)
        ivUpiQrPreview.isVisible = bitmap != null
        // WHAT THE NOTE SAYS FOLLOWS THE SWITCH, now that the section is on screen in
        // both states. It read "every bill prints its own code" unconditionally, which
        // was safe while these fields only appeared with printing switched on and is a
        // plain untruth beside a switch that is off.
        //
        // Off is not "this does nothing" either: the checkout screen draws its code
        // from these same details, so the setting decides paper and nothing else, and
        // the note says which.
        val sample = "Sample for \u20B9 " +
            String.format(java.util.Locale.US, "%.2f", PREVIEW_AMOUNT)
        tvUpiPreviewNote.text = when {
            bitmap == null -> "The code could not be drawn for this UPI ID."
            swUpiQr.isChecked ->
                "$sample \u2014 every bill prints its own code carrying that bill's total."
            else ->
                "$sample \u2014 not printed on bills while the switch above is off. " +
                    "It is still shown on the checkout screen to scan."
        }
    }

    /**
     * Writes the screen as it stands, silently.
     *
     * Called by every control except the start bill number - see the wiring above and
     * [onStartNoSettled] for why that one is on its own.
     *
     * No "Saved" dialog: it was the Save button's receipt, and with the button gone a
     * box on every toggle would be one to dismiss per switch touched.
     *
     * A UPI code with no payable address is a square nobody can pay into, so the switch
     * cannot be written on until there is one - the field says so and nothing is saved
     * until it is answered, exactly as the Save button used to refuse.
     */
    private fun autoSave() {
        val s = collect()
        if (s.upiQrEnabled && !UpiQr.isValidVpa(s.upiId)) {
            tilUpiId.error = "Enter a valid UPI ID, e.g. shop@okaxis"
            return
        }
        // The start number the till is ON, not the one being typed. Every other
        // setting on this screen still saves while that field is mid-edit.
        persist(if (s.startBillNo != savedStartNo) s.copy(startBillNo = savedStartNo) else s)
    }

    /**
     * The start bill number, once the operator has finished with the field.
     *
     * Changing it requires erasing the bills, so numbering can restart cleanly - which
     * is the one thing on this screen that cannot be undone, and the one thing that
     * still stops to ask. Cancelling puts the field back to the number in force, so a
     * screen that refused the change does not sit there reading as though it took it.
     */
    private fun onStartNoSettled() {
        val s = collect()
        if (s.startBillNo == savedStartNo) return
        // The one erase flow, shared with the Tax Mode change and About's own Erase
        // Bills - see [BillErasePrompt]. It says what is KEPT as well as what goes,
        // and it clears the floor with the bills, which the bare clearAllBills this
        // used to call left standing mid-service.
        com.example.synergic_pos_offline.utils.BillErasePrompt.confirm(
            fragment = this,
            reason = "Changing the start bill number restarts the numbering",
            action = "change the start bill number",
            onCancelled = { etStartBillNo.setText(savedStartNo.toString()) }
        ) {
            persist(s)
        }
    }

    private fun persist(s: BillSettings) {
        dao.save(s)
        savedStartNo = s.startBillNo
    }

    private companion object {
        /** The figure the sample code under the UPI fields is drawn for. */
        const val PREVIEW_AMOUNT = 100.0

        /** Side of the preview code, matching ivUpiQrPreview in the layout. */
        const val PREVIEW_QR_DP = 168
    }

    /** Dropdown adapter that never filters, so the full option list always shows. */
    private class NoFilterAdapter(context: android.content.Context, items: List<String>) :
        ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items.toList()) {

        private val all = items.toList()
        private val passthrough = object : android.widget.Filter() {
            override fun performFiltering(constraint: CharSequence?) =
                FilterResults().apply { values = all; count = all.size }
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
        }

        override fun getFilter(): android.widget.Filter = passthrough
    }
}
