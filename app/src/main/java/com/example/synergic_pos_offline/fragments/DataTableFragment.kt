package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
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
import android.widget.CheckBox
import android.widget.ImageButton
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
import com.google.android.material.button.MaterialButton
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
            showsThumbnails,
            onEdit = { onEditRow(it) },
            onThumbClick = { showImagePreview(it) },
            onSelectionChanged = { updateSelectionUI() }
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

        view.findViewById<View>(R.id.btnAdd).setOnClickListener { onAddRow() }
        
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

    private fun applyFilter(q: String) {
        query = q.trim()
        shownRows.clear()
        if (query.isEmpty()) {
            shownRows.addAll(allRows)
        } else {
            shownRows.addAll(allRows.filter { row ->
                row.cells.any { it.contains(query, ignoreCase = true) }
            })
        }
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (shownRows.isEmpty()) View.VISIBLE else View.GONE
        if (::cbSelectAll.isInitialized) updateSelectionUI()
    }

    protected fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    /** Opens the row's image full size. Called when a row thumbnail is tapped. */
    private fun showImagePreview(row: DataRow) {
        val bitmap = row.thumbnail?.let { decodeSampledBitmap(it, PREVIEW_PX) } ?: return
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_image_preview, null)
        val dialog = AlertDialog.Builder(requireContext()).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        view.findViewById<TextView>(R.id.tvPreviewTitle).text =
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
        applyFilter(query)
    }

    // ---- Actions -----------------------------------------------------------

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
        val count = selectedIds.size
        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Delete Selected",
            message = "Are you sure you want to delete $count selected record(s)?",
            positiveText = "Delete All",
            negativeText = "Cancel",
            iconRes = android.R.drawable.ic_menu_delete,
            destructive = true
        ) {
            allRows.removeAll { selectedIds.contains(it.id) }
            selectedIds.clear()
            applyFilter(query)
            toast("Deleted $count record(s)")
        }
    }

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
        private val showsThumbnails: Boolean,
        private val onEdit: (DataRow) -> Unit,
        private val onThumbClick: (DataRow) -> Unit,
        private val onSelectionChanged: () -> Unit
    ) : RecyclerView.Adapter<DataTableAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cbRow: CheckBox = view.findViewById(R.id.cbRow)
            val ivThumb: android.widget.ImageView = view.findViewById(R.id.ivRowThumb)
            val llCells: LinearLayout = view.findViewById(R.id.llCells)
            val btnEdit: View = view.findViewById(R.id.btnRowEdit)
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
                val tv = TextView(ctx)
                tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                tv.text = row.cells.getOrNull(i).orEmpty()
                tv.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_main))
                tv.textSize = 16f
                tv.maxLines = 1
                tv.ellipsize = android.text.TextUtils.TruncateAt.END
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
        }
    }
}