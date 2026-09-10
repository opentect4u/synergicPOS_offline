package com.example.synergic_pos_offline.utils

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.synergic_pos_offline.database.AppSettingsDao
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Takes a backup on its own, every so often, so the shop is not relying on somebody
 * remembering to.
 *
 * ## Where the files go
 *
 * `Downloads/backup/synergic_backup_<date>_<time>.sql` - ONE folder, with the day and
 * time in each file's own name so the right one can be picked out without opening
 * any of them.
 *
 * All four ways of taking a backup land here: the timer, the Backup button, and the
 * safety copies taken before Erase Bills and Restore Defaults. Two conventions for
 * the same kind of file would only mean hunting in two places.
 *
 * It used to be a folder per day. That kept the days apart, but it put anybody
 * looking for "the backup" in front of a list of folders to open first, and the day
 * was never lost by flattening it - it is in the name.
 *
 * Never more than [MAX_BACKUPS] FILES stand at once - the oldest goes the moment a
 * backup would make a fourth, whichever of the four ways took it. See
 * [pruneToRecentBackups].
 *
 * ## When it runs
 *
 * While the app is open. It is checked when the app starts and every few minutes
 * after that, and takes a backup whenever [intervalHours] have passed since the last
 * one - including the case where the app was closed over that period, which is
 * caught up on the next start.
 *
 * It deliberately does not run when the app is closed. That would need a scheduled
 * background job, and a till reading its whole database while nobody is looking at
 * it is a worse trade than a backup that waits until the app is next opened. What it
 * does guarantee is that a till open through the working day is backed up through
 * the working day.
 */
object AutoBackup {

    /** Whether to take backups without being asked. Stored as "1" / "0". */
    private const val KEY_ENABLED = "Auto Backup"

    /** How many hours between backups. */
    private const val KEY_INTERVAL = "Auto Backup Interval Hours"

    /** When the last automatic backup was taken, as epoch millis. */
    private const val KEY_LAST_RUN = "Auto Backup Last Run"

    /** How long backups are kept before the oldest are cleared away - the number. */
    private const val KEY_RETENTION = "Auto Backup Retention Days"

    /** The unit [KEY_RETENTION]'s number is counted in - "DAYS"/"MONTHS"/"YEARS". */
    private const val KEY_RETENTION_UNIT = "Auto Backup Retention Unit"

    /**
     * The one folder every backup lands in - `Downloads/backup`.
     *
     * ONE PLACE, FLAT. It used to be `POSbackup/<date>`, a folder per day, which put
     * an operator looking for "the backup" in front of a list of folders to open
     * before they found a file. All four things that take a backup - the timer, the
     * Backup button, Erase Bills and Restore Defaults - write here now, so there is
     * one place to look and one place to copy off the tablet.
     *
     * The day is still known: it is in every file name (see [fileName]), which is
     * what retention now reads - and unlike a folder, a name travels with the file.
     */
    const val FOLDER = "backup"

    /**
     * The gaps an operator may choose between - A LIST, not a range.
     *
     * The About screen offers exactly these in a dropdown. It used to be a box to
     * type a number of hours into, anything from 1 to 168, which asked the operator
     * a question they have no way to answer well: a till backing up hourly writes 24
     * copies of its whole database a day, and only [MAX_BACKUPS] of them will still
     * be there by evening, so the other 21 were disk and delay for nothing.
     *
     * Every one of these divides the trading day, so backups fall at the same hours
     * each day rather than drifting, and the three that are kept span a useful part
     * of it: at 12 hours, three backups reach back a day and a half.
     */
    val INTERVAL_CHOICES = listOf(2, 4, 6, 8, 12)

    /** How often, when nobody has said otherwise. */
    const val DEFAULT_INTERVAL_HOURS = 2

    /**
     * The narrowest and widest gap that can be asked for - the ends of
     * [INTERVAL_CHOICES], since nothing outside the list can be chosen.
     *
     * Kept as their own names because they are what a stored value is held to: a
     * till carrying a number from before the dropdown existed - 1, or 24 - is
     * brought back onto the list by [nearestInterval] rather than being trusted.
     */
    val MIN_INTERVAL_HOURS = INTERVAL_CHOICES.first()
    val MAX_INTERVAL_HOURS = INTERVAL_CHOICES.last()

    /**
     * How long a backup is kept before it is cleared away - a number and the unit
     * it counts in, typed on the About screen rather than chosen off a fixed list:
     * "keep for 5 years" is not a figure seven/fifteen/thirty/sixty/ninety days
     * could ever say.
     *
     * IN PRACTICE THIS RARELY DECIDES ANYTHING NOW. [MAX_BACKUPS] keeps only the 3
     * most recent files whatever this says, and 3 files is tighter than any window
     * a shop would type: a till that backs up hourly is past three inside a
     * morning. What this still does is clear a backup out on age alone - a till
     * that has not traded in months does not keep its last three for ever. Both run
     * on every prune (see [pruneOldBackups]), and the ceiling runs last.
     */
    enum class RetentionUnit(val label: String, val storedName: String) {
        DAYS("Days", "DAYS"),
        MONTHS("Months", "MONTHS"),
        YEARS("Years", "YEARS");

        companion object {
            /** [storedName] read back, or [DAYS] for anything else - including
             *  nothing stored at all, which is every till before this setting
             *  existed and every one still on its default. */
            fun fromStored(v: String?): RetentionUnit =
                entries.firstOrNull { it.storedName == v } ?: DAYS
        }
    }

    /** How long backups are kept when nobody has said otherwise. */
    const val DEFAULT_RETENTION_VALUE = 7
    val DEFAULT_RETENTION_UNIT = RetentionUnit.DAYS

    /** The narrowest and widest number that can be typed, whatever the unit. */
    const val MIN_RETENTION_VALUE = 1
    const val MAX_RETENTION_VALUE = 999

    /**
     * The most backup FILES [FOLDER] is ever left holding, whatever the retention
     * setting says.
     *
     * A HARD CEILING, not a choice on a settings screen: a till backing up every
     * hour would otherwise keep every one it took for as long as
     * [Settings.retentionValue] says to, which is hundreds of copies of the whole
     * database sitting on a shop's tablet. Whichever backup was just taken -
     * automatic, manual, or the safety copy before Erase Bills or Restore
     * Defaults - always leaves at most this many files on disk; see
     * [pruneToRecentBackups].
     *
     * COUNTED IN FILES, not in days. It used to keep the 3 most recent DAYS, which
     * on a till backing up hourly is 72 files, not 3 - the shop asked for three
     * backups and got three days of them. Three files is what it now means.
     */
    const val MAX_BACKUPS = 3

    // ---- The setting ---------------------------------------------------------

    data class Settings(
        val enabled: Boolean,
        val intervalHours: Int,
        /** How long backups are kept, and in what unit - see [RetentionUnit]. */
        val retentionValue: Int = DEFAULT_RETENTION_VALUE,
        val retentionUnit: RetentionUnit = DEFAULT_RETENTION_UNIT
    ) {
        /** "5 Years", "7 Days" - the About screen's own reading of the pair. */
        val retentionLabel: String get() = "$retentionValue ${retentionUnit.label}"
    }

    fun settings(context: Context): Settings {
        val dao = AppSettingsDao(context)
        val hours = dao.get(KEY_INTERVAL)?.toIntOrNull() ?: DEFAULT_INTERVAL_HOURS
        val value = dao.get(KEY_RETENTION)?.toIntOrNull() ?: DEFAULT_RETENTION_VALUE
        return Settings(
            enabled = dao.get(KEY_ENABLED) == "1",
            intervalHours = nearestInterval(hours),
            retentionValue = value.coerceIn(MIN_RETENTION_VALUE, MAX_RETENTION_VALUE),
            retentionUnit = RetentionUnit.fromStored(dao.get(KEY_RETENTION_UNIT))
        )
    }

    /**
     * [hours] brought onto [INTERVAL_CHOICES] - the closest one that is offered.
     *
     * NOT A CLAMP. A till updated from the version with a typed hours box can be
     * carrying any number from 1 to 168, and 1 clamped to the range would become 2
     * while 24 became 12 - which is right - but 3 would stay 3, a value the dropdown
     * cannot show. The field would then sit blank on a till that has a perfectly
     * good interval stored, and the operator would have to pick one before anything
     * they changed on this screen could be saved.
     *
     * Closest wins, and a tie goes to the shorter gap: backing up sooner than asked
     * is the safe direction to be wrong in.
     */
    fun nearestInterval(hours: Int): Int =
        INTERVAL_CHOICES.minByOrNull { kotlin.math.abs(it - hours) } ?: DEFAULT_INTERVAL_HOURS

    /** "2 hours", "12 hours" - one choice as the dropdown shows it. */
    fun intervalLabel(hours: Int): String = if (hours == 1) "1 hour" else "$hours hours"

    fun save(
        context: Context,
        enabled: Boolean,
        intervalHours: Int,
        retentionValue: Int = settings(context).retentionValue,
        retentionUnit: RetentionUnit = settings(context).retentionUnit
    ) {
        val dao = AppSettingsDao(context)
        dao.put(KEY_ENABLED, if (enabled) "1" else "0")
        dao.put(KEY_INTERVAL, nearestInterval(intervalHours).toString())
        dao.put(
            KEY_RETENTION,
            retentionValue.coerceIn(MIN_RETENTION_VALUE, MAX_RETENTION_VALUE).toString()
        )
        dao.put(KEY_RETENTION_UNIT, retentionUnit.storedName)
    }

    /**
     * [typed] read as a retention number, or null where it is not one to accept.
     *
     * A whole number in range only - the same shape [validHours] asks of the
     * interval field, refused rather than clamped so the screen can say what was
     * wrong with what was actually typed instead of quietly storing something else.
     */
    fun validRetentionValue(typed: String): Int? {
        val trimmed = typed.trim()
        if (trimmed.isEmpty() || !trimmed.all { it.isDigit() }) return null
        val value = trimmed.toIntOrNull() ?: return null
        return value.takeIf { it in MIN_RETENTION_VALUE..MAX_RETENTION_VALUE }
    }

    /** When the last automatic backup was taken, or null if none has been. */
    fun lastRun(context: Context): Long? =
        AppSettingsDao(context).get(KEY_LAST_RUN)?.toLongOrNull()?.takeIf { it > 0 }

    /** The last automatic backup, worded for the About screen. */
    fun lastRunDescription(context: Context): String {
        val at = lastRun(context) ?: return "not yet"
        return SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US).format(Date(at))
    }

    // ---- Running it ----------------------------------------------------------

    /** What a due backup did, for the caller to log or show. */
    data class Outcome(val taken: Boolean, val savedTo: String? = null, val error: String? = null)

    /**
     * Takes a backup if one is due, and does nothing otherwise.
     *
     * Safe to call as often as the caller likes - on every start, on a timer, on a
     * screen coming back - because it is the elapsed time that decides, not the call.
     *
     * Blocking: it reads the whole database. Callers run it off the main thread.
     */
    fun runIfDue(context: Context): Outcome {
        val settings = settings(context)
        if (!settings.enabled) return Outcome(taken = false)

        val last = lastRun(context)
        val dueAt = (last ?: 0L) + settings.intervalHours * 60L * 60L * 1000L
        if (last != null && System.currentTimeMillis() < dueAt) return Outcome(taken = false)

        return backupNow(context)
    }

    /**
     * Takes a backup now, wherever it is called from.
     *
     * The time it finished is recorded whether or not it succeeded - a backup that
     * fails every time it is tried should not have the till trying again every few
     * minutes for the rest of the day.
     */
    fun backupNow(context: Context): Outcome {
        val now = Date()
        return try {
            // A file per backup, in that day's folder: the retention window keeps a
            // rolling stretch of them, so each has to stand on its own rather than
            // overwrite the one before it.
            val savedTo = Downloads.stream(
                context, fileName(now), "application/sql", folderFor(now)
            ) { writer -> DatabaseBackup.exportTo(context, writer) }
            AppSettingsDao(context).put(KEY_LAST_RUN, System.currentTimeMillis().toString())
            // Clear away whatever has aged out of the window, now that a fresh backup
            // is safely on disk - never before, so a failed prune cannot leave the till
            // with neither the old backups nor a new one.
            pruneOldBackups(context)
            Outcome(taken = true, savedTo = savedTo)
        } catch (e: Exception) {
            android.util.Log.e("AutoBackup", "Automatic backup failed", e)
            AppSettingsDao(context).put(KEY_LAST_RUN, System.currentTimeMillis().toString())
            Outcome(taken = false, error = e.message ?: "the backup could not be written")
        }
    }

    /**
     * A backup taken immediately before something that cannot be undone, named after
     * the thing it precedes.
     *
     * Every irreversible action on the About screen goes through here first, so the
     * state of the till a second before it was changed is always on disk. The name
     * carries [action] because that is what makes the file findable afterwards: an
     * operator looking for "the one from before I erased the bills" should not have
     * to work it out from a timestamp.
     *
     * Everything is carried except who the device is - the users and the store
     * registration, see [DatabaseBackup.DEVICE_IDENTITY]. This is the one backup that
     * is meant to be restored onto the device it came from, minutes later, and
     * rolling the login list back to that moment alongside the settings is not what
     * anybody pressing undo is asking for.
     *
     * Throws if the file could not be written, and the caller is expected to let it:
     * an irreversible action whose safety net silently failed to deploy should not
     * go ahead. Blocking - it reads the whole database.
     */
    fun backupBefore(context: Context, action: String): String {
        val now = Date()
        val savedTo = Downloads.stream(
            context, fileName(now, action), "application/sql", folderFor(now)
        ) { writer ->
            DatabaseBackup.exportTo(context, writer, DatabaseBackup.DEVICE_IDENTITY)
        }
        // Every backup prunes, this one included - "automatic or manual, anything"
        // is what keeps the folder count honest; a safety copy taken here is no
        // different from one the timer took. After the write, same as everywhere
        // else: a failed prune must never cost the copy that was just made safe.
        runCatching { pruneOldBackups(context) }
        return savedTo
    }

    // ---- Retention -----------------------------------------------------------

    /**
     * Deletes the backups that have aged out of the retention window.
     *
     * A backup's day is read from its own file NAME - see [dayOf] - rather than from
     * whatever timestamp the store happens to carry. The name is what the backup
     * itself wrote down, and it survives the file being copied about.
     *
     * The window itself is [Settings.retentionValue] counted in
     * [Settings.retentionUnit] - DAYS keeps today plus the N-1 days before it, so a
     * retention of 7 days keeps a week and clears the eighth day the moment it is
     * reached; MONTHS and YEARS are a genuine rolling calendar window back from
     * today (5 years keeps everything from the same date five years ago onward),
     * not the number of days that happens to span.
     *
     * Best-effort by design - a backup that cannot be deleted (a file held open, a
     * permission withdrawn) is left where it is rather than failing the backup that
     * has just been taken.
     *
     * @return how many files were removed
     */
    fun pruneOldBackups(context: Context): Int {
        val settings = settings(context)
        val cutoff = Calendar.getInstance().apply {
            when (settings.retentionUnit) {
                RetentionUnit.DAYS -> add(Calendar.DAY_OF_YEAR, -(settings.retentionValue - 1))
                RetentionUnit.MONTHS -> add(Calendar.MONTH, -settings.retentionValue)
                RetentionUnit.YEARS -> add(Calendar.YEAR, -settings.retentionValue)
            }
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.time

        var removed = 0
        BackupFiles.list(context, FOLDER).forEach { found ->
            // Unreadable is LEFT ALONE, never deleted - see [dayOf].
            val day = dayOf(found) ?: return@forEach
            if (day.before(cutoff) && BackupFiles.delete(context, found)) removed++
        }
        if (removed > 0) {
            android.util.Log.i(
                "AutoBackup",
                "retention ${settings.retentionLabel}: removed $removed old backup file(s)"
            )
        }
        // The retention window on its own can leave a great many backups standing - a
        // till backing up every hour keeps 24 a day inside any window at all - so the
        // hard ceiling runs every time too, after it, and has the last word: at most
        // MAX_BACKUPS files are on disk when this returns, whatever the window said.
        return removed + pruneToRecentBackups(context)
    }

    /**
     * The day a backup declares itself to be from, or null where it will not say.
     *
     * READ FROM THE FILE NAME first - `synergic_backup_2026-09-08_12-34-33.sql` - and
     * only then from whatever time the store recorded. The name is what the backup
     * itself wrote down and it travels WITH the file: copy one somewhere else and it
     * still says which day it is from, where a filesystem timestamp becomes the day it
     * was copied.
     *
     * That is the property the folder-per-day layout used to provide, and the reason
     * it can be given up - see [FOLDER].
     *
     * Null rather than a guess where neither can be read. An unrecognised file in the
     * backup folder is somebody else's, and retention must not delete what it cannot
     * identify.
     */
    private fun dayOf(found: BackupFiles.Found): Date? {
        Regex("""(\d{4}-\d{2}-\d{2})""").find(found.name)?.groupValues?.get(1)?.let { named ->
            runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(named) }
                .getOrNull()?.let { return it }
        }
        return found.takenAt.takeIf { it > 0L }?.let { Date(it) }
    }

    /**
     * Deletes every backup in [FOLDER] but the [keep] most recent, however many
     * [pruneOldBackups]'s own retention window would have let stand.
     *
     * Run after EVERY backup, from all four of the places one can be taken - the
     * timer, the Backup button, Erase Bills and Restore Defaults - so the one just
     * written is itself in the count. Three already on disk plus a new fourth
     * leaves three: the new one and the two before it, and the oldest goes.
     *
     * Newest first is [BackupFiles.list]'s own order, so this is a `drop` - no day
     * is worked out and nothing is grouped. A backup left by an older version of
     * the app, filed under the same folder, is counted and aged out like any other:
     * it is still one of this shop's backups taking up the tablet.
     *
     * @return how many files were removed
     */
    fun pruneToRecentBackups(context: Context, keep: Int = MAX_BACKUPS): Int {
        val stale = BackupFiles.list(context, FOLDER).drop(keep)
        if (stale.isEmpty()) return 0

        var removed = 0
        stale.forEach { if (BackupFiles.delete(context, it)) removed++ }
        if (removed > 0) {
            android.util.Log.i(
                "AutoBackup",
                "kept the $keep most recent backup(s): removed $removed older file(s)"
            )
        }
        return removed
    }

    // ---- Naming --------------------------------------------------------------

    /**
     * Where a backup is written - always [FOLDER], whoever asked for it.
     *
     * Takes the moment anyway, and ignores it. The date used to select a subfolder
     * and now selects nothing; keeping the parameter means the four callers did not
     * have to change, and the day they pass is still recorded - in the file name.
     */
    @Suppress("UNUSED_PARAMETER")
    fun folderFor(at: Date): String = FOLDER

    /**
     * The file's name, carrying the date and the time it was taken - and, for one
     * taken ahead of an irreversible action, what that action was.
     *
     * Dashes rather than colons in the time: a colon is not a legal character in a
     * file name on the storage this lands on, and a name the system has to sanitise
     * is a name the operator cannot search for. [action] is put through the same
     * treatment and goes last, so the day's files still sort into the order they
     * were taken in.
     */
    fun fileName(at: Date, action: String? = null): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(at)
        val suffix = action?.trim()?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.US)?.replace(Regex("[^a-z0-9]+"), "_")?.trim('_')
            ?.let { "_before_$it" }
            .orEmpty()
        return "synergic_backup_$stamp$suffix.sql"
    }
}
