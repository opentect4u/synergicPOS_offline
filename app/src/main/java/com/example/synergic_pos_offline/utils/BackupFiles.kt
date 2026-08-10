package com.example.synergic_pos_offline.utils

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Finds the backups this app has written, and tells a backup from whatever else the
 * operator might hand it.
 *
 * ## Why finding them is not simply listing a folder
 *
 * Backups go to `Downloads/POSbackup/<date>/` through MediaStore, and from Android 10
 * an app may only read what it put there itself. That ownership does not survive the
 * app being uninstalled: **a fresh installation cannot see the backups the previous
 * installation wrote**, which is exactly the case Restore Data exists for. Asking for
 * storage permission would not fix it either - the broad one that would is the kind
 * Play restricts to file managers.
 *
 * So [list] is an accelerator, not the mechanism. It finds the files when the app
 * itself put them there - restoring after erasing the bills, or on a device the app
 * was never removed from - and comes back empty otherwise. The way in that always
 * works is the system's own file picker, which grants access to whatever the operator
 * chooses without any permission at all; the caller falls back to it, and the
 * operator navigates to Downloads/POSbackup themselves.
 */
object BackupFiles {

    /** One backup file found on the device. */
    data class Found(
        val name: String,
        val uri: Uri,
        /** When it was written, epoch millis; 0 when the store did not say. */
        val takenAt: Long,
        val bytes: Long
    )

    /**
     * The backups this app can still see, newest first.
     *
     * Empty is a normal answer, not a failure - see the note on the class. Never
     * throws: a store that refuses the query is the same to the caller as one that
     * held nothing, and both mean "use the picker".
     */
    fun list(context: Context, folder: String = AutoBackup.FOLDER): List<Found> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) fromMediaStore(context, folder)
        else fromAppExternalFiles(context, folder)
    }.getOrDefault(emptyList()).sortedByDescending { it.takenAt }

    private fun fromMediaStore(context: Context, folder: String): List<Found> {
        val found = mutableListOf<Found>()
        val columns = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.SIZE
        )
        // Matched on the folder rather than the file name so a backup an operator has
        // renamed is still offered - the name is theirs to change, the folder is ours.
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            columns,
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("%$folder%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!name.endsWith(".sql", ignoreCase = true)) continue
                found.add(
                    Found(
                        name = name,
                        uri = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)
                        ),
                        // DATE_ADDED is in seconds.
                        takenAt = c.getLong(2) * 1000L,
                        bytes = c.getLong(3)
                    )
                )
            }
        }
        return found
    }

    /**
     * Where the backups land below Android 10 - the app's own external Downloads
     * directory, which needs no permission to read.
     *
     * That directory is removed when the app is, so on those versions a fresh
     * installation finds nothing here either and goes to the picker like the rest.
     */
    private fun fromAppExternalFiles(context: Context, folder: String): List<Found> {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return emptyList()
        val root = File(base, folder)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".sql", ignoreCase = true) }
            .map { Found(it.name, Uri.fromFile(it), it.lastModified(), it.length()) }
            .toList()
    }

    /**
     * The first [lines] lines of [uri], or null when it cannot be read.
     *
     * Enough to tell a backup from anything else and to read its header, without
     * pulling a whole shop's database into memory to ask one question. The restore
     * itself streams the file.
     */
    fun headOf(context: Context, uri: Uri, lines: Int = 200): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().useLines { seq -> seq.take(lines).joinToString("\n") }
        }
    }.getOrNull()

    /**
     * Whether [head] looks like one of this app's backups.
     *
     * Two ways in, because both have to keep working: the header this app writes, and
     * the presence of any INSERT at all - which is what an older backup, or one an
     * operator has trimmed by hand, still has.
     */
    fun looksLikeBackup(head: String): Boolean =
        head.contains("INSERT INTO ", ignoreCase = true) ||
            head.contains("Synergic POS data backup")

    /** "1.4 MB" / "820 KB", for a file the operator is choosing between. */
    fun sizeLabel(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    }

    /** "10-08-2026 02:35 PM", or "" when the store did not record a time. */
    fun timeLabel(millis: Long): String =
        if (millis <= 0) ""
        else java.text.SimpleDateFormat("dd-MM-yyyy hh:mm a", java.util.Locale.US)
            .format(java.util.Date(millis))
}
