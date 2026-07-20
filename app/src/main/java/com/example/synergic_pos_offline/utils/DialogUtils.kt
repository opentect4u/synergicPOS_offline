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
 */
object DialogUtils {

    private const val DESTRUCTIVE_COLOR = "#D93025"

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
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_common, null)
        val dialog = AlertDialog.Builder(context).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
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

        btnPositive.backgroundTintList = ColorStateList.valueOf(positiveColor)
        btnNegative.setTextColor(accent)
        btnNegative.strokeColor = ColorStateList.valueOf(accent)

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
        mandatoryFields: List<Int> = emptyList(),
        onSave: (List<String>) -> Unit
    ) {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_form, null)
        val dialog = AlertDialog.Builder(context).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val accent = ThemeManager.getThemeColor(context)

        view.findViewById<TextView>(R.id.tvFormTitle).text = title
        val grid = view.findViewById<GridLayout>(R.id.glFields)
        val btnPositive = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnNegative = view.findViewById<MaterialButton>(R.id.btnFormNegative)
        btnPositive.text = positiveText
        btnNegative.text = negativeText

        val inputs = ArrayList<TextInputEditText>(fields.size)
        val density = context.resources.displayMetrics.density
        val margin = (8 * density).toInt()

        var currentRow = 0
        var currentColumn = 0
        val colsPerRow = 2

        for (field in fields) {
            val til = inflater.inflate(R.layout.item_form_field, null, false) as TextInputLayout
            til.hint = field.label

            // Calculate row and column based on spanning
            if (currentColumn > 0 && field.spanColumns > 1) {
                currentRow++
                currentColumn = 0
            } else if (currentColumn + field.spanColumns > colsPerRow) {
                currentRow++
                currentColumn = 0
            }

            // Layout params for 2 columns with spanning support
            val params = GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(currentRow)
                columnSpec = GridLayout.spec(currentColumn, field.spanColumns.coerceAtMost(2), 1f)
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(margin, margin / 2, margin, margin / 2)
            }
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

            // Apply max length if specified
            if (field.maxLength > 0) {
                val filters = arrayOf(android.text.InputFilter.LengthFilter(field.maxLength))
                et.filters = filters
            }

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

        // Configure grid layout column widths
        grid.columnCount = 2

        ThemeManager.applyTheme(grid)
        btnPositive.backgroundTintList = ColorStateList.valueOf(accent)
        btnNegative.setTextColor(accent)
        btnNegative.strokeColor = ColorStateList.valueOf(accent)

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