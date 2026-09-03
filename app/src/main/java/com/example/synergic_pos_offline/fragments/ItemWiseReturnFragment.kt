package com.example.synergic_pos_offline.fragments

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.ReturnDao
import com.example.synergic_pos_offline.database.TaxSettingsDao
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.ReturnPrinter
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

/**
 * Item-wise sale return: goods come back without the bill they were sold on.
 *
 * The item is found by barcode, name or HSN, priced from the master under the Tax
 * Settings in force, and the operator says how many are coming back. The refund is
 * worked out by the same routine that priced the original sale, so what goes back
 * is what was charged - shown broken down before it is taken, and printed after.
 */
class ItemWiseReturnFragment : Fragment(), TitledScreen {

    override val screenTitle = "Sale Return"

    private val dao: ReturnDao by lazy { ReturnDao(requireContext()) }

    /** The item on screen, or null when nothing has been picked. */
    private var selected: ReturnDao.Item? = null

    /** The label a pick put in the search box, so an edit to it can be spotted. */
    private var selectedLabel: String? = null

    private lateinit var root: View
    private lateinit var actvItem: MaterialAutoCompleteTextView
    private lateinit var tilQty: TextInputLayout
    private lateinit var etQty: TextInputEditText
    private lateinit var etRate: TextInputEditText
    private lateinit var etReason: TextInputEditText

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_return_itemwise, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view

        actvItem = view.findViewById(R.id.actvItem)
        tilQty = view.findViewById(R.id.tilQty)
        etQty = view.findViewById(R.id.etQty)
        etRate = view.findViewById(R.id.etRate)
        etReason = view.findViewById(R.id.etReason)

        val suggestions = ItemSuggestionAdapter()
        actvItem.setAdapter(suggestions)
        actvItem.setOnItemClickListener { _, _, position, _ -> select(suggestions.getItem(position)) }
        // Editing the box after a pick means a different item is being looked for.
        actvItem.addTextChangedListener(watcher {
            val label = selectedLabel ?: return@watcher
            if (actvItem.text?.toString() != label) clearSelection()
        })

        // The refund follows whatever is typed, so the figure is never a surprise.
        etQty.addTextChangedListener(watcher { refreshBreakdown() })
        etRate.addTextChangedListener(watcher { refreshBreakdown() })

        view.findViewById<MaterialButton>(R.id.btnSaveReturn).setOnClickListener { saveReturn() }

        ThemeManager.applyTheme(view)
        showEmpty()
    }

    // ---- Selection ---------------------------------------------------------

    private fun select(item: ReturnDao.Item) {
        selected = item
        selectedLabel = label(item)
        actvItem.setText(selectedLabel, false)

        root.findViewById<View>(R.id.llItemEmpty).visibility = View.GONE
        root.findViewById<View>(R.id.svItemDetail).visibility = View.VISIBLE

        root.findViewById<TextView>(R.id.tvItemName).text = item.name.ifBlank { "Unnamed item" }
        root.findViewById<TextView>(R.id.tvItemMeta).text = listOfNotNull(
            item.barcode.takeIf { it.isNotBlank() }?.let { "Barcode $it" },
            item.hsn.takeIf { it.isNotBlank() }?.let { "HSN $it" }
        ).joinToString("  ·  ").ifEmpty { "No barcode or HSN on file" }

        etRate.setText(trim(item.rate))
        etQty.setText("1")
        etQty.setSelection(etQty.text?.length ?: 0)
        etReason.setText("")
        refreshBreakdown()
    }

    private fun clearSelection() {
        selected = null
        selectedLabel = null
        showEmpty()
    }

    private fun showEmpty() {
        root.findViewById<View>(R.id.svItemDetail).visibility = View.GONE
        root.findViewById<View>(R.id.llItemEmpty).visibility = View.VISIBLE
    }

    // ---- The figures -------------------------------------------------------

    /** The line as currently typed, or null when the inputs do not describe one. */
    private fun currentLine(): ReturnDao.ReturnLine? {
        val item = selected ?: return null
        val qty = etQty.text?.toString()?.trim()?.toDoubleOrNull() ?: return null
        if (qty <= 0.0) return null
        val rate = etRate.text?.toString()?.trim()?.toDoubleOrNull() ?: item.rate
        // The rate can be corrected on screen - goods are sometimes brought back
        // against a price that has since changed - and the discount follows it,
        // being a share of what the line actually came to.
        val priced = item.copy(rate = rate)
        return dao.priceLine(
            name = item.name,
            productId = item.productId,
            billItemId = null,
            quantity = qty,
            rate = rate,
            cgstRate = item.cgstRate,
            sgstRate = item.sgstRate,
            vatRate = item.vatRate,
            discountAmount = dao.discountFor(priced, qty)
        )
    }

    /**
     * Restates the refund every time the quantity or rate changes.
     *
     * Laid out as the item sale dialog lays a line out - discount, taxable value,
     * each tax, then the amount under a rule - so the operator reads a return the
     * way they read a sale. Which rows apply follows the selected item's own rates
     * - GST shows CGST and SGST, VAT relabels the first and drops the second - and
     * with tax switched off entirely both go, whatever the item carries on file.
     */
    private fun refreshBreakdown() {
        val item = selected ?: return
        val line = currentLine()

        val regime = taxRegime(item)
        val vatOnly = regime == GstCalculator.TaxRegime.VAT
        root.findViewById<View>(R.id.rowReturnCgst).visibility =
            if (regime == GstCalculator.TaxRegime.NONE) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.rowReturnSgst).visibility =
            if (regime == GstCalculator.TaxRegime.GST) View.VISIBLE else View.GONE

        val cgstRate = if (vatOnly) item.vatRate else item.cgstRate
        root.findViewById<TextView>(R.id.tvReturnCgstLabel).text =
            (if (vatOnly) "VAT" else "CGST") + " (${rate(cgstRate)}%)"
        root.findViewById<TextView>(R.id.tvReturnSgstLabel).text = "SGST (${rate(item.sgstRate)}%)"

        // Nothing typed yet: the rows stay, showing nothing rather than vanishing
        // and shifting everything under them as soon as a digit is entered.
        val blank = line == null
        root.findViewById<View>(R.id.rowReturnDiscount).visibility =
            if (!blank && line!!.discount > 0.005) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.tvReturnDiscountAmt).text =
            if (blank) "-₹0.00" else "-" + money(line!!.discount)
        root.findViewById<TextView>(R.id.tvReturnTaxable).text =
            if (blank) "₹0.00" else money(line!!.taxable)
        root.findViewById<TextView>(R.id.tvReturnCgstAmt).text =
            if (blank) "₹0.00" else money(if (vatOnly) line!!.vat else line!!.cgst)
        root.findViewById<TextView>(R.id.tvReturnSgstAmt).text =
            if (blank) "₹0.00" else money(line!!.sgst)
        root.findViewById<TextView>(R.id.tvReturnLineAmount).text =
            if (blank) "₹0.00" else money(line!!.amount)
    }

    /** Which tax rows [item] shows, from whatever it carries on file - forced to
     *  NONE when tax is switched off store-wide. */
    private fun taxRegime(item: ReturnDao.Item): GstCalculator.TaxRegime {
        if (!TaxSettingsDao(requireContext()).load().taxEnabled) return GstCalculator.TaxRegime.NONE
        return GstCalculator.regimeOf(item.cgstRate, item.sgstRate, item.vatRate)
    }

    // ---- Saving ------------------------------------------------------------

    private fun saveReturn() {
        if (selected == null) return
        val line = currentLine()
        if (line == null) {
            tilQty.error = "Enter the quantity coming back"
            return
        }
        tilQty.error = null

        val result = dao.save(listOf(line), reason = etReason.text?.toString()?.trim())
        if (result == null) {
            toast("Could not record the return")
            return
        }

        DialogUtils.showSuccess(
            context = requireContext(),
            title = "Return recorded",
            message = "${trim(line.quantity)} x ${line.name} taken back. " +
                "Refund ${money(result.totalAmount)}.",
            buttonText = "Print receipt"
        ) {
            ReturnPrinter.print(requireContext(), result) { if (isAdded) toast(it) }
            clearSelection()
            actvItem.setText("", false)
        }
    }

    // ---- Type-ahead --------------------------------------------------------

    /** "Name · barcode", the one label the dropdown and the search box both use. */
    private fun label(item: ReturnDao.Item): String {
        val name = item.name.trim().ifEmpty { "Unnamed item" }
        return if (item.barcode.isBlank()) name else "$name · ${item.barcode}"
    }

    /**
     * Matches items on name, barcode or HSN as the operator types - the three
     * things to hand when something is brought back without its bill. Filtering
     * runs on the framework's filter thread, so the lookup goes to the database on
     * every keystroke rather than being held in memory.
     */
    private inner class ItemSuggestionAdapter : ArrayAdapter<ReturnDao.Item>(
        requireContext(), R.layout.item_return_suggestion, mutableListOf()
    ) {
        private val matches = mutableListOf<ReturnDao.Item>()

        override fun getCount(): Int = matches.size

        override fun getItem(position: Int): ReturnDao.Item = matches[position]

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(
                R.layout.item_return_suggestion, parent, false
            )
            val item = matches[position]
            view.findViewById<TextView>(R.id.tvSuggestItemName).text =
                item.name.trim().ifEmpty { "Unnamed item" }
            view.findViewById<TextView>(R.id.tvSuggestItemMeta).text = listOfNotNull(
                item.barcode.takeIf { it.isNotBlank() },
                item.hsn.takeIf { it.isNotBlank() }?.let { "HSN $it" }
            ).joinToString("  ·  ").ifEmpty { "No barcode or HSN" }
            view.findViewById<TextView>(R.id.tvSuggestItemRate).text = money(item.rate)
            return view
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            getView(position, convertView, parent)

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val term = constraint?.toString().orEmpty()
                val found = if (term.isBlank()) emptyList()
                else runCatching { dao.searchItems(term) }.getOrDefault(emptyList())
                return FilterResults().apply { values = found; count = found.size }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                matches.clear()
                @Suppress("UNCHECKED_CAST")
                (results.values as? List<ReturnDao.Item>)?.let { matches.addAll(it) }
                if (results.count > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
            }

            override fun convertResultToString(resultValue: Any?): CharSequence =
                (resultValue as? ReturnDao.Item)?.let { label(it) }.orEmpty()
        }
    }

    // ---- Small helpers -----------------------------------------------------

    private fun watcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange()
    }

    private fun money(v: Double): String = "₹ " + String.format(Locale.US, "%.2f", v)

    /** A figure without a needless ".0" - "2" not "2.0", but "2.50" kept. */
    private fun trim(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.2f", v)

    private fun rate(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.2f", v)

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
