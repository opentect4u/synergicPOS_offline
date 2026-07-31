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
    fun save(context: Context, fileName: String, content: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
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
