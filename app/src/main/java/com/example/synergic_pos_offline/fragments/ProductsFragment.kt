package com.example.synergic_pos_offline.fragments

import android.content.ContentValues
import android.content.res.ColorStateList
import android.database.sqlite.SQLiteConstraintException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * "Products" master screen — a concrete [DataTableFragment] backed by md_products.
 *
 * Add/Edit uses a custom form that also captures the product image and the pricing
 * details, writing md_products and md_product_rates together.
 */
class ProductsFragment : DataTableFragment() {

    override val screenTitle = "Products"

    override val columns = listOf("Name", "HSN Code", "Barcode", "Category")

    /** Products show their image as a round preview before the name. */
    override val showsThumbnails = true

    /** An id/label pair backing a dropdown. */
    private data class Option(val id: Int, val label: String)

    // Image chosen in the currently open dialog.
    private var dialogImageView: ImageView? = null
    private var pendingImage: ByteArray? = null
    private var imageCleared = false
    private var cameraUri: Uri? = null

    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launchers must be registered before the fragment is STARTED.
        galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { applyPickedImage(it) }
        }
        cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            if (saved) cameraUri?.let { applyPickedImage(it) }
        }
    }

    // ---- Table ---------------------------------------------------------------

    override fun loadRows(): MutableList<DataRow> {
        val rows = mutableListOf<DataRow>()
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        val sql = """
            SELECT p.id, p.product_name, p.hsn_code, p.bar_code, c.category_name, p.product_image
            FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
            LEFT JOIN ${DatabaseHelper.Tables.MD_CATEGORY} c ON c.id = p.category_id
            WHERE p.store_id = ?
            ORDER BY p.product_name COLLATE NOCASE
        """.trimIndent()

        db.rawQuery(sql, arrayOf(storeId().toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    DataRow(
                        id = cursor.getInt(0).toString(),
                        cells = listOf(
                            cursor.getString(1).orEmpty(),
                            cursor.getString(2).orEmpty(),
                            cursor.getString(3).orEmpty(),
                            cursor.getString(4).orEmpty()
                        ),
                        thumbnail = if (cursor.isNull(5)) null else cursor.getBlob(5)
                    )
                )
            }
        }
        return rows
    }

    override fun onAddRow() = showProductDialog(null)

    override fun onEditRow(row: DataRow) = showProductDialog(row.id.toIntOrNull())

    override fun onBulkDelete() {
        val ids = selectedRowIds.toList()
        if (ids.isEmpty()) return

        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Delete Selected",
            message = "Delete ${ids.size} product(s)? Their rates will be removed as well.",
            positiveText = "Delete All",
            negativeText = "Cancel",
            iconRes = android.R.drawable.ic_menu_delete,
            destructive = true
        ) {
            if (deleteProducts(ids)) {
                refreshRows()
                toast("Deleted ${ids.size} product(s)")
            } else {
                // A product still referenced by bills/stock can't be removed.
                toast("Could not delete: product is used in existing records")
            }
        }
    }

    /** Removes the products and their rates together. Returns false if the DB refused. */
    private fun deleteProducts(ids: List<String>): Boolean {
        val db = DatabaseHelper.getInstance(requireContext()).writableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.toTypedArray()

        db.beginTransaction()
        return try {
            // Rates are the child rows, so they must go first (foreign keys are on).
            db.delete(
                DatabaseHelper.Tables.MD_PRODUCT_RATES, "product_id IN ($placeholders)", args
            )
            db.delete(
                DatabaseHelper.Tables.MD_PRODUCTS,
                "id IN ($placeholders) AND store_id = ?",
                args + storeId().toString()
            )
            db.setTransactionSuccessful()
            true
        } catch (_: SQLiteConstraintException) {
            false
        } finally {
            db.endTransaction()
        }
    }

    // ---- Add / Edit dialog ---------------------------------------------------

    private fun showProductDialog(productId: Int?) {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_product_form, null)
        val dialog = AlertDialog.Builder(context).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Reset per-dialog image state.
        pendingImage = null
        imageCleared = false
        dialogImageView = view.findViewById(R.id.ivProductImage)

        val categories = loadOptions(DatabaseHelper.Tables.MD_CATEGORY, "category_name")
        val units = loadOptions(DatabaseHelper.Tables.MD_UNITS, "unit_name")

        val actCategory = view.findViewById<AutoCompleteTextView>(R.id.actCategory)
        val actUnit1 = view.findViewById<AutoCompleteTextView>(R.id.actUnit1)
        val actUnit2 = view.findViewById<AutoCompleteTextView>(R.id.actUnit2)
        val actUnit3 = view.findViewById<AutoCompleteTextView>(R.id.actUnit3)
        val actDiscountType = view.findViewById<AutoCompleteTextView>(R.id.actDiscountType)

        val existing = productId?.let { loadProduct(it) }

        bindOptions(actCategory, categories, existing?.categoryId)
        bindOptions(actUnit1, units, existing?.unit1Id)
        bindOptions(actUnit2, units, existing?.unit2Id)
        bindOptions(actUnit3, units, existing?.unit3Id)
        bindDiscountType(actDiscountType, existing?.discountType)

        view.findViewById<TextInputEditText>(R.id.etName).setText(existing?.name.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etHsn).setText(existing?.hsn.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etBarcode).setText(existing?.barcode.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etStockAlert).setText(existing?.stockAlert.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etBatchNo).setText(existing?.batchNo.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etRate1).setText(existing?.rate1.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etRate2).setText(existing?.rate2.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etRate3).setText(existing?.rate3.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etCgst).setText(existing?.cgst.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etSgst).setText(existing?.sgst.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etIgst).setText(existing?.igst.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etVat).setText(existing?.vat.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etDiscount).setText(existing?.discount.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etSellPrice).setText(existing?.sellPrice.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etPurchasePrice).setText(existing?.purchasePrice.orEmpty())

        existing?.image?.let { showImage(it) }
        view.findViewById<TextView>(R.id.tvProductFormTitle).text =
            if (productId == null) "Add Product" else "Edit Product"

        view.findViewById<MaterialButton>(R.id.btnPickGallery).setOnClickListener {
            galleryLauncher.launch("image/*")
        }
        view.findViewById<MaterialButton>(R.id.btnTakePhoto).setOnClickListener { launchCamera() }
        view.findViewById<MaterialButton>(R.id.btnClearImage).setOnClickListener {
            pendingImage = null
            imageCleared = true
            dialogImageView?.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        view.findViewById<MaterialButton>(R.id.btnProductCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnProductSave).setOnClickListener {
            val tilName = view.findViewById<TextInputLayout>(R.id.tilName)
            val name = view.findViewById<TextInputEditText>(R.id.etName).text.toString().trim()
            if (name.isEmpty()) {
                tilName.error = "Product name is required"
                return@setOnClickListener
            }
            tilName.error = null

            val form = ProductForm(
                name = name,
                hsn = text(view, R.id.etHsn),
                barcode = text(view, R.id.etBarcode),
                stockAlert = text(view, R.id.etStockAlert),
                categoryId = selectedId(actCategory),
                batchNo = text(view, R.id.etBatchNo),
                rate1 = text(view, R.id.etRate1),
                rate2 = text(view, R.id.etRate2),
                rate3 = text(view, R.id.etRate3),
                unit1Id = selectedId(actUnit1),
                unit2Id = selectedId(actUnit2),
                unit3Id = selectedId(actUnit3),
                cgst = text(view, R.id.etCgst),
                sgst = text(view, R.id.etSgst),
                igst = text(view, R.id.etIgst),
                vat = text(view, R.id.etVat),
                discount = text(view, R.id.etDiscount),
                discountType = actDiscountType.tag as? String,
                sellPrice = text(view, R.id.etSellPrice),
                purchasePrice = text(view, R.id.etPurchasePrice)
            )
            saveProduct(productId, form)
            dialog.dismiss()
            dialogImageView = null
            refreshRows()
            toast(if (productId == null) "Product added" else "Product updated")
        }

        ThemeManager.applyTheme(view)
        // ThemeManager tints every MaterialButton's background with the accent, which
        // would make the outlined/text buttons accent-on-accent. Restore them here.
        styleDialogButtons(view)
        dialog.setOnDismissListener { dialogImageView = null }
        dialog.show()
    }

    private fun styleDialogButtons(root: android.view.View) {
        val accent = ThemeManager.getThemeColor(requireContext())
        val accentTint = ColorStateList.valueOf(accent)
        val transparent = ColorStateList.valueOf(Color.TRANSPARENT)

        root.findViewById<MaterialButton>(R.id.btnProductSave).apply {
            backgroundTintList = accentTint
            setTextColor(Color.WHITE)
        }
        for (id in intArrayOf(R.id.btnProductCancel, R.id.btnPickGallery, R.id.btnTakePhoto)) {
            root.findViewById<MaterialButton>(id).apply {
                backgroundTintList = transparent
                setTextColor(accent)
                strokeColor = accentTint
                iconTint = accentTint
            }
        }
        root.findViewById<MaterialButton>(R.id.btnClearImage).apply {
            backgroundTintList = transparent
            setTextColor(accent)
        }
    }

    // ---- Image capture -------------------------------------------------------

    private fun launchCamera() {
        val file = File(requireContext().cacheDir, "product_capture.jpg")
        val uri = FileProvider.getUriForFile(
            requireContext(), "${requireContext().packageName}.fileprovider", file
        )
        cameraUri = uri
        cameraLauncher.launch(uri)
    }

    /** Decodes, downscales and compresses the picked image, then previews it. */
    private fun applyPickedImage(uri: Uri) {
        val bytes = try {
            requireContext().contentResolver.openInputStream(uri).use { input ->
                val original = BitmapFactory.decodeStream(input) ?: return
                compress(original)
            }
        } catch (_: Exception) {
            toast("Could not read that image")
            return
        }
        pendingImage = bytes
        imageCleared = false
        showImage(bytes)
    }

    /** Scales the longest edge down to 800px and encodes as JPEG to keep the BLOB small. */
    private fun compress(source: Bitmap): ByteArray {
        val max = 800
        val scale = minOf(1f, max.toFloat() / maxOf(source.width, source.height))
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source, (source.width * scale).toInt(), (source.height * scale).toInt(), true
            )
        } else {
            source
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
    }

    private fun showImage(bytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        dialogImageView?.setImageBitmap(bitmap)
    }

    // ---- Dropdown helpers ----------------------------------------------------

    private fun loadOptions(table: String, nameColumn: String): List<Option> {
        val options = mutableListOf<Option>()
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        db.rawQuery(
            "SELECT id, $nameColumn FROM $table WHERE store_id = ? ORDER BY $nameColumn COLLATE NOCASE",
            arrayOf(storeId().toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                options.add(Option(cursor.getInt(0), cursor.getString(1).orEmpty()))
            }
        }
        return options
    }

    private fun bindOptions(view: AutoCompleteTextView, options: List<Option>, selectedId: Int?) {
        view.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, options.map { it.label })
        )
        view.setOnItemClickListener { _, _, position, _ -> view.tag = options[position].id }
        options.firstOrNull { it.id == selectedId }?.let {
            view.setText(it.label, false)
            view.tag = it.id
        }
    }

    private fun bindDiscountType(view: AutoCompleteTextView, selectedCode: String?) {
        val codes = listOf("P" to "Percentage", "A" to "Amount")
        view.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, codes.map { it.second })
        )
        view.setOnItemClickListener { _, _, position, _ -> view.tag = codes[position].first }
        codes.firstOrNull { it.first == selectedCode }?.let {
            view.setText(it.second, false)
            view.tag = it.first
        }
    }

    private fun selectedId(view: AutoCompleteTextView): Int? = view.tag as? Int

    private fun text(root: android.view.View, id: Int): String =
        root.findViewById<TextInputEditText>(id).text.toString().trim()

    // ---- Persistence ---------------------------------------------------------

    private fun storeId(): Int = SessionManager.currentUser?.storeId ?: 0

    private class ProductForm(
        val name: String,
        val hsn: String,
        val barcode: String,
        val stockAlert: String,
        val categoryId: Int?,
        val batchNo: String,
        val rate1: String,
        val rate2: String,
        val rate3: String,
        val unit1Id: Int?,
        val unit2Id: Int?,
        val unit3Id: Int?,
        val cgst: String,
        val sgst: String,
        val igst: String,
        val vat: String,
        val discount: String,
        val discountType: String?,
        val sellPrice: String,
        val purchasePrice: String
    )

    private class ExistingProduct(
        val name: String, val hsn: String, val barcode: String, val stockAlert: String,
        val categoryId: Int?, val image: ByteArray?,
        val batchNo: String, val rate1: String, val rate2: String, val rate3: String,
        val unit1Id: Int?, val unit2Id: Int?, val unit3Id: Int?,
        val cgst: String, val sgst: String, val igst: String, val vat: String,
        val discount: String, val discountType: String?,
        val sellPrice: String, val purchasePrice: String
    )

    private fun loadProduct(productId: Int): ExistingProduct? {
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        val sql = """
            SELECT p.product_name, p.hsn_code, p.bar_code, p.stock_alert_qty, p.category_id, p.product_image,
                   r.batch_no, r.rate_1, r.rate_2, r.rate_3, r.unit_1_id, r.unit_2_id, r.unit_3_id,
                   r.cgst_rate, r.sgst_rate, r.igst_rate, r.vat_rate,
                   r.discount, r.discount_type, r.sell_price, r.purchase_price
            FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCT_RATES} r ON r.product_id = p.id
            WHERE p.id = ?
            LIMIT 1
        """.trimIndent()

        db.rawQuery(sql, arrayOf(productId.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return ExistingProduct(
                name = c.getString(0).orEmpty(),
                hsn = c.getString(1).orEmpty(),
                barcode = c.getString(2).orEmpty(),
                stockAlert = num(c, 3),
                categoryId = if (c.isNull(4)) null else c.getInt(4),
                image = if (c.isNull(5)) null else c.getBlob(5),
                batchNo = c.getString(6).orEmpty(),
                rate1 = num(c, 7), rate2 = num(c, 8), rate3 = num(c, 9),
                unit1Id = if (c.isNull(10)) null else c.getInt(10),
                unit2Id = if (c.isNull(11)) null else c.getInt(11),
                unit3Id = if (c.isNull(12)) null else c.getInt(12),
                cgst = num(c, 13), sgst = num(c, 14), igst = num(c, 15), vat = num(c, 16),
                discount = num(c, 17),
                discountType = if (c.isNull(18)) null else c.getString(18),
                sellPrice = num(c, 19), purchasePrice = num(c, 20)
            )
        }
    }

    /** Reads a numeric column as a display string ("" when null, no trailing ".0"). */
    private fun num(cursor: android.database.Cursor, index: Int): String {
        if (cursor.isNull(index)) return ""
        val value = cursor.getDouble(index)
        return if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }

    private fun saveProduct(productId: Int?, form: ProductForm) {
        val db = DatabaseHelper.getInstance(requireContext()).writableDatabase
        val storeId = storeId()

        db.beginTransaction()
        try {
            val product = ContentValues().apply {
                put("store_id", storeId)
                put("product_name", form.name)
                put("hsn_code", form.hsn.ifEmpty { null })
                put("bar_code", form.barcode.ifEmpty { null })
                putDouble(this, "stock_alert_qty", form.stockAlert)
                if (form.categoryId != null) put("category_id", form.categoryId) else putNull("category_id")
                // Only touch the image when the user picked a new one or cleared it.
                val image = pendingImage
                when {
                    image != null -> put("product_image", image)
                    imageCleared -> putNull("product_image")
                }
            }

            val id: Long = if (productId == null) {
                db.insert(DatabaseHelper.Tables.MD_PRODUCTS, null, product)
            } else {
                db.update(
                    DatabaseHelper.Tables.MD_PRODUCTS, product, "id = ?", arrayOf(productId.toString())
                )
                productId.toLong()
            }
            if (id == -1L) return

            val rates = ContentValues().apply {
                put("store_id", storeId)
                put("outlet_id", 0)
                put("product_id", id)
                putDouble(this, "rate_1", form.rate1)
                putDouble(this, "rate_2", form.rate2)
                putDouble(this, "rate_3", form.rate3)
                if (form.unit1Id != null) put("unit_1_id", form.unit1Id) else putNull("unit_1_id")
                if (form.unit2Id != null) put("unit_2_id", form.unit2Id) else putNull("unit_2_id")
                if (form.unit3Id != null) put("unit_3_id", form.unit3Id) else putNull("unit_3_id")
                put("cgst_rate", form.cgst.toDoubleOrNull() ?: 0.0)
                put("sgst_rate", form.sgst.toDoubleOrNull() ?: 0.0)
                put("igst_rate", form.igst.toDoubleOrNull() ?: 0.0)
                put("vat_rate", form.vat.toDoubleOrNull() ?: 0.0)
                put("discount", form.discount.toDoubleOrNull() ?: 0.0)
                if (form.discountType != null) put("discount_type", form.discountType) else putNull("discount_type")
                put("batch_no", form.batchNo.ifEmpty { null })
                putDouble(this, "sell_price", form.sellPrice)
                putDouble(this, "purchase_price", form.purchasePrice)
            }

            val hasRates = db.rawQuery(
                "SELECT 1 FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES} WHERE product_id = ? LIMIT 1",
                arrayOf(id.toString())
            ).use { it.moveToFirst() }

            if (hasRates) {
                db.update(
                    DatabaseHelper.Tables.MD_PRODUCT_RATES, rates, "product_id = ?", arrayOf(id.toString())
                )
            } else {
                db.insert(DatabaseHelper.Tables.MD_PRODUCT_RATES, null, rates)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Stores a numeric field, or NULL when the user left it blank. */
    private fun putDouble(values: ContentValues, key: String, raw: String) {
        val parsed = raw.toDoubleOrNull()
        if (parsed == null) values.putNull(key) else values.put(key, parsed)
    }
}
