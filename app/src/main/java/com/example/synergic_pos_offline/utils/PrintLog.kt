package com.example.synergic_pos_offline.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-device log for the print path, written to a file rather than only
 * logcat. A till in the field usually has no adb access, so this is how a print
 * failure gets diagnosed: Printer Settings has a "View print log" screen showing
 * this file's content as selectable text, which can be copied out and sent
 * without ever connecting the device to a computer.
 */
object PrintLog {
    private const val FILE_NAME = "print_log.txt"

    /** Trimmed to this many characters on write, so the file never grows unbounded. */
    private const val MAX_FILE_CHARS = 20_000

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun d(context: Context, tag: String, message: String) {
        Log.d(tag, message)
        runCatching {
            val file = logFile(context)
            val existing = if (file.exists()) file.readText() else ""
            val line = "${timeFormat.format(Date())} [$tag] $message\n"
            val combined = existing + line
            file.writeText(if (combined.length > MAX_FILE_CHARS) combined.takeLast(MAX_FILE_CHARS) else combined)
        }
    }

    /** The full log content, oldest first, for the viewer screen. */
    fun read(context: Context): String {
        val file = logFile(context)
        if (!file.exists()) return "(no print activity logged yet - try a print, then come back here)"
        return runCatching { file.readText() }.getOrDefault("(could not read the log)")
    }

    fun clear(context: Context) {
        runCatching { logFile(context).delete() }
    }

    private fun logFile(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
