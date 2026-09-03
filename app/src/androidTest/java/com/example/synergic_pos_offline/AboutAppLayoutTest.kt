package com.example.synergic_pos_offline

import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The About App screen inflates and can find everything it fills in.
 *
 * A screen assembled from a layout and a fragment that never meet until someone
 * taps the tile is a screen that crashes for the operator rather than for us. This
 * inflates the layout for real and looks up every view the fragment reaches for.
 */
@RunWith(AndroidJUnit4::class)
class AboutAppLayoutTest {

    @Test
    fun theScreenInflatesWithEverythingItNeeds() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // A themed inflater, as the fragment gets: the Material cards and buttons in
        // the layout will not inflate against a bare application context.
        val themed = androidx.appcompat.view.ContextThemeWrapper(
            ctx, com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar
        )
        val view: View = LayoutInflater.from(themed).inflate(R.layout.fragment_about_app, null, false)

        listOf(
            R.id.tvAboutName, R.id.tvAboutVersion, R.id.tvAboutCompatibility,
            R.id.llAboutSections, R.id.btnBackupData, R.id.btnRestoreData, R.id.ivAboutIcon,
            R.id.swAutoBackup, R.id.llAutoBackupInterval, R.id.etAutoBackupHours,
            R.id.tilAutoBackupHours, R.id.btnSaveAutoBackup, R.id.tvAutoBackupState,
            R.id.btnEraseBills, R.id.btnRestoreDefaults,
            R.id.btnExportMasters, R.id.btnRestoreMasters
        ).forEach { assertNotNull("a view the fragment fills in is missing", view.findViewById<View>(it)) }
    }

    /**
     * The password gate inflates and carries every view the dialog fills in.
     *
     * It is the last thing between a tap and an erased book, and it is built in code
     * from ids - so an id renamed in the layout would not be noticed until an
     * operator was standing in front of the dialog that failed to open.
     */
    @Test
    fun thePasswordGateInflatesWithEverythingItNeeds() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val themed = androidx.appcompat.view.ContextThemeWrapper(
            ctx, com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar
        )
        val view: View =
            LayoutInflater.from(themed).inflate(R.layout.dialog_password_confirm, null, false)

        listOf(
            R.id.llPwdConfirmContent, R.id.ivPwdConfirmIcon, R.id.tvPwdConfirmTitle,
            R.id.tvPwdConfirmMessage, R.id.tilPwdConfirm, R.id.etPwdConfirm,
            R.id.btnPwdConfirmPositive, R.id.btnPwdConfirmNegative
        ).forEach { assertNotNull("a view the dialog fills in is missing", view.findViewById<View>(it)) }

        // The field must actually mask what is typed into it.
        val input = view.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etPwdConfirm
        )
        assertTrue(
            "the password field should be masked",
            input.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
        )
    }

    /** The facts the screen reports are readable on this device. */
    @Test
    fun theDeviceFactsAreAvailable() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        assertTrue("a version name is expected", !pkg.versionName.isNullOrBlank())
        assertTrue("minSdk should be readable", ctx.applicationInfo.minSdkVersion > 0)
        assertTrue("targetSdk should be readable", ctx.applicationInfo.targetSdkVersion > 0)
        println(
            "ABOUT: ${pkg.versionName} minSdk=${ctx.applicationInfo.minSdkVersion} " +
                "target=${ctx.applicationInfo.targetSdkVersion} device=${android.os.Build.VERSION.SDK_INT}"
        )
    }
}
