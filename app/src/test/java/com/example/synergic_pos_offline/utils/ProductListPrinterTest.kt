package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.utils.ProductListPrinter.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the printed price list.
 *
 * The drawing needs Android, but the part that can go wrong does not: a table set
 * in a monospace face is aligned by its padding, so whether the figures line up
 * under their headings is a question about strings. These check that, and print the
 * table so a change to the widths can be looked at rather than guessed at.
 */
class ProductListPrinterTest {

    /** Characters across an 80mm roll at SMALL_SP - see PrintType.charsAcross. */
    private val across = 40
    // Asked of the printer rather than restated, so the test cannot drift from it.
    private val nameW = ProductListPrinter.nameWidthFor(across)

    private fun table(vararg rows: Row): List<String> = buildList {
        add("=".repeat(across))
        add(ProductListPrinter.row(Row("PID", "NAME", "CGST%", "SGST%", "AMOUNT"), nameW))
        add("=".repeat(across))
        rows.forEach { r ->
            val wrapped = ProductListPrinter.wrap(r.name, nameW)
            add(ProductListPrinter.row(r.copy(name = wrapped.first()), nameW))
            wrapped.drop(1).forEach { add(" ".repeat(5) + it) }
        }
    }

    @Test
    fun `every line is the width of the paper, so nothing runs off it`() {
        val rows = arrayOf(
            Row("1", "SAMOSA", "0.00", "0.00", "20.00"),
            Row("21", "LACHHEDAR RUBRI", "0.00", "0.00", "600.00"),
            Row("999", "MOTICHOOR DESHI GHEE", "2.50", "2.50", "480.00")
        )
        table(*rows).forEach { line ->
            assertTrue("too wide: '$line' (${line.length})", line.length <= across)
        }
    }

    @Test
    fun `the figures sit under their headings`() {
        val header = ProductListPrinter.row(Row("PID", "NAME", "CGST%", "SGST%", "AMOUNT"), nameW)
        val line = ProductListPrinter.row(Row("1", "SAMOSA", "0.00", "0.00", "20.00"), nameW)
        // Right-aligned columns end where their heading ends, which is what puts a
        // decimal point under a decimal point all the way down the page.
        assertEquals(header.indexOf("CGST%") + "CGST%".length, line.indexOf("0.00") + "0.00".length)
        assertEquals(header.length, line.length)
        assertTrue(line.endsWith("20.00"))
    }

    @Test
    fun `a long name runs on underneath without repeating its figures`() {
        val wrapped = ProductListPrinter.wrap("PANEER BREAD PAKORA SPECIAL", nameW)
        assertTrue("should have wrapped", wrapped.size > 1)
        val lines = table(Row("4", "PANEER BREAD PAKORA SPECIAL", "0.00", "0.00", "30.00"))
        val runOn = lines.last()
        assertTrue("run-on should carry no figures", !runOn.contains("30.00"))
        assertTrue("run-on should be indented", runOn.startsWith("     "))
    }

    @Test
    fun `a name that fits is not wrapped`() {
        assertEquals(listOf("SAMOSA"), ProductListPrinter.wrap("SAMOSA", nameW))
    }

    @Test
    fun `the table reads as the reference slip does`() {
        val lines = table(
            Row("1", "SAMOSA", "0.00", "0.00", "20.00"),
            Row("2", "BREAD PAKORA", "0.00", "0.00", "25.00"),
            Row("3", "PNR.PAKORA", "0.00", "0.00", "30.00"),
            Row("21", "LACHHEDAR RUBRI", "0.00", "0.00", "600.00"),
            Row("18", "MOTICHOOR DESHI GHEE", "0.00", "0.00", "480.00")
        )
        lines.forEach { println("|$it|") }
        assertEquals(across, lines.first().length)
    }
}
