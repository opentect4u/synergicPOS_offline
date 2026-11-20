package com.synergic.posreports

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Writes a file to the phone's Downloads folder - the till's own Downloads helper, so
 * a report downloaded on the phone lands where one downloaded on the till does.
 *
 * On Android 10 and up this goes through MediaStore, which needs no permission. Below
 * that it goes to the app's own external Downloads directory, without asking.
 */
object Downloads {

    /** [write] is handed the raw stream and called once; returns a path to show. */
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
}
