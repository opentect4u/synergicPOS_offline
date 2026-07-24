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
import androidx.core.widget.addTextChangedListener
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.example.synergic_pos_offline.utils.SettingsCache
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

    override val columns = listOf("S.No", "Name", "HSN Code", "Barcode", "Category")

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
            ORDER BY p.id DESC
        """.trimIndent()

        db.rawQuery(sql, arrayOf(storeId().toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    DataRow(
                        id = cursor.getInt(0).toString(),
                        cells = listOf(
                            cursor.getInt(0).toString(),
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

        val existing = productId?.let { loadProduct(it) }

        bindOptions(actCategory, categories, existing?.categoryId)

        view.findViewById<TextInputEditText>(R.id.etName).setText(existing?.name.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etHsn).setText(existing?.hsn.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etBarcode).setText(existing?.barcode.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etStockAlert).setText(existing?.stockAlert.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etBatchNo).setText(existing?.batchNo.orEmpty())

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

            // Discount Type is required on any rate that carries a discount value.
            val ratesContainer = view.findViewById<LinearLayout>(R.id.llRates)
            for (i in 0 until ratesContainer.childCount) {
                val r = ratesContainer.getChildAt(i)
                val disc = r.findViewById<TextInputEditText>(R.id.etRateDiscount)
                val discType = r.findViewById<AutoCompleteTextView>(R.id.actRateDiscType)
                val tilDiscType = r.findViewById<TextInputLayout>(R.id.tilRateDiscType)
                if (disc.isEnabled && !disc.text.isNullOrBlank() && discType.text.isNullOrBlank()) {
                    tilDiscType.error = "Required"
                    return@setOnClickListener
                }
                tilDiscType.error = null
            }

            val rateRows = (0 until ratesContainer.childCount)
                .map { collectRate(ratesContainer.getChildAt(it)) }

            val form = ProductForm(
                name = name,
                hsn = text(view, R.id.etHsn),
                barcode = text(view, R.id.etBarcode),
                stockAlert = text(view, R.id.etStockAlert),
                categoryId = selectedId(actCategory),
                batchNo = text(view, R.id.etBatchNo),
                rates = rateRows
            )
            saveProduct(productId, form)
            dialog.dismiss()
            dialogImageView = null
            refreshRows()
            toast(if (productId == null) "Product added" else "Product updated")
        }

        // ----- Repeatable rate rows (Rate Name, Rate, Unit, CGST, IGST, VAT,
        // Discount, Discount Type, Sell/Purchase price) with add/remove. -----
        val llRates = view.findViewById<LinearLayout>(R.id.llRates)

        fun renumberRates() {
            for (i in 0 until llRates.childCount) {
                llRates.getChildAt(i).findViewById<TextView>(R.id.tvRateTitle).text = "Rate ${i + 1}"
            }
        }

        // Tax settings (local cache, type 'T') decide which tax fields are editable:
        // GST on -> CGST/SGST/IGST; VAT on -> VAT; both off -> all disabled.
        val gstOn = SettingsCache.value(requireContext(), "T", "GST") == "1"
        val vatOn = SettingsCache.value(requireContext(), "T", "VAT") == "1"
        val gstInclusive = SettingsCache.value(requireContext(), "T", "GST Type") == "I"
        val vatInclusive = SettingsCache.value(requireContext(), "T", "VAT Type") == "I"
        // Discount applies per product rate only when it is on AND item-wise; a
        // bill-wise discount is applied later at billing, so the master keeps the
        // rate's discount field disabled. Position decides how it hits sell price.
        val discountOn = SettingsCache.value(requireContext(), "T", "Discount") == "1"
        val itemWiseDiscount = discountOn && SettingsCache.value(requireContext(), "T", "Discount Type") == "1"
        val preTaxDiscount = SettingsCache.value(requireContext(), "T", "Discount Position") == "1"
        // Item Rate (general settings, type 'G'): Multiple ("M") allows several rate
        // cards; anything else (default Single, "S") pins the form to one card.
        val multipleRates = SettingsCache.value(requireContext(), "G", "Item Rate") == "M"

        fun addRateRow(prefill: RateRow? = null) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_product_rate, llRates, false)
            bindOptions(row.findViewById(R.id.actRateUnit), units, prefill?.unitId)
            bindDiscountType(row.findViewById(R.id.actRateDiscType), prefill?.discountType)

            val etCgst = row.findViewById<TextInputEditText>(R.id.etRateCgst)
            val etSgst = row.findViewById<TextInputEditText>(R.id.etRateSgst)
            val etIgst = row.findViewById<TextInputEditText>(R.id.etRateIgst)
            val etDiscount = row.findViewById<TextInputEditText>(R.id.etRateDiscount)
            val actDiscType = row.findViewById<AutoCompleteTextView>(R.id.actRateDiscType)
            etCgst.isEnabled = gstOn
            etSgst.isEnabled = gstOn
            etIgst.isEnabled = gstOn
            row.findViewById<TextInputEditText>(R.id.etRateVat).isEnabled = vatOn
            etDiscount.isEnabled = itemWiseDiscount
            actDiscType.isEnabled = itemWiseDiscount

            // Prefill from an existing rate (edit). Sell price is left to recompute.
            if (prefill != null) {
                row.findViewById<TextInputEditText>(R.id.etRateName).setText(prefill.rateName)
                row.findViewById<TextInputEditText>(R.id.etRate).setText(prefill.rate)
                etCgst.setText(prefill.cgst)
                etSgst.setText(prefill.sgst)
                etIgst.setText(prefill.igst)
                row.findViewById<TextInputEditText>(R.id.etRateVat).setText(prefill.vat)
                etDiscount.setText(prefill.discount)
                row.findViewById<TextInputEditText>(R.id.etRatePurchase).setText(prefill.purchasePrice)
            }

            // Within a rate (GST on): entering CGST/SGST disables IGST, and entering
            // IGST disables CGST + SGST - they are two ways to charge the same tax.
            if (gstOn) {
                fun syncGstFields() {
                    val cgstSgstFilled = !etCgst.text.isNullOrBlank() || !etSgst.text.isNullOrBlank()
                    val igstFilled = !etIgst.text.isNullOrBlank()
                    etIgst.isEnabled = !cgstSgstFilled
                    etCgst.isEnabled = !igstFilled
                    etSgst.isEnabled = !igstFilled
                }
                etCgst.addTextChangedListener { syncGstFields() }
                etSgst.addTextChangedListener { syncGstFields() }
                etIgst.addTextChangedListener { syncGstFields() }
                syncGstFields()
            }

            // Sell price is always derived and read-only. GST and VAT are mutually
            // exclusive, so one effective tax %/inclusive flag drives everything.
            // Item-wise discount (P = %, A = flat amount):
            //   pre-tax  + inclusive  -> base = rate/(1+t); discount base; re-add tax
            //   pre-tax  + exclusive  -> discount rate; then add tax
            //   post-tax + inclusive  -> just discount the rate (tax already inside)
            //   post-tax + exclusive  -> add tax to rate; then discount the gross
            // No item discount: inclusive -> rate ; exclusive -> rate + tax.
            val etRateVal = row.findViewById<TextInputEditText>(R.id.etRate)
            val etVat = row.findViewById<TextInputEditText>(R.id.etRateVat)
            val etSell = row.findViewById<TextInputEditText>(R.id.etRateSell)
            etSell.isFocusable = false
            etSell.isFocusableInTouchMode = false
            etSell.isCursorVisible = false
            etSell.keyListener = null

            fun computeSellPrice() {
                if (etRateVal.text.isNullOrBlank()) { etSell.setText(""); return }
                val rate = etRateVal.text?.toString()?.toDoubleOrNull() ?: 0.0
                // Effective tax rate + inclusive flag (GST xor VAT).
                val taxPct: Double
                val inclusive: Boolean
                when {
                    gstOn -> {
                        val cgst = etCgst.text?.toString()?.toDoubleOrNull() ?: 0.0
                        val sgst = etSgst.text?.toString()?.toDoubleOrNull() ?: 0.0
                        val igst = etIgst.text?.toString()?.toDoubleOrNull() ?: 0.0
                        taxPct = cgst + sgst + igst
                        inclusive = gstInclusive
                    }
                    vatOn -> {
                        taxPct = etVat.text?.toString()?.toDoubleOrNull() ?: 0.0
                        inclusive = vatInclusive
                    }
                    else -> { taxPct = 0.0; inclusive = false }
                }
                val t = taxPct / 100.0

                val sell = if (itemWiseDiscount) {
                    val discVal = etDiscount.text?.toString()?.toDoubleOrNull() ?: 0.0
                    val isPercent = actDiscType.text?.toString() != "Amount"
                    fun less(base: Double) =
                        (base - if (isPercent) base * discVal / 100.0 else discVal).coerceAtLeast(0.0)
                    if (preTaxDiscount) {
                        if (inclusive) {
                            // 1) strip tax to get base, discount base, re-apply tax
                            less(rate / (1 + t)) * (1 + t)
                        } else {
                            // 2) discount the rate, then add tax
                            less(rate) * (1 + t)
                        }
                    } else {
                        if (inclusive) {
                            // 3) tax already inside the rate -> just discount the rate
                            less(rate)
                        } else {
                            // 4) add tax to the rate, then discount the gross
                            less(rate * (1 + t))
                        }
                    }
                } else {
                    if (inclusive) rate else rate * (1 + t)
                }
                etSell.setText(String.format("%.2f", sell))
            }
            etRateVal.addTextChangedListener { computeSellPrice() }
            etCgst.addTextChangedListener { computeSellPrice() }
            etSgst.addTextChangedListener { computeSellPrice() }
            etIgst.addTextChangedListener { computeSellPrice() }
            etVat.addTextChangedListener { computeSellPrice() }
            if (itemWiseDiscount) {
                val tilDiscType = row.findViewById<TextInputLayout>(R.id.tilRateDiscType)
                etDiscount.addTextChangedListener { computeSellPrice() }
                actDiscType.addTextChangedListener {
                    if (!it.isNullOrBlank()) tilDiscType.error = null
                    computeSellPrice()
                }
            }
            computeSellPrice()
            val btnRemove = row.findViewById<ImageButton>(R.id.btnRemoveRate)
            // Single mode never has more than one card, so hide its remove control.
            btnRemove.visibility = if (multipleRates) android.view.View.VISIBLE else android.view.View.GONE
            btnRemove.setOnClickListener {
                if (llRates.childCount > 1) {
                    llRates.removeView(row)
                    renumberRates()
                } else {
                    toast("At least one rate is required")
                }
            }
            llRates.addView(row)
            ThemeManager.applyTheme(row)
            renumberRates()
        }

        // "+ Add Rate" only exists in Multiple mode.
        val btnAddRate = view.findViewById<MaterialButton>(R.id.btnAddRate)
        btnAddRate.visibility = if (multipleRates) android.view.View.VISIBLE else android.view.View.GONE
        btnAddRate.setOnClickListener { addRateRow() }

        val existingRates = existing?.rates
        when {
            existingRates.isNullOrEmpty() -> addRateRow()          // one blank card
            multipleRates -> existingRates.forEach { addRateRow(it) }
            else -> addRateRow(existingRates.first())              // single: default card only
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

    /**
     * Offers the legal GST slabs and keeps the read-only CGST/SGST boxes showing
     * half of the chosen one each, which is how an intra-state supply is split.
     */
    private fun bindGstSlab(
        view: AutoCompleteTextView,
        cgstField: TextInputEditText,
        sgstField: TextInputEditText,
        selectedRate: Double?
    ) {
        val slabs = DatabaseHelper.GST_SLABS
        val labels = slabs.map { pctLabel(it) }
        view.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels))

        fun apply(rate: Double) {
            view.tag = rate
            cgstField.setText(pctLabel(rate / 2.0))
            sgstField.setText(pctLabel(rate / 2.0))
        }

        view.setOnItemClickListener { _, _, position, _ -> apply(slabs[position]) }

        val current = slabs.firstOrNull { it == selectedRate } ?: slabs.first()
        view.setText(pctLabel(current), false)
        apply(current)
    }

    /** Trims a whole-number rate to "18", keeping "0.25" and "2.5" intact. */
    private fun pctLabel(rate: Double): String =
        if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString()

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

    /** store_id and outlet_id sourced from md_registration (verified row preferred). */
    private fun storeAndOutlet(): Pair<Int?, Int?> {
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        db.rawQuery(
            "SELECT store_id, outlet_id FROM ${DatabaseHelper.Tables.MD_REGISTRATION} " +
                "ORDER BY verify_flag DESC, store_id ASC LIMIT 1", null
        ).use { c ->
            if (c.moveToFirst()) {
                val s = if (c.isNull(0)) null else c.getInt(0)
                val o = if (c.isNull(1)) null else c.getInt(1)
                return s to o
            }
        }
        return null to null
    }

    /** One rate card's values (raw strings, parsed on save). */
    private class RateRow(
        val rateName: String = "",
        val rate: String = "",
        val unitId: Int? = null,
        val cgst: String = "",
        val sgst: String = "",
        val igst: String = "",
        val vat: String = "",
        val discount: String = "",
        val discountType: String? = null,
        val sellPrice: String = "",
        val purchasePrice: String = ""
    )

    /** Reads one inflated rate card back into a [RateRow]. */
    private fun collectRate(row: android.view.View): RateRow = RateRow(
        rateName = text(row, R.id.etRateName),
        rate = text(row, R.id.etRate),
        unitId = row.findViewById<AutoCompleteTextView>(R.id.actRateUnit).tag as? Int,
        cgst = text(row, R.id.etRateCgst),
        sgst = text(row, R.id.etRateSgst),
        igst = text(row, R.id.etRateIgst),
        vat = text(row, R.id.etRateVat),
        discount = text(row, R.id.etRateDiscount),
        discountType = row.findViewById<AutoCompleteTextView>(R.id.actRateDiscType).tag as? String,
        sellPrice = text(row, R.id.etRateSell),
        purchasePrice = text(row, R.id.etRatePurchase)
    )

    private class ProductForm(
        val name: String,
        val hsn: String,
        val barcode: String,
        val stockAlert: String,
        val categoryId: Int?,
        val batchNo: String,
        val rates: List<RateRow>
    )

    private class ExistingProduct(
        val name: String, val hsn: String, val barcode: String, val stockAlert: String,
        val categoryId: Int?, val image: ByteArray?, val batchNo: String,
        val rates: List<RateRow>
    )

    private fun loadProduct(productId: Int): ExistingProduct? {
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase

        // All rate cards for this product (default rate first).
        val rates = mutableListOf<RateRow>()
        db.rawQuery(
            """
            SELECT rate_name, rate, unit_id, cgst_rate, sgst_rate, igst_rate, vat_rate,
                   discount, discount_type, sell_price, purchase_price
            FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES}
            WHERE product_id = ?
            ORDER BY "default" DESC, id ASC
            """.trimIndent(),
            arrayOf(productId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                rates.add(
                    RateRow(
                        rateName = c.getString(0).orEmpty(),
                        rate = num(c, 1),
                        unitId = if (c.isNull(2)) null else c.getInt(2),
                        cgst = num(c, 3), sgst = num(c, 4), igst = num(c, 5), vat = num(c, 6),
                        discount = num(c, 7),
                        discountType = if (c.isNull(8)) null else c.getString(8),
                        sellPrice = num(c, 9), purchasePrice = num(c, 10)
                    )
                )
            }
        }

        db.rawQuery(
            "SELECT product_name, hsn_code, bar_code, stock_alert_qty, category_id, product_image " +
                "FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE id = ? LIMIT 1",
            arrayOf(productId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            return ExistingProduct(
                name = c.getString(0).orEmpty(),
                hsn = c.getString(1).orEmpty(),
                barcode = c.getString(2).orEmpty(),
                stockAlert = num(c, 3),
                categoryId = if (c.isNull(4)) null else c.getInt(4),
                image = if (c.isNull(5)) null else c.getBlob(5),
                batchNo = "",
                rates = rates
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
        // store_id and outlet_id both come from md_registration.
        val (storeId, outletId) = storeAndOutlet()

        db.beginTransaction()
        try {
            val product = ContentValues().apply {
                if (storeId != null) put("store_id", storeId) else putNull("store_id")
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

            // Replace this product's rate cards wholesale (delete then re-insert), so
            // added/removed rows stay in sync. sku (=rate id) is set by a DB trigger.
            db.delete(
                DatabaseHelper.Tables.MD_PRODUCT_RATES, "product_id = ?", arrayOf(id.toString())
            )
            var firstRateId = -1L
            form.rates.forEachIndexed { index, r ->
                val rate = ContentValues().apply {
                    if (storeId != null) put("store_id", storeId) else putNull("store_id")
                    if (outletId != null) put("outlet_id", outletId) else putNull("outlet_id")
                    put("product_id", id)
                    put("rate_name", r.rateName.ifEmpty { null })
                    putDouble(this, "rate", r.rate)
                    if (r.unitId != null) put("unit_id", r.unitId) else putNull("unit_id")
                    putDouble(this, "cgst_rate", r.cgst)
                    putDouble(this, "sgst_rate", r.sgst)
                    putDouble(this, "igst_rate", r.igst)
                    putDouble(this, "vat_rate", r.vat)
                    put("discount", r.discount.toDoubleOrNull() ?: 0.0)
                    if (r.discountType != null) put("discount_type", r.discountType) else putNull("discount_type")
                    putDouble(this, "sell_price", r.sellPrice)
                    putDouble(this, "purchase_price", r.purchasePrice)
                }
                val rid = db.insert(DatabaseHelper.Tables.MD_PRODUCT_RATES, null, rate)
                if (index == 0) firstRateId = rid
            }
            // First card is the product's default rate. "default" is a reserved word,
            // so set it via execSQL where SQLite honours the quoted identifier.
            if (firstRateId != -1L) {
                db.execSQL(
                    "UPDATE ${DatabaseHelper.Tables.MD_PRODUCT_RATES} SET \"default\" = 1 WHERE id = ?",
                    arrayOf<Any>(firstRateId)
                )
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
