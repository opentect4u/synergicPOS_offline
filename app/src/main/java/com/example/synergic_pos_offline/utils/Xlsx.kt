package com.example.synergic_pos_offline.utils

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Excel workbooks, read and written without a library.
 *
 * ## Why this is hand-rolled
 *
 * An `.xlsx` is a ZIP of XML files, and both halves of that are in the platform
 * already - [java.util.zip] and the SAX parser. What a spreadsheet library would
 * add here is everything this app does not want: Apache POI is tens of thousands of
 * classes built against `javax.xml` APIs Android does not ship, and it drags an APK
 * that installs on a shop's tablet into a different size class entirely. For one
 * sheet of plain cells, that is a large price for a small job.
 *
 * So this handles exactly the shape the item master needs: ONE sheet, a heading row,
 * and text cells. It is not a spreadsheet engine - there are no formulas, styles,
 * merges, dates or number formats here, and none are wanted. A quantity or a rate is
 * written as the digits the operator typed, because that is what the importer parses
 * back out.
 *
 * ## What it will read
 *
 * More than it writes, deliberately, because the file coming back has been through
 * Excel or WPS or Google Sheets and will not look like the one that went out. Cells
 * arrive as shared strings, as inline strings, or as bare numbers, and a row skips
 * the columns it left empty - so a cell's column is taken from its own `r="C7"`
 * reference rather than from its position among its siblings. A sheet whose blank
 * cells were dropped still lines up with its headings.
 */
object Xlsx {

    /** The file extension and the MIME type a spreadsheet is offered under. */
    const val EXTENSION = "xlsx"
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    // ---- Writing ------------------------------------------------------------

    /**
     * [rows] as a one-sheet workbook, ready to be written to a file.
     *
     * Every cell is written as an INLINE string - `<is><t>` - rather than through a
     * shared-strings table. A shared table is how Excel saves space when a value
     * repeats across thousands of cells; a template of a heading row and five
     * examples has nothing to share, and the table is a second file to keep in step
     * with the sheet for no gain. Every reader accepts inline strings.
     *
     * Text throughout, including the figures. The importer reads its own columns and
     * parses what it finds ([String.toDoubleOrNull] and friends), so a rate typed as
     * "498.75" has to come back as those characters and not as a float Excel has
     * decided to render as 498.7500000001.
     */
    fun write(rows: List<List<String>>, sheetName: String = "Sheet1"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.entry("[Content_Types].xml", CONTENT_TYPES)
            zip.entry("_rels/.rels", ROOT_RELS)
            zip.entry("xl/workbook.xml", workbook(sheetName))
            zip.entry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            zip.entry("xl/worksheets/sheet1.xml", sheet(rows))
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.entry(name: String, body: String) {
        putNextEntry(ZipEntry(name))
        write(body.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sheet(rows: List<List<String>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="$NS_MAIN"><sheetData>""")
        rows.forEachIndexed { r, cells ->
            append("""<row r="${r + 1}">""")
            cells.forEachIndexed { c, value ->
                // An empty cell is left out entirely rather than written as an empty
                // string - it is what Excel itself does, and the reader places cells
                // by their reference, so nothing shifts.
                if (value.isNotEmpty()) {
                    append("""<c r="${columnName(c)}${r + 1}" t="inlineStr"><is><t xml:space="preserve">""")
                    append(escape(value))
                    append("""</t></is></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    /** 0 -> A, 25 -> Z, 26 -> AA - the spreadsheet column names. */
    private fun columnName(index: Int): String {
        var n = index
        val name = StringBuilder()
        while (n >= 0) {
            name.insert(0, ('A' + (n % 26)))
            n = n / 26 - 1
        }
        return name.toString()
    }

    /**
     * XML-safe text.
     *
     * Control characters are dropped rather than escaped: they are not legal in XML
     * 1.0 at all, and a stray one in a pasted product name would make the whole
     * workbook unopenable - a file Excel refuses is worse than a name missing a
     * character nobody can see.
     */
    private fun escape(value: String): String = buildString {
        value.forEach { ch ->
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\'' -> append("&apos;")
                ch == '\n' || ch == '\t' -> append(ch)
                ch.code < 0x20 -> Unit
                else -> append(ch)
            }
        }
    }

    // ---- Reading ------------------------------------------------------------

    /**
     * The first sheet of [input], as rows of cells.
     *
     * Rows come back padded to the widest row in the sheet, so a caller can index
     * columns by heading position without checking the length of every row.
     *
     * Returns an empty list for anything that is not a readable workbook, so a caller
     * can offer this and CSV from one button and let the file say which it is - see
     * [looksLikeXlsx].
     */
    fun read(input: InputStream): List<List<String>> {
        var sheetXml: ByteArray? = null
        var sharedXml: ByteArray? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    // The first worksheet, whatever it is called. A workbook saved by
                    // Excel names it sheet1.xml; some writers number from elsewhere.
                    sheetXml == null && entry.name.startsWith("xl/worksheets/") &&
                        entry.name.endsWith(".xml") -> sheetXml = zip.readBytes()
                    entry.name == "xl/sharedStrings.xml" -> sharedXml = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        val sheet = sheetXml ?: return emptyList()
        val shared = sharedXml?.let { sharedStrings(it) } ?: emptyList()
        return rowsOf(sheet, shared)
    }

    /**
     * Whether [head] is the start of a ZIP, and so of a workbook.
     *
     * Sniffed from the bytes rather than from the file name: a sheet mailed round an
     * office arrives as `.xlsx`, `.XLSX` or with no extension at all once a phone has
     * renamed it, and the content picker hands over a `content://` URI that need not
     * carry a name in the first place.
     */
    fun looksLikeXlsx(head: ByteArray): Boolean =
        head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

    /**
     * The workbook's shared-string table, in order - cells refer to it by index.
     *
     * SAX rather than android.util.Xml's pull parser, and for a plain reason: the
     * latter is an Android stub with no implementation off the device, so nothing
     * here could be unit tested. SAX is in both the platform and the JVM, so the same
     * reader the tills run is the reader the tests exercise.
     */
    private fun sharedStrings(xml: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        var text: StringBuilder? = null
        parse(xml, object : org.xml.sax.helpers.DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String?, a: org.xml.sax.Attributes?) {
                if (local(l, q) == "si") text = StringBuilder()
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                // <si> can hold several <t> runs where part of the text was formatted
                // differently; they are one string and are joined.
                text?.append(ch, start, length)
            }

            override fun endElement(u: String?, l: String?, q: String?) {
                if (local(l, q) == "si") {
                    strings.add(text?.toString().orEmpty())
                    text = null
                }
            }
        })
        return strings
    }

    private fun rowsOf(xml: ByteArray, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<MutableMap<Int, String>>()
        var row: MutableMap<Int, String>? = null
        var column = 0
        var type = ""
        var inValue = false
        val value = StringBuilder()

        parse(xml, object : org.xml.sax.helpers.DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String?, a: org.xml.sax.Attributes?) {
                when (local(l, q)) {
                    "row" -> row = mutableMapOf()
                    "c" -> {
                        column = columnIndex(a?.getValue("r"))
                        type = a?.getValue("t").orEmpty()
                        value.setLength(0)
                    }
                    // <v> is the stored value; <t> is the text of an inline string.
                    "v", "t" -> inValue = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) value.append(ch, start, length)
            }

            override fun endElement(u: String?, l: String?, q: String?) {
                when (local(l, q)) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = value.toString()
                        val text = if (type == "s") {
                            // A shared-string cell holds an index into the table.
                            shared.getOrNull(raw.trim().toIntOrNull() ?: -1).orEmpty()
                        } else {
                            raw
                        }
                        if (text.isNotEmpty() && column >= 0) row?.put(column, text)
                    }
                    "row" -> row?.let { rows.add(it); row = null }
                }
            }
        })

        // Padded to the widest row, so a short row - one whose trailing cells were
        // left empty and therefore never written - still lines up with the headings.
        val width = rows.maxOfOrNull { r -> (r.keys.maxOrNull() ?: -1) + 1 } ?: 0
        return rows.map { r -> (0 until width).map { r[it].orEmpty() } }
    }

    private fun parse(xml: ByteArray, handler: org.xml.sax.helpers.DefaultHandler) {
        javax.xml.parsers.SAXParserFactory.newInstance()
            .newSAXParser()
            .parse(xml.inputStream(), handler)
    }

    /**
     * An element's name without its namespace prefix.
     *
     * Namespace processing is off, so SAX reports the name in `qName` and leaves
     * `localName` empty - and a workbook written by another tool may prefix its
     * elements (`x:row`). Taking what follows the colon reads both.
     */
    private fun local(localName: String?, qName: String?): String {
        val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()
        return name.substringAfterLast(':')
    }

    /** "C7" -> 2. The digits are the row and are not wanted here. */
    private fun columnIndex(reference: String?): Int {
        if (reference.isNullOrEmpty()) return -1
        var index = 0
        for (ch in reference) {
            if (!ch.isLetter()) break
            index = index * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return index - 1
    }

    // ---- The fixed parts of a workbook --------------------------------------

    private const val NS_MAIN =
        "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    private val CONTENT_TYPES = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        </Types>
    """.trimIndent()

    private val ROOT_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private val WORKBOOK_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
        </Relationships>
    """.trimIndent()

    private fun workbook(sheetName: String): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="$NS_MAIN"
                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets><sheet name="${escape(sheetName)}" sheetId="1" r:id="rId1"/></sheets>
        </workbook>
    """.trimIndent()
}
