package com.example.synergic_pos_offline.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.example.synergic_pos_offline.database.CategoryDao
import com.example.synergic_pos_offline.database.CustomerDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.UnitDao
import java.io.ByteArrayOutputStream

/**
 * Fills the master tables with demo content on first run so the app has
 * something to show before any masters are entered by hand: units, categories
 * (with images), products (with images, rates and unit links) and customers.
 *
 * Every table is guarded by its own emptiness check, so this is idempotent and
 * safe to call on every launch — it only ever fills tables that are empty and
 * never overwrites or duplicates hand-entered rows. Category/product images are
 * drawn in code (a coloured tile with the item's initials) so no asset files
 * need to be bundled; the raw JPEG bytes land in the *_image BLOB columns.
 */
object DatabaseSeeder {

    /** Seeds any of the four master tables that are currently empty. */
    fun seedIfEmpty(context: Context) {
        val unitDao = UnitDao(context)
        val categoryDao = CategoryDao(context)
        val customerDao = CustomerDao(context)

        // Units first — products reference them by id.
        val unitIdBySymbol = HashMap<String, Long>()
        if (isEmpty(context, DatabaseHelper.Tables.MD_UNITS)) {
            for (u in DEMO_UNITS) {
                val id = unitDao.insert(u.name, u.symbol, u.fraction)
                if (id != -1L) unitIdBySymbol[u.symbol] = id
            }
        } else {
            for (u in unitDao.getAll()) unitIdBySymbol[u.symbol] = u.id
        }

        // Categories (with images) next — products reference them by id.
        val categoryIdByName = HashMap<String, Long>()
        if (isEmpty(context, DatabaseHelper.Tables.MD_CATEGORY)) {
            for (c in DEMO_CATEGORIES) {
                val id = categoryDao.insert(c.name, tileImage(c.name, c.color))
                if (id != -1L) categoryIdByName[c.name] = id
            }
        } else {
            for (c in categoryDao.getAll()) categoryIdByName[c.name] = c.id
        }

        // Products (with images) + their rate/unit rows.
        if (isEmpty(context, DatabaseHelper.Tables.MD_PRODUCTS)) {
            for (p in DEMO_PRODUCTS) {
                insertProduct(context, p, categoryIdByName[p.category], unitIdBySymbol[p.unitSymbol])
            }
        }

        // Customers.
        if (isEmpty(context, DatabaseHelper.Tables.MD_CUSTOMERS)) {
            for (c in DEMO_CUSTOMERS) customerDao.insert(c)
        }
    }

    // ---------------------------------------------------------------
    // Product insert — mirrors ProductsFragment.saveProduct so seeded
    // products fill md_products and md_product_rates the same way the
    // manual entry screen does.
    // ---------------------------------------------------------------

    private fun insertProduct(
        context: Context,
        p: DemoProduct,
        categoryId: Long?,
        unitId: Long?
    ) {
        val db = DatabaseHelper.getInstance(context).writableDatabase
        val storeId = storeId(context)

        db.beginTransaction()
        try {
            val product = ContentValues().apply {
                put("store_id", storeId)
                put("product_name", p.name)
                put("hsn_code", p.hsn)
                put("bar_code", p.barcode)
                put("stock_alert_qty", p.stockAlert)
                put("gst_rate", p.gst)
                if (categoryId != null) put("category_id", categoryId) else putNull("category_id")
                put("product_image", tileImage(p.name, p.color))
            }
            val id = db.insert(DatabaseHelper.Tables.MD_PRODUCTS, null, product)
            if (id == -1L) return // rolls back — setTransactionSuccessful not called

            val rates = ContentValues().apply {
                put("store_id", storeId)
                put("outlet_id", 0)
                put("product_id", id)
                put("rate_1", p.rate)
                if (unitId != null) put("unit_1_id", unitId) else putNull("unit_1_id")
                // CGST/SGST are each half the product's slab, as elsewhere in the app.
                put("cgst_rate", p.gst / 2.0)
                put("sgst_rate", p.gst / 2.0)
                put("sell_price", p.rate)
                put("purchase_price", p.purchase)
            }
            db.insert(DatabaseHelper.Tables.MD_PRODUCT_RATES, null, rates)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** True when [table] holds no rows. */
    private fun isEmpty(context: Context, table: String): Boolean {
        DatabaseHelper.getInstance(context).readableDatabase
            .rawQuery("SELECT 1 FROM $table LIMIT 1", null)
            .use { return !it.moveToFirst() }
    }

    /** The registered store id (matches how the DAOs resolve it), or null. */
    private fun storeId(context: Context): Long? {
        DatabaseHelper.getInstance(context).readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0) }
        return null
    }

    /**
     * Draws a square [color] tile with [label]'s initials in white and returns it
     * as JPEG bytes — a lightweight stand-in image for demo categories/products.
     */
    private fun tileImage(label: String, color: Int): ByteArray {
        val size = 240
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(color)

        val initials = label.trim().split(" ")
            .filter { it.isNotEmpty() }
            .take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
            .ifEmpty { "?" }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = size * 0.36f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val fm = paint.fontMetrics
        val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(initials, size / 2f, baseline, paint)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    // ---------------------------------------------------------------
    // Demo data
    // ---------------------------------------------------------------

    private data class DemoUnit(val name: String, val symbol: String, val fraction: Boolean)

    private data class DemoCategory(val name: String, val color: Int)

    private data class DemoProduct(
        val name: String,
        val category: String,
        val unitSymbol: String,
        val gst: Double,          // must be one of DatabaseHelper.GST_SLABS
        val rate: Double,
        val purchase: Double,
        val hsn: String,
        val barcode: String,
        val stockAlert: Double,
        val color: Int
    )

    private val DEMO_UNITS = listOf(
        DemoUnit("Pieces", "PCS", fraction = false),
        DemoUnit("Kilogram", "KG", fraction = true),
        DemoUnit("Gram", "GM", fraction = true),
        DemoUnit("Litre", "LTR", fraction = true),
        DemoUnit("Box", "BOX", fraction = false),
        DemoUnit("Dozen", "DOZ", fraction = false)
    )

    private val DEMO_CATEGORIES = listOf(
        DemoCategory("Beverages", 0xFF3B82F6.toInt()),
        DemoCategory("Snacks", 0xFFF59E0B.toInt()),
        DemoCategory("Dairy", 0xFF10B981.toInt()),
        DemoCategory("Bakery", 0xFFEF4444.toInt()),
        DemoCategory("Groceries", 0xFF8B5CF6.toInt()),
        DemoCategory("Personal Care", 0xFF06B6D4.toInt())
    )

    private val DEMO_PRODUCTS = listOf(
        // Beverages
        DemoProduct("Coca Cola 500ml", "Beverages", "PCS", 12.0, 40.0, 30.0, "22021010", "8901234500017", 24.0, 0xFF3B82F6.toInt()),
        DemoProduct("Mineral Water 1L", "Beverages", "PCS", 18.0, 20.0, 12.0, "22011010", "8901234500024", 48.0, 0xFF2563EB.toInt()),
        DemoProduct("Orange Juice 1L", "Beverages", "LTR", 12.0, 90.0, 65.0, "20091100", "8901234500031", 12.0, 0xFF1D4ED8.toInt()),
        // Snacks
        DemoProduct("Potato Chips", "Snacks", "PCS", 12.0, 20.0, 14.0, "20052000", "8901234500048", 30.0, 0xFFF59E0B.toInt()),
        DemoProduct("Salted Peanuts", "Snacks", "GM", 5.0, 50.0, 35.0, "20081100", "8901234500055", 20.0, 0xFFD97706.toInt()),
        // Dairy
        DemoProduct("Full Cream Milk", "Dairy", "LTR", 0.0, 60.0, 50.0, "04012000", "8901234500062", 20.0, 0xFF10B981.toInt()),
        DemoProduct("Paneer", "Dairy", "KG", 5.0, 320.0, 260.0, "04061000", "8901234500079", 5.0, 0xFF059669.toInt()),
        DemoProduct("Butter 500g", "Dairy", "PCS", 12.0, 250.0, 200.0, "04051000", "8901234500086", 10.0, 0xFF047857.toInt()),
        // Bakery
        DemoProduct("White Bread", "Bakery", "PCS", 5.0, 45.0, 32.0, "19052000", "8901234500093", 15.0, 0xFFEF4444.toInt()),
        DemoProduct("Chocolate Cake", "Bakery", "PCS", 18.0, 350.0, 240.0, "19059090", "8901234500109", 5.0, 0xFFDC2626.toInt()),
        // Groceries
        DemoProduct("Basmati Rice 5kg", "Groceries", "KG", 5.0, 550.0, 470.0, "10063020", "8901234500116", 8.0, 0xFF8B5CF6.toInt()),
        DemoProduct("Sunflower Oil 1L", "Groceries", "LTR", 5.0, 140.0, 120.0, "15121100", "8901234500123", 12.0, 0xFF7C3AED.toInt()),
        // Personal Care
        DemoProduct("Bath Soap", "Personal Care", "PCS", 18.0, 35.0, 22.0, "34011190", "8901234500130", 40.0, 0xFF06B6D4.toInt()),
        DemoProduct("Toothpaste 100g", "Personal Care", "PCS", 18.0, 55.0, 38.0, "33061020", "8901234500147", 25.0, 0xFF0891B2.toInt())
    )

    private val DEMO_CUSTOMERS = listOf(
        customer("Rahul Sharma", "12 MG Road, Pune", "9822012345"),
        customer("Priya Nair", "45 Residency Road, Bengaluru", "9845098450"),
        customer("Amit Verma", "Salt Lake, Kolkata", "9831098310"),
        customer("Deepa Iyer", "T Nagar, Chennai", "9840098400"),
        customer(
            "Sunrise Restaurant", "Plot 7, Andheri, Mumbai", "9820011223",
            gstin = "27ABCDE1234F1Z5", creditEnabled = true,
            creditLimit = 50000.0, creditDays = 30, balance = 12500.0
        ),
        customer(
            "Green Grocers", "Sector 18, Noida", "9911002233",
            gstin = "09PQRST5678K1Z2", creditEnabled = true,
            creditLimit = 20000.0, creditDays = 15, balance = 3200.0
        ),
        customer(
            "Cafe Mocha", "Park Street, Kolkata", "9830012345",
            gstin = "19LMNOP9012Q1Z8", creditEnabled = true,
            creditLimit = 30000.0, creditDays = 21, balance = 0.0
        )
    )

    /** Builds a [CustomerDao.Customer] for seeding (id is ignored on insert). */
    private fun customer(
        name: String,
        address: String,
        phone: String,
        gstin: String = "",
        creditEnabled: Boolean = false,
        creditLimit: Double = 0.0,
        creditDays: Int = 0,
        balance: Double = 0.0
    ) = CustomerDao.Customer(
        id = 0L,
        name = name,
        address = address,
        phone = phone,
        gstin = gstin,
        creditEnabled = creditEnabled,
        creditLimit = creditLimit,
        creditDays = creditDays,
        balance = balance
    )
}
