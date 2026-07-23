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
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout

/**
 * Central store + applier for the app's dynamic accent color.
 */
object ThemeManager {
    private const val PREF_NAME = "theme_prefs"
    private const val KEY_THEME_COLOR = "theme_color"
    const val DEFAULT_COLOR = "#008181"

    val PALETTE = listOf(
        "#008181", "#1A73E8", "#1E8E3E", "#D93025", "#F9AB00", "#8E24AA", "#455A64", "#000000"
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
            is MaterialButton -> {
                // Determine if this button should be Outlined (White bg, colored border) 
                // or Filled (colored bg, white text).
                if (isSecondary(name)) {
                    // Outlined style matching Dialog Cancel button
                    view.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                    view.setTextColor(color)
                    view.strokeColor = tint
                    view.strokeWidth = (view.resources.displayMetrics.density * 1.5f).toInt()
                    view.iconTint = tint
                } else {
                    // Primary Filled style
                    view.backgroundTintList = tint
                    view.setTextColor(Color.WHITE)
                    view.strokeWidth = 0
                    view.iconTint = ColorStateList.valueOf(Color.WHITE)
                }
                // Ensure rounded corners match the dialog design (12dp)
                view.cornerRadius = (view.resources.displayMetrics.density * 12).toInt()
            }

            is FloatingActionButton -> view.backgroundTintList = tint

            // Switch: accent thumb + translucent accent track when ON, grey when OFF.
            is SwitchMaterial -> {
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
                view.thumbTintList = ColorStateList(states, intArrayOf(color, Color.parseColor("#FAFAFA")))
                view.trackTintList = ColorStateList(
                    states,
                    intArrayOf(ColorUtils.setAlphaComponent(color, 0x66), Color.parseColor("#C4C4C4"))
                )
            }

            // RadioButton (must precede TextView since it is a TextView subclass).
            is RadioButton -> {
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
                view.buttonTintList = ColorStateList(states, intArrayOf(color, Color.parseColor("#888888")))
            }

            is CheckBox -> view.buttonTintList = tint

            is TextInputLayout -> themeTextInput(view, color, tint)

            is ImageView -> if (name == "btnBack" || name == "btnMenu" ||
                name == "btnTheme" || name == "ivChevron" ||
                name == "btnRowEdit" || name == "btnRowDelete" || name == "btnRowPrint" ||
                name == "btnGlobalPrint" || name == "btnGlobalDelete" ||
                name == "btnPlus" || name == "btnMinus" || name == "btnRemoveLine" || name == "btnRemove") {
                view.imageTintList = tint
            }

            is TextView -> if (name != null) {
                if (name.endsWith("Header") || name == "tvForgot" || name == "tvSelectionCount") {
                    view.setTextColor(color)
                } else if (name == "tvTotal" || name == "tvLeftTotal" || name == "tvAmountDue") {
                    // These are on dark teal backgrounds, so keep them white.
                    // NOTE: matching is by resource name alone, so it hits *every*
                    // layout that reuses the id. Only list ids that are unique to a
                    // dark-background screen - a name shared with a light layout
                    // paints white-on-white there and the text vanishes.
                    view.setTextColor(Color.WHITE)
                }
            }
        }

        // Solid accent panels
        if (name == "sidebarHeader" || name == "barTotal" || name == "barLeftTotal" || name == "barAmountDue") {
            view.setBackgroundColor(color)
        }
    }

    private fun isSecondary(name: String?): Boolean {
        if (name == null) return false
        val low = name.lowercase()
        return low.contains("cancel") || 
               low.contains("negative") || 
               low.contains("back") ||
               low.contains("add") ||
               low.contains("apply") ||
               low.contains("exact") ||
               low.contains("btn20") || low.contains("btn50") || low.contains("btn100") ||
               low.contains("receipt") ||
               low.contains("card") || low.contains("wallet") || low.contains("split") ||
               low.contains("hold")
    }

    private fun themeTextInput(input: TextInputLayout, color: Int, tint: ColorStateList) {
        val strokeStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(-android.R.attr.state_focused)),
            intArrayOf(color, Color.parseColor("#B0B0B0"))
        )
        input.setBoxStrokeColorStateList(strokeStates)
        input.hintTextColor = tint
        input.setStartIconTintList(tint)
        input.setEndIconTintList(tint)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            input.editText?.textCursorDrawable?.let { it.setTint(color) }
        }
    }
}