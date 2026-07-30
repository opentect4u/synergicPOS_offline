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

    /** Upload columns matching the Add-Product popup; category comes from the page. */
    private val csvHeader = listOf(
        "product_name", "hsn_code", "bar_code",
        "rate_name", "rate", "unit_id", "cgst", "sgst",
        "discount", "discount_type", "sell_price", "purchase_price"
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
                w.appendLine("Apple,987640,11111111,Retail,120,1,5,5,0,P,120,110")
                w.appendLine("Mango,897651,11111112,Retail,80,2,5,5,0,P,80,70")
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

        // Column order comes from the CSV header (rows are LinkedHashMaps).
        val columns = rows.first().keys.toList()
        val named = rows.count { (it["product_name"] ?: it["item_name"]).orEmpty().isNotBlank() }
        view.findViewById<TextView>(R.id.tvPreviewSub).text =
            "$named of ${rows.size} row(s) • ${columns.size} columns → category \"$categoryName\""

        val table = view.findViewById<LinearLayout>(R.id.llPreviewTable)
        // Header row.
        table.addView(tableRow(ctx, columns, columns.map { it.replace('_', ' ').uppercase() }, -1))
        table.addView(divider(ctx))
        // Data rows.
        rows.forEachIndexed { i, r ->
            table.addView(tableRow(ctx, columns, columns.map { r[it].orEmpty() }, i))
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
                val name = (r["product_name"] ?: r["item_name"]).orEmpty()
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

                val rateVal = (r["rate"] ?: r["price"])?.toDoubleOrNull() ?: 0.0
                val sell = r["sell_price"]?.toDoubleOrNull() ?: r["sale_price"]?.toDoubleOrNull() ?: rateVal
                val rate = ContentValues().apply {
                    if (storeId != null) put("store_id", storeId) else putNull("store_id")
                    if (outletId != null) put("outlet_id", outletId) else putNull("outlet_id")
                    put("product_id", id)
                    put("rate_name", r["rate_name"]?.ifBlank { null })
                    put("rate", rateVal)
                    r["unit_id"]?.toIntOrNull()?.let { put("unit_id", it) } ?: putNull("unit_id")
                    put("cgst_rate", r["cgst"]?.toDoubleOrNull() ?: 0.0)
                    put("sgst_rate", r["sgst"]?.toDoubleOrNull() ?: 0.0)
                    put("igst_rate", 0.0)
                    put("vat_rate", 0.0)
                    put("discount", r["discount"]?.toDoubleOrNull() ?: 0.0)
                    discountType(r["discount_type"])?.let { put("discount_type", it) } ?: putNull("discount_type")
                    put("sale_price", sell)
                    put("sell_price", sell)
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

    /** Normalises a discount type to the stored 'P'/'A' (null when unrecognised). */
    private fun discountType(v: String?): String? = when (v?.trim()?.uppercase()?.firstOrNull()) {
        'P' -> "P"
        'A' -> "A"
        else -> null
    }

    /** store_id (signed-in user's store) + outlet_id, so uploads land in the list. */
    private fun storeAndOutlet(): Pair<Int?, Int?> {
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        val sessionStore =
            com.example.synergic_pos_offline.utils.SessionManager.currentUser?.storeId?.takeIf { it != 0 }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Fixed cell width per column, so the header aligns with every data row. */
    private fun columnWidth(name: String): Int = dp(
        when (name) {
            "item_name", "product_name", "description" -> 150
            "rate_name", "hsn_code", "bar_code" -> 110
            "unit_id", "discount_type" -> 92
            else -> 80
        }
    )

    /** Builds one horizontal row (header when [index] < 0, else a zebra data row). */
    private fun tableRow(
        ctx: android.content.Context, columns: List<String>, values: List<String>, index: Int
    ): View {
        val header = index < 0
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(9), dp(6), dp(9))
            setBackgroundColor(
                when {
                    header -> Color.parseColor("#ECEFF1")
                    index % 2 == 1 -> Color.parseColor("#FFFFFF")
                    else -> Color.parseColor("#F7F8FA")
                }
            )
        }
        columns.forEachIndexed { i, colName ->
            row.addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(columnWidth(colName), LinearLayout.LayoutParams.WRAP_CONTENT)
                text = values.getOrNull(i).orEmpty().ifBlank { if (header) "" else "—" }
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(8), 0)
                if (header) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(resources.getColor(R.color.text_secondary, null))
                } else {
                    setTextColor(resources.getColor(R.color.text_main, null))
                }
            })
        }
        return row
    }

    private fun divider(ctx: android.content.Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(Color.parseColor("#D8DCE0"))
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
}
