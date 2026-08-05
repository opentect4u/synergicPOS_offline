package com.example.synergic_pos_offline.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.synergic_pos_offline.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Reusable, theme-aware dialogs used across the app.
 *
 * These cards are a fixed number of `dp` across - 380 for a message, 450 for a form
 * - which is what keeps a two-field dialog from stretching over a tablet. It also
 * means the card cannot grow when its contents do, so both ways a device can change
 * the size of things are pinned down here:
 *
 *  * **font size** is neutralised outright ([FixedFontScale]), because a dialog set
 *    to the largest text has the same width to fit it in and the surplus comes off
 *    the ends of the labels;
 *  * **display size** is followed but bounded ([fitToScreen]) - it changes how many
 *    `dp` the screen has, so at the largest setting a 450dp card is wider than the
 *    display it has to appear on.
 *
 * A dialog therefore reads the same on every device, and on a small screen it is
 * the card that gives way rather than its contents.
 */
object DialogUtils {

    private const val DESTRUCTIVE_COLOR = "#D93025"

    /**
     * The most of the screen's width a dialog may take.
     *
     * The card is designed at a fixed width, which is what stops a two-field form
     * stretching across a tablet. On a screen too narrow for that width - a phone,
     * or a tablet set to a large display size, which leaves fewer `dp` to go round -
     * the fixed width is wider than the screen and the dialog is cut off at both
     * edges. The share below is the ceiling; the designed width still wins wherever
     * it fits.
     */
    private const val MAX_SCREEN_WIDTH_FRACTION = 0.92f

    /**
     * Narrows [content] to what the screen can actually show, when its designed
     * width does not fit.
     *
     * Only ever narrows: a dialog that fits keeps the width it was drawn at, so this
     * cannot stretch a small form across a tablet.
     */
    private fun fitToScreen(context: Context, content: android.view.View?) {
        val params = content?.layoutParams ?: return
        val available = (context.resources.displayMetrics.widthPixels * MAX_SCREEN_WIDTH_FRACTION).toInt()
        if (available > 0 && params.width > available) {
            params.width = available
            content.layoutParams = params
        }
    }

    /** Shows a two-button confirmation dialog (Logout, Delete, Print confirmation, etc). */
    fun showConfirm(
        context: Context,
        title: String,
        message: String,
        positiveText: String = "Confirm",
        negativeText: String = "Cancel",
        iconRes: Int? = null,
        destructive: Boolean = false,
        onCancel: () -> Unit = {},
        onConfirm: () -> Unit
    ) {
        val ctx = FixedFontScale.wrap(context)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_common, null)
        fitToScreen(ctx, view.findViewById(R.id.llDialogContent))
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val accent = ThemeManager.getThemeColor(context)
        val positiveColor = if (destructive) Color.parseColor(DESTRUCTIVE_COLOR) else accent

        val ivIcon = view.findViewById<ImageView>(R.id.ivDialogIcon)
        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val tvMessage = view.findViewById<TextView>(R.id.tvDialogMessage)
        val btnPositive = view.findViewById<MaterialButton>(R.id.btnDialogPositive)
        val btnNegative = view.findViewById<MaterialButton>(R.id.btnDialogNegative)

        if (iconRes != null) {
            ivIcon.setImageResource(iconRes)
            ivIcon.imageTintList = ColorStateList.valueOf(positiveColor)
            ivIcon.visibility = android.view.View.VISIBLE
        } else {
            ivIcon.visibility = android.view.View.GONE
        }

        tvTitle.text = title
        tvMessage.text = message
        btnPositive.text = positiveText
        btnNegative.text = negativeText

        ThemeManager.styleDialogButtons(btnPositive, btnNegative, positiveColor)

        btnPositive.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        btnNegative.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }
        // Back-press / outside-tap counts as a cancel too.
        dialog.setOnCancelListener { onCancel() }

        dialog.show()
        centerWindow(dialog)
    }

    /** Shows a single-button informational dialog (e.g. "Saved successfully"). */
    fun showSuccess(
        context: Context,
        title: String = "Success",
        message: String,
        buttonText: String = "OK",
        iconRes: Int? = R.drawable.ic_check,
        onDismiss: () -> Unit = {}
    ) {
        val ctx = FixedFontScale.wrap(context)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_common, null)
        fitToScreen(ctx, view.findViewById(R.id.llDialogContent))
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val accent = ThemeManager.getThemeColor(context)

        val ivIcon = view.findViewById<ImageView>(R.id.ivDialogIcon)
        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val tvMessage = view.findViewById<TextView>(R.id.tvDialogMessage)
        val btnPositive = view.findViewById<MaterialButton>(R.id.btnDialogPositive)
        val btnNegative = view.findViewById<MaterialButton>(R.id.btnDialogNegative)

        if (iconRes != null) {
            ivIcon.setImageResource(iconRes)
            ivIcon.imageTintList = ColorStateList.valueOf(accent)
            ivIcon.visibility = android.view.View.VISIBLE
        } else {
            ivIcon.visibility = android.view.View.GONE
        }

        tvTitle.text = title
        tvMessage.text = message
        btnNegative.visibility = android.view.View.GONE
        btnPositive.text = buttonText
        ThemeManager.styleDialogButtons(btnPositive, null)

        btnPositive.setOnClickListener {
            dialog.dismiss()
            onDismiss()
        }
        dialog.setOnCancelListener { onDismiss() }

        dialog.show()
        centerWindow(dialog)
    }

    /** A single tappable row in [showList]. */
    data class ListItem(
        val title: String,
        val subtitle: String = "",
        /** Right-hand value (a total, a count); drawn in the accent colour. */
        val trailing: String = ""
    )

    /**
     * Shows a reusable picker: a titled card of tappable rows, reporting the index
     * of the row chosen. The list scrolls once it outgrows the card, so callers can
     * pass as many rows as they have.
     */
    fun showList(
        context: Context,
        title: String,
        items: List<ListItem>,
        subtitle: String = "",
        negativeText: String = "Close",
        onPick: (Int) -> Unit
    ) {
        val ctx = FixedFontScale.wrap(context)
        val inflater = LayoutInflater.from(ctx)
        val view = inflater.inflate(R.layout.dialog_list, null)
        fitToScreen(ctx, view.findViewById(R.id.llListContent))
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val accent = ThemeManager.getThemeColor(context)

        view.findViewById<TextView>(R.id.tvListTitle).text = title
        view.findViewById<TextView>(R.id.tvListSubtitle).apply {
            text = subtitle
            visibility = if (subtitle.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        }

        val container = view.findViewById<LinearLayout>(R.id.llListItems)
        items.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.item_list_row, container, false)
            row.findViewById<TextView>(R.id.tvRowTitle).text = item.title
            row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
                text = item.subtitle
                visibility = if (item.subtitle.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
            }
            row.findViewById<TextView>(R.id.tvRowTrailing).apply {
                text = item.trailing
                setTextColor(accent)
                visibility = if (item.trailing.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
            }
            (row as? com.google.android.material.card.MaterialCardView)?.strokeColor = accent
            row.setOnClickListener {
                dialog.dismiss()
                onPick(index)
            }
            container.addView(row)
        }

        val btnNegative = view.findViewById<MaterialButton>(R.id.btnListNegative)
        btnNegative.text = negativeText
        ThemeManager.styleDialogButtons(null, btnNegative)
        btnNegative.setOnClickListener { dialog.dismiss() }

        dialog.show()
        centerWindow(dialog)
    }

    /** A single labelled input field for [showForm]. */
    data class FormField(
        val label: String,
        val value: String,
        val isTextArea: Boolean = false,
        val spanColumns: Int = 1,
        val inputType: String = "text",
        val maxLength: Int = -1
    )

    /** Shows a reusable form dialog for Adding or Editing records. */
    fun showForm(
        context: Context,
        title: String,
        fields: List<FormField>,
        positiveText: String = "Save",
        negativeText: String = "Cancel",
        showNegative: Boolean = true,
        mandatoryFields: List<Int> = emptyList(),
        onSave: (List<String>) -> Unit
    ) {
        val ctx = FixedFontScale.wrap(context)
        val inflater = LayoutInflater.from(ctx)
        val view = inflater.inflate(R.layout.dialog_form, null)
        fitToScreen(ctx, view.findViewById(R.id.llFormContent))
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val accent = ThemeManager.getThemeColor(context)

        view.findViewById<TextView>(R.id.tvFormTitle).text = title
        val grid = view.findViewById<GridLayout>(R.id.glFields)
        
        // Dynamic column count: 1 if only one field, 2 for more.
        grid.columnCount = if (fields.size == 1) 1 else 2
        
        val btnPositive = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnNegative = view.findViewById<MaterialButton>(R.id.btnFormNegative)
        btnPositive.text = positiveText
        btnNegative.text = negativeText
        // Callers can drop the Cancel button entirely (e.g. a mandatory prompt); the
        // positive button then fills the row.
        btnNegative.visibility = if (showNegative) android.view.View.VISIBLE else android.view.View.GONE

        val inputs = ArrayList<TextInputEditText>(fields.size)
        val density = context.resources.displayMetrics.density
        val margin = (8 * density).toInt()

        var currentRow = 0
        var currentColumn = 0
        val colsPerRow = 2

        fields.forEachIndexed { index, field ->
            val til = inflater.inflate(R.layout.item_form_field, grid, false) as TextInputLayout
            til.hint = field.label
            
            val params = GridLayout.LayoutParams()

            // Determine column span. An explicit spanColumns wins; otherwise keep the
            // old behaviour of stretching a lone field, and the last field of an
            // odd-count form, to full width.
            val explicitSpan = field.spanColumns.coerceIn(1, grid.columnCount)
            val span = when {
                fields.size == 1 -> grid.columnCount
                explicitSpan > 1 -> explicitSpan
                index == fields.lastIndex && fields.size % 2 != 0 -> grid.columnCount
                else -> 1
            }

            params.width = 0 // Will be handled by weight
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span, 1f)
            params.setMargins(margin, 0, margin, margin)
            til.layoutParams = params

            val et = til.findViewById<TextInputEditText>(R.id.etField)
            et.setText(field.value)

            // Apply input type
            when (field.inputType) {
                "phone" -> et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                "number" -> et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                "email" -> et.inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                else -> et.inputType = android.text.InputType.TYPE_CLASS_TEXT
            }

            // Cap the field length. An explicit maxLength wins; otherwise a sensible
            // default keeps every form field bounded (text areas get more room),
            // so no input can grow without limit.
            val cap = if (field.maxLength > 0) field.maxLength else when {
                field.isTextArea -> InputLimits.TEXT_AREA
                field.inputType == "phone" -> InputLimits.PHONE
                field.inputType == "number" -> InputLimits.NUMBER
                else -> InputLimits.TEXT
            }
            et.filters = arrayOf(android.text.InputFilter.LengthFilter(cap))

            // Set textarea properties if needed
            if (field.isTextArea) {
                et.minLines = 3
                et.maxLines = 5
                et.isSingleLine = false
            }

            grid.addView(til, params)
            inputs.add(et)

            // Update position for next field
            currentColumn += field.spanColumns
            if (currentColumn >= colsPerRow) {
                currentRow++
                currentColumn = 0
            }
        }

        ThemeManager.applyTheme(grid)
        ThemeManager.styleDialogButtons(btnPositive, btnNegative)

        btnPositive.setOnClickListener {
            val values = inputs.map { it.text?.toString()?.trim().orEmpty() }

            // Validate mandatory fields
            val missingFields = mandatoryFields.filter { index ->
                index < values.size && values[index].isEmpty()
            }

            if (missingFields.isNotEmpty()) {
                val missingFieldNames = missingFields.mapNotNull { index ->
                    if (index < fields.size) fields[index].label else null
                }.joinToString(", ")
                android.widget.Toast.makeText(
                    context,
                    "Missing required fields: $missingFieldNames",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Validate phone number fields
            for (index in fields.indices) {
                if (fields[index].inputType == "phone" && values[index].isNotEmpty()) {
                    if (values[index].length != 10 || !values[index].all { it.isDigit() }) {
                        android.widget.Toast.makeText(
                            context,
                            "${fields[index].label} must be exactly 10 digits",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                }
            }

            dialog.dismiss()
            onSave(values)
        }
        btnNegative.setOnClickListener { dialog.dismiss() }

        dialog.show()
        centerWindow(dialog)
    }

    /** Shrinks the dialog window to its content so the card is centered. */
    private fun centerWindow(dialog: AlertDialog) {
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
    }
}