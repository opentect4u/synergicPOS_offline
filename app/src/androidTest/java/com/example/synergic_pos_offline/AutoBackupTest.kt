package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.AppSettingsDao
import com.example.synergic_pos_offline.utils.AutoBackup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Automatic backup takes one when it is due, leaves it alone when it is not, and
 * files it where it can be found.
 *
 * The timing is the part worth pinning: a backup that fires on every check would
 * fill the card and read the whole database every few minutes, and one that never
 * fires is the same as not having the feature.
 */
@RunWith(AndroidJUnit4::class)
class AutoBackupTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun switchItBackOff() {
        AutoBackup.save(ctx, enabled = false, intervalHours = AutoBackup.DEFAULT_INTERVAL_HOURS)
    }

    @Test
    fun offMeansOff() {
        AutoBackup.save(ctx, enabled = false, intervalHours = 1)
        assertFalse("nothing should be taken while it is off", AutoBackup.runIfDue(ctx).taken)
    }

    @Test
    fun theDefaultIsEveryHour() {
        // Cleared, so what comes back is the default rather than a leftover.
        AppSettingsDao(ctx).put("Auto Backup Interval Hours", "")
        assertEquals(1, AutoBackup.settings(ctx).intervalHours)
        assertEquals(1, AutoBackup.DEFAULT_INTERVAL_HOURS)
    }

    @Test
    fun anAskedForIntervalIsHeldToSomethingSane() {
        AutoBackup.save(ctx, enabled = true, intervalHours = 0)
        assertEquals(
            "below an hour the till would spend its day reading its own database",
            AutoBackup.MIN_INTERVAL_HOURS, AutoBackup.settings(ctx).intervalHours
        )
        AutoBackup.save(ctx, enabled = true, intervalHours = 100000)
        assertEquals(AutoBackup.MAX_INTERVAL_HOURS, AutoBackup.settings(ctx).intervalHours)
    }

    /**
     * Only whole numbers of hours above zero are accepted.
     *
     * Refused rather than corrected: silently turning a typed 0 into a 1 is how an
     * operator ends up believing they set something they did not.
     */
    @Test
    fun onlyWholeNumbersAboveZeroAreAccepted() {
        listOf("", " ", "0", "00", "-1", "1.5", "1,5", "abc", "1a", "169", "999")
            .forEach { assertNull("\"$it\" should be refused", AutoBackup.validHours(it)) }

        assertEquals(1, AutoBackup.validHours("1"))
        assertEquals(6, AutoBackup.validHours("6"))
        assertEquals(24, AutoBackup.validHours(" 24 "))
        assertEquals(AutoBackup.MAX_INTERVAL_HOURS, AutoBackup.validHours("168"))
        // A leading zero is still a whole number of hours.
        assertEquals(2, AutoBackup.validHours("02"))
    }

    @Test
    fun theFirstRunTakesOneAndTheNextDoesNot() {
        AutoBackup.save(ctx, enabled = true, intervalHours = 1)
        // No record of a previous run, so one is due immediately.
        AppSettingsDao(ctx).put("Auto Backup Last Run", "")

        val first = AutoBackup.runIfDue(ctx)
        assertTrue("the first run should take a backup: ${first.error}", first.taken)
        assertTrue("it should say where it went", !first.savedTo.isNullOrBlank())

        // Filed under POSbackup, in today's folder, named with the date and time.
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        assertTrue(
            "should be filed under POSbackup/<date>, was ${first.savedTo}",
            first.savedTo!!.contains("POSbackup/$today")
        )
        assertTrue(
            "the name should carry the date and time, was ${first.savedTo}",
            Regex("""synergic_backup_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.sql""")
                .containsMatchIn(first.savedTo!!)
        )

        // An hour has not passed, so the next check leaves it alone.
        assertFalse(
            "a second backup within the hour would fill the card",
            AutoBackup.runIfDue(ctx).taken
        )
    }

    @Test
    fun aBackupBecomesDueOnceTheIntervalHasPassed() {
        AutoBackup.save(ctx, enabled = true, intervalHours = 2)
        // Three hours ago: past a two-hour interval, so one is due.
        val threeHoursAgo = System.currentTimeMillis() - 3 * 60 * 60 * 1000L
        AppSettingsDao(ctx).put("Auto Backup Last Run", threeHoursAgo.toString())

        assertTrue("a backup was overdue and should have been taken", AutoBackup.runIfDue(ctx).taken)

        // And having just run, it is not due again.
        AutoBackup.save(ctx, enabled = true, intervalHours = 2)
        assertFalse("it should not run twice in a row", AutoBackup.runIfDue(ctx).taken)
    }
}
