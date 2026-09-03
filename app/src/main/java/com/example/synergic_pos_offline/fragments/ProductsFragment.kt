package com.example.synergic_pos_offline.fragments

import android.content.ContentValues
import android.content.res.ColorStateList
import android.database.sqlite.SQLiteConstraintException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.database.StockDao
import com.example.synergic_pos_offline.utils.AppLanguage
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.ProductName
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.Downloads
import com.example.synergic_pos_offline.utils.ProductCsvExport
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

    /** Whether stock is tracked. Decides the extra column and the dialog's stock block. */
    private val stockTracked by lazy { GeneralSettingsDao.isStockEnabled(requireContext()) }

    /**
     * Stock is appended rather than slotted in, so the columns before it keep their
     * positions - [filterColumnIndex] and the cell order both count from the front,
     * and a column that only sometimes exists must not shift them.
     */
    override val columns by lazy {
        listOf("S.No", "Name", "HSN Code", "Barcode", "Category") +
            if (stockTracked) listOf("Stock") else emptyList()
    }

    /** Products filter by category - the "Category" column above. */
    override val filterColumnIndex = 4

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

    // ---- Bulk upload -----------------------------------------------------------

    /** A single FAB opens the dedicated, category-wise bulk-upload page. */
    override fun bulkPageEnabled(): Boolean = true

    override fun onBulkPage() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, BulkUploadProductFragment())
            .addToBackStack(null)
            .commit()
    }

    /** The download icon (beside the bin) shows only on Products. */
    override fun showDownloadTemplate(): Boolean = true

    override fun onDownloadTemplate() = downloadTemplate()

    /**
     * Saves the product master itself to Downloads - not the blank template.
     *
     * This button used to hand over an empty sheet, which the Bulk Upload page also
     * does and does better, since that is where a template is any use. What is
     * wanted from a table is the table: the catalogue joined back together across
     * products, rates, categories and units.
     *
     * It comes out in the upload's own format ([ProductCsvExport]), so the two make
     * a round trip - export what is here, edit it in a spreadsheet, upload it back.
     */
    private fun downloadTemplate() {
        try {
            val savedTo = Downloads.save(
                requireContext(), ProductCsvExport.FILE_NAME,
                ProductCsvExport.content(requireContext())
            )
            toast("Products saved to $savedTo")
        } catch (e: Exception) {
            toast("Could not save products: ${e.message}")
        }
    }

    // ---- Table ---------------------------------------------------------------

    override fun loadRows(): MutableList<DataRow> {
        val rows = mutableListOf<DataRow>()
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        // The count is summed in the query rather than read per row: the master lists
        // the whole catalogue, and a lookup per product would be a query per tile.
        val sql = """
            SELECT p.id, p.product_name, p.hsn_code, p.bar_code, c.category_name, p.product_image,
                   COALESCE((SELECT SUM(s.current_quantity) FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK} s
                             WHERE s.product_id = p.id), 0)
            FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
            LEFT JOIN ${DatabaseHelper.Tables.MD_CATEGORY} c ON c.id = p.category_id
            WHERE p.store_id = ?
            ORDER BY p.id DESC
        """.trimIndent()

        db.rawQuery(sql, arrayOf(storeId().toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val cells = listOf(
                    cursor.getInt(0).toString(),
                    cursor.getString(1).orEmpty(),
                    cursor.getString(2).orEmpty(),
                    cursor.getString(3).orEmpty(),
                    cursor.getString(4).orEmpty()
                )
                rows.add(
                    DataRow(
                        id = cursor.getInt(0).toString(),
                        cells = if (stockTracked) cells + StockDao.trim(cursor.getDouble(6)) else cells,
                        thumbnail = if (cursor.isNull(5)) null else cursor.getBlob(5)
                    )
                )
            }
        }
        return rows
    }

    override fun formatCellText(columnIndex: Int, row: DataRow, text: String): String {
        return when (columnIndex) {
            1 -> {
                val language = AppLanguage.of(requireContext())
                ProductName.inAppLanguage(language, text)
            }
            else -> text
        }
    }

    override fun onAddRow() = showProductDialog(null)

    override fun onEditRow(row: DataRow) = showProductDialog(row.id.toIntOrNull())

    /**
     * Prints the catalogue as a table rather than as a block per record.
     *
     * The generic master printout is right for a screen with a handful of records
     * and a few fields each. The item master is neither: it is the one master that
     * runs to hundreds of rows, and what anyone printing it wants is a price list -
     * names down one column, figures down another, so a wrong rate is found by
     * running a finger down the page. See [ProductListPrinter].
     */
    override fun onBulkPrint() {
        val ids = selectedRowIds
        if (ids.isEmpty()) { toast("Select at least one product to print"); return }

        val ctx = requireContext()
        val config = com.example.synergic_pos_offline.utils.ThermalPrinter.configForPurpose(ctx, "BILL")
        if (config == null) {
            toast("No printer set up — configure a bill printer first")
            return
        }

        DialogUtils.showConfirm(
            context = ctx,
            title = "Print Selected",
            message = "Do you want to print ${ids.size} selected product(s)?",
            positiveText = "Print All",
            negativeText = "Cancel",
            iconRes = android.R.drawable.ic_menu_set_as
        ) {
            val bitmap = com.example.synergic_pos_offline.utils.ProductListPrinter
                .render(ctx, ids, config.paperDots)
            if (bitmap == null) {
                toast("Could not build the product list")
                return@showConfirm
            }
            toast("Printing ${ids.size} product(s)…")
            com.example.synergic_pos_offline.utils.ThermalPrinter.print(ctx, bitmap, config) { result ->
                if (!isAdded) return@print
                when (result) {
                    is com.example.synergic_pos_offline.utils.ThermalPrinter.Result.Failure ->
                        toast("Print failed: ${result.message}")
                    else -> toast("Sent ${ids.size} product(s) to the printer")
                }
            }
        }
    }

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
        val context = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_product_form, null)
        val dialog = AlertDialog.Builder(context).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        // Reset per-dialog image state.
        pendingImage = null
        imageCleared = false
        dialogImageView = view.findViewById(R.id.ivProductImage)

        val categories = loadOptions(DatabaseHelper.Tables.MD_CATEGORY, "category_name")
        val units = loadOptions(DatabaseHelper.Tables.MD_UNITS, "unit_name")
        val rateNames = loadRateNames()   // master rate names (Rate 1 / Rate 2 / MRP …)

        val actCategory = view.findViewById<AutoCompleteTextView>(R.id.actCategory)

        val existing = productId?.let { loadProduct(it) }

        bindOptions(actCategory, categories, existing?.categoryId)

        view.findViewById<TextInputEditText>(R.id.etName).setText(existing?.name.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etHsn).setText(existing?.hsn.orEmpty())
        val etBarcode = view.findViewById<TextInputEditText>(R.id.etBarcode)
        etBarcode.setText(existing?.barcode.orEmpty())
        bindGenerateBarcode(view, etBarcode)
        view.findViewById<TextInputEditText>(R.id.etStockAlert).setText(existing?.stockAlert.orEmpty())

        // The stock block shows whenever stock is tracked, but says different things
        // either side of an edit. Adding: the opening batch, which is what sets the
        // count. Editing: what is on hand now, an Add Stock box to receive more, and
        // the opening figure locked - that batch is already there with a stock history
        // behind it, and re-opening it would silently rewrite a count that sales and
        // deliveries have since moved. Adding to the count is a different act, and it
        // goes through the same receive() the Stock In screen uses, so it lands in the
        // stock history as the delivery it is.
        val editing = productId != null
        val capturesOpeningStock = stockTracked && !editing
        view.findViewById<LinearLayout>(R.id.llStockDetails).visibility =
            if (stockTracked) android.view.View.VISIBLE else android.view.View.GONE

        if (stockTracked && !capturesOpeningStock) {
            view.findViewById<LinearLayout>(R.id.llCurrentStock).visibility =
                android.view.View.VISIBLE
            view.findViewById<TextInputEditText>(R.id.etCurrentStock).setText(
                StockDao.trim(StockDao(context).stockOf(productId!!))
            )
            // Locked, not hidden: the opening figure is part of what the product is,
            // and an edit that simply dropped it would read as if it never had one.
            // Disabling the layout greys its field along with it.
            view.findViewById<TextInputLayout>(R.id.tilOpeningStock).isEnabled = false
            view.findViewById<TextInputEditText>(R.id.etOpeningStock).isEnabled = false
        }

        // Restaurant-only attributes, shown above the rates section in Restaurant mode.
        val restaurantMode = SettingsCache.value(requireContext(), "G", "Mode") == "R"
        view.findViewById<LinearLayout>(R.id.llRestaurantDetails).visibility =
            if (restaurantMode) android.view.View.VISIBLE else android.view.View.GONE
        if (restaurantMode) {
            bindStrings(view.findViewById(R.id.actFoodType), listOf("Veg", "Non-Veg", "Egg"))
            bindStrings(view.findViewById(R.id.actAvailability), listOf("Available", "Unavailable"))
            bindStrings(view.findViewById(R.id.actSpiceLevel), listOf("Mild", "Medium", "Hot"))
            existing?.foodType?.takeIf { it.isNotBlank() }
                ?.let { view.findViewById<AutoCompleteTextView>(R.id.actFoodType).setText(it, false) }
            existing?.spiceLevel?.takeIf { it.isNotBlank() }
                ?.let { view.findViewById<AutoCompleteTextView>(R.id.actSpiceLevel).setText(it, false) }
            existing?.prepTime?.takeIf { it.isNotBlank() }
                ?.let { view.findViewById<TextInputEditText>(R.id.etPrepTime).setText(it) }
            existing?.availability?.takeIf { it.isNotBlank() }
                ?.let { view.findViewById<AutoCompleteTextView>(R.id.actAvailability).setText(it, false) }
        }

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

            // With stock tracked, the quantity a product opens with is part of adding
            // it: a product created without one would sit at nothing on the sale
            // screen until someone noticed and took it through Stock In.
            if (capturesOpeningStock) {
                val tilOpening = view.findViewById<TextInputLayout>(R.id.tilOpeningStock)
                val openingQty = text(view, R.id.etOpeningStock).toDoubleOrNull()
                if (openingQty == null) {
                    tilOpening.error = "Opening stock is required"
                    return@setOnClickListener
                }
                if (openingQty < 0.0) {
                    tilOpening.error = "Enter a quantity of 0 or more"
                    return@setOnClickListener
                }
                tilOpening.error = null
            }

            // Stock being received on this edit, checked before anything is written -
            // the same rule the Stock In screen applies to a row: a quantity or
            // nothing, and never a negative one.
            val tilAdd = view.findViewById<TextInputLayout>(R.id.tilAddStock)
            val addStockText = text(view, R.id.etAddStock)
            val addStockQty = if (addStockText.isBlank()) 0.0 else addStockText.toDoubleOrNull()
            if (editing && stockTracked) {
                if (addStockQty == null) {
                    tilAdd.error = "Enter a quantity, or leave it empty"
                    return@setOnClickListener
                }
                if (addStockQty < 0.0) {
                    tilAdd.error = "Stock can only be added here — use Write Off to take it off"
                    return@setOnClickListener
                }
                tilAdd.error = null
            }

            // Discount Type is required on any rate that carries a discount value.
            val ratesContainer = view.findViewById<LinearLayout>(R.id.llRates)
            for (i in 0 until ratesContainer.childCount) {
                val r = ratesContainer.getChildAt(i)
                val disc = r.findViewById<TextInputEditText>(R.id.etRateDiscount)
                val discType = r.findViewById<AutoCompleteTextView>(R.id.actRateDiscType)
                val tilDiscType = r.findViewById<TextInputLayout>(R.id.tilRateDiscType)
                // A type is only required for a *real* discount (> 0). A stored 0
                // shows up as "0" in the field, which must not block saving.
                val discNum = disc.text?.toString()?.toDoubleOrNull() ?: 0.0
                if (disc.isEnabled && discNum > 0.0 && discType.text.isNullOrBlank()) {
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
                openingStock = text(view, R.id.etOpeningStock),
                foodType = view.findViewById<TextView>(R.id.actFoodType).text?.toString()?.trim().orEmpty(),
                spiceLevel = view.findViewById<TextView>(R.id.actSpiceLevel).text?.toString()?.trim().orEmpty(),
                prepTime = text(view, R.id.etPrepTime),
                availability = view.findViewById<TextView>(R.id.actAvailability).text?.toString()?.trim().orEmpty(),
                rates = rateRows
            )
            saveProduct(productId, form, capturesOpeningStock)
            // Received after the product is saved, and through the Stock In write
            // itself: it joins the batch the product last moved in and leaves its own
            // line in the stock history, exactly as a delivery booked from Stock In.
            val received = addStockQty ?: 0.0
            if (editing && stockTracked && received > 0.0) {
                StockDao(context).receive(
                    listOf(StockDao.Movement(productId!!.toInt(), received)),
                    "Added from Edit Product"
                )
            }
            dialog.dismiss()
            dialogImageView = null
            refreshRows()
            toast(
                when {
                    productId == null -> "Product added"
                    received > 0.0 -> "Product updated — ${StockDao.trim(received)} added to stock"
                    else -> "Product updated"
                }
            )
        }

        // ----- Repeatable rate rows (Rate Name, Rate, Unit, CGST, IGST, VAT,
        // Discount, Discount Type, Sell/Purchase price) with add/remove. -----
        val llRates = view.findViewById<LinearLayout>(R.id.llRates)

        fun renumberRates() {
            for (i in 0 until llRates.childCount) {
                llRates.getChildAt(i).findViewById<TextView>(R.id.tvRateTitle).text = "Rate ${i + 1}"
            }
        }

        // Tax settings (local cache, type 'T') decide two things here: which of a
        // rate's own GST or VAT figure the sell price is worked out from, and - since
        // this change - which of those figures the form ASKS FOR at all.
        //
        // It used to ask for all four on every product whatever the settings said, on
        // the reasoning that a shop with VAT off might still want to carry a VAT rate
        // against the day it sells under VAT. That is a real case, and the cost of it
        // was a VAT-only shop being asked for three GST figures it does not use and a
        // no-tax shop being asked for four. The form now follows what the till
        // charges.
        //
        // THE CASE IT WOULD HAVE BROKEN IS KEPT: a field is hidden only when its tax
        // is off AND this rate carries no figure for it. A rate already holding VAT
        // still shows VAT on a VAT-off till, so nothing that was configured
        // disappears, and nothing already behind a bill becomes invisible.
        //
        // What gates these fields against EACH OTHER is unchanged and is not about
        // the settings at all - see syncTaxFields: a rate is taxed one way or the
        // other, never both.
        // ONLY TWO SETTINGS ARE READ HERE, and neither decides what may be entered.
        //
        // GST/VAT on-off and Discount Type are gone from this form entirely: they say
        // what the TILL charges, and this form records what the PRODUCT is. Reading
        // them here is what made a typed VAT vanish from Sell Price on a GST till and a
        // typed discount do nothing on a bill-wise one.
        //
        // What is left is the two that say how to READ a number rather than whether it
        // counts, and neither has a per-product answer anywhere:
        //
        //  * Inclusive/exclusive - whether the rate typed already contains its tax.
        //  * Discount position - whether a discount comes off before tax or after.
        val gstInclusive = SettingsCache.value(requireContext(), "T", "GST Type") == "I"
        val vatInclusive = SettingsCache.value(requireContext(), "T", "VAT Type") == "I"
        val preTaxDiscount = SettingsCache.value(requireContext(), "T", "Discount Position") == "1"
        // Item Rate (general settings, type 'G'): Multiple ("M") allows several rate
        // cards; anything else (default Single, "S") pins the form to one card.
        val multipleRates = SettingsCache.value(requireContext(), "G", "Item Rate") == "M"

        fun addRateRow(prefill: RateRow? = null) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_product_rate, llRates, false)
            bindOptions(row.findViewById(R.id.actRateName), rateNames, prefill?.rateNameId)
            bindOptions(row.findViewById(R.id.actRateUnit), units, prefill?.unitId)
            bindDiscountType(row.findViewById(R.id.actRateDiscType), prefill?.discountType)

            val etCgst = row.findViewById<TextInputEditText>(R.id.etRateCgst)
            val etSgst = row.findViewById<TextInputEditText>(R.id.etRateSgst)
            val etIgst = row.findViewById<TextInputEditText>(R.id.etRateIgst)
            val etDiscount = row.findViewById<TextInputEditText>(R.id.etRateDiscount)
            val actDiscType = row.findViewById<AutoCompleteTextView>(R.id.actRateDiscType)
            // A rate that has already been saved keeps the tax and the discount it
            // was saved with - GST, VAT and the discount alike, since a shop that
            // moved between the two regimes has bills under both - the way an edited
            // product keeps its opening stock:
            // both are already behind every bill this rate has been sold on, and
            // those bills carry the figures they were raised with. Editing them here
            // would not correct a single one of them - it would only make the master
            // disagree with the books, with nothing to say which was right.
            //
            // Keyed on the rate rather than on the dialog, so "+ Add Rate" during an
            // edit still gives a rate that can be configured: a genuinely different
            // tax or discount is a different rate, and that is how one is made.
            val etVat = row.findViewById<TextInputEditText>(R.id.etRateVat)

            // ALL FOUR TAX BOXES ARE ALWAYS ON SCREEN AND ALWAYS EDITABLE. Tax
            // Settings has no say here.
            //
            // They used to be GONE where the till did not charge that tax, which meant
            // the same product opened on a GST till and on a VAT one was two different
            // forms with nothing on either to say why. The master records what a
            // product IS taxed at; which of those taxes a till happens to charge today
            // is the billing screens' question, and they read the setting for
            // themselves. A shop can therefore carry a VAT figure against the day it
            // sells under VAT, without switching a setting to type it.
            //
            // The only rule left is the one below, and it is about the product rather
            // than the till: a rate is under VAT or under GST, never both.
            // The discount goes the same way as the tax boxes above: always on screen,
            // always editable, whatever Tax Settings says.
            //
            // It used to be hidden unless the till was set to ITEM-WISE discount, and
            // greyed even then unless it was. So on a shop set to bill-wise - or with
            // discount switched off while it was being set up - the Discount and
            // Discount Type fields were simply not on the form, and there was nothing
            // to say they existed or why they had gone.
            //
            // Same reasoning as the tax fields: the master records what the PRODUCT is
            // - what it is taxed at, what it is discounted by - and which of those a
            // till actually applies is a question for the screens that price a bill.
            // They read the setting for themselves ([CartMath.Config.itemwiseDiscount]),
            // so a figure sitting here on a bill-wise till costs nothing and is ready
            // for the day the shop switches over.

            // A SAVED RATE'S TAX AND DISCOUNT ARE EDITABLE.
            //
            // They were locked, on the reasoning that these figures sit behind every
            // bill the rate has been sold on and editing them here would make the
            // master disagree with the books. That reasoning does not hold: a bill
            // stores its OWN cgst_rate, sgst_rate and vat_rate against every line it
            // writes (see BillDao), and a running order snapshots them too. Nothing
            // already sold reads its tax from the master, so nothing already sold
            // changes. What the master says is what the NEXT sale will charge.
            //
            // And a shop's rates do change - a slab moves, a product is reclassified,
            // a figure is simply typed wrong. With this locked the only way to correct
            // one was to add a second rate beside it and leave the wrong one in place,
            // which leaves the master less true, not more.
            // Left enabled. See the note above the discount row for why Tax Settings
            // no longer decides whether this can be typed in.

            // Prefill from an existing rate (edit). The name is fixed by position
            // (set in renumberRates), so it is not restored from the saved value.
            if (prefill != null) {
                row.findViewById<TextInputEditText>(R.id.etRate).setText(prefill.rate)
                etCgst.setText(prefill.cgst)
                etSgst.setText(prefill.sgst)
                etIgst.setText(prefill.igst)
                etVat.setText(prefill.vat)
                etDiscount.setText(prefill.discount)
                row.findViewById<TextInputEditText>(R.id.etRatePurchase).setText(prefill.purchasePrice)
            }

            // A rate is taxed one way or the other, never both - see the comment
            // above computeSellPrice for why one effective rate has to come out of
            // whatever is entered here. So within CGST/SGST/IGST/VAT: a VAT value
            // locks out all three GST fields, and a GST value (CGST+SGST, or IGST)
            // locks out VAT; within GST itself, CGST/SGST and IGST stay mutually
            // exclusive as before - two ways to charge the same tax.
            //
            // Gated only on the rate being new, never on whether GST or VAT is
            // switched on - Tax Settings decides which of these figures the bill
            // is CALCULATED from, not which ones the master will accept. A shop
            // with VAT off can still carry a VAT rate here against the day it sells
            // this product under VAT instead.
            //
            // Wired on a saved rate as well as a new one, now that a saved rate can be
            // edited at all. It used to be skipped there because none of these fields
            // were enabled and running it would have re-enabled whichever half of the
            // split was blank - the opposite of what was wanted. With editing allowed
            // it is exactly what is wanted: clear the VAT box on a saved rate and the
            // GST boxes open up, which is how a rate is moved from one tax to the
            // other.
            //
            // A blank counts as unfilled, so a figure of 0 does not lock out the other
            // side - the rule is about which tax this rate is under, not about zero.
            //
            // THE ONE RULE: a rate is under VAT or under GST, never both.
            //
            //   VAT typed              -> CGST, SGST and IGST go grey.
            //   CGST, SGST or IGST typed -> VAT goes grey.
            //
            // And inside GST the same exclusion again, because CGST+SGST is an
            // intra-state sale and IGST an inter-state one, and a line is one or the
            // other: filling either side greys the opposite one.
            //
            // Nothing here reads Tax Settings. Which tax the till charges is a
            // question for the screens that price a bill; this form records what the
            // product is taxed at, and every box on it stays open until the rule above
            // closes one. Clearing a box is what re-opens the other side, which is how
            // a rate is moved from one tax to the other.
            /**
             * Whether a tax box actually carries a RATE - a figure above zero.
             *
             * Not "has any text in it", which is what this used to ask and what made an
             * edited product read-only. A saved rate stores 0 in the taxes it does not
             * use, not NULL, so opening one prefilled every box: "0" is not blank, so
             * all four counted as filled, so all four locked each other out and the
             * whole row came up grey. A product saved under VAT could not have its VAT
             * corrected, and one saved under GST could not have its GST corrected.
             *
             * Zero is the absence of a tax, and the comment above has always said so -
             * "a figure of 0 does not lock out the other side" - it was only the test
             * that disagreed.
             */
            fun rated(field: TextInputEditText): Boolean =
                (field.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0) > 0.0

            fun syncTaxFields() {
                val vatFilled = rated(etVat)
                val cgstSgstFilled = rated(etCgst) || rated(etSgst)
                val igstFilled = rated(etIgst)
                val gstFilled = cgstSgstFilled || igstFilled
                etVat.isEnabled = !gstFilled
                etIgst.isEnabled = !vatFilled && !cgstSgstFilled
                etCgst.isEnabled = !vatFilled && !igstFilled
                etSgst.isEnabled = !vatFilled && !igstFilled
            }
            etCgst.addTextChangedListener { syncTaxFields() }
            etSgst.addTextChangedListener { syncTaxFields() }
            etIgst.addTextChangedListener { syncTaxFields() }
            etVat.addTextChangedListener { syncTaxFields() }
            // RUN IT ONCE, HERE. The listeners only fire on a change, and an edit
            // arrives with its figures already in the boxes - put there by the prefill
            // above, which happens before this. Without this call a saved rate opened
            // with both sides enabled and stayed that way until something was typed,
            // so a VAT-rated product let CGST and SGST be filled in beside its VAT.
            //
            // After the prefill by necessity: run before it and there is nothing in
            // the fields to lock anything out.
            syncTaxFields()

            // Sell price is always derived and read-only. GST and VAT are mutually
            // exclusive, so one effective tax %/inclusive flag drives everything.
            // Item-wise discount (P = %, A = flat amount):
            //   pre-tax  + inclusive  -> base = rate/(1+t); discount base; re-add tax
            //   pre-tax  + exclusive  -> discount rate; then add tax
            //   post-tax + inclusive  -> just discount the rate (tax already inside)
            //   post-tax + exclusive  -> add tax to rate; then discount the gross
            // No item discount: inclusive -> rate ; exclusive -> rate + tax.
            val etRateVal = row.findViewById<TextInputEditText>(R.id.etRate)
            val etSell = row.findViewById<TextInputEditText>(R.id.etRateSell)
            etSell.isFocusable = false
            etSell.isFocusableInTouchMode = false
            etSell.isCursorVisible = false
            etSell.keyListener = null

            fun computeSellPrice() {
                if (etRateVal.text.isNullOrBlank()) { etSell.setText(""); return }
                val rate = etRateVal.text?.toString()?.toDoubleOrNull() ?: 0.0
                // THE TAX THIS ROW CARRIES, not the one Tax Settings has switched on.
                //
                // This used to pick the regime off the settings: with GST on it summed
                // the GST boxes and a typed VAT was ignored, with both off it took no
                // tax at all. So the form disagreed with itself - the operator entered
                // VAT 5 in a box this screen had just let them fill, and Sell Price came
                // back as the bare rate with nothing to say why.
                //
                // A rate is under VAT or under GST and never both (that is the rule the
                // boxes above enforce), so whichever one carries a figure IS the tax,
                // and the form can work its own sell price out without asking the till
                // what it charges.
                val vat = etVat.text?.toString()?.toDoubleOrNull() ?: 0.0
                val gst = (etCgst.text?.toString()?.toDoubleOrNull() ?: 0.0) +
                    (etSgst.text?.toString()?.toDoubleOrNull() ?: 0.0) +
                    (etIgst.text?.toString()?.toDoubleOrNull() ?: 0.0)
                val onVat = vat > 0.0
                val taxPct = if (onVat) vat else gst
                // Inclusive is the one thing still read from Tax Settings, and it has
                // to be: it says whether the rate typed already CONTAINS its tax, and
                // there is no per-product answer to that anywhere. Which of the two
                // flags applies follows the tax the row is under.
                val inclusive = if (onVat) vatInclusive else gstInclusive
                val t = taxPct / 100.0

                val sell = run {
                    // Applied whenever one is typed. It used to need Tax Settings on
                    // ITEM-WISE, so a discount entered on a bill-wise till changed
                    // nothing on screen and looked like it had not registered.
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
                }
                etSell.setText(String.format("%.2f", sell))
            }
            etRateVal.addTextChangedListener { computeSellPrice() }
            etCgst.addTextChangedListener { computeSellPrice() }
            etSgst.addTextChangedListener { computeSellPrice() }
            etIgst.addTextChangedListener { computeSellPrice() }
            etVat.addTextChangedListener { computeSellPrice() }
            // Wired whatever Tax Settings says, like the four tax boxes above it. Behind
            // the `if` these two listeners never ran on a bill-wise till, so typing a
            // discount there left Sell Price sitting at its undiscounted figure.
            run {
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

    /** The current store's active rate-name master, seeding defaults on first use. */
    private fun loadRateNames(): List<Option> {
        val db = DatabaseHelper.getInstance(requireContext()).writableDatabase
        val store = storeId()
        // Seed this store's defaults the first time it has none.
        val count = db.rawQuery(
            "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.MD_RATE_NAME} WHERE store_id = ?",
            arrayOf(store.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (count == 0L) {
            listOf("Rate 1", "Rate 2", "Rate 3", "MRP", "Wholesale").forEach { name ->
                db.execSQL(
                    "INSERT INTO ${DatabaseHelper.Tables.MD_RATE_NAME} (store_id, rate_name, is_active) VALUES (?, ?, 1)",
                    arrayOf<Any>(store, name)
                )
            }
        }
        val options = mutableListOf<Option>()
        db.rawQuery(
            "SELECT id, rate_name FROM ${DatabaseHelper.Tables.MD_RATE_NAME} " +
                "WHERE store_id = ? AND is_active = 1 ORDER BY id ASC",
            arrayOf(store.toString())
        ).use { c ->
            while (c.moveToNext()) options.add(Option(c.getInt(0), c.getString(1).orEmpty()))
        }
        return options
    }

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
        // Default to Percentage when the user hasn't chosen a discount type.
        val effective = selectedCode ?: "P"
        codes.firstOrNull { it.first == effective }?.let {
            view.setText(it.second, false)
            view.tag = it.first
        }
    }

    /** Binds a plain string dropdown; the picked value is stored on the view's tag. */
    private fun bindStrings(view: AutoCompleteTextView, items: List<String>) {
        view.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, items))
        view.setOnItemClickListener { _, _, position, _ -> view.tag = items[position] }
    }

    private fun selectedId(view: AutoCompleteTextView): Int? = view.tag as? Int

    private fun text(root: android.view.View, id: Int): String =
        root.findViewById<TextInputEditText>(id).text.toString().trim()

    // ---- Persistence ---------------------------------------------------------

    private fun storeId(): Int = SessionManager.currentUser?.storeId ?: 0

    /**
     * store_id and outlet_id for a new product. The store is the signed-in user's
     * store (so saved products match the store-scoped list); outlet comes from the
     * matching md_registration row. Falls back to registration if there's no session.
     */
    private fun storeAndOutlet(): Pair<Int?, Int?> {
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        val sessionStore = SessionManager.currentUser?.storeId?.takeIf { it != 0 }
        if (sessionStore != null) {
            val outlet = db.rawQuery(
                "SELECT outlet_id FROM ${DatabaseHelper.Tables.MD_REGISTRATION} WHERE store_id = ? LIMIT 1",
                arrayOf(sessionStore.toString())
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }
            return sessionStore to outlet
        }
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
        val rateNameId: Int? = null,
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
        rateName = row.findViewById<AutoCompleteTextView>(R.id.actRateName).text?.toString()?.trim().orEmpty(),
        rateNameId = row.findViewById<AutoCompleteTextView>(R.id.actRateName).tag as? Int,
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
        /** Quantity the product opens with, captured on Add while stock is tracked. */
        val openingStock: String,
        val foodType: String,
        val spiceLevel: String,
        val prepTime: String,
        val availability: String,
        val rates: List<RateRow>
    )

    private class ExistingProduct(
        val name: String, val hsn: String, val barcode: String, val stockAlert: String,
        val categoryId: Int?, val image: ByteArray?,
        val foodType: String, val spiceLevel: String,
        val prepTime: String, val availability: String,
        val rates: List<RateRow>
    )

    /**
     * The Generate button inside the Barcode field.
     *
     * For the stock that arrives without a code of its own - loose goods, repacks, the
     * shop's own cooking, anything sold by weight. Those still need something the gun
     * can read off a shelf-edge label, and a number typed by hand produces codes that
     * collide and that carry no check digit for a scanner to verify.
     *
     * ASKS BEFORE OVERWRITING. A barcode already in the box was almost certainly
     * scanned off the packet, and replacing it silently would swap a manufacturer's
     * code for one this shop invented - after which the gun stops finding the product
     * it is pointed at. An empty box generates straight away.
     */
    private fun bindGenerateBarcode(
        view: android.view.View,
        etBarcode: TextInputEditText
    ) {
        val til = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilBarcode)
        til.setEndIconOnClickListener {
            val current = etBarcode.text?.toString()?.trim().orEmpty()
            if (current.isEmpty()) {
                generateBarcodeInto(etBarcode)
                return@setEndIconOnClickListener
            }
            com.example.synergic_pos_offline.utils.DialogUtils.showConfirm(
                context = requireContext(),
                title = "Replace this barcode?",
                message = "This product already has the barcode $current. If it was " +
                    "scanned off the packet, replacing it will stop the scanner finding " +
                    "this product.",
                positiveText = "Replace",
                destructive = true
            ) { generateBarcodeInto(etBarcode) }
        }
    }

    /** Builds a code no product in this catalogue is already using, and fills it in. */
    private fun generateBarcodeInto(etBarcode: TextInputEditText) {
        val code = com.example.synergic_pos_offline.utils.BarcodeGenerator
            .nextEan13 { candidate -> barcodeExists(candidate) }
        etBarcode.setText(code)
        etBarcode.setSelection(code.length)
        toast("Barcode $code generated")
    }

    /** Whether any product already carries [code] as its barcode. */
    private fun barcodeExists(code: String): Boolean = runCatching {
        DatabaseHelper.getInstance(requireContext()).readableDatabase.rawQuery(
            "SELECT 1 FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE bar_code = ? LIMIT 1",
            arrayOf(code)
        ).use { it.moveToFirst() }
    }.getOrDefault(false)

    private fun loadProduct(productId: Int): ExistingProduct? {
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase

        // All rate cards for this product (default rate first).
        val rates = mutableListOf<RateRow>()
        db.rawQuery(
            """
            SELECT rate_name, rate, unit_id, cgst_rate, sgst_rate, igst_rate, vat_rate,
                   discount, discount_type, COALESCE(sell_price, sale_price), purchase_price, rate_name_id
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
                        sellPrice = num(c, 9), purchasePrice = num(c, 10),
                        rateNameId = if (c.isNull(11)) null else c.getInt(11)
                    )
                )
            }
        }

        db.rawQuery(
            "SELECT product_name, hsn_code, bar_code, stock_alert_qty, category_id, product_image, " +
                "food_type, spice_level, prep_time, availability " +
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
                foodType = c.getString(6).orEmpty(),
                spiceLevel = c.getString(7).orEmpty(),
                prepTime = c.getString(8).orEmpty(),
                availability = c.getString(9).orEmpty(),
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

    private fun saveProduct(productId: Int?, form: ProductForm, withStock: Boolean = false) {
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
                put("food_type", form.foodType.ifEmpty { null })
                put("spice_level", form.spiceLevel.ifEmpty { null })
                put("prep_time", form.prepTime.ifEmpty { null })
                put("availability", form.availability.ifEmpty { null })
                putDouble(this, "stock_alert_qty", form.stockAlert)
                if (form.categoryId != null) put("category_id", form.categoryId) else putNull("category_id")
                // Only touch the image when the user picked a new one or cleared it.
                val image = pendingImage
                when {
                    image != null -> put("product_image", image)
                    imageCleared -> putNull("product_image")
                }
                // Audit trail: stamp who/when on create, and who/when on each edit.
                val nowTs = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                val user = SessionManager.auditUser
                if (productId == null) {
                    put("created_at", nowTs)
                    put("created_by", user)
                } else {
                    put("modified_at", nowTs)
                    put("modified_by", user)
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
                    if (r.rateNameId != null) put("rate_name_id", r.rateNameId) else putNull("rate_name_id")
                    putDouble(this, "rate", r.rate)
                    if (r.unitId != null) put("unit_id", r.unitId) else putNull("unit_id")
                    putDouble(this, "cgst_rate", r.cgst)
                    putDouble(this, "sgst_rate", r.sgst)
                    putDouble(this, "igst_rate", r.igst)
                    putDouble(this, "vat_rate", r.vat)
                    put("discount", r.discount.toDoubleOrNull() ?: 0.0)
                    if (r.discountType != null) put("discount_type", r.discountType) else putNull("discount_type")
                    // md_product_rates carries the selling price in two columns.
                    // Both are written with the same figure: a rate saved through
                    // this form used to fill only one of them, so anything reading
                    // the other - the CSV export among them - reported no price at
                    // all for a product added by hand.
                    putDouble(this, "sell_price", r.sellPrice)
                    putDouble(this, "sale_price", r.sellPrice)
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

            if (withStock) saveOpeningBatch(db, id, storeId, outletId, form)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Writes the batch a newly added product opens with, through
     * [StockDao.recordOpening] - the same call the bulk upload's `stock` column
     * makes, so a product opened by hand and a product opened by sheet end up
     * counted and filed alike.
     *
     * Runs inside [saveProduct]'s transaction - a product cannot end up saved with
     * its opening stock missing, or a stock row left pointing at no product.
     *
     * Nothing is written when no opening quantity was given: a product added without
     * one is a product with no stock yet, not a batch of zero.
     */
    private fun saveOpeningBatch(
        db: android.database.sqlite.SQLiteDatabase,
        productId: Long,
        storeId: Int?,
        outletId: Int?,
        form: ProductForm
    ) {
        val quantity = form.openingStock.toDoubleOrNull() ?: return
        StockDao(requireContext()).recordOpening(db, productId, quantity, storeId, outletId)
    }

    /** Stores a numeric field, or NULL when the user left it blank. */
    private fun putDouble(values: ContentValues, key: String, raw: String) {
        val parsed = raw.toDoubleOrNull()
        if (parsed == null) values.putNull(key) else values.put(key, parsed)
    }
}
