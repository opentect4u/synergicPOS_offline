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

    /**
     * Blank space under the last line of a KOT, in dp.
     *
     * On top of the roll's proportional feed margin. A kitchen ticket is torn off by
     * hand at a busy pass rather than cut cleanly, so the last line wants clear paper
     * under it - a ticket torn through its own bottom line is one the kitchen has to
     * ask about.
     */
    private const val BOTTOM_MARGIN_DP = 20f

    /**
     * Blank lines fed after the ticket, on top of [BOTTOM_MARGIN_DP].
     *
     * Counted in LINES rather than dp because that is what a feed is on a thermal
     * head, and because a line is the one unit that stays the same height on every
     * roll - print sizes here are absolute, so two lines is the same length of paper
     * on 58mm as on 80mm (see PrintType).
     *
     * They are there to clear the head. On most counter printers the last line stops
     * under the print head rather than past the tear bar, so a ticket torn straight
     * after printing takes the bottom of itself with it; the next one then starts on
     * paper that has already been through.
     */
    private const val EXTRA_FEED_LINES = 2

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
        val language = PrintLanguage.of(context)
        // PRODUCT NAMES come out in the Products master's language, not this one.
        // `language` above sets the ticket's own words - KOT NO, ITEM, QUANTITY - and
        // what the dishes are CALLED is the master's question. See [RegionalName].
        val bitmap = render(
            batch, config.paperDots, language,
            RegionalName.language(context), RegionalName.map(context)
        )
        ThermalPrinter.print(context, bitmap, config) { result ->
            when (result) {
                is ThermalPrinter.Result.Success -> report("${batch.kotNumber} printed at ${printer.printerName}")
                is ThermalPrinter.Result.Sent -> report("${batch.kotNumber} sent to ${printer.printerName}")
                is ThermalPrinter.Result.Failure -> report("KOT print failed: ${result.message}")
            }
            bitmap.recycle()
        }
    }

    /**
     * One printed line.
     *
     * [text] is the left-hand (or centred) part; [mid] and [right] are the other two
     * columns of a row that carries more than one thing - the header row of a KOT puts
     * the number on the left, the date in the middle and the time hard against the
     * right edge, and the dish rows put the quantity in a column of its own.
     *
     * Three separate draws rather than one padded string: a thermal ticket is drawn as
     * a bitmap in a proportional face, so columns spaced with spaces line up only in a
     * monospaced one and drift by a character or two on every row otherwise.
     */
    private class Line(
        val text: String,
        val paint: Paint,
        val center: Boolean = false,
        val mid: String? = null,
        val right: String? = null
    )

    /**
     * The KOT number as the ticket prints it: the running figure alone, zero-padded.
     *
     * The number is STORED as "KOT-0008" - that is what the record is called, what the
     * cancel report lists and what the toast says when the ticket goes to the printer -
     * so it is not the stored form that changes here, only how this one document
     * writes it. The row already says "KOT NO", and "KOT NO : KOT-0008" says it twice.
     *
     * Padded to four figures whatever it arrives as, so the number sits in the same
     * place on every ticket and a stack of them can be flicked through by eye. A number
     * that is not a number at all is left exactly as it is rather than mangled.
     */
    private fun kotNo(raw: String): String {
        val tail = raw.substringAfterLast('-').trim().ifBlank { raw.trim() }
        return tail.toLongOrNull()?.toString()?.padStart(4, '0') ?: tail
    }

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
        language: PrintLanguage.Language = PrintLanguage.Language.ENGLISH,
        // The language the DISHES are named in - the Products master's. Separate from
        // [language], which is the ticket's own words; see [RegionalName].
        productLanguage: PrintLanguage.Language = language,
        // The shop's own names for its products, keyed as [RegionalName.map] keys them.
        // Passed IN rather than read here, so this stays free of a Context - it is the
        // one function on this object a test can call. Empty means none is saved, which
        // falls back to translating each name, exactly as this always did.
        regionalNames: Map<String, String> = emptyMap()
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
        // Feed margin before the cut, plus a fixed 10dp under it.
        //
        // The proportional part is the roll's own feed - it scales with the paper, so
        // 58mm and 80mm both clear the cutter. The 10dp is a margin in the same unit
        // the rest of the slip is set in (see PrintType.dots), so it is the same
        // physical gap on every roll rather than a tenth of whatever width happens to
        // be fitted: on a narrow roll a proportional margin alone leaves the last line
        // sitting closer to the tear than it does on a wide one.
        // Two blank lines of the same pitch the ticket is set in, so the feed matches
        // the document rather than being a number picked in dots.
        val feedLine = item.descent() - item.ascent()
        val padBottom = width * 0.10f + PrintType.dots(BOTTOM_MARGIN_DP) +
            EXTRA_FEED_LINES * feedLine
        val gap = width * 0.012f

        // Build the line list top-to-bottom; ruleBefore holds indices to draw a rule above.
        val lines = mutableListOf<Line>()
        val ruleBefore = mutableSetOf<Int>()
        /** This ticket's own labels, in the till's print language. */
        fun t(text: String) = PrintLanguage.tr(language, text)

        // BILL KOT stays as it is in every language. It is what the trade calls this
        // document - the kitchen, the floor and the till all say it - and spelling out
        // "kitchen order ticket" in another language would name it something nobody
        // asks for.
        lines += Line("BILL KOT", title, center = true)

        // THE HEADER ROW: number, date and time across one line.
        //
        // All three are identifiers rather than words - the staff match them against a
        // table and a screen that both say the same thing - so they print as they are,
        // the way a bill number does. Spread across the width instead of stacked: it is
        // read at a glance at the pass, and three centred lines make the reader hunt
        // down the ticket for the one they want.
        ruleBefore += lines.size
        lines += Line(
            "${t("KOT NO")} : ${kotNo(batch.kotNumber)}",
            sub,
            mid = batch.date.takeIf { it.isNotBlank() },
            right = batch.time.takeIf { it.isNotBlank() }
        )
        // The table under it, on the left, where the eye lands after the number. The
        // room goes in brackets beside the code rather than on a line of its own -
        // table codes repeat across sections, so "1" alone does not say which room was
        // served, and this is the way the table is named everywhere else in the app.
        val table = if (batch.section.isNotBlank()) "${batch.tableCode} (${batch.section})"
        else batch.tableCode
        lines += Line("${t("TABLE")} : $table", sub)

        // The column heads, so the figures down the right have something naming them.
        ruleBefore += lines.size
        lines += Line(t("ITEM"), sub, right = t("QUANTITY"))

        /**
         * One dish: what, and how many - the name down the left and the quantity in its
         * own column against the right edge.
         *
         * The name is wrapped to the room left BESIDE the quantity column, not to the
         * whole width: a long dish name running under the figures would print the two
         * on top of each other. Only the first line carries the quantity; a name that
         * takes two lines is still one dish ordered once.
         */
        fun dish(name: String, qty: Double): List<Line> {
            val qtyText = String.format(java.util.Locale.US, "%.2f", qty)
            // Reserved from the widest thing this column ever holds - its own heading
            // or a four-figure quantity - so the names stop at the same place on every
            // ticket rather than wherever this one's numbers happen to end.
            val qtyCol = maxOf(
                item.measureText("0000.00"), sub.measureText(t("QUANTITY"))
            ) + gap * 2
            val wrapped = wrapToWidth(
                RegionalName.forPrint(regionalNames, productLanguage, name),
                item, width - padX * 2 - qtyCol
            )
            return wrapped.mapIndexed { i, part ->
                Line(part, item, right = if (i == 0) qtyText else null)
            }
        }

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
                    // Left, then any middle, then any right - each drawn where it
                    // belongs rather than padded into one string. The alignment is set
                    // per draw because the paints are shared between lines.
                    canvas?.drawText(line.text, padX, y, line.paint.apply { textAlign = Paint.Align.LEFT })
                    line.mid?.let {
                        canvas?.drawText(it, width / 2f, y, line.paint.apply { textAlign = Paint.Align.CENTER })
                    }
                    line.right?.let {
                        canvas?.drawText(it, width - padX, y, line.paint.apply { textAlign = Paint.Align.RIGHT })
                    }
                }
                y += line.paint.descent() + gap
            }
            // Closes the ticket under the last dish, the way the rules above open each
            // block. Without it the list of food simply stops, and on a ticket torn off
            // by hand there is nothing to say the bottom line is the bottom line rather
            // than where the paper was pulled through.
            return PrintType.drawRule(canvas, y, width, padX)
        }

        val height = (layout(null) + padBottom).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        layout(Canvas(bmp).apply { drawColor(Color.WHITE) })
        return bmp
    }
}
