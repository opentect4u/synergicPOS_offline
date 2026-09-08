package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The item master sheet handed out and the reading of it, checked against each other.
 *
 * The template and the importer are two files that have to agree on a column name
 * exactly, and nothing about a mismatch is loud: a heading the importer does not
 * recognise reads as an empty cell, so the upload succeeds and the products come in
 * with no rate, no tax and no name. That is the failure these guard.
 */
class ProductSheetTest {

    /**
     * The template as the upload actually receives it - headings lower-cased, and
     * filled in for a till working in Hindi.
     *
     * A language has to be named because the two regional cells are no longer written
     * into the sample strings: they are filled in for whatever language the till is
     * on, which is the whole point of the template being an example of THIS shop's
     * sheet. Hindi rather than English, so the regional name is actually populated and
     * there is something to read back.
     */
    private fun parsedTemplate(): List<Map<String, String>> = CsvUtils.parse(
        (
            listOf(ProductCsvTemplate.header.joinToString(",")) +
                ProductCsvTemplate.sampleRows(PrintLanguage.Language.HINDI)
            ).joinToString("\n")
    )

    /**
     * The id and the department are read back as the sheet writes them.
     *
     * Checked through the parse rather than off the header: the heading the sheet
     * writes and the key the importer looks up have to be the same word, and a
     * mismatch there reads as an empty cell rather than as an error.
     */
    @Test
    fun theIdAndTheDepartmentAreReadBack() {
        val rows = parsedTemplate()
        assertEquals(listOf("1", "2", "3", "4", "5"), rows.map { it[ProductCsvTemplate.PRODUCT_ID_COLUMN] })
        assertEquals(
            listOf("Chinese", "Chinese", "Chinese", "Sauces", "Beverages"),
            rows.map { ProductBulkImporter.categoryNameOf(it) }
        )
    }

    /**
     * The department heading the PREVIOUS template used is still read.
     *
     * A shop that has been filling in a sheet headed `category` for months should not
     * have to rename a column to upload it.
     */
    @Test
    fun theOlderCategoryHeadingIsStillRead() {
        val old = CsvUtils.parse("product_name,category\nTea,Beverages")
        assertEquals("Beverages", ProductBulkImporter.categoryNameOf(old[0]))
    }

    /** A row naming no department says so, rather than saying "". */
    @Test
    fun noDepartmentReadsAsNone() {
        assertNull(ProductBulkImporter.categoryNameOf(CsvUtils.parse("product_name\nTea")[0]))
    }

    /**
     * The name column is read back, under the heading the sheet gives it - and it
     * carries the till's own language rather than a fixed one.
     *
     * Checked against what the template itself generated rather than against a literal
     * string: the point is that the cell the template WRITES is the cell the importer
     * READS, whichever language produced it.
     */
    @Test
    fun theSheetsOwnRegionalNameHeadingIsRead() {
        val nameAt = ProductCsvTemplate.header
            .indexOf(ProductCsvTemplate.REGIONAL_NAME_COLUMN.uppercase())
        val written = ProductCsvTemplate.sampleRows(PrintLanguage.Language.HINDI)[0].split(",")[nameAt]
        assertTrue("the template wrote no regional name to read back", written.isNotBlank())
        assertEquals(written, ProductBulkImporter.regionalNameOf(parsedTemplate()[0]))
    }

    /** And so is the heading the previous template used, so an old file still imports. */
    @Test
    fun thePreviousRegionalNameHeadingIsStillRead() {
        val old = CsvUtils.parse("product_name,regional_name\nTea,चाय")
        assertEquals("चाय", ProductBulkImporter.regionalNameOf(old[0]))
    }

    /** A product priced in three slots imports as three rates, in slot order. */
    @Test
    fun everyPricedSlotBecomesARate() {
        val lines = ProductBulkImporter.rateLinesOf(parsedTemplate()[1])
        assertEquals(
            listOf(
                ProductBulkImporter.RateLine("PLT", 160.0),
                ProductBulkImporter.RateLine("HALF", 90.0),
                ProductBulkImporter.RateLine("QTR", 50.0)
            ),
            lines
        )
    }

    /** A slot with no price is not a way the shop sells it, whatever its unit says. */
    @Test
    fun aSlotWithNoRateIsNotARate() {
        val row = CsvUtils.parse("product_name,unit_1,rate_1,unit_2,rate_2\nTea,PLT,25,HALF,")[0]
        assertEquals(listOf(ProductBulkImporter.RateLine("PLT", 25.0)), ProductBulkImporter.rateLinesOf(row))
    }

    /** A price with no unit is still a price - the product's only rate must survive. */
    @Test
    fun aRateWithNoUnitIsStillARate() {
        val row = CsvUtils.parse("product_name,unit_1,rate_1\nTea,,25")[0]
        assertEquals(listOf(ProductBulkImporter.RateLine(null, 25.0)), ProductBulkImporter.rateLinesOf(row))
    }

    /**
     * A sheet filled in against the previous template still yields its one rate -
     * every product that imported before had a rate row, and one with none would not
     * reach the sales screen at all.
     */
    @Test
    fun theOlderSingleRateColumnsStillImport() {
        val row = CsvUtils.parse("product_name,unit_id,rate\nTea,Ltr,25")[0]
        assertEquals(listOf(ProductBulkImporter.RateLine("Ltr", 25.0)), ProductBulkImporter.rateLinesOf(row))
    }

    /** A row that prices nothing still gets its one empty rate, for the same reason. */
    @Test
    fun aRowThatPricesNothingStillGetsARate() {
        val row = CsvUtils.parse("product_name,unit_1,rate_1\nTea,,")[0]
        assertEquals(1, ProductBulkImporter.rateLinesOf(row).size)
        assertEquals(0.0, ProductBulkImporter.rateLinesOf(row)[0].rate, 0.0)
    }

    /** No more slots are read than the sheet has columns for. */
    @Test
    fun noMoreRatesAreReadThanTheSheetCanHold() {
        val row = parsedTemplate()[0]
        assertTrue(ProductBulkImporter.rateLinesOf(row).size <= ProductCsvTemplate.RATE_SLOTS)
    }

    /**
     * A blank cell reads as absent, so the shop's own spelling of a column and the
     * previous template's spelling can sit on one sheet without the empty one winning.
     */
    @Test
    fun anEmptyCellDoesNotBeatAFilledAlternativeSpelling() {
        val row = CsvUtils.parse("product_name,product_uni_name,regional_name\nTea,,चाय")[0]
        assertEquals("चाय", ProductBulkImporter.regionalNameOf(row))
    }

    /** A sheet that names no regional name at all says so, rather than saying "". */
    @Test
    fun noRegionalNameReadsAsNone() {
        assertNull(ProductBulkImporter.regionalNameOf(CsvUtils.parse("product_name\nTea")[0]))
    }
}
