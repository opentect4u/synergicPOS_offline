package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.TableDao
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

/**
 * "Table" master (restaurant only). The list shows one row per section
 * (name, its total table count, and the code range). Add generates a table per
 * code across From..To; edit opens that section's tables in a grid.
 */
class TableFragment : DataTableFragment() {

    override val screenTitle = "Table"

    override val columns = listOf("Section", "No. of Tables", "Range", "Waiter")

    private val dao: TableDao by lazy { TableDao(requireContext()) }
    private val cache = mutableMapOf<String, TableDao.Allocation>()
    private var sections: List<TableDao.SectionOption> = emptyList()
    private var waiterNames: Map<Long, String> = emptyMap()

    private val statuses = listOf("Available", "Occupied", "Reserved", "Cleaning", "Billing", "Blocked")

    override fun loadRows(): MutableList<DataRow> {
        cache.clear()
        sections = dao.sections()
        waiterNames = dao.waiters().associate { it.id to it.name }
        val allocations = dao.allocations()
        return allocations.mapNotNull { a ->
            val sid = a.sectionId ?: return@mapNotNull null
            val key = groupKey(sid, a.waiterId)
            cache[key] = a
            DataRow(
                key,
                listOf(
                    a.sectionName.ifBlank { "Sec-%03d".format(sid) },
                    a.count.toString(),
                    if (a.fromCode != null && a.toCode != null) "${a.fromCode}-${a.toCode}" else "—",
                    a.waiterId?.let { waiterNames[it] } ?: "—"
                )
            )
        }.toMutableList()
    }

    /** A section+waiter row key, e.g. "3|7" (waiter 7) or "3|-1" (no waiter). */
    private fun groupKey(sectionId: Long, waiterId: Long?) = "$sectionId|${waiterId ?: -1L}"

    override fun onAddRow() = showAddDialog()

    override fun onEditRow(row: DataRow) = showSectionTablesEditor(row)

    override fun onRowsDeleted(ids: Set<String>) {
        ids.forEach { key ->
            val a = cache[key] ?: return@forEach
            a.sectionId?.let { dao.deleteGroup(it, a.waiterId) }
        }
    }

    // ---- Add: generate a table per code across From..To ---------------------

    private fun showAddDialog() {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_table, null)
        com.example.synergic_pos_offline.utils.InputLimits.applyDefaults(view)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        view.findViewById<TextView>(R.id.tvDialogTitle).text = "Add Tables"
        val actSection = view.findViewById<MaterialAutoCompleteTextView>(R.id.actSection)
        val etFloor = view.findViewById<TextInputEditText>(R.id.etFloorNo)
        val actWaiter = view.findViewById<MaterialAutoCompleteTextView>(R.id.actWaiter)
        val etFrom = view.findViewById<TextInputEditText>(R.id.etFromTable)
        val etTo = view.findViewById<TextInputEditText>(R.id.etToTable)
        val etNoTables = view.findViewById<TextInputEditText>(R.id.etNoTables)
        val etNoLeft = view.findViewById<TextInputEditText>(R.id.etNoLeft)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)
        btnSave.text = "Add"

        var remaining = 0        // free slots left in the chosen section (capacity − already added)
        var syncing = false      // guard so the From→To auto-fill doesn't recurse

        fun recalc() {
            val from = etFrom.text?.toString()?.toIntOrNull()
            val to = etTo.text?.toString()?.toIntOrNull()
            etNoLeft.setText(
                if (from != null && to != null && to >= from) (remaining - (to - from + 1)).toString()
                else remaining.toString()
            )
        }
        actSection.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, sections.map { it.name }))
        actSection.setOnItemClickListener { _, _, pos, _ ->
            val s = sections[pos]
            actSection.tag = s.id
            etNoTables.setText(s.noOfTables.toString())
            val usage = dao.sectionUsage(s.id)
            remaining = (s.noOfTables - usage.count).coerceAtLeast(0)
            val nextFrom = (usage.maxCode ?: 0) + 1
            syncing = true
            etFrom.setText(nextFrom.toString())
            etTo.setText(if (remaining > 0) (nextFrom + remaining - 1).toString() else nextFrom.toString())
            syncing = false
            recalc()
        }
        // Entering From auto-derives To to fill the remaining slots; To stays editable.
        etFrom.addTextChangedListener {
            if (!syncing) {
                val from = etFrom.text?.toString()?.toIntOrNull()
                if (from != null && remaining > 0) {
                    syncing = true
                    etTo.setText((from + remaining - 1).toString())
                    syncing = false
                }
            }
            recalc()
        }
        etTo.addTextChangedListener { recalc() }

        val waiters = dao.waiters()
        actWaiter.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, waiters.map { it.name }))
        actWaiter.setOnItemClickListener { _, _, pos, _ -> actWaiter.tag = waiters[pos].id }

        ThemeManager.applyTheme(view)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val sectionId = actSection.tag as? Long
            if (sectionId == null) { actSection.error = "Section is required"; return@setOnClickListener }
            val from = etFrom.text?.toString()?.toIntOrNull()
            val to = etTo.text?.toString()?.toIntOrNull()
            if (from == null || to == null) { etFrom.error = "Enter From/To"; return@setOnClickListener }
            if (to < from) { etTo.error = "To must be ≥ From"; return@setOnClickListener }
            if (to - from + 1 > remaining) {
                etTo.error = "Only $remaining table(s) left in this section"; return@setOnClickListener
            }

            val waiterId = actWaiter.tag as? Long
            val n = dao.insertRange(sectionId, etFloor.text?.toString()?.trim().orEmpty(), from, to, waiterId)
            dialog.dismiss()
            refreshRows()
            toast("$n table(s) added")
        }

        showCentered(dialog)
    }

    // ---- Edit: the section's individual tables in a grid --------------------

    private fun showSectionTablesEditor(row: DataRow) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)
        val alloc = cache[row.id] ?: return
        val sectionId = alloc.sectionId ?: return
        val cap = alloc.sectionCapacity
        val groupWaiterId = alloc.waiterId

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_table_units, null)
        com.example.synergic_pos_offline.utils.InputLimits.applyDefaults(view)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        view.findViewById<TextView>(R.id.tvDialogTitle).text = "Tables — ${alloc.sectionName}"
        val info = view.findViewById<TextView>(R.id.tvUnitsInfo)
        val container = view.findViewById<LinearLayout>(R.id.llTableUnits)

        // Editable waiter for this group (prefilled with the group's waiter).
        val actWaiter = view.findViewById<MaterialAutoCompleteTextView>(R.id.actUnitsWaiter)
        val waiterOptions = dao.waiters()
        actWaiter.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, waiterOptions.map { it.name }))
        actWaiter.setOnItemClickListener { _, _, pos, _ -> actWaiter.tag = waiterOptions[pos].id }
        if (groupWaiterId != null) {
            actWaiter.tag = groupWaiterId
            waiterOptions.firstOrNull { it.id == groupWaiterId }?.let { actWaiter.setText(it.name, false) }
        }

        val tables = dao.tablesForGroup(sectionId, groupWaiterId)
        val floor = tables.firstOrNull()?.floorNo.orEmpty()
        // Tables of this section held by OTHER waiter groups (for the section-wide "left").
        val otherGroupTables = (dao.sectionUsage(sectionId).count - tables.size).coerceAtLeast(0)

        fun updateInfo() {
            val left = (cap - (otherGroupTables + container.childCount)).coerceAtLeast(0)
            val waiterName = groupWaiterId?.let { waiterNames[it] } ?: "—"
            info.text = "Section: ${alloc.sectionName}   •   Waiter: $waiterName   •   " +
                "No. of Tables: ${container.childCount}   •   No. of Left: $left"
        }

        // Serial removal: only the last table can be removed, so codes stay contiguous.
        fun updateRemoveButtons() {
            for (i in 0 until container.childCount) {
                container.getChildAt(i).findViewById<ImageButton>(R.id.btnRemoveUnit).visibility =
                    if (i == container.childCount - 1) android.view.View.VISIBLE else android.view.View.INVISIBLE
            }
        }
        fun renumber() {
            for (i in 0 until container.childCount) {
                container.getChildAt(i).findViewById<TextView>(R.id.tvUnitId).text = "${i + 1}"
            }
            updateRemoveButtons()
            updateInfo()
        }
        // Only Status can be changed here (full status dropdown); everything else is view-only.
        fun addRow(prefill: TableDao.TableRow?) {
            val r = LayoutInflater.from(ctx).inflate(R.layout.item_table_unit, container, false)
            r.findViewById<TextView>(R.id.etUnitCode).text = prefill?.tableCode.orEmpty()
            r.findViewById<AutoCompleteTextView>(R.id.actUnitStatus).apply {
                setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, statuses))
                threshold = 0
                keyListener = null
                setText(prefill?.status?.takeIf { it in statuses } ?: "Available", false)
                setOnClickListener { showDropDown() }
            }
            r.findViewById<ImageButton>(R.id.btnRemoveUnit).setOnClickListener {
                // Only the last row is removable (serial), so this always drops the tail.
                container.removeView(r); renumber()
            }
            container.addView(r)
            ThemeManager.applyTheme(r)
            renumber()
        }

        tables.forEach { addRow(it) }
        updateInfo()

        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)
        ThemeManager.applyTheme(view)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val tables = (0 until container.childCount).map { i ->
                val c = container.getChildAt(i)
                TableDao.TableRow(
                    id = 0,
                    sectionId = sectionId,
                    tableCode = c.findViewById<TextView>(R.id.etUnitCode).text?.toString()?.trim().orEmpty(),
                    floorNo = floor,
                    seatingCapacity = c.findViewById<EditText>(R.id.etUnitSeating).text?.toString()?.toIntOrNull() ?: 0,
                    status = c.findViewById<AutoCompleteTextView>(R.id.actUnitStatus).text?.toString()?.ifBlank { "Available" } ?: "Available"
                )
            }
            dao.replaceGroupTables(sectionId, groupWaiterId, tables, actWaiter.tag as? Long)
            dialog.dismiss()
            refreshRows()
            toast("Tables saved")
        }

        showCentered(dialog)
    }

    private fun showCentered(dialog: AlertDialog) {
        dialog.show()
        dialog.window?.apply {
            setLayout(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(android.view.Gravity.CENTER)
        }
    }
}
