package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.AppSettingsDao
import java.text.SimpleDateFormat
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
 * picked out without opening any of them. Manual backups land in the same place: two
 * conventions for the same file would only mean hunting in two places.
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

    // ---- The setting ---------------------------------------------------------

    data class Settings(val enabled: Boolean, val intervalHours: Int)

    fun settings(context: Context): Settings {
        val dao = AppSettingsDao(context)
        val hours = dao.get(KEY_INTERVAL)?.toIntOrNull() ?: DEFAULT_INTERVAL_HOURS
        return Settings(
            enabled = dao.get(KEY_ENABLED) == "1",
            intervalHours = hours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)
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

    fun save(context: Context, enabled: Boolean, intervalHours: Int) {
        val dao = AppSettingsDao(context)
        dao.put(KEY_ENABLED, if (enabled) "1" else "0")
        dao.put(
            KEY_INTERVAL,
            intervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS).toString()
        )
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
            val savedTo = Downloads.stream(
                context, fileName(now), "application/sql", folderFor(now)
            ) { writer -> DatabaseBackup.exportTo(context, writer) }
            AppSettingsDao(context).put(KEY_LAST_RUN, System.currentTimeMillis().toString())
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
        return Downloads.stream(
            context, fileName(now, action), "application/sql", folderFor(now)
        ) { writer ->
            DatabaseBackup.exportTo(context, writer, DatabaseBackup.DEVICE_IDENTITY)
        }
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
