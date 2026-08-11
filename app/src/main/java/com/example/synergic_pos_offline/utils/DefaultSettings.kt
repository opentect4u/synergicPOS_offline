package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.AppSettingsDao
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.database.OperatingPrinterDao
import com.example.synergic_pos_offline.database.TaxSettingsDao

/**
 * What a factory-fresh till is configured as - written down in one place, and the
 * only thing Restore Defaults puts back.
 *
 * ## Why it is spelled out here
 *
 * Each settings group already carries defaults on its own data class, which is what
 * a till reads when a setting has never been saved. That is enough to *start* a till
 * but not to *reset* one: the defaults were scattered across four files, half of them
 * implied by a missing row rather than stated, and there was nowhere to look to answer
 * "what is this app supposed to be out of the box". This object is that answer -
 * every setting named, with the value it is frozen at. Restoring writes these values
 * down rather than deleting rows and hoping the reader's fallback agrees.
 *
 * ## Adding a setting
 *
 * A new setting belongs in this file the same day it is added to a settings screen.
 * The groups below are constructed with every field named on purpose: a field left
 * out silently inherits the data class's own default, and nobody reading this file
 * would know the setting existed.
 *
 * ## What restoring does not touch
 *
 * The shop's data - products, customers, bills, stock, ledgers - and the things that
 * identify the installation: the store registration, the users and their passwords,
 * and the bill's own header, footer, logo and QR. None of those are settings; they
 * are what the shop has typed in, and a reset that erased them would be a factory
 * wipe wearing the wrong label. [DatabaseBackup] is the tool for that.
 */
object DefaultSettings {

    // ---- General Settings (md_app_settings, type 'G') -------------------------

    /**
     * Grocery, with nothing optional turned on.
     *
     * Mode is the one that reaches furthest: Grocery is what the till goes back to,
     * so the restaurant menus, KOT and table screens fold away with it. Customer Info
     * is off with the rest - a credit sale still asks for the customer whatever this
     * says, because it has to be collected from somebody.
     */
    val GENERAL = GeneralSettingsDao.GeneralSettings(
        mode = GeneralSettingsDao.Mode.GROCERY,
        saleReturn = false,
        returnMode = GeneralSettingsDao.ReturnMode.BILL_WISE,
        saleReturnDays = 0,
        lastBillStatus = false,
        quantityStatus = false,
        itemRate = GeneralSettingsDao.ItemRate.SINGLE,
        customerInfo = false,
        landingScreen = GeneralSettingsDao.LandingScreen.SALE,
        stockFlag = false,
        stockAlert = false,
        stockAlertQty = 0
    )

    // ---- Bill Settings (md_app_settings, type 'B') ----------------------------

    /**
     * A plain bill: numbered straight through from 1 with no prefix, no round off,
     * no words, one copy, mobile number only for the customer.
     *
     * Start No. of 0 means "the first bill is number 1", and it only applies to a
     * till with no bills in the period at all - a shop already part-way through its
     * book keeps counting from the highest number it has used, so restoring defaults
     * cannot hand out a number twice.
     */
    val BILL = BillSettingsDao.BillSettings(
        roundOff = false,
        amountInWords = false,
        twoCopyBill = false,
        startBillNo = 0,
        resetMode = BillSettingsDao.ResetMode.CONTINUE,
        billNoCharEnabled = false,
        billNoCharPrefix = "",
        hsnCode = false,
        customerDetails = BillSettingsDao.CustomerDetails.ONLY_MOBILE,
        customerAddressPrinting = false,
        totalAmountFontSize = BillSettingsDao.FontSize.REGULAR,
        billFormat = BillSettingsDao.BillFormat.STANDARD
    )

    /** The width the Print Template preview is drawn at - 3 inch. */
    const val TEMPLATE_PAPER_MM = BillSettingsDao.DEFAULT_TEMPLATE_PAPER_MM

    // ---- Tax Settings (md_app_settings, type 'T') -----------------------------

    /**
     * No discount and no tax.
     *
     * Off rather than on because a tax charged that the shop is not registered for
     * is a worse mistake than one that has to be switched on. The modes carried
     * alongside are what each becomes when it is switched on - exclusive, i.e. added
     * on top of the price.
     */
    val TAX = TaxSettingsDao.TaxSettings(
        discountEnabled = false,
        discountType = TaxSettingsDao.DiscountType.ITEM_WISE,
        discountPosition = TaxSettingsDao.DiscountPosition.PRE_TAX,
        gstEnabled = false,
        gstMode = TaxSettingsDao.GstMode.EXCLUSIVE,
        vatEnabled = false,
        vatMode = TaxSettingsDao.GstMode.EXCLUSIVE
    )

    // ---- App Settings (md_app_settings, type 'A') -----------------------------

    /**
     * Every optional behaviour off - the shortest path through a sale.
     *
     * The restaurant half is off too. It is only visible in Restaurant mode, and
     * a restore has just put the till back to Grocery; when the mode is switched
     * back, General Settings turns the restaurant toggles on again itself.
     */
    val APP = AppSettingsDao.AppSettings(
        manualRate = false,
        cashReception = false,
        paymentMode = false,
        otherCharges = false,
        directAddToCart = false,
        couponMode = false,
        kot = false,
        tableMerge = false,
        tableShift = false,
        tableSplit = false
    )

    // ---- Everything else ------------------------------------------------------

    /** Automatic backup is off; backups are taken when the operator asks for one. */
    const val AUTO_BACKUP_ENABLED = false

    /** The gap it uses once it is switched on. */
    const val AUTO_BACKUP_INTERVAL_HOURS = AutoBackup.DEFAULT_INTERVAL_HOURS

    /** The accent the whole app is coloured with. */
    const val THEME_COLOR = ThemeManager.DEFAULT_COLOR

    // ---- Restoring ------------------------------------------------------------

    /** What a restore actually undid, for the screen to report back. */
    data class Outcome(val printersRemoved: Int)

    /**
     * Puts every setting above back, and returns what it cost.
     *
     * Blocking - it rewrites the settings table and republishes the cache - so
     * callers run it off the main thread.
     *
     * The order matters in one place: the settings rows are cleared *first*, so a
     * key that some older version of the app wrote and no version reads any more
     * goes with them, and the four saves that follow write the frozen set back on a
     * clean table. Each save republishes [SettingsCache] as it goes, so nothing is
     * left reading the values that were just replaced.
     */
    fun restore(context: Context): Outcome {
        val helper = DatabaseHelper.getInstance(context)
        helper.clearAppSettings()

        GeneralSettingsDao(context).save(GENERAL)
        BillSettingsDao(context).apply {
            save(BILL)
            saveTemplatePaperMm(TEMPLATE_PAPER_MM)
        }
        TaxSettingsDao(context).save(TAX)
        AppSettingsDao(context).save(APP)

        // Auto backup is stored in the same table but has no settings screen of its
        // own, so it is written here rather than folded into one of the four above.
        AutoBackup.save(context, AUTO_BACKUP_ENABLED, AUTO_BACKUP_INTERVAL_HOURS)

        val printersRemoved = OperatingPrinterDao(context).getAll().size
        helper.resetPrintersToDefaults()

        ThemeManager.setThemeColor(context, THEME_COLOR)

        // The saves each refreshed the cache, but the clear above and the auto-backup
        // write did not - one last pass so what is cached is exactly what is stored.
        SettingsCache.storeFromDb(context, "Restore defaults")

        return Outcome(printersRemoved = printersRemoved)
    }
}
