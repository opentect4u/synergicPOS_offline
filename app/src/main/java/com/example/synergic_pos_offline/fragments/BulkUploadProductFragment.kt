package com.example.synergic_pos_offline.fragments

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import com.example.synergic_pos_offline.utils.Xlsx
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton

/**
 * Dedicated "Bulk Upload Products" page reached from the Products screen.
 *
 * The operator downloads an Excel template to see the structure, fills it in a
 * spreadsheet, and uploads it — every row becomes a product, under the department
 * its CATEGORY_ID names. A preview is shown before anything is written.
 *
 * ## The sheet IS the product list
 *
 * An upload REPLACES the catalogue. The till ends up holding what the file holds:
 * upload a sheet of four items over a till of three and the till has those four.
 *
 * There used to be an Append/Replace choice here, defaulting to Append. It made every
 * upload a decision, and the wrong answer is not one the operator can undo from the
 * till - Append quietly doubled a catalogue that was meant to be corrected and
 * re-uploaded. One outcome, stated on the preview and confirmed once, is the honest
 * reading of what "upload my product list" means.
 *
 * A product that has been SOLD is still kept, because deleting it would take the
 * bills it appears on with it - see [ProductBulkImporter.replaceCounts], which is
 * what the confirmation counts.
 *
 * There is no category to pick on this page: one upload can carry the whole
 * catalogue, and a sheet holding twenty categories cannot be filed under a single
 * one chosen beforehand.
 */
class BulkUploadProductFragment : Fragment(), TitledScreen {

    override val screenTitle = "Bulk Upload Products"

    /**
     * The file picker the Upload button opens.
     *
     * It accepts EVERY type rather than filtering on the workbook's own, and that is
     * deliberate. An `.xlsx` sitting in Downloads is reported as
     * `application/octet-stream` by a good many Android file providers - the type is
     * guessed from whatever the provider knows, and a file that arrived over WhatsApp
     * or a USB cable often carries nothing to guess from. Filtering on the correct
     * type would grey out the very file the operator came here to upload, with no
     * explanation on screen.
     *
     * So everything is offered and the FILE decides what it is once opened - see
     * [importSheet], which reads the first bytes rather than trusting a name or a type.
     */
    private val uploadSheet: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importSheet(it) }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bulk_upload_product, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.btnBulkDownload).setOnClickListener { downloadTemplate() }
        view.findViewById<MaterialButton>(R.id.btnBulkUpload).setOnClickListener {
            uploadSheet.launch("*/*")
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
            // AN EXCEL WORKBOOK, not a CSV.
            //
            // The sheet is filled in on a computer in Excel or WPS, and a CSV opened
            // there is a file of guesses: the app decides which delimiter was meant,
            // whether "40120" is a number to be shown as 4.012E+04, and what to do
            // with a name holding a comma. A workbook has cells, so a code stays a
            // code and a name stays a name, and the file that comes back is the file
            // that went out.
            //
            // Written from the same rows as the CSV - see ProductCsvTemplate.rows -
            // so the two describe the same sheet. CSV is still accepted on the way
            // back in; see [importSheet].
            val savedTo = Downloads.save(
                requireContext(),
                ProductCsvTemplate.EXCEL_FILE_NAME,
                Xlsx.write(ProductCsvTemplate.rows(requireContext()), "Item Master"),
                Xlsx.MIME
            )
            toast("Template saved to $savedTo")
        } catch (e: Exception) {
            toast("Could not save template: ${e.message}")
        }
    }

    // ---- Upload -------------------------------------------------------------

    /**
     * Reads the uploaded sheet - a workbook or a CSV, whichever it turns out to be.
     *
     * ## The FILE says which it is, not its name
     *
     * A ZIP starts with the two bytes `PK`, and nothing that is CSV does. So the
     * first bytes are read and the answer taken from them, rather than from an
     * extension that may be `.XLSX`, may have been dropped by a phone, or may not
     * exist at all - the picker hands over a `content://` URI, which need not carry a
     * file name.
     *
     * CSV is still read, and deliberately. The template downloads as a workbook now,
     * but shops have sheets they have been keeping for months, and a system that
     * exports their catalogue writes CSV. Refusing those would be making the operator
     * convert a file the app is perfectly able to read.
     */
    private fun importSheet(uri: Uri) {
        // Which reader ran, so the message on an empty result can say something the
        // operator can act on rather than "no rows" for every kind of wrong file.
        var workbook = false
        val rows = try {
            requireContext().contentResolver.openInputStream(uri)?.use { ins ->
                // Buffered so the sniffed bytes can be put back for whichever reader
                // takes over - a content stream cannot be reopened from the start.
                val stream = ins.buffered()
                val head = ByteArray(2)
                stream.mark(4)
                val read = stream.read(head)
                stream.reset()
                workbook = read == 2 && Xlsx.looksLikeXlsx(head)
                if (workbook) fromWorkbook(stream)
                else CsvUtils.parse(stream.bufferedReader().readText())
            } ?: emptyList()
        } catch (e: Exception) {
            toast("Could not read file: ${e.message}"); return
        }
        if (rows.isEmpty()) {
            toast(
                if (workbook) "That workbook has no rows under its headings"
                else "No rows found - is that the sheet you filled in?"
            )
            return
        }
        showPreview(rows)
    }

    /**
     * A workbook's rows, keyed by its heading row - the shape [CsvUtils.parse]
     * returns, so everything downstream reads one sheet the same way whichever file
     * it arrived in.
     *
     * Headings are lower-cased and trimmed exactly as the CSV reader does, because
     * the importer looks its columns up by name and a heading Excel handed back as
     * "Product_Name " must still be `product_name`.
     */
    private fun fromWorkbook(input: java.io.InputStream): List<Map<String, String>> {
        val rows = Xlsx.read(input)
        if (rows.isEmpty()) return emptyList()
        val headings = rows.first().map { it.trim().lowercase() }
        return rows.drop(1)
            // A workbook keeps trailing blank rows that were only ever formatted or
            // scrolled through; they are not products and must not be counted as
            // skipped rows in the report at the end.
            .filter { cells -> cells.any { it.isNotBlank() } }
            .map { cells ->
                headings.mapIndexed { i, key -> key to cells.getOrNull(i)?.trim().orEmpty() }.toMap()
            }
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
        // Read through the importer, under every heading it accepts, so the preview
        // names the categories the upload will ACTUALLY create. It read only the old
        // `category` heading, so once the sheet's column became CATEGORY_NAME the
        // preview promised nothing and the departments appeared without warning.
        val knownCategories = ProductBulkImporter.knownCategoryNames(ctx)
        val newCategories = rows
            .mapNotNull { ProductBulkImporter.categoryNameOf(it) }
            .distinctBy { it.lowercase() }
            .filter { it.lowercase() !in knownCategories }
        // Read through the importer, so the units the preview promises to create are
        // the ones it will actually create - whichever heading the sheet used.
        val knownUnits = ProductBulkImporter.knownUnitNames(ctx)
        val newUnits = rows
            .mapNotNull { ProductBulkImporter.unitNameOf(it) }
            .distinctBy { it.lowercase() }
            .filter { it.lowercase() !in knownUnits }
        // A row that names no category imports uncategorised rather than being
        // filed under a guess - the page no longer asks for one to guess with.
        val uncategorised = rows.count { ProductBulkImporter.categoryNameOf(it) == null }
        // What the sheet's stock column is about to do, said before it does it. Only
        // a till that tracks stock opens a count from it, and on one that does not
        // the figures are quietly dropped - which is worth saying out loud, since an
        // operator who has filled a column in has every reason to expect it to land.
        val stockTracked = GeneralSettingsDao.isStockEnabled(ctx)
        val withStock = rows.count { ProductBulkImporter.openingStockOf(it) != null }
        // Read the same way the import itself will read it, so the preview never
        // promises a language the upload would not actually apply.
        val languageMatch = ProductBulkImporter.regionalLanguageOf(rows)
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
            append(
                when {
                    languageMatch.conflicting ->
                        "\nThe regional language column names more than one language - " +
                            "\"${languageMatch.raw}\" (the first) will be applied. Fill every row " +
                            "with the same language, or leave the rest blank."
                    languageMatch.raw.isEmpty() ->
                        "\nNo regional language specified - app language will be set to English"
                    languageMatch.exact ->
                        "\nApp language will be set to ${languageMatch.language.englishName}"
                    else ->
                        "\n\"${languageMatch.raw}\" is not a recognised language - " +
                            "${languageMatch.language.englishName} will be applied instead"
                }
            )
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
        // ALWAYS A REPLACE. The sheet IS the product list after an upload - the till
        // holds what the file holds and nothing else. See [ProductBulkImporter.Mode].
        view.findViewById<MaterialButton>(R.id.btnPreviewSubmit).setOnClickListener {
            confirmReplace(ctx) { runImport(ctx, rows, dialog) }
        }

        ThemeManager.applyTheme(view)
        dialog.show()
    }


    /**
     * Asks once before the upload, stating what it will actually do to *this* till -
     * which products go, and which cannot.
     *
     * This is now the ONLY thing standing between the button and the catalogue, since
     * the mode is no longer a choice - so it names real products off this till rather
     * than describing replacement in general. Deleting the catalogue is not something
     * the operator can undo from here.
     *
     * A till with nothing on it yet is not worth stopping for: there is nothing to
     * lose, and a confirmation that always says "0 products" trains people to tap
     * through the one that matters.
     */
    private fun confirmReplace(ctx: android.content.Context, onConfirm: () -> Unit) {
        val counts = ProductBulkImporter.replaceCounts(ctx)
        if (counts.total == 0) { onConfirm(); return }

        DialogUtils.showConfirm(
            context = ctx,
            title = "These products will be overridden",
            message = overrideReport(counts),
            positiveText = "Confirm & Upload",
            negativeText = "Cancel",
            destructive = true,
            // A column of product names, not a sentence - see [DialogUtils.showConfirm].
            messageStart = true,
            onConfirm = onConfirm
        )
    }

    /**
     * The override report: which products this upload takes off the till, and which
     * it cannot.
     *
     * BY NAME, not by count. "This removes 9 product(s)" is a number an operator can
     * only agree to on trust - they cannot tell from it whether the sheet they are
     * about to upload is the right one, and the mistake is only visible afterwards,
     * when the catalogue is already gone. Reading "Coffee, Tea, Sugar" is what makes
     * it possible to notice that the wrong file was picked while Cancel is still on
     * screen.
     *
     * The KEPT list is the other half and matters just as much: those products stay
     * whatever the sheet says, because they are on a bill or a stock record and
     * deleting them would take those records with them. An operator who expects the
     * sheet to be the whole catalogue needs to see the ones that will still be there
     * beside it - otherwise the upload "did not work" and nothing said why.
     *
     * Both lists are trimmed to [ProductBulkImporter.NAMES_LISTED] with the remainder
     * counted, so a long catalogue still produces a message that can be read.
     */
    private fun overrideReport(counts: ProductBulkImporter.ReplaceCounts): String {
        fun listed(names: List<String>, total: Int): String {
            val shown = names.take(ProductBulkImporter.NAMES_LISTED)
            val more = total - shown.size
            return shown.joinToString("\n") { "  • $it" } +
                if (more > 0) "\n  • …and $more more" else ""
        }

        return buildString {
            append("The sheet you are uploading becomes the whole product list.")
            if (counts.removable > 0) {
                append("\n\nRemoved from this till (${counts.removable}), with their rates and stock:\n")
                append(listed(counts.removableNames, counts.removable))
            }
            // What a hard replace costs, said before it is agreed to rather than
            // discovered in a report weeks later. The bills themselves are NOT touched
            // and still name their items - only the link from a sold line back to a
            // product record goes, and it cannot be put back.
            append(
                "\n\nBills, returns and stock records are kept and still show their item " +
                    "names, but they will no longer be linked to a product - so reports " +
                    "that group past sales by product cannot do so for these."
            )
            append("\n\nThis cannot be undone.")
        }
    }

    /** Runs the import, closes the preview and reports what happened. */
    private fun runImport(
        ctx: android.content.Context,
        rows: List<Map<String, String>>,
        preview: AlertDialog
    ) {
        val result = ProductBulkImporter.import(ctx, rows, ProductBulkImporter.Mode.REPLACE)
        preview.dismiss()
        val summary = buildString {
            append("${result.imported} product(s) uploaded successfully.")
            if (result.removed > 0) append("\n${result.removed} existing product(s) removed.")
            if (result.skipped > 0) append("\n${result.skipped} row(s) skipped.")
            append("\nApp language set to ${result.languageApplied}.")
            result.languageWarning?.let { append("\n$it") }
            result.referenceWarning?.let { append("\n$it") }
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
