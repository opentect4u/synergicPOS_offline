package com.example.synergic_pos_offline.utils

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.DatabaseHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bulk upload writes straight into the product, category and unit masters, and
 * a mistake there is not one an operator can undo from the till - so it is checked
 * against a real database rather than reasoned about.
 *
 * The rows are taken from the item master template, categories and units named in
 * words as the sheet names them.
 */
@RunWith(AndroidJUnit4::class)
class ProductBulkImporterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db: SQLiteDatabase get() = DatabaseHelper.getInstance(context).writableDatabase

    /**
     * Names unlikely to exist on the device already, so "was this created?" is a
     * question about the import and not about what was there before.
     */
    private val newCategory = "ZZ Test Dry Fruits & Cereals"
    private val newUnit = "ZZTESTLTR"
    private val productPrefix = "ZZ Test Product"

    private fun row(
        name: String,
        category: String,
        unit: String,
        rate: String = "498.75",
        discount: String = "5",
        discountType: String = "P",
        selling: String = "473.81",
        purchase: String = "399"
    ) = linkedMapOf(
        "product_name" to name,
        "category" to category,
        "hsn_code" to "40120",
        "bar_code" to "",
        "rate_name" to "Regular",
        "rate" to rate,
        "unit_id" to unit,
        "cgst" to "2.5",
        "sgst" to "2.5",
        "discount" to discount,
        "discount_type" to discountType,
        "selling_price" to selling,
        "purchase_price" to purchase
    )

    /** The bill line standing in for "this product has been sold", removed afterwards. */
    private var billItemId: Long? = null

    private fun productCount(): Int = db.rawQuery(
        "SELECT count(*) FROM ${DatabaseHelper.Tables.MD_PRODUCTS}", null
    ).use { c -> c.moveToFirst(); c.getInt(0) }

    private fun productId(name: String): Long? = db.rawQuery(
        "SELECT id FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE product_name = ?", arrayOf(name)
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun skuOf(productId: Long): String? = db.rawQuery(
        "SELECT sku FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE id = ?",
        arrayOf(productId.toString())
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun rateCountFor(productId: Long): Int = db.rawQuery(
        "SELECT count(*) FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES} WHERE product_id = ?",
        arrayOf(productId.toString())
    ).use { c -> c.moveToFirst(); c.getInt(0) }

    /** Puts [productId] on a bill line, the cheapest way to make it "in use". */
    private fun sellProduct(productId: Long): Long = db.insert(
        DatabaseHelper.Tables.TD_BILL_ITEMS, null,
        android.content.ContentValues().apply {
            put("product_id", productId)
            put("quantity", 1.0)
            put("rate", 10.0)
            put("item_total", 10.0)
        }
    )

    @After
    fun cleanUp() {
        billItemId?.let {
            db.delete(DatabaseHelper.Tables.TD_BILL_ITEMS, "id=?", arrayOf(it.toString()))
            billItemId = null
        }
        db.rawQuery(
            "SELECT id FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE product_name LIKE ?",
            arrayOf("$productPrefix%")
        ).use { c ->
            while (c.moveToNext()) {
                db.delete(
                    DatabaseHelper.Tables.MD_PRODUCT_RATES, "product_id=?",
                    arrayOf(c.getLong(0).toString())
                )
            }
        }
        db.delete(DatabaseHelper.Tables.MD_PRODUCTS, "product_name LIKE ?", arrayOf("$productPrefix%"))
        db.delete(DatabaseHelper.Tables.MD_CATEGORY, "category_name LIKE ?", arrayOf("ZZ Test%"))
        db.delete(DatabaseHelper.Tables.MD_UNITS, "unit_symbol LIKE ?", arrayOf("ZZTEST%"))
    }

    private fun categoryIdOf(name: String): Int? = db.rawQuery(
        "SELECT id FROM ${DatabaseHelper.Tables.MD_CATEGORY} WHERE category_name = ? COLLATE NOCASE",
        arrayOf(name)
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }

    private fun countCategories(name: String): Int = db.rawQuery(
        "SELECT count(*) FROM ${DatabaseHelper.Tables.MD_CATEGORY} WHERE category_name = ? COLLATE NOCASE",
        arrayOf(name)
    ).use { c -> c.moveToFirst(); c.getInt(0) }

    private fun countUnits(symbol: String): Int = db.rawQuery(
        "SELECT count(*) FROM ${DatabaseHelper.Tables.MD_UNITS} WHERE unit_symbol = ? COLLATE NOCASE",
        arrayOf(symbol)
    ).use { c -> c.moveToFirst(); c.getInt(0) }

    /** The stored product row and its default rate, as a flat map for assertions. */
    private fun stored(name: String): Map<String, String?> = db.rawQuery(
        """
        SELECT p.category_id, p.hsn_code, r.rate, r.unit_id, r.discount, r.discount_type,
               r.sell_price, r.purchase_price, r.cgst_rate, r.sgst_rate
        FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
        LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCT_RATES} r ON r.product_id = p.id
        WHERE p.product_name = ?
        """.trimIndent(),
        arrayOf(name)
    ).use { c ->
        assertTrue("no product stored for $name", c.moveToFirst())
        (0 until c.columnCount).associate { c.getColumnName(it) to c.getString(it) }
    }

    /**
     * A category the sheet names but the till has never seen is created, and its id -
     * not its text - is what lands on the product.
     */
    @Test
    fun createsAnUnknownCategoryAndStoresItsId() {
        val result = ProductBulkImporter.import(
            context, listOf(row("$productPrefix A", newCategory, newUnit))
        )
        assertEquals(1, result.imported)

        val categoryId = categoryIdOf(newCategory)
        assertNotNull("the unknown category should have been created", categoryId)
        assertEquals(categoryId.toString(), stored("$productPrefix A")["category_id"])
    }

    /**
     * The same new category named on many rows is created once, not once per row -
     * the reason the resolved ids are cached across the import.
     */
    @Test
    fun createsEachNewCategoryAndUnitOnlyOnce() {
        val rows = (1..5).map { row("$productPrefix $it", newCategory, newUnit) }
        assertEquals(5, ProductBulkImporter.import(context, rows).imported)

        assertEquals("the category should exist exactly once", 1, countCategories(newCategory))
        assertEquals("the unit should exist exactly once", 1, countUnits(newUnit))
    }

    /** A category already on the master is matched, whatever case the sheet used. */
    @Test
    fun reusesAnExistingCategoryWhateverTheCase() {
        ProductBulkImporter.import(context, listOf(row("$productPrefix A", newCategory, newUnit)))
        val first = categoryIdOf(newCategory)

        ProductBulkImporter.import(
            context, listOf(row("$productPrefix B", newCategory.uppercase(), newUnit.lowercase()))
        )

        assertEquals("a differently-cased name is the same category", 1, countCategories(newCategory))
        assertEquals("a differently-cased symbol is the same unit", 1, countUnits(newUnit))
        assertEquals(first.toString(), stored("$productPrefix B")["category_id"])
    }

    /**
     * The unit is found whichever heading the sheet gave it, and an unknown one is
     * added to the master and assigned either way.
     *
     * The template's own heading is `unit_id`, which is a misleading name for a
     * column holding "Ltr"; a sheet that heads it `unit` should not be the one that
     * imports without a unit at all.
     */
    @Test
    fun readsTheUnitUnderAnyOfItsHeadings() {
        listOf("unit_id", "unit", "unit_name", "unit_symbol").forEachIndexed { i, heading ->
            val symbol = "$newUnit$i"
            val row = linkedMapOf(
                "product_name" to "$productPrefix H$i",
                "category" to newCategory,
                "rate" to "10",
                heading to symbol,
                "selling_price" to "10"
            )
            assertEquals(1, ProductBulkImporter.import(context, listOf(row)).imported)

            assertEquals("$heading did not create the unit", 1, countUnits(symbol))
            val unitId = db.rawQuery(
                "SELECT id FROM ${DatabaseHelper.Tables.MD_UNITS} WHERE unit_symbol = ? COLLATE NOCASE",
                arrayOf(symbol)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }
            assertEquals(
                "$heading did not assign the unit to the rate",
                unitId.toString(), stored("$productPrefix H$i")["unit_id"]
            )
        }
    }

    /** The unit named in the sheet is resolved to md_units and stored as its id. */
    @Test
    fun storesTheUnitAsAnId() {
        ProductBulkImporter.import(context, listOf(row("$productPrefix A", newCategory, newUnit)))

        val unitId = db.rawQuery(
            "SELECT id FROM ${DatabaseHelper.Tables.MD_UNITS} WHERE unit_symbol = ? COLLATE NOCASE",
            arrayOf(newUnit)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }

        assertNotNull("the unknown unit should have been created", unitId)
        assertEquals(unitId.toString(), stored("$productPrefix A")["unit_id"])
    }

    /**
     * A discount is always stored as a percentage, even where the sheet says
     * otherwise - the figures in the template are percentages, and reading a "5"
     * meant as 5% as five rupees off would misprice the product silently.
     */
    @Test
    fun alwaysStoresTheDiscountAsAPercentage() {
        val rows = listOf(
            row("$productPrefix A", newCategory, newUnit, discount = "5", discountType = "P"),
            row("$productPrefix B", newCategory, newUnit, discount = "15", discountType = "A"),
            row("$productPrefix C", newCategory, newUnit, discount = "0", discountType = "")
        )
        assertEquals(3, ProductBulkImporter.import(context, rows).imported)

        listOf("A" to "5", "B" to "15", "C" to "0").forEach { (suffix, expected) ->
            val stored = stored("$productPrefix $suffix")
            assertEquals("P", stored["discount_type"])
            assertEquals(expected.toDouble(), stored["discount"]!!.toDouble(), 0.001)
        }
    }

    /**
     * The selling price lands in both columns that carry it.
     *
     * md_product_rates has `sell_price` and `sale_price`, and different parts of the
     * app read different ones - fill only one and the price reads as zero wherever
     * the other is consulted.
     */
    @Test
    fun storesTheSellingPriceInBothPriceColumns() {
        ProductBulkImporter.import(
            context,
            listOf(row("$productPrefix A", newCategory, newUnit, selling = "473.81"))
        )

        val id = productId("$productPrefix A")
        requireNotNull(id)
        db.rawQuery(
            "SELECT sell_price, sale_price FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES} " +
                "WHERE product_id = ?",
            arrayOf(id.toString())
        ).use { c ->
            assertTrue("no rate stored", c.moveToFirst())
            assertEquals(473.81, c.getDouble(0), 0.001)
            assertEquals("both columns carry the same price", 473.81, c.getDouble(1), 0.001)
        }
    }

    /**
     * The rate and the selling price come from their own columns, and neither is
     * filled in from the other.
     *
     * They are different figures - a rate is what the product is rated at, a selling
     * price what it sells for - and they coincide only on an untaxed, undiscounted
     * line. Substituting one for the other would put a price on the product that the
     * sheet never gave.
     */
    @Test
    fun keepsTheRateAndTheSellingPriceApart() {
        ProductBulkImporter.import(
            context,
            listOf(row("$productPrefix A", newCategory, newUnit, rate = "498.75", selling = "473.81"))
        )

        val stored = stored("$productPrefix A")
        assertEquals("the rate is the rate column", 498.75, stored["rate"]!!.toDouble(), 0.001)
        assertEquals(
            "the selling price is the selling_price column",
            473.81, stored["sell_price"]!!.toDouble(), 0.001
        )
    }

    /** A blank selling price stays blank rather than borrowing the rate. */
    @Test
    fun doesNotTakeTheSellingPriceFromTheRate() {
        ProductBulkImporter.import(
            context,
            listOf(row("$productPrefix A", newCategory, newUnit, rate = "498.75", selling = ""))
        )

        val stored = stored("$productPrefix A")
        assertEquals(498.75, stored["rate"]!!.toDouble(), 0.001)
        assertEquals(0.0, stored["sell_price"]!!.toDouble(), 0.001)
    }

    /** And a blank rate stays blank rather than borrowing the selling price. */
    @Test
    fun doesNotTakeTheRateFromTheSellingPrice() {
        ProductBulkImporter.import(
            context,
            listOf(row("$productPrefix A", newCategory, newUnit, rate = "", selling = "473.81"))
        )

        val stored = stored("$productPrefix A")
        assertEquals(0.0, stored["rate"]!!.toDouble(), 0.001)
        assertEquals(473.81, stored["sell_price"]!!.toDouble(), 0.001)
    }

    /** Rate, selling price, purchase price and the tax rates land on the rate row. */
    @Test
    fun storesTheRateFiguresAgainstTheProduct() {
        ProductBulkImporter.import(
            context,
            listOf(row("$productPrefix A", newCategory, newUnit,
                rate = "498.75", selling = "473.81", purchase = "399"))
        )
        val stored = stored("$productPrefix A")
        assertEquals(498.75, stored["rate"]!!.toDouble(), 0.001)
        assertEquals(473.81, stored["sell_price"]!!.toDouble(), 0.001)
        assertEquals(399.0, stored["purchase_price"]!!.toDouble(), 0.001)
        assertEquals(2.5, stored["cgst_rate"]!!.toDouble(), 0.001)
        assertEquals(2.5, stored["sgst_rate"]!!.toDouble(), 0.001)
        assertEquals("40120", stored["hsn_code"])
    }

    /**
     * A row naming no category imports uncategorised.
     *
     * There is no category chosen on the upload page to fall back to any more - the
     * sheet is the whole catalogue - and filing the row under a guess would put it
     * in a category the sheet never asked for. A blank is visible; a wrong one is not.
     */
    @Test
    fun leavesTheCategoryUnsetWhenTheRowNamesNone() {
        ProductBulkImporter.import(context, listOf(row("$productPrefix A", "", newUnit)))

        assertNull(
            "a row with no category should import uncategorised",
            stored("$productPrefix A")["category_id"]
        )
    }

    /**
     * A product's sku is its own id.
     *
     * Enforced by a database trigger rather than by the importer, so it holds for
     * every way a product can be created; this checks it through the importer, which
     * is the path that creates them by the hundred.
     */
    @Test
    fun storesTheProductSkuAsItsOwnId() {
        ProductBulkImporter.import(context, listOf(row("$productPrefix A", newCategory, newUnit)))

        val id = productId("$productPrefix A")
        requireNotNull(id)
        assertEquals(id.toString(), skuOf(id))
    }

    /**
     * The rule holds for every product on the device, not just the ones this test
     * made - which is what the one-off backfill in [DatabaseHelper] is for. Products
     * created before the trigger existed had no sku at all, and this is what says
     * they were given one.
     */
    @Test
    fun everyProductOnTheTillHasItsIdAsItsSku() {
        // Reported as skipped rather than passing vacuously: an empty catalogue has
        // no sku to get wrong, and a green tick there would say nothing.
        assumeTrue("no products on this device to check", productCount() > 0)

        val wrong = mutableListOf<String>()
        db.rawQuery(
            "SELECT id, product_name, sku FROM ${DatabaseHelper.Tables.MD_PRODUCTS}", null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val sku = c.getString(2)
                if (sku != id.toString()) wrong.add("${c.getString(1)} (id $id) has sku $sku")
            }
        }
        assertTrue("products whose sku is not their id: $wrong", wrong.isEmpty())
    }

    /** The same rule holds for a product inserted directly, not through an import. */
    @Test
    fun givesADirectlyInsertedProductAnSkuToo() {
        val id = db.insert(
            DatabaseHelper.Tables.MD_PRODUCTS, null,
            android.content.ContentValues().apply { put("product_name", "$productPrefix Direct") }
        )
        assertTrue("the product should have been inserted", id > 0)
        assertEquals(id.toString(), skuOf(id))
    }

    // ---- Append vs Replace --------------------------------------------------

    /** Append is the default, and leaves what is already on the till alone. */
    @Test
    fun appendKeepsTheProductsAlreadyOnTheTill() {
        ProductBulkImporter.import(context, listOf(row("$productPrefix Old", newCategory, newUnit)))
        val before = productCount()

        ProductBulkImporter.import(
            context, listOf(row("$productPrefix New", newCategory, newUnit)),
            ProductBulkImporter.Mode.APPEND
        )

        assertEquals("append should add, not replace", before + 1, productCount())
        assertNotNull("the earlier product should still be there", productId("$productPrefix Old"))
    }

    /** Replace clears the catalogue before importing, so only the sheet is left. */
    @Test
    fun replaceClearsTheUnusedProductsFirst() {
        ProductBulkImporter.import(context, listOf(row("$productPrefix Old", newCategory, newUnit)))
        assertNotNull(productId("$productPrefix Old"))

        val result = ProductBulkImporter.import(
            context, listOf(row("$productPrefix New", newCategory, newUnit)),
            ProductBulkImporter.Mode.REPLACE
        )

        assertTrue("replace should report what it removed", result.removed > 0)
        assertEquals(1, result.imported)
        assertNull("the old product should be gone", productId("$productPrefix Old"))
        assertNotNull("the sheet's product should be there", productId("$productPrefix New"))
    }

    /** A cleared product takes its rates with it - no rate rows left orphaned. */
    @Test
    fun replaceClearsTheRatesOfTheProductsItRemoves() {
        ProductBulkImporter.import(context, listOf(row("$productPrefix Old", newCategory, newUnit)))
        val oldId = productId("$productPrefix Old")
        requireNotNull(oldId)
        assertEquals(1, rateCountFor(oldId))

        ProductBulkImporter.import(
            context, listOf(row("$productPrefix New", newCategory, newUnit)),
            ProductBulkImporter.Mode.REPLACE
        )

        assertEquals("the removed product's rates should go with it", 0, rateCountFor(oldId))
    }

    /**
     * A product that has been sold is part of the record of that sale, so Replace
     * leaves it standing - clearing it would either break the bill or take it down
     * too. The counts say so before the operator confirms.
     */
    @Test
    fun replaceKeepsAProductThatHasBeenSold() {
        ProductBulkImporter.import(context, listOf(row("$productPrefix Sold", newCategory, newUnit)))
        val soldId = productId("$productPrefix Sold")
        requireNotNull(soldId)
        billItemId = sellProduct(soldId)

        val counts = ProductBulkImporter.replaceCounts(context)
        assertTrue("a sold product should be counted as kept", counts.kept >= 1)

        ProductBulkImporter.import(
            context, listOf(row("$productPrefix New", newCategory, newUnit)),
            ProductBulkImporter.Mode.REPLACE
        )

        assertNotNull("a sold product must survive a replace", productId("$productPrefix Sold"))
    }

    /**
     * A Replace that empties the catalogue starts the next one at 1.
     *
     * AUTOINCREMENT would otherwise carry on from the highest id ever used, so a
     * till on its third import numbers its products in the thousands and the
     * operator reads that as their own serial numbering.
     */
    @Test
    fun replaceRestartsProductIdsFromOne() {
        // Only meaningful where the replace can actually empty the table: a till
        // with sold products keeps them, and the ids have to keep clear of those.
        ProductBulkImporter.import(context, listOf(row("$productPrefix Old", newCategory, newUnit)))
        assumeTrue(
            "this device has products that a replace must keep",
            ProductBulkImporter.replaceCounts(context).kept == 0
        )

        ProductBulkImporter.import(
            context, listOf(row("$productPrefix New", newCategory, newUnit)),
            ProductBulkImporter.Mode.REPLACE
        )

        assertEquals(1L, productId("$productPrefix New"))
        assertEquals("the sku follows the id", "1", skuOf(1L))
    }

    /**
     * A sheet far larger than the preview shows imports in full.
     *
     * The preview draws only its first stretch of rows, to keep a big file from
     * freezing the till while thousands of views are laid out. That is a limit on
     * what is drawn and must never become a limit on what is written - which is
     * exactly the confusion this checks against.
     */
    @Test
    fun importsAsManyRowsAsTheSheetHas() {
        val many = 200
        val rows = (1..many).map { row("$productPrefix $it", newCategory, newUnit) }

        val result = ProductBulkImporter.import(context, rows)

        assertEquals("every row in the sheet should import", many, result.imported)
        assertEquals(0, result.skipped)
        assertEquals("the category is still created just once", 1, countCategories(newCategory))
    }

    /** A row with no product name is skipped rather than stored as a blank product. */
    @Test
    fun skipsRowsWithoutAProductName() {
        val rows = listOf(
            row("$productPrefix A", newCategory, newUnit),
            row("", newCategory, newUnit),
            row("   ", newCategory, newUnit)
        )
        val result = ProductBulkImporter.import(context, rows)
        assertEquals(1, result.imported)
        assertEquals(2, result.skipped)
    }
}
