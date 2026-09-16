package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.AppSettingsDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.BarcodeGenerator
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThermalPrinter
import com.example.synergic_pos_offline.utils.TsplLabel

/**
 * Master > Database Settings > Barcode: give the catalogue its barcodes.
 *
 * ## The products table, not a screen of its own
 *
 * Built on [DataTableFragment], so it is the same table the Products master is - same
 * search box, same tick boxes, same column header, same row height. Picking products
 * out of the catalogue is a thing this app already knows how to present, and a
 * bespoke layout for it would have been a second answer to a question already settled.
 *
 * Three columns and nothing else. S.No and Name are how the operator finds the row;
 * Barcode is what the screen is for, and it is on the table so that stock with no code
 * of its own is visible as a gap that can be ticked and filled.
 *
 * ## One action, and nothing else
 *
 * - **Add.** There is no barcode record to create. A product is added in the product
 *   master, and it arrives here the moment it exists.
 * - **Edit.** Typing a code in by hand is the product form's job and it is already
 *   there. What this screen does to a code it does in bulk and by generating it - see
 *   [onBulkGenerate] - which is the one thing the product form cannot do, because it
 *   only ever has one product open.
 * - **Delete.** [showsDeleteAction] - the tick boxes here mean "give this a code", and
 *   a screen that selects rows in order to code them has no business removing them
 *   from the catalogue.
 * - **Bulk print.** [showsPrintAction] - the generic "print these rows" slip is a
 *   printout of the TABLE, which is not what anyone comes here wanting. A label is
 *   printed from its own row instead; see [onRowAction].
 *
 * So the tick boxes feed exactly one button, and that button generates barcodes. The
 * printer is per row, because a label is a thing you print for one product at a time.
 */
class BarcodeLabelsFragment : DataTableFragment() {

    override val screenTitle = "Barcode"

    /** S.No is the product's own id, the same figure the Products master shows there. */
    override val columns = listOf("S.No", "Name", "Barcode")

    override val showsAddAction = false
    override val showsEditAction = false
    override val showsDeleteAction = false
    override val showsPrintAction = false

    /** The barcode icon beside the printer - see [onBulkGenerate]. */
    override val showsGenerateAction = true

    override fun loadRows(): MutableList<DataRow> {
        val rows = mutableListOf<DataRow>()
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        // Newest first, as the Products master lists them: a delivery just entered is
        // the stock most likely to be standing on the counter waiting for labels.
        val sql = """
            SELECT p.id, p.product_name, p.bar_code
            FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
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
                            // Said in words rather than left blank. An empty cell reads
                            // as "not loaded"; a product with no barcode is a fact
                            // about the product, and one the operator has to see before
                            // they tick it for printing.
                            cursor.getString(2).orEmpty().ifBlank { "— none —" }
                        )
                    )
                )
            }
        }
        return rows
    }

    // ---- Print one label ---------------------------------------------------

    /**
     * The printer in the row's Actions column - but only on a row that has a barcode.
     *
     * Decided per row, which is what [rowActionIcon] is for: a product with no code has
     * nothing to put on a label, and offering the button there would be offering
     * something that can only be refused. The operator ticks that row and generates a
     * code first, at which point the printer appears on it.
     */
    override fun rowActionIcon(row: DataRow): Int? =
        if (row.cells.getOrNull(2)?.any { it.isDigit() } == true) R.drawable.ic_print else null

    override val rowActionLabel = "Print barcode label"

    /**
     * Asks how many labels and what stock they are on, then sends the job to the TSC
     * printer.
     *
     * ONE PRODUCT NEEDS AS MANY LABELS AS IT HAS FACINGS. A delivery of twelve jars
     * wants twelve labels, and a single shelf-edge strip wants one - so the count is
     * asked rather than assumed, and it is asked HERE rather than being a column on the
     * table, because it is a fact about this trip to the printer and not about the
     * product.
     *
     * The label's size is asked on the same card, and REMEMBERED: a shop buys one size
     * of label stock and works through the roll, so it is typed once and prefilled
     * every time after. It has to be asked at all because TSPL needs to be told - the
     * printer cannot measure its own stock, and a size that does not match the roll is
     * how bars end up printed across the gap between two labels.
     *
     * The printer is looked for BEFORE the card opens: told there is none, the operator
     * has nothing to do with a number they have just typed.
     */
    override fun onRowAction(row: DataRow) {
        val code = row.cells.getOrNull(2).orEmpty()
        val name = row.cells.getOrNull(1).orEmpty()
        val id = row.id.toIntOrNull()
        if (code.isBlank() || id == null) {
            toast("This product has no barcode yet")
            return
        }

        val config = labelPrinter()
        if (config == null) {
            toast("No label printer set up — configure one under Printer Settings")
            return
        }

        val settings = AppSettingsDao(requireContext())
        DialogUtils.showForm(
            context = requireContext(),
            title = "Print barcode label",
            fields = listOf(
                // Locked, so the popup says WHICH product it is about. The icon was
                // tapped on one row of a list of hundreds, and a bare "how many?" over
                // a table that has since scrolled is a question about nothing in
                // particular.
                DialogUtils.FormField("Product", name, locked = true),
                DialogUtils.FormField(
                    label = "Number of labels", value = "1", inputType = "number", maxLength = 3
                ),
                DialogUtils.FormField(
                    label = "Label width (mm)",
                    value = settings.get(KEY_LABEL_WIDTH_MM) ?: DEFAULT_LABEL_WIDTH_MM.toString(),
                    inputType = "number", maxLength = 3
                ),
                DialogUtils.FormField(
                    label = "Label height (mm)",
                    value = settings.get(KEY_LABEL_HEIGHT_MM) ?: DEFAULT_LABEL_HEIGHT_MM.toString(),
                    inputType = "number", maxLength = 3
                ),
                DialogUtils.FormField(
                    label = "Gap between labels (mm)",
                    value = settings.get(KEY_LABEL_GAP_MM) ?: DEFAULT_LABEL_GAP_MM.toString(),
                    inputType = "number", maxLength = 2
                )
            ),
            mandatoryFields = listOf(1, 2, 3),
            positiveText = "Print",
            negativeText = "Cancel"
        ) { values ->
            val asked = values.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            val widthMm = values.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
            val heightMm = values.getOrNull(3)?.trim()?.toIntOrNull() ?: 0
            // Blank means continuous stock, which is a real answer and means no gap.
            val gapMm = values.getOrNull(4)?.trim()?.toIntOrNull() ?: 0

            if (asked <= 0) { toast("Enter how many labels to print"); return@showForm }
            if (widthMm <= 0 || heightMm <= 0) { toast("Enter the label size in mm"); return@showForm }

            val copies = asked.coerceAtMost(MAX_LABELS)
            if (asked > MAX_LABELS) toast("Printing the first $MAX_LABELS labels")

            // Kept for next time, so the size is typed once per roll and not per label.
            settings.put(KEY_LABEL_WIDTH_MM, widthMm.toString())
            settings.put(KEY_LABEL_HEIGHT_MM, heightMm.toString())
            settings.put(KEY_LABEL_GAP_MM, gapMm.coerceAtLeast(0).toString())

            val job = TsplLabel.build(
                widthMm = widthMm,
                heightMm = heightMm,
                gapMm = gapMm.coerceAtLeast(0),
                productName = name,
                code = code,
                price = sellingPrice(id),
                shopName = storeName(),
                // The printer repeats the label itself and feeds the gap between each
                // one - see TsplLabel.build. One job, however many labels.
                copies = copies
            )

            toast(if (copies == 1) "Printing 1 label…" else "Printing $copies labels…")
            ThermalPrinter.printRaw(requireContext(), job, config) { result ->
                if (!isAdded) return@printRaw
                when (result) {
                    is ThermalPrinter.Result.Failure -> toast("Print failed: ${result.message}")
                    else -> toast(
                        if (copies == 1) "Label sent for $name" else "$copies labels sent for $name"
                    )
                }
            }
        }
    }

    /**
     * The label printer: the one set up under the OTHERS purpose, falling back to the
     * bill printer.
     *
     * OTHERS first because that is the third printer slot the app already has, and a
     * shop with a TSC beside its receipt printer has somewhere to put it without a new
     * settings screen. The fall back to BILL covers the shop whose ONLY printer is the
     * TSC - it will have been set up as the bill printer, because that is the slot that
     * gets configured first.
     */
    private fun labelPrinter(): ThermalPrinter.Config? =
        ThermalPrinter.configForPurpose(requireContext(), "OTHERS")
            ?: ThermalPrinter.configForPurpose(requireContext(), "BILL")

    /**
     * The product's default selling price, or null where it has none.
     *
     * Null rather than 0.00: a label reading "Rs 0.00" prices the goods at nothing,
     * which is worse on a shelf than a label with no price on it at all - so
     * [TsplLabel.build] leaves the line off entirely.
     *
     * COALESCE(sell_price, sale_price) because the two are duplicate columns for the
     * one figure and either may be the populated one - the same read the rest of the
     * app makes (see DatabaseHelper's own note on them).
     */
    private fun sellingPrice(productId: Int): Double? = runCatching {
        DatabaseHelper.getInstance(requireContext()).readableDatabase.rawQuery(
            "SELECT COALESCE(sell_price, sale_price) FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES} " +
                "WHERE product_id = ? ORDER BY \"default\" DESC, id ASC LIMIT 1",
            arrayOf(productId.toString())
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0).takeIf { it > 0.0 } else null
        }
    }.getOrNull()

    /** The shop's name for the top of the label, or null to leave that line off. */
    private fun storeName(): String? = runCatching {
        DatabaseHelper.getInstance(requireContext()).readableDatabase.rawQuery(
            "SELECT store_name FROM ${DatabaseHelper.Tables.MD_REGISTRATION} LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    }.getOrNull()

    // ---- Generate ----------------------------------------------------------

    /**
     * Gives the ticked products barcodes, asking first where any of them already has
     * one.
     *
     * ## Replacing is offered, never assumed
     *
     * A code already on a product is on the labels already stuck to that shelf and, if
     * it came off the packet, on the manufacturer's own printing - so replacing it
     * stops the scanner finding that product until the shelf is re-labelled. That is a
     * real thing to want after a re-price or a re-pack, and the operator is the one who
     * knows. What it must not be is the silent default of one icon tapped over forty
     * ticked rows.
     *
     * So the three cases are asked three different ways:
     *
     * - **Nothing ticked has a code.** One plain confirm, and off it goes.
     * - **Everything ticked has one.** A replace warning, destructive styling, and the
     *   only affirmative button says Replace. There is nothing else this tap could
     *   mean, so there is nothing else to offer.
     * - **A mix.** A picker, because there are genuinely two reasonable answers -
     *   label the new stock, or re-code the lot - and a two-button dialog would have to
     *   guess which. Closing it does neither.
     *
     * ## Why the codes cannot collide
     *
     * [BarcodeGenerator.nextEan13] builds from the clock and retries against whatever
     * it is told is taken. The catalogue alone is not enough to tell it: a whole batch
     * is minted inside one loop, and two products handled in the same millisecond would
     * otherwise be handed the same digits, since neither is in the database yet when
     * the other is built. So the check is the catalogue OR anything already issued in
     * this batch.
     */
    override fun onBulkGenerate() {
        val ids = selectedRowIds
        if (ids.isEmpty()) {
            toast("Tick the products to generate barcodes for")
            return
        }

        val (blanks, coded) = splitByBarcode(ids)
        val all = blanks + coded

        when {
            all.isEmpty() -> toast("Could not read the selected products")

            coded.isEmpty() -> DialogUtils.showConfirm(
                context = requireContext(),
                title = "Generate barcodes",
                message = "Generate a new barcode for " +
                    (if (blanks.size == 1) "1 product" else "${blanks.size} products") +
                    " with none.",
                positiveText = "Generate",
                negativeText = "Cancel"
            ) { generateInto(blanks) }

            blanks.isEmpty() -> DialogUtils.showConfirm(
                context = requireContext(),
                title = "Replace barcodes",
                message = (if (coded.size == 1) "This product already has a barcode."
                    else "All ${coded.size} selected products already have a barcode.") +
                    "\n\nReplacing " + (if (coded.size == 1) "it" else "them") +
                    " will stop the scanner finding " +
                    (if (coded.size == 1) "this product" else "these products") +
                    " by any label already printed. Re-print the labels afterwards.",
                positiveText = "Replace",
                negativeText = "Cancel",
                destructive = true
            ) { generateInto(coded) }

            else -> DialogUtils.showList(
                context = requireContext(),
                title = "Some already have a barcode",
                subtitle = "${coded.size} of the ${all.size} selected products " +
                    "already carry one.",
                items = listOf(
                    DialogUtils.ListItem(
                        title = "Fill in the blanks only",
                        subtitle = "Leaves the existing barcodes exactly as they are",
                        trailing = blanks.size.toString()
                    ),
                    DialogUtils.ListItem(
                        title = "Replace all",
                        subtitle = "Every label already printed for the " +
                            "${coded.size} coded products stops scanning",
                        trailing = all.size.toString()
                    )
                ),
                negativeText = "Cancel"
            ) { picked -> generateInto(if (picked == 0) blanks else all) }
        }
    }

    /**
     * The ticked products split into those with no barcode and those that carry one,
     * read fresh from the catalogue.
     *
     * Read back rather than trusting the "— none —" the table is showing: the row on
     * screen is as old as the last load, and a code added on the product master in
     * between must not be treated as a blank and overwritten without the warning.
     */
    private fun splitByBarcode(ids: Set<String>): Pair<List<Int>, List<Int>> {
        val wanted = ids.mapNotNull { it.toIntOrNull() }
        if (wanted.isEmpty()) return emptyList<Int>() to emptyList()
        val blanks = mutableListOf<Int>()
        val coded = mutableListOf<Int>()
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        db.rawQuery(
            "SELECT id, bar_code FROM ${DatabaseHelper.Tables.MD_PRODUCTS} " +
                "WHERE id IN (${wanted.joinToString(",")})",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getInt(0)
                if (c.getString(1).orEmpty().isBlank()) blanks.add(id) else coded.add(id)
            }
        }
        return blanks to coded
    }

    /** Mints and saves a code for each of [productIds], then repaints the table. */
    private fun generateInto(productIds: List<Int>) {
        val db = DatabaseHelper.getInstance(requireContext()).writableDatabase
        val issued = mutableSetOf<String>()
        var saved = 0

        // One transaction: forty products either all get their codes or none do, rather
        // than the operator being left to work out how far down the list it reached.
        db.beginTransaction()
        try {
            productIds.forEach { id ->
                val code = BarcodeGenerator.nextEan13 { candidate ->
                    candidate in issued || barcodeExists(candidate)
                }
                val values = android.content.ContentValues().apply { put("bar_code", code) }
                val rows = db.update(
                    DatabaseHelper.Tables.MD_PRODUCTS, values, "id = ?", arrayOf(id.toString())
                )
                if (rows > 0) {
                    issued.add(code)
                    saved++
                }
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            android.util.Log.e("BarcodeLabelsFragment", "Could not save generated barcodes", e)
        } finally {
            db.endTransaction()
        }

        if (saved == 0) {
            toast("Could not generate barcodes")
            return
        }
        // The codes are on the products now, so the table has to say so - the Barcode
        // column is the only place the operator can check what was just minted.
        refreshRows()
        toast(if (saved == 1) "1 barcode generated" else "$saved barcodes generated")
    }

    /** Whether any product already carries [code] - the same check the product form makes. */
    private fun barcodeExists(code: String): Boolean = runCatching {
        DatabaseHelper.getInstance(requireContext()).readableDatabase.rawQuery(
            "SELECT 1 FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE bar_code = ? LIMIT 1",
            arrayOf(code)
        ).use { it.moveToFirst() }
    }.getOrDefault(false)

    private fun storeId(): Int = SessionManager.currentUser?.storeId ?: 0

    private companion object {
        /**
         * The most labels one tap will print.
         *
         * Not a limit anyone should meet in practice - it is there so that a slipped
         * digit ("100" typed as "1000") costs a torn strip of paper rather than the
         * whole roll and a printer that cannot be stopped from the app.
         */
        const val MAX_LABELS = 100

        /**
         * The label stock's size, remembered between prints.
         *
         * In App Settings' key-value table rather than on the printer row, because it
         * describes the ROLL rather than the machine - the same printer runs 50x25 one
         * week and 40x30 the next, and it is the operator loading the roll who knows.
         */
        const val KEY_LABEL_WIDTH_MM = "barcode_label_width_mm"
        const val KEY_LABEL_HEIGHT_MM = "barcode_label_height_mm"
        const val KEY_LABEL_GAP_MM = "barcode_label_gap_mm"

        /** 50x25mm on a 2mm gap - far and away the commonest shelf-label stock. */
        const val DEFAULT_LABEL_WIDTH_MM = 50
        const val DEFAULT_LABEL_HEIGHT_MM = 25
        const val DEFAULT_LABEL_GAP_MM = 2
    }
}
