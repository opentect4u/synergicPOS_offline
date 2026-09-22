package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.UserManual.Block
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole manual as an A4 PDF, for Download PDF on the User Manual screen.
 *
 * ## Why a PDF at all, when the manual is already in the app
 *
 * The screen answers a question for whoever is holding the till. The file answers a
 * different one: a shop wants the manual printed and left by the counter, or sent to a
 * new member of staff who has not got the device in front of them. Neither is served by
 * text that exists only inside the app it describes.
 *
 * ## Built from the same blocks the screen draws
 *
 * This reads [UserManual.ALL] - the same headings, steps and cautions - so the file and
 * the screen cannot drift apart. What differs is only the medium: the screen has one
 * column that scrolls forever, a page is a fixed rectangle that runs out, so most of
 * what is here is deciding where a page ends.
 *
 * ## Why the type is measured rather than laid out
 *
 * There is no text engine on a PDF canvas - [Canvas.drawText] puts one line at one
 * coordinate and nothing wraps. So prose is broken into lines against the measured
 * column width ([wrap]) and each line is its own decision about whether it still fits.
 * Drawing a paragraph in one call would run it off the edge of the paper, which the
 * reader would not discover until it was printed.
 */
object UserManualPdf {

    /** Writes the manual into Downloads and returns a path fit to show the operator. */
    fun save(context: Context): String = Downloads.bytes(
        context,
        // Dated rather than overwritten: a shop that printed an older copy can tell at a
        // glance whether the file it is looking at is the one on the wall.
        "User_Manual_${UserManual.VERSION}.pdf",
        "application/pdf"
    ) { out -> write(out, context.getString(R.string.app_name)) }

    /**
     * Renders the document.
     *
     * Twice, because the contents needs page numbers and a page number is not known
     * until the text above it has been laid out - a chapter runs to one page or three
     * depending on how long its paragraphs wrap. The first pass lays the chapters out
     * against a scratch document purely to record where each one begins; the second
     * writes the real file, cover first, with those numbers in hand.
     *
     * The alternative - printing a contents with no numbers - makes the list decorative:
     * a reader can already see the chapter names on the tabs.
     *
     * Visible to the tests, which render the pages and check there is ink on them. Going
     * through [save] instead would make every run leave a file in the device's Downloads.
     *
     * [chapters] is a seam for the same tests. Every chapter as written today happens to
     * fit on one page, so the whole of the page-breaking below - the only part of this
     * that can silently put text off the paper - would otherwise never run until the day
     * somebody made a chapter longer.
     */
    internal fun write(
        out: OutputStream,
        appName: String,
        chapters: List<Pair<String, List<Block>>> = UserManual.ALL
    ) {
        val scratch = Sheet(appName)
        val starts = chapters.map { (title, blocks) -> scratch.chapter(title, blocks) }
        scratch.discard()

        val sheet = Sheet(appName)
        // +1 for the cover, which the scratch pass did not draw.
        sheet.cover(chapters.map { it.first }, starts.map { it + 1 })
        chapters.forEach { (title, blocks) -> sheet.chapter(title, blocks) }
        sheet.writeTo(out)
    }

    // A4 at 72dpi, in points - the same page the reports use, so a shop printing both is
    // not changing paper between them.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 48f
    private const val FOOTER_H = 34f

    /** The line the body must not cross; below it is the footer's room. */
    private const val BOTTOM = PAGE_H - FOOTER_H
    private const val COLUMN = PAGE_W - MARGIN * 2

    // Leading is set per size rather than taken from the font, so the space between
    // lines of prose on paper matches the 5dp the screen adds between them.
    private const val BODY_SIZE = 10.5f
    private const val BODY_LEADING = 15f
    private const val HEADING_SIZE = 13f
    private const val CHAPTER_SIZE = 20f

    private const val MARKER_COLUMN = 18f
    private const val CAUTION_BAR = 3f
    private const val CAUTION_PAD = 9f

    private const val GAP_SECTION = 14f
    private const val GAP_BLOCK = 8f
    private const val GAP_LIST_ITEM = 5f

    private const val INK = 0xFF202124.toInt()
    private const val MUTED = 0xFF80868B.toInt()

    /**
     * The brand colour, fixed rather than taken from the user's theme.
     *
     * On screen the accent is the app agreeing with itself. On paper it is ink, and a
     * pale theme that reads perfectly against a lit display prints as a heading nobody
     * can make out.
     */
    private const val BRAND = 0xFF008181.toInt()
    private const val CAUTION_FILL = 0xFFFEF7E0.toInt()
    private const val CAUTION_EDGE = 0xFFF9AB00.toInt()

    /**
     * A document being written: the page it is on, how far down it, and the paints.
     *
     * Holding the position as state rather than passing it through every draw call is
     * what lets a paragraph be written without its caller knowing whether it will fit -
     * the [need] check turns the page underneath it.
     */
    private class Sheet(private val appName: String) {

        private val doc = PdfDocument()
        private var pageNo = 0
        private var page: PdfDocument.Page? = null
        private var canvas = Canvas()
        private var y = 0f

        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = BODY_SIZE; color = INK }
        private val bold = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        private val headingPaint = Paint(bold).apply { textSize = HEADING_SIZE; color = BRAND }
        private val chapterPaint = Paint(bold).apply { textSize = CHAPTER_SIZE; color = BRAND }
        private val titlePaint = Paint(bold).apply { textSize = 30f; color = BRAND }
        private val mutedPaint = Paint(body).apply { color = MUTED }
        private val footerPaint = Paint(body).apply { textSize = 8f; color = MUTED }
        private val rule = Paint().apply { color = 0xFFDADCE0.toInt(); strokeWidth = 0.7f }
        private val fill = Paint().apply { color = CAUTION_FILL }
        private val edge = Paint().apply { color = CAUTION_EDGE }

        /** Closes the page in hand, footer and all, and starts the next. */
        private fun newPage() {
            closePage()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page!!.canvas
            y = MARGIN
        }

        private fun closePage() {
            val open = page ?: return
            // The cover carries no number: it is the front of the document, not the first
            // page of the text. Numbering still counts it, which is what makes the
            // contents agree with a reader counting sheets.
            if (pageNo > 1) {
                open.canvas.drawText("$appName - User Manual", MARGIN, PAGE_H - 20f, footerPaint)
                val n = "Page $pageNo"
                open.canvas.drawText(
                    n, PAGE_W - MARGIN - footerPaint.measureText(n), PAGE_H - 20f, footerPaint
                )
            }
            doc.finishPage(open)
            page = null
        }

        /** Turns the page unless [height] still fits under the current line. */
        private fun need(height: Float) {
            if (y + height > BOTTOM) newPage()
        }

        /** Draws [lines] at [x], breaking the page between any two of them. */
        private fun flow(lines: List<String>, paint: Paint, x: Float) {
            lines.forEach { line ->
                need(BODY_LEADING)
                canvas.drawText(line, x, y + paint.textSize, paint)
                y += BODY_LEADING
            }
        }

        /**
         * One list line: its marker in a column, the text wrapped beside it.
         *
         * Continuation lines are indented to the text and not to the marker - a hanging
         * indent, so a three-line step reads as one step rather than as three.
         */
        private fun listRow(mark: String, text: String, markPaint: Paint) {
            wrap(text, COLUMN - MARKER_COLUMN, body).forEachIndexed { i, line ->
                need(BODY_LEADING)
                if (i == 0) canvas.drawText(mark, MARGIN, y + BODY_SIZE, markPaint)
                canvas.drawText(line, MARGIN + MARKER_COLUMN, y + BODY_SIZE, body)
                y += BODY_LEADING
            }
        }

        /** The title page, and a contents naming the page each chapter opens on. */
        fun cover(titles: List<String>, pages: List<Int>) {
            newPage()
            y += 60f
            canvas.drawText("User Manual", MARGIN, y + 30f, titlePaint)
            y += 48f
            canvas.drawText(appName, MARGIN, y + 14f, Paint(bold).apply { textSize = 14f })
            y += 24f
            canvas.drawText("Version ${UserManual.VERSION}", MARGIN, y + 11f, mutedPaint)
            y += 18f
            val stamp = SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.getDefault()).format(Date())
            canvas.drawText("Generated $stamp", MARGIN, y + 11f, mutedPaint)
            y += 34f
            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
            y += 30f

            canvas.drawText("Contents", MARGIN, y + HEADING_SIZE, headingPaint)
            y += 26f
            titles.forEachIndexed { i, title ->
                canvas.drawText("${i + 1}.", MARGIN, y + BODY_SIZE, bold)
                canvas.drawText(title, MARGIN + MARKER_COLUMN, y + BODY_SIZE, body)
                val n = pages.getOrElse(i) { 0 }.toString()
                canvas.drawText(
                    n, PAGE_W - MARGIN - mutedPaint.measureText(n), y + BODY_SIZE, mutedPaint
                )
                y += 20f
            }
        }

        /**
         * One chapter, always opening a page of its own, returning the page it opened on.
         *
         * A new page per chapter rather than running them together: a chapter that starts
         * four lines from the bottom of the previous one is a chapter nobody finds when
         * thumbing a printed copy, and it is the printed copy this file exists for.
         */
        fun chapter(title: String, blocks: List<Block>): Int {
            newPage()
            canvas.drawText(title, MARGIN, y + CHAPTER_SIZE, chapterPaint)
            y += CHAPTER_SIZE + 10f
            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
            y += 22f

            blocks.forEachIndexed { index, block ->
                val first = index == 0
                when (block) {
                    is Block.Heading -> {
                        if (!first) y += GAP_SECTION
                        // Kept with the first lines of what it names: a heading alone at
                        // the foot of a page has come adrift from its own section.
                        need(HEADING_SIZE + 9f + BODY_LEADING * 2)
                        canvas.drawText(block.text, MARGIN, y + HEADING_SIZE, headingPaint)
                        y += HEADING_SIZE + 9f
                    }

                    is Block.Para -> {
                        if (!first) y += GAP_BLOCK
                        flow(wrap(block.text, COLUMN, body), body, MARGIN)
                    }

                    is Block.Steps -> block.items.forEachIndexed { n, item ->
                        y += if (n == 0) GAP_BLOCK else GAP_LIST_ITEM
                        listRow("${n + 1}.", item, bold)
                    }

                    is Block.Bullets -> block.items.forEachIndexed { n, item ->
                        y += if (n == 0) GAP_BLOCK else GAP_LIST_ITEM
                        listRow("•", item, body)
                    }

                    is Block.Caution -> caution(block.text)
                }
            }
            return pageNo
        }

        /** An amber panel with a bar down its left edge, moved whole if it will not fit. */
        private fun caution(text: String) {
            y += GAP_SECTION
            val lines = wrap(text, COLUMN - CAUTION_BAR - CAUTION_PAD * 2, body)
            val height = lines.size * BODY_LEADING + CAUTION_PAD * 2
            // Moved rather than split: a warning broken across a page fold is a warning
            // whose second half nobody reads.
            need(height)
            canvas.drawRect(MARGIN, y, PAGE_W - MARGIN, y + height, fill)
            canvas.drawRect(MARGIN, y, MARGIN + CAUTION_BAR, y + height, edge)
            var ty = y + CAUTION_PAD
            lines.forEach {
                canvas.drawText(it, MARGIN + CAUTION_BAR + CAUTION_PAD, ty + BODY_SIZE, body)
                ty += BODY_LEADING
            }
            y += height
        }

        /** Closes the last page and writes the finished document to [out]. */
        fun writeTo(out: OutputStream) {
            closePage()
            doc.writeTo(out)
            doc.close()
        }

        /**
         * Throws the document away, keeping only what was measured from it.
         *
         * The measuring pass still has to draw - a page number falls out of laying the
         * text out and nothing else - but nobody reads what it drew.
         */
        fun discard() {
            closePage()
            doc.close()
        }
    }

    /**
     * Greedy word wrap, measured with the paint that will draw the text.
     *
     * In a proportional face the width of a line is a property of its letters and not of
     * how many there are, so it has to be asked of the font rather than counted.
     */
    fun wrap(text: String, width: Float, paint: Paint): List<String> =
        wrap(text, width, paint::measureText)

    /**
     * The wrapping itself, with the measuring handed in.
     *
     * Separated from [Paint] so it can be tested. Measuring text is the one part of this
     * that needs a real font loaded on a real device; deciding where the breaks go is
     * arithmetic, and arithmetic that puts a word in the wrong place is a fault worth
     * catching without an emulator.
     */
    fun wrap(text: String, width: Float, measure: (String) -> Float): List<String> {
        val out = mutableListOf<String>()
        val line = StringBuilder()
        text.split(' ').filter { it.isNotEmpty() }.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            // A word too wide for the column on its own still goes out on its own line:
            // there is nowhere better for it, and dropping it would lose text.
            if (measure(candidate) <= width || line.isEmpty()) {
                line.setLength(0)
                line.append(candidate)
            } else {
                out += line.toString()
                line.setLength(0)
                line.append(word)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        // Never empty: a caller drawing "no lines" would silently lose a paragraph, and
        // one blank line is a gap they can see.
        return out.ifEmpty { listOf("") }
    }
}
