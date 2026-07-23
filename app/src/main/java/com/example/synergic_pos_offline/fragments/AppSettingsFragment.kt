package com.example.synergic_pos_offline.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.AppSettingsDao
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * App Settings screen, backed by [AppSettingsDao] (md_app_settings, type 'A').
 * Simple ON/OFF toggles for Manual Rate, Cash Reception, Payment Mode and
 * Other Charges.
 */
class AppSettingsFragment : Fragment(), TitledScreen {

    override val screenTitle = "App Settings"

    private val dao: AppSettingsDao by lazy { AppSettingsDao(requireContext()) }

    private lateinit var swManualRate: SwitchMaterial
    private lateinit var swCashReception: SwitchMaterial
    private lateinit var swPaymentMode: SwitchMaterial
    private lateinit var swOtherCharges: SwitchMaterial

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_app_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swManualRate = view.findViewById(R.id.swManualRate)
        swCashReception = view.findViewById(R.id.swCashReception)
        swPaymentMode = view.findViewById(R.id.swPaymentMode)
        swOtherCharges = view.findViewById(R.id.swOtherCharges)

        bind(dao.load())

        view.findViewById<MaterialButton>(R.id.btnSaveAppSettings).setOnClickListener { onSave() }

        // Theme accent for switches, header and button.
        ThemeManager.applyTheme(view)
    }

    private fun bind(s: AppSettingsDao.AppSettings) {
        swManualRate.isChecked = s.manualRate
        swCashReception.isChecked = s.cashReception
        swPaymentMode.isChecked = s.paymentMode
        swOtherCharges.isChecked = s.otherCharges
    }

    private fun collect(): AppSettingsDao.AppSettings = AppSettingsDao.AppSettings(
        manualRate = swManualRate.isChecked,
        cashReception = swCashReception.isChecked,
        paymentMode = swPaymentMode.isChecked,
        otherCharges = swOtherCharges.isChecked
    )

    private fun onSave() {
        dao.save(collect())
        DialogUtils.showSuccess(
            context = requireContext(),
            title = "Saved",
            message = "App settings saved successfully."
        )
    }
}
