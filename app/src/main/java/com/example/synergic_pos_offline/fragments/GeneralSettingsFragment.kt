package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.utils.SettingsAutoSave
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.database.GeneralSettingsDao.GeneralSettings
import com.example.synergic_pos_offline.database.UserDao
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.SettingsCache
import com.example.synergic_pos_offline.utils.ThemeManager
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import androidx.core.view.isVisible
import com.example.synergic_pos_offline.database.GeneralSettingsDao.ItemRate
import com.example.synergic_pos_offline.database.GeneralSettingsDao.LandingScreen
import com.example.synergic_pos_offline.database.GeneralSettingsDao.Mode
import com.example.synergic_pos_offline.database.GeneralSettingsDao.ProductSort
import com.example.synergic_pos_offline.database.GeneralSettingsDao.ReturnMode
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

/**
 * General Settings screen: change the signed-in user's password and toggle the
 * Sale Return feature (persisted via [GeneralSettingsDao], md_app_settings type 'G').
 */
class GeneralSettingsFragment : Fragment(), TitledScreen {

    override val screenTitle = "General Settings"

    private val dao by lazy { GeneralSettingsDao(requireContext()) }
    private val userDao by lazy { UserDao(requireContext()) }

    private lateinit var actMode: MaterialAutoCompleteTextView
    private lateinit var swSaleReturn: SwitchMaterial
    private lateinit var llReturnMode: View
    private lateinit var rgReturnMode: RadioGroup
    private lateinit var llSaleReturnDays: View
    private lateinit var etSaleReturnDays: TextInputEditText
    private lateinit var swLastBillStatus: SwitchMaterial
    private lateinit var swQuantityStatus: SwitchMaterial
    // Customer Info's row is commented out (see the layout), not removed - we may
    // need it back. [storedCustomerInfo] keeps whatever value is already saved
    // surviving every other switch's autosave in the meantime, the same way
    // AppSettingsFragment.storedCouponMode carries a value its own screen no
    // longer edits.
    // private lateinit var swCustomerInfo: SwitchMaterial
    private var storedCustomerInfo = false
    private lateinit var rgItemRate: RadioGroup
    private lateinit var actProductSort: MaterialAutoCompleteTextView
    private lateinit var rgLandingScreen: RadioGroup
    private lateinit var swStockFlag: SwitchMaterial
    private lateinit var llStockAlert: View
    private lateinit var swStockAlert: SwitchMaterial
    private lateinit var llStockAlertQty: View
    private lateinit var swNegativeStock: SwitchMaterial
    private lateinit var llNegativeStock: View
    private lateinit var tilStockAlertQty: View
    private lateinit var etStockAlertQty: TextInputEditText

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_general_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        actMode = view.findViewById(R.id.actMode)
        swSaleReturn = view.findViewById(R.id.swSaleReturn)
        llReturnMode = view.findViewById(R.id.llReturnMode)
        rgReturnMode = view.findViewById(R.id.rgReturnMode)
        llSaleReturnDays = view.findViewById(R.id.llSaleReturnDays)
        etSaleReturnDays = view.findViewById(R.id.etSaleReturnDays)
        swLastBillStatus = view.findViewById(R.id.swLastBillStatus)
        swQuantityStatus = view.findViewById(R.id.swQuantityStatus)
        // swCustomerInfo = view.findViewById(R.id.swCustomerInfo)
        rgItemRate = view.findViewById(R.id.rgItemRate)
        actProductSort = view.findViewById(R.id.actProductSort)
        rgLandingScreen = view.findViewById(R.id.rgLandingScreen)
        swStockFlag = view.findViewById(R.id.swStockFlag)
        llStockAlert = view.findViewById(R.id.llStockAlert)
        swStockAlert = view.findViewById(R.id.swStockAlert)
        llStockAlertQty = view.findViewById(R.id.llStockAlertQty)
        swNegativeStock = view.findViewById(R.id.swNegativeStock)
        llNegativeStock = view.findViewById(R.id.llNegativeStock)
        tilStockAlertQty = view.findViewById(R.id.tilStockAlertQty)
        etStockAlertQty = view.findViewById(R.id.etStockAlertQty)

        val s = dao.load()
        // Section access is an admin-only control: only an admin sees or sets it.
        swLastBillStatus.isChecked = s.lastBillStatus
        swQuantityStatus.isChecked = s.quantityStatus
        // swCustomerInfo.isChecked = s.customerInfo
        storedCustomerInfo = s.customerInfo
        rgItemRate.check(
            if (s.itemRate == ItemRate.MULTIPLE) R.id.rbItemRateMultiple else R.id.rbItemRateSingle
        )
        rgLandingScreen.check(
            if (s.landingScreen == LandingScreen.HOME) R.id.rbLandingHome else R.id.rbLandingSale
        )

        // Mode dropdown (always shows every option). Displays labels; stores G / R.
        actMode.setAdapter(NoFilterAdapter(requireContext(), Mode.entries.map { it.label }))
        actMode.setText(s.mode.label, false)

        // Product Sorting dropdown (always shows every option). Displays labels;
        // stores the short code.
        actProductSort.setAdapter(NoFilterAdapter(requireContext(), ProductSort.entries.map { it.label }))
        actProductSort.setText(s.productSort.label, false)

        swSaleReturn.isChecked = s.saleReturn
        rgReturnMode.check(
            if (s.returnMode == ReturnMode.ITEM_WISE) R.id.rbReturnItemWise else R.id.rbReturnBillWise
        )
        etSaleReturnDays.setText(if (s.saleReturn) s.saleReturnDays.toString() else "")

        // The return type only exists while Sale Return is on, and the days limit
        // only within bill-wise - an item-wise return has no bill to date from, so
        // there is nothing for a limit to measure against.
        fun applyReturnState() {
            val on = swSaleReturn.isChecked
            val billWise = rgReturnMode.checkedRadioButtonId != R.id.rbReturnItemWise
            llReturnMode.isVisible = on
            llSaleReturnDays.isVisible = on && billWise
        }
        applyReturnState()
        swSaleReturn.setOnCheckedChangeListener { _, _ -> applyReturnState() }
        rgReturnMode.setOnCheckedChangeListener { _, _ -> applyReturnState() }

        // ---- Stock: each flag only opens the one below it ----------------------
        swStockFlag.isChecked = s.stockFlag
        swStockAlert.isChecked = s.stockFlag && s.stockAlert
        swNegativeStock.isChecked = s.negativeStock
        etStockAlertQty.setText(
            if (swStockAlert.isChecked) s.stockAlertQty.toString() else ""
        )

        // The alert has nothing to watch without stock tracking, and the quantity
        // has nothing to bound without the alert - so each stays visible but
        // greyed out until its parent is on, and switching a parent off clears
        // what depends on it rather than leaving a value that no longer applies.
        fun applyStockState() {
            val stockOn = swStockFlag.isChecked
            val alertOn = stockOn && swStockAlert.isChecked
            llStockAlert.setRowEnabled(stockOn)
            swStockAlert.isEnabled = stockOn
            llStockAlertQty.setRowEnabled(alertOn)
            tilStockAlertQty.isEnabled = alertOn
            etStockAlertQty.isEnabled = alertOn
            // Nothing to go negative when no count is kept.
            llNegativeStock.setRowEnabled(stockOn)
            swNegativeStock.isEnabled = stockOn
        }
        applyStockState()
        swStockFlag.setOnCheckedChangeListener { _, on ->
            if (!on) swStockAlert.isChecked = false
            applyStockState()
        }
        swStockAlert.setOnCheckedChangeListener { _, on ->
            if (!on) etStockAlertQty.setText("")
            applyStockState()
        }

        view.findViewById<MaterialButton>(R.id.btnChangePassword).setOnClickListener {
            showChangePasswordDialog()
        }
        // The settings as the screen currently stands. Read on every change now that
        // there is no Save button to gather them at one moment - see [SettingsAutoSave].
        fun collect(): GeneralSettings {
            val returnMode = if (rgReturnMode.checkedRadioButtonId == R.id.rbReturnItemWise)
                ReturnMode.ITEM_WISE else ReturnMode.BILL_WISE
            val daysApply = swSaleReturn.isChecked && returnMode == ReturnMode.BILL_WISE
            val days = if (daysApply) etSaleReturnDays.text?.toString()?.toIntOrNull() ?: 0 else 0
            val modeVal = Mode.fromStored(actMode.text?.toString()) ?: Mode.GROCERY

            val isMultipleRate = rgItemRate.checkedRadioButtonId == R.id.rbItemRateMultiple
            val itemRateVal = if (isMultipleRate) ItemRate.MULTIPLE else ItemRate.SINGLE

            val productSortVal = ProductSort.fromStored(actProductSort.text?.toString())
                ?: ProductSort.SERIAL_ASC

            val isLandingHome = rgLandingScreen.checkedRadioButtonId == R.id.rbLandingHome
            val landingScreenVal = if (isLandingHome) LandingScreen.HOME else LandingScreen.SALE

            val alertApply = swStockFlag.isChecked && swStockAlert.isChecked
            val alertQty =
                if (alertApply) etStockAlertQty.text?.toString()?.toIntOrNull() ?: 0 else 0

            return GeneralSettings(
                mode = modeVal,
                saleReturn = swSaleReturn.isChecked,
                returnMode = returnMode,
                saleReturnDays = days,
                lastBillStatus = swLastBillStatus.isChecked,
                quantityStatus = swQuantityStatus.isChecked,
                itemRate = itemRateVal,
                productSort = productSortVal,
                // customerInfo = swCustomerInfo.isChecked,
                customerInfo = storedCustomerInfo,
                landingScreen = landingScreenVal,
                stockFlag = swStockFlag.isChecked,
                stockAlert = alertApply,
                stockAlertQty = alertQty,
                negativeStock = swNegativeStock.isChecked,
                // Access is granted per user now, on the Add/Edit User form. These are
                // carried through untouched so an older till's stored flags are not
                // wiped by a save from a screen that no longer shows them.
                accessMaster = s.accessMaster,
                accessSettings = s.accessSettings,
                accessReports = s.accessReports,
                accessAboutApp = s.accessAboutApp
            )
        }

        /**
         * Writes the screen as it stands. Every control calls this; the Mode dropdown
         * does not.
         *
         * MODE IS NOT SAVED FROM HERE. Changing it erases every product, bill and
         * table on the till, so it is not something a screen may do because a switch
         * three cards below it was flipped. It has a flow of its own - password, then
         * the erase warning - and until that has been agreed to, the mode written is
         * the mode already stored.
         */
        fun autoSave() {
            val settings = collect()
            if (settings.mode != dao.load().mode) return
            dao.save(settings)
        }

        /**
         * The Mode dropdown, which is the one destructive control on this screen.
         *
         * Asked as soon as it is chosen rather than at a Save press, because there is
         * no Save press any more - the choice IS the request. Cancelling anywhere in
         * the flow puts the dropdown back to the mode still in force, so a screen that
         * refused to switch does not sit there reading as though it had.
         */
        fun onModeChosen() {
            val settings = collect()
            val currentMode = dao.load().mode
            val modeVal = settings.mode
            if (modeVal == currentMode) return
            promptPasswordThenSwitch(modeVal) {
                DialogUtils.showConfirm(
                    context = requireContext(),
                    title = "Switch to ${modeVal.label}?",
                    message = "Changing the mode will erase all current data - products, categories, " +
                        "sections, tables, waiters, bills, KOTs, payments, sale returns and running " +
                        "orders. This cannot be undone.",
                    positiveText = "Erase & Switch",
                    negativeText = "Cancel",
                    destructive = true,
                    onCancel = { actMode.setText(currentMode.label, false) },
                    onConfirm = {
                        DatabaseHelper.getInstance(requireContext()).eraseBusinessDataForModeChange()
                        dao.save(settings)
                        if (modeVal == Mode.RESTAURANT) enableRestaurantDefaults()
                        // The menus and the landing screen read the cache, so it
                        // has to know about the new mode before the next sign-in.
                        SettingsCache.storeFromDb(requireContext())
                        DialogUtils.showSuccess(
                            context = requireContext(),
                            title = "Mode changed",
                            message = "Switched to ${modeVal.label}. All previous data was " +
                                "erased. You will be signed out - sign back in and the till " +
                                "opens in ${modeVal.label} mode.",
                            buttonText = "Sign out"
                        ) { signOut() }
                    }
                )
            }
        }

        // SAVED AS EACH CONTROL MOVES - there is no Save button any more. Attached
        // after everything above has loaded its value, so the load is not mistaken for
        // a change and written straight back.
        //
        // The two with listeners of their own keep them and save from inside; the Mode
        // dropdown is wired to its own flow rather than to autoSave.
        swSaleReturn.setOnCheckedChangeListener { _, _ -> applyReturnState(); autoSave() }
        rgReturnMode.setOnCheckedChangeListener { _, _ -> applyReturnState(); autoSave() }
        actMode.setOnItemClickListener { _, _, _, _ -> onModeChosen() }
        SettingsAutoSave.onChange(
            ::autoSave,
            swLastBillStatus, swQuantityStatus, /* swCustomerInfo, */ swNegativeStock,
            rgItemRate, rgLandingScreen, actProductSort
        )
        SettingsAutoSave.onTyped(::autoSave, etSaleReturnDays, etStockAlertQty)

        // The stock pair keep the behaviour they were given further up and save from
        // inside it. Re-stated here because autoSave is not in scope where they were
        // first wired, and a second listener cannot be added to a switch - only the
        // one can be set, so it has to do both jobs.
        swStockFlag.setOnCheckedChangeListener { _, on ->
            if (!on) swStockAlert.isChecked = false
            applyStockState()
            autoSave()
        }
        swStockAlert.setOnCheckedChangeListener { _, on ->
            if (!on) etStockAlertQty.setText("")
            applyStockState()
            autoSave()
        }

        ThemeManager.applyTheme(view)
        com.example.synergic_pos_offline.utils.SettingsHighlighter.apply(
            view, arguments?.getString(com.example.synergic_pos_offline.utils.SettingsHighlighter.ARG_SETTING)
        )
    }

    /**
     * Asks the signed-in user for their password before a mode switch. On the correct
     * password it runs [onVerified] (which then confirms the data erase); on cancel or
     * a wrong password nothing switches and the Mode dropdown is put back to how it was.
     */
    private fun promptPasswordThenSwitch(targetMode: Mode, onVerified: () -> Unit) {
        val userId = SessionManager.currentUser?.userId
        if (userId.isNullOrBlank()) { toast("No signed-in user"); return }

        val accent = ThemeManager.getThemeColor(requireContext())
        val (dialog, view) = DialogUtils.buildCustom(requireContext(), R.layout.dialog_password_prompt)
        com.example.synergic_pos_offline.utils.InputLimits.applyDefaults(view)
        view.findViewById<android.widget.TextView>(R.id.tvPromptTitle).text = "Switch to ${targetMode.label}?"
        view.findViewById<android.widget.TextView>(R.id.tvPromptMessage).text =
            "Enter your password to change the mode to ${targetMode.label}."
        val etPwd = view.findViewById<TextInputEditText>(R.id.etPromptPwd)

        val btnCancel = view.findViewById<MaterialButton>(R.id.btnPromptCancel)
        val btnConfirm = view.findViewById<MaterialButton>(R.id.btnPromptConfirm)
        ThemeManager.styleDialogButtons(btnConfirm, btnCancel, accent)

        // Cancelling leaves the mode untouched — reset the dropdown to the saved mode.
        val revertDropdown = { actMode.setText(dao.load().mode.label, false) }
        btnCancel.setOnClickListener { dialog.dismiss(); revertDropdown() }
        dialog.setOnCancelListener { revertDropdown() }

        btnConfirm.setOnClickListener {
            val pwd = etPwd.text?.toString()?.trim().orEmpty()
            when {
                pwd.isEmpty() -> toast("Enter your password")
                !userDao.verifyPassword(userId, pwd) -> toast("Incorrect password")
                else -> { dialog.dismiss(); onVerified() }
            }
        }
        dialog.show()
    }

    /** Turns on the restaurant App Settings by default when Restaurant mode is enabled. */
    /**
     * Ends the session so the new mode takes effect.
     *
     * The mode decides the landing screen and the whole sidebar, and both are built
     * once when a session starts - saving the setting alone would leave the till
     * showing the old mode's menu over the new mode's data until someone happened to
     * sign out. The same thing Change Mode does in Calculator mode, for the same
     * reason.
     */
    private fun signOut() {
        SessionManager.logout()
        val fm = requireActivity().supportFragmentManager
        // The screen signed in on is the root of the stack, so there is nothing
        // underneath to pop back to - the login form is put up outright.
        fm.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        fm.beginTransaction()
            .replace(R.id.fragment_container, LoginFragment())
            .commit()
    }

    private fun enableRestaurantDefaults() {
        val appDao = com.example.synergic_pos_offline.database.AppSettingsDao(requireContext())
        val a = appDao.load()
        appDao.save(a.copy(couponMode = true, kot = true, tableMerge = true, tableShift = true))
    }

    private fun showChangePasswordDialog() {
        val userId = SessionManager.currentUser?.userId
        if (userId.isNullOrBlank()) { toast("No signed-in user"); return }

        val accent = ThemeManager.getThemeColor(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_change_password, null)
        com.example.synergic_pos_offline.utils.InputLimits.applyDefaults(view)
        val etCurrent = view.findViewById<TextInputEditText>(R.id.etCurrentPwd)
        val etNew = view.findViewById<TextInputEditText>(R.id.etNewPwd)
        val etConfirm = view.findViewById<TextInputEditText>(R.id.etConfirmPwd)

        val dialog = AlertDialog.Builder(requireContext()).setView(view).create()
        dialog.setCanceledOnTouchOutside(false)

        val btnCancel = view.findViewById<MaterialButton>(R.id.btnPwdCancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnPwdSave)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        btnSave.setTextColor(Color.WHITE)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val current = etCurrent.text?.toString()?.trim().orEmpty()
            val newPwd = etNew.text?.toString()?.trim().orEmpty()
            val confirm = etConfirm.text?.toString()?.trim().orEmpty()

            when {
                current.isEmpty() || newPwd.isEmpty() || confirm.isEmpty() ->
                    toast("Fill in all password fields")
                newPwd.length < 4 -> toast("New password must be at least 4 characters")
                newPwd != confirm -> toast("New passwords do not match")
                !userDao.verifyPassword(userId, current) -> toast("Current password is incorrect")
                else -> {
                    val id = userDao.idOf(userId)
                    if (id == null) { toast("User not found") }
                    else {
                        userDao.resetPassword(id, newPwd)
                        SessionManager.currentUser?.password = newPwd
                        dialog.dismiss()
                        DialogUtils.showSuccess(
                            context = requireContext(),
                            title = "Password changed",
                            message = "Your password has been updated successfully."
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    /** Greys a settings row out when the flag it depends on is off. */
    private fun View.setRowEnabled(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

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
