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
 * `Downloads/POSbackup/<date>/synergic_backup_<date>_<time>.sql` - a folder per day,
 * so a fortnight of hourly backups is fourteen folders rather than three hundred
 * files in one, and a name that says when it was taken so the right one can be
 * picked out without opening any of them. Manual backups, and the safety copy taken
 * before an irreversible action, land in the same place: two conventions for the
 * same file would only mean hunting in two places.
 *
 * Never more than [MAX_FOLDERS] of those day-folders stand at once - the oldest
 * goes the moment a backup would make a fourth, whichever of the three ways here
 * took it. See [pruneToRecentFolders].
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

    /** The folder every backup goes under, automatic or not. */
    const val FOLDER = "POSbackup"

    /** How often, when nobody has said otherwise. */
    const val DEFAULT_INTERVAL_HOURS = 1

    /**
     * The narrowest and widest gap that can be asked for.
     *
     * Below an hour the till would spend its day reading its own database; above a
     * week the backup is old enough that restoring it loses a shop's work.
     */
    const val MIN_INTERVAL_HOURS = 1
    const val MAX_INTERVAL_HOURS = 168

    /**
     * How long a backup is kept before it is cleared away - a number and the unit
     * it counts in, typed on the About screen rather than chosen off a fixed list:
     * "keep for 5 years" is not a figure seven/fifteen/thirty/sixty/ninety days
     * could ever say.
     *
     * Shorter than [MAX_FOLDERS]' own hard ceiling of 3 day-folders and it never
     * matters - [MAX_FOLDERS] is already tighter; longer, and this is the one
     * actually deciding what stays. Both run on every prune (see
     * [pruneOldBackups]), so a shop that wants years of backups on file genuinely
     * gets them, and a till backing up hourly still never holds more than 3 days.
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
     * The most day-folders [FOLDER] is ever left holding, whatever the retention
     * days setting says.
     *
     * A HARD CEILING, not a choice on a settings screen: a till backing up every
     * hour would otherwise keep a folder a day for as long as
     * [Settings.retentionDays] says to, which is a lot of hourly backups sitting
     * on a shop's device for 30, 60 or 90 days. Whichever backup was just taken -
     * automatic, manual, or the safety copy before an irreversible action -
     * always leaves at most this many days on disk; see [pruneToRecentFolders].
     */
    const val MAX_FOLDERS = 3

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
            intervalHours = hours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS),
            retentionValue = value.coerceIn(MIN_RETENTION_VALUE, MAX_RETENTION_VALUE),
            retentionUnit = RetentionUnit.fromStored(dao.get(KEY_RETENTION_UNIT))
        )
    }

    /**
     * [typed] read as a number of hours, or null where it is not one to accept.
     *
     * Whole numbers above zero only. Zero would have the till backing up
     * continuously, a fraction of an hour is not something this schedules in, and an
     * empty box is not a decision - so each is refused rather than quietly turned
     * into something else. Refusing here, rather than clamping, is what lets the
     * screen say *why* instead of silently storing a different number than was typed.
     */
    fun validHours(typed: String): Int? {
        val trimmed = typed.trim()
        if (trimmed.isEmpty() || !trimmed.all { it.isDigit() }) return null
        val value = trimmed.toIntOrNull() ?: return null
        return value.takeIf { it in MIN_INTERVAL_HOURS..MAX_INTERVAL_HOURS }
    }

    fun save(
        context: Context,
        enabled: Boolean,
        intervalHours: Int,
        retentionValue: Int = settings(context).retentionValue,
        retentionUnit: RetentionUnit = settings(context).retentionUnit
    ) {
        val dao = AppSettingsDao(context)
        dao.put(KEY_ENABLED, if (enabled) "1" else "0")
        dao.put(
            KEY_INTERVAL,
            intervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS).toString()
        )
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
     * A backup's day is read from the folder it sits in (`POSbackup/2026-08-17`)
     * rather than from a file timestamp: the folder is what the backup itself
     * declared its day to be, and it survives the file being copied about.
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
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        /** True when [folderDay] ("2026-08-17") falls before the window opens. */
        fun expired(folderDay: String): Boolean = runCatching {
            dayFormat.parse(folderDay)!!.before(cutoff)
        }.getOrDefault(false)   // an unreadable name is left alone rather than deleted

        var removed = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH
            )
            // Everything this app has filed under the backup folder.
            val where = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/%")
            runCatching {
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, where, args, null
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val pathCol = c.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        // ".../POSbackup/2026-08-17/" - the day is the last part.
                        val day = c.getString(pathCol).orEmpty().trim('/').substringAfterLast('/')
                        if (!expired(day)) continue
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idCol)
                        )
                        if (runCatching { resolver.delete(uri, null, null) }.getOrDefault(0) > 0) removed++
                    }
                }
            }
        } else {
            val base = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            base.listFiles()?.forEach { dayDir ->
                if (!dayDir.isDirectory || !expired(dayDir.name)) return@forEach
                dayDir.listFiles()?.forEach { if (runCatching { it.delete() }.getOrDefault(false)) removed++ }
                runCatching { dayDir.delete() }
            }
        }
        if (removed > 0) {
            android.util.Log.i(
                "AutoBackup",
                "retention ${settings.retentionLabel}: removed $removed old backup file(s)"
            )
        }
        // The days window on its own can leave a great many folders standing - a
        // till backing up every hour under a 60- or 90-day setting - so the hard
        // ceiling runs every time too, after it, so it is pruning whatever the days
        // window left rather than a folder list already down to what a caller
        // expects to see it act on.
        return removed + pruneToRecentFolders(context)
    }

    /**
     * Deletes whichever of [FOLDER]'s day-folders are not among the [MAX_FOLDERS]
     * most recent, however many days [pruneOldBackups]'s own window would have let
     * stand.
     *
     * A day is read off each file's own recorded time rather than off a MediaStore
     * path, so it works the same way on every OS version [BackupFiles.list] does -
     * one function, not the two branches [pruneOldBackups] still needs for its own
     * WHERE-clause deletion. Every file of a day outside the kept set goes with it;
     * a folder is not a real object in either storage model, only the files filed
     * under its name are.
     *
     * @return how many files were removed
     */
    fun pruneToRecentFolders(context: Context, keepFolders: Int = MAX_FOLDERS): Int {
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        // Newest first already - see BackupFiles.list - so the first appearance of
        // each day, in order, is the order the days themselves were last written in.
        val all = BackupFiles.list(context, FOLDER)
        val days = all.map { dayFormat.format(Date(it.takenAt)) }.distinct()
        val staleDays = days.drop(keepFolders).toSet()
        if (staleDays.isEmpty()) return 0

        var removed = 0
        all.forEach { found ->
            if (dayFormat.format(Date(found.takenAt)) in staleDays) {
                if (BackupFiles.delete(context, found)) removed++
            }
        }
        if (removed > 0) {
            android.util.Log.i(
                "AutoBackup",
                "kept the $keepFolders most recent day(s): removed $removed backup file(s) " +
                    "from ${staleDays.size} older day(s)"
            )
        }
        return removed
    }

    // ---- Naming --------------------------------------------------------------

    /** The day's folder: `POSbackup/2026-08-08`. */
    fun folderFor(at: Date): String =
        "$FOLDER/" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(at)

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
