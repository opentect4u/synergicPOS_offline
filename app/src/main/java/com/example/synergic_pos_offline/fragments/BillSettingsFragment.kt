package com.example.synergic_pos_offline.fragments

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
import androidx.appcompat.app.AlertDialog
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
    private lateinit var swCouponSplit: SwitchMaterial
    private lateinit var etStartBillNo: TextInputEditText
    private lateinit var rgReset: RadioGroup
    private lateinit var swBillNoChar: SwitchMaterial
    private lateinit var tilPrefix: TextInputLayout
    private lateinit var etPrefix: TextInputEditText
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
        swCouponSplit = view.findViewById(R.id.swCouponSplit)
        etStartBillNo = view.findViewById(R.id.etStartBillNo)
        rgReset = view.findViewById(R.id.rgReset)
        swBillNoChar = view.findViewById(R.id.swBillNoChar)
        tilPrefix = view.findViewById(R.id.tilPrefix)
        etPrefix = view.findViewById(R.id.etPrefix)
        tvPreview = view.findViewById(R.id.tvBillNoPreview)
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

        // The UPI fields only mean anything once the QR is switched on.
        swUpiQr.setOnCheckedChangeListener { _, on ->
            llUpiFields.isVisible = on
            if (on) updateUpiPreview()
        }
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

        view.findViewById<MaterialButton>(R.id.btnSaveSettings).setOnClickListener { onSave() }

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
        swCouponSplit.isChecked = s.couponSplit
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
        actCustomerDetails.setText(s.customerDetails.label, false)
        swCustomerAddress.isChecked = s.customerAddressPrinting
        actTotalFontSize.setText(s.totalAmountFontSize.label, false)
        swUpiQr.isChecked = s.upiQrEnabled
        llUpiFields.isVisible = s.upiQrEnabled
        etUpiId.setText(s.upiId)
        etUpiName.setText(s.upiPayeeName)
        currentFormat = s.billFormat
        updatePreview()
        updateUpiPreview()
    }

    private fun collect(): BillSettings = BillSettings(
        roundOff = swRoundOff.isChecked,
        amountInWords = swAmountWords.isChecked,
        twoCopyBill = swTwoCopy.isChecked,
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

    /** Shows what the next bill number will look like with the current inputs. */
    private fun updatePreview() {
        val start = etStartBillNo.text?.toString()?.toIntOrNull() ?: 0
        val prefix = if (swBillNoChar.isChecked) etPrefix.text?.toString()?.trim().orEmpty().take(3) else ""
        tvPreview.text = "Next bill no.: $prefix${start + 1}"
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
        tvUpiPreviewNote.text = if (bitmap == null) {
            "The code could not be drawn for this UPI ID."
        } else {
            "Sample for \u20B9 " + String.format(java.util.Locale.US, "%.2f", PREVIEW_AMOUNT) +
                " \u2014 every bill prints its own code carrying that bill's total."
        }
    }

    private fun onSave() {
        val s = collect()
        // A QR built without a payable address is a square nobody can pay into, so
        // the setting cannot be switched on until there is one.
        if (s.upiQrEnabled && !UpiQr.isValidVpa(s.upiId)) {
            tilUpiId.error = "Enter a valid UPI ID, e.g. shop@okaxis"
            etUpiId.requestFocus()
            return
        }
        // Changing the start number when bills exist requires erasing them.
        if (s.startBillNo != savedStartNo && dao.hasBills()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Erase existing bills?")
                .setMessage(
                    "Changing the start bill number requires deleting all previous bills " +
                        "so numbering can restart cleanly. This cannot be undone."
                )
                .setPositiveButton("Erase & Save") { _, _ ->
                    dao.clearAllBills()
                    persist(s)
                }
                .setNegativeButton("Cancel", null)
                .create()
                .also { it.setCanceledOnTouchOutside(false); it.show() }
        } else {
            persist(s)
        }
    }

    private fun persist(s: BillSettings) {
        dao.save(s)
        savedStartNo = s.startBillNo
        DialogUtils.showSuccess(
            context = requireContext(),
            title = "Saved",
            message = "Bill settings saved successfully."
        )
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
