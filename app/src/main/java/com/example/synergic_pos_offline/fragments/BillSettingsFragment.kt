package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.TextView
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
    private lateinit var swTwoCopy: SwitchMaterial
    private lateinit var etStartBillNo: TextInputEditText
    private lateinit var rgReset: RadioGroup
    private lateinit var swBillNoChar: SwitchMaterial
    private lateinit var tilPrefix: TextInputLayout
    private lateinit var etPrefix: TextInputEditText
    private lateinit var tvPreview: TextView
    private lateinit var actCustomerDetails: MaterialAutoCompleteTextView
    private lateinit var swCustomerAddress: SwitchMaterial
    private lateinit var actTotalFontSize: MaterialAutoCompleteTextView
    private lateinit var actBillFormat: MaterialAutoCompleteTextView

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
        swTwoCopy = view.findViewById(R.id.swTwoCopy)
        etStartBillNo = view.findViewById(R.id.etStartBillNo)
        rgReset = view.findViewById(R.id.rgReset)
        swBillNoChar = view.findViewById(R.id.swBillNoChar)
        tilPrefix = view.findViewById(R.id.tilPrefix)
        etPrefix = view.findViewById(R.id.etPrefix)
        tvPreview = view.findViewById(R.id.tvBillNoPreview)
        actCustomerDetails = view.findViewById(R.id.actCustomerDetails)
        swCustomerAddress = view.findViewById(R.id.swCustomerAddress)
        actTotalFontSize = view.findViewById(R.id.actTotalFontSize)
        actBillFormat = view.findViewById(R.id.actBillFormat)

        // Dropdowns (always show every option).
        actCustomerDetails.setAdapter(
            NoFilterAdapter(requireContext(), CustomerDetails.values().map { it.label })
        )
        actTotalFontSize.setAdapter(
            NoFilterAdapter(requireContext(), FontSize.values().map { it.label })
        )
        actBillFormat.setAdapter(
            NoFilterAdapter(requireContext(), BillFormat.values().map { it.label })
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

        view.findViewById<MaterialButton>(R.id.btnSaveSettings).setOnClickListener { onSave() }

        // Applies the theme accent to switches, radios, headers, button, inputs.
        ThemeManager.applyTheme(view)
    }

    private fun bind(s: BillSettings) {
        savedStartNo = s.startBillNo
        swRoundOff.isChecked = s.roundOff
        swAmountWords.isChecked = s.amountInWords
        swHsn.isChecked = s.hsnCode
        swTwoCopy.isChecked = s.twoCopyBill
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
        actBillFormat.setText(s.billFormat.label, false)
        updatePreview()
    }

    private fun collect(): BillSettings = BillSettings(
        roundOff = swRoundOff.isChecked,
        amountInWords = swAmountWords.isChecked,
        twoCopyBill = swTwoCopy.isChecked,
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
        customerDetails = CustomerDetails.fromStored(actCustomerDetails.text?.toString()) ?: CustomerDetails.ONLY_MOBILE,
        customerAddressPrinting = swCustomerAddress.isChecked,
        totalAmountFontSize = FontSize.fromStored(actTotalFontSize.text?.toString()) ?: FontSize.REGULAR,
        billFormat = BillFormat.fromStored(actBillFormat.text?.toString()) ?: BillFormat.NORMAL
    )

    /** Shows what the next bill number will look like with the current inputs. */
    private fun updatePreview() {
        val start = etStartBillNo.text?.toString()?.toIntOrNull() ?: 0
        val prefix = if (swBillNoChar.isChecked) etPrefix.text?.toString()?.trim().orEmpty().take(3) else ""
        tvPreview.text = "Next bill no.: $prefix${start + 1}"
    }

    private fun onSave() {
        val s = collect()
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
