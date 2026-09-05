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
     * The regional pair sits together, name after language.
     *
     * They are read by heading rather than by position, so this is about the sheet
     * being legible to whoever fills it in: the column saying WHICH language, and the
     * column giving the name in it, belong next to each other.
     */
    @Test
    fun theRegionalNameFollowsTheRegionalLanguage() {
        val language = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.REGIONAL_LANGUAGE_COLUMN)
        val name = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.REGIONAL_NAME_COLUMN)
        assertTrue("regional_language is missing from the header", language >= 0)
        assertEquals("regional_name should follow regional_language", language + 1, name)
    }

    /**
     * The columns before the regional pair have not moved.
     *
     * `category` became `category_code` and `rate_name` became `rate_name_id`, but
     * both stayed exactly where they were: a heading changed, not a layout. A sheet
     * an operator has been filling in for months still has its figures under the
     * right headings, and only the two reference columns need re-entering.
     */
    @Test
    fun theOlderColumnsKeepTheirPositions() {
        assertEquals(
            listOf(
                "product_name", "category_code", "hsn_code", "bar_code",
                "rate_name_id", "rate", "unit_id", "cgst", "sgst", "igst", "vat",
                "discount", "discount_type", "selling_price", "purchase_price"
            ),
            ProductCsvTemplate.header.take(15)
        )
    }

    /**
     * The sample Dept Codes are spelled the way the Category master spells them.
     *
     * The samples are hand-written strings and the code format lives in
     * [com.example.synergic_pos_offline.database.CategoryDao.formatCode]. If that
     * ever changes shape, a sheet still showing the old one teaches the operator to
     * type something the import will not resolve.
     */
    @Test
    fun theSampleCategoryCodesMatchTheMastersFormat() {
        val at = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.CATEGORY_CODE_COLUMN)
        val codes = ProductCsvTemplate.sampleRows.map { it.split(",")[at] }
        assertEquals(
            (1..codes.size).map { com.example.synergic_pos_offline.database.CategoryDao.formatCode(it.toLong()) },
            codes
        )
    }

    /** The sample rate references are ids, not the names they replaced. */
    @Test
    fun theSampleRateNamesAreIds() {
        val at = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.RATE_NAME_ID_COLUMN)
        ProductCsvTemplate.sampleRows.forEach { row ->
            val cell = row.split(",")[at]
            assertTrue("not a rate name id in: $row", cell.toLongOrNull() != null)
        }
    }

    /**
     * The sample sheet actually demonstrates the new column.
     *
     * A column shown blank on every example row teaches nobody what goes in it - and
     * the regional name is the one column whose format is not obvious from its heading.
     */
    @Test
    fun everySampleRowShowsARegionalName() {
        val at = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.REGIONAL_NAME_COLUMN)
        ProductCsvTemplate.sampleRows.forEach { row ->
            val cell = row.split(",")[at]
            assertTrue("no regional name shown in: $row", cell.isNotBlank())
        }
    }
}
