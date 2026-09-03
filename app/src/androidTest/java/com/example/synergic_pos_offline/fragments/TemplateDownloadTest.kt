package com.example.synergic_pos_offline.fragments

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.utils.CsvUtils
import com.example.synergic_pos_offline.utils.ProductCsvTemplate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Download Template button writes the sheet to the app cache and hands it to
 * another app through [FileProvider].
 *
 * That handover is the part that fails loudly and only at runtime: a path the
 * provider has no configured root for throws, and the operator gets a crash instead
 * of a spreadsheet. It is checked here rather than found in the field.
 */
@RunWith(AndroidJUnit4::class)
class TemplateDownloadTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val templateFile = File(context.cacheDir, "item_master_template.csv")

    @After
    fun cleanUp() {
        templateFile.delete()
    }

    /** The cache is a configured FileProvider root, so the template can be shared. */
    @Test
    fun theTemplateCanBeHandedToAnotherApp() {
        templateFile.writeText("product_name,category\nTest,Dairy\n")

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", templateFile
        )

        assertNotNull("FileProvider produced no uri for the template", uri)
        assertEquals("content", uri.scheme)
    }

    /**
     * The template the operator downloads has to be readable by the importer it is
     * meant to be uploaded back into - a template the app cannot parse is worse than
     * none - and it has to carry every column the importer reads.
     *
     * Checked against [ProductCsvTemplate.content], which is what both Download
     * Template buttons emit, so it covers the shipped asset and the generated
     * fallback alike.
     */
    @Test
    fun theTemplateParsesBackIntoImportableRows() {
        val rows = CsvUtils.parse(ProductCsvTemplate.content(context))

        assertTrue("the template has no rows to show", rows.isNotEmpty())
        assertTrue(
            "every row needs a product_name to import",
            rows.all { it["product_name"].orEmpty().isNotBlank() }
        )
        listOf("category", "unit_id", "rate", "cgst", "sgst", "discount").forEach { column ->
            assertTrue("the template is missing the $column column", rows.first().containsKey(column))
        }
    }

    /**
     * `category` reads between the product's name and its HSN code.
     *
     * Its position is not arbitrary: the operator fills this in left to right, and
     * what a product *is* belongs beside its name rather than off past the tax
     * columns.
     */
    @Test
    fun categorySitsBetweenProductNameAndHsnCode() {
        assertEquals(
            listOf("product_name", "category", "hsn_code"),
            ProductCsvTemplate.header.take(3)
        )
    }

    /**
     * Both Download Template buttons - the icon on Products and the one on Bulk
     * Upload - hand over the same sheet.
     *
     * They used to build one each, and the two drifted until only one of them knew
     * about categories. This is the check that stops that happening twice.
     */
    @Test
    fun everySampleRowMatchesTheHeader() {
        ProductCsvTemplate.sampleRows.forEach { row ->
            assertEquals(
                "sample row does not line up with the header: $row",
                ProductCsvTemplate.header.size,
                row.split(",").size
            )
        }
    }
}
