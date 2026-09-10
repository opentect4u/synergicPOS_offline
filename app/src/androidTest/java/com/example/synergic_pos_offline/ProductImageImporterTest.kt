package com.example.synergic_pos_offline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.ProductImageImporter
import com.example.synergic_pos_offline.utils.ProductImages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A folder of pictures lands on the products its file names point at.
 *
 * The rule is the whole feature: `41.jpg` is the picture for product 41, and a file
 * named anything else is left alone rather than guessed at. The expensive mistake
 * here is a guess - putting a picture on the WRONG product is worse than putting it
 * on none, because nothing on screen would say it had happened.
 *
 * ## What this covers, and what it cannot
 *
 * Everything except the folder picker itself: the listing, the name-to-id matching,
 * the decode, the resize, and the write into `md_products.product_image`. It runs
 * against [ProductImageImporter.Source.Dir], a plain folder, because that is the one
 * a test can create - the operator's route is `Source.Tree`, a folder handed over by
 * the system picker, and the two differ only in how the file list is read.
 */
@RunWith(AndroidJUnit4::class)
class ProductImageImporterTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    private lateinit var folder: File

    /** A real product to put a picture on, and whatever picture it had before. */
    private var productId: Long = 0
    private var originalImage: ByteArray? = null
    private var planted = false

    @Before
    fun setUp() {
        folder = File(ctx.filesDir, "image-import-test").apply {
            deleteRecursively()
            mkdirs()
        }
        productId = anyProductId()
        originalImage = db.rawQuery(
            "SELECT product_image FROM md_products WHERE id = ?", arrayOf(productId.toString())
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null }
    }

    /** The till is put back exactly as it was - this writes to a real product. */
    @After
    fun putItBack() {
        folder.deleteRecursively()
        if (planted) {
            db.execSQL("DELETE FROM md_products WHERE id = $productId")
        } else {
            val before = originalImage
            if (before == null) {
                db.execSQL("UPDATE md_products SET product_image = NULL WHERE id = $productId")
            } else {
                db.execSQL(
                    "UPDATE md_products SET product_image = ? WHERE id = ?",
                    arrayOf<Any>(before, productId)
                )
            }
        }
    }

    /**
     * The picture named after a product lands on it; nothing else is touched.
     *
     * Four files, one of each kind that can be in a folder: the one that matches, one
     * named after a product this till does not have, one whose name is not a number
     * at all, and one that is not a picture. Only the first may be written.
     */
    @Test
    fun eachPictureLandsOnTheProductItsNameNames() {
        writeImage("$productId.jpg", width = 1200, height = 900)
        writeImage("99999999.jpg")              // no such product
        writeImage("logo.png")                  // not a number
        File(folder, "notes.txt").writeText("not a picture")

        val source = ProductImageImporter.Source.Dir(folder.absolutePath)

        val preview = ProductImageImporter.preview(ctx, source)
        assertEquals("preview should not have failed: ${preview.error}", null, preview.error)
        assertEquals("three of the four files are images", 3, preview.images)
        assertEquals("only one names a product here", 1, preview.matched)
        assertEquals("the other two name nothing here", 2, preview.unmatched)
        assertEquals("the text file is not an image", 1, preview.skipped)

        val result = ProductImageImporter.import(ctx, source)
        assertEquals("import should not have failed: ${result.error}", null, result.error)
        assertEquals("one product should have been given its picture", 1, result.updated)
        assertEquals("nothing should have failed to decode", 0, result.failed)
        assertEquals("two images named no product here", 2, result.unmatched)

        val stored = imageOf(productId)
        assertNotNull("the product should now carry a picture", stored)

        // Stored at the size the Products screen stores one at, not the size it
        // arrived - a folder of camera photographs would otherwise put tens of
        // megabytes into the database, and every backup after it.
        val bitmap = BitmapFactory.decodeByteArray(stored, 0, stored!!.size)
        assertNotNull("what was stored should decode as an image", bitmap)
        assertTrue(
            "a 1200px image should have been scaled to ${ProductImages.MAX_EDGE}px, " +
                "was ${bitmap.width}x${bitmap.height}",
            maxOf(bitmap.width, bitmap.height) <= ProductImages.MAX_EDGE
        )
    }

    /**
     * A name that only LOOKS like an id is refused, not rounded off to one.
     *
     * `41 (1).jpg` is what a phone calls the second copy of `41.jpg`, and it is the
     * exact case where being clever would put yesterday's picture on product 41 -
     * silently, because a picture that is merely wrong looks like a picture that is
     * right. Anything that is not digits end to end names no product.
     */
    @Test
    fun aNameThatOnlyLooksLikeAnIdIsRefused() {
        writeImage("$productId (1).jpg")
        writeImage("$productId-front.jpg")
        writeImage("product$productId.jpg")

        val source = ProductImageImporter.Source.Dir(folder.absolutePath)
        val preview = ProductImageImporter.preview(ctx, source)

        assertEquals("all three are images", 3, preview.images)
        assertEquals("and none of them names a product", 0, preview.matched)
        assertEquals(3, preview.unmatched)

        val result = ProductImageImporter.import(ctx, source)
        assertEquals("nothing should have been written", 0, result.updated)
        assertEquals("the product's picture must be untouched", originalImage, imageOf(productId))
    }

    /** A folder with nothing in it says so rather than reporting success. */
    @Test
    fun anEmptyFolderIsReportedNotSilentlyAccepted() {
        val preview = ProductImageImporter.preview(
            ctx, ProductImageImporter.Source.Dir(folder.absolutePath)
        )
        assertNotNull("an empty folder should come back with a reason", preview.error)
        assertEquals(false, preview.hasWork)
    }

    // ---- Helpers -------------------------------------------------------------------

    /** Any product on this till, made if the catalogue is empty. */
    private fun anyProductId(): Long {
        db.rawQuery("SELECT id FROM md_products ORDER BY id LIMIT 1", null).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        db.execSQL("INSERT INTO md_products (product_name) VALUES ('Image import test product')")
        planted = true
        return db.rawQuery("SELECT last_insert_rowid()", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    private fun imageOf(id: Long): ByteArray? = db.rawQuery(
        "SELECT product_image FROM md_products WHERE id = ?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null }

    /** A real, decodable JPEG of the given size, written into the test folder. */
    private fun writeImage(name: String, width: Int = 40, height: Int = 40) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(200, 40, 40))
        File(folder, name).outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
    }
}
