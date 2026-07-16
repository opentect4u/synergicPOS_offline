package com.example.synergic_pos_offline.utils

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A [ScrollView] that never grows taller than a fraction of the screen height.
 *
 * Plain ScrollView ignores `android:maxHeight`, so tall dialog content gets
 * clipped instead of scrolling. This clamps the measured height so short
 * content still wraps, while tall content scrolls within the cap.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxHeight = (resources.displayMetrics.heightPixels * MAX_SCREEN_FRACTION).toInt()
        val cappedSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, cappedSpec)
    }

    private companion object {
        const val MAX_SCREEN_FRACTION = 0.88f
    }
}
