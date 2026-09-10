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
import com.example.synergic_pos_offline.utils.ProductImageImporter
import com.example.synergic_pos_offline.utils.Xlsx
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton

/**
 * Dedicated "Bulk Upload Products" page reached from the Products screen.
 *
 * The operator downloads a sheet - either the blank template, or the Products
 * screen's own export of the current catalogue, with every product's own id on
 * it - fills it in a spreadsheet, and uploads it. A preview is shown before
 * anything is written.
 *
 * ## The upload MERGES, it does not wipe
 *
 * A row whose [ProductCsvTemplate.PRODUCT_ID_COLUMN] names a product this till
 * already has - the shape a sheet takes once it was exported, edited and brought
 * back - UPDATES that product in place: its fields and its rates become the
 * sheet's. A row naming no id, or one this till has never used, is simply added.
 * A product the sheet never mentions at all is left exactly as it is - nothing is
 * deleted just for being absent from the file. See
 * [ProductBulkImporter.Mode.APPEND].
 *
 * ## An upload ERASES THE BOOKS
 *
 * The catalogue merges; the bills do not survive at all. Every bill on the till
 * goes - active and cancelled alike, with its items, payments, prints, kitchen
 * orders, returns and ledger entries - and the floor is cleared with them, exactly
 * as Erase Bills does it. A backup is taken first, and if it cannot be written
 * nothing is uploaded.
 *
 * It used to take only the bills naming an id the sheet replaced. That sounds
 * narrower and safer and is neither: a sheet moves prices, tax rates, units and
 * categories on products it does not replace, and every report reads the old bills
 * THROUGH the catalogue as it stands now - so what survived was a set of books that
 * only looked intact, reporting under figures that were never what was sold.
 *
 * What is not touched: what customers owe (a debt is held on the customer, not
 * summed from the ledger), the stock movements, and the shop's Tax Settings - which
 * Erase Bills resets and an upload has no business deciding. The count is named in
 * the confirmation before the upload runs, and again in the summary after.
 *
 * This used to be a hard Replace: the whole catalogue cleared first and put back
 * from the sheet, wiping the link between every existing product and its bills
 * whether or not that product was still in the file. Matching by id and updating
 * in place is what makes a corrected re-upload safe without that.
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

    /**
     * The folder picker behind Upload Image.
     *
     * A FOLDER PICKER, not a path typed into the box, and not by preference. This app
     * holds no storage permission at all, and since Android 10 an app cannot open
     * `/sdcard/ProductImages` without one - the folder simply reads as empty. The
     * picker grants access to the one folder the operator chooses, needs no
     * permission, and the grant is taken persistably below so the folder only has to
     * be named once: after that the button reuses it and the path stays in the box.
     */
    private val pickImageFolder: ActivityResultLauncher<Uri?> =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            // Held on to, so this survives the screen closing and the app restarting -
            // without it the grant dies with the Activity and the folder would have to
            // be picked again every single time.
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            rememberFolder(uri)
            showFolder(uri.toString())
            previewImages(ProductImageImporter.Source.Tree(uri))
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

        imagePath = view.findViewById(R.id.etImagePath)
        // The folder named last time, so an operator who keeps their pictures in one
        // place sees it already filled in and only has to press the button.
        storedFolder()?.let { showFolder(it) }
        view.findViewById<MaterialButton>(R.id.btnBulkUploadImage).setOnClickListener {
            onUploadImages()
        }

        ThemeManager.applyTheme(view)
    }

    // ---- Product images -----------------------------------------------------

    /** The box showing which folder the pictures are being read from. */
    private lateinit var imagePath: com.google.android.material.textfield.TextInputEditText

    /**
     * Reads the folder in the box, or asks for one.
     *
     * Three ways this goes, in the order that costs the operator least:
     *
     * 1. The box holds a folder picked before and still granted - read it, no picker.
     * 2. The box holds a plain path - TRIED, because it costs nothing and it works
     *    for the folders this app is allowed to open. Most typed paths are not, and
     *    the failure falls through to the picker rather than being reported as an
     *    error the operator can do nothing about.
     * 3. Nothing usable - open the picker.
     */
    private fun onUploadImages() {
        val typed = imagePath.text?.toString()?.trim().orEmpty()
        // What is SHOWN is a readable path; the URI it stands for rides on the tag.
        // They stop matching the moment the operator edits the box, which is exactly
        // when the text should be believed over the folder that used to be there.
        val picked = (imagePath.tag as? String)?.takeIf { readablePath(it) == typed }

        if (typed.isEmpty()) {
            pickImageFolder.launch(null)
            return
        }
        if (picked != null) {
            val uri = Uri.parse(picked)
            // The grant can have been revoked since - the folder was deleted, or the
            // app's data cleared - in which case this is no longer a folder we hold,
            // and asking again is the only way back.
            if (!stillGranted(uri)) {
                toast("That folder is no longer shared with the app - pick it again")
                pickImageFolder.launch(null)
                return
            }
            previewImages(ProductImageImporter.Source.Tree(uri))
            return
        }
        // A plain path. Almost never readable on a modern Android, but it is one
        // cheap attempt and it saves the picker where it does work.
        val dir = java.io.File(typed)
        if (dir.isDirectory && dir.listFiles() != null) {
            previewImages(ProductImageImporter.Source.Dir(typed))
        } else {
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Pick the folder instead",
                message = "Android does not let this app open \"$typed\" from a typed path - " +
                    "since Android 10 an app may only read a folder somebody has handed it.\n\n" +
                    "Choose the folder on the next screen and it will be remembered, so from " +
                    "then on Upload Image reads it without asking."
            ) { pickImageFolder.launch(null) }
        }
    }

    /** Whether this app still holds the read grant on [uri]. */
    private fun stillGranted(uri: Uri): Boolean =
        requireContext().contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }

    /**
     * Counts what the folder holds and asks before writing any of it.
     *
     * The same manners the sheet upload has: this REPLACES pictures already on
     * products, and a folder whose files are named anything but product ids should be
     * caught while Cancel is still on screen rather than reported afterwards.
     */
    private fun previewImages(source: ProductImageImporter.Source) {
        val ctx = requireContext()
        val preview = ProductImageImporter.preview(ctx, source)

        preview.error?.let {
            DialogUtils.showSuccess(ctx, "Could not read that folder", it)
            return
        }
        if (!preview.hasWork) {
            DialogUtils.showSuccess(
                context = ctx,
                title = "Nothing to upload",
                message = buildString {
                    append("${preview.images} image(s) found, and none is named after a ")
                    append("product on this till.\n\n")
                    append("Name each file after the product's id - 41.jpg for product 41. ")
                    append("The ids are the PRODUCT_ID column of the sheet the Download ")
                    append("Template button gives you.")
                    if (preview.unmatchedNames.isNotEmpty()) {
                        append("\n\nFound instead:\n")
                        append(namesList(preview.unmatchedNames))
                    }
                }
            )
            return
        }

        DialogUtils.showConfirm(
            context = ctx,
            title = "Put these pictures on their products?",
            message = buildString {
                append("${preview.matched} image(s) are named after a product on this till ")
                append("and will be set as its picture.")
                if (preview.replacing > 0) {
                    append("\n\n${preview.replacing} of those products already have a picture. ")
                    append("It will be REPLACED.")
                }
                if (preview.unmatched > 0) {
                    append("\n\n${preview.unmatched} image(s) name no product here and will be ")
                    append("left alone:\n")
                    append(namesList(preview.unmatchedNames))
                }
                if (preview.skipped > 0) {
                    append("\n\n${preview.skipped} file(s) in the folder are not images and ")
                    append("are ignored.")
                }
                append("\n\nOnly the picture changes - no price, stock or name is touched.")
            },
            positiveText = "Confirm & Upload",
            negativeText = "Cancel",
            // Red only where something already there is being written over.
            destructive = preview.replacing > 0,
            messageStart = true
        ) { runImageImport(source) }
    }

    /**
     * Does the work, off the main thread, and reports it.
     *
     * Every image in the folder is decoded and re-encoded, which on a folder of a few
     * hundred photographs is long enough that the till would be reported as frozen if
     * it ran where the screen is drawn.
     */
    private fun runImageImport(source: ProductImageImporter.Source) {
        val ctx = requireContext().applicationContext
        com.example.synergic_pos_offline.utils.BusyDialog.run(this, "Uploading images…") {
            val result = ProductImageImporter.import(ctx, source)
            com.example.synergic_pos_offline.utils.BusyDialog.onMain(this) {
                if (!isAdded) return@onMain
                result.error?.let {
                    DialogUtils.showSuccess(requireContext(), "Could not read that folder", it)
                    return@onMain
                }
                DialogUtils.showSuccess(
                    context = requireContext(),
                    title = "Images Uploaded",
                    message = buildString {
                        append("${result.updated} product(s) now carry their picture.")
                        if (result.failed > 0) {
                            append("\n${result.failed} image(s) could not be read and were skipped.")
                        }
                        if (result.unmatched > 0) {
                            append("\n${result.unmatched} image(s) named no product here.")
                        }
                        if (result.skipped > 0) {
                            append("\n${result.skipped} file(s) were not images.")
                        }
                    }
                )
            }
        }
    }

    /** The first few names, with the rest counted - a folder can hold hundreds. */
    private fun namesList(names: List<String>): String {
        val shown = names.take(ProductImageImporter.NAMES_LISTED)
        val more = names.size - shown.size
        return shown.joinToString("\n") { "  • $it" } +
            if (more > 0) "\n  • …and $more more" else ""
    }

    /** Puts the folder in the box, as something an operator can read. */
    private fun showFolder(value: String) {
        imagePath.setText(readablePath(value))
        imagePath.tag = value
    }

    /**
     * A picked folder's URI as a path a person recognises.
     *
     * The system hands back `content://com.android.externalstorage.documents/tree/primary%3AProductImages`,
     * which tells the operator nothing about which folder they chose. The part after
     * the volume is the path they actually navigated to, so that is what is shown -
     * the URI itself is kept on the view's tag, since it is what has to be reopened.
     */
    private fun readablePath(value: String): String {
        if (!value.startsWith("content://")) return value
        val decoded = Uri.decode(value.substringAfterLast("/tree/"))
        return when {
            decoded.startsWith("primary:") -> "Internal storage/" + decoded.removePrefix("primary:")
            decoded.contains(':') -> decoded.substringAfter(':')
            else -> decoded
        }
    }

    /** The folder last used, or null where none has been picked on this till. */
    private fun storedFolder(): String? =
        com.example.synergic_pos_offline.database.AppSettingsDao(requireContext())
            .get(KEY_IMAGE_FOLDER)?.takeIf { it.isNotBlank() }

    private fun rememberFolder(uri: Uri) =
        com.example.synergic_pos_offline.database.AppSettingsDao(requireContext())
            .put(KEY_IMAGE_FOLDER, uri.toString())

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
        // ALWAYS AN APPEND - see [ProductBulkImporter.Mode.APPEND]: a common id is
        // updated in place, everything else is added, nothing on the till is
        // removed just for being absent from the sheet.
        view.findViewById<MaterialButton>(R.id.btnPreviewSubmit).setOnClickListener {
            confirmMerge(ctx, rows) { runImport(ctx, rows, dialog) }
        }

        ThemeManager.applyTheme(view)
        dialog.show()
    }


    /**
     * Asks once before the upload, stating what it will actually do to *this*
     * till - which products it updates, and which it adds.
     *
     * No catalogue is lost - nothing is removed for being absent from the sheet -
     * but two things here can still surprise an operator: a product's fields and
     * rates being overwritten by the row that shares its id, and the BILLS that
     * product was rung up against going with it. Both are named before it runs, and
     * the dialog turns destructive once the second one applies.
     *
     * Skipped when nothing would be updated: a sheet of entirely new products
     * has nothing on this till to overwrite, and a confirmation that never says
     * anything but "N new product(s)" trains people to tap through the one that
     * matters.
     */
    private fun confirmMerge(
        ctx: android.content.Context, rows: List<Map<String, String>>, onConfirm: () -> Unit
    ) {
        val counts = ProductBulkImporter.mergeCounts(ctx, rows)
        // ASKED WHENEVER THERE ARE BILLS, not only when a product is being replaced.
        //
        // This skipped straight through on a sheet of entirely new products, on the
        // reasoning that such a sheet has nothing on this till to overwrite. That
        // stopped being true when the upload began erasing the books: a sheet adding
        // ten products would have taken a year of sales with it without ever putting
        // a dialog on screen.
        if (counts.toUpdate == 0 && counts.billsToDelete == 0) { onConfirm(); return }

        DialogUtils.showConfirm(
            context = ctx,
            title = if (counts.billsToDelete > 0) "This upload will erase every bill"
                    else "These products will be updated",
            message = mergeReport(counts),
            positiveText = "Confirm & Upload",
            negativeText = "Cancel",
            // Red once bills are going with it - the dialog is no longer just
            // reporting an overwrite, it is asking to delete sales.
            destructive = counts.billsToDelete > 0,
            // A column of product names, not a sentence - see [DialogUtils.showConfirm].
            messageStart = true,
            onConfirm = onConfirm
        )
    }

    /**
     * The merge report: which products this upload updates, and how many it adds.
     *
     * BY NAME, not by count - see the same reasoning the old Replace confirmation
     * carried: a number is agreed to on trust, a name is checked against what the
     * operator meant to upload. Trimmed to [ProductBulkImporter.NAMES_LISTED] with
     * the remainder counted, so a long sheet still produces a message that can be
     * read.
     */
    private fun mergeReport(counts: ProductBulkImporter.MergeCounts): String {
        val shown = counts.toUpdateNames.take(ProductBulkImporter.NAMES_LISTED)
        val more = counts.toUpdate - shown.size
        return buildString {
            append(
                "${counts.toUpdate} product(s) already on this till will be updated with " +
                    "the sheet's values - their fields and rates, replaced:\n"
            )
            append(shown.joinToString("\n") { "  • $it" })
            if (more > 0) append("\n  • …and $more more")
            if (counts.toAdd > 0) {
                append("\n\n${counts.toAdd} new product(s) will be added.")
            }
            // THE PART THAT CANNOT BE UNDONE, stated before it happens.
            //
            // Replacing a product replaces what its old bills were FOR, so those
            // bills go - see ProductBulkImporter.deleteBillsFor. An operator agreeing
            // to "update 12 products" is not agreeing to lose a day's sales unless
            // this says so, in the number it will actually cost them.
            if (counts.billsToDelete > 0) {
                // THE WHOLE BOOKS, not merely the bills naming a replaced product.
                // An operator agreeing to "update 12 products" is not agreeing to
                // lose a year of sales unless this says so, in the number it will
                // actually cost them.
                if (counts.toUpdate > 0) append("\n")
                append("\nEVERY bill on this till will be DELETED - all ")
                append("${counts.billsToDelete} of them, active and cancelled alike, with ")
                append("their items, payments, returns, kitchen orders and ledger entries. ")
                append("An upload redraws the catalogue the books were written against, so ")
                append("bills left behind would report under prices and tax rates that were ")
                append("not what was sold.")
                append("\n\nA backup is taken first, into Downloads/backup.")
                append("\n\nWhat customers owe is NOT written off - a debt is held on the ")
                append("customer, not worked out from the ledger, so it survives its history.")
                append("\n\nThe stock they moved is left as it is: the goods did leave ")
                append("the shelf, whatever the catalogue says now.")
                append("\n\nThis cannot be undone.")
            }
        }
    }

    /** Runs the import, closes the preview and reports what happened. */
    private fun runImport(
        ctx: android.content.Context,
        rows: List<Map<String, String>>,
        preview: AlertDialog
    ) {
        preview.dismiss()
        val app = ctx.applicationContext
        // OFF THE MAIN THREAD. The import writes the whole catalogue and now erases
        // the whole books, and it takes a full backup before either - none of which
        // belongs where the screen is drawn. It ran here synchronously before, and
        // adding a database-sized backup to that would have read as a frozen till.
        com.example.synergic_pos_offline.utils.BusyDialog.run(this, "Backing up, then uploading…") {
            // A PRECONDITION, not a courtesy - the same contract Erase Bills keeps.
            // This upload throws the books away, so if the copy cannot be written,
            // nothing is uploaded and nothing is erased.
            val backup = try {
                if (ProductBulkImporter.mergeCounts(app, rows).billsToDelete > 0) {
                    com.example.synergic_pos_offline.utils.AutoBackup.backupBefore(app, "upload products")
                } else null
            } catch (e: Exception) {
                com.example.synergic_pos_offline.utils.BusyDialog.onMain(this) {
                    if (!isAdded) return@onMain
                    DialogUtils.showSuccess(
                        context = requireContext(),
                        title = "Nothing was uploaded",
                        message = "A full backup is taken before an upload erases the books, " +
                            "and this one could not be written: ${e.message ?: e.javaClass.simpleName}." +
                            "\n\nSo nothing was changed. Check there is room on the device and " +
                            "try again."
                    )
                }
                return@run
            }

            val result = ProductBulkImporter.import(app, rows, ProductBulkImporter.Mode.APPEND)

            // THE FLOOR GOES WITH THE BILLS, exactly as it does in Erase Bills. A
            // running order is a bill that has not been written yet; leaving those
            // behind would strand tables mid-service on orders whose kitchen tickets
            // this has just deleted underneath them.
            val floor = if (result.billsDeleted > 0) {
                runCatching { com.example.synergic_pos_offline.utils.BillErase.clearFloor(app) }
                    .getOrNull()
            } else null

            com.example.synergic_pos_offline.utils.BusyDialog.onMain(this) {
                if (!isAdded) return@onMain
                DialogUtils.showSuccess(
                    context = requireContext(),
                    title = "Upload Complete",
                    message = buildString {
                        append("${result.imported} new product(s) added.")
                        if (result.replaced > 0) append("\n${result.replaced} existing product(s) updated.")
                        // Said plainly. The upload takes the books with it, and an
                        // operator who is not told here finds them missing from a
                        // report later with nothing to connect the two.
                        if (result.billsDeleted > 0) {
                            append("\n${result.billsDeleted} bill(s) erased - the whole books, ")
                            append("cancelled bills included.")
                        }
                        floor?.let { (open, freed) ->
                            if (open > 0 || freed > 0) {
                                append("\n$open open table order(s) cleared, ")
                                append("$freed table(s) back to Available.")
                            }
                        }
                        if (result.skipped > 0) append("\n${result.skipped} row(s) skipped.")
                        append("\nApp language set to ${result.languageApplied}.")
                        result.languageWarning?.let { append("\n$it") }
                        result.referenceWarning?.let { append("\n$it") }
                        backup?.let {
                            append("\n\nThe till as it was is saved to $it.")
                        }
                    }
                )
            }
        }
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

        /**
         * Where the picture folder last picked is remembered.
         *
         * The URI, not the readable path - the URI is the grant, and it is what has
         * to be reopened. A shop keeps its product pictures in one place, so being
         * asked to find that place again on every upload is a tax on the common case.
         */
        const val KEY_IMAGE_FOLDER = "Product Image Folder"
    }

}
