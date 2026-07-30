package com.example.synergic_pos_offline.utils

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A [ScrollView] that never grows taller than a fraction of the screen height,
 * nor than the room its parent actually has for it.
 *
 * Plain ScrollView ignores `android:maxHeight`, so tall dialog content gets
 * clipped instead of scrolling. This clamps the measured height so short
 * content still wraps, while tall content scrolls within the cap.
 *
 * The parent's own limit is honoured alongside the screen cap. Taking the screen
 * cap alone would let this claim height a dialog has already spent on its title
 * and buttons, leaving them squeezed to nothing at the bottom of a long form -
 * and a form whose buttons cannot be read is a form that cannot be submitted.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // A height the parent has already decided on - LinearLayout's weighted pass
        // does this - is final; capping it again would undo the space it just gave.
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val screenCap = (resources.displayMetrics.heightPixels * MAX_SCREEN_FRACTION).toInt()
        val cap = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> screenCap
            else -> minOf(screenCap, MeasureSpec.getSize(heightMeasureSpec))
        }.coerceAtLeast(1)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }

    private companion object {
        const val MAX_SCREEN_FRACTION = 0.88f
    }
}
