package com.example.synergic_pos_offline.utils

import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/**
 * Central character limits for text entry, so every form is bounded the same way
 * and no field (an address, a note, a name) can be typed without end. The numbers
 * are deliberately generous - they exist to stop runaway input, not to police the
 * exact shape of a value; format checks stay with each field's own validation.
 */
object InputLimits {
    /** Free single-line text: names of people, stores, products, categories, etc. */
    const val TEXT = 60
    /** Multi-line free text: addresses, notes, header/footer lines. */
    const val TEXT_AREA = 250
    /** A phone number, room for a country code. */
    const val PHONE = 15
    /** A GSTIN is fixed at 15 characters. */
    const val GSTIN = 15
    /** Codes: barcode, SKU, HSN. */
    const val CODE = 40
    /** A numeric amount / count. */
    const val NUMBER = 12

    /** Caps [field]'s length at [max], keeping any filters it already carries. */
    fun cap(field: EditText?, max: Int) {
        if (field == null) return
        val existing = field.filters.filterNot { it is InputFilter.LengthFilter }
        field.filters = (existing + InputFilter.LengthFilter(max)).toTypedArray()
    }

    /**
     * Walks a form and caps every editable text field that isn't already bounded,
     * choosing a limit from the field's input type. A field that already declares its
     * own maxLength (e.g. a GSTIN capped at 15 in XML) is left untouched, and pickers /
     * dropdowns (input type "none", not typed into) are skipped. Call once on a
     * dialog/form root after inflation so nothing goes out uncapped.
     */
    fun applyDefaults(root: View?) {
        when (root) {
            null -> return
            is ViewGroup -> for (i in 0 until root.childCount) applyDefaults(root.getChildAt(i))
            is EditText -> {
                // Already bounded, or a read-only picker/dropdown → leave it alone.
                if (root.filters.any { it is InputFilter.LengthFilter }) return
                val type = root.inputType
                if (type == InputType.TYPE_NULL || !root.isFocusable) return
                val cls = type and InputType.TYPE_MASK_CLASS
                val multiLine = (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
                cap(root, when {
                    cls == InputType.TYPE_CLASS_PHONE -> PHONE
                    cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_DATETIME -> NUMBER
                    multiLine -> TEXT_AREA
                    else -> TEXT
                })
            }
        }
    }
}
