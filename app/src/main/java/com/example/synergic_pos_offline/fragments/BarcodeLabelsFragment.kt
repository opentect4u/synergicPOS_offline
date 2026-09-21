package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.BarcodeGenerator
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.PrintLog
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
     * Asks how many labels this product needs, then sends that many to the TSC printer.
     *
     * ONE PRODUCT NEEDS AS MANY LABELS AS IT HAS FACINGS. A delivery of twelve jars
     * wants twelve labels, and a single shelf-edge strip wants one - so the count is
     * asked rather than assumed, and it is asked HERE rather than being a column on the
     * table, because it is a fact about this trip to the printer and not about the
     * product.
     *
     * It is the ONLY thing asked. The stock never changes between prints, so its size
     * and gap are constants - see [TsplLabel] - rather than four boxes to retype every
     * time a label is wanted.
     *
     * The printer is looked for BEFORE the card opens: told there is none, the operator
     * has nothing to do with a number they have just typed.
     */
    override fun onRowAction(row: DataRow) {
        // The tap itself is logged before anything is looked up, so a print that never
        // reaches the printer can still be told apart from one that was never asked for.
        // Everything that follows either prints, or says here why it did not.
        PrintLog.d(requireContext(), LOG_TAG, "barcode print icon tapped on row ${row.id}")
        val id = row.id.toIntOrNull()
        // READ FRESH, not off the row. The cells on screen are as old as the last table
        // load, and between then and now the code may have been generated on this very
        // screen, or the price changed on the product master. What goes on a label that
        // is about to be stuck to a shelf has to be what the catalogue says NOW.
        val product = id?.let { productForLabel(it) }
        if (product == null) {
            PrintLog.d(requireContext(), LOG_TAG, "STOPPED: product $id could not be read")
            toast("Could not read this product")
            return
        }
        val name = product.name
        val code = product.code
        if (code.isBlank()) {
            PrintLog.d(requireContext(), LOG_TAG, "STOPPED: \"$name\" has no barcode")
            toast("This product has no barcode yet")
            return
        }
        PrintLog.d(
            requireContext(), LOG_TAG,
            "product: \"$name\" code=$code price=${product.price} mrp=${product.mrp}"
        )

        val config = labelPrinter()
        if (config == null) {
            PrintLog.d(requireContext(), LOG_TAG, "STOPPED: no printer saved under the BARCODE purpose")
            toast("This product has no barcode yet")
            return
        }

        val config = labelPrinter()
        if (config == null) {
            // NAMES THE OPTION, because "configure one under Printer Settings" sent the
            // operator to a page of printers with no clue which one this screen wants.
            DialogUtils.showConfirm(
                context = requireContext(),
                title = "No barcode printer set up",
                message = "Add the TSC label printer under Print Settings › " +
                    "Connections, and pick one of the BARCODE options in the printer " +
                    "dropdown — BARCODE-USB, BARCODE-LAN or BARCODE-BLUETOOTH, " +
                    "whichever matches how it is plugged in.\n\n" +
                    "Enter its address, tick Default, and save. BILL and KOT stay as " +
                    "they are for receipts and kitchen tickets.",
                positiveText = "Open Connections",
                negativeText = "Close"
            ) {
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, OperatingPrinterFragment())
                    .addToBackStack(null)
                    .commit()
            }
            return
        }
        PrintLog.d(
            requireContext(), LOG_TAG,
            "label printer: ${config.connection} ${config.ip}:${config.port}"
        )

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
                )
            ),
            mandatoryFields = listOf(1),
            positiveText = "Print",
            negativeText = "Cancel"
        ) { values ->
            val asked = values.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            if (asked <= 0) {
                PrintLog.d(requireContext(), LOG_TAG, "STOPPED: \"${values.getOrNull(1)}\" is not a count")
                toast("Enter how many labels to print"); return@showForm
            }

            val copies = asked.coerceAtMost(MAX_LABELS)
            if (asked > MAX_LABELS) toast("Printing the first $MAX_LABELS labels")
            PrintLog.d(requireContext(), LOG_TAG, "operator asked for $asked label(s), printing $copies")
            if (asked <= 0) { toast("Enter how many labels to print"); return@showForm }

            val copies = asked.coerceAtMost(MAX_LABELS)
            if (asked > MAX_LABELS) toast("Printing the first $MAX_LABELS labels")

            val job = TsplLabel.build(
                productName = name,
                code = code,
                price = product.price,
                mrp = product.mrp,
                // STICKERS, not feeds. On two-up stock ten of these is five feeds, and
                // an odd count leaves the last feed's right-hand cell blank rather than
                // handing over a spare sticker - see TsplLabel.build.
                copies = copies
            )

            toast(if (copies == 1) "Printing 1 label…" else "Printing $copies labels…")
            val ctx = requireContext()
            ThermalPrinter.printRaw(ctx, job, config) { result ->
                // Logged whether or not the screen is still there to be told. A print
                // finishing after the operator has moved on is exactly the case where
                // the toast is missed and the log is all there is.
                PrintLog.d(ctx, LOG_TAG, "screen got the result: $result (screen still open=$isAdded)")
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
     * The label printer: the one set up under the BARCODE purpose, and ONLY that one.
     *
     * BARCODE is the third printer slot the app has always had - it was called OTHERS
     * until it had a job to do (see DatabaseHelper's v22 migration) - so a TSC sitting
     * beside the receipt printer has somewhere to live without a new settings screen.
     *
     * ## Why there is no fall back to the bill printer
     *
     * There was one, and it was wrong. The job this screen sends is TSPL - a program
     * for a label printer - and the BILL printer is an ESC/POS receipt printer. Handed
     * TSPL it does not fail; it prints the commands as text, so a shop with its receipt
     * printer set up and its TSC not yet set up would get a page of
     * "SIZE 50 mm,25 mm / GAP 2 mm..." spooling out of the till roll, with nothing on
     * screen to say why. A shop that has not told the app where its label printer is
     * should be asked, not guessed at.
     */
    private fun labelPrinter(): ThermalPrinter.Config? =
        ThermalPrinter.configForPurpose(requireContext(), "BARCODE")

    /** Everything one label carries, read off the catalogue at the moment of printing. */
    private data class LabelData(
        val name: String,
        val code: String,
        val price: Double?,
        val mrp: Double?
    )

    /**
     * The product as it stands RIGHT NOW - name, barcode and prices - or null if it has
     * gone.
     *
     * One query rather than three. The label is a single statement about one product
     * and the four values on it have to agree with each other; read separately, a price
     * edited on another screen between two of the reads would put a name and a price on
     * the same sticker that never belonged together.
     *
     * ## Where the two prices come from
     *
     * The selling price is the product's DEFAULT rate - `COALESCE(sell_price,
     * sale_price)`, because those are duplicate columns for the one figure and either
     * may be the populated one (see DatabaseHelper's own note on them).
     *
     * MRP IS A RATE NAME, not a column. A product carries a row per rate in
     * md_product_rates - "Rate 1", "Rate 2", "MRP" - named from the Rate Name master,
     * so the listed price is the row whose rate_name says MRP.
     *
     * Both come back null rather than 0.00 where there is no such rate. A label reading
     * "PRICE:0.00" prices the goods at nothing, which is worse on a shelf than a label
     * carrying no price at all - so [TsplLabel.build] leaves the line off entirely.
     */
    private fun productForLabel(productId: Int): LabelData? = runCatching {
        val rates = DatabaseHelper.Tables.MD_PRODUCT_RATES
        DatabaseHelper.getInstance(requireContext()).readableDatabase.rawQuery(
            """
            SELECT p.product_name,
                   COALESCE(p.bar_code, ''),
                   (SELECT COALESCE(r.sell_price, r.sale_price) FROM $rates r
                     WHERE r.product_id = p.id ORDER BY r."default" DESC, r.id ASC LIMIT 1),
                   (SELECT COALESCE(r.sell_price, r.sale_price, r.rate) FROM $rates r
                     WHERE r.product_id = p.id
                       AND UPPER(TRIM(COALESCE(r.rate_name, ''))) = 'MRP'
                     ORDER BY r.id ASC LIMIT 1)
            FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
            WHERE p.id = ?
            """.trimIndent(),
            arrayOf(productId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            LabelData(
                name = c.getString(0).orEmpty(),
                code = c.getString(1).orEmpty().trim(),
                price = if (c.isNull(2)) null else c.getDouble(2).takeIf { it > 0.0 },
                mrp = if (c.isNull(3)) null else c.getDouble(3).takeIf { it > 0.0 }
            )
        }
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

    companion object {
        /**
         * WHETHER THE BARCODE SCREEN IS OFFERED AT ALL.
         *
         * Off, and it is listed nowhere: neither the Database Settings tile grid nor
         * the drawer's Master > Database Settings branch carries it, so there is no way
         * in and nothing half-finished for an operator to find.
         *
         * The screen, the TSPL builder, the generator and their tests all stay exactly
         * where they are - this hides the door, it does not pull the room down. Turning
         * it back on is this one word, and both listings pick it up, because both ask
         * here rather than each keeping its own copy of the answer.
         *
         * On, now that the print path behind it sends TSPL through TSC's own SDK
         * ([TscPrinter]) rather than the ESC/POS receipt SDK's raw byte pipe - see
         * [ThermalPrinter.printRaw].
         */
        const val ENABLED = true
         */
        const val ENABLED = false

        /**
         * The most labels one tap will print.
         *
         * Not a limit anyone should meet in practice - it is there so that a slipped
         * digit ("100" typed as "1000") costs a torn strip of paper rather than the
         * whole roll and a printer that cannot be stopped from the app.
         */
        private const val MAX_LABELS = 100

        /** What this screen's lines are filed under in the print log. */
        private const val LOG_TAG = "BarcodeLabels"
    }
}
