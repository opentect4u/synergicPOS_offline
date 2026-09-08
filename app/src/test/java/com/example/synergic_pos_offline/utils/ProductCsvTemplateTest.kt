package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
     * What the row IS leads the sheet - which product this is, and which department it
     * belongs to - before anything the row merely says about it.
     */
    @Test
    fun theSheetLeadsWithIdentity() {
        assertEquals(
            listOf("PRODUCT_ID", "PRODUCT_NAME", "PRODUCT_UNI_NAME", "CATEGORY_NAME"),
            ProductCsvTemplate.header.take(4)
        )
    }

    /**
     * The product id is a plain number; the category is a WORD.
     *
     * The category column carried an id for a while, which is exact and unreadable: a
     * spreadsheet column of 1, 2, 3 cannot be filled in or checked without the Category
     * master open beside it. This pins the split - the id stays an id, the department
     * became something a person can type.
     */
    @Test
    fun theProductIdIsANumberAndTheCategoryIsAName() {
        val product = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.PRODUCT_ID_COLUMN.uppercase())
        val category = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.CATEGORY_NAME_COLUMN.uppercase())
        ProductCsvTemplate.sampleRows.forEach { row ->
            val cells = row.split(",")
            assertTrue("not a product id in: $row", cells[product].toIntOrNull() != null)
            assertTrue("the category is blank in: $row", cells[category].isNotBlank())
            assertTrue("the category is still a number in: $row", cells[category].toIntOrNull() == null)
        }
    }

    /**
     * Products in the same department name it identically.
     *
     * The point of a name over an id: it repeats as the same WORD, so a mistyped one
     * shows up in the spreadsheet. Three of the samples share a department, and if this
     * ever drifts the template is teaching operators to create duplicate categories.
     */
    @Test
    fun productsInOneDepartmentNameItTheSameWay() {
        val at = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.CATEGORY_NAME_COLUMN.uppercase())
        val names = ProductCsvTemplate.sampleRows.map { it.split(",")[at] }
        assertEquals(listOf("Chinese", "Chinese", "Chinese", "Sauces", "Beverages"), names)
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
     * The sample sheet demonstrates the regional name column IN THE TILL'S OWN
     * LANGUAGE.
     *
     * A column shown blank on every example row teaches nobody what goes in it - and
     * the regional name is the one column whose format is not obvious from its
     * heading. It used to be hard-coded Marathi, so a shop working in Hindi opened a
     * template written in a script they do not use.
     */
    @Test
    fun everySampleRowShowsARegionalNameInTheChosenLanguage() {
        val at = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.REGIONAL_NAME_COLUMN.uppercase())
        val english = ProductCsvTemplate.header.indexOf("PRODUCT_NAME")
        ProductCsvTemplate.sampleRows(PrintLanguage.Language.HINDI).forEach { row ->
            val cells = row.split(",")
            assertTrue("no regional name shown in: $row", cells[at].isNotBlank())
            assertNotEquals("still the English name in: $row", cells[english], cells[at])
        }
    }

    /**
     * The language column names the till's language, on the first row only.
     *
     * One answer is all a sheet needs - see ProductCsvTemplate.REGIONAL_LANGUAGE_COLUMN
     * - and filling every row would teach the operator to repeat it.
     */
    @Test
    fun theFirstRowAloneNamesTheChosenLanguage() {
        val at = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.REGIONAL_LANGUAGE_COLUMN)
        val rows = ProductCsvTemplate.sampleRows(PrintLanguage.Language.HINDI)
        assertEquals(PrintLanguage.Language.HINDI.englishName, rows.first().split(",")[at])
        rows.drop(1).forEach {
            assertEquals("only the first row names the language", "", it.split(",")[at])
        }
    }

    /**
     * On an English till both language cells stay empty - and that is the right sheet,
     * not a gap.
     *
     * English is the absence of a regional name, so an English shop's own file has
     * that column present and unfilled. Naming a language there would set one on the
     * next upload.
     */
    @Test
    fun anEnglishTillGetsAnEmptyRegionalPair() {
        val name = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.REGIONAL_NAME_COLUMN.uppercase())
        val lang = ProductCsvTemplate.header.indexOf(ProductCsvTemplate.REGIONAL_LANGUAGE_COLUMN)
        ProductCsvTemplate.sampleRows(PrintLanguage.Language.ENGLISH).forEachIndexed { i, row ->
            val cells = row.split(",")
            assertEquals("row $i named a language", "", cells[lang])
            // The English name passes through untranslated, so the cell holds the
            // product's own name rather than a second script.
            assertEquals(cells[ProductCsvTemplate.header.indexOf("PRODUCT_NAME")], cells[name])
        }
    }
}
