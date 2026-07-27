package com.example.synergic_pos_offline.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView

/**
 * OS-settings-style "jump to setting": after a settings screen opens from the
 * global search, scroll the matching row into view and flash it briefly.
 *
 * Rows are matched by their on-screen title text (no per-row ids needed).
 */
object SettingsHighlighter {

    const val ARG_SETTING = "highlight_setting"

    /** Finds the row titled [target] under [root], scrolls to it and flashes it. */
    fun apply(root: View, target: String?) {
        val q = target?.trim().orEmpty()
        if (q.isEmpty()) return
        // Wait for layout so positions and the scroll container are measured.
        root.post {
            val tv = findMatch(root, q) ?: return@post
            val row = rowOf(tv)
            scrollTo(row)
            flash(row)
        }
    }

    /** Prefers an exact (case-insensitive) title, else the first that contains it. */
    private fun findMatch(root: View, q: String): TextView? {
        val all = mutableListOf<TextView>()
        collect(root, all)
        return all.firstOrNull { it.text?.toString()?.trim().equals(q, ignoreCase = true) }
            ?: all.firstOrNull { it.text?.toString()?.contains(q, ignoreCase = true) == true }
    }

    private fun collect(v: View, out: MutableList<TextView>) {
        if (v is TextView) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    /** The title's row container (title → text column → row), best-effort. */
    private fun rowOf(tv: TextView): View {
        val parent = tv.parent as? View ?: return tv
        return (parent.parent as? View) ?: parent
    }

    private fun scrollTo(row: View) {
        var vp: ViewParent? = row.parent
        while (vp != null && vp !is ScrollView && vp !is NestedScrollView) vp = vp.parent
        val scroller = vp as? ViewGroup ?: return
        val rect = Rect().also { row.getDrawingRect(it) }
        scroller.offsetDescendantRectToMyCoords(row, rect)
        val y = (rect.top - 48).coerceAtLeast(0)
        when (scroller) {
            is ScrollView -> scroller.smoothScrollTo(0, y)
            is NestedScrollView -> scroller.smoothScrollTo(0, y)
        }
    }

    /** Pulses a translucent accent background on the row twice, then restores it. */
    private fun flash(row: View) {
        val accent = ThemeManager.getThemeColor(row.context)
        val highlight = ColorUtils.setAlphaComponent(accent, 0x40)
        val original = row.background
        ValueAnimator.ofArgb(Color.TRANSPARENT, highlight, Color.TRANSPARENT, highlight, Color.TRANSPARENT).apply {
            duration = 1700
            startDelay = 300
            addUpdateListener { row.setBackgroundColor(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { row.background = original }
            })
            start()
        }
    }
}
