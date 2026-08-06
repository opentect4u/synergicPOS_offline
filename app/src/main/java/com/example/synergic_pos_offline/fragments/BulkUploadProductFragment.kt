package com.example.synergic_pos_offline.fragments

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.utils.CsvUtils
import com.example.synergic_pos_offline.utils.Downloads
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.ProductBulkImporter
import com.example.synergic_pos_offline.utils.ProductCsvTemplate
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton

/**
 * Dedicated "Bulk Upload Products" page reached from the Products screen.
 *
 * The operator downloads a CSV template to see the structure, fills it in a
 * spreadsheet, and uploads it — every row becomes a product, under the category the
 * row itself names. A preview is shown before anything is written, and it is there
 * that the operator says whether to add to the products already on the till or
 * replace them.
 *
 * There is no category to pick on this page: one upload can carry the whole
 * catalogue, and a sheet holding twenty categories cannot be filed under a single
 * one chosen beforehand.
 */
class BulkUploadProductFragment : Fragment(), TitledScreen {

    override val screenTitle = "Bulk Upload Products"

    private val uploadCsv: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importCsv(it) }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bulk_upload_product, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.btnBulkDownload).setOnClickListener { downloadTemplate() }
        view.findViewById<MaterialButton>(R.id.btnBulkUpload).setOnClickListener {
            uploadCsv.launch("*/*")
        }

        ThemeManager.applyTheme(view)
    }

    // ---- Template download --------------------------------------------------

    /**
     * Saves the template to Downloads, for the operator to fill in and upload back.
     *
     * It is written to the Downloads folder rather than offered to another app
     * through a chooser: the chooser only appears where something is installed that
     * handles `text/csv`, and on a till where nothing is, the button did nothing at
     * all. The sheet itself comes from [ProductCsvTemplate], shared with the
     * download icon on the Products screen.
     */
    private fun downloadTemplate() {
        try {
            val savedTo = Downloads.save(
                requireContext(), ProductCsvTemplate.FILE_NAME,
                ProductCsvTemplate.content(requireContext())
            )
            toast("Template saved to $savedTo")
        } catch (e: Exception) {
            toast("Could not save template: ${e.message}")
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
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_csv_preview, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create()
            .also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        // Column order comes from the CSV header (rows are LinkedHashMaps).
        val columns = rows.first().keys.toList()
        val named = rows.count { (it["product_name"] ?: it["item_name"]).orEmpty().isNotBlank() }
        // Categories and units the sheet names that this till does not have yet are
        // called out before the import, not after: they will be created, and that is
        // worth seeing while Cancel is still on screen - a misspelt "Diary" reads as
        // a new category rather than as the mistake it is.
        val newCategories =
            unknownNames(rows, "category", ProductBulkImporter.knownCategoryNames(ctx))
        // Read through the importer, so the units the preview promises to create are
        // the ones it will actually create - whichever heading the sheet used.
        val knownUnits = ProductBulkImporter.knownUnitNames(ctx)
        val newUnits = rows
            .mapNotNull { ProductBulkImporter.unitNameOf(it) }
            .distinctBy { it.lowercase() }
            .filter { it.lowercase() !in knownUnits }
        // A row that names no category imports uncategorised rather than being
        // filed under a guess - the page no longer asks for one to guess with.
        val uncategorised = rows.count { it["category"].orEmpty().isBlank() }
        // What the sheet's stock column is about to do, said before it does it. Only
        // a till that tracks stock opens a count from it, and on one that does not
        // the figures are quietly dropped - which is worth saying out loud, since an
        // operator who has filled a column in has every reason to expect it to land.
        val stockTracked = GeneralSettingsDao.isStockEnabled(ctx)
        val withStock = rows.count { ProductBulkImporter.openingStockOf(it) != null }
        view.findViewById<TextView>(R.id.tvPreviewSub).text = buildString {
            append("$named of ${rows.size} row(s) • ${columns.size} columns")
            if (uncategorised > 0) {
                append("\n$uncategorised row(s) name no category and will be left uncategorised")
            }
            if (withStock > 0) {
                append(
                    if (stockTracked) "\nOpening stock will be set for $withStock row(s)"
                    else "\nStock tracking is off - the stock column will be ignored"
                )
            }
            if (newCategories.isNotEmpty()) {
                append("\nNew categories to be created: ${newCategories.joinToString(", ")}")
            }
            if (newUnits.isNotEmpty()) {
                append("\nNew units to be created: ${newUnits.joinToString(", ")}")
            }
        }

        val table = view.findViewById<LinearLayout>(R.id.llPreviewTable)
        // Header row.
        table.addView(tableRow(ctx, columns, columns.map { it.replace('_', ' ').uppercase() }, -1))
        table.addView(divider(ctx))
        // Only the first stretch of rows is drawn. Every row still imports - this is
        // a limit on what is put on screen, not on what is uploaded. The table is
        // built one view per cell, so a sheet of a thousand products would be
        // thirteen thousand views laid out before the dialog could appear, and the
        // till would sit frozen through it. Nobody reads the nine-hundredth row of a
        // preview anyway; what it is there for is to show the shape of the file.
        rows.take(PREVIEW_ROWS).forEachIndexed { i, r ->
            table.addView(tableRow(ctx, columns, columns.map { r[it].orEmpty() }, i))
        }
        if (rows.size > PREVIEW_ROWS) {
            table.addView(divider(ctx))
            table.addView(
                TextView(ctx).apply {
                    text = "… and ${rows.size - PREVIEW_ROWS} more row(s), all of which will be imported"
                    textSize = 12f
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    setTextColor(resources.getColor(R.color.text_secondary, null))
                }
            )
        }

        view.findViewById<MaterialButton>(R.id.btnPreviewCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnPreviewSubmit).setOnClickListener {
            val replace = view.findViewById<RadioGroup>(R.id.rgImportMode)
                .checkedRadioButtonId == R.id.rbImportReplace
            if (replace) confirmReplace(ctx) { runImport(ctx, rows, dialog, ProductBulkImporter.Mode.REPLACE) }
            else runImport(ctx, rows, dialog, ProductBulkImporter.Mode.APPEND)
        }

        ThemeManager.applyTheme(view)
        dialog.show()
    }


    /**
     * Asks once more before a Replace, stating what it will actually do to *this*
     * till - how many products go, and how many cannot.
     *
     * Deleting the catalogue is not something the operator can undo from the till,
     * and "Replace" chosen among radio buttons is easier to pick by accident than to
     * recover from, so the count is put in front of them before it happens. A till
     * with nothing on it yet is not worth stopping for.
     */
    private fun confirmReplace(ctx: android.content.Context, onConfirm: () -> Unit) {
        val counts = ProductBulkImporter.replaceCounts(ctx)
        if (counts.total == 0) { onConfirm(); return }

        val kept = if (counts.kept > 0) {
            "\n\n${counts.kept} product(s) already on a bill, return or stock entry will be " +
                "kept - removing them would break those records."
        } else ""
        DialogUtils.showConfirm(
            context = ctx,
            title = "Replace all products?",
            message = "This removes ${counts.removable} product(s) from this till, " +
                "with their rates and stock, and then imports the sheet.$kept",
            positiveText = "Replace",
            negativeText = "Cancel",
            destructive = true,
            onConfirm = onConfirm
        )
    }

    /** Runs the import, closes the preview and reports what happened. */
    private fun runImport(
        ctx: android.content.Context,
        rows: List<Map<String, String>>,
        preview: AlertDialog,
        mode: ProductBulkImporter.Mode
    ) {
        val result = ProductBulkImporter.import(ctx, rows, mode)
        preview.dismiss()
        val summary = buildString {
            append("${result.imported} product(s) uploaded successfully.")
            if (result.removed > 0) append("\n${result.removed} existing product(s) removed.")
            if (result.skipped > 0) append("\n${result.skipped} row(s) skipped.")
        }
        DialogUtils.showSuccess(
            context = ctx,
            title = "Upload Complete",
            message = summary
        )
    }

    // ---- Helpers ------------------------------------------------------------

    /** The distinct values of [column] the sheet names that [known] does not hold. */
    private fun unknownNames(
        rows: List<Map<String, String>>, column: String, known: Set<String>
    ): List<String> = rows
        .mapNotNull { it[column]?.trim()?.takeIf { v -> v.isNotEmpty() } }
        .distinctBy { it.lowercase() }
        .filter { it.lowercase() !in known }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Fixed cell width per column, so the header aligns with every data row. */
    private fun columnWidth(name: String): Int = dp(
        when (name) {
            "item_name", "product_name", "description" -> 150
            // Category names run long - "Dry Fruits & Cereals" - and are the column
            // the operator most wants to read back before importing.
            "category" -> 150
            "rate_name", "hsn_code", "bar_code" -> 110
            "unit_id", "discount_type", "selling_price", "purchase_price" -> 92
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

    private companion object {
        /**
         * How many rows of the sheet the preview draws.
         *
         * A limit on the preview only - every row in the file is imported. The table
         * is built a view per cell, so drawing a whole large sheet would freeze the
         * till before the dialog appeared, and a preview nobody can scroll to the end
         * of is not telling them anything the first fifty rows did not.
         */
        const val PREVIEW_ROWS = 50
    }

}
