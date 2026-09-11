package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.utils.SettingsAutoSave
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
    // Biometric Login's row is commented out (see the layout), not removed - we
    // may need it back. [storedBiometricLogin] keeps whatever value is already
    // saved surviving every other switch's autosave in the meantime, the same way
    // [storedCouponMode] does for Coupon Mode below.
    // private lateinit var swBiometricLogin: SwitchMaterial
    private var storedBiometricLogin = false
    private lateinit var swShift: SwitchMaterial
    private lateinit var cardRestaurantSettings: View
    // Coupon Mode is commented out rather than removed - we may need it back. Its
    // switch, row and autosave wiring are all commented below; [storedCouponMode]
    // keeps whatever value is already saved surviving every other switch's
    // autosave in the meantime, the same way BillSettingsFragment.currentFormat
    // carries a value this screen no longer edits.
    // private lateinit var swCouponMode: SwitchMaterial
    private lateinit var swKot: SwitchMaterial
    private lateinit var swTableMerge: SwitchMaterial
    private lateinit var swTableShift: SwitchMaterial
    private lateinit var swTableSplit: SwitchMaterial
    private var storedCouponMode = false

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
        // swBiometricLogin = view.findViewById(R.id.swBiometricLogin)
        swShift = view.findViewById(R.id.swShift)
        // bindBiometric(view)
        cardRestaurantSettings = view.findViewById(R.id.cardRestaurantSettings)
        // swCouponMode = view.findViewById(R.id.swCouponMode)
        swKot = view.findViewById(R.id.swKot)
        swTableMerge = view.findViewById(R.id.swTableMerge)
        swTableShift = view.findViewById(R.id.swTableShift)
        swTableSplit = view.findViewById(R.id.swTableSplit)

        // The restaurant toggles only exist in Restaurant mode (md_app_settings type 'G', key Mode).
        val isRestaurant = SettingsCache.value(requireContext(), "G", "Mode") == "R"
        cardRestaurantSettings.visibility = if (isRestaurant) View.VISIBLE else View.GONE

        bind(dao.load())

        // SAVED AS EACH SWITCH IS FLIPPED - there is no Save button any more.
        //
        // Attached after [bind], so loading the stored values does not read as the
        // operator changing them and write straight back over what was just read.
        SettingsAutoSave.onChange(
            ::onSave,
            swManualRate, swOtherCharges, swParcelCharge,
            swDirectAddToCart, /* swBiometricLogin, */ swShift,
            /* swCouponMode, */ swKot, swTableMerge, swTableShift, swTableSplit
        )
        // NOT IN THE LIST ABOVE. These two carry a listener of their own - see
        // [bindExclusive] - and SettingsAutoSave.onChange replaces whatever listener a
        // switch already has, so passing them here would silently undo the pairing.
        // They save from inside their own listener instead.
        bindExclusive()

        // Theme accent for switches, header and button.
        ThemeManager.applyTheme(view)
        com.example.synergic_pos_offline.utils.SettingsHighlighter.apply(
            view, arguments?.getString(com.example.synergic_pos_offline.utils.SettingsHighlighter.ARG_SETTING)
        )
    }

    /**
     * Cash Reception and Payment Mode are one choice, not two switches.
     *
     * Cash Reception collects the money on the BILLING screen; Payment Mode asks how
     * the sale is being settled at CHECKOUT. Both on is a till that takes the cash up
     * front and then asks which way it was paid, so turning either on turns the other
     * off - which is what the pair had to be operated as anyway, from memory, with
     * nothing on screen to say so.
     *
     * ## The guard
     *
     * Clearing the other switch fires ITS listener, which would clear this one
     * straight back and land both off - so the flag holds that second pass off, and
     * the save runs once from whichever switch the operator actually touched.
     */
    private var syncingExclusive = false

    private fun bindExclusive() {
        fun pair(touched: com.google.android.material.switchmaterial.SwitchMaterial,
                 other: com.google.android.material.switchmaterial.SwitchMaterial) {
            touched.setOnCheckedChangeListener { _, isChecked ->
                if (syncingExclusive) return@setOnCheckedChangeListener
                if (isChecked && other.isChecked) {
                    syncingExclusive = true
                    other.isChecked = false
                    syncingExclusive = false
                }
                onSave()
            }
        }
        pair(swCashReception, swPaymentMode)
        pair(swPaymentMode, swCashReception)
    }

    private fun bind(s: AppSettingsDao.AppSettings) {
        swManualRate.isChecked = s.manualRate
        swCashReception.isChecked = s.cashReception
        // A till stored with BOTH on - every one of them, until the two became a
        // pair - is settled here rather than left showing a combination the screen
        // will not let anybody set. Cash Reception wins: it is the one that changes
        // how the billing screen itself behaves, so it is the harder of the two to
        // have turned on by accident. Written back on the next flip like any other
        // change; nothing is saved merely for opening the screen.
        swPaymentMode.isChecked = s.paymentMode && !s.cashReception
        swOtherCharges.isChecked = s.otherCharges
        swParcelCharge.isChecked = s.parcelCharge
        swDirectAddToCart.isChecked = s.directAddToCart
        // swBiometricLogin.isChecked = s.biometricLogin
        storedBiometricLogin = s.biometricLogin
        swShift.isChecked = s.shift
        // swCouponMode.isChecked = s.couponMode
        storedCouponMode = s.couponMode
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
        // biometricLogin = swBiometricLogin.isChecked,
        biometricLogin = storedBiometricLogin,
        shift = swShift.isChecked,
        // couponMode = swCouponMode.isChecked,
        couponMode = storedCouponMode,
        kot = swKot.isChecked,
        tableMerge = swTableMerge.isChecked,
        tableShift = swTableShift.isChecked,
        tableSplit = swTableSplit.isChecked
    )

    // bindBiometric and the fingerprint-specific half of onSave are commented out
    // with the row they served - see storedBiometricLogin's own note. Nothing on
    // this now-hidden screen changes biometricLogin any more, so there is nothing
    // for either to say or act on: forget()-on-off and the "just turned on" note
    // both only ever fired off a switch this screen no longer shows.
    //
    // /**
    //  * Says what the fingerprint switch will actually get you on *this* tablet.
    //  *
    //  * A device with no reader, or one with nothing enrolled on it, can have the
    //  * setting switched on all day and the login screen will still show only the
    //  * password form. Said here, where it can be acted on, rather than left to be
    //  * discovered at the login screen - which is the wrong moment to learn that
    //  * nobody has registered a fingerprint yet.
    //  */
    // private fun bindBiometric(view: View) {
    //     val reason = BiometricLogin.unavailableReason(requireContext())
    //     view.findViewById<TextView>(R.id.tvBiometricSub).text = reason
    //         ?: "Offer the fingerprint reader on the login screen, beside the password"
    // }

    /**
     * Writes the switches as they stand. Called by every switch on the screen - see
     * [SettingsAutoSave].
     *
     * NO "SAVED" DIALOG. It was the Save button's receipt, and with the button gone a
     * dialog on every flip would be a box to dismiss for each switch touched. The
     * switch showing its new position is the confirmation.
     */
    private fun onSave() {
        dao.save(collect())
        // if (!settings.biometricLogin) {
        //     BiometricLogin.forget(requireContext())
        //     return
        // }
        // val note = when {
        //     BiometricLogin.unavailableReason(requireContext()) != null ->
        //         "Fingerprint login is on, but this device cannot use it yet: " +
        //             BiometricLogin.unavailableReason(requireContext())
        //     BiometricLogin.offeredUser(requireContext()) == null ->
        //         "Fingerprint login is on. Sign in once with a password, and the " +
        //             "fingerprint reader will be offered next time."
        //     else -> null
        // }
        // if (note != null && !biometricNoteShown) {
        //     biometricNoteShown = true
        //     android.widget.Toast.makeText(requireContext(), note, android.widget.Toast.LENGTH_LONG).show()
        // }
    }

    // /** Whether the fingerprint caveat has already been said this visit. */
    // private var biometricNoteShown = false
}
