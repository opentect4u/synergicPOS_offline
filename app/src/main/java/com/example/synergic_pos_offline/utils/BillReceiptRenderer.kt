package com.example.synergic_pos_offline.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillHeaderFooterDao
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.CaptionDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.LogoDao
import com.example.synergic_pos_offline.database.TaxSettingsDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Longest edge decoded for a receipt logo; the slots cap well below this. */
private const val LOGO_PX = 480

/**
 * Width the card is laid out at before it is scaled to the paper.
 *
 * Taken from [PrintType.CARD_WIDTH_DP], the one dial for how big every slip
 * prints - each renderer used to carry its own copy of this number, so making
 * the print bigger meant finding all five and hoping none was missed.
 */
private const val CARD_WIDTH_DP = PrintType.CARD_WIDTH_DP

/** The card's own horizontal padding, per side - the bill layouts' paddingHorizontal. */
private const val CARD_PADDING_DP = 20

/** Printable dots on 80mm paper - what [CARD_WIDTH_DP] is measured against. */
private const val REFERENCE_PAPER_DOTS = PrintType.REFERENCE_PAPER_DOTS

/**
 * Below this, the item table is set smaller so its columns still fit the roll.
 *
 * The layout is the same at every width - a line item across one row, with a long
 * name taking the row above its figures - because that is what makes a bill readable
 * down the page. What a 2-inch roll cannot take is five columns at full size: the
 * headings grow wider than the columns they label and wrap, which drags the figures
 * under them out of line. Stepping the type down keeps the labels on one line and
 * the columns where they belong. 58mm (384 dots) falls under this; 80mm (576) and
 * up print at full size.
 */
private const val NARROW_PAPER_DOTS = 450

/** Type size for the item table's headings and figures at 80mm and wider. */
private const val WIDE_ITEM_SP = 12.5f

/**
 * Type size for the totals block on a 2-inch roll. Its lines are label-and-figure
 * pairs sharing one width, so at full size "ITEM: n QTY: q" runs past the half it
 * has and wraps under itself.
 */
private const val NARROW_SUMMARY_SP = 13.5f

/** The same, at 80mm and wider. */
private const val WIDE_SUMMARY_SP = 12.5f

/**
 * Type size for the rest of the slip - bill number, customer, payment, footer - on
 * a 2-inch roll. These are single-column lines with room to spare, so they can be
 * set larger than the item table, which has three columns to fit across the same
 * width. At 80mm they keep the size the layout gives them.
 */
private const val NARROW_BODY_SP = 15f

/**
 * How much of the usual gap between printed rows a 2-inch slip keeps.
 *
 * The larger type it is set in already carries its own leading, so the padding that
 * spaces rows apart at 80mm leaves the narrow slip looking loose - and every
 * millimetre of it is paper. Applied to the gaps between rows only; the space
 * within a row is what keeps a figure off the line above it.
 */
private const val NARROW_ROW_SPACING = 0.5f

/**
 * Type size for a Classic line item on a 2-inch roll.
 *
 * Classic prints a whole line - serial, name, quantity, price and amount - across
 * one row, so a narrow roll has to set it *smaller* to keep those five columns on
 * that row. Every template prints that way now, so this is the size a 2-inch item
 * line is set at whichever one is chosen.
 */
private const val CLASSIC_NARROW_ITEM_SP = 10f

/**
 * Type size for the Classic head - bill number, date and time, which share a line.
 *
 * Left out of [NARROW_BODY_SP] for the same reason as the item rows: three fields
 * on one line have no room to grow. Here the squeeze is the sharper one, because
 * the date is centred on the paper rather than merely placed between the other two,
 * which leaves the bill number only half of what the date does not take.
 */
private const val CLASSIC_NARROW_HEAD_SP = 10f

/**
 * What the bill number is labelled on a 2-inch Classic roll.
 *
 * Centring the date costs the bill number width - it gets half of what is left,
 * about ten characters here - and "BILL NO: " spends nine of them on the label
 * before the number starts. Shortening the label is what keeps the number itself on
 * the line; beside a date and a time, "NO:" is not ambiguous.
 *
 * The colon is added where it is printed, not carried here: this is the word that is
 * looked up in the print language, and the punctuation is not part of it.
 */
private const val NARROW_BILL_NO_LABEL = "NO"

/** GRAND TOTAL on a Classic slip, at Bill Settings' Regular and Big sizes. */
private const val CLASSIC_GRAND_TOTAL_SP = 17f
private const val CLASSIC_GRAND_TOTAL_BIG_SP = 22f

/** The same on a 2-inch roll, where "GRAND TOTAL:" and its figure share 24 characters. */
private const val CLASSIC_NARROW_GRAND_TOTAL_SP = 14f
private const val CLASSIC_NARROW_GRAND_TOTAL_BIG_SP = 17f

/**
 * Column weights for the Classic item table: name, quantity, price, discount, amount.
 *
 * The one source of truth for both the headings and the rows beneath them - the
 * headings in fragment_bill_classic.xml are laid out to these at inflation and set
 * from here after, so a column and its label cannot drift apart.
 */
private val CLASSIC_COLUMNS = floatArrayOf(3.8f, 2f, 1.9f, 1.7f, 2.3f)

/**
 * Where each column sits in the arrays above: name, quantity, price, discount,
 * amount. DISC is the one that is not always drawn.
 */
private const val QTY_COLUMN = 1
private const val PRICE_COLUMN = 2
private const val DISC_COLUMN = 3
private const val NET_COLUMN = 4

/** The figure columns, in the order they are drawn - everything but the name. */
private val ITEM_FIGURE_COLUMNS = listOf(QTY_COLUMN, PRICE_COLUMN, DISC_COLUMN, NET_COLUMN)

/**
 * The fewest characters of an item name the name column is ever squeezed to.
 *
 * It gives up width to the figure columns when their values need more than their
 * weight allows, and this is the floor: past it a bill of very wide figures would
 * print a column of names too narrow to show anything at all.
 */
private const val NAME_COLUMN_MIN_CHARS = 6f

/**
 * The longest item name that shares its line with the figures on a Classic bill.
 *
 * A count of characters rather than a measurement, because that is the rule the
 * bill is meant to follow: up to ten characters the name sits beside its quantity
 * and price, and past ten it takes a line of its own. Ten is about what the name
 * column holds on 80mm paper, so the rule and the room agree there - which is the
 * paper this was set against.
 *
 * It is a ceiling, not a guarantee: on a 2-inch roll carrying a DISC column the
 * name column holds nearer nine characters, and a name that will not fit still
 * takes its own line whatever its length. Otherwise it would wrap inside the
 * column, which is the thing giving the name its own line exists to prevent.
 */
private const val CLASSIC_NAME_MAX_CHARS = 10

/**
 * The same on a 2-inch roll, where the item name is given a larger share.
 *
 * The figures need a fixed number of characters whatever the paper is - a price is
 * a price - so on a narrow roll it is the name that has to give, and at these
 * proportions it gives less: everything but the name is trimmed to what it actually
 * needs, which buys the name back the room for a typical one to stay on its line.
 */
private val CLASSIC_NARROW_COLUMNS = floatArrayOf(4.6f, 1.9f, 1.8f, 1.5f, 2f)

/**
 * The same again for a 2-inch roll that also has a DISC column to fit.
 *
 * Five columns is more than that width comfortably takes, and something has to be
 * squeezed. It is the name: a figure that wraps is unreadable - "145.00" broken
 * after the "5" reads as two numbers - while a name that wraps is still the name,
 * on a second line. So each figure column is widened to the characters it actually
 * needs and the name takes what is left, wrapping where it must.
 */
private val CLASSIC_NARROW_DISC_COLUMNS = floatArrayOf(3.5f, 1.6f, 1.9f, 1.6f, 1.9f)

/**
 * Type size for a Classic line item on a 2-inch roll carrying a DISC column.
 *
 * A step down from [CLASSIC_NARROW_ITEM_SP], bought to widen the name column back
 * to around a dozen characters. Below that the name does not merely wrap, it breaks
 * mid-word - "TOOTHPASTE" split as "TOOT" / "HPASTE" - and a customer cannot read
 * back what they were charged for.
 */
private const val CLASSIC_NARROW_DISC_ITEM_SP = 9f

/**
 * Column weights for the Tax wise short tax table: rate, base, SGST, CGST, total.
 *
 * The one source of truth for the table's headings and its rows alike, the same way
 * [CLASSIC_COLUMNS] is for the item table.
 */
private val TAX_WISE_COLUMNS = floatArrayOf(2f, 2.4f, 1.9f, 1.9f, 2.3f)

/**
 * The same on a 2-inch roll. Every column here holds a money figure of much the same
 * width, so unlike the item table there is no name to give up room - they are simply
 * evened out, and the type is what shrinks ([CLASSIC_NARROW_ITEM_SP]).
 */
private val TAX_WISE_NARROW_COLUMNS = floatArrayOf(2.1f, 2.2f, 1.9f, 1.9f, 2.2f)

/**
 * What the item heading says on a 2-inch Classic roll carrying a DISC column - the
 * name column is down to about nine characters there, and "SR.NO ITEM" wrapping
 * would take the headings out of line with the figures under them.
 */
private const val CLASSIC_NARROW_ITEM_HEADING = "ITEM"

/**
 * Fills a receipt layout from the bill tables.
 *
 * Split out of the bill screen so a receipt can be produced without one being on
 * display: checkout prints the moment a sale is completed, and the operator never
 * leaves the till. Both paths render the same layout from the same query, so an
 * auto-print and a later reprint from the bill screen are identical slips.
 *
 * Needs a themed context - an Activity or a Fragment's context - because it
 * inflates and measures real views.
 */
class BillReceiptRenderer(context: Context) {

    /**
     * Pinned to a standard font scale, so the slip is identical whatever the device
     * is set to - see [ReceiptContext].
     */
    private val ctx: Context = ReceiptContext.standardFontScale(context)

    /** The language this till labels its slips in - see [PrintLanguage]. */
    private val lang: PrintLanguage.Language = PrintLanguage.of(context)

    /** [text] in the till's print language, or as it is where there is no translation. */
    private fun t(text: String): String = PrintLanguage.tr(lang, text)

    /**
     * A product's name as it prints - upper-cased, then put into the print language.
     *
     * Translated where the words are words and respelled where they are names; see
     * [ProductName], which decides which is which. Upper-cased first and not after,
     * because it is the Latin name that has a case to change - the scripts it becomes
     * have none.
     */
    private fun productName(name: String): String = ProductName.inPrintLanguage(lang, name.uppercase())

    /**
     * The bill's typeface — the bundled Roboto Mono font (res/font/roboto_mono_regular.ttf).
     * Every code-built cell renders with it (the bill XML layouts point their fontFamily
     * at the same font), so the whole slip prints in one face. Falls back to the platform
     * monospace if the font ever fails to load.
     *
     * A slip in any other language is set in the platform's own monospace family
     * instead - Roboto Mono carries no Indic script, and a face with no fallback
     * behind it prints those labels as empty boxes. See [PrintLanguage.typeface].
     */
    private val billTypeface: Typeface by lazy {
        PrintLanguage.typeface(
            lang,
            androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.roboto_mono_regular)
                ?: Typeface.MONOSPACE
        )
    }

    /**
     * One printed line item: serial + name, quantity, unit price, discount and the
     * net it leaves.
     *
     * [netAmount] is the line's gross - quantity x [price] - less its [discount], so
     * an undiscounted line's net is simply its gross and [discount] is null there,
     * printed as a dash.
     */
    private data class BillItem(
        val sr: Int,
        val name: String,
        val qty: String,
        val price: String,
        val netAmount: String,
        val hsn: String? = null,
        val discount: String? = null,
        /**
         * The unit the quantity was sold in - PKT, LTR, GM. Printed beside the
         * quantity on a Classic slip and nowhere else, and only where the sale
         * actually records one: a bill line with no unit prints the bare figure
         * rather than a guessed unit.
         */
        val unit: String? = null
    )

    /**
     * Totals accumulated from the line items rather than read back from the
     * `td_bills` header, so the printed receipt always adds up to what is listed
     * on it. [discount] is the one figure the items cannot supply: it is stored
     * per bill, not per line, so it is passed in from the header.
     *
     * A pre-tax discount is spread across the lines at billing time, so it already
     * shows up inside [itemsSubtotal] via each line's own `discount_amount`
     * ([itemDiscountApplied] is that spread total). A post-tax discount instead
     * leaves every line untouched and applies once, after tax, at the bill level -
     * [remainingDiscount] is whichever part of [discount] the lines have not
     * already accounted for, so it is never subtracted twice.
     */
    private data class BillTotals(
        val itemsSubtotal: Double = 0.0,
        val cgst: Double = 0.0,
        val sgst: Double = 0.0,
        val vat: Double = 0.0,
        val otherTax: Double = 0.0,
        val discount: Double = 0.0,
        val itemDiscountApplied: Double = 0.0,
        val itemDiscountListed: Double = 0.0,
        val grossMrp: Double = 0.0,
        val qtyCount: Double = 0.0,
        val itemCount: Int = 0
    ) {
        val base: Double get() = itemsSubtotal
        val tax: Double get() = cgst + sgst + vat + otherTax
        val remainingDiscount: Double get() = (discount - itemDiscountApplied).coerceAtLeast(0.0)
        val grandTotal: Double get() = (base + tax - remainingDiscount).coerceAtLeast(0.0)

        /**
         * The whole discount the customer got, stated against the listed prices it
         * came off: what the lines already priced in ([itemDiscountListed]) plus any
         * bill-level discount not folded into them ([remainingDiscount]). Printed
         * against the summary's own listed-price AMT, so the two chain: AMT less this
         * discount, plus the tax below it, is the TOTAL.
         */
        val totalDiscount: Double get() = itemDiscountListed + remainingDiscount
    }

    /**
     * One rate slab in the tax summary. A bill can mix products taxed at different
     * rates, so the tax is reported one line per rate - not a single blended rate,
     * which is meaningless on a tax invoice (a 10% and a 5% line do not average to
     * a real "7.35%"). For GST [cgstRate]/[sgstRate] label the split; for VAT only
     * [vatRate]/[vat] are used.
     */
    private data class TaxSlab(
        val cgstRate: Double,
        val sgstRate: Double,
        val vatRate: Double,
        val base: Double,
        val cgst: Double,
        val sgst: Double,
        val vat: Double
    ) {
        val tax: Double get() = cgst + sgst + vat
    }

    /**
     * Renders the bill to a bitmap without it ever being shown, laid out for a printer
     * whose head is [paperDots] wide (defaults to 80mm).
     *
     * The card is detached from the inflated hierarchy and measured on its own,
     * unbounded in height. Its width scales with the paper rather than being fixed, so
     * the printer scales every paper size by the same factor: a 58mm slip prints at the
     * same font size as an 80mm one and simply wraps more text, instead of coming out
     * as a shrunk 80mm.
     *
     * [duplicate] stamps the slip as a second copy of a bill already issued - see
     * [populate].
     *
     * @return null if the bill could not be rendered, so a caller does not print blank paper
     */
    fun renderToBitmap(
        receiptNo: Long,
        paperDots: Int = REFERENCE_PAPER_DOTS,
        duplicate: Boolean = false
    ): Bitmap? = renderInternal(paperDots) { root ->
        populate(root, receiptNo, paperDots, duplicate = duplicate)
    }

    /**
     * Renders an in-memory [draft] to a bitmap through exactly the same layout, font
     * sizes and settings the saved bill uses â€” so a restaurant bill printed from a
     * draft is byte-for-byte the grocery bill's format. The service charge should be
     * folded into the draft's items so it is part of the printed total.
     */
    fun renderDraftToBitmap(draft: Draft, paperDots: Int = REFERENCE_PAPER_DOTS): Bitmap? =
        renderInternal(paperDots) { root ->
            populate(root, receiptNo = 0, paperDots = paperDots, draft = draft)
        }

    /** Inflates the receipt layout, runs [fill], then measures and captures the card. */
    private fun renderInternal(paperDots: Int, fill: (View) -> Unit): Bitmap? = runCatching {
        val root = LayoutInflater.from(ctx).inflate(layoutFor(ctx), null, false)

        // The print button floats over the receipt and would be drawn onto the paper.
        root.findViewById<View>(R.id.btnPrintBill)?.visibility = View.GONE
        fill(root)

        val card = root.findViewById<View>(R.id.cardReceipt) ?: return null
        (card.parent as? ViewGroup)?.removeView(card)

        val widthDp = CARD_WIDTH_DP.toDouble() * paperDots / REFERENCE_PAPER_DOTS
        val widthPx = (widthDp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        if (card.measuredHeight <= 0) return null
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)

        val captured = ReceiptPrinter.capture(card) ?: return null
        // The card is captured to its exact height, so a big header/footer font sits
        // flush against the top/bottom edge â€” with no clearance the footer runs into
        // the tear line and the next receipt. Add white top/bottom margins that scale
        // with the paper so header/footer always have breathing room in print.
        withVerticalMargins(captured, top = paperDots / 16, bottom = paperDots / 9)
    }.getOrElse {
        android.util.Log.e(TAG, "Could not render bill", it)
        null
    }

    /** Returns [src] padded with white space above and below (recycling [src]). */
    private fun withVerticalMargins(src: Bitmap, top: Int, bottom: Int): Bitmap {
        if (top <= 0 && bottom <= 0) return src
        val out = Bitmap.createBitmap(src.width, src.height + top + bottom, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(src, 0f, top.toFloat(), null)
        }
        src.recycle()
        return out
    }

    /**
     * A sale that has not been written yet, standing in for the three transaction
     * tables so a bill can be rendered before it exists - what the checkout screen
     * previews. Everything else a receipt prints (store identity, header/footer lines
     * and logos, and the Bill/Tax settings themselves) comes from the masters either
     * way, so a draft renders through exactly the same code as the saved bill and
     * cannot drift from it.
     *
     * With no settings snapshot to read, a draft falls back to the *live* settings -
     * which is what a preview wants: it shows what this sale would print right now.
     */
    data class Draft(
        val billNumber: String,
        val dateTime: String,
        val cashier: String,
        val customer: Customer,
        val items: List<Item>,
        val discount: Double,
        val roundOff: Double,
        val netAmount: Double,
        val paymentModes: List<String>,
        /** Restaurant service charge â€” shown as its own totals line, added to the net. */
        val serviceCharge: Double = 0.0,
        /** Cash returned when the customer tenders more than the payable â€” printed only when > 0. */
        val returnAmount: Double = 0.0
    ) {
        /** As captured on the sale; each field printed only where the settings ask. */
        data class Customer(
            val name: String? = null,
            val phone: String? = null,
            val gstin: String? = null,
            val address: String? = null,
            /**
             * What the customer will owe once this sale is booked - what is already
             * on their account plus whatever this bill leaves unpaid. The preview
             * has to work it out because nothing has been written yet; a saved bill
             * reads the same figure straight off the master.
             */
            val outstanding: Double? = null
        )

        /**
         * One line as it will be written to `td_bill_items`. [discountAmount] is
         * stated against the line's raw pre-tax base, the same shape the DAO stores,
         * so [BillPricing] prices it identically here and there.
         */
        data class Item(
            val name: String,
            val quantity: Double,
            val rate: Double,
            val cgstRate: Double = 0.0,
            val sgstRate: Double = 0.0,
            val vatRate: Double = 0.0,
            val discountAmount: Double = 0.0,
            val hsn: String? = null,
            /** Unit symbol, printed beside the quantity on a Classic slip. */
            val unit: String? = null
        )
    }

    /**
     * Fills an already-inflated receipt layout in place, for the on-screen bill.
     *
     * [paperDots] sets how tightly the item table is packed: the default (80mm's
     * width) prints it full size. Pass the printer's actual [paperDots] when this is
     * heading to a printer, so a 2-inch roll gets the smaller setting its columns
     * need - see [NARROW_PAPER_DOTS].
     *
     * [draft] renders a sale that has not been saved yet, in which case [receiptNo]
     * is unused; without one the bill is read from the transaction tables as usual.
     *
     * [duplicate] marks the slip as a copy of one already issued, which brings the
     * DUPLICATE captions onto it. It is the caller's to decide, not something
     * worked out from the print history: whether a copy counts as a duplicate is
     * about where it was asked for - a bill pulled back up from Bill history is a
     * second copy of one the customer already has - and the sale itself cannot
     * know that.
     */
    fun populate(
        view: View,
        receiptNo: Long,
        paperDots: Int = REFERENCE_PAPER_DOTS,
        draft: Draft? = null,
        duplicate: Boolean = false
    ) {
        try {
            val db = DatabaseHelper.getInstance(ctx).readableDatabase

            // Store identity and tax registration, printed at the head of the bill.
            // Its store_id also scopes the header/footer lines, so a header set up for
            // one store does not print alongside another store's on the same slip.
            var headerStoreId: Long? = null
            db.query(
                DatabaseHelper.Tables.MD_REGISTRATION,
                arrayOf("store_name", "address", "phone_no", "store_gstin", "store_id"),
                null, null, null, null, "store_id ASC", "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) {
                        view.findViewById<TextView>(R.id.tvStoreName).text = name.uppercase()
                    }
                    setIfPresent(view, R.id.tvStoreAddress, c.getString(1))
                    setIfPresent(view, R.id.tvStorePhone, c.getString(2)?.let { "Ph: $it" })
                    setIfPresent(view, R.id.tvStoreGstin, c.getString(3)?.let { "GSTIN: $it" })
                    if (!c.isNull(4)) headerStoreId = c.getLong(4)
                }
            }

            renderFixedLines(
                db, view, R.id.llBillHeaderLines,
                DatabaseHelper.Tables.MD_HEADERS, "header_text", "header_number", "header_type",
                headerStoreId
            )
            renderLogos(view)

            // Bill header + totals.
            var billNumber = ""
            var dateTime = ""
            var customerId: Long? = null
            var operatorId: Long? = null
            var createdBy: String? = null
            var billType: String? = null
            var amountInWords: String? = null
            var discount = 0.0
            var storedNetAmount = 0.0
            var roundOff = 0.0
            var serviceCharge = 0.0
            // Cash handed back to the customer when they tendered more than the payable.
            var returnAmount = 0.0
            var settingsSnapshotJson: String? = null
            if (draft != null) {
                billNumber = draft.billNumber
                dateTime = draft.dateTime
                roundOff = draft.roundOff
                discount = draft.discount
                storedNetAmount = draft.netAmount
                serviceCharge = draft.serviceCharge
                returnAmount = draft.returnAmount
            } else db.rawQuery(
                """
                SELECT bill_number, bill_date_time, bill_date, customer_id,
                       tot_discount_amount, net_amount, operator_id, created_by, bill_type,
                       tot_round_off_amount, amount_in_words, settings_snapshot,
                       COALESCE(service_charge_amount, 0)
                FROM ${billsTableFor(db, receiptNo)} WHERE receipt_no = ?
                """.trimIndent(),
                arrayOf(receiptNo.toString())
            ).use { c ->
                if (!c.moveToFirst()) return
                serviceCharge = c.getDouble(12)
                billNumber = c.getString(0) ?: receiptNo.toString()
                dateTime = c.getString(1) ?: c.getString(2) ?: ""
                customerId = if (c.isNull(3)) null else c.getLong(3)
                operatorId = if (c.isNull(6)) null else c.getLong(6)
                createdBy = c.getString(7)
                billType = c.getString(8)
                roundOff = c.getDouble(9)
                amountInWords = c.getString(10)
                settingsSnapshotJson = c.getString(11)
                // Discounts are recorded per bill, not per line, so this one figure
                // still has to come from the header; every other total is derived
                // from the printed line items below.
                discount = c.getDouble(4)
                storedNetAmount = c.getDouble(5)
            }

            // The change given back is recorded per payment row; a reprint reads the
            // stored figure so it matches what was handed over on the day.
            if (draft == null) db.rawQuery(
                "SELECT COALESCE(SUM(change_amount), 0) FROM ${DatabaseHelper.Tables.TD_PAYMENTS} WHERE bill_id = ?",
                arrayOf(receiptNo.toString())
            ).use { c -> if (c.moveToFirst()) returnAmount = c.getDouble(0) }

            // What was actually collected at the till on this bill - 0 on a full credit
            // sale, the part-payment on a partial one. Drives the CASH RECEIVED line.
            var cashReceived = 0.0
            if (draft == null) db.rawQuery(
                "SELECT COALESCE(SUM(amount_paid), 0) FROM ${DatabaseHelper.Tables.TD_PAYMENTS} WHERE bill_id = ?",
                arrayOf(receiptNo.toString())
            ).use { c -> if (c.moveToFirst()) cashReceived = c.getDouble(0) }

            // Whichever Bill/Tax Settings were active when this bill was made - not
            // necessarily what is live now - so a reprint reads exactly as it did on
            // the day. Older bills saved before this existed fall back to today's
            // settings, the only information there is for them.
            val snapshot = BillSettingsSnapshot.parse(settingsSnapshotJson)
            val liveSettings by lazy { BillSettingsDao(ctx).load() }
            val hsnCode = snapshot?.hsnCode ?: liveSettings.hsnCode
            val customerDetails = snapshot?.customerDetails ?: liveSettings.customerDetails
            val customerAddressPrinting = snapshot?.customerAddressPrinting ?: liveSettings.customerAddressPrinting
            val totalAmountFontSize = snapshot?.totalAmountFontSize ?: liveSettings.totalAmountFontSize
            val roundOffSetting = snapshot?.roundOff ?: liveSettings.roundOff
            val amountInWordsSetting = snapshot?.amountInWords ?: liveSettings.amountInWords
            val taxRegime = snapshot?.taxRegime ?: run {
                val taxSettings = TaxSettingsDao(ctx).load()
                GstCalculator.regimeFor(taxSettings.gstEnabled, taxSettings.vatEnabled)
            }
            val discountPreTax = snapshot?.discountPreTax
                ?: (TaxSettingsDao(ctx).load().discountPosition == TaxSettingsDao.DiscountPosition.PRE_TAX)
            val inclusive = snapshot?.inclusive ?: run {
                val taxSettings = TaxSettingsDao(ctx).load()
                when (taxRegime) {
                    GstCalculator.TaxRegime.GST -> taxSettings.gstMode == TaxSettingsDao.GstMode.INCLUSIVE
                    GstCalculator.TaxRegime.VAT -> taxSettings.vatMode == TaxSettingsDao.GstMode.INCLUSIVE
                    GstCalculator.TaxRegime.NONE -> false
                }
            }

            // Which layout this is being drawn into. The format is a live setting
            // rather than part of the bill's snapshot - it is how this till prints,
            // not a term of the sale - so changing the template restyles reprints of
            // old bills too, which is what "print Classic from now on" means.
            //
            // Confirmed against the view as well as the setting: a caller that
            // inflated the Standard layout gets the Standard treatment whatever the
            // setting says, rather than having its summary written into ids that
            // are not there.
            //
            // Tax wise short is a Classic slip that reports its tax as a table
            // rather than as a line per component per rate, so it takes the whole
            // Classic treatment - head, item rows, totals - and diverges only where
            // [taxWise] says so.
            val format = liveSettings.billFormat
            val classicFormat = format == BillSettingsDao.BillFormat.CLASSIC ||
                format == BillSettingsDao.BillFormat.TAX_WISE_SHORT
            val classic = classicFormat && view.findViewById<View>(R.id.llGrandTotal) != null
            val taxWise = classic && format == BillSettingsDao.BillFormat.TAX_WISE_SHORT &&
                view.findViewById<View>(R.id.llTaxRows) != null

            // How tightly everything is packed - the item table, the totals and the
            // head. Read here rather than at the item table because the Classic head
            // is written above it and needs the same answer.
            val narrow = paperDots < NARROW_PAPER_DOTS

            val billNoLabel = t(if (classic && narrow) NARROW_BILL_NO_LABEL else "BILL NO")
            view.findViewById<TextView>(R.id.tvBillNo).text = "$billNoLabel: $billNumber"
            // Moved to the foot of the bill, where "created by" belongs.
            view.findViewById<TextView>(R.id.tvBillCreatedBy).text =
                "${t("Created by")}: ${draft?.cashier ?: cashierName(db, operatorId, createdBy)}"

            // Which of mobile/name/gstin print is driven by "Customer Details"; the
            // address line is a separate on/off. Each still only shows when the
            // sale actually captured it - the settings choose what to print, not
            // what to fabricate.
            val cust = draft?.customer?.let {
                CustomerInfo(it.name?.uppercase(), it.phone, it.gstin, it.address?.uppercase(), it.outstanding)
            } ?: loadCustomerInfo(db, customerId, receiptNo)
            // A credit bill is an invoice the customer settles later and claims
            // against, so their GSTIN goes on it whatever Customer Details says - if
            // the sale captured one at all.
            val creditSale = draft?.paymentModes?.any { it.equals("CREDIT", true) }
                ?: billType.equals("CREDIT", true)
            val showMobile = customerDetails == BillSettingsDao.CustomerDetails.ONLY_MOBILE ||
                customerDetails == BillSettingsDao.CustomerDetails.MOBILE_NAME ||
                customerDetails == BillSettingsDao.CustomerDetails.MOBILE_NAME_GSTIN
            val showName = customerDetails == BillSettingsDao.CustomerDetails.MOBILE_NAME ||
                customerDetails == BillSettingsDao.CustomerDetails.MOBILE_NAME_GSTIN ||
                customerDetails == BillSettingsDao.CustomerDetails.ONLY_NAME
            val showGstin = creditSale ||
                customerDetails == BillSettingsDao.CustomerDetails.MOBILE_NAME_GSTIN ||
                customerDetails == BillSettingsDao.CustomerDetails.ONLY_GSTIN

            // Captions head the slip, and which ones apply is only known now that
            // the bill's type has been read. They stack: a credit bill reprinted
            // from Bill history carries all three sets.
            renderCaptions(view, creditSale = creditSale, duplicate = duplicate)

            setIfPresent(view, R.id.tvCustMobile, if (showMobile) cust.phone?.let { custLine("MOBILE ", "MOBILE", it) } else null)
            setIfPresent(view, R.id.tvName, if (showName) cust.name?.let { custLine("NAME  ", "NAME", it) } else null)
            setIfPresent(view, R.id.tvCustGstin, if (showGstin) cust.gstin?.let { custLine("GSTIN ", "GSTIN", it) } else null)
            setIfPresent(view, R.id.tvCustAddress, if (customerAddressPrinting) cust.address?.let { custLine("ADDRESS", "ADDRESS", it) } else null)
            // The customer's outstanding balance is printed beside the totals (in the
            // summary block below), not here in the customer block, so it reads right
            // next to the amount due on every final bill - grocery or restaurant.
            setIfPresent(view, R.id.tvCustOutstanding, null)

            val (date, time) = splitDateTime(dateTime)
            if (date.isNotEmpty()) view.findViewById<TextView>(R.id.tvDate).text = date
            if (time.isNotEmpty()) view.findViewById<TextView>(R.id.tvTime).text = time

            // Line items, plus the totals summed from those same lines.
            val raws = if (draft != null) {
                draftRawLines(draft, hsnCode, taxRegime, inclusive, discountPreTax)
            } else {
                readRawLines(db, receiptNo, hsnCode)
            }
            val (items, lineTotals, taxSlabs) = loadItems(raws, inclusive)
            val llItems = view.findViewById<LinearLayout>(R.id.llItems)
            llItems.removeAllViews()
            // The DISC column earns its place only when a line actually carries a
            // discount. A bill-wise discount comes off the taxed total instead, so it
            // leaves every line alone and the column would print nothing but dashes.
            val showDisc = items.any { it.discount != null }
            view.findViewById<TextView>(R.id.tvColDisc).visibility =
                if (showDisc) View.VISIBLE else View.GONE

            // The headings are set at the size the figures beneath them are, so the
            // two stay in step - a heading left at full size on a 2-inch roll wraps
            // and takes the column alignment with it.
            // One item table for all three templates. Standard used to build its own
            // - name always on a row of its own, its figures in columns of their own
            // widths beneath - which meant a short name took two lines there and one
            // on a Classic slip off the same till. The table is now the same table
            // everywhere, and only what surrounds it tells the templates apart.
            val headingSp = when {
                narrow && showDisc -> CLASSIC_NARROW_DISC_ITEM_SP
                narrow -> CLASSIC_NARROW_ITEM_SP
                else -> WIDE_ITEM_SP
            }
            val headings = listOf(
                R.id.tvColSrItem, R.id.tvColQty, R.id.tvColPrice, R.id.tvColDisc, R.id.tvColNet
            )
            // Translated before anything is measured, not after. The column widths
            // below are settled from the width of these headings, so a heading
            // replaced afterwards would be laid out to the width of the English one
            // it replaced - and a wider word in another script would then wrap.
            listOf(
                R.id.tvColSrItem to "SR.NO ITEM", R.id.tvColQty to "QTY",
                R.id.tvColPrice to "PRICE", R.id.tvColDisc to "DISC", R.id.tvColNet to "AMOUNT"
            ).forEach { (id, label) -> view.findViewById<TextView>(id).text = t(label) }
            headings.forEach { view.findViewById<TextView>(it).textSize = headingSp }
            val classicColumns = when {
                narrow && showDisc -> CLASSIC_NARROW_DISC_COLUMNS
                narrow -> CLASSIC_NARROW_COLUMNS
                else -> CLASSIC_COLUMNS
            }
            if (narrow && showDisc) {
                view.findViewById<TextView>(R.id.tvColSrItem).text = t(CLASSIC_NARROW_ITEM_HEADING)
            }
            // Fixed widths, not weights - see [itemColumnWidths]. Applied to the
            // headings and to the rows beneath them from the one array, so a figure
            // stays under its label.
            val columnPx = itemColumnWidths(
                items, showDisc, headingSp, classicColumns, paperDots,
                headings.map { view.findViewById<TextView>(it).text?.toString().orEmpty() }
            )
            headings.forEachIndexed { i, id ->
                view.findViewById<TextView>(id).let { heading ->
                    (heading.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                        lp.width = columnPx[i]
                        lp.weight = 0f
                        heading.layoutParams = lp
                    }
                    // A heading is a label, not a paragraph: one that outgrows its
                    // column is shortened or clipped, never wrapped, since a second
                    // line of heading sits over the wrong column entirely.
                    heading.maxLines = 1
                }
            }
            // "SR.NO ITEM" needs more room than the name column has left once the
            // figures have taken theirs - on a bill carrying a DISC column it is
            // squeezed hardest - so it gives way to the short form rather than
            // wrapping under itself.
            view.findViewById<TextView>(R.id.tvColSrItem).let { srItem ->
                if (measure(headingSp).measureText(srItem.text.toString()) > columnPx[0]) {
                    srItem.text = t(CLASSIC_NARROW_ITEM_HEADING)
                }
            }
            items.forEach {
                llItems.addView(buildClassicItemRow(it, showDisc, headingSp, columnPx, narrow))
            }

            val totals = lineTotals.copy(discount = discount)
            val isGst = taxRegime == GstCalculator.TaxRegime.GST

            // Round off is whatever the bill recorded, not something worked out here:
            // the printed total has to match the amount that was actually charged.
            //
            // net_amount is stored already rounded, so the adjustment is only added
            // to a total summed from the line items - adding it to the stored figure
            // would count it twice.
            val payable = if (items.isEmpty()) storedNetAmount else totals.grandTotal + roundOff + serviceCharge

            // Bill summary: item count / qty / gross, each tax rate on its own line,
            // discount and totals - laid out as "label : value" lines. Replaces the
            // old base-amount tax table.
            // The totals block follows the item table down on a 2-inch roll, so the
            // two read as one thing rather than the figures shrinking and their
            // totals staying large. The NET AMT value keeps its own size - Bill
            // Settings sets that deliberately, and it is the figure being looked for.
            val summarySp = if (narrow) NARROW_SUMMARY_SP else WIDE_SUMMARY_SP
            val netSize = if (totalAmountFontSize == BillSettingsDao.FontSize.BIG) 20f else 15f
            val llSummary = view.findViewById<LinearLayout>(R.id.llSummary)
            llSummary.removeAllViews()
            // A pre-tax discount reduces the taxable value, so it reads before the
            // tax; a post-tax discount comes off after tax is charged on the full
            // amount, so it reads after the tax total. True of either layout - only
            // where the block sits and what its lines are called differ.
            val showDiscount = totals.totalDiscount > 0.005

            // The account block printed under the totals. On a CREDIT bill it is the
            // running-account breakdown from the customer's side - what they had, this
            // bill, what they paid now, and where that leaves them (positive = in credit,
            // negative = owing), so TOTAL BALANCE = PREVI - BILL + CASH. On any other
            // bill it is just the change given back and the outstanding, when there is
            // any. cust.outstanding is md_customers.balance_amount (positive = owes),
            // which is why it is negated for the customer-side figures.
            val trailer: List<Pair<String, String>> = if (creditSale) {
                val current = cust.outstanding ?: 0.0
                val totalBalance = -current
                val previBalance = totalBalance + payable - cashReceived
                listOf(
                    t("PREVI BALANCE") to money(previBalance),
                    t("BILL AMOUNT") to money(payable),
                    t("CASH RECEIVED") to money(cashReceived),
                    t("TOTAL BALANCE") to money(totalBalance)
                )
            } else buildList {
                if (returnAmount > 0.005) add(t("CHANGE DUE") to money(returnAmount))
                cust.outstanding?.takeIf { it > 0.005 }?.let { add(t("OUTSTANDING") to money(it)) }
            }
            // Which of the trailer's lines is set in bold is decided from the English
            // label, before it was translated - matching on the printed text would
            // stop working the moment the slip stopped being in English.
            val boldTrailer = if (creditSale) setOf(t("TOTAL BALANCE")) else setOf(t("OUTSTANDING"))

            if (classic) {
                // Tax wise short reports its tax in the table above the totals, so
                // the block below them carries none: no line per rate, and no total
                // of them either, since the table's own TOTAL column already states
                // each rate's value with its tax on it.
                if (taxWise) renderTaxWiseTable(view, taxSlabs, isGst, headingSp, narrow)
                renderClassicSummary(
                    llSummary, totals,
                    taxSlabs = if (taxWise) emptyList() else taxSlabs,
                    isGst = isGst, summarySp = summarySp, showTotalTax = !taxWise,
                    showDiscount = showDiscount, discountPreTax = discountPreTax,
                    roundOff = roundOff, showRoundOff = roundOffSetting, narrow = narrow,
                    serviceCharge = serviceCharge, trailer = trailer
                )
                view.findViewById<TextView>(R.id.tvGrandTotalLabel)?.text = "${t("GRAND TOTAL")}:"
                val big = totalAmountFontSize == BillSettingsDao.FontSize.BIG
                val grandSp = when {
                    narrow && big -> CLASSIC_NARROW_GRAND_TOTAL_BIG_SP
                    narrow -> CLASSIC_NARROW_GRAND_TOTAL_SP
                    big -> CLASSIC_GRAND_TOTAL_BIG_SP
                    else -> CLASSIC_GRAND_TOTAL_SP
                }
                view.findViewById<TextView>(R.id.tvGrandTotalLabel)?.textSize = grandSp
                view.findViewById<TextView>(R.id.tvGrandTotal)?.apply {
                    textSize = grandSp
                    text = money(payable)
                }
            } else {
                renderStandardSummary(
                    llSummary, totals, taxSlabs, isGst, summarySp, netSize,
                    showDiscount = showDiscount, discountPreTax = discountPreTax,
                    roundOff = roundOff, showRoundOff = roundOffSetting,
                    payable = payable, narrow = narrow, serviceCharge = serviceCharge,
                    trailer = trailer, boldTrailer = boldTrailer
                )
            }

            // Prefer what the bill stored, so a reprint reads exactly as the original.
            if (amountInWordsSetting) {
                val words = amountInWords?.takeIf { it.isNotBlank() } ?: AmountInWords.of(payable)
                // Classic sets the whole slip in capitals, this line included.
                setIfPresent(view, R.id.tvAmountWords, if (classic) words.uppercase() else words)
            } else {
                view.findViewById<TextView>(R.id.tvAmountWords).visibility = View.GONE
            }

            renderPayment(view, draft?.paymentModes ?: paymentModes(db, receiptNo, billType), narrow)

            renderFixedLines(
                db, view, R.id.llBillFooterLines,
                DatabaseHelper.Tables.MD_FOOTERS, "footer_text", "footer_number", "footer_type",
                headerStoreId
            )

            if (narrow) enlargeBodyForNarrowPaper(view, classic)
            applyPrintTypeface(view)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error loading bill $receiptNo", e)
        }
    }

    /**
     * The Standard totals block: item count / qty / gross, each tax rate on its own
     * line, discount and the totals - "label : value" all the way down to NET AMT.
     */
    private fun renderStandardSummary(
        llSummary: LinearLayout,
        totals: BillTotals,
        taxSlabs: List<TaxSlab>,
        isGst: Boolean,
        summarySp: Float,
        netSize: Float,
        showDiscount: Boolean,
        discountPreTax: Boolean,
        roundOff: Double,
        showRoundOff: Boolean,
        payable: Double,
        narrow: Boolean,
        serviceCharge: Double = 0.0,
        /** Account lines printed under the totals - see where it is built in render(). */
        trailer: List<Pair<String, String>> = emptyList(),
        /** Which of [trailer]'s labels are set in bold, in the print language. */
        boldTrailer: Set<String> = emptySet()
    ) {
        fun row(label: String, value: String, bold: Boolean = false, valueSize: Float = summarySp) {
            llSummary.addView(
                summaryRow(t(label), value, bold, valueSize, labelSize = summarySp, narrow = narrow)
            )
        }

        // AMT is the AMOUNT column's own total - every line's gross - so the
        // customer can add the column up and land on it.
        // "ITEM: n  QTY: q" shares its line with the AMT figure, and on a
        // 2-inch roll at this size the pair does not fit beside it - left to
        // itself it breaks as "QTY:" / "3", splitting a label from its number.
        // Stacked deliberately instead, each count stays with its own label.
        val separator = if (narrow) "\n" else "  "
        val counts = "${t("ITEM")}: ${totals.itemCount}$separator" +
            "${t("QTY")}: ${qtyText(totals.qtyCount)}"
        llSummary.addView(
            summaryHead(counts, "${t("AMT")}: ${money(totals.grossMrp)}", summarySp, narrow)
        )
        if (showDiscount && discountPreTax) row("DISCOUNT", money(totals.totalDiscount))
        taxSlabs.forEach { slab ->
            if (isGst) {
                row("CGST @${rate(slab.cgstRate)}%", money(slab.cgst))
                row("SGST @${rate(slab.sgstRate)}%", money(slab.sgst))
            } else {
                row("VAT @${rate(slab.vatRate)}%", money(slab.vat))
            }
        }
        if (totals.tax > 0.005) row(if (isGst) "TOTAL GST" else "TOTAL VAT", money(totals.tax))
        if (showDiscount && !discountPreTax) row("DISCOUNT", money(totals.totalDiscount))
        row("TOTAL", money(totals.grandTotal))
        if (serviceCharge > 0.005) row("SERVICE CHARGE", money(serviceCharge))
        if (showRoundOff) row("ROUND OFF", money(roundOff))
        row("NET AMT", money(payable), bold = true, valueSize = netSize)
        // The account block under the totals (credit breakdown, or change + outstanding).
        // Its labels arrive already translated, so they go through summaryRow directly
        // rather than through row(), which translates what it is given.
        trailer.forEach { (label, value) ->
            llSummary.addView(
                summaryRow(
                    label, value, bold = label in boldTrailer,
                    valueSize = summarySp, labelSize = summarySp, narrow = narrow
                )
            )
        }
    }

    /**
     * The Classic totals block: tax broken out per rate, then TOTAL TAX, TOTAL
     * AMOUNT and ROUNDED OFF. The payable figure is not part of this block - it is
     * the GRAND TOTAL line set apart below its own rule, which the caller fills.
     *
     * Three things differ from [renderStandardSummary] beyond the labels, and each
     * is what the Classic slip has always shown:
     *
     *  * no item-count/gross header - the block is the tax and the totals, nothing
     *    else;
     *  * rates ascending, the lowest slab first, and each stated to two decimals
     *    ("2.50%", "5.00%") so the column of rates reads straight down;
     *  * SGST above CGST within a slab.
     *
     * Everything conditional stays conditional: a bill with no tax prints no tax
     * lines and no TOTAL TAX, an undiscounted one prints no DISCOUNT, and ROUNDED
     * OFF appears only where Bill Settings asks for it.
     */
    private fun renderClassicSummary(
        llSummary: LinearLayout,
        totals: BillTotals,
        taxSlabs: List<TaxSlab>,
        isGst: Boolean,
        summarySp: Float,
        showTotalTax: Boolean,
        showDiscount: Boolean,
        discountPreTax: Boolean,
        roundOff: Double,
        showRoundOff: Boolean,
        narrow: Boolean,
        serviceCharge: Double = 0.0,
        /** Account lines printed under the totals - see where it is built in render(). */
        trailer: List<Pair<String, String>> = emptyList()
    ) {
        val rows = mutableListOf<Pair<String, String>>()
        fun row(label: String, value: String) { rows.add(t(label) to value) }

        if (showDiscount && discountPreTax) row("DISCOUNT", money(totals.totalDiscount))
        // [loadItems] orders the slabs highest-rate first, the Standard order; the
        // Classic slip lists them the other way up.
        taxSlabs.asReversed().forEach { slab ->
            if (isGst) {
                row("SGST @ ${classicRate(slab.sgstRate)}%", money(slab.sgst))
                row("CGST @ ${classicRate(slab.cgstRate)}%", money(slab.cgst))
            } else {
                row("VAT @ ${classicRate(slab.vatRate)}%", money(slab.vat))
            }
        }
        if (showTotalTax && totals.tax > 0.005) row("TOTAL TAX", money(totals.tax))
        if (showDiscount && !discountPreTax) row("DISCOUNT", money(totals.totalDiscount))
        // Stated before the rounding adjustment, so TOTAL AMOUNT + SERVICE CHARGE +
        // ROUNDED OFF is visibly the GRAND TOTAL below.
        row("TOTAL AMOUNT", money(totals.grandTotal))
        if (serviceCharge > 0.005) row("SERVICE CHARGE", money(serviceCharge))
        if (showRoundOff) row("ROUNDED OFF", money(roundOff))
        // The account block under the totals (credit breakdown, or change + outstanding),
        // added to the rows so its colons line up with the totals above. Its labels
        // are already in the print language, so they skip row()'s translation.
        trailer.forEach { (label, value) -> rows.add(label to value) }

        // The colons line up in a column, and that column sits directly after the
        // longest label rather than at a fixed fraction of the paper: padding to
        // this bill's own widest label is what puts "TOTAL TAX    :" under
        // "SGST @ 2.50%:". Done by padding a monospace string rather than by giving
        // the colon a weighted column of its own, so the label block takes only the
        // width it needs and leaves the rest of a narrow roll to the figures.
        if (lang == PrintLanguage.Language.ENGLISH) {
            val pad = rows.maxOf { it.first.length }
            rows.forEach { (label, value) ->
                llSummary.addView(classicSummaryRow(label.padEnd(pad) + " :", value, summarySp, narrow))
            }
            return
        }
        // Counting characters only aligns anything in a face where every character
        // is the same width, and none of the other scripts is set in one - padded to
        // an equal length, "मूल्य :" and "सेवा शुल्क :" still end in different places.
        // So the label column is measured instead and every label is given that
        // width, which puts the colons in a column whatever the script.
        val paint = measure(summarySp)
        val labelPx = rows.maxOf { paint.measureText("${it.first} :") }.toInt() + 1
        rows.forEach { (label, value) ->
            llSummary.addView(
                classicSummaryRow("$label :", value, summarySp, narrow, labelWidthPx = labelPx)
            )
        }
    }

    /**
     * The Tax wise short tax table: a row per rate, every line taxed at that rate
     * clubbed into it.
     *
     *     TAX%          B.AMT      SGST      CGST     TOTAL
     *     5.00%        118.00      2.95      2.95    123.90
     *     10.00%        56.00      2.80      2.80     61.60
     *
     * TAX% is the *combined* rate the customer was charged - SGST plus CGST, so two
     * halves of 2.50% report as one 5.00% row - because that is the rate the goods
     * are taxed at, and the halves are already given their own columns beside it.
     * B.AMT is the taxable value of those lines and TOTAL is that value with its tax
     * on it, so the TOTAL column adds up to the TOTAL AMOUNT below the table.
     *
     * Under VAT there is no split to report, so the CGST column is dropped and the
     * one beside it is headed VAT. A bill with no tax at all prints no table -
     * headings and rules included.
     */
    private fun renderTaxWiseTable(
        view: View,
        taxSlabs: List<TaxSlab>,
        isGst: Boolean,
        sizeSp: Float,
        narrow: Boolean
    ) {
        val table = view.findViewById<LinearLayout>(R.id.llTaxTable) ?: return
        val rows = view.findViewById<LinearLayout>(R.id.llTaxRows) ?: return
        rows.removeAllViews()

        if (taxSlabs.isEmpty()) {
            table.visibility = View.GONE
            return
        }
        table.visibility = View.VISIBLE

        val columns = if (narrow) TAX_WISE_NARROW_COLUMNS else TAX_WISE_COLUMNS
        val headings = listOf(
            R.id.tvTaxColRate, R.id.tvTaxColBase, R.id.tvTaxColSgst,
            R.id.tvTaxColCgst, R.id.tvTaxColTotal
        )
        headings.forEachIndexed { i, id ->
            view.findViewById<TextView>(id).let { heading ->
                heading.textSize = sizeSp
                (heading.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = columns[i]
                    heading.layoutParams = lp
                }
            }
        }
        // The rate and the two money columns are words; SGST and CGST are statutory
        // short forms and print as themselves in every language.
        view.findViewById<TextView>(R.id.tvTaxColRate).text = t("TAX%")
        view.findViewById<TextView>(R.id.tvTaxColBase).text = t("B.AMT")
        view.findViewById<TextView>(R.id.tvTaxColTotal).text = t("TOTAL")
        // Under VAT the tax is not split, so the second tax column goes and the
        // first is headed for what it holds.
        view.findViewById<TextView>(R.id.tvTaxColSgst).text = if (isGst) "SGST" else "VAT"
        view.findViewById<TextView>(R.id.tvTaxColCgst).visibility =
            if (isGst) View.VISIBLE else View.GONE

        // Lowest rate first, as the Classic slip lists its own tax lines.
        taxSlabs.asReversed().forEach { slab ->
            val row = classicRow(narrow)
            val rate = if (isGst) slab.cgstRate + slab.sgstRate else slab.vatRate
            row.addView(cell("${classicRate(rate)}%", columns[0], Gravity.START, sizeSp))
            row.addView(cell(money(slab.base), columns[1], Gravity.END, sizeSp))
            row.addView(
                cell(money(if (isGst) slab.sgst else slab.vat), columns[2], Gravity.END, sizeSp)
            )
            if (isGst) row.addView(cell(money(slab.cgst), columns[3], Gravity.END, sizeSp))
            row.addView(cell(money(slab.base + slab.tax), columns[4], Gravity.END, sizeSp))
            rows.addView(row)
        }
    }

    /**
     * One Classic totals line: the label with its colon already aligned into it, and
     * the figure right against the far edge.
     *
     * The label takes only the width it needs - unlike [summaryRow], which splits the
     * line into weighted columns. On a 2-inch roll a weighted label column is wider
     * than the label and narrower than it needs to be at once, so "SGST @ 2.50%"
     * breaks after the "@" while half the line sits empty.
     */
    private fun classicSummaryRow(
        label: String,
        value: String,
        sizeSp: Float,
        narrow: Boolean,
        /** A measured label column, for the scripts that cannot be aligned by padding. */
        labelWidthPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    ): View {
        val row = summaryRowContainer(narrow)
        row.addView(TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                labelWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = label
            typeface = billTypeface
            textSize = sizeSp
            setTextColor(0xFF222222.toInt())
        })
        row.addView(summaryCell(value, 1f, Gravity.END, bold = false, size = sizeSp))
        return row
    }

    /**
     * One line's priced figures, whether they were read back from `td_bill_items` or
     * priced from a [Draft] that has not been written yet. Everything the receipt
     * prints is derived from these, so both sources produce an identical bill.
     */
    private data class RawLine(
        val name: String,
        val qty: Double,
        val rate: Double,
        val subtotal: Double,
        val itemTotal: Double,
        val discountAmount: Double,
        val cgst: Double,
        val sgst: Double,
        val vat: Double,
        val cgstRate: Double,
        val sgstRate: Double,
        val vatRate: Double,
        val hsn: String?,
        val unit: String? = null
    )

    /**
     * Reads the stored lines of a saved bill.
     *
     * [includeHsn] mirrors the Bill Settings "HSN Code" toggle: the column is only
     * fetched and printed when it is on. Each figure is taken at the paisa it prints
     * at, so the receipt's totals are summed from the same numbers the lines show -
     * bills written before that was stored this way are brought to the same footing
     * here, rather than reprinting a total a paisa off its own lines.
     */
    private fun readRawLines(db: SQLiteDatabase, receiptNo: Long, includeHsn: Boolean): List<RawLine> {
        val raws = mutableListOf<RawLine>()
        db.rawQuery(
            """
            SELECT i.product_id, i.quantity, i.rate, i.item_subtotal, i.item_total, p.product_name,
                   i.discount_amount, i.cgst_amount, i.sgst_amount, i.igst_amount, i.vat_amount, p.hsn_code,
                   i.cgst_rate, i.sgst_rate, i.vat_rate,
                   COALESCE(
                       (SELECT u.unit_symbol FROM ${DatabaseHelper.Tables.MD_UNITS} u
                         WHERE u.id = i.unit_id),
                       (SELECT u.unit_symbol FROM ${DatabaseHelper.Tables.MD_UNITS} u
                          JOIN ${DatabaseHelper.Tables.MD_PRODUCT_RATES} r ON r.unit_id = u.id
                         WHERE r.product_id = i.product_id
                         ORDER BY r.id ASC LIMIT 1)
                   )
            FROM ${itemsTableFor(db, receiptNo)} i
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON i.product_id = p.id
            WHERE i.bill_id = ?
            ORDER BY i.id ASC
            """.trimIndent(),
            arrayOf(receiptNo.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val qty = c.getDouble(1)
                val rate = c.getDouble(2)
                val subtotal = BillRounding.toPaise(if (c.isNull(3)) rate * qty else c.getDouble(3))
                raws.add(
                    RawLine(
                        name = c.getString(5)?.takeIf { it.isNotBlank() } ?: "Item",
                        qty = qty,
                        rate = rate,
                        subtotal = subtotal,
                        itemTotal = BillRounding.toPaise(if (c.isNull(4)) subtotal else c.getDouble(4)),
                        discountAmount = c.getDouble(6),
                        cgst = BillRounding.toPaise(c.getDouble(7)),
                        sgst = BillRounding.toPaise(c.getDouble(8)),
                        vat = BillRounding.toPaise(c.getDouble(10)),
                        cgstRate = c.getDouble(12),
                        sgstRate = c.getDouble(13),
                        vatRate = c.getDouble(14),
                        hsn = if (includeHsn) c.getString(11)?.takeIf { it.isNotBlank() } else null,
                        // Read whatever the sale recorded, falling back to the
                        // product's own unit. Printed beside the quantity on every
                        // template, and nothing is printed where there is nothing.
                        unit = c.getString(15)?.takeIf { it.isNotBlank() }?.uppercase()
                    )
                )
            }
        }
        return raws
    }

    /**
     * Prices a [Draft]'s lines the way they will be written, through the same
     * [BillPricing] the DAO uses - so the preview cannot quote a line the sale will
     * not go on to charge.
     */
    private fun draftRawLines(
        draft: Draft,
        includeHsn: Boolean,
        regime: GstCalculator.TaxRegime,
        inclusive: Boolean,
        discountPreTax: Boolean
    ): List<RawLine> = draft.items.map { item ->
        val priced = BillPricing.price(
            rate = item.rate,
            quantity = item.quantity,
            cgstRate = item.cgstRate,
            sgstRate = item.sgstRate,
            vatRate = item.vatRate,
            discountAmount = item.discountAmount,
            regime = regime,
            inclusive = inclusive,
            discountPreTax = discountPreTax
        )
        RawLine(
            name = item.name,
            qty = item.quantity,
            rate = item.rate,
            subtotal = priced.subtotal,
            itemTotal = priced.itemTotal,
            discountAmount = item.discountAmount,
            cgst = priced.cgst,
            sgst = priced.sgst,
            vat = priced.vat,
            cgstRate = item.cgstRate,
            sgstRate = item.sgstRate,
            vatRate = item.vatRate,
            hsn = if (includeHsn) item.hsn?.takeIf { it.isNotBlank() } else null,
            unit = item.unit?.takeIf { it.isNotBlank() }?.uppercase()
        )
    }

    /** Turns priced lines into the printed rows, the receipt totals and the tax
     *  slabs, in one pass. */
    private fun loadItems(raws: List<RawLine>, inclusive: Boolean): Triple<List<BillItem>, BillTotals, List<TaxSlab>> {
        val list = mutableListOf<BillItem>()
        var subtotalSum = 0.0
        var cgstSum = 0.0
        var sgstSum = 0.0
        var vatSum = 0.0
        var itemDiscountSum = 0.0
        var itemDiscountListedSum = 0.0
        var grossSum = 0.0
        var qtySum = 0.0
        // Taxed base/tax grouped by combined rate (scaled x100 for a clean key), so
        // the summary prints one line per distinct rate rather than one blended row.
        // Each entry holds [cgstRate, sgstRate, vatRate, base, cgst, sgst, vat].
        val slabs = LinkedHashMap<Long, DoubleArray>()
        run {
            raws.forEach { raw ->
                val qty = raw.qty
                val rate = raw.rate
                val subtotal = raw.subtotal
                val itemTotal = raw.itemTotal
                val name = raw.name
                val cgstAmt = raw.cgst
                val sgstAmt = raw.sgst
                val vatAmt = raw.vat

                // The tax block still needs each line's taxable (pre-tax) base, net
                // of its discount share - recovered from what was stored rather than
                // re-derived, so it can never drift from item_total, which already
                // accounts for inclusive/exclusive pricing and however the discount
                // was applied.
                val lineNet = (itemTotal - cgstAmt - sgstAmt - vatAmt).coerceAtLeast(0.0)
                subtotalSum += lineNet
                cgstSum += cgstAmt
                sgstSum += sgstAmt
                vatSum += vatAmt
                grossSum += subtotal
                qtySum += qty

                val cgstRate = raw.cgstRate
                val sgstRate = raw.sgstRate
                val vatRate = raw.vatRate

                // Bucket this line into its rate slab so the summary can report each
                // rate on its own line. Only taxed lines form a slab - an exempt
                // (0%) line contributes no tax line.
                if (cgstAmt + sgstAmt + vatAmt > 0.0) {
                    val key = Math.round((cgstRate + sgstRate + vatRate) * 100.0)
                    val acc = slabs.getOrPut(key) { DoubleArray(7) }
                    acc[0] = cgstRate
                    acc[1] = sgstRate
                    acc[2] = vatRate
                    acc[3] += lineNet
                    acc[4] += cgstAmt
                    acc[5] += sgstAmt
                    acc[6] += vatAmt
                }

                // The discount the customer got, stated against the line's listed
                // price - the terms it was set in - so a "3% off 100" reads as 3.00
                // whichever way the price is taxed. That means comparing like with
                // like: an inclusive price already carries tax, so it is measured
                // against the line total; an exclusive one is measured pre-tax,
                // against the taxable net. Taking the drop in the tax-inclusive
                // total instead would gross an exclusive 3.00 up to 3.15.
                //
                // Both sides come from the stored figures, so the column still
                // reconciles with the totals rather than being re-derived from the
                // configured rate.
                val lineDiscount = raw.discountAmount
                val lineNetListed = if (inclusive) itemTotal else lineNet
                val lineDiscountListed = (subtotal - lineNetListed).coerceAtLeast(0.0)
                itemDiscountSum += lineDiscount
                itemDiscountListedSum += lineDiscountListed
                val hsn = raw.hsn
                val disc = if (lineDiscountListed > 0.005) money(lineDiscountListed) else null

                // The printed NET AMT column is the line's gross - quantity x listed
                // price - less the DISC beside it, so the two columns read together
                // and the summary chains: AMT (the gross the lines add up to) less
                // DISCOUNT, plus the tax below it, reaches the same TOTAL.
                list.add(
                    BillItem(
                        sr = list.size + 1,
                        // Respelled in the print language's script where there is one -
                        // the name the shop typed, in letters the customer can read.
                        // See [Transliterator] for what that does and does not mean.
                        name = productName(name),
                        qty = qtyText(qty),
                        price = money(rate),
                        netAmount = money(lineNetListed),
                        hsn = hsn,
                        discount = disc,
                        unit = raw.unit
                    )
                )
            }
        }
        val totals = BillTotals(
            itemsSubtotal = subtotalSum,
            cgst = cgstSum,
            sgst = sgstSum,
            vat = vatSum,
            itemDiscountApplied = itemDiscountSum,
            itemDiscountListed = itemDiscountListedSum,
            grossMrp = grossSum,
            qtyCount = qtySum,
            itemCount = list.size
        )
        // Highest rate first, the usual order on a tax invoice.
        val taxSlabs = slabs.entries
            .sortedByDescending { it.key }
            .map { (_, acc) -> TaxSlab(acc[0], acc[1], acc[2], acc[3], acc[4], acc[5], acc[6]) }
        return Triple(list, totals, taxSlabs)
    }

    /**
     * Draws the configured bill logos at the head and foot of the receipt.
     *
     * Decoded at a modest size: the receipt card is [CARD_WIDTH_DP] wide and the slots cap
     * out well below that, so pushing a full-resolution image through would cost
     * memory for pixels nobody sees. The most recently added logo of each type
     * wins, which is what an operator replacing an old one expects.
     */
    private fun renderLogos(view: View) {
        val dao = LogoDao(ctx)
        listOf(
            LogoDao.LogoType.BILL_HEADER to R.id.ivBillHeaderLogo,
            LogoDao.LogoType.BILL_FOOTER to R.id.ivBillFooterLogo
        ).forEach { (type, viewId) ->
            val target = view.findViewById<android.widget.ImageView>(viewId)
            val bitmap = dao.getAll(listOf(type)).lastOrNull()?.image
                ?.takeIf { it.isNotEmpty() }
                ?.let { ImageUtils.decodeThumb(it, LOGO_PX) }

            if (bitmap == null) {
                target.setImageDrawable(null)
                target.visibility = View.GONE
            } else {
                target.setImageBitmap(bitmap)
                target.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Prints the operator's caption lines at the head of the slip.
     *
     * BILL and DUPLICATE are alternatives, not layers: a slip is either an original
     * or a copy of one, so a duplicate is captioned DUPLICATE *instead of* BILL.
     * CREDIT is a separate question - how the sale was settled - so it joins
     * whichever of the two applies.
     *
     * A till with no captions set up prints none and the block takes no height,
     * which is what keeps this out of the way of anyone who does not use it.
     */
    private fun renderCaptions(view: View, creditSale: Boolean, duplicate: Boolean) {
        val container = view.findViewById<LinearLayout>(R.id.llBillCaptions) ?: return
        container.removeAllViews()

        val types = buildList {
            add(if (duplicate) CaptionDao.Type.DUPLICATE else CaptionDao.Type.BILL)
            if (creditSale) add(CaptionDao.Type.CREDIT)
        }
        val captions = runCatching { CaptionDao(ctx).enabledFor(types) }.getOrDefault(emptyList())
        if (captions.isEmpty()) return

        val density = ctx.resources.displayMetrics.density
        captions.forEach { caption ->
            container.addView(TextView(ctx).apply {
                text = caption.text
                gravity = Gravity.CENTER
                textSize = caption.fontSize.sp
                setTypeface(billTypeface, if (caption.bold) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(0xFF111111.toInt())
                setPadding(0, (2 * density).toInt(), 0, 0)
            })
        }
        // Breathing room either side, so the captions read as their own block
        // between the header above and the bill below - but only when there is
        // something there to space out.
        val gap = (6 * density).toInt()
        container.setPadding(0, gap, 0, gap)
    }

    /**
     * Sets the slip's single-column lines to [NARROW_BODY_SP] on a 2-inch roll.
     *
     * Listed by id rather than swept for by current size, so adding a line to the
     * layout is a deliberate decision to include or leave out rather than something
     * that silently changes size because it happened to be set at 13sp. The store
     * header and the separator rules are left alone: the header is already sized to
     * stand out, and a wider rule only overflows the card sooner.
     */
    private fun enlargeBodyForNarrowPaper(view: View, classic: Boolean = false) {
        // Classic puts the bill number, date and time on one line, so those three
        // are the one part of the body a narrow roll has to set *smaller* rather
        // than larger - enlarged, the date wraps under the bill number.
        val head = listOf(R.id.tvDate, R.id.tvTime, R.id.tvBillNo)
        head.forEach {
            view.findViewById<TextView>(it)?.textSize =
                if (classic) CLASSIC_NARROW_HEAD_SP else NARROW_BODY_SP
        }
        listOf(
            R.id.tvCustMobile, R.id.tvName, R.id.tvCustGstin,
            R.id.tvCustAddress, R.id.tvCustOutstanding,
            R.id.tvAmountWords, R.id.tvBillCreatedBy
        ).forEach { view.findViewById<TextView>(it)?.textSize = NARROW_BODY_SP }

        // The payment rows are built in code, so they are not in the list above.
        view.findViewById<LinearLayout>(R.id.llBillPayment)?.let { block ->
            (0 until block.childCount)
                .mapNotNull { block.getChildAt(it) as? LinearLayout }
                .forEach { row ->
                    (0 until row.childCount).forEach { i ->
                        (row.getChildAt(i) as? TextView)?.textSize = NARROW_BODY_SP
                    }
                }
        }
    }

    /**
     * One line of the customer block: "MOBILE : 9800000000".
     *
     * [padded] is the English label with the spaces that line the colons up in a
     * monospace face, and it is used exactly as it is so an English slip prints
     * character for character as it always has. A translated label is set from [key]
     * and left unpadded: its script is not monospace, so padding it out to the same
     * number of characters would line nothing up and only cost width on the roll.
     */
    private fun custLine(padded: String, key: String, value: String): String =
        (if (lang == PrintLanguage.Language.ENGLISH) padded else t(key)) + ": $value"

    /**
     * Re-sets every line of the slip in the face the print language needs.
     *
     * The layouts name Roboto Mono in their own XML, which the cells built in code
     * cannot reach: setting [billTypeface] on those alone would leave the bill
     * number, the store block and the GRAND TOTAL line still asking for a font with
     * no Devanagari, Tamil or Bengali in it, and those are exactly the lines an
     * operator would notice printing as empty boxes.
     *
     * Nothing happens on an English slip. It already has the face it was laid out
     * against, and walking the tree to set it again could only change something.
     */
    private fun applyPrintTypeface(root: View) {
        if (lang == PrintLanguage.Language.ENGLISH) return
        when (root) {
            // Bold stays bold: the style is read off what the view already has, so
            // only the family is replaced.
            is TextView -> root.setTypeface(billTypeface, root.typeface?.style ?: Typeface.NORMAL)
            is ViewGroup -> for (i in 0 until root.childCount) applyPrintTypeface(root.getChildAt(i))
        }
    }

    /** Fills a receipt line, or hides it when there is nothing to print there. */
    private fun setIfPresent(root: View, id: Int, value: String?) {
        val tv = root.findViewById<TextView>(id)
        if (value.isNullOrBlank()) {
            tv.visibility = View.GONE
        } else {
            tv.text = value
            tv.visibility = View.VISIBLE
        }
    }

    /**
     * Renders the operator's configured header or footer lines.
     *
     * Both tables have the same shape - numbered, ordered, individually enabled,
     * and typed BILL or KOT - so one routine serves each end of the receipt. Only
     * BILL lines are printed here; KOT lines belong on a kitchen ticket.
     */
    private fun renderFixedLines(
        db: SQLiteDatabase,
        root: View,
        containerId: Int,
        table: String,
        textColumn: String,
        numberColumn: String,
        typeColumn: String,
        storeId: Long? = null
    ) {
        val container = root.findViewById<LinearLayout>(containerId)
        container.removeAllViews()
        // Header/footer tables are store-scoped; without this filter a line set up for
        // another store prints on this store's slip too - the same header twice.
        val storeClause = if (storeId != null) " AND (store_id = ? OR store_id IS NULL)" else ""
        val args = if (storeId != null) arrayOf(storeId.toString()) else null
        db.rawQuery(
            """
            SELECT $textColumn, font_size, is_bold FROM $table
            WHERE is_enabled = 1 AND ($typeColumn IS NULL OR $typeColumn = 'BILL')$storeClause
            ORDER BY $numberColumn ASC
            """.trimIndent(),
            args
        ).use { c ->
            while (c.moveToNext()) {
                val text = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                // Size and weight come from the master; an unrecognised size falls
                // back to MEDIUM rather than silently printing at the wrong scale.
                val size = BillHeaderFooterDao.FontSize.fromStored(c.getString(1))
                val bold = c.getInt(2) == 1
                container.addView(TextView(ctx).apply {
                    // Full width, so a line too long for the roll wraps onto the next
                    // one as a centred block. Left to wrap_content it is centred line
                    // by line inside a box only as wide as its longest line, which on
                    // a narrow roll leaves the second line sitting off to one side.
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    this.text = text
                    gravity = Gravity.CENTER
                    textSize = size.sp
                    setTypeface(billTypeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(0xFF333333.toInt())
                    setPadding(0, (2 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
                })
            }
        }
    }

    /** Mobile, name, GSTIN and address as captured on the sale - each nullable, printed
     *  only where the Bill Settings "Customer Details" / "Customer Address Printing"
     *  toggles call for it and the sale actually captured it. */
    private data class CustomerInfo(
        val name: String?,
        val phone: String?,
        val gstin: String?,
        val address: String?,
        /**
         * What the customer owes in total, `md_customers.balance_amount`. Null when
         * the sale is not attached to a master record, which is where the figure
         * lives - a walk-in has no running account to report.
         */
        val outstanding: Double? = null
    )

    /**
     * Resolves the sale's customer details: the customer master first (it is the
     * more complete, more current record), falling back to whatever was typed into
     * the payment for a walk-in with no master record. The address only ever comes
     * from the master - a payment row has nowhere to store one.
     */
    private fun loadCustomerInfo(db: SQLiteDatabase, customerId: Long?, receiptNo: Long): CustomerInfo {
        var name: String? = null
        var phone: String? = null
        var gstin: String? = null
        var address: String? = null
        var outstanding: Double? = null

        if (customerId != null) {
            db.query(
                DatabaseHelper.Tables.MD_CUSTOMERS,
                arrayOf("customer_name", "phone_number", "gstin", "customer_address", "balance_amount"),
                "id=?", arrayOf(customerId.toString()), null, null, null, "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)?.takeIf { it.isNotBlank() }
                    phone = c.getString(1)?.takeIf { it.isNotBlank() }
                    gstin = c.getString(2)?.takeIf { it.isNotBlank() }
                    address = c.getString(3)?.takeIf { it.isNotBlank() }
                    // Read after the sale is written, so this bill's own unpaid
                    // amount is already in it - see [recordBalanceDue].
                    outstanding = if (c.isNull(4)) 0.0 else c.getDouble(4)
                }
            }
        }

        if (name == null || phone == null || gstin == null) {
            db.query(
                DatabaseHelper.Tables.TD_PAYMENTS,
                arrayOf("cust_name", "cust_phone", "cust_gstin"),
                "bill_id=?", arrayOf(receiptNo.toString()), null, null, "id ASC", "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    if (name == null) name = c.getString(0)?.takeIf { it.isNotBlank() }
                    if (phone == null) phone = c.getString(1)?.takeIf { it.isNotBlank() }
                    if (gstin == null) gstin = c.getString(2)?.takeIf { it.isNotBlank() }
                }
            }
        }

        return CustomerInfo(name?.uppercase(), phone, gstin, address?.uppercase(), outstanding)
    }

    /**
     * Prints how the bill was paid. A sale can be settled in more than one payment,
     * so every row recorded against the bill gets a line. If nothing was recorded -
     * a credit sale is billed now and collected later - the bill's own type stands
     * in, so the receipt never goes out with the payment silently blank.
     */
    private fun paymentModes(db: SQLiteDatabase, receiptNo: Long, billType: String?): List<String> {
        val modes = mutableListOf<String>()
        db.rawQuery(
            """
            SELECT payment_mode FROM ${DatabaseHelper.Tables.TD_PAYMENTS}
            WHERE bill_id = ? ORDER BY id ASC
            """.trimIndent(),
            arrayOf(receiptNo.toString())
        ).use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { modes.add(it.uppercase()) }
            }
        }
        if (modes.isEmpty()) billType?.takeIf { it.isNotBlank() }?.let { modes.add(it.uppercase()) }
        return modes
    }

    private fun renderPayment(
        view: View, modes: List<String>, narrow: Boolean = false
    ) {
        val ll = view.findViewById<LinearLayout>(R.id.llBillPayment)
        ll.removeAllViews()
        if (modes.isEmpty()) return

        modes.forEach { mode ->
            val row = baseRow(narrow)
            row.addView(cell(t("PAY MODE"), 1f, Gravity.START))
            // The mode itself is one of a handful of known words - CASH, CARD,
            // CREDIT - so it is translated too where it is one of them, and left as
            // it was recorded where it is not.
            row.addView(cell(t(mode), 1f, Gravity.END))
            ll.addView(row)
        }
        // The change handed back (RETURN) now prints with the totals - see the summary.
    }

    /**
     * Login id of the operator who generated the bill. Resolved from the bill's own
     * `operator_id` rather than the current session, so reprinting an older bill
     * still credits whoever actually rang it up. Falls back to `created_by`, the
     * login id stamped on the row, which is all that survives if that operator has
     * since been removed from md_users.
     */
    private fun cashierName(db: SQLiteDatabase, operatorId: Long?, createdBy: String?): String {
        if (operatorId != null) {
            db.query(
                DatabaseHelper.Tables.MD_USERS, arrayOf("user_id", "user_name"),
                "id=?", arrayOf(operatorId.toString()), null, null, null, "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    val id = c.getString(0)?.takeIf { it.isNotBlank() }
                        ?: c.getString(1)?.takeIf { it.isNotBlank() }
                    if (id != null) return id.uppercase()
                }
            }
        }
        return createdBy?.takeIf { it.isNotBlank() }?.uppercase() ?: "---"
    }

    /**
     * A row of a Classic table - a line item or a line of the tax table.
     *
     * Set tighter than [baseRow]: these rows are read as a block down the page, and
     * the space that keeps two payment lines apart only spreads a table out.
     */
    private fun classicRow(narrow: Boolean): LinearLayout {
        val density = ctx.resources.displayMetrics.density
        val gap = 1.5f * density * (if (narrow) NARROW_ROW_SPACING else 1f)
        return LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap.toInt(), 0, gap.toInt())
        }
    }

    /**
     * A line item: serial and name, the quantity with its unit, the unit price and
     * the amount, on one row under the headings - or on two, where the name is too
     * long for one.
     *
     * Every template's item table is built here. It is named for the Classic slip
     * because that is the shape it takes, and Standard and Tax Wise Short now print
     * their lines the same way.
     *
     * [columnPx] and [sizeSp] are the widths and the size the headings were laid out
     * to, passed in rather than restated so a figure cannot end up in a different
     * column, or at a different size, from the label above it.
     *
     * A name of more than [CLASSIC_NAME_MAX_CHARS] takes a line to itself, whole and
     * unbroken, and the figures drop to the line beneath - still in their own
     * columns, still under their own headings. Short names keep the old single row.
     * Before this, a name too long for its column wrapped inside it, breaking the
     * product's name across two or three lines and leaving the figures floating
     * beside a ragged block of text. A name is the thing on a bill a customer
     * actually checks, so it is the thing that gets the room.
     *
     * HSN, when Bill Settings asks for it, goes underneath the name either way.
     */
    private fun buildClassicItemRow(
        item: BillItem,
        showDisc: Boolean,
        sizeSp: Float,
        columnPx: IntArray,
        narrow: Boolean
    ): View {
        val heading = "${item.sr} ${item.name}"

        /** The figures, in their columns - the same cells whichever line they land on. */
        fun addFigures(row: LinearLayout) {
            ITEM_FIGURE_COLUMNS.filter { showDisc || it != DISC_COLUMN }.forEach { i ->
                row.addView(
                    figureCell(
                        itemCellText(item, i), columnPx[i],
                        if (i == QTY_COLUMN) Gravity.CENTER else Gravity.END, sizeSp
                    )
                )
            }
        }

        // Ten characters or fewer share the line with the figures; anything longer
        // takes a line of its own - and so does a shorter name that still will not
        // fit the column on this paper, see [CLASSIC_NAME_MAX_CHARS].
        val sharesTheLine = item.name.length <= CLASSIC_NAME_MAX_CHARS &&
            measure(sizeSp).measureText(heading) <= columnPx[0]

        if (sharesTheLine) {
            val row = classicRow(narrow)
            val name = buildString {
                append(heading)
                if (item.hsn != null) append("\nHSN: ${item.hsn}")
            }
            row.addView(nameCell(name, columnPx[0], sizeSp))
            addFigures(row)
            return row
        }

        // Spaced as one row would be, so the two lines read as one item rather than
        // as an item and a stray line of figures.
        val gap = (1.5f * ctx.resources.displayMetrics.density *
            (if (narrow) NARROW_ROW_SPACING else 1f)).toInt()
        return LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(0, gap, 0, gap)

            addView(fullWidthLine(heading, sizeSp))
            if (item.hsn != null) addView(fullWidthLine("HSN: ${item.hsn}", sizeSp))

            addView(
                LinearLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    orientation = LinearLayout.HORIZONTAL
                    // The name column is held open and empty, so the figures stay
                    // under the headings instead of sliding left into its place.
                    addView(nameCell("", columnPx[0], sizeSp))
                    addFigures(this)
                }
            )
        }
    }

    /**
     * A figure cell of the item table, at the width [itemColumnWidths] settled on.
     *
     * Held to one line. The width was measured to hold the value, so there is nothing
     * to wrap - and if a figure ever did outrun its column, breaking it across two
     * lines is the one thing it must not do: a quantity split as "1.0" over "0" reads
     * as a different quantity.
     *
     * One line is set with [TextView.setMaxLines], never `isSingleLine`. They read as
     * the same instruction and are not: `isSingleLine` also turns on horizontal
     * scrolling, and a scrolling TextView with a fixed width and no ellipsize lays
     * its text out at its natural width and then scrolls it - straight out of the
     * cell. Every figure on the bill printed blank, in a cell of exactly the right
     * width, holding exactly the right text.
     */
    private fun figureCell(text: String, widthPx: Int, gravity: Int, sizeSp: Float): TextView =
        TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
            this.text = text
            this.gravity = gravity
            typeface = billTypeface
            textSize = sizeSp
            maxLines = 1
            setTextColor(0xFF222222.toInt())
        }

    /**
     * The name cell of a row that shares its line with the figures.
     *
     * Wrapping is left on here alone: HSN sits under the name in this cell on a line
     * of its own, and a name only lands here when it was measured to fit.
     */
    private fun nameCell(text: String, widthPx: Int, sizeSp: Float): TextView =
        TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
            this.text = text
            gravity = Gravity.START
            typeface = billTypeface
            textSize = sizeSp
            setTextColor(0xFF222222.toInt())
        }

    /**
     * One line of the item block running the whole width of the paper.
     *
     * Kept to a single line on purpose: the point of giving the name its own line is
     * that it stops being broken up, so it is cut at the edge rather than wrapped if
     * it somehow outruns the whole roll as well.
     */
    private fun fullWidthLine(text: String, sizeSp: Float): TextView = TextView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        this.text = text
        gravity = Gravity.START
        typeface = billTypeface
        textSize = sizeSp
        // maxLines, not isSingleLine - see [figureCell] for what that costs.
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(0xFF222222.toInt())
    }

    /** A monospace paint at [sizeSp], for measuring what a column has to hold. */
    private fun measure(sizeSp: Float): Paint = Paint().apply {
        typeface = billTypeface
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sizeSp, ctx.resources.displayMetrics
        )
    }

    /** What column [column] of the item table holds for [item]. */
    private fun itemCellText(item: BillItem, column: Int): String = when (column) {
        // The unit trails the quantity - "1 PKT", "1.00 LTR" - and is simply left
        // off where the sale did not record one, rather than assuming pieces.
        QTY_COLUMN -> item.unit?.let { "${item.qty} $it" } ?: item.qty
        PRICE_COLUMN -> item.price
        DISC_COLUMN -> item.discount ?: "-"
        else -> item.netAmount
    }

    /**
     * The width in pixels each of the item table's five columns is given.
     *
     * The weights in [columns] are the starting point, not the answer. A weighted
     * column cannot be trusted with a number: give it less width than the figure in
     * it and Android wraps the figure mid-value - "1.00" printing as "1.0" with the
     * last zero tucked underneath, or "125000." over "00". On a bill that is not a
     * cosmetic problem, and it is why the widths are settled here and handed to the
     * headings and the rows alike rather than each being weighted separately.
     *
     * So every figure column is measured against the widest value the bill actually
     * puts in it - and against its own heading, which can be wider than any of them -
     * and widened to fit where the weight alone would not. A gutter goes on top, so a
     * right-aligned figure cannot end up touching the column before it.
     *
     * The name column pays for whatever the figures borrow. It is the one column that
     * can afford to: its content moves to a line of its own when it will not fit
     * (see [CLASSIC_NAME_MAX_CHARS]), where a figure has nowhere else to go. It keeps
     * a floor of a few characters so a bill of very wide figures still shows the
     * start of each name rather than a column of nothing.
     */
    private fun itemColumnWidths(
        items: List<BillItem>,
        showDisc: Boolean,
        sizeSp: Float,
        columns: FloatArray,
        paperDots: Int,
        headings: List<String>
    ): IntArray {
        val metrics = ctx.resources.displayMetrics
        val widthDp = CARD_WIDTH_DP.toDouble() * paperDots / REFERENCE_PAPER_DOTS
        val contentPx = ((widthDp - CARD_PADDING_DP * 2) * metrics.density).toFloat()

        val paint = measure(sizeSp)
        val gutter = paint.measureText("0")

        val drawn = columns.indices.filter { showDisc || it != DISC_COLUMN }
        val totalWeight = drawn.map { columns[it] }.sum()
        val width = FloatArray(columns.size)
        if (totalWeight <= 0f) return IntArray(columns.size)
        drawn.forEach { width[it] = contentPx * columns[it] / totalWeight }

        var borrowed = 0f
        drawn.filter { it != 0 }.forEach { i ->
            val widest = items.maxOfOrNull { paint.measureText(itemCellText(it, i)) } ?: 0f
            val needed = maxOf(widest, paint.measureText(headings.getOrElse(i) { "" })) + gutter
            if (needed > width[i]) {
                borrowed += needed - width[i]
                width[i] = needed
            }
        }
        width[0] = (width[0] - borrowed).coerceAtLeast(paint.measureText("0").times(NAME_COLUMN_MIN_CHARS))

        // Never wider than the paper. If the name column hit its floor before the
        // figures were paid for in full, the shortfall comes back off the figures in
        // proportion - a row wider than the roll would push the amount off the right
        // edge, and the amount is the one figure that has to print whatever else does
        // not. A bill that reaches this is one whose figures will not fit the paper at
        // this type size at all.
        val total = drawn.map { width[it] }.sum()
        if (total > contentPx) {
            val figures = drawn.filter { it != 0 }
            val figuresPx = figures.map { width[it] }.sum()
            if (figuresPx > 0f) {
                val excess = total - contentPx
                figures.forEach { width[it] -= excess * width[it] / figuresPx }
            }
        }

        return IntArray(columns.size) { width[it].toInt() }
    }

    /** A summary line without a colon column: left label and a right-aligned value
     *  (used for the "ITEM: n QTY: q ... AMT: x" header of the summary block). */
    private fun summaryHead(
        left: String,
        right: String,
        sizeSp: Float = WIDE_SUMMARY_SP,
        narrow: Boolean = false
    ): View {
        val row = summaryRowContainer(narrow)
        row.addView(summaryCell(left, 1f, Gravity.START, bold = true, size = sizeSp))
        row.addView(summaryCell(right, 1f, Gravity.END, bold = true, size = sizeSp))
        return row
    }

    /** A "label : value" summary line, colons aligned in their own thin column. */
    private fun summaryRow(
        label: String,
        value: String,
        bold: Boolean = false,
        valueSize: Float = WIDE_SUMMARY_SP,
        labelSize: Float = WIDE_SUMMARY_SP,
        narrow: Boolean = false
    ): View {
        val row = summaryRowContainer(narrow)
        row.addView(summaryCell(label, 3f, Gravity.START, bold, labelSize))
        row.addView(summaryCell(":", 0.4f, Gravity.START, bold, labelSize))
        row.addView(summaryCell(value, 3f, Gravity.END, bold, valueSize))
        return row
    }

    private fun summaryRowContainer(narrow: Boolean = false): LinearLayout {
        val density = ctx.resources.displayMetrics.density
        val gap = 1 * density * (if (narrow) NARROW_ROW_SPACING else 1f)
        return LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, gap.toInt(), 0, gap.toInt())
        }
    }

    private fun summaryCell(text: String, weight: Float, gravity: Int, bold: Boolean, size: Float): TextView =
        TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            this.text = text
            this.gravity = gravity
            setTypeface(billTypeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textSize = size
            setTextColor(0xFF222222.toInt())
        }

    /** A tax rate trimmed of a needless ".00" - "5" not "5.00", but "2.50" kept. */
    private fun rate(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.2f", v)

    /**
     * A tax rate for the Classic slip, always to two decimals.
     *
     * Unlike [rate], nothing is trimmed: the rates stack one under another there
     * ("2.50%" above "5.00%"), and a rate printed as "5" would sit a character short
     * and break the column.
     */
    private fun classicRate(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun baseRow(narrow: Boolean = false): LinearLayout {
        val density = ctx.resources.displayMetrics.density
        val gap = 6 * density * (if (narrow) NARROW_ROW_SPACING else 1f)
        return LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap.toInt(), 0, gap.toInt())
        }
    }

    private fun cell(
        text: String,
        weight: Float,
        gravity: Int,
        sizeSp: Float = WIDE_ITEM_SP
    ): TextView = TextView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        this.text = text
        this.gravity = gravity
        typeface = billTypeface
        textSize = sizeSp
        setTextColor(0xFF222222.toInt())
    }

    private fun money(v: Double) = String.format(Locale.US, "%.2f", BillRounding.toPaise(v))

    /** Whole quantities print without decimals; fractional ones keep two places. */
    private fun qtyText(qty: Double): String =
        if (qty % 1.0 == 0.0) qty.toInt().toString() else String.format(Locale.US, "%.2f", qty)

    private fun splitDateTime(value: String): Pair<String, String> {
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)
            if (parsed != null) {
                SimpleDateFormat("dd-MM-yyyy", Locale.US).format(parsed) to
                    SimpleDateFormat("HH:mm", Locale.US).format(parsed)
            } else value to ""
        } catch (_: Exception) {
            value to ""
        }
    }


    /**
     * Which table holds this bill - the live one, or the archive a delete moved it to.
     *
     * A deleted bill is still listed under Cancelled in Bill History and still opens
     * its receipt, so the preview has to be able to find it after it has left
     * `td_bills`. Resolved per read rather than passed in, so every caller - the
     * screen, the printer, a reprint - gets the same answer without having to know
     * the bill was deleted.
     */
    private fun billsTableFor(db: SQLiteDatabase, receiptNo: Long): String =
        if (existsIn(db, DatabaseHelper.Tables.TD_BILLS, "receipt_no", receiptNo))
            DatabaseHelper.Tables.TD_BILLS else DatabaseHelper.Tables.TD_BILLS_DELETE

    /** The lines of [receiptNo], from whichever side of the delete they are on. */
    private fun itemsTableFor(db: SQLiteDatabase, receiptNo: Long): String =
        if (existsIn(db, DatabaseHelper.Tables.TD_BILLS, "receipt_no", receiptNo))
            DatabaseHelper.Tables.TD_BILL_ITEMS else DatabaseHelper.Tables.TD_BILL_ITEMS_DELETE

    private fun existsIn(db: SQLiteDatabase, table: String, column: String, value: Long): Boolean =
        db.rawQuery("SELECT 1 FROM $table WHERE $column = ? LIMIT 1", arrayOf(value.toString()))
            .use { it.moveToFirst() }

    companion object {
        private const val TAG = "BillReceiptRenderer"

        /**
         * The receipt layout for a bill format - what every screen that shows or
         * prints a bill has to inflate, so the till's Print Template choice reaches
         * all of them and not just the printer.
         *
         * A format whose layout has not been built yet falls back to Standard, which
         * is what Printer Settings > Print Template tells the operator will happen.
         */
        fun layoutFor(format: BillSettingsDao.BillFormat): Int = when (format) {
            BillSettingsDao.BillFormat.CLASSIC -> R.layout.fragment_bill_classic
            BillSettingsDao.BillFormat.TAX_WISE_SHORT -> R.layout.fragment_bill_tax_wise
            else -> R.layout.fragment_bill
        }

        /** The receipt layout for the format this till is currently set to. */
        fun layoutFor(context: Context): Int =
            layoutFor(runCatching { BillSettingsDao(context).load().billFormat }
                .getOrDefault(BillSettingsDao.BillFormat.STANDARD))

        /**
         * Logs the print against the bill, as an ORIGINAL or a DUPLICATE.
         *
         * [duplicate] is the caller's own answer, not something worked out here: the
         * screen doing the printing is the only thing that knows whether this is the
         * copy that goes with the sale or one run off afterwards from Bill history.
         *
         * It used to be inferred - first row logged for a bill was the ORIGINAL, the
         * rest reprints - and that was wrong whenever the sale-time print never
         * reached the log. A printer offline at the till, a bill never printed at the
         * counter: the first duplicate then became the "original", and the Duplicate
         * Receipt Report undercounted that bill by one for the rest of its life. The
         * caller already had the answer; it just was not being asked.
         *
         * Shared so a checkout auto-print and a Bill history reprint are recorded the
         * same way - otherwise the audit trail depends on which screen printed.
         */
        fun recordPrint(ctx: Context, receiptNo: Long, duplicate: Boolean) {
            runCatching {
                DatabaseHelper.getInstance(ctx).writableDatabase.insert(
                    DatabaseHelper.Tables.TD_BILL_PRINTS, null,
                    ContentValues().apply {
                        put("bill_id", receiptNo)
                        put("print_type", if (duplicate) "DUPLICATE" else "ORIGINAL")
                        put("print_date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                        put("created_by", SessionManager.auditUser)
                    }
                )
            }.onFailure { android.util.Log.e(TAG, "Could not record the print", it) }
        }
    }
}
