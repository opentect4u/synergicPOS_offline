package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word wrapping for the downloaded manual.
 *
 * Every character is one unit wide here, which is not how the real font measures - but
 * where the breaks fall is arithmetic over whatever the font says, and it is the
 * arithmetic that can be wrong. A monospaced stand-in makes the expected output
 * something a reader of this test can count for themselves.
 */
class UserManualPdfTest {

    private val monospace: (String) -> Float = { it.length.toFloat() }

    private fun wrap(text: String, width: Float) =
        UserManualPdf.wrap(text, width, monospace)

    @Test
    fun `breaks between words, not through them`() {
        assertEquals(
            listOf("the quick", "brown fox"),
            wrap("the quick brown fox", 10f)
        )
    }

    @Test
    fun `fills each line as far as it will go`() {
        // 20 wide: "the quick brown fox" is 19 and fits; adding "jumps" would make 25.
        assertEquals(
            listOf("the quick brown fox", "jumps"),
            wrap("the quick brown fox jumps", 20f)
        )
    }

    @Test
    fun `a line exactly the column width is not broken`() {
        assertEquals(listOf("abc def"), wrap("abc def", 7f))
    }

    @Test
    fun `text shorter than the column stays on one line`() {
        assertEquals(listOf("short"), wrap("short", 500f))
    }

    @Test
    fun `keeps a word too wide for the column rather than losing it`() {
        // Nowhere better to put it: dropping it would lose text from the manual, and
        // silently losing a word is worse than one line running long.
        val lines = wrap("a supercalifragilistic word", 8f)
        assertTrue("$lines", lines.contains("supercalifragilistic"))
        assertEquals("a supercalifragilistic word", lines.joinToString(" "))
    }

    @Test
    fun `loses no words however narrow the column`() {
        val text = "Settings General Settings Mode Grocery Restaurant or Calculator"
        listOf(6f, 12f, 25f, 40f, 200f).forEach { width ->
            assertEquals("at width $width", text, wrap(text, width).joinToString(" "))
        }
    }

    @Test
    fun `collapses the runs of spaces that wrapped source text leaves behind`() {
        assertEquals(listOf("one two three"), wrap("one   two  three", 50f))
    }

    @Test
    fun `gives back a blank line rather than nothing`() {
        // A caller drawing "no lines" would silently swallow a paragraph; one empty
        // line is a gap that can be seen on the page.
        assertEquals(listOf(""), wrap("", 100f))
        assertEquals(listOf(""), wrap("   ", 100f))
    }

    @Test
    fun `every chapter of the manual has a heading and some words`() {
        // The PDF and the screen are both built from these blocks, so an empty chapter
        // would print as a page with nothing but its title on it.
        UserManual.ALL.forEach { (title, blocks) ->
            assertTrue("$title is empty", blocks.isNotEmpty())
            assertTrue(
                "$title has no prose",
                blocks.any { it is UserManual.Block.Para || it is UserManual.Block.Bullets }
            )
            blocks.filterIsInstance<UserManual.Block.Heading>().forEach {
                assertTrue("blank heading in $title", it.text.isNotBlank())
            }
        }
    }

    @Test
    fun `chapter titles are short enough to sit on a tab`() {
        UserManual.ALL.forEach { (title, _) ->
            assertTrue("'$title' is too long for a tab", title.length <= 20)
        }
    }
}
