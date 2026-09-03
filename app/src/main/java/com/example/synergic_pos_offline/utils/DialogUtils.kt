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

    /** The red a destructive confirm is drawn in - and the button that opens it. */
    const val DESTRUCTIVE_COLOR = "#D93025"

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
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

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

    /**
     * The last gate in front of something irreversible: the operator's own password,
     * typed again.
     *
     * Shown *after* the warning that says what is about to happen, never instead of
     * it - a password box on its own asks somebody to authorise a thing they have not
     * been told the shape of. What it adds is proof that the person holding the till
     * is the person the till is signed in as: the warning stops an accident, this
     * stops anybody who walked up to a screen left unattended on the About page.
     *
     * [verify] is handed the typed password and answers whether it is right; who is
     * signed in and how their password is stored are the caller's business, not this
     * dialog's. A wrong answer keeps the dialog open with the field marked and
     * emptied, because being thrown out to the start of a two-step confirmation for a
     * typo is how an operator ends up avoiding the safe path altogether.
     */
    fun showPasswordConfirm(
        context: Context,
        title: String,
        message: String,
        positiveText: String = "Confirm",
        negativeText: String = "Cancel",
        destructive: Boolean = false,
        onCancel: () -> Unit = {},
        verify: (String) -> Boolean,
        onConfirmed: () -> Unit
    ) {
        val ctx = FixedFontScale.wrap(context)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_password_confirm, null)
        fitToScreen(ctx, view.findViewById(R.id.llPwdConfirmContent))
        val dialog = AlertDialog.Builder(ctx).setView(view).create()
            .also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }

        val positiveColor =
            if (destructive) Color.parseColor(DESTRUCTIVE_COLOR) else ThemeManager.getThemeColor(context)

        view.findViewById<ImageView>(R.id.ivPwdConfirmIcon).imageTintList =
            ColorStateList.valueOf(positiveColor)
        view.findViewById<TextView>(R.id.tvPwdConfirmTitle).text = title
        view.findViewById<TextView>(R.id.tvPwdConfirmMessage).text = message

        val field = view.findViewById<TextInputLayout>(R.id.tilPwdConfirm)
        val input = view.findViewById<TextInputEditText>(R.id.etPwdConfirm)
        val btnPositive = view.findViewById<MaterialButton>(R.id.btnPwdConfirmPositive)
        val btnNegative = view.findViewById<MaterialButton>(R.id.btnPwdConfirmNegative)
        btnPositive.text = positiveText
        btnNegative.text = negativeText
        ThemeManager.styleDialogButtons(btnPositive, btnNegative, positiveColor)

        // Clears the complaint as soon as the operator starts correcting it.
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { field.error = null }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        fun submit() {
            val typed = input.text?.toString().orEmpty()
            when {
                typed.isEmpty() -> field.error = "Enter your password"
                !verify(typed) -> {
                    field.error = "That password is not right"
                    input.setText("")
                }
                else -> {
                    dialog.dismiss()
                    onConfirmed()
                }
            }
        }

        btnPositive.setOnClickListener { submit() }
        // Done on the keyboard is the same as pressing the button - a password field
        // is the one place people expect it to submit.
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submit(); true
            } else false
        }
        btnNegative.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }
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
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

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
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

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
        val maxLength: Int = -1,
        /**
         * "text", "dropdown", "toggle" or "checkboxes".
         *
         * "checkboxes" is the multi-select answer to "dropdown": [options] are shown as
         * a labelled row of boxes and ANY NUMBER of them can be on, where a dropdown
         * forces exactly one. Its value - in and out - is the checked options joined by
         * commas, so a caller reads it back with `split(",")` and nothing else about
         * the form changes.
         */
        val fieldType: String = "text", // "text", "dropdown", "toggle", "checkboxes"
        val options: List<String> = emptyList(), // For dropdown and checkboxes
        /** A "text" field shown but not editable - its [value] is fixed. */
        val locked: Boolean = false
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
        /**
         * Run when the form is dismissed WITHOUT saving - the negative button or a
         * back press.
         *
         * Optional, and null for the forms that are only ever an edit: cancelling
         * those means "leave it as it was", which needs nothing done. It exists for a
         * form standing in front of something else, where declining to fill it in is
         * still a decision to carry on - the take-away customer prompt, where Skip
         * means start the order without a customer rather than start no order at all.
         */
        onCancel: (() -> Unit)? = null,
        onSave: (List<String>) -> Unit
    ) {
        val ctx = FixedFontScale.wrap(context)
        val inflater = LayoutInflater.from(ctx)
        val view = inflater.inflate(R.layout.dialog_form, null)
        fitToScreen(ctx, view.findViewById(R.id.llFormContent))
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        val accent = ThemeManager.getThemeColor(context)

        view.findViewById<TextView>(R.id.tvFormTitle).text = title
        val grid = view.findViewById<GridLayout>(R.id.glFields)
        
        // Dynamic column count: 1 if only one field, 2 for more.
        grid.columnCount = if (fields.size == 1) 1 else 2
        
        /** Set by Save, so its own dismiss is not also reported as a cancel. */
        var saved = false
        val btnPositive = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnNegative = view.findViewById<MaterialButton>(R.id.btnFormNegative)
        btnPositive.text = positiveText
        btnNegative.text = negativeText
        // Callers can drop the Cancel button entirely (e.g. a mandatory prompt); the
        // positive button then fills the row.
        btnNegative.visibility = if (showNegative) android.view.View.VISIBLE else android.view.View.GONE

        val inputs = ArrayList<TextInputEditText>(fields.size)
        val toggles = HashMap<Int, Boolean>() // For toggle fields: index -> value
        // Checkbox fields: index -> the boxes, in the order the caller listed them, so
        // the value read back out is in that order too rather than tick order.
        val checkboxes = HashMap<Int, List<android.widget.CheckBox>>()
        val density = context.resources.displayMetrics.density
        val margin = (8 * density).toInt()

        var currentRow = 0
        var currentColumn = 0
        val colsPerRow = 2

        fields.forEachIndexed { index, field ->
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

            when (field.fieldType) {
                "toggle" -> {
                    // A SWITCH IN A BOX, so it sits in the row as an equal.
                    //
                    // It used to be a bare label and switch with no ground under them,
                    // dropped between two outlined fields. Beside a bordered box it read
                    // as text that had escaped its field: no edge, no fill, a different
                    // height, and the label in body-sized black where the fields put
                    // theirs small and grey on the border.
                    //
                    // Same background, same corner radius and the same 56dp height as
                    // item_form_field, so a row of "Type | Enabled" is two controls of
                    // one size rather than a control and a caption.
                    val container = android.widget.LinearLayout(ctx).apply {
                        layoutParams = params
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        minimumHeight = (56 * density).toInt()
                        background = androidx.core.content.ContextCompat
                            .getDrawable(ctx, R.drawable.bg_dropdown_field)
                        setPadding(
                            (14 * density).toInt(), 0, (10 * density).toInt(), 0
                        )
                    }
                    val label = android.widget.TextView(ctx).apply {
                        text = field.label
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                        )
                        textSize = 14f
                        setTextColor(ctx.resources.getColor(R.color.text_main, null))
                    }
                    // The state in words beside the switch. A switch alone is read by
                    // its position, which is a thing to know rather than see - and on
                    // a form that is saved and reopened, "On" or "Off" is what the
                    // operator is checking.
                    val state = android.widget.TextView(ctx).apply {
                        textSize = 12f
                        setTextColor(ctx.resources.getColor(R.color.text_secondary, null))
                        setPadding(0, 0, (8 * density).toInt(), 0)
                    }
                    val toggle = com.google.android.material.switchmaterial.SwitchMaterial(ctx).apply {
                        isChecked = field.value.lowercase() in listOf("yes", "true", "enabled", "on", "1")
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    state.text = if (toggle.isChecked) "On" else "Off"
                    container.addView(label)
                    container.addView(state)
                    container.addView(toggle)
                    // The margin the outlined fields carry themselves (item_form_field's
                    // 12dp bottom), applied here so the row's two halves end level.
                    params.setMargins(margin, 0, margin, margin + (12 * density).toInt())
                    grid.addView(container, params)
                    toggles[index] = toggle.isChecked
                    toggle.setOnCheckedChangeListener { _, isChecked ->
                        toggles[index] = isChecked
                        state.text = if (isChecked) "On" else "Off"
                    }
                    // Add empty EditText to inputs list for consistency
                    inputs.add(TextInputEditText(ctx).apply { visibility = android.view.View.GONE })
                }
                "checkboxes" -> {
                    // A label with the options under it, laid out like the other
                    // fields rather than as a dialog of its own: the answer is several
                    // of a short list, and a picker that has to be opened, scrolled and
                    // dismissed to tick two boxes hides how many are on. Here the
                    // answer is readable without touching anything.
                    // On the same ground as the fields around it - see the toggle above.
                    // A bare label with three boxes under it read as loose text on the
                    // dialog rather than as the form's last question.
                    val container = android.widget.LinearLayout(ctx).apply {
                        layoutParams = params
                        orientation = android.widget.LinearLayout.VERTICAL
                        background = androidx.core.content.ContextCompat
                            .getDrawable(ctx, R.drawable.bg_dropdown_field)
                        setPadding(
                            (14 * density).toInt(), (10 * density).toInt(),
                            (14 * density).toInt(), (10 * density).toInt()
                        )
                    }
                    container.addView(
                        android.widget.TextView(ctx).apply {
                            text = field.label
                            textSize = 12f
                            setTextColor(ctx.resources.getColor(R.color.text_secondary, null))
                        }
                    )
                    // One row, and the field is expected to span the dialog's full
                    // width (spanColumns) so the options have the room. A handful of
                    // short labels is what this is for; a long list belongs in a
                    // dropdown, where scrolling is the point.
                    val row = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                    }
                    // Ticked from the value: the same comma list this field gives back,
                    // so an edit opens on exactly what was saved.
                    val on = field.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val boxes = field.options.map { option ->
                        android.widget.CheckBox(ctx).apply {
                            text = option
                            textSize = 14f
                            isChecked = on.any { it.equals(option, ignoreCase = true) }
                            setPadding(0, 0, (12 * density).toInt(), 0)
                        }
                    }
                    boxes.forEach { row.addView(it) }
                    container.addView(row)
                    grid.addView(container, params)
                    checkboxes[index] = boxes
                    // A placeholder so `inputs` stays index-aligned with `fields`, the
                    // same thing the toggle branch above does.
                    inputs.add(TextInputEditText(ctx).apply { visibility = android.view.View.GONE })
                }
                "dropdown" -> {
                    // Create a dropdown using dialog selection
                    val til = inflater.inflate(R.layout.item_form_field, grid, false) as TextInputLayout
                    til.hint = field.label
                    til.layoutParams = params

                    val et = til.findViewById<TextInputEditText>(R.id.etField)
                    et.setText(field.value)
                    et.inputType = android.text.InputType.TYPE_NULL
                    et.isFocusable = false
                    et.isClickable = true
                    et.isCursorVisible = false

                    // Show selection dialog on click
                    et.setOnClickListener {
                        AlertDialog.Builder(ctx)
                            .setTitle(field.label)
                            .setItems(field.options.toTypedArray()) { _, which ->
                                et.setText(field.options[which])
                            }
                            .show()
                    }

                    grid.addView(til, params)
                    inputs.add(et)
                }
                else -> {
                    // Regular text field
                    val til = inflater.inflate(R.layout.item_form_field, grid, false) as TextInputLayout
                    til.hint = field.label
                    til.layoutParams = params

                    val et = til.findViewById<TextInputEditText>(R.id.etField)
                    et.setText(field.value)

                    // Apply input type
                    when (field.inputType) {
                        "phone" -> et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        "number" -> et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        "email" -> et.inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        "decimal" -> et.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
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

                    // Shown, not editable - the value is fixed (e.g. Parcel Charge's
                    // name), so there is nothing here for the operator to type into.
                    if (field.locked) et.isEnabled = false

                    grid.addView(til, params)
                    inputs.add(et)
                }
            }

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
            // Collect values from both text inputs and toggle fields
            val values = fields.mapIndexed { index, field ->
                when (field.fieldType) {
                    "toggle" -> if (toggles[index] == true) "Yes" else "No"
                    // The ticked options, comma-joined, in the order they were listed.
                    // Empty when none is ticked - which is a real answer ("applies to
                    // nothing"), not a missing one, so it is left to the caller to
                    // decide whether that is allowed.
                    "checkboxes" -> checkboxes[index].orEmpty()
                        .filter { it.isChecked }
                        .joinToString(",") { it.text.toString() }
                    else -> inputs.getOrNull(index)?.text?.toString()?.trim().orEmpty()
                }
            }

            // Validate mandatory fields (skip for toggle fields, they're always filled)
            val missingFields = mandatoryFields.filter { index ->
                index < values.size && values[index].isEmpty() && fields[index].fieldType != "toggle"
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

            saved = true
            dialog.dismiss()
            onSave(values)
        }
        btnNegative.setOnClickListener { dialog.dismiss() }
        // Every way out that is NOT Save runs onCancel - the negative button and the
        // back press - so a caller standing this form in front of something else only
        // has to handle "declined" once. [saved] is what keeps Save's own dismiss from
        // counting as a decline as well.
        dialog.setOnDismissListener { if (!saved) onCancel?.invoke() }

        dialog.show()
        centerWindow(dialog)
    }

    /** Shrinks the dialog window to its content so the card is centered. */
    private fun centerWindow(dialog: AlertDialog) {
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            // NOTE: App language is NOT applied to dialogs. It only affects product
            // names in sale screens - every dialog, including product/customer/charge
            // forms, stays in English regardless of the app language setting.
        }
    }

    /**
     * Builds an [AlertDialog] for a bespoke [contentLayout] wrapped in the same chrome
     * as [showConfirm]/[showSuccess] — neutralised font scale, transparent window,
     * centered, and width-fit to the screen — so a custom dialog matches the global
     * design. The layout's width-controlling container must have id `llDialogContent`
     * (as in dialog_common). Returns the dialog and its inflated root; the caller wires
     * up the views and calls `dialog.show()`.
     */
    fun buildCustom(context: Context, contentLayout: Int): Pair<AlertDialog, android.view.View> {
        val ctx = FixedFontScale.wrap(context)
        val view = LayoutInflater.from(ctx).inflate(contentLayout, null)
        fitToScreen(ctx, view.findViewById(R.id.llDialogContent))
        val dialog = AlertDialog.Builder(ctx).setView(view).create()
            .also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Re-apply on show so the card stays wrap-content and centered.
        dialog.setOnShowListener { centerWindow(dialog) }
        return dialog to view
    }
}