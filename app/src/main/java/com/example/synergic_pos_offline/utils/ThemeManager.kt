package com.example.synergic_pos_offline.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout

/**
 * Central store + applier for the app's dynamic accent color.
 *
 * The chosen color is persisted in SharedPreferences and applied at runtime by
 * walking a view tree ([applyTheme]) and re-tinting every "primary colored"
 * piece of chrome, identified by its resource-entry name so no per-screen
 * bookkeeping is required.
 */
object ThemeManager {
    private const val PREF_NAME = "theme_prefs"
    private const val KEY_THEME_COLOR = "theme_color"
    const val DEFAULT_COLOR = "#008181"

    /** Preset palette shown in the color picker. */
    val PALETTE = listOf(
        "#008181", // Teal (default)
        "#1A73E8", // Blue
        "#1E8E3E", // Green
        "#D93025", // Red
        "#F9AB00", // Amber
        "#8E24AA", // Purple
        "#455A64", // Blue Grey
        "#000000"  // Black
    )

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun setThemeColor(context: Context, colorHex: String) {
        getPrefs(context).edit().putString(KEY_THEME_COLOR, colorHex).apply()
    }

    fun getThemeColor(context: Context): Int {
        val colorHex = getPrefs(context).getString(KEY_THEME_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR
        return Color.parseColor(colorHex)
    }

    /** Applies the current theme color to [root] and all of its descendants. */
    fun applyTheme(root: View) {
        applyRecursive(root, getThemeColor(root.context))
    }

    private fun applyRecursive(view: View, color: Int) {
        themeSingle(view, color)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyRecursive(view.getChildAt(i), color)
            }
        }
    }

    private fun themeSingle(view: View, color: Int) {
        val tint = ColorStateList.valueOf(color)
        val name = if (view.id != View.NO_ID) {
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        } else null

        when (view) {
            // Filled buttons (login, admin actions) take the accent as background.
            is MaterialButton -> view.backgroundTintList = tint

            // Floating action buttons (Add New, etc.) also follow the theme.
            is FloatingActionButton -> view.backgroundTintList = tint

            // Checkboxes (Select All, Row Selection) also follow the theme.
            is CheckBox -> view.buttonTintList = tint

            // Outlined text fields: focused stroke, floating hint, icons & cursor.
            is TextInputLayout -> themeTextInput(view, color, tint)

            // Only the navigation icons follow the accent; menu-card icons and
            // the logo keep their own colors.
            is ImageView -> if (name == "btnBack" || name == "btnMenu" ||
                name == "btnTheme" || name == "ivChevron" ||
                name == "btnRowEdit" || name == "btnRowDelete" || name == "btnRowPrint" ||
                name == "btnGlobalPrint" || name == "btnGlobalDelete") {
                view.imageTintList = tint
            }

            // Page titles (tv*Header) and accent links follow the accent.
            is TextView -> if (name != null && (name.endsWith("Header") || name == "tvForgot")) {
                view.setTextColor(color)
            }
        }

        // Solid accent panels (drawer header, etc.).
        if (name == "sidebarHeader") {
            view.setBackgroundColor(color)
        }
    }

    private fun themeTextInput(input: TextInputLayout, color: Int, tint: ColorStateList) {
        // Focused stroke uses the accent; idle stroke stays a neutral grey.
        val strokeStates = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(-android.R.attr.state_focused)
            ),
            intArrayOf(color, Color.parseColor("#B0B0B0"))
        )
        input.setBoxStrokeColorStateList(strokeStates)
        input.hintTextColor = tint          // floating label when focused
        input.setStartIconTintList(tint)     // leading icon (user / lock)
        input.setEndIconTintList(tint)       // password toggle

        // Blinking caret color (API 29+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            input.editText?.textCursorDrawable?.let { it.setTint(color) }
        }
    }
}
