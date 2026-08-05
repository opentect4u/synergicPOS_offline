package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.example.synergic_pos_offline.database.OperatingPrinterDao
import com.example.synergic_pos_offline.database.RunningOrderDao

/**
 * Renders a KOT (Kitchen Order Ticket) to a bitmap and sends it to a thermal
 * printer — the KOT counterpart of [BillPrinter]. The [printer] is a KOT row from
 * the Operating Printer master (chosen by the caller: default or picked).
 */
object KotPrinter {

    fun print(
        context: Context,
        batch: RunningOrderDao.KotBatch,
        printer: OperatingPrinterDao.OperatingPrinter,
        report: (String) -> Unit
    ) {
        val config = ThermalPrinter.configFor(printer)
        if (config == null) {
            report("KOT printer '${printer.printerName}' is not fully configured")
            return
        }
        val bitmap = render(batch, config.paperDots)
        ThermalPrinter.print(context, bitmap, config) { result ->
            when (result) {
                is ThermalPrinter.Result.Success -> report("${batch.kotNumber} printed at ${printer.printerName}")
                is ThermalPrinter.Result.Sent -> report("${batch.kotNumber} sent to ${printer.printerName}")
                is ThermalPrinter.Result.Failure -> report("KOT print failed: ${result.message}")
            }
            bitmap.recycle()
        }
    }

    /** One printed line: its text, the paint to draw it with, and whether it centers. */
    private class Line(val text: String, val paint: Paint, val center: Boolean)

    /**
     * Breaks [text] into lines that each fit within [maxWidth] when drawn with [paint],
     * splitting on spaces and hard-splitting any single word too long to fit.
     */
    private fun wrapToWidth(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (maxWidth <= 0f) return listOf(text)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() { if (current.isNotEmpty()) { out.add(current.toString()); current.clear() } }
        for (word in text.split(" ")) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current.clear(); current.append(candidate)
            } else {
                flush()
                var rest = word
                // A single word wider than the line is chopped to fit.
                while (paint.measureText(rest) > maxWidth && rest.length > 1) {
                    var cut = 1
                    while (cut < rest.length && paint.measureText(rest.substring(0, cut + 1)) <= maxWidth) cut++
                    out.add(rest.substring(0, cut))
                    rest = rest.substring(cut)
                }
                current.append(rest)
            }
        }
        flush()
        return out.ifEmpty { listOf(text) }
    }

    /** Draws the ticket at [width] dots (the printer's printable width) and returns it. */
    fun render(batch: RunningOrderDao.KotBatch, width: Int): Bitmap {
        val black = Color.BLACK
        fun paint(scale: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = black
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textSize = width * scale
        }
        val title = paint(0.095f, true)
        val sub = paint(0.05f, false)
        val item = paint(0.058f, true)
        val cancelHdr = paint(0.05f, true)
        val note = paint(0.048f, false)
        val ruleH = maxOf(2, width / 200)

        val padX = width * 0.04f
        val padTop = width * 0.04f
        val padBottom = width * 0.10f     // feed margin before the cut
        val gap = width * 0.012f

        // Build the line list top-to-bottom; ruleBefore holds indices to draw a rule above.
        val lines = mutableListOf<Line>()
        val ruleBefore = mutableSetOf<Int>()
        lines += Line("KOT", title, center = true)
        lines += Line(batch.kotNumber, sub, center = true)
        if (batch.section.isNotBlank()) lines += Line("Section: ${batch.section}", sub, center = true)
        lines += Line("Table: ${batch.tableCode}    ${batch.time}", sub, center = true)
        if (batch.lines.isNotEmpty()) ruleBefore += lines.size
        batch.lines.forEach { (name, qty) -> lines += Line("${qty.toInt()} x  $name", item, center = false) }
        // Cancelled items — a clearly separated section.
        if (batch.cancelLines.isNotEmpty()) {
            ruleBefore += lines.size
            lines += Line("** CANCELLED **", cancelHdr, center = true)
            batch.cancelLines.forEach { (name, qty) -> lines += Line("${qty.toInt()} x  $name", item, center = false) }
        }
        if (batch.note.isNotBlank()) {
            ruleBefore += lines.size
            // Wrap the note to the paper width so a long note prints in full instead
            // of running off the edge and being cut.
            wrapToWidth("Note: ${batch.note}", note, width - padX * 2).forEach {
                lines += Line(it, note, center = false)
            }
        }

        // Measure total height (each line + a gap; each rule adds its own height).
        fun lineHeight(p: Paint) = p.descent() - p.ascent()
        var height = padTop + padBottom
        lines.forEach { height += lineHeight(it.paint) + gap }
        height += ruleBefore.size * (ruleH + gap)

        val bmp = Bitmap.createBitmap(width, height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { drawColor(Color.WHITE) }
        val rule = Paint().apply { color = black; strokeWidth = ruleH.toFloat() }

        var y = padTop
        lines.forEachIndexed { index, line ->
            if (index in ruleBefore) {
                y += gap
                canvas.drawLine(padX, y, width - padX, y, rule)
                y += ruleH + gap
            }
            y -= line.paint.ascent()
            if (line.center) canvas.drawText(line.text, width / 2f, y, line.paint.apply { textAlign = Paint.Align.CENTER })
            else canvas.drawText(line.text, padX, y, line.paint.apply { textAlign = Paint.Align.LEFT })
            y += line.paint.descent() + gap
        }
        return bmp
    }
}
