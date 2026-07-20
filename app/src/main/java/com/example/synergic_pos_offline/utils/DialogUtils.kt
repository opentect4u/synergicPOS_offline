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
    data class FormField(val label: String, val value: String)

    /** Shows a reusable form dialog for Adding or Editing records. */
    fun showForm(
        context: Context,
        title: String,
        fields: List<FormField>,
        positiveText: String = "Save",
        negativeText: String = "Cancel",
        onSave: (List<String>) -> Unit
    ) {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_form, null)
        val dialog = AlertDialog.Builder(context).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
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

        val inputs = ArrayList<TextInputEditText>(fields.size)
        val density = context.resources.displayMetrics.density
        val margin = (8 * density).toInt()

        fields.forEachIndexed { index, field ->
            val til = inflater.inflate(R.layout.item_form_field, grid, false) as TextInputLayout
            til.hint = field.label
            
            val params = GridLayout.LayoutParams()
            
            // Determine column span.
            val isFullWidth = fields.size == 1 || (index == fields.lastIndex && fields.size % 2 != 0)
            val span = if (isFullWidth) grid.columnCount else 1
            
            params.width = 0 // Will be handled by weight
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span, 1f)
            params.setMargins(margin, 0, margin, margin)
            til.layoutParams = params
            
            val et = til.findViewById<TextInputEditText>(R.id.etField)
            et.setText(field.value)
            grid.addView(til)
            inputs.add(et)
        }

        ThemeManager.applyTheme(grid)
        btnPositive.backgroundTintList = ColorStateList.valueOf(accent)
        btnNegative.setTextColor(accent)
        btnNegative.strokeColor = ColorStateList.valueOf(accent)

        btnPositive.setOnClickListener {
            val values = inputs.map { it.text?.toString()?.trim().orEmpty() }
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