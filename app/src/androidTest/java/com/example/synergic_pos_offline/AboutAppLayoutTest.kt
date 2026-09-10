package com.example.synergic_pos_offline

import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
        val view: View = inflateAbout()

        listOf(
            R.id.tvAboutName, R.id.tvAboutVersion, R.id.tvAboutCompatibility,
            R.id.llAboutSections, R.id.btnBackupData, R.id.btnRestoreData, R.id.ivAboutIcon,
            R.id.swAutoBackup, R.id.llAutoBackupInterval, R.id.actAutoBackupHours,
            R.id.tilAutoBackupHours, R.id.btnSaveAutoBackup, R.id.tvAutoBackupState,
            R.id.btnEraseBills, R.id.btnRestoreDefaults,
            R.id.llRollOverSection, R.id.tilRollOverYears, R.id.actRollOverYears,
            R.id.btnExportMasters, R.id.btnRestoreMasters
        ).forEach { assertNotNull("a view the fragment fills in is missing", view.findViewById<View>(it)) }

        // The interval is CHOSEN, not typed - see AutoBackup.INTERVAL_CHOICES. A
        // field that still takes a keyboard would let an operator enter an interval
        // that is not on the list, and the screen reads its value back by matching
        // the label against that list, so anything typed would be silently dropped.
        val interval = view.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(
            R.id.actAutoBackupHours
        )
        assertNotNull("the interval field should be a dropdown", interval)
        assertTrue(
            "the interval dropdown should not open a keyboard, inputType was ${interval.inputType}",
            interval.inputType == android.text.InputType.TYPE_NULL
        )

        // Filled the way the fragment fills it, then asked what it would actually
        // offer. An AutoCompleteTextView narrows its list to whatever text is in the
        // field unless something stops it, and a pick-one field is never empty - so
        // the menu would open showing the one interval already chosen and nothing
        // else. Dropdowns exists for that; this checks the interval field is using
        // it, by counting the rows the adapter publishes AFTER a value is set.
        val labels = com.example.synergic_pos_offline.utils.AutoBackup.INTERVAL_CHOICES
            .map { com.example.synergic_pos_offline.utils.AutoBackup.intervalLabel(it) }
        // ON THE MAIN THREAD, both of them. A Filter builds a Handler in its own
        // constructor, so the adapter cannot even be created on a thread without a
        // Looper - and the instrumentation thread is one. The fragment always does
        // this from onViewCreated, so this is the test matching the app, not a
        // workaround for it.
        //
        // Filtering then runs on the Filter's own worker thread and publishes back on
        // the main one, so it is waited for rather than assumed: a count read too
        // early would pass against the plain ArrayAdapter this is here to rule out.
        val done = java.util.concurrent.CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            com.example.synergic_pos_offline.utils.Dropdowns.fill(interval, labels)
            interval.setText(labels.first(), false)
            (interval.adapter as android.widget.Filterable).filter
                .filter(labels.first()) { done.countDown() }
        }
        assertTrue(
            "the dropdown's filter never finished",
            done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        )
        assertEquals(
            "the menu should offer all of $labels whatever is already chosen",
            labels.size, interval.adapter.count
        )

        // And every label maps back to exactly one interval - the fragment reads the
        // operator's choice by matching this text against the list.
        labels.forEachIndexed { i, label ->
            val matches = com.example.synergic_pos_offline.utils.AutoBackup.INTERVAL_CHOICES
                .filter { com.example.synergic_pos_offline.utils.AutoBackup.intervalLabel(it) == label }
            assertEquals("\"$label\" should name exactly one interval", 1, matches.size)
            assertEquals(
                com.example.synergic_pos_offline.utils.AutoBackup.INTERVAL_CHOICES[i], matches.first()
            )
        }
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

    /**
     * The roll-over card offers every period, and starts on the stored one.
     *
     * AboutAppFragment.bindRollOverDesign fills the dropdown from
     * TransactionRollOver.CHOICES and saves what is picked. What is checked here is
     * that the card
     * can actually be operated: a dropdown that opens showing only the value already
     * in it is a broken design, and this one was exactly that until the list stopped
     * coming from app:simpleItems, whose adapter filters. See Dropdowns. Counted
     * AFTER a value is set, which is when it went wrong.
     */
    @Test
    fun theRollOverDropdownOffersEveryPeriod() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val view: View = inflateAbout()
        val years = view.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(
            R.id.actRollOverYears
        )
        // From the object that owns the periods, not a copy in the resources: a
        // period is a label AND a number of days, and the two must not drift apart.
        val offered = com.example.synergic_pos_offline.utils.TransactionRollOver
            .CHOICES.map { it.label }

        assertEquals(
            "the card should offer one day, one year and one and a half",
            listOf("1 day", "1 year", "1.5 years"), offered
        )

        // The heading, WORD FOR WORD as the operator asked for it - "BACK UP" as two
        // words and "TRANSCATION" as spelled. Pinned here because it reads like a
        // mistake and the next person to touch this file will want to correct it.
        assertEquals(
            "the heading is the operator's own wording and is not to be tidied",
            "MAXIMUM BACK UP SIZE  years BEFORE TRANSCATION ROLL OVER",
            view.findViewById<android.widget.TextView>(R.id.tvRollOverLabel).text.toString()
        )
        // Filled the way the fragment fills it - the layout carries no list of its
        // own any more, on purpose.
        val done = java.util.concurrent.CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            com.example.synergic_pos_offline.utils.Dropdowns.fill(years, offered)
            years.setText(
                com.example.synergic_pos_offline.utils.TransactionRollOver.DEFAULT.label, false
            )
            (years.adapter as android.widget.Filterable).filter
                .filter(years.text.toString()) { done.countDown() }
        }
        assertEquals(
            "it should start on whatever is stored, which defaults to a year",
            com.example.synergic_pos_offline.utils.TransactionRollOver.DEFAULT.label,
            years.text.toString()
        )
        assertTrue(
            "the period should be chosen, not typed - inputType was " + years.inputType,
            years.inputType == android.text.InputType.TYPE_NULL
        )

        assertTrue(
            "the dropdown's filter never finished",
            done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        )
        assertEquals(
            "the menu should offer every period even with one already chosen",
            offered.size, years.adapter.count
        )
    }

    /**
     * The About layout, inflated ON THE MAIN THREAD - the way the fragment inflates it.
     *
     * Inflated where the app inflates it, not where the test happens to run. Some of
     * these Material views build an adapter in their own constructor - and an adapter
     * builds a Filter, and a Filter builds a Handler - so the layout throws outright
     * on a thread with no Looper. The instrumentation thread is one such thread; the
     * main thread the fragment runs on is not. Inflating here the way the fragment
     * does means a failure in this test is a failure the operator would have seen.
     */
    private fun inflateAbout(): View {
        // A themed inflater, as the fragment gets: the Material cards and buttons in
        // the layout will not inflate against a bare application context.
        val themed = androidx.appcompat.view.ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar
        )
        var inflated: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            inflated = LayoutInflater.from(themed).inflate(R.layout.fragment_about_app, null, false)
        }
        return inflated!!
    }
}
