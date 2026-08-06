package com.example.synergic_pos_offline.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.StockDao
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Stock In / Write Off: what every item holds, and the + button that moves it.
 *
 * One screen serves both directions because the table behind them is the same
 * table; [Mode] decides only which way the entry applies. The rows are a statement
 * of the count and nothing more - stock is moved through the modal, not by tapping
 * an item, so there is nowhere for a tap to go.
 */
class StockListFragment : DataTableFragment() {

    /**
     * Which way a saved entry moves the stock. [OUT] is titled Write Off: the name
     * says what taking stock off the shelf outside a sale actually is, and the
     * direction it moves the count is the same either way it is called.
     */
    enum class Mode(val title: String) { IN("Stock In"), OUT("Write Off") }

    private val mode: Mode by lazy {
        Mode.entries.firstOrNull { it.name == arguments?.getString(ARG_MODE) } ?: Mode.IN
    }

    override val screenTitle: String get() = mode.title

    override val columns = listOf("Item", "Category", "Stock")

    /** Products filter by category - the "Category" column above. */
    override val filterColumnIndex = 1

    // The table lists products so stock can be moved against them; it does not own
    // them. Adding, editing and deleting a product all belong to the product master,
    // and a row itself does nothing - the + button is the only way in.
    override val showsEditAction = false
    override val showsSelection = false

    private val dao by lazy { StockDao(requireContext()) }

    /** The catalogue behind the modal's item dropdown, re-read whenever it opens. */
    private var items: List<StockDao.StockItem> = emptyList()

    override fun loadRows(): MutableList<DataRow> {
        items = dao.items(SessionManager.currentUser?.storeId ?: 0)
        return items
            .map {
                DataRow(
                    id = it.productId.toString(),
                    cells = listOf(it.name, it.category, StockDao.trim(it.stock))
                )
            }
            .toMutableList()
    }

    /** The + button opens the entry modal; there is no per-row Add. */
    override fun onAddRow() = showEntryDialog()

    // ---- Entry modal ---------------------------------------------------------

    /** One row of the modal, holding the views its values are read back out of. */
    private class EntryRow(
        val view: View,
        val item: MaterialAutoCompleteTextView,
        val quantity: TextInputEditText,
        val itemField: TextInputLayout,
        val quantityField: TextInputLayout
    ) {
        /**
         * This row's own drop-down. Held so the list can be re-filtered when another
         * row's pick changes what is left for this one to choose from.
         *
         * Assigned the moment the row is built - it needs the row to exist before it
         * can be told which selections are not its own.
         */
        lateinit var choices: ItemAdapter

        /**
         * The product this row is set to, or null while nothing has been picked.
         *
         * Read from the tag rather than the text: a name typed but never chosen from
         * the list is not a selection.
         */
        val productId: Int? get() = item.tag as? Int
    }

    private fun showEntryDialog() {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_stock_entry, null)
        val dialog = AlertDialog.Builder(context).setView(view).create()
            .also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        val stockIn = mode == Mode.IN
        view.findViewById<TextView>(R.id.tvStockEntryTitle).text = mode.title
        view.findViewById<TextView>(R.id.tvStockEntrySub).text =
            if (stockIn) "Adds to the stock of each item below"
            else "Deducts from the stock of each item below"

        // Why stock is leaving is asked once for the whole entry - every row of one
        // write-off leaves for the same reason. Stock In has no equivalent question,
        // so the whole block stays hidden there.
        val llOutReason = view.findViewById<View>(R.id.llOutReason)
        val actReason = view.findViewById<MaterialAutoCompleteTextView>(R.id.actReason)
        val tilReason = view.findViewById<TextInputLayout>(R.id.tilReason)
        val etNote = view.findViewById<TextInputEditText>(R.id.etNote)
        llOutReason.visibility = if (stockIn) View.GONE else View.VISIBLE
        if (!stockIn) {
            actReason.setAdapter(
                ArrayAdapter(
                    context, android.R.layout.simple_list_item_1,
                    StockDao.OutReason.entries.map { it.label }
                )
            )
        }

        // The dropdown is read fresh: stock moves, and an entry started from a stale
        // catalogue would be checked against counts that have since changed.
        items = dao.items(SessionManager.currentUser?.storeId ?: 0)

        val container = view.findViewById<LinearLayout>(R.id.llStockRows)
        val rows = mutableListOf<EntryRow>()

        /**
         * The products the *other* rows have already taken - what [current]'s
         * drop-down must not offer.
         *
         * The row's own pick is deliberately left in: it is what the box is showing,
         * and a list that hid it would have the operator open their own selection
         * and not find it.
         */
        fun takenByOthers(current: EntryRow): Set<Int> =
            rows.filter { it !== current }.mapNotNull { it.productId }.toSet()

        /**
         * Re-filters every row's drop-down against the picks as they now stand.
         *
         * Called whenever a selection appears, changes or goes away, rather than
         * when a list is about to open: a drop-down can be opened by tapping the box
         * or the arrow as well as by focus arriving, and correcting the list at each
         * of those moments would mean the one path that was missed still offers an
         * item another row has taken. Kept right by the change instead, every way in
         * finds it right.
         *
         * A row still being searched keeps its own text as the filter, so a
         * half-typed "mil" is not thrown away by someone else's pick. A row that has
         * already picked is filtered on nothing at all: its box holds a whole item
         * name, and filtering by that would offer only the item it is showing to an
         * operator who reopened the list precisely because they wanted a different
         * one.
         */
        fun refreshChoices() {
            rows.forEach { row ->
                row.choices.refresh(
                    query = if (row.productId != null) null else row.item.text,
                    taken = takenByOthers(row)
                )
            }
        }

        fun addRow() {
            val rowView = LayoutInflater.from(context)
                .inflate(R.layout.item_stock_entry_row, container, false)
            val row = EntryRow(
                view = rowView,
                item = rowView.findViewById(R.id.actRowItem),
                quantity = rowView.findViewById(R.id.etRowQty),
                itemField = rowView.findViewById(R.id.tilRowItem),
                quantityField = rowView.findViewById(R.id.tilRowQty)
            )
            // Offers the catalogue less whatever the other rows are already set to:
            // one entry moves an item once, and a second row of the same item is
            // either a mistake or a quantity that belonged on the first.
            row.choices = ItemAdapter(context, items)
            row.item.setAdapter(row.choices)
            // The picked product is held on the tag, so a name typed but never
            // chosen from the list cannot be mistaken for a selection.
            row.item.setOnItemClickListener { parent, _, position, _ ->
                row.item.tag = (parent.getItemAtPosition(position) as? StockDao.StockItem)?.productId
                row.itemField.error = null
                refreshChoices()
            }
            // Typing on after a pick drops the pick. Without this an operator could
            // choose Milk, edit the box to something else, and still move Milk's
            // stock - the box would say one item and the tag would mean another.
            row.item.addTextChangedListener { text ->
                val selected = items.firstOrNull { it.productId == row.productId }
                if (selected != null && text?.toString()?.trim() != selected.name) {
                    row.item.tag = null
                    // The item this row was holding is free again, and the other
                    // rows are owed it back.
                    refreshChoices()
                }
            }
            row.item.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) row.item.showDropDown() }
            rowView.findViewById<ImageButton>(R.id.btnRemoveRow).setOnClickListener {
                // The last row is emptied rather than removed: a modal with nothing
                // in it has no way back to a first row.
                if (rows.size > 1) {
                    container.removeView(rowView)
                    rows.remove(row)
                } else {
                    row.item.setText("")
                    row.item.tag = null
                    row.quantity.setText("")
                }
                // Either way the row is no longer holding an item.
                refreshChoices()
            }
            container.addView(rowView)
            rows.add(row)
            // The new row starts out holding the whole catalogue; this takes the
            // rows above it back out before it can be opened.
            refreshChoices()
            ThemeManager.applyTheme(rowView)
        }

        addRow()
        view.findViewById<MaterialButton>(R.id.btnAddStockRow).setOnClickListener {
            // Nothing left to pick means nothing for another row to say. Adding one
            // anyway would open an empty drop-down, which reads as a broken list
            // rather than as a finished entry.
            if (items.isNotEmpty() && rows.mapNotNull { it.productId }.size >= items.size) {
                toast("Every item is already on this entry")
            } else {
                addRow()
            }
        }
        view.findViewById<MaterialButton>(R.id.btnStockCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnStockSave).setOnClickListener {
            if (save(rows, stockIn, actReason, tilReason, etNote)) dialog.dismiss()
        }

        ThemeManager.applyTheme(view)
        styleButtons(view)
        dialog.show()
    }

    /**
     * Validates and applies the entry. Returns false - leaving the modal open with
     * the offending field marked - when anything is missing or does not add up.
     */
    private fun save(
        rows: List<EntryRow>,
        stockIn: Boolean,
        actReason: MaterialAutoCompleteTextView,
        tilReason: TextInputLayout,
        etNote: TextInputEditText
    ): Boolean {
        val reason = StockDao.OutReason.fromLabel(actReason.text?.toString())
        if (!stockIn && reason == null) {
            tilReason.error = "Choose a reason"
            return false
        }
        tilReason.error = null

        val movements = mutableListOf<StockDao.Movement>()
        // What has been picked so far, so an item cannot be entered twice. The
        // drop-downs already leave out what another row holds; this is the check
        // behind that, and the one that has the last word - a list is filtered when
        // it is built, and the entry is what is actually about to be written.
        val picked = mutableSetOf<Int>()
        for (row in rows) {
            val productId = row.productId
            val quantity = row.quantity.text?.toString()?.toDoubleOrNull()
            val blank = productId == null && (quantity == null || quantity == 0.0) &&
                row.item.text.isNullOrBlank()
            // A row left untouched is not an error - the modal opens with one, and
            // "+ Add Row" may have been pressed once more than was needed.
            if (blank) continue

            if (productId == null) {
                row.itemField.error = "Pick an item from the list"
                return false
            }
            if (!picked.add(productId)) {
                row.itemField.error = "Already on this entry - put the whole quantity on one row"
                return false
            }
            row.itemField.error = null
            if (quantity == null || quantity <= 0.0) {
                row.quantityField.error = "Enter a quantity"
                return false
            }
            row.quantityField.error = null
            movements.add(StockDao.Movement(productId, quantity))
        }

        if (movements.isEmpty()) {
            rows.first().itemField.error = "Add at least one item"
            return false
        }

        val note = etNote.text?.toString()?.trim().orEmpty()
        if (stockIn) {
            dao.receive(movements, note.takeIf { it.isNotEmpty() })
        } else {
            // Refused as a whole when any item is short, so a part-applied entry
            // cannot leave the operator guessing which rows went through.
            val failure = dao.issue(movements, reason!!, note)
            if (failure != null) {
                rows.first().quantityField.error = failure
                toast(failure)
                return false
            }
        }

        refreshRows()
        DialogUtils.showSuccess(
            context = requireContext(),
            title = if (stockIn) "Stock added" else "Stock removed",
            message = "${movements.size} item${if (movements.size > 1) "s" else ""} updated."
        )
        return true
    }

    /** ThemeManager fills every MaterialButton; the secondary ones read as outlined. */
    private fun styleButtons(root: View) {
        val accent = ThemeManager.getThemeColor(requireContext())
        val tint = android.content.res.ColorStateList.valueOf(accent)
        val clear = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        for (id in intArrayOf(R.id.btnStockCancel, R.id.btnAddStockRow)) {
            root.findViewById<MaterialButton>(id).apply {
                backgroundTintList = clear
                setTextColor(accent)
                strokeColor = tint
            }
        }
        root.findViewById<MaterialButton>(R.id.btnStockSave).apply {
            backgroundTintList = tint
            setTextColor(Color.WHITE)
        }
    }

    /**
     * The item dropdown: type to search, pick to select.
     *
     * Filters on the name *containing* what was typed rather than starting with it,
     * which is how someone looking for "Orange Juice 1L" by typing "juice" expects a
     * search box to behave.
     *
     * [excluded] holds the products another row of the same entry has already taken,
     * and they are left out of the list. It is handed in with each [refresh] rather
     * than worked out while filtering: [Filter] does that on a worker thread, and
     * which rows exist and what each is holding are questions only the main thread
     * can safely be asked.
     */
    private class ItemAdapter(context: Context, private val all: List<StockDao.StockItem>) :
        ArrayAdapter<StockDao.StockItem>(context, android.R.layout.simple_list_item_1, all.toList()) {

        /** Written on the main thread, read on the filter's - hence volatile. */
        @Volatile
        private var excluded: Set<Int> = emptySet()

        private val matching = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString()?.trim().orEmpty()
                val taken = excluded
                val hits = all
                    .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
                    .filterNot { it.productId in taken }
                return FilterResults().apply { values = hits; count = hits.size }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                clear()
                addAll((results?.values as? List<StockDao.StockItem>).orEmpty())
                notifyDataSetChanged()
            }

            /** What lands in the box once a row is picked. */
            override fun convertResultToString(resultValue: Any?): CharSequence =
                (resultValue as? StockDao.StockItem)?.name.orEmpty()
        }

        override fun getFilter(): Filter = matching

        /**
         * Rebuilds the visible list against [query], offering everything except
         * [taken] - the products the entry's other rows are set to.
         *
         * Called for a change the box itself did not make: another row taking an
         * item, or giving one back.
         */
        fun refresh(query: CharSequence?, taken: Set<Int>) {
            excluded = taken
            matching.filter(query)
        }

        // The list holds products, not strings, so each row is labelled explicitly -
        // left to the default the drop-down would show the data class's toString.
        // The count comes with it: choosing what to move is easier knowing what is
        // there, and on a write-off it is the ceiling the quantity will be held to.
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            super.getView(position, convertView, parent).also { row ->
                val item = getItem(position)
                (row as? TextView)?.text = item?.let { "${it.name}  (${StockDao.trim(it.stock)})" }
            }

        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            getView(position, convertView, parent)
    }

    companion object {
        private const val ARG_MODE = "stock_mode"

        fun newInstance(mode: Mode) = StockListFragment().apply {
            arguments = Bundle().apply { putString(ARG_MODE, mode.name) }
        }
    }
}
