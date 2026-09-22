package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * The Word document is a document Word will open.
 *
 * A hand-built `.docx` fails in one of two ways, and neither shows up as an exception
 * on the way out: the package is missing a part or a relationship, or a part is not
 * well-formed XML. Either way the file writes cleanly, lands in Downloads, and is
 * refused when somebody double-clicks it - by which time it is on their machine and not
 * ours. So the parts are checked here, where it costs nothing.
 *
 * What this cannot check is schema ORDER - see the note of that name in [UserManualDoc].
 * Elements in the wrong sequence are still well-formed XML and still parse here.
 */
class UserManualDocTest {

    private fun parts(bytes: ByteArray): Map<String, String> {
        val parts = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return parts
    }

    private val doc = parts(UserManualDoc.build("Synergic POS"))

    /** Throws if [xml] is not well-formed, which is the assertion. */
    private fun parseable(xml: String) {
        SAXParserFactory.newInstance().newSAXParser()
            .parse(xml.byteInputStream(), org.xml.sax.helpers.DefaultHandler())
    }

    @Test
    fun `carries every part the package needs`() {
        // Drop any of these and Word rejects the file outright: the content types say
        // what each part is, the root relationship says which one is the document, and
        // the document's own relationship points at its styles.
        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "word/_rels/document.xml.rels",
                "word/styles.xml",
                "word/document.xml"
            ),
            doc.keys
        )
    }

    @Test
    fun `every part is well-formed xml`() {
        doc.forEach { (name, xml) ->
            runCatching { parseable(xml) }
                .onFailure { throw AssertionError("$name is not well-formed: ${it.message}") }
        }
    }

    @Test
    fun `the content types declare both the document and its styles`() {
        val types = doc.getValue("[Content_Types].xml")
        assertTrue(types.contains("/word/document.xml"))
        assertTrue(types.contains("/word/styles.xml"))
    }

    @Test
    fun `holds every chapter, and every word of every chapter`() {
        val body = doc.getValue("word/document.xml")
        UserManual.ALL.forEach { (title, blocks) ->
            assertContains(body, title)
            blocks.forEach { block ->
                when (block) {
                    is UserManual.Block.Heading -> assertContains(body, block.text)
                    is UserManual.Block.Para -> assertContains(body, block.text)
                    is UserManual.Block.Caution -> assertContains(body, block.text)
                    is UserManual.Block.Steps -> block.items.forEach { assertContains(body, it) }
                    is UserManual.Block.Bullets -> block.items.forEach { assertContains(body, it) }
                }
            }
        }
    }

    /** The text as it would appear once XML-escaped, which is how it is written out. */
    private fun assertContains(body: String, text: String) {
        val escaped = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        assertTrue("missing from the document: ${text.take(40)}...", body.contains(escaped))
    }

    @Test
    fun `starts each chapter on a new page`() {
        val body = doc.getValue("word/document.xml")
        // One per chapter. A manual whose chapters run together is one nobody can thumb.
        assertEquals(
            UserManual.ALL.size,
            Regex("<w:pageBreakBefore/>").findAll(body).count()
        )
    }

    @Test
    fun `uses the heading styles Word recognises`() {
        // Named 'heading 1', not 'Heading1'. Called anything else they would look right
        // and still be invisible to the navigation pane and to a generated contents.
        val styles = doc.getValue("word/styles.xml")
        assertTrue(styles.contains("""<w:name w:val="heading 1"/>"""))
        assertTrue(styles.contains("""<w:name w:val="heading 2"/>"""))

        val body = doc.getValue("word/document.xml")
        assertTrue(body.contains("""<w:pStyle w:val="Heading1"/>"""))
        assertTrue(body.contains("""<w:pStyle w:val="Heading2"/>"""))
    }

    @Test
    fun `stays well-formed when the text holds xml characters`() {
        // Nothing in the manual does today. One '&' added to a chapter tomorrow would
        // otherwise produce a file that no longer opens at all.
        val awkward = "Tea & Coffee <Rates> \"quoted\" it's 5 > 3"
        val bytes = UserManualDoc.build(
            "Synergic POS",
            listOf("Odd" to listOf(UserManual.Block.Para(awkward)))
        )
        val body = parts(bytes).getValue("word/document.xml")
        parseable(body)
        assertTrue(body.contains("Tea &amp; Coffee &lt;Rates&gt;"))
    }

    @Test
    fun `drops control characters rather than writing a file that will not open`() {
        val bytes = UserManualDoc.build(
            "Synergic POS",
            listOf("Odd" to listOf(UserManual.Block.Para("bell\u0007and\u0000null")))
        )
        val body = parts(bytes).getValue("word/document.xml")
        parseable(body)
        assertTrue(body.contains("bellandnull"))
    }

    @Test
    fun `is a zip, as every reader will check first`() {
        val bytes = UserManualDoc.build("Synergic POS")
        assertTrue(Xlsx.looksLikeXlsx(bytes.copyOf(2)))
    }
}
