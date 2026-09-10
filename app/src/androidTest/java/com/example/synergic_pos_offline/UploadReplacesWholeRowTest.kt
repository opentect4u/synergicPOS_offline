package com.example.synergic_pos_offline

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.ProductNameDao
import com.example.synergic_pos_offline.utils.ProductBulkImporter
import com.example.synergic_pos_offline.utils.PrintLanguage
import com.example.synergic_pos_offline.utils.ProductImages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uploading over an existing id replaces the WHOLE row, not the columns the sheet fills.
 *
 * A common PRODUCT_ID does not edit a product, it replaces what that id MEANS: the row
 * becomes a different product. Anything the previous one left in a column the sheet
 * does not carry is then attached to a product it was never about.
 *
 * The photograph is the one that shows. Upload "Paneer Chilly" over the id that was
 * "Paneer Tikka" and the till went on showing Paneer Tikka's picture beside the new
 * name - in the product grid, on the sale screen, everywhere - with nothing on screen
 * to say why. The regional name did the same thing more quietly: a blank cell wrote
 * nothing, so a Hindi till kept printing the old product's Hindi name on the new one.
 */
@RunWith(AndroidJUnit4::class)
class UploadReplacesWholeRowTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    private var productId: Long = 0

    @After
    fun clearUpAfterItself() {
        if (productId > 0) {
            runCatching { ProductNameDao(ctx).deleteFor(listOf(productId.toString())) }
            runCatching { db.execSQL("DELETE FROM md_product_rates WHERE product_id = $productId") }
            runCatching { db.execSQL("DELETE FROM md_products WHERE id = $productId") }
        }
    }

    @Test
    fun theOldProductsPictureAndRegionalNameDoNotSurvive() {
        // An existing product, with a picture and a regional name - the shape the
        // shop's catalogue is actually in.
        db.execSQL("INSERT INTO md_products (product_name) VALUES ('Paneer Tikka')")
        productId = db.rawQuery("SELECT last_insert_rowid()", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(10, 200, 90))
        db.execSQL(
            "UPDATE md_products SET product_image = ?, sku = 'OLD-SKU', brand = 'OLD-BRAND', " +
                "stock_alert_qty = 7 WHERE id = ?",
            arrayOf<Any>(ProductImages.compress(bitmap), productId)
        )
        ProductNameDao(ctx).save(productId.toInt(), PrintLanguage.Language.HINDI.code, "पनीर टिक्का")

        assertTrue("the old product should start with a picture", imageOf(productId) != null)
        assertEquals("and a regional name", "पनीर टिक्का", ProductNameDao(ctx).nameFor(productId.toInt(), PrintLanguage.Language.HINDI.code))

        // The sheet reuses that id for a DIFFERENT product, and carries no picture -
        // no sheet does; there is no column for one.
        ProductBulkImporter.import(
            ctx,
            listOf(
                mapOf(
                    "product_id" to productId.toString(),
                    "product_name" to "Paneer Chilly",
                    "rate_1" to "210",
                    "unit_1" to "PCS"
                )
            ),
            ProductBulkImporter.Mode.APPEND
        )

        assertEquals("the id should now be the sheet's product", "Paneer Chilly", nameOf(productId))
        assertNull("the previous product's picture must not survive it", imageOf(productId))
        assertNull(
            "nor its regional name - a blank cell must clear, not keep",
            ProductNameDao(ctx).nameFor(productId.toInt(), PrintLanguage.Language.HINDI.code)
        )
        assertNull("nor its SKU", textOf(productId, "sku"))
        assertNull("nor its brand", textOf(productId, "brand"))
        assertEquals("nor its stock alert level", 0.0, doubleOf(productId, "stock_alert_qty"), 0.005)
    }

    /**
     * A sheet that DOES give a regional name still sets it.
     *
     * The clear happens before the write, so dropping the old names must not drop the
     * new one with them - which is the way this fix could have gone wrong.
     */
    @Test
    fun aRegionalNameOnTheSheetStillLands() {
        db.execSQL("INSERT INTO md_products (product_name) VALUES ('Old product')")
        productId = db.rawQuery("SELECT last_insert_rowid()", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        ProductNameDao(ctx).save(productId.toInt(), PrintLanguage.Language.HINDI.code, "पुराना")

        ProductBulkImporter.import(
            ctx,
            listOf(
                mapOf(
                    "product_id" to productId.toString(),
                    "product_name" to "New product",
                    "rate_1" to "10",
                    "unit_1" to "PCS",
                    "regional_language" to "Hindi",
                    "product_uni_name" to "नया"
                )
            ),
            ProductBulkImporter.Mode.APPEND
        )

        assertEquals("the sheet's own regional name should be there", "नया",
            ProductNameDao(ctx).nameFor(productId.toInt(), PrintLanguage.Language.HINDI.code))
    }

    // ---- Helpers -------------------------------------------------------------------

    private fun imageOf(id: Long): ByteArray? = db.rawQuery(
        "SELECT product_image FROM md_products WHERE id = ?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null }

    private fun nameOf(id: Long): String? = db.rawQuery(
        "SELECT product_name FROM md_products WHERE id = ?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun textOf(id: Long, column: String): String? = db.rawQuery(
        "SELECT $column FROM md_products WHERE id = ?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

    private fun doubleOf(id: Long, column: String): Double = db.rawQuery(
        "SELECT $column FROM md_products WHERE id = ?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) c.getDouble(0) else -1.0 }

    /**
     * The old product's stock does not become the new product's.
     *
     * Fifty Paneer Tikka on the shelf are not fifty Paneer Chilly. The count and the
     * movements behind it belonged to the product that held this id, and leaving them
     * gave the new one an opening quantity nobody had counted and a trading history it
     * had no part in - which the stock report then showed as fact.
     *
     * Skipped where the till does not track stock at all: there is no count to clear
     * and none to open, and StockDao is not even constructed.
     */
    @Test
    fun theOldProductsStockDoesNotCarryOver() {
        if (!com.example.synergic_pos_offline.database.GeneralSettingsDao.isStockEnabled(ctx)) return

        db.execSQL("INSERT INTO md_products (product_name) VALUES ('Paneer Tikka')")
        productId = db.rawQuery("SELECT last_insert_rowid()", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

        // Fifty on the shelf, booked the way the Add Product form books an opening.
        com.example.synergic_pos_offline.database.StockDao(ctx)
            .recordOpening(db, productId, 50.0, null, null)
        assertEquals("the old product should start with stock", 50.0, stockOf(productId), 0.005)

        // The sheet reuses the id for a different product, opening at 7.
        ProductBulkImporter.import(
            ctx,
            listOf(
                mapOf(
                    "product_id" to productId.toString(),
                    "product_name" to "Paneer Chilly",
                    "rate_1" to "210",
                    "unit_1" to "PCS",
                    "stock" to "7"
                )
            ),
            ProductBulkImporter.Mode.APPEND
        )

        assertEquals(
            "the new product should hold the sheet's count, not the old one's",
            7.0, stockOf(productId), 0.005
        )
    }

    /** Every product's stock across its batches - what the stock screens read. */
    private fun stockOf(id: Long): Double = db.rawQuery(
        "SELECT COALESCE(SUM(current_quantity), 0) FROM md_batch_stock WHERE product_id = ?",
        arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
}
