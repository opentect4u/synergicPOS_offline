package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/**
 * The workbook the bulk upload hands out, and reads back.
 *
 * A file Excel refuses to open is the whole feature failing, and it fails on the
 * operator's computer where nothing here can see it - so what CAN be checked is
 * checked: that the parts a reader goes looking for are present, and that a sheet
 * written and read again is the sheet that went in.
 */
class XlsxTest {

    private val sheet = listOf(
        listOf("product_name", "category_code", "rate", "regional_name"),
        listOf("Amul Pack 100L", "DEPT001", "498.75", "अमूल प्रीमियम पैक"),
        listOf("Tea, strong", "DEPT002", "25", "")
    )

    /** A workbook is a ZIP holding the parts a reader goes looking for. */
    @Test
    fun theWorkbookCarriesEveryPartAReaderNeeds() {
        val names = mutableListOf<String>()
        ZipInputStream(Xlsx.write(sheet).inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names.add(e.name)
                zip.closeEntry()
            }
        }
        listOf(
            "[Content_Types].xml", "_rels/.rels",
            "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml"
        ).forEach { assertTrue("missing $it, got $names", it in names) }
    }

    /** Written then read is the same sheet - the round trip the operator makes. */
    @Test
    fun aSheetSurvivesBeingWrittenAndReadBack() {
        assertEquals(sheet, Xlsx.read(Xlsx.write(sheet).inputStream()))
    }

    /**
     * A COMMA IN A CELL survives, which is one of the reasons for handing out a
     * workbook: the same value in a CSV has to be quoted, and tears the row in two
     * where it is not.
     */
    @Test
    fun aCommaInsideACellIsNotASeparator() {
        assertEquals("Tea, strong", Xlsx.read(Xlsx.write(sheet).inputStream())[2][0])
    }

    /** Non-Latin text comes back as it went in - the regional name column. */
    @Test
    fun scriptOtherThanLatinSurvives() {
        assertEquals(
            "अमूल प्रीमियम पैक",
            Xlsx.read(Xlsx.write(sheet).inputStream())[1][3]
        )
    }

    /**
     * Characters XML cannot carry are dropped rather than written, because one of
     * them makes the whole workbook unopenable - a name missing an invisible
     * character beats a file Excel refuses.
     */
    @Test
    fun aControlCharacterDoesNotBreakTheFile() {
        val nasty = listOf(listOf("badname", "ok"))
        assertEquals("badname", Xlsx.read(Xlsx.write(nasty).inputStream())[0][0])
    }

    /** Markup in a cell is escaped, not injected. */
    @Test
    fun markupInACellIsEscaped() {
        val marked = listOf(listOf("a<b & c>d", "\"quoted\""))
        val read = Xlsx.read(Xlsx.write(marked).inputStream())
        assertEquals("a<b & c>d", read[0][0])
        assertEquals("\"quoted\"", read[0][1])
    }

    /**
     * A row whose trailing cells are empty still lines up with the headings. Empty
     * cells are never written, so the reader has to place each cell by its own
     * reference rather than by counting siblings.
     */
    @Test
    fun aShortRowStillLinesUpWithItsHeadings() {
        val read = Xlsx.read(Xlsx.write(sheet).inputStream())
        assertEquals(4, read[2].size)
        assertEquals("", read[2][3])
    }

    /** Anything that is not a workbook reads as nothing rather than throwing. */
    @Test
    fun somethingThatIsNotAWorkbookReadsAsEmpty() {
        assertEquals(emptyList<List<String>>(), Xlsx.read("product_name,rate".byteInputStream()))
    }

    /** And is told apart before it is opened, from its first two bytes. */
    @Test
    fun csvIsNotMistakenForAWorkbook() {
        assertTrue(Xlsx.looksLikeXlsx(Xlsx.write(sheet).copyOfRange(0, 2)))
        assertFalse(Xlsx.looksLikeXlsx("pr".toByteArray()))
    }
}
