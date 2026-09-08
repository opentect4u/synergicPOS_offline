package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload sheet's shape.
 *
 * [ProductCsvTemplate.sampleRows] are hand-written comma strings while
 * [ProductCsvTemplate.header] is a list, and nothing in the language holds the two to
 * the same width. A row one comma short does not fail to compile - it ships, and the
 * operator opens a sheet whose headings have slid one column off its own examples.
 *
 * The layout itself is now the shop's own item master reproduced, so these also pin
 * the decisions that were made ABOUT that master - where the two ids went, where IGST
 * was added - since those are the parts someone tidying the file later would not know
 * were deliberate.
 */
class ProductCsvTemplateTest {

    /** Every sample row fills exactly the columns the header declares. */
    @Test
    fun eachSampleRowHasOneCellPerColumn() {
        ProductCsvTemplate.sampleRows.forEach { row ->
            assertEquals(
                "wrong number of cells in: $row",
                ProductCsvTemplate.header.size,
                row.split(",").size
            )
        }
    }

    /**
     * The two ids lead the sheet - which product this is, and which department it
     * belongs to, before anything the row merely says about it.
     */
    @Test
    fun theSheetLeadsWithTheTwoIds() {
        assertEquals(
            listOf("PRODUCT_ID", "PRODUCT_NAME", "PRODUCT_UNI_NAME", "CATEGORY_ID"),
            ProductCsvTemplate.header.take(4)
        )
    }

    /** Both id columns hold plain numbers - 1, 2, 3, 4 - not codes to decode. */
    @Test
    fun theSampleIdsArePlainNumbers() {
        val product = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.PRODUCT_ID_COLUMN.uppercase())
        val category = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.CATEGORY_ID_COLUMN.uppercase())
        ProductCsvTemplate.sampleRows.forEach { row ->
            val cells = row.split(",")
            assertTrue("not a product id in: $row", cells[product].toIntOrNull() != null)
            assertTrue("not a category id in: $row", cells[category].toIntOrNull() != null)
        }
    }

    /**
     * The unit and rate slots are two blocks of [ProductCsvTemplate.RATE_SLOTS], not
     * interleaved pairs - UNIT_1..4 then RATE_1..4, the way the shop's master has them.
     *
     * The export writes into those blocks by offset, so this is the shape that code is
     * reading; a column added ahead of them has to move both together.
     */
    @Test
    fun theSlotBlocksSitWhereTheHeaderSaysTheyDo() {
        val slots = ProductCsvTemplate.RATE_SLOTS
        val units = ProductCsvTemplate.header.indexOf("UNIT_1")
        val rates = ProductCsvTemplate.header.indexOf("RATE_1")
        assertTrue("UNIT_1 is missing from the header", units >= 0)
        assertEquals("the rate block should follow the unit block", units + slots, rates)
        assertEquals("UNIT_$slots", ProductCsvTemplate.header[rates - 1])
        assertEquals("RATE_$slots", ProductCsvTemplate.header[rates + slots - 1])
    }

    /**
     * The three tax rates are read together.
     *
     * PRODUCT_IGST is this template's one addition to the shop's own sheet, and it was
     * put beside the pair rather than appended at the end because SGST, CGST and IGST
     * are three answers to one question. Appending it would have been the easier edit
     * and is what a later hand is likely to do.
     */
    @Test
    fun theThreeTaxRatesSitTogether() {
        val at = ProductCsvTemplate.header.indexOf("PRODUCT_SGST")
        assertTrue("PRODUCT_SGST is missing from the header", at >= 0)
        assertEquals(
            listOf("PRODUCT_SGST", "PRODUCT_CGST", "PRODUCT_IGST", "VAT_FLAG"),
            ProductCsvTemplate.header.subList(at, at + 4)
        )
    }

    /**
     * The till's own screen language is the last column.
     *
     * It is the one heading that is not a fact about the product on that row - it is a
     * setting riding along because there is no second file to ask for it on - so it
     * goes after everything the shop's master itself carries, where it cannot be
     * mistaken for one of the product's own fields.
     */
    @Test
    fun theScreenLanguageComesLast() {
        assertEquals(
            ProductCsvTemplate.REGIONAL_LANGUAGE_COLUMN,
            ProductCsvTemplate.header.last()
        )
    }

    /**
     * The sample sheet actually demonstrates the regional name column.
     *
     * A column shown blank on every example row teaches nobody what goes in it - and
     * the regional name is the one column whose format is not obvious from its heading.
     */
    @Test
    fun everySampleRowShowsARegionalName() {
        val at = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.REGIONAL_NAME_COLUMN.uppercase())
        ProductCsvTemplate.sampleRows.forEach { row ->
            assertTrue("no regional name shown in: $row", row.split(",")[at].isNotBlank())
        }
    }
}
