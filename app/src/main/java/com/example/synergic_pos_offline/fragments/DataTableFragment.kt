package com.example.synergic_pos_offline.fragments

import android.database.sqlite.SQLiteConstraintException
import android.content.res.ColorStateList
import androidx.core.view.isVisible
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

/** Largest edge, in px, decoded for the full-size image preview. */
private const val PREVIEW_PX = 1200

/** Decodes only as many pixels as needed, so large image BLOBs stay cheap to show. */
private fun decodeSampledBitmap(bytes: ByteArray, targetPx: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > targetPx || bounds.outHeight / sample > targetPx) {
        sample *= 2
    }
    BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
    )
} catch (_: Exception) {
    null
}

/**
 * A single table row: a stable [id] plus one string per data column.
 * [thumbnail] is optional encoded image bytes shown as a round preview before the
 * first cell (only when the screen sets [DataTableFragment.showsThumbnails]).
 */
data class DataRow(val id: String, val cells: List<String>, val thumbnail: ByteArray? = null)

/** Lets a fragment supply its own title to the global header. */
interface TitledScreen {
    val screenTitle: String
}

/**
 * Reusable searchable data-table screen. Each row has a selection checkbox.
 * Global Print and Delete actions appear in a contextual bar when rows are selected.
 */
abstract class DataTableFragment : Fragment(), TitledScreen {

    /** Column headers (checkbox + "Actions" columns are added automatically). */
    abstract val columns: List<String>

    /** Fields shown in the Add/Edit form. Defaults to [columns] if not overridden. */
    open val formFields: List<String> get() = columns

    /** Set true to reserve a leading round thumbnail column fed by [DataRow.thumbnail]. */
    open val showsThumbnails: Boolean get() = false

    /** Initial rows to display; each row's cells must align with [columns]. */
    abstract fun loadRows(): MutableList<DataRow>

    /** Column index (into [columns]) rendered as an image thumbnail, if any. */
    open val thumbnailColumn: Int? = null

    /** Supplies the thumbnail bitmap for [row]; null renders a placeholder icon. */
    open fun loadThumbnail(row: DataRow): Bitmap? = null

    /** Invoked when a row's thumbnail cell is tapped (e.g. to preview it full-size). */
    open fun onThumbnailClick(row: DataRow) {}

    /** Column index (into [columns]) rendered as an inline ON/OFF switch, if any. */
    open val switchColumn: Int? = null

    /**
     * Columns (indices into [columns]) whose cells wrap onto as many lines as their
     * text needs, instead of being cut off with an ellipsis on one line.
     *
     * One line per cell is what keeps a table of short values - names, codes, amounts -
     * scannable, so it stays the default. A column holding a whole sentence, like a
     * printed header or footer line, is the opposite case: truncated it is unreadable,
     * and the operator cannot tell what will come out on the paper without opening the
     * row. Opting a column in here is saying it holds prose, not a value.
     */
    open val wrappingColumns: Set<Int> get() = emptySet()

    /** Invoked when a row's inline switch is toggled. Persist + reflect the new state. */
    open fun onSwitchToggled(row: DataRow, isOn: Boolean) {}

    /** Set true to add a per-row "Test Print" icon button next to Edit (Operating Printer only). */
    open val showsTestPrintAction: Boolean get() = false

    /** Invoked when a row's Test Print button is tapped. */
    open fun onTestPrintRow(row: DataRow) {}

    /**
     * Icon for an extra action on [row], or null to leave that row without one.
     *
     * Decided per row rather than per screen, so an action that only makes sense
     * for some records - a ledger for a customer who is actually on credit - is
     * simply absent on the rest instead of being offered and then refused.
     */
    open fun rowActionIcon(row: DataRow): Int? = null

    /** Accessibility label for the [rowActionIcon] button. */
    open val rowActionLabel: String get() = "Action"

    /** Invoked when a row's extra action button is tapped. */
    open fun onRowAction(row: DataRow) {}

    private val allRows = mutableListOf<DataRow>()
    private val shownRows = mutableListOf<DataRow>()
    private val selectedIds = linkedSetOf<String>()
    private var query = ""

    private lateinit var rvTable: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: DataTableAdapter
    private lateinit var cbSelectAll: CheckBox
    private var suppressSelectAll = false

    // Selection UI
    private lateinit var tvSelectionCount: TextView
    private lateinit var btnGlobalPrint: ImageButton
    private lateinit var btnGlobalDelete: ImageButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_data_table, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvSelectionCount = view.findViewById(R.id.tvSelectionCount)
        btnGlobalPrint = view.findViewById(R.id.btnGlobalPrint)
        btnGlobalDelete = view.findViewById(R.id.btnGlobalDelete)

        rvTable = view.findViewById(R.id.rvTable)
        tvEmpty = view.findViewById(R.id.tvEmpty)

        adapter = DataTableAdapter(
            shownRows,
            columns.size,
            selectedIds,
            thumbnailColumn,
            { loadThumbnail(it) },
            onThumbnailClick = { onThumbnailClick(it) },
            showsThumbnails,
            switchColumn,
            wrappingColumns,
            onSwitchToggled = { row, isOn -> onSwitchToggled(row, isOn) },
            onEdit = { onEditRow(it) },
            onThumbClick = { showImagePreview(it) },
            onSelectionChanged = { updateSelectionUI() },
            showsTestPrintAction = showsTestPrintAction,
            onTestPrint = { onTestPrintRow(it) },
            rowActionIcon = { rowActionIcon(it) },
            rowActionLabel = rowActionLabel,
            onRowAction = { onRowAction(it) }
        )
        rvTable.layoutManager = LinearLayoutManager(requireContext())
        rvTable.adapter = adapter

        buildHeader(view.findViewById(R.id.llTableHeader))

        allRows.clear()
        allRows.addAll(loadRows())
        applyFilter("")

        val etSearch = view.findViewById<TextInputEditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                applyFilter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        setUpColumnFilter(view)

        view.findViewById<View>(R.id.btnAdd).setOnClickListener { onAddRow() }

        // A single FAB that opens a dedicated bulk-upload page (product screen).
        view.findViewById<View>(R.id.btnBulkPage).apply {
            isVisible = bulkPageEnabled()
            setOnClickListener { onBulkPage() }
        }
        // Download-template action, beside the bin (product screen only).
        view.findViewById<View>(R.id.btnGlobalDownload).apply {
            isVisible = showDownloadTemplate()
            setOnClickListener { onDownloadTemplate() }
        }

        btnGlobalDelete.setOnClickListener { onBulkDelete() }
        btnGlobalPrint.setOnClickListener { onBulkPrint() }

        ThemeManager.applyTheme(view)
    }

    private fun buildHeader(header: LinearLayout) {
        header.removeAllViews()
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)
        val density = resources.displayMetrics.density

        cbSelectAll = CheckBox(ctx)
        cbSelectAll.layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        cbSelectAll.buttonTintList = ColorStateList.valueOf(accent)
        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSelectAll) return@setOnCheckedChangeListener
            if (isChecked) shownRows.forEach { selectedIds.add(it.id) }
            else shownRows.forEach { selectedIds.remove(it.id) }
            adapter.notifyDataSetChanged()
            updateSelectionUI()
        }
        header.addView(cbSelectAll)

        // Keep the header aligned with the rows' leading thumbnail (40dp + 10dp margin).
        if (showsThumbnails) {
            val spacer = View(ctx)
            spacer.layoutParams = LinearLayout.LayoutParams((50 * density).toInt(), 1)
            header.addView(spacer)
        }

        for (col in columns) {
            val tv = TextView(ctx)
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tv.text = col
            tv.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary))
            tv.textSize = 15f
            tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            header.addView(tv)
        }
        val actions = TextView(ctx)
        actions.layoutParams = LinearLayout.LayoutParams((120 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        actions.text = "Actions"
        actions.gravity = Gravity.CENTER
        actions.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary))
        actions.textSize = 15f
        actions.setTypeface(actions.typeface, android.graphics.Typeface.BOLD)
        header.addView(actions)
    }

    private fun updateSelectionUI() {
        val count = selectedIds.size
        val hasSelection = count > 0

        tvSelectionCount.text = if (hasSelection) "$count item${if (count > 1) "s" else ""} selected" else "No items selected"

        btnGlobalPrint.isEnabled = hasSelection
        btnGlobalDelete.isEnabled = hasSelection

        btnGlobalPrint.alpha = if (hasSelection) 1f else 0.35f
        btnGlobalDelete.alpha = if (hasSelection) 1f else 0.35f

        suppressSelectAll = true
        cbSelectAll.isChecked = shownRows.isNotEmpty() && shownRows.all { selectedIds.contains(it.id) }
        suppressSelectAll = false
    }

    /**
     * The column a screen can offer a dropdown filter on, as its index into
     * [columns]; null - the default - leaves the dropdown off the screen entirely.
     *
     * Filtering by a whole column is a different question from searching: a search
     * for "Dairy" also matches a product happening to be *called* Dairy Milk, which
     * is not what someone narrowing a table by category is asking for. The dropdown
     * matches the cell exactly, and its options are whatever values that column
     * actually holds - so it cannot offer a category the table has nothing under.
     */
    protected open val filterColumnIndex: Int? = null

    /** The dropdown's "no filter" entry, and what it is set to until one is picked. */
    private val allFilterValues = "All"

    /** The value picked in the column dropdown, or [allFilterValues] for no filter. */
    private var filterValue: String = allFilterValues

    /**
     * Fills the column dropdown from the rows on screen and wires it to the filter.
     *
     * Rebuilt from [allRows] rather than queried, so it lists exactly the values the
     * table holds and needs no knowledge of where they came from.
     */
    private fun setUpColumnFilter(view: View) {
        val index = filterColumnIndex ?: return
        val til = view.findViewById<View>(R.id.tilFilter) ?: return
        val act = view.findViewById<MaterialAutoCompleteTextView>(R.id.actFilter) ?: return
        til.visibility = View.VISIBLE
        (til as? com.google.android.material.textfield.TextInputLayout)?.hint = columns.getOrNull(index)

        val values = listOf(allFilterValues) + allRows
            .mapNotNull { it.cells.getOrNull(index)?.takeIf { cell -> cell.isNotBlank() } }
            .distinct()
            .sortedBy { it.lowercase() }
        act.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, values))
        act.setText(filterValue.takeIf { it in values } ?: allFilterValues, false)
        act.setOnItemClickListener { _, _, pos, _ ->
            filterValue = values[pos]
            applyFilter(query)
        }
    }

    private fun applyFilter(q: String) {
        query = q.trim()
        val index = filterColumnIndex
        shownRows.clear()
        shownRows.addAll(
            allRows.filter { row ->
                val matchesFilter = index == null || filterValue == allFilterValues ||
                    row.cells.getOrNull(index).equals(filterValue, ignoreCase = true)
                val matchesQuery = query.isEmpty() ||
                    row.cells.any { it.contains(query, ignoreCase = true) }
                matchesFilter && matchesQuery
            }
        )
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (shownRows.isEmpty()) View.VISIBLE else View.GONE
        if (::cbSelectAll.isInitialized) updateSelectionUI()
    }

    protected fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    // ---- Row mutation helpers (for subclasses with custom forms) ----------

    /** Snapshot of every row currently backing the table (unfiltered). */
    protected fun currentRows(): List<DataRow> = allRows.toList()

    /** Appends [row] and refreshes the visible list, keeping the active search. */
    protected fun addRow(row: DataRow) {
        allRows.add(row)
        applyFilter(query)
    }

    /** Replaces the cells of the row identified by [id], if it still exists. */
    protected fun updateRow(id: String, cells: List<String>) {
        val idx = allRows.indexOfFirst { it.id == id }
        if (idx >= 0) {
            allRows[idx] = DataRow(id, cells)
            applyFilter(query)
        }
    }

    /** Re-reads the backing data via [loadRows] and refreshes the table. */
    protected fun reload() {
        refreshRows()
    }
    /** Opens the row's image full size. Called when a row thumbnail is tapped. */
    private fun showImagePreview(row: DataRow) {
        val bitmap = row.thumbnail?.let { decodeSampledBitmap(it, PREVIEW_PX) } ?: return
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_image_preview, null)
        val dialog = AlertDialog.Builder(requireContext()).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        view.findViewById<TextView>(R.id.tvPreviewName).text =
            row.cells.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Image"
        view.findViewById<android.widget.ImageView>(R.id.ivPreview).setImageBitmap(bitmap)
        view.findViewById<MaterialButton>(R.id.btnPreviewClose).apply {
            backgroundTintList = ColorStateList.valueOf(ThemeManager.getThemeColor(requireContext()))
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }

    /** Ids of the currently ticked rows, for subclasses that persist bulk actions. */
    protected val selectedRowIds: Set<String> get() = selectedIds.toSet()

    /** Re-runs [loadRows] and repaints the table, keeping the current search filter. */
    protected fun refreshRows() {
        allRows.clear()
        allRows.addAll(loadRows())
        selectedIds.clear()
        // The dropdown lists the values the rows actually hold, so it is rebuilt
        // with them - a product added under a brand-new category would otherwise
        // leave that category unofferable until the screen was reopened.
        view?.let { setUpColumnFilter(it) }
        applyFilter(query)
    }

    // ---- Actions -----------------------------------------------------------

    /** Screens that offer a download-template action (beside the bin) return true. */
    protected open fun showDownloadTemplate(): Boolean = false

    /** Called when the download-template action is tapped. */
    protected open fun onDownloadTemplate() {}

    /** Screens that route bulk upload to a dedicated page return true. */
    protected open fun bulkPageEnabled(): Boolean = false

    /** Called when the bulk-page FAB is tapped. */
    protected open fun onBulkPage() {}

    protected open fun onAddRow() {
        val fields = formFields.map { DialogUtils.FormField(it, "") }
        DialogUtils.showForm(requireContext(), "Add New", fields, positiveText = "Add") { values ->
            allRows.add(DataRow(System.currentTimeMillis().toString(), values))
            applyFilter(query)
            toast("Added")
        }
    }

    protected open fun onEditRow(row: DataRow) {
        val fields = formFields.mapIndexed { i, label ->
            DialogUtils.FormField(label, row.cells.getOrNull(i).orEmpty())
        }
        DialogUtils.showForm(requireContext(), "Edit Record", fields) { values ->
            val idx = allRows.indexOfFirst { it.id == row.id }
            if (idx >= 0) {
                allRows[idx] = DataRow(row.id, values)
                applyFilter(query)
                toast("Updated")
            }
        }
    }

    protected open fun onBulkDelete() {
        val ids = selectedIds.toSet()
        val count = ids.size
        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Delete Selected",
            message = "Are you sure you want to delete $count selected record(s)?",
            positiveText = "Delete All",
            negativeText = "Cancel",
            iconRes = android.R.drawable.ic_menu_delete,
            destructive = true
        ) {
            deleteBlockedReason(ids)?.let { reason ->
                toast(reason)
                return@showConfirm
            }
            try {
                onRowsDeleted(ids)
            } catch (e: SQLiteConstraintException) {
                // A master row that other records still point at cannot be removed.
                // The table is reloaded so it keeps showing what the database
                // actually holds rather than the rows we hoped to drop.
                android.util.Log.w("DataTableFragment", "Delete refused by the database", e)
                reload()
                toast("Cannot delete: these records are still in use")
                return@showConfirm
            }
            // Only drop the rows once the database has actually accepted the delete.
            allRows.removeAll { ids.contains(it.id) }
            selectedIds.clear()
            applyFilter(query)
            toast("Deleted $count record(s)")
        }
    }

    /**
     * Persists the deletion of the given row ids. Subclasses backed by a database
     * override this; throwing leaves the table untouched.
     */
    protected open fun onRowsDeleted(ids: Set<String>) {}

    /**
     * Why these rows cannot be deleted, or null to allow it. Overridden by screens
     * whose rows are referenced elsewhere, so the user is told what is holding the
     * record rather than being shown a generic refusal.
     */
    protected open fun deleteBlockedReason(ids: Set<String>): String? = null

    protected open fun onBulkPrint() {
        val count = selectedIds.size
        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Print Selected",
            message = "Do you want to print $count selected record(s)?",
            positiveText = "Print All",
            negativeText = "Cancel",
            iconRes = android.R.drawable.ic_menu_set_as,
            destructive = false
        ) {
            toast("Printing $count record(s)...")
        }
    }

    private class DataTableAdapter(
        private val rows: List<DataRow>,
        private val columnCount: Int,
        private val selectedIds: MutableSet<String>,
        private val thumbnailColumn: Int?,
        private val thumbnailProvider: (DataRow) -> Bitmap?,
        private val onThumbnailClick: (DataRow) -> Unit,
        private val showsThumbnails: Boolean,
        private val switchColumn: Int?,
        private val wrappingColumns: Set<Int>,
        private val onSwitchToggled: (DataRow, Boolean) -> Unit,
        private val onEdit: (DataRow) -> Unit,
        private val onThumbClick: (DataRow) -> Unit,
        private val onSelectionChanged: () -> Unit,
        private val showsTestPrintAction: Boolean = false,
        private val onTestPrint: (DataRow) -> Unit = {},
        private val rowActionIcon: (DataRow) -> Int? = { null },
        private val rowActionLabel: String = "Action",
        private val onRowAction: (DataRow) -> Unit = {}
    ) : RecyclerView.Adapter<DataTableAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cbRow: CheckBox = view.findViewById(R.id.cbRow)
            val ivThumb: android.widget.ImageView = view.findViewById(R.id.ivRowThumb)
            val llCells: LinearLayout = view.findViewById(R.id.llCells)
            val btnEdit: View = view.findViewById(R.id.btnRowEdit)
            val btnTestPrint: View = view.findViewById(R.id.btnRowTestPrint)
            val btnAction: MaterialButton = view.findViewById(R.id.btnRowAction)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_data_row, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = rows[position]
            val ctx = holder.itemView.context

            bindThumbnail(holder, row, ctx)

            holder.llCells.removeAllViews()
            for (i in 0 until columnCount) {
                if (i == thumbnailColumn) {
                    holder.llCells.addView(buildThumbnailCell(ctx, row))
                    continue
                }
                if (i == switchColumn) {
                    holder.llCells.addView(buildSwitchCell(ctx, row, i))
                    continue
                }
                val tv = TextView(ctx)
                tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                tv.text = row.cells.getOrNull(i).orEmpty()
                tv.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_main))
                tv.textSize = 16f
                // Recycled cells carry the last row's setting, so both branches always
                // run rather than only the one that turns wrapping on.
                if (i in wrappingColumns) {
                    tv.maxLines = Int.MAX_VALUE
                    tv.ellipsize = null
                } else {
                    tv.maxLines = 1
                    tv.ellipsize = android.text.TextUtils.TruncateAt.END
                }
                tv.setPadding(0, 0, (8 * ctx.resources.displayMetrics.density).toInt(), 0)
                holder.llCells.addView(tv)
            }

            // Apply the dynamic theme to the entire row (Edit button, Checkbox, etc.)
            ThemeManager.applyTheme(holder.itemView)

            holder.cbRow.setOnCheckedChangeListener(null)
            holder.cbRow.isChecked = selectedIds.contains(row.id)
            holder.cbRow.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedIds.add(row.id) else selectedIds.remove(row.id)
                onSelectionChanged()
            }

            holder.btnEdit.setOnClickListener { onEdit(row) }

            // Recycled rows carry the previous row's action, so both branches are
            // always taken - a row with no action has to actively lose one.
            val actionIcon = rowActionIcon(row)
            if (actionIcon != null) {
                holder.btnAction.visibility = View.VISIBLE
                holder.btnAction.setIconResource(actionIcon)
                holder.btnAction.contentDescription = rowActionLabel
                // ThemeManager fills every MaterialButton above; this one is a
                // secondary action and reads as outlined.
                val accent = ThemeManager.getThemeColor(ctx)
                holder.btnAction.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                holder.btnAction.iconTint = ColorStateList.valueOf(accent)
                holder.btnAction.strokeColor = ColorStateList.valueOf(accent)
                holder.btnAction.setOnClickListener { onRowAction(row) }
            } else {
                holder.btnAction.visibility = View.GONE
                holder.btnAction.setOnClickListener(null)
            }

            if (showsTestPrintAction) {
                holder.btnTestPrint.visibility = View.VISIBLE
                holder.btnTestPrint.setOnClickListener { onTestPrint(row) }
            } else {
                holder.btnTestPrint.visibility = View.GONE
            }
        }

        /** A weighted table cell holding a rounded 40dp image thumbnail. */
        private fun buildThumbnailCell(ctx: android.content.Context, row: DataRow): View {
            val density = ctx.resources.displayMetrics.density
            val size = (40 * density).toInt()

            val slot = LinearLayout(ctx)
            slot.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            slot.gravity = Gravity.CENTER_VERTICAL
            slot.setPadding(0, 0, (8 * density).toInt(), 0)

            val card = MaterialCardView(ctx)
            card.layoutParams = LinearLayout.LayoutParams(size, size)
            card.radius = 8 * density
            card.cardElevation = 0f
            card.strokeWidth = (1 * density).toInt()
            card.setStrokeColor(android.graphics.Color.parseColor("#E0E0E0"))
            card.setCardBackgroundColor(android.graphics.Color.parseColor("#F1F3F4"))
            card.isClickable = true
            card.isFocusable = true
            card.setOnClickListener { onThumbnailClick(row) }

            val iv = ImageView(ctx)
            iv.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            val thumb = thumbnailProvider(row)
            if (thumb != null) {
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageBitmap(thumb)
            } else {
                iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = (10 * density).toInt()
                iv.setPadding(pad, pad, pad, pad)
                iv.setImageResource(android.R.drawable.ic_menu_gallery)
                iv.imageTintList = ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary)
                )
            }
            card.addView(iv)
            slot.addView(card)
            return slot
        }
        /** A weighted table cell holding an inline ON/OFF switch driven by cell text. */
        private fun buildSwitchCell(ctx: android.content.Context, row: DataRow, col: Int): View {
            val slot = LinearLayout(ctx)
            slot.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            slot.gravity = Gravity.CENTER_VERTICAL

            val sw = SwitchMaterial(ctx)
            val on = row.cells.getOrNull(col)?.lowercase() in ON_VALUES
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = on
            sw.thumbTintList = ColorStateList.valueOf(ThemeManager.getThemeColor(ctx))
            sw.setOnCheckedChangeListener { _, checked -> onSwitchToggled(row, checked) }
            slot.addView(sw)
            return slot
        }

        /** Shows the row's image as a circle, or a plain placeholder circle when absent. */
        private fun bindThumbnail(holder: ViewHolder, row: DataRow, ctx: android.content.Context) {
            if (!showsThumbnails) {
                holder.ivThumb.visibility = View.GONE
                return
            }
            holder.ivThumb.visibility = View.VISIBLE

            val bitmap = row.thumbnail?.let { decodeSampledBitmap(it, THUMB_PX) }
            if (bitmap == null) {
                holder.ivThumb.setImageDrawable(null)
                holder.ivThumb.setBackgroundResource(R.drawable.bg_thumb_placeholder)
                holder.ivThumb.isClickable = false
                holder.ivThumb.setOnClickListener(null)
            } else {
                holder.ivThumb.background = null
                holder.ivThumb.setImageDrawable(
                    RoundedBitmapDrawableFactory.create(ctx.resources, bitmap).apply {
                        isCircular = true
                    }
                )
                // Only rows that actually have an image open the preview.
                holder.ivThumb.setOnClickListener { onThumbClick(row) }
            }
        }

        override fun getItemCount() = rows.size

        private companion object {
            const val THUMB_PX = 120
            /** Cell values (lowercased) that render the inline switch as ON. */
            val ON_VALUES = setOf("on", "enabled", "yes", "active", "true")
        }
    }
}