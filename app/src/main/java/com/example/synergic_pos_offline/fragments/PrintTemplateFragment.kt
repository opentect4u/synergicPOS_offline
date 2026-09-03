package com.example.synergic_pos_offline.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.BillSettingsDao.BillFormat
import com.example.synergic_pos_offline.utils.BillReceiptRenderer
import com.example.synergic_pos_offline.utils.ReceiptContext
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Width the receipt card is laid out at for 80mm paper - see fragment_bill.xml. */
private const val CARD_WIDTH_DP = 360

/** Printable dots on 80mm and 58mm paper, per [com.example.synergic_pos_offline.utils.ThermalPrinter]. */
private const val DOTS_80MM = 576
private const val DOTS_58MM = 384

/**
 * The "Print Template" tab of Printer Settings: pick the bill layout, see it.
 *
 * The preview is not a mock-up of the receipt - it is the receipt, rendered by the
 * same [BillReceiptRenderer] the printer prints through, off a sample sale. So the
 * store header, logos, header/footer lines, HSN, which customer details show, the
 * tax summary and the totals all appear here exactly as they will on paper; only
 * the line items are invented.
 *
 * Standard, Classic and Tax wise short are built, and picking one is what the whole
 * app - bill screen, checkout preview and printer alike - draws from that point on.
 * Clubbed is stored when chosen, since the choice is the operator's, but its layout
 * has not been supplied yet, so the pane says so rather than showing a Standard bill
 * under another name.
 */
class PrintTemplateFragment : Fragment() {

    private val dao: BillSettingsDao by lazy { BillSettingsDao(requireContext()) }

    private var format = BillFormat.CLASSIC
    private var paperMm = BillSettingsDao.DEFAULT_TEMPLATE_PAPER_MM

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_print_template, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        format = dao.load().billFormat
        paperMm = dao.loadTemplatePaperMm()

        val rgTemplate = view.findViewById<RadioGroup>(R.id.rgTemplate)
        val rgPaper = view.findViewById<RadioGroup>(R.id.rgPaperWidth)

        // Checked before the listeners go on, so restoring the saved choice does not
        // read as the operator having just made it and write it straight back.
        rgTemplate.check(radioFor(format))
        rgPaper.check(if (paperMm == 58) R.id.rbPaper58 else R.id.rbPaper80)

        rgTemplate.setOnCheckedChangeListener { _, checkedId ->
            format = formatFor(checkedId)
            dao.saveBillFormat(format)
            render()
        }
        rgPaper.setOnCheckedChangeListener { _, checkedId ->
            paperMm = if (checkedId == R.id.rbPaper58) 58 else 80
            dao.saveTemplatePaperMm(paperMm)
            render()
        }

        ThemeManager.applyTheme(view)
    }

    /**
     * Redrawn on every return to the tab rather than once: the bill this shows is
     * built from the Bill, Tax and header/footer settings, any of which may have
     * been changed on another screen since.
     */
    override fun onResume() {
        super.onResume()
        render()
    }

    private fun radioFor(format: BillFormat): Int = when (format) {
        BillFormat.STANDARD -> R.id.rbTemplateStandard
        BillFormat.CLASSIC -> R.id.rbTemplateClassic
        BillFormat.CLUBBED -> R.id.rbTemplateClubbed
        BillFormat.TAX_WISE_SHORT -> R.id.rbTemplateTaxWise
    }

    private fun formatFor(checkedId: Int): BillFormat = when (checkedId) {
        R.id.rbTemplateClassic -> BillFormat.CLASSIC
        R.id.rbTemplateClubbed -> BillFormat.CLUBBED
        R.id.rbTemplateTaxWise -> BillFormat.TAX_WISE_SHORT
        // Anything unrecognised falls to the app default - see BillSettings.billFormat.
        else -> BillFormat.CLASSIC
    }

    /**
     * Draws the selected template into the preview pane.
     *
     * The receipt view is inflated afresh each time instead of being refilled:
     * rendering for a 2-inch roll permanently resizes the type and shortens the
     * column headings, so a view that had once been drawn narrow would stay narrow
     * when the 3-inch option was picked back.
     */
    private fun render() {
        val root = view ?: return
        val host = root.findViewById<FrameLayout>(R.id.previewHost)
        val notice = root.findViewById<TextView>(R.id.tvTemplateUnavailable)
        val sampleNote = root.findViewById<TextView>(R.id.tvSampleNote)

        host.removeAllViews()

        if (!format.implemented) {
            notice.text = "The ${format.label} template hasn't been built yet.\n\n" +
                "It is saved as your bill format, and bills print in the Standard " +
                "layout until the ${format.label} layout is added."
            notice.visibility = View.VISIBLE
            sampleNote.visibility = View.INVISIBLE
            return
        }
        notice.visibility = View.GONE
        sampleNote.visibility = View.VISIBLE

        val dots = if (paperMm == 58) DOTS_58MM else DOTS_80MM
        // Standard font scale, like the print - a device set to large text must not
        // change what the operator is shown here. See [ReceiptContext].
        // The layout of the format being previewed, not the one that happens to be
        // saved: the two agree here anyway, since the choice is written before this
        // runs, but reading it from [format] keeps the pane showing what was tapped.
        val bill = LayoutInflater.from(ReceiptContext.standardFontScale(requireContext()))
            .inflate(BillReceiptRenderer.layoutFor(format), host, false)
        bill.findViewById<View>(R.id.btnPrintBill)?.visibility = View.GONE

        // The card is 360dp wide for 80mm; narrower paper gets its share of that, so
        // the two widths differ on screen the way they differ on paper rather than
        // being the same slip in a smaller font.
        bill.findViewById<View>(R.id.cardReceipt)?.let { card ->
            card.layoutParams = card.layoutParams.apply {
                width = ((CARD_WIDTH_DP.toDouble() * dots / DOTS_80MM) *
                    resources.displayMetrics.density).toInt()
            }
        }

        host.addView(bill)
        BillReceiptRenderer(requireContext()).populate(bill, 0L, dots, draft = sampleDraft())
    }

    /**
     * A stand-in sale for the preview: three lines, one of them discounted so the
     * DISC column shows, each carrying both a GST and a VAT rate so the tax summary
     * fills in whichever regime the till is set to, and a unit apiece for the
     * layouts that print one beside the quantity.
     */
    private fun sampleDraft(): BillReceiptRenderer.Draft = BillReceiptRenderer.Draft(
        billNumber = "SAMPLE",
        dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
        cashier = SessionManager.currentUser?.userId?.uppercase() ?: "ADMIN",
        customer = BillReceiptRenderer.Draft.Customer(
            name = "Sample Customer",
            phone = "9876543210",
            gstin = "22AAAAA0000A1Z5",
            address = "12 Sample Street, Kolkata"
        ),
        items = listOf(
            BillReceiptRenderer.Draft.Item(
                name = "Basmati Rice 1kg", quantity = 2.0, rate = 145.0,
                cgstRate = 2.5, sgstRate = 2.5, vatRate = 5.0, hsn = "1006", unit = "KG"
            ),
            BillReceiptRenderer.Draft.Item(
                name = "Sunflower Oil 1L", quantity = 1.0, rate = 189.5,
                cgstRate = 2.5, sgstRate = 2.5, vatRate = 5.0, hsn = "1512", unit = "LTR"
            ),
            BillReceiptRenderer.Draft.Item(
                name = "Toothpaste 100g", quantity = 3.0, rate = 55.0,
                cgstRate = 9.0, sgstRate = 9.0, vatRate = 18.0,
                discountAmount = SAMPLE_DISCOUNT, hsn = "3306", unit = "PKT"
            )
        ),
        discount = SAMPLE_DISCOUNT,
        roundOff = 0.0,
        // Only read for a bill with no lines; this one's total is summed from its own.
        netAmount = 0.0,
        paymentModes = listOf("CASH")
    )

    private companion object {
        /** Carried on one sample line and as the bill's own total, so the two agree. */
        const val SAMPLE_DISCOUNT = 15.0
    }
}
