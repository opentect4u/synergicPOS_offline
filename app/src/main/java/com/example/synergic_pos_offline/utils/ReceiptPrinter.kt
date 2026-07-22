package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.view.View
import java.io.FileOutputStream

/**
 * Sends a rendered receipt to the system print service.
 *
 * The receipt is already laid out on screen, so it is captured as it appears
 * rather than re-composed for print - what the operator sees is what comes out.
 * Uses the framework print stack, so it reaches any configured printer and falls
 * back to "Save as PDF" where none is set up.
 */
object ReceiptPrinter {

    /**
     * @param view the receipt view to capture
     * @param jobName appears in the system print queue, e.g. the bill number
     * @param onRendered invoked once the document is actually written, so a caller
     *        can record the print; not called if the operator cancels
     */
    fun print(context: Context, view: View, jobName: String, onRendered: () -> Unit = {}) {
        val bitmap = capture(view) ?: return
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        printManager.print(jobName, ReceiptAdapter(bitmap, jobName, onRendered), null)
    }

    /**
     * Draws [view] into a bitmap at its current size. Shared with the thermal
     * printer path so both produce an identical receipt image.
     */
    fun capture(view: View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        return runCatching {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                // The card draws its own background, but a transparent margin would
                // print as black on some services, so start from white.
                canvas.drawColor(Color.WHITE)
                view.draw(canvas)
            }
        }.getOrNull()
    }

    private class ReceiptAdapter(
        private val bitmap: Bitmap,
        private val jobName: String,
        private val onRendered: () -> Unit
    ) : PrintDocumentAdapter() {

        private var reported = false

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            callback.onLayoutFinished(
                PrintDocumentInfo.Builder("$jobName.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build(),
                // Attributes always change enough to warrant a re-render.
                true
            )
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            val document = PdfDocument()
            try {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return
                }

                // A receipt is tall and narrow; the page is sized to the capture so
                // the aspect ratio survives, and the print service scales to paper.
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
                )
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(bitmap, null, Rect(0, 0, bitmap.width, bitmap.height), null)
                document.finishPage(page)

                FileOutputStream(destination.fileDescriptor).use { document.writeTo(it) }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))

                // Only report the first successful render: the service may re-write
                // the document when the operator changes paper size or orientation.
                if (!reported) {
                    reported = true
                    onRendered()
                }
            } catch (e: Exception) {
                android.util.Log.e("ReceiptPrinter", "Could not write the receipt", e)
                callback.onWriteFailed(e.message)
            } finally {
                document.close()
            }
        }
    }
}
