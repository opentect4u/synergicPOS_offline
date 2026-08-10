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
            R.id.tilAutoBackupHours, R.id.btnSaveAutoBackup, R.id.tvAutoBackupState
        ).forEach { assertNotNull("a view the fragment fills in is missing", view.findViewById<View>(it)) }
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
