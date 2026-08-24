package com.example.synergic_pos_offline.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Writes a file to the device's Downloads folder.
 *
 * Shared because two screens hand the operator a CSV - the product table's export
 * and the bulk upload's template - and a download that lands somewhere different
 * depending on which button was pressed is a download the operator has to go
 * hunting for.
 *
 * Handing the file to another app through a chooser was tried and is worse: it
 * depends on a spreadsheet app being installed to handle `text/csv`, and where none
 * is, the chooser simply does not appear and the button looks broken. Downloads is
 * somewhere the file always is.
 */
object Downloads {

    /**
     * Writes [content] as [fileName] into Downloads, returning a path fit to show
     * the operator.
     *
     * On Android 10 and up this goes through MediaStore, which needs no permission
     * and puts the file in the real Downloads folder. Below that, where writing
     * there would need a runtime permission, it goes to the app's own external
     * Downloads directory instead - reachable over USB and by a file manager,
     * without stopping to ask.
     */
    /**
     * Writes a file to Downloads a piece at a time.
     *
     * For anything that could be large - a whole database, not a product list - so
     * the file is never held in memory in one piece. [write] is handed a writer and
     * called once; whatever it writes is the file.
     */
    fun stream(
        context: Context,
        fileName: String,
        mimeType: String,
        // Folders under Downloads to put it in, e.g. "POSbackup/2026-08-08". Created
        // as needed; empty means Downloads itself.
        folder: String = "",
        // Replace any file already at this name/path instead of letting MediaStore add
        // " (1)" beside it - so a single, always-latest file can be kept.
        overwrite: Boolean = false,
        write: (java.io.Writer) -> Unit
    ): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relative = if (folder.isBlank()) Environment.DIRECTORY_DOWNLOADS
            else "${Environment.DIRECTORY_DOWNLOADS}/${folder.trim('/')}"
            val resolver = context.contentResolver
            // MediaStore never overwrites - it appends " (1)". Delete the old entry
            // first so the new write genuinely replaces it. (RELATIVE_PATH is stored
            // with a trailing slash.)
            if (overwrite) runCatching {
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
                    arrayOf("$relative/", fileName)
                )
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                // MediaStore creates the folders on the way in; there is no mkdirs to
                // call and no permission to ask for.
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("could not create file")
            resolver.openOutputStream(uri)?.use { out ->
                out.bufferedWriter().use { write(it) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "$relative/$fileName"
        } else {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val dir = if (folder.isBlank()) base else File(base, folder).apply { mkdirs() }
            File(dir, fileName).apply { bufferedWriter().use { write(it) } }.absolutePath
        }

    /**
     * The same, for a file that is not text - a PDF, a spreadsheet. [write] is handed
     * the raw stream and called once; whatever it writes is the file.
     */
    fun bytes(
        context: Context,
        fileName: String,
        mimeType: String,
        write: (java.io.OutputStream) -> Unit
    ): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("could not create file")
            resolver.openOutputStream(uri)?.use { out -> out.buffered().use { write(it) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/$fileName"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            File(dir, fileName).apply {
                outputStream().buffered().use { write(it) }
            }.absolutePath
        }

    fun save(
        context: Context,
        fileName: String,
        content: String,
        // Defaults to CSV, which is what the two export buttons produce; the database
        // backup is SQL and says so, or a file manager offers to open it in a
        // spreadsheet.
        mimeType: String = "text/csv"
    ): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("could not create file")
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/$fileName"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            File(dir, fileName).apply { writeText(content) }.absolutePath
        }
}
