package com.example.synergic_pos_offline.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.AppSettingsDao
import com.example.synergic_pos_offline.utils.BiometricLogin
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.SettingsCache
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
    private lateinit var swParcelCharge: SwitchMaterial
    private lateinit var swDirectAddToCart: SwitchMaterial
    private lateinit var swBiometricLogin: SwitchMaterial
    private lateinit var swShift: SwitchMaterial
    private lateinit var cardRestaurantSettings: View
    private lateinit var swCouponMode: SwitchMaterial
    private lateinit var swKot: SwitchMaterial
    private lateinit var swTableMerge: SwitchMaterial
    private lateinit var swTableShift: SwitchMaterial
    private lateinit var swTableSplit: SwitchMaterial

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
        swParcelCharge = view.findViewById(R.id.swParcelCharge)
        swDirectAddToCart = view.findViewById(R.id.swDirectAddToCart)
        swBiometricLogin = view.findViewById(R.id.swBiometricLogin)
        swShift = view.findViewById(R.id.swShift)
        bindBiometric(view)
        cardRestaurantSettings = view.findViewById(R.id.cardRestaurantSettings)
        swCouponMode = view.findViewById(R.id.swCouponMode)
        swKot = view.findViewById(R.id.swKot)
        swTableMerge = view.findViewById(R.id.swTableMerge)
        swTableShift = view.findViewById(R.id.swTableShift)
        swTableSplit = view.findViewById(R.id.swTableSplit)

        // The restaurant toggles only exist in Restaurant mode (md_app_settings type 'G', key Mode).
        val isRestaurant = SettingsCache.value(requireContext(), "G", "Mode") == "R"
        cardRestaurantSettings.visibility = if (isRestaurant) View.VISIBLE else View.GONE

        bind(dao.load())

        view.findViewById<MaterialButton>(R.id.btnSaveAppSettings).setOnClickListener { onSave() }

        // Theme accent for switches, header and button.
        ThemeManager.applyTheme(view)
        com.example.synergic_pos_offline.utils.SettingsHighlighter.apply(
            view, arguments?.getString(com.example.synergic_pos_offline.utils.SettingsHighlighter.ARG_SETTING)
        )
    }

    private fun bind(s: AppSettingsDao.AppSettings) {
        swManualRate.isChecked = s.manualRate
        swCashReception.isChecked = s.cashReception
        swPaymentMode.isChecked = s.paymentMode
        swOtherCharges.isChecked = s.otherCharges
        swParcelCharge.isChecked = s.parcelCharge
        swDirectAddToCart.isChecked = s.directAddToCart
        swBiometricLogin.isChecked = s.biometricLogin
        swShift.isChecked = s.shift
        swCouponMode.isChecked = s.couponMode
        swKot.isChecked = s.kot
        swTableMerge.isChecked = s.tableMerge
        swTableShift.isChecked = s.tableShift
        swTableSplit.isChecked = s.tableSplit
    }

    private fun collect(): AppSettingsDao.AppSettings = AppSettingsDao.AppSettings(
        manualRate = swManualRate.isChecked,
        cashReception = swCashReception.isChecked,
        paymentMode = swPaymentMode.isChecked,
        otherCharges = swOtherCharges.isChecked,
        parcelCharge = swParcelCharge.isChecked,
        directAddToCart = swDirectAddToCart.isChecked,
        biometricLogin = swBiometricLogin.isChecked,
        shift = swShift.isChecked,
        couponMode = swCouponMode.isChecked,
        kot = swKot.isChecked,
        tableMerge = swTableMerge.isChecked,
        tableShift = swTableShift.isChecked,
        tableSplit = swTableSplit.isChecked
    )

    /**
     * Says what the fingerprint switch will actually get you on *this* tablet.
     *
     * A device with no reader, or one with nothing enrolled on it, can have the
     * setting switched on all day and the login screen will still show only the
     * password form. Said here, where it can be acted on, rather than left to be
     * discovered at the login screen - which is the wrong moment to learn that
     * nobody has registered a fingerprint yet.
     */
    private fun bindBiometric(view: View) {
        val reason = BiometricLogin.unavailableReason(requireContext())
        view.findViewById<TextView>(R.id.tvBiometricSub).text = reason
            ?: "Offer the fingerprint reader on the login screen, beside the password"
    }

    private fun onSave() {
        val settings = collect()
        dao.save(settings)
        // Switching it off revokes rather than hides: the operator a fingerprint would
        // have signed in is forgotten, so turning it back on offers nobody until
        // somebody has signed in with a password again.
        if (!settings.biometricLogin) BiometricLogin.forget(requireContext())

        val note = when {
            !settings.biometricLogin -> ""
            BiometricLogin.unavailableReason(requireContext()) != null ->
                "\n\nFingerprint login is on, but this device cannot use it yet: " +
                    BiometricLogin.unavailableReason(requireContext())
            BiometricLogin.offeredUser(requireContext()) == null ->
                "\n\nFingerprint login is on. Sign in once with a password, and the " +
                    "fingerprint reader will be offered next time."
            else -> ""
        }
        DialogUtils.showSuccess(
            context = requireContext(),
            title = "Saved",
            message = "App settings saved successfully.$note"
        )
    }
}
