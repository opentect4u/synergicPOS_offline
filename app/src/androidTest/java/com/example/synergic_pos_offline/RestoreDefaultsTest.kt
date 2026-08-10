package com.example.synergic_pos_offline

import android.content.ContentValues
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.AppSettingsDao
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.database.OperatingPrinterDao
import com.example.synergic_pos_offline.database.PrinterDao
import com.example.synergic_pos_offline.database.TaxSettingsDao
import com.example.synergic_pos_offline.utils.AutoBackup
import com.example.synergic_pos_offline.utils.DefaultSettings
import com.example.synergic_pos_offline.utils.SettingsCache
import com.example.synergic_pos_offline.utils.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restore Defaults puts every setting back to what [DefaultSettings] says the app
 * came as - and leaves the shop's data where it is.
 *
 * Worth pinning because the defaults are now a promise rather than an accident: a
 * setting added to a screen and forgotten here would survive a reset, and the
 * operator who pressed the button would be left with a till that is half factory
 * and half whatever it was before, with no way to tell which half is which.
 */
@RunWith(AndroidJUnit4::class)
class RestoreDefaultsTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    /**
     * Every row of the two tables that say who this device is, as one comparable
     * string - so a test can assert nothing about them moved.
     */
    private fun identity(): String {
        val out = StringBuilder()
        listOf(DatabaseHelper.Tables.MD_REGISTRATION, DatabaseHelper.Tables.MD_USERS)
            .forEach { table ->
                out.append("-- $table\n")
                db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { c ->
                    while (c.moveToNext()) {
                        (0 until c.columnCount).joinTo(out, "|") { c.getString(it) ?: "" }
                        out.append('\n')
                    }
                }
            }
        return out.toString()
    }

    /** Moves every group as far from its default as it goes. */
    private fun configureEverything() {
        GeneralSettingsDao(ctx).save(
            GeneralSettingsDao.GeneralSettings(
                mode = GeneralSettingsDao.Mode.RESTAURANT,
                saleReturn = true,
                returnMode = GeneralSettingsDao.ReturnMode.ITEM_WISE,
                saleReturnDays = 7,
                lastBillStatus = true,
                quantityStatus = true,
                itemRate = GeneralSettingsDao.ItemRate.MULTIPLE,
                customerInfo = false,
                landingScreen = GeneralSettingsDao.LandingScreen.HOME,
                stockFlag = true,
                stockAlert = true,
                stockAlertQty = 5
            )
        )
        BillSettingsDao(ctx).apply {
            save(
                BillSettingsDao.BillSettings(
                    roundOff = true,
                    amountInWords = true,
                    twoCopyBill = true,
                    startBillNo = 500,
                    resetMode = BillSettingsDao.ResetMode.DAILY,
                    billNoCharEnabled = true,
                    billNoCharPrefix = "INV",
                    hsnCode = true,
                    customerDetails = BillSettingsDao.CustomerDetails.MOBILE_NAME_GSTIN,
                    customerAddressPrinting = true,
                    totalAmountFontSize = BillSettingsDao.FontSize.BIG,
                    billFormat = BillSettingsDao.BillFormat.CLASSIC
                )
            )
            saveTemplatePaperMm(58)
        }
        TaxSettingsDao(ctx).save(
            TaxSettingsDao.TaxSettings(
                discountEnabled = true,
                discountType = TaxSettingsDao.DiscountType.BILL_WISE,
                discountPosition = TaxSettingsDao.DiscountPosition.POST_TAX,
                gstEnabled = true,
                gstMode = TaxSettingsDao.GstMode.INCLUSIVE,
                vatEnabled = false,
                vatMode = TaxSettingsDao.GstMode.INCLUSIVE
            )
        )
        AppSettingsDao(ctx).save(
            AppSettingsDao.AppSettings(
                manualRate = true, cashReception = true, paymentMode = true,
                otherCharges = true, directAddToCart = true, couponMode = true,
                kot = true, tableMerge = true, tableShift = true, tableSplit = true
            )
        )
        AutoBackup.save(ctx, enabled = true, intervalHours = 6)
        ThemeManager.setThemeColor(ctx, "#8E24AA")
    }

    @Test
    fun everySettingGoesBackToTheFrozenDefault() {
        configureEverything()
        DefaultSettings.restore(ctx)

        assertEquals(DefaultSettings.GENERAL, GeneralSettingsDao(ctx).load())
        assertEquals(DefaultSettings.BILL, BillSettingsDao(ctx).load())
        assertEquals(DefaultSettings.TAX, TaxSettingsDao(ctx).load())
        assertEquals(DefaultSettings.APP, AppSettingsDao(ctx).load())
        assertEquals(
            DefaultSettings.TEMPLATE_PAPER_MM, BillSettingsDao(ctx).loadTemplatePaperMm()
        )
        assertEquals(
            AutoBackup.Settings(
                DefaultSettings.AUTO_BACKUP_ENABLED, DefaultSettings.AUTO_BACKUP_INTERVAL_HOURS
            ),
            AutoBackup.settings(ctx)
        )
        assertEquals(
            Color.parseColor(DefaultSettings.THEME_COLOR), ThemeManager.getThemeColor(ctx)
        )
    }

    /**
     * The cache the app actually reads at the till is republished, not just the table.
     *
     * Mode is the one to check: nearly every menu and screen asks the cache for it,
     * so a restore that wrote Grocery to the database and left Restaurant cached
     * would look like it had done nothing at all.
     */
    @Test
    fun theCachedCopyIsRepublishedToo() {
        configureEverything()
        assertEquals("R", SettingsCache.value(ctx, "G", "Mode"))

        DefaultSettings.restore(ctx)

        assertEquals("G", SettingsCache.value(ctx, "G", "Mode"))
        assertEquals("0", SettingsCache.value(ctx, "A", "KOT"))
    }

    /**
     * A key no version of the app reads any more does not survive the reset.
     *
     * The settings table is cleared before the defaults are written, so a till that
     * has been upgraded through several versions ends up with the same rows as one
     * installed today rather than those plus a decade of leftovers.
     */
    @Test
    fun keysNothingReadsAnyMoreAreCleared() {
        AppSettingsDao(ctx).put("Some Setting From An Older Version", "1")
        assertEquals("1", AppSettingsDao(ctx).get("Some Setting From An Older Version"))

        DefaultSettings.restore(ctx)

        assertNull(AppSettingsDao(ctx).get("Some Setting From An Older Version"))
    }

    /**
     * Who the device is survives the reset, exactly as it was.
     *
     * A reset that also took the logins with it would lock the shop out of the till
     * it had just reset, and one that took the registration would leave every row in
     * the database pointing at a store the device no longer is. Compared row by row
     * rather than merely counted: a password quietly rewritten is as bad as a user
     * deleted, and a count would not notice.
     */
    @Test
    fun whoTheDeviceIsIsNotTouched() {
        val store = db.insert(
            DatabaseHelper.Tables.MD_REGISTRATION, null,
            ContentValues().apply {
                put("store_name", "Defaults Test Store")
                put("store_gstin", "19ABCDE1234F1Z5")
            }
        )
        val user = db.insert(
            DatabaseHelper.Tables.MD_USERS, null,
            ContentValues().apply {
                put("user_id", "DEFAULTS_TEST_USER")
                put("user_name", "Defaults Test")
            }
        )
        try {
            configureEverything()
            val before = identity()

            DefaultSettings.restore(ctx)

            assertEquals(
                "the users and the registration should come through untouched",
                before, identity()
            )
        } finally {
            db.delete(DatabaseHelper.Tables.MD_USERS, "id = ?", arrayOf(user.toString()))
            db.delete(
                DatabaseHelper.Tables.MD_REGISTRATION, "store_id = ?", arrayOf(store.toString())
            )
        }
    }

    /**
     * The printers are forgotten - which is what the warning on the button promises,
     * and the one part of a reset that costs an operator a trip to the hardware.
     */
    @Test
    fun thePrintersGoBackToAnUnconfiguredTill() {
        val billWifi = PrinterDao(ctx).getAll()
            .first { it.purpose.equals("BILL", true) && it.type.equals("WIFI", true) }
        PrinterDao(ctx).updateConfig(billWifi.slNo, "192.168.1.50", 58)
        PrinterDao(ctx).setSelectedType("BILL", "BLUETOOTH")
        OperatingPrinterDao(ctx).insert(
            printerName = "Counter printer", printerSlNo = billWifi.slNo,
            purpose = "BILL", value = "192.168.1.50", paperMm = 58, isDefault = true
        )
        // Counted rather than assumed to be one: this runs against whatever printers
        // the device it is testing on happens to have already.
        val configured = OperatingPrinterDao(ctx).getAll().size

        val outcome = DefaultSettings.restore(ctx)

        assertEquals(
            "every named printer should be counted as removed",
            configured, outcome.printersRemoved
        )
        assertTrue(
            "no named printer should survive",
            OperatingPrinterDao(ctx).getAll().isEmpty()
        )

        val printers = PrinterDao(ctx).getAll()
        assertTrue("the connection rows should be rebuilt", printers.isNotEmpty())
        assertTrue(
            "no address or paper width should be left behind",
            printers.all { it.ip == null && it.paperMm == null }
        )
        assertEquals("WIFI", PrinterDao(ctx).getSelected("BILL")?.type)
        assertEquals("LAN", PrinterDao(ctx).getSelected("KOT")?.type)
        assertEquals("LAN", PrinterDao(ctx).getSelected("OTHERS")?.type)
    }
}
