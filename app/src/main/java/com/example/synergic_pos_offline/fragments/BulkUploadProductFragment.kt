package com.example.synergic_pos_offline.fragments

import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.CsvUtils
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.io.File

/**
 * Dedicated "Bulk Upload Products" page reached from the Products screen.
 *
 * The operator picks a category, downloads a CSV template to see the structure,
 * fills it in a spreadsheet, and uploads it — every row is added as a new product
 * under the chosen category (a preview is shown before anything is written).
 */
class BulkUploadProductFragment : Fragment(), TitledScreen {

    override val screenTitle = "Bulk Upload Products"

    private data class Category(val id: Int, val name: String)

    private lateinit var actCategory: MaterialAutoCompleteTextView
    private var categories: List<Category> = emptyList()

    /** The bulk-upload columns; category comes from the on-page selector. */
    private val csvHeader = listOf(
        "hsn_code", "item_name", "bar_code", "price", "discount", "cgst", "sgst",
        "sale_price", "sp_gst_flag", "purchase_price", "pp_gst_flag", "description"
    )

    private val uploadCsv: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importCsv(it) }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bulk_upload_product, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        actCategory = view.findViewById(R.id.actBulkCategory)
        categories = loadCategories()
        actCategory.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, categories.map { it.name })
        )
        actCategory.setOnItemClickListener { _, _, pos, _ -> actCategory.tag = categories[pos].id }

     //   view.findViewById<MaterialButton>(R.id.btnBulkDownload).setOnClickListener { downloadTemplate() }
        view.findViewById<MaterialButton>(R.id.btnBulkUpload).setOnClickListener {
            if (selectedCategoryId() == null) toast("Select a category first")
            else uploadCsv.launch("*/*")
        }

        ThemeManager.applyTheme(view)
    }

    private fun selectedCategoryId(): Int? = actCategory.tag as? Int

    // ---- Template download --------------------------------------------------

    private fun downloadTemplate() {
        try {
            val file = File(requireContext().cacheDir, "item_master_template.csv")
            file.bufferedWriter().use { w ->
                w.appendLine(csvHeader.joinToString(","))
                w.appendLine("987640,Apple,11111111,120,0,5,5,120,N,110,N,Fresh apple")
                w.appendLine("897651,Mango,11111112,80,0,5,5,80,N,70,N,")
            }
            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", file
            )
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/csv")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(view, "Open template with"))
            } catch (_: android.content.ActivityNotFoundException) {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Save template to"))
            }
        } catch (e: Exception) {
            toast("Could not create template: ${e.message}")
        }
    }

    // ---- Upload -------------------------------------------------------------

    private fun importCsv(uri: Uri) {
        val rows = try {
            requireContext().contentResolver.openInputStream(uri)?.use { ins ->
                CsvUtils.parse(ins.bufferedReader().readText())
            } ?: emptyList()
        } catch (e: Exception) {
            toast("Could not read file: ${e.message}"); return
        }
        if (rows.isEmpty()) { toast("No rows found in the file"); return }
        showPreview(rows)
    }

    private fun showPreview(rows: List<Map<String, String>>) {
        val ctx = requireContext()
        val categoryName = categories.firstOrNull { it.id == selectedCategoryId() }?.name ?: "-"
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_csv_preview, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create()
            .also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val named = rows.count { (it["item_name"] ?: it["product_name"]).orEmpty().isNotBlank() }
        view.findViewById<TextView>(R.id.tvPreviewSub).text =
            "$named of ${rows.size} row(s) → category \"$categoryName\""

        val container = view.findViewById<LinearLayout>(R.id.llPreviewRows)
        rows.forEachIndexed { i, r ->
            val name = (r["item_name"] ?: r["product_name"]).orEmpty().ifBlank { "(no name)" }
            val price = r["price"]?.toDoubleOrNull() ?: 0.0
            val cgst = r["cgst"]?.toDoubleOrNull() ?: 0.0
            val sgst = r["sgst"]?.toDoubleOrNull() ?: 0.0
            val sale = r["sale_price"]?.toDoubleOrNull() ?: price
            val purchase = r["purchase_price"]?.toDoubleOrNull() ?: 0.0
            val gst = if (cgst == 0.0 && sgst == 0.0) "—" else "${pctLabel(cgst)}+${pctLabel(sgst)}"
            container.addView(previewRow(ctx, i, name, money(price), gst, money(sale), money(purchase)))
        }

        view.findViewById<MaterialButton>(R.id.btnPreviewCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnPreviewSubmit).setOnClickListener {
            val (ok, failed) = insertProducts(rows, selectedCategoryId())
            dialog.dismiss()
            toast("Imported $ok product(s)" + if (failed > 0) ", $failed skipped" else "")
        }

        ThemeManager.applyTheme(view)
        dialog.show()
    }

    /** Inserts each row as a new product (under [categoryId]) + its default rate. */
    private fun insertProducts(rows: List<Map<String, String>>, categoryId: Int?): Pair<Int, Int> {
        val db = DatabaseHelper.getInstance(requireContext()).writableDatabase
        val (storeId, outletId) = storeAndOutlet()
        var ok = 0
        var failed = 0
        db.beginTransaction()
        try {
            for (r in rows) {
                val name = (r["item_name"] ?: r["product_name"]).orEmpty()
                if (name.isBlank()) { failed++; continue }

                val product = ContentValues().apply {
                    if (storeId != null) put("store_id", storeId) else putNull("store_id")
                    put("product_name", name)
                    put("hsn_code", r["hsn_code"]?.ifBlank { null })
                    put("bar_code", r["bar_code"]?.ifBlank { null })
                    if (categoryId != null) put("category_id", categoryId) else putNull("category_id")
                }
                val id = db.insert(DatabaseHelper.Tables.MD_PRODUCTS, null, product)
                if (id == -1L) { failed++; continue }

                val price = r["price"]?.toDoubleOrNull() ?: 0.0
                val sale = r["sale_price"]?.toDoubleOrNull() ?: price
                val rate = ContentValues().apply {
                    if (storeId != null) put("store_id", storeId) else putNull("store_id")
                    if (outletId != null) put("outlet_id", outletId) else putNull("outlet_id")
                    put("product_id", id)
                    put("rate", price)
                    put("cgst_rate", r["cgst"]?.toDoubleOrNull() ?: 0.0)
                    put("sgst_rate", r["sgst"]?.toDoubleOrNull() ?: 0.0)
                    put("igst_rate", 0.0)
                    put("vat_rate", 0.0)
                    put("discount", r["discount"]?.toDoubleOrNull() ?: 0.0)
                    put("sale_price", sale)
                    put("sell_price", sale)
                    put("purchase_price", r["purchase_price"]?.toDoubleOrNull() ?: 0.0)
                }
                val rid = db.insert(DatabaseHelper.Tables.MD_PRODUCT_RATES, null, rate)
                if (rid != -1L) {
                    db.execSQL(
                        "UPDATE ${DatabaseHelper.Tables.MD_PRODUCT_RATES} SET \"default\" = 1 WHERE id = ?",
                        arrayOf<Any>(rid)
                    )
                }
                ok++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ok to failed
    }

    // ---- Helpers ------------------------------------------------------------

    private fun loadCategories(): List<Category> {
        val list = mutableListOf<Category>()
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        db.query(
            DatabaseHelper.Tables.MD_CATEGORY, arrayOf("id", "category_name"),
            null, null, null, null, "category_name COLLATE NOCASE"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(Category(c.getInt(0), c.getString(1).orEmpty()))
            }
        }
        return list
    }

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

    private fun pctLabel(rate: Double): String =
        if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString()

    private fun money(v: Double): String =
        "₹" + if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v)

    /** One table row for the preview; column weights match the header in XML. */
    private fun previewRow(
        ctx: android.content.Context, index: Int,
        name: String, rate: String, gst: String, sale: String, purchase: String
    ): View {
        val d = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((10 * d).toInt(), (9 * d).toInt(), (10 * d).toInt(), (9 * d).toInt())
            if (index % 2 == 1) setBackgroundColor(Color.parseColor("#FAFAFA"))
        }
        row.addView(cell(ctx, "${index + 1}. $name", 2.4f, android.view.Gravity.START, strong = true))
        row.addView(cell(ctx, rate, 1f, android.view.Gravity.END))
        row.addView(cell(ctx, gst, 1.1f, android.view.Gravity.END))
        row.addView(cell(ctx, sale, 1.1f, android.view.Gravity.END))
        row.addView(cell(ctx, purchase, 1.3f, android.view.Gravity.END))
        return row
    }

    private fun cell(
        ctx: android.content.Context, text: String, weight: Float, gravity: Int, strong: Boolean = false
    ): TextView = TextView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        this.text = text
        textSize = 12.5f
        this.gravity = gravity
        setTextColor(resources.getColor(R.color.text_main, null))
        if (strong) {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, (6 * resources.displayMetrics.density).toInt(), 0)
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
}
