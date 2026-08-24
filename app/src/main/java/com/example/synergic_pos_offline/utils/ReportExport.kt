package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A generated report, handed to the operator as a file.
 *
 * Every report on this till is the same thing on screen - a title, the period it
 * covers, a table, and the totals under it - so it is described here once, as
 * [Sheet], and written out two ways. A report screen fills in a [Sheet] from what it
 * has already drawn, which is what makes the download show exactly the figures that
 * were generated rather than a second reading of the database that could differ.
 *
 * Both writers are hand-rolled against the platform: `.xlsx` is a zip of XML parts
 * written through [java.util.zip], and the PDF through [PdfDocument]. Neither needs
 * a library, which matters for a till that is built and shipped offline.
 */
object ReportExport {

    /** One column's worth of alignment: figures right, words left. */
    data class Sheet(
        val title: String,
        val subtitle: String,
        val columns: List<String>,
        val alignEnd: List<Boolean>,
        val rows: List<List<String>>,
        /** The totals block under the table; the last pair is the headline figure. */
        val summary: List<Pair<String, String>> = emptyList()
    )

    // ---- Files ---------------------------------------------------------------

    /**
     * `Tax_Report_2026-08-21_1802` - the report and when it was taken, so a folder of
     * them sorts by report and then by run, and a second run does not overwrite the
     * first.
     */
    fun fileBase(title: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        val name = title.trim().replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        return "${name.ifBlank { "Report" }}_$stamp"
    }

    /** Writes [sheet] to Downloads as a spreadsheet. Returns the path to tell them. */
    fun toExcel(context: Context, sheet: Sheet): String = Downloads.bytes(
        context,
        "${fileBase(sheet.title)}.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    ) { out -> writeXlsx(out, sheet) }

    /** Writes [sheet] to Downloads as a PDF. Returns the path to tell them. */
    fun toPdf(context: Context, sheet: Sheet): String = Downloads.bytes(
        context, "${fileBase(sheet.title)}.pdf", "application/pdf"
    ) { out -> writePdf(out, sheet) }

    // ---- Spreadsheet ---------------------------------------------------------

    /**
     * A minimal but genuine .xlsx: the six parts Excel insists on, zipped.
     *
     * Text goes in as an inline string rather than through a shared-strings table -
     * a report is written once and never edited here, so the table would only add a
     * part to get wrong. Anything that reads as a number goes in as one (see
     * [numberOf]), so a column of amounts can be summed in the spreadsheet instead of
     * being a column of text that looks like money.
     */
    private fun writeXlsx(out: OutputStream, sheet: Sheet) {
        val zip = ZipOutputStream(out)

        fun part(name: String, body: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(body.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        part(
            "[Content_Types].xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""
        )
        part(
            "_rels/.rels",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
        )
        part(
            "xl/workbook.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="${sheetName(sheet.title)}" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""
        )
        part(
            "xl/_rels/workbook.xml.rels",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
        )
        // Style 0 plain, 1 the report title, 2 a column heading, 3 a total.
        part(
            "xl/styles.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="3">
<font><sz val="11"/><name val="Calibri"/></font>
<font><b/><sz val="14"/><name val="Calibri"/></font>
<font><b/><sz val="11"/><name val="Calibri"/></font>
</fonts>
<fills count="3">
<fill><patternFill patternType="none"/></fill>
<fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFE8EEF7"/><bgColor indexed="64"/></patternFill></fill>
</fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="4">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="0" fontId="2" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
        )
        part("xl/worksheets/sheet1.xml", sheetXml(sheet))
        zip.finish()
        zip.flush()
    }

    private fun sheetXml(sheet: Sheet): String {
        val sb = StringBuilder(1024 + sheet.rows.size * 128)
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")

        // Column widths from the widest thing that will sit in each, so the file opens
        // readable instead of as a row of #### and clipped names.
        sb.append("<cols>")
        sheet.columns.indices.forEach { c ->
            val widest = (sequenceOf(sheet.columns.getOrElse(c) { "" }) +
                sheet.rows.asSequence().take(500).map { it.getOrElse(c) { "" } })
                .maxOf { it.length }
            val width = (widest + 4).coerceIn(10, 60)
            sb.append("""<col min="${c + 1}" max="${c + 1}" width="$width" customWidth="1"/>""")
        }
        sb.append("</cols><sheetData>")

        var r = 1
        // Title and period, then a blank line, then the table - the same order the
        // screen reads in.
        sb.append(rowXml(r++, listOf(sheet.title), style = 1))
        if (sheet.subtitle.isNotBlank()) sb.append(rowXml(r++, listOf(sheet.subtitle)))
        sb.append(rowXml(r++, emptyList()))
        sb.append(rowXml(r++, sheet.columns, style = 2))
        sheet.rows.forEach { row -> sb.append(rowXml(r++, row, numeric = true)) }
        if (sheet.summary.isNotEmpty()) {
            sb.append(rowXml(r++, emptyList()))
            sheet.summary.forEach { (label, value) ->
                sb.append(rowXml(r++, listOf(label, value), style = 3, numeric = true))
            }
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun rowXml(rowNo: Int, cells: List<String>, style: Int = 0, numeric: Boolean = false): String {
        if (cells.isEmpty()) return """<row r="$rowNo"/>"""
        val sb = StringBuilder()
        sb.append("""<row r="$rowNo">""")
        cells.forEachIndexed { i, text ->
            val ref = "${columnRef(i)}$rowNo"
            val s = if (style == 0) "" else """ s="$style""""
            val number = if (numeric) numberOf(text) else null
            if (number != null) {
                sb.append("""<c r="$ref"$s><v>$number</v></c>""")
            } else if (text.isNotEmpty()) {
                sb.append("""<c r="$ref"$s t="inlineStr"><is><t xml:space="preserve">${esc(text)}</t></is></c>""")
            }
        }
        sb.append("</row>")
        return sb.toString()
    }

    /** "A", "B" … "AA". Reports are never 27 columns wide, but the rule is cheap. */
    private fun columnRef(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.toString()
    }

    /**
     * [text] as a number when it is one, else null.
     *
     * A report formats its figures for reading - "₹ 1,240.00", "12.5%", "(300.00)" -
     * and a spreadsheet cannot add up any of those as written. The decoration comes
     * off here so the column arrives as numbers. Anything left holding a letter is
     * left alone: a bill number like "INV-9" is not arithmetic.
     */
    private fun numberOf(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val negative = t.startsWith("(") && t.endsWith(")")
        val bare = t.trim('(', ')')
            .replace("₹", "")      // ₹
            .replace(",", "")
            .replace("%", "")
            .replace(" ", "")
        if (bare.isEmpty()) return null
        val value = bare.toDoubleOrNull() ?: return null
        return if (negative) (-value).toString() else value.toString()
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
        // A control character is not legal XML and would make the file unopenable.
        .filter { it >= ' ' || it == '\t' }

    /** Excel refuses a sheet name over 31 characters or holding []:*?/\ */
    private fun sheetName(title: String): String =
        esc(title.replace(Regex("[\\[\\]:*?/\\\\]"), " ")).take(31).ifBlank { "Report" }

    // ---- PDF -----------------------------------------------------------------

    private const val PAGE_W = 595      // A4 at 72dpi, in points
    private const val PAGE_H = 842
    private const val MARGIN = 32f
    private const val ROW_H = 18f
    private const val CELL_PAD = 6f

    /**
     * The report as A4, paginated, with its column headings repeated on every page.
     *
     * Widths are measured from the text itself and then scaled to the page, so a
     * report of four short figures does not print as four narrow columns against a
     * band of white, and one with a long item name gives that name the room.
     */
    private fun writePdf(out: OutputStream, sheet: Sheet) {
        val doc = PdfDocument()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = 0xFF202124.toInt() }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val titlePaint = Paint(bold).apply { textSize = 16f }
        val subtitlePaint = Paint(text).apply { textSize = 10f; color = 0xFF5F6368.toInt() }
        val footerPaint = Paint(text).apply { textSize = 8f; color = 0xFF80868B.toInt() }
        val line = Paint().apply { color = 0xFFDADCE0.toInt(); strokeWidth = 0.7f }
        val headerFill = Paint().apply { color = 0xFFE8EEF7.toInt() }
        val zebra = Paint().apply { color = 0xFFF7F8FA.toInt() }

        val widths = columnWidths(sheet, bold, text)
        val stamp = SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.getDefault()).format(Date())

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun footer(c: Canvas) {
            c.drawText("Page $pageNo", MARGIN, PAGE_H - 18f, footerPaint)
            val gen = "Generated $stamp"
            c.drawText(gen, PAGE_W - MARGIN - footerPaint.measureText(gen), PAGE_H - 18f, footerPaint)
        }

        fun newPage() {
            page?.let { footer(it.canvas); doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page!!.canvas
            y = MARGIN
            if (pageNo == 1) {
                canvas!!.drawText(sheet.title, MARGIN, y + 14f, titlePaint)
                y += 24f
                if (sheet.subtitle.isNotBlank()) {
                    canvas!!.drawText(sheet.subtitle, MARGIN, y + 10f, subtitlePaint)
                    y += 18f
                }
                y += 6f
            } else {
                canvas!!.drawText("${sheet.title} (continued)", MARGIN, y + 10f, subtitlePaint)
                y += 22f
            }
            // Column headings, on every page: a table whose second page is unlabelled
            // is a page of loose figures.
            canvas!!.drawRect(MARGIN, y, PAGE_W - MARGIN, y + ROW_H, headerFill)
            drawRow(canvas!!, sheet.columns, widths, sheet.alignEnd, y, bold)
            y += ROW_H
            canvas!!.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line)
        }

        newPage()
        sheet.rows.forEachIndexed { i, row ->
            if (y + ROW_H > PAGE_H - MARGIN - 20f) newPage()
            if (i % 2 == 1) canvas!!.drawRect(MARGIN, y, PAGE_W - MARGIN, y + ROW_H, zebra)
            drawRow(canvas!!, row, widths, sheet.alignEnd, y, text)
            y += ROW_H
        }

        if (sheet.summary.isNotEmpty()) {
            if (y + ROW_H * (sheet.summary.size + 1) > PAGE_H - MARGIN - 20f) newPage()
            y += 10f
            canvas!!.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line)
            y += 6f
            sheet.summary.forEachIndexed { i, (label, value) ->
                // The last figure is the one the report is read for.
                val paint = if (i == sheet.summary.lastIndex) bold else text
                canvas!!.drawText(label, MARGIN + CELL_PAD, y + 12f, paint)
                canvas!!.drawText(
                    value, PAGE_W - MARGIN - CELL_PAD - paint.measureText(value), y + 12f, paint
                )
                y += ROW_H
            }
        }

        page?.let { footer(it.canvas); doc.finishPage(it) }
        doc.writeTo(out)
        doc.close()
    }

    private fun drawRow(
        canvas: Canvas,
        cells: List<String>,
        widths: FloatArray,
        alignEnd: List<Boolean>,
        top: Float,
        paint: Paint
    ) {
        var x = MARGIN
        widths.forEachIndexed { i, w ->
            val raw = cells.getOrElse(i) { "" }
            val room = w - CELL_PAD * 2
            val body = fit(raw, room, paint)
            val tx = if (alignEnd.getOrElse(i) { false }) {
                x + w - CELL_PAD - paint.measureText(body)
            } else {
                x + CELL_PAD
            }
            canvas.drawText(body, tx, top + 12.5f, paint)
            x += w
        }
    }

    /** [text] cut to [room] with an ellipsis, rather than run into the next column. */
    private fun fit(text: String, room: Float, paint: Paint): String {
        if (room <= 0f || paint.measureText(text) <= room) return text
        var cut = text
        while (cut.isNotEmpty() && paint.measureText("$cut…") > room) cut = cut.dropLast(1)
        return "$cut…"
    }

    /**
     * What each column is drawn at: the width its own content wants, all scaled to
     * the page. Only the first few hundred rows are measured - past that the widest
     * cell has almost certainly already been seen, and a 20,000-row report should not
     * be walked twice to find out.
     */
    private fun columnWidths(sheet: Sheet, header: Paint, body: Paint): FloatArray {
        val n = sheet.columns.size.coerceAtLeast(1)
        val wanted = FloatArray(n) { i ->
            val head = header.measureText(sheet.columns.getOrElse(i) { "" })
            val widest = sheet.rows.asSequence().take(300)
                .map { body.measureText(it.getOrElse(i) { "" }) }
                .maxOrNull() ?: 0f
            maxOf(head, widest) + CELL_PAD * 2
        }
        val available = PAGE_W - MARGIN * 2
        val total = wanted.sum().takeIf { it > 0f } ?: return FloatArray(n) { available / n }
        return FloatArray(n) { wanted[it] * available / total }
    }
}
