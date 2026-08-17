package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
        val bitmap = render(batch, config.paperDots, PrintLanguage.of(context))
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

    /**
     * Draws the ticket at [width] dots (the printer's printable width) and returns it.
     *
     * [language] puts the whole ticket into the till's print language - the dish
     * names the way the bill does them, and its own labels beside them. The kitchen
     * reads this ticket, and it is the one document in the shop whose reader is most
     * likely to want it in their own language rather than the owner's.
     *
     * Three things stay as they are, and each for the same reason: they are matched
     * against something else rather than read.
     *
     *  * **KOT**, because that is what the trade calls this document - the floor asks
     *    for a KOT and the kitchen hands one back;
     *  * the **KOT number**, the **table code** and the **time**, which are
     *    identifiers - the staff match them against a table and a screen that both
     *    say "T1", so translating them would break the match;
     *  * the **note**, which the floor typed for the kitchen in their own words.
     */
    fun render(
        batch: RunningOrderDao.KotBatch,
        width: Int,
        language: PrintLanguage.Language = PrintLanguage.Language.ENGLISH
    ): Bitmap {
        // Set from [PrintType], so the ticket carries the same face and the same
        // sizes as the bill. These used to be fractions of the paper width, which
        // made a KOT off a 58mm roll a visibly smaller document than one off an 80mm
        // roll - and neither of them the size of anything else the till printed.
        val title = PrintType.paint(PrintType.STORE_NAME_SP, bold = true)
        val sub = PrintType.paint(PrintType.BODY_SP)
        val item = PrintType.paint(PrintType.BODY_SP, bold = true)
        val cancelHdr = PrintType.paint(PrintType.BODY_SP, bold = true)
        val note = PrintType.paint(PrintType.SMALL_SP)

        val padX = width * 0.04f
        val padTop = width * 0.04f
        val padBottom = width * 0.10f     // feed margin before the cut
        val gap = width * 0.012f

        // Build the line list top-to-bottom; ruleBefore holds indices to draw a rule above.
        val lines = mutableListOf<Line>()
        val ruleBefore = mutableSetOf<Int>()
        /** This ticket's own labels, in the till's print language. */
        fun t(text: String) = PrintLanguage.tr(language, text)

        // KOT stays KOT in every language. It is what the trade calls this document -
        // the kitchen, the floor and the till all say it - and spelling out "kitchen
        // order ticket" in another language would name it something nobody asks for.
        lines += Line("KOT", title, center = true)
        // The KOT number, the table code and the time are identifiers, not words: the
        // staff match them against a physical table and a screen that both say "T1",
        // so they print as they are, the way a bill number does.
        lines += Line(batch.kotNumber, sub, center = true)
        if (batch.section.isNotBlank()) {
            lines += Line("${t("SECTION")}: ${batch.section}", sub, center = true)
        }
        lines += Line("${t("TABLE")}: ${batch.tableCode}    ${batch.time}", sub, center = true)
        /**
         * One dish: how many, and what - in the print language, and wrapped.
         *
         * Wrapped because it has to be. A dish line is the one thing on this ticket
         * that cannot be allowed to run off the edge, and a name in another script is
         * not the width the English one was. The note below it has always been
         * wrapped for the same reason; this simply stops the item lines being the
         * exception.
         */
        fun dish(name: String, qty: Double): List<Line> =
            wrapToWidth(
                "${qty.toInt()} x  ${ProductName.inPrintLanguage(language, name)}",
                item, width - padX * 2
            ).map { Line(it, item, center = false) }

        if (batch.lines.isNotEmpty()) ruleBefore += lines.size
        batch.lines.forEach { (name, qty) -> lines += dish(name, qty) }
        // Cancelled items — a clearly separated section.
        if (batch.cancelLines.isNotEmpty()) {
            ruleBefore += lines.size
            lines += Line("** ${t("CANCELLED")} **", cancelHdr, center = true)
            batch.cancelLines.forEach { (name, qty) -> lines += dish(name, qty) }
        }
        if (batch.note.isNotBlank()) {
            ruleBefore += lines.size
            // Wrap the note to the paper width so a long note prints in full instead
            // of running off the edge and being cut.
            // The note itself is what the floor typed for the kitchen - their words,
            // in whatever language they wrote them, so only the label is translated.
            wrapToWidth("${t("NOTE")}: ${batch.note}", note, width - padX * 2).forEach {
                lines += Line(it, note, center = false)
            }
        }

        // Measured and drawn by walking the same list twice, so the height reserved
        // is the height used - the rule is a line of text now, not a bar of known
        // thickness, and guessing at it would crop the ticket.
        fun layout(canvas: Canvas?): Float {
            var y = padTop
            lines.forEachIndexed { index, line ->
                if (index in ruleBefore) y = PrintType.drawRule(canvas, y, width, padX)
                y -= line.paint.ascent()
                if (line.center) {
                    canvas?.drawText(line.text, width / 2f, y, line.paint.apply { textAlign = Paint.Align.CENTER })
                } else {
                    canvas?.drawText(line.text, padX, y, line.paint.apply { textAlign = Paint.Align.LEFT })
                }
                y += line.paint.descent() + gap
            }
            return y
        }

        val height = (layout(null) + padBottom).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        layout(Canvas(bmp).apply { drawColor(Color.WHITE) })
        return bmp
    }
}
