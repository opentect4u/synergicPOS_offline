package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.AppSettingsDao
import com.example.synergic_pos_offline.utils.AutoBackup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun theDefaultIsOneOfTheChoicesOffered() {
        // Cleared, so what comes back is the default rather than a leftover.
        AppSettingsDao(ctx).put("Auto Backup Interval Hours", "")
        assertEquals(AutoBackup.DEFAULT_INTERVAL_HOURS, AutoBackup.settings(ctx).intervalHours)
        // A default the dropdown cannot show would leave the field blank on a till
        // nobody has touched the setting on.
        assertTrue(
            "the default must be one of ${AutoBackup.INTERVAL_CHOICES}",
            AutoBackup.DEFAULT_INTERVAL_HOURS in AutoBackup.INTERVAL_CHOICES
        )
    }

    /**
     * Whatever is stored, what comes back is a value the dropdown can show.
     *
     * The interval used to be typed, so a till updating into this version can be
     * carrying any number from 1 to 168. Snapped to the nearest choice rather than
     * clamped into a range: clamping fixes 0 and 100000 but leaves 3 sitting there,
     * and 3 is not on the list, so the field would come up blank on a till with a
     * perfectly good setting stored.
     */
    @Test
    fun aStoredIntervalIsBroughtOntoTheList() {
        listOf(0, 1, 3, 5, 7, 9, 24, 168, 100000).forEach { stored ->
            AppSettingsDao(ctx).put("Auto Backup Interval Hours", stored.toString())
            val read = AutoBackup.settings(ctx).intervalHours
            assertTrue(
                "$stored came back as $read, which is not one of ${AutoBackup.INTERVAL_CHOICES}",
                read in AutoBackup.INTERVAL_CHOICES
            )
        }

        // The nearest, not merely any of them - and a tie goes to the shorter gap,
        // because backing up sooner than asked is the safe way to be wrong.
        assertEquals(2, AutoBackup.nearestInterval(1))
        assertEquals(2, AutoBackup.nearestInterval(3))
        assertEquals(4, AutoBackup.nearestInterval(5))
        assertEquals(12, AutoBackup.nearestInterval(24))
        assertEquals(6, AutoBackup.nearestInterval(6))
    }

    /** Saving is held to the list too, not just reading. */
    @Test
    fun anAskedForIntervalIsHeldToTheList() {
        AutoBackup.save(ctx, enabled = true, intervalHours = 0)
        assertEquals(AutoBackup.MIN_INTERVAL_HOURS, AutoBackup.settings(ctx).intervalHours)
        AutoBackup.save(ctx, enabled = true, intervalHours = 100000)
        assertEquals(AutoBackup.MAX_INTERVAL_HOURS, AutoBackup.settings(ctx).intervalHours)
    }

    @Test
    fun theFirstRunTakesOneAndTheNextDoesNot() {
        AutoBackup.save(ctx, enabled = true, intervalHours = AutoBackup.MIN_INTERVAL_HOURS)
        // No record of a previous run, so one is due immediately.
        AppSettingsDao(ctx).put("Auto Backup Last Run", "")

        val first = AutoBackup.runIfDue(ctx)
        assertTrue("the first run should take a backup: ${first.error}", first.taken)
        assertTrue("it should say where it went", !first.savedTo.isNullOrBlank())

        // ONE folder, with the day in the file's own name - no date subfolder. See
        // AutoBackup.FOLDER: everything that takes a backup files it in the same
        // place, so there is one place for the shop to look.
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        assertTrue(
            "should be filed straight under ${AutoBackup.FOLDER}/, was ${first.savedTo}",
            first.savedTo!!.contains("${AutoBackup.FOLDER}/synergic_backup_$today")
        )
        assertTrue(
            "the name should carry the date and time, was ${first.savedTo}",
            Regex("""synergic_backup_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.sql""")
                .containsMatchIn(first.savedTo!!)
        )

        // The interval has not passed, so the next check leaves it alone.
        assertFalse(
            "a second backup inside the interval would fill the card",
            AutoBackup.runIfDue(ctx).taken
        )
    }

    // ---- Retention -------------------------------------------------------------

    /**
     * The folder is never left holding more than [AutoBackup.MAX_BACKUPS] files,
     * and the one just taken is always among the survivors.
     *
     * BOTH HALVES MATTER. A prune that kept nothing would satisfy the count on its
     * own, so the backup taken a moment ago is looked for by name afterwards: the
     * newest file is the one a shop would actually restore from, and it is the one
     * an off-by-one in the `drop` would take.
     *
     * Takes one more than the ceiling so there is genuinely something to delete -
     * this is the case the old day-based ceiling could not reach at all, because
     * every backup a test can take lands on the same day.
     */
    @Test
    fun pruningKeepsOnlyTheMostRecentBackups() {
        AutoBackup.save(ctx, enabled = true, intervalHours = AutoBackup.MIN_INTERVAL_HOURS)
        val settings = AppSettingsDao(ctx)
        var last: AutoBackup.Outcome? = null
        repeat(AutoBackup.MAX_BACKUPS + 1) {
            // Cleared each time so the interval never holds the next one back - it is
            // the ceiling under test here, not the schedule.
            settings.put("Auto Backup Last Run", "")
            last = AutoBackup.runIfDue(ctx)
            assertTrue("a backup should have been taken to prune around: ${last?.error}", last!!.taken)
            // MediaStore records DATE_ADDED in whole SECONDS, and "newest first" is
            // what the prune keeps. Four backups inside one second would be four files
            // claiming the same instant, and which one survived would be the store's
            // guess - a tie this test must not depend on either way. A real till backs
            // up hourly, or when somebody presses a button; it never hits this.
            Thread.sleep(1100)
        }

        val left = com.example.synergic_pos_offline.utils.BackupFiles.list(ctx, AutoBackup.FOLDER)
        assertTrue(
            "at most ${AutoBackup.MAX_BACKUPS} backups may stand, found ${left.size}: " +
                left.joinToString { it.name },
            left.size <= AutoBackup.MAX_BACKUPS
        )
        assertTrue(
            "the backup just taken must survive its own prune, was ${last?.savedTo}",
            left.any { it.name == last?.savedTo?.substringAfterLast('/') }
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
