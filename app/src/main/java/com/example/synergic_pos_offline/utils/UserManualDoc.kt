package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.UserManual.Block
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The manual as a Word document, for Download DOC on the User Manual screen.
 *
 * ## Why this as well as the PDF
 *
 * They are for different things. A PDF is finished - it prints the same everywhere and
 * nobody can change it by accident, which is what you want pinned by a counter. A shop
 * that wants to put its own name on the front, cut the chapters it does not use, or add
 * the three house rules the manual cannot know about needs a file it can edit, and that
 * is this one. Sent as a PDF, those edits mean retyping the whole thing.
 *
 * ## Why it is hand-rolled
 *
 * A `.docx` is a ZIP of XML, and [java.util.zip] is already in the platform - the same
 * reasoning [Xlsx] sets out at length for workbooks. A document library would bring in
 * far more than this needs and put a shop's tablet install into another size class.
 *
 * So, like [Xlsx], this handles exactly one shape: the manual's own blocks. There is no
 * document engine here - no tables, images, sections or fields - and none are wanted.
 * Headings are real Word heading styles, though, so the navigation pane works and a
 * shop can generate its own table of contents after editing.
 */
object UserManualDoc {

    const val EXTENSION = "docx"
    const val MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** Writes the manual into Downloads and returns a path fit to show the operator. */
    fun save(context: Context): String = Downloads.save(
        context,
        "User_Manual_${UserManual.VERSION}.$EXTENSION",
        build(context.getString(R.string.app_name)),
        MIME
    )

    /**
     * The document's bytes.
     *
     * [chapters] is a seam for the tests, the same one [UserManualPdf.write] has.
     */
    internal fun build(
        appName: String,
        chapters: List<Pair<String, List<Block>>> = UserManual.ALL
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.entry("[Content_Types].xml", CONTENT_TYPES)
            zip.entry("_rels/.rels", ROOT_RELS)
            zip.entry("word/_rels/document.xml.rels", DOCUMENT_RELS)
            zip.entry("word/styles.xml", STYLES)
            zip.entry("word/document.xml", document(appName, chapters))
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.entry(name: String, body: String) {
        putNextEntry(ZipEntry(name))
        write(body.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun document(
        appName: String,
        chapters: List<Pair<String, List<Block>>>
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="$NS_W"><w:body>""")

        // ---- the front ----
        append(para("User Manual", style = "Title"))
        append(para(appName, bold = true))
        append(para("Version ${UserManual.VERSION}", muted = true))
        val stamp = SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.getDefault()).format(Date())
        append(para("Generated $stamp", muted = true))

        // A list of chapters, and deliberately without page numbers. In a document that
        // reflows as soon as anybody edits it, a number typed in here would be wrong by
        // the second paragraph; Word builds a real table of contents on request, and the
        // heading styles below are what let it.
        append(para("Contents", style = "Heading2"))
        chapters.forEachIndexed { i, (title, _) ->
            append(listItem("${i + 1}.", title))
        }

        // ---- the chapters ----
        chapters.forEach { (title, blocks) ->
            append(para(title, style = "Heading1", pageBreakBefore = true))
            blocks.forEach { block ->
                when (block) {
                    is Block.Heading -> append(para(block.text, style = "Heading2"))
                    is Block.Para -> append(para(block.text))
                    is Block.Steps -> block.items.forEachIndexed { n, item ->
                        append(listItem("${n + 1}.", item))
                    }

                    is Block.Bullets -> block.items.forEach { append(listItem("•", it)) }
                    is Block.Caution -> append(caution(block.text))
                }
            }
        }

        // A4 with 2cm margins, in twips - the same page the PDF uses.
        append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""")
        append("""<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr>""")
        append("</w:body></w:document>")
    }

    /** One paragraph, with whatever of the direct formatting it needs. */
    private fun para(
        text: String,
        style: String? = null,
        bold: Boolean = false,
        muted: Boolean = false,
        pageBreakBefore: Boolean = false
    ): String = buildString {
        append("<w:p><w:pPr>")
        if (style != null) append("""<w:pStyle w:val="$style"/>""")
        if (pageBreakBefore) append("<w:pageBreakBefore/>")
        append("""<w:spacing w:before="80" w:after="80"/>""")
        append("</w:pPr>")
        append("<w:r><w:rPr>")
        if (bold) append("<w:b/>")
        if (muted) append("""<w:color w:val="$MUTED"/>""")
        append("</w:rPr>")
        append(text(text))
        append("</w:r></w:p>")
    }

    /**
     * A numbered or bulleted line.
     *
     * The marker is typed into the paragraph rather than coming from Word's own list
     * numbering. Real numbering means a `numbering.xml` part, an abstract list, a
     * concrete instance and a relationship for each - a great deal of machinery to
     * renumber lists that are fixed text and will never have an item inserted into them
     * by the app. What it costs is that a shop editing the file renumbers by hand.
     *
     * The hanging indent is real, though, so a step that wraps lines up under its own
     * words and not under its number.
     */
    private fun listItem(marker: String, text: String): String = buildString {
        // tabs, then spacing, then ind. See ORDER below.
        append("<w:p><w:pPr>")
        append("""<w:tabs><w:tab w:val="left" w:pos="360"/></w:tabs>""")
        append("""<w:spacing w:before="40" w:after="40"/>""")
        append("""<w:ind w:left="360" w:hanging="360"/>""")
        append("</w:pPr>")
        append("<w:r>${text(marker)}<w:tab/>${text(text)}</w:r>")
        append("</w:p>")
    }

    /** A caution: shaded amber, with a bar down its left edge, as on screen and on paper. */
    private fun caution(text: String): String = buildString {
        append("<w:p><w:pPr>")
        append("""<w:pBdr><w:left w:val="single" w:sz="18" w:space="6" w:color="$CAUTION_EDGE"/></w:pBdr>""")
        append("""<w:shd w:val="clear" w:fill="$CAUTION_FILL"/>""")
        append("""<w:spacing w:before="160" w:after="160"/>""")
        append("""<w:ind w:left="170" w:right="170"/>""")
        append("</w:pPr>")
        append("<w:r>${text(text)}</w:r></w:p>")
    }

    /** A run's text, kept intact - `xml:space` stops Word trimming the spaces off it. */
    private fun text(value: String) =
        """<w:t xml:space="preserve">${escape(value)}</w:t>"""

    /**
     * XML-safe text.
     *
     * Control characters are dropped rather than escaped, for [Xlsx]'s reason: they are
     * not legal in XML 1.0, and one stray character would make the whole document
     * unopenable - which is worse than a document missing a character nobody can see.
     */
    private fun escape(value: String): String = buildString {
        value.forEach { ch ->
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\'' -> append("&apos;")
                ch == '\t' -> append(ch)
                ch.code < 0x20 -> Unit
                else -> append(ch)
            }
        }
    }

    // ---- The fixed parts of a document ---------------------------------------

    /**
     * ORDER: the children of `w:pPr` and `w:rPr` go in the sequence the schema fixes,
     * not the order they are thought of in.
     *
     * The ones used here, in order. Paragraph: `pStyle`, `pageBreakBefore`, `pBdr`,
     * `shd`, `tabs`, `spacing`, `ind`, `outlineLvl`. Run: `b`, `color`, `sz`.
     *
     * This is worth a note because getting it wrong is invisible from here: the XML is
     * still well-formed, the ZIP still builds, every test that parses the file still
     * passes - and Word reports the document as corrupt and offers to recover it. The
     * indent below reads naturally; the order is the part that has to be right.
     */
    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** The brand colour, and the caution's amber. Word takes RRGGBB without the hash. */
    private const val BRAND = "008181"
    private const val MUTED = "80868B"
    private const val CAUTION_FILL = "FEF7E0"
    private const val CAUTION_EDGE = "F9AB00"

    private val CONTENT_TYPES = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
          <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
        </Types>
    """.trimIndent()

    private val ROOT_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>
    """.trimIndent()

    private val DOCUMENT_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    /**
     * Four styles: the body text, and the three headings the manual has.
     *
     * Named `heading 1` and `heading 2` rather than given arbitrary names, because those
     * are the names Word matches on. Called anything else they would look right and
     * still be invisible to the navigation pane and to an inserted table of contents,
     * which is the whole reason for having styles here rather than formatting each
     * paragraph where it is written.
     *
     * Sizes are in half-points, so `w:sz w:val="21"` is 10.5pt - the PDF's body size.
     */
    private val STYLES = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:styles xmlns:w="$NS_W">
          <w:docDefaults>
            <w:rPrDefault><w:rPr>
              <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/>
              <w:sz w:val="21"/>
            </w:rPr></w:rPrDefault>
          </w:docDefaults>
          <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
            <w:name w:val="Normal"/>
          </w:style>
          <w:style w:type="paragraph" w:styleId="Title">
            <w:name w:val="Title"/>
            <w:pPr><w:spacing w:after="240"/></w:pPr>
            <w:rPr><w:b/><w:color w:val="$BRAND"/><w:sz w:val="60"/></w:rPr>
          </w:style>
          <w:style w:type="paragraph" w:styleId="Heading1">
            <w:name w:val="heading 1"/>
            <w:pPr><w:spacing w:before="240" w:after="160"/><w:outlineLvl w:val="0"/></w:pPr>
            <w:rPr><w:b/><w:color w:val="$BRAND"/><w:sz w:val="40"/></w:rPr>
          </w:style>
          <w:style w:type="paragraph" w:styleId="Heading2">
            <w:name w:val="heading 2"/>
            <w:pPr><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr>
            <w:rPr><w:b/><w:color w:val="$BRAND"/><w:sz w:val="26"/></w:rPr>
          </w:style>
        </w:styles>
    """.trimIndent()
}
