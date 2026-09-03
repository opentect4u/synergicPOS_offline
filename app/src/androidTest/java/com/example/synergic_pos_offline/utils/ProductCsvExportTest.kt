package com.example.synergic_pos_offline.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.DatabaseHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Products screen's Download exports the catalogue joined back together across
 * products, rates, categories and units.
 *
 * The point of exporting it in the upload's own format is that the two make a round
 * trip, so that is what is checked: what comes out has to go back in.
 */
@RunWith(AndroidJUnit4::class)
class ProductCsvExportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(context).writableDatabase

    private val category = "ZZ Export Dairy"
    private val unit = "ZZEXPLTR"
    private val product = "ZZ Export Product"

    @After
    fun cleanUp() {
        db.rawQuery(
            "SELECT id FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE product_name LIKE ?",
            arrayOf("$product%")
        ).use { c ->
            while (c.moveToNext()) {
                db.delete(
                    DatabaseHelper.Tables.MD_PRODUCT_RATES, "product_id=?",
                    arrayOf(c.getLong(0).toString())
                )
            }
        }
        db.delete(DatabaseHelper.Tables.MD_PRODUCTS, "product_name LIKE ?", arrayOf("$product%"))
        db.delete(DatabaseHelper.Tables.MD_CATEGORY, "category_name LIKE ?", arrayOf("ZZ Export%"))
        db.delete(DatabaseHelper.Tables.MD_UNITS, "unit_symbol LIKE ?", arrayOf("ZZEXP%"))
    }

    private fun importOne() = ProductBulkImporter.import(
        context,
        listOf(
            linkedMapOf(
                "product_name" to product,
                "category" to category,
                "hsn_code" to "40120",
                "bar_code" to "",
                "rate_name" to "Regular",
                "rate" to "498.75",
                "unit_id" to unit,
                "cgst" to "2.5",
                "sgst" to "2.5",
                "discount" to "5",
                "discount_type" to "P",
                "selling_price" to "473.81",
                "purchase_price" to "399"
            )
        )
    )

    /** The export carries the template's columns, so it can be uploaded straight back. */
    @Test
    fun exportsInTheUploadFormat() {
        importOne()

        val rows = CsvUtils.parse(ProductCsvExport.content(context))
        val exported = rows.firstOrNull { it["product_name"] == product }

        assertTrue("the imported product is missing from the export", exported != null)
        requireNotNull(exported)
        // Category and unit come back as names, not the ids they are stored under -
        // those are what the upload reads and what a person can edit.
        assertEquals(category, exported["category"])
        assertEquals(unit, exported["unit_id"])
        assertEquals("40120", exported["hsn_code"])
        assertEquals(498.75, exported["rate"]!!.toDouble(), 0.001)
        assertEquals(473.81, exported["selling_price"]!!.toDouble(), 0.001)
        assertEquals(399.0, exported["purchase_price"]!!.toDouble(), 0.001)
    }

    /** Every column the upload reads is present, in the template's own order. */
    @Test
    fun theHeaderIsTheTemplateHeader() {
        assertEquals(
            ProductCsvTemplate.header.joinToString(","),
            ProductCsvExport.content(context).lineSequence().first()
        )
    }

    /**
     * A name holding a comma is quoted, so it stays one cell.
     *
     * Unquoted it would split in two and shift every figure on the line one place
     * left - damage only noticed after the sheet has been uploaded back.
     */
    @Test
    fun quotesACellHoldingAComma() {
        val awkward = "$product, Basmati \"Gold\""
        db.insert(
            DatabaseHelper.Tables.MD_PRODUCTS, null,
            android.content.ContentValues().apply { put("product_name", awkward) }
        )

        val rows = CsvUtils.parse(ProductCsvExport.content(context))

        assertTrue(
            "a comma in the name broke the row apart",
            rows.any { it["product_name"] == awkward }
        )
    }
}
