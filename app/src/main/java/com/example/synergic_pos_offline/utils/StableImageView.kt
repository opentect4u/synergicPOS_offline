package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * An ImageView whose size never depends on its picture - so changing the picture does
 * not ask the whole screen to lay itself out again.
 *
 * ## Why the sale grids need it
 *
 * A plain ImageView calls requestLayout() whenever it is given a drawable of a
 * different size from the last one. On a sale tile that is every time: a tile is bound
 * empty and its photo arrives a moment later from the background decoder, and photos
 * are not all one shape. Each of those requests climbs to the top of the window and
 * lays out the entire sale screen - the order panel, the totals, the category strip
 * and every tile - so switching category, which brings forty-odd photos in one after
 * another, meant forty-odd full layouts in a row. That was the pause after a tap.
 *
 * Only for a view whose size is fixed by its layout (match_parent inside a frame of
 * its own, as the tile's 4:3 banner is): its bounds cannot change with the picture, so
 * the layout it asked for could never have moved anything. It is simply redrawn.
 */
class StableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private var settingImage = false

    override fun setImageDrawable(drawable: Drawable?) {
        settingImage = true
        try { super.setImageDrawable(drawable) } finally { settingImage = false }
    }

    override fun requestLayout() {
        // A picture change cannot move this view - redraw instead of relaying out.
        if (settingImage && width > 0 && height > 0) invalidate() else super.requestLayout()
    }
}
