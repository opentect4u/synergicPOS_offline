package com.example.synergic_pos_offline.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.CalculatorBill
import com.example.synergic_pos_offline.utils.PeriodReportPrinter
import com.example.synergic_pos_offline.utils.PeriodReportRenderer
import com.example.synergic_pos_offline.utils.ReceiptContext
import com.example.synergic_pos_offline.utils.SessionManager
import com.google.android.material.button.MaterialButton

/**
 * A saved calculator bill, shown as the slip it prints as.
 *
 * The same renderer fills this screen and the paper, so what is checked here is what
 * comes out - see [CalculatorBill.content]. The Print button reprints it.
 */
class CalculatorBillFragment : Fragment(), TitledScreen {

    override val screenTitle = "Calculator Bill"

    private val bill: CalculatorBill.Bill by lazy {
        val args = requireArguments()
        val rates = args.getDoubleArray(ARG_RATES) ?: DoubleArray(0)
        val quantities = args.getDoubleArray(ARG_QTYS) ?: DoubleArray(0)
        CalculatorBill.Bill(
            billNumber = args.getString(ARG_NUMBER).orEmpty(),
            stamp = args.getString(ARG_STAMP).orEmpty(),
            // Paired by position - the two arrays are written together and are always
            // the same length, so the shorter one bounds the walk either way.
            lines = rates.indices.take(quantities.size).map { i ->
                CalculatorBill.Line(i + 1, rates[i], quantities[i])
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = LayoutInflater
        // At a standard font scale, like the print - otherwise the preview would grow
        // and shrink with the device's text size while the paper did not, and the two
        // would stop being the same slip. See [ReceiptContext].
        .from(ReceiptContext.standardFontScale(requireContext()))
        .inflate(R.layout.receipt_period_report, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val printedBy = SessionManager.currentUser?.userId?.uppercase() ?: "---"
        PeriodReportRenderer(requireContext())
            .populate(view, CalculatorBill.content(bill), printedBy)

        // The receipt layout carries no button of its own - it is drawn for paper -
        // so Print is added over it here.
        val print = MaterialButton(requireContext()).apply {
            text = "Print"
            setIconResource(R.drawable.ic_print)
            cornerRadius = (12 * resources.displayMetrics.density).toInt()
            setOnClickListener {
                PeriodReportPrinter.print(requireContext(), CalculatorBill.content(bill), "lines") {
                    if (isAdded) Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                }
            }
        }
        (view as ViewGroup).addView(
            print,
            android.widget.FrameLayout.LayoutParams(-2, (46 * resources.displayMetrics.density).toInt())
                .apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    val margin = (16 * resources.displayMetrics.density).toInt()
                    setMargins(margin, margin, margin, margin)
                }
        )
    }

    companion object {
        private const val ARG_NUMBER = "number"
        private const val ARG_STAMP = "stamp"
        private const val ARG_RATES = "rates"
        private const val ARG_QTYS = "qtys"

        /**
         * The bill as arguments.
         *
         * Carried as two parallel arrays rather than a serialised object: a
         * calculator line is a rate and a quantity and nothing else, and two double
         * arrays survive a rotation without anything having to be made parcelable.
         */
        fun of(bill: CalculatorBill.Bill): CalculatorBillFragment =
            CalculatorBillFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_NUMBER, bill.billNumber)
                    putString(ARG_STAMP, bill.stamp)
                    putDoubleArray(ARG_RATES, bill.lines.map { it.rate }.toDoubleArray())
                    putDoubleArray(ARG_QTYS, bill.lines.map { it.quantity }.toDoubleArray())
                }
            }
    }
}
