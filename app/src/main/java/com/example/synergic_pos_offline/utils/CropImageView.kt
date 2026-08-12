package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * A minimal, dependency-free crop view: it draws the image fit-to-view and a movable,
 * resizable crop rectangle over it (drag the middle to move, a corner to resize). The
 * area outside the selection is dimmed. [getCroppedBitmap] returns the selected region
 * mapped back to source pixels and downscaled to a standard box.
 */
class CropImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val imageRect = RectF()   // where the bitmap is drawn (fit-centre)
    private val cropRect = RectF()    // current selection, kept within imageRect
    private var initialised = false

    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(2f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x66FFFFFF; strokeWidth = dp(1f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.WHITE
    }

    private val handleTouch = dp(26f)
    private val minSize = dp(48f)
    private var active = Handle.NONE
    private var lastX = 0f
    private var lastY = 0f

    private enum class Handle { NONE, MOVE, TL, TR, BL, BR }

    fun setImage(bmp: Bitmap) {
        bitmap = bmp
        initialised = false
        if (width > 0 && height > 0) computeImageRect()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeImageRect()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun computeImageRect() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = min(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        imageRect.set(left, top, left + dw, top + dh)
        if (!initialised) {
            val inset = min(dw, dh) * 0.08f
            cropRect.set(imageRect.left + inset, imageRect.top + inset, imageRect.right - inset, imageRect.bottom - inset)
            initialised = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        if (imageRect.isEmpty) computeImageRect()
        canvas.drawBitmap(bmp, null, imageRect, null)

        // Dim the four bands outside the crop rect.
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropRect.top, dimPaint)
        canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, imageRect.right, cropRect.bottom, dimPaint)

        // Rule-of-thirds grid.
        val tw = cropRect.width() / 3f
        val th = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + tw, cropRect.top, cropRect.left + tw, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + 2 * tw, cropRect.top, cropRect.left + 2 * tw, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + th, cropRect.right, cropRect.top + th, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + 2 * th, cropRect.right, cropRect.top + 2 * th, gridPaint)

        canvas.drawRect(cropRect, borderPaint)
        val r = dp(6f)
        for (p in corners()) canvas.drawCircle(p.x, p.y, r, handlePaint)
    }

    private fun corners() = listOf(
        PointF(cropRect.left, cropRect.top), PointF(cropRect.right, cropRect.top),
        PointF(cropRect.left, cropRect.bottom), PointF(cropRect.right, cropRect.bottom)
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = hitTest(event.x, event.y)
                lastX = event.x; lastY = event.y
                return active != Handle.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                applyDrag(event.x - lastX, event.y - lastY)
                lastX = event.x; lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = Handle.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Handle {
        fun near(px: Float, py: Float) = abs(x - px) <= handleTouch && abs(y - py) <= handleTouch
        return when {
            near(cropRect.left, cropRect.top) -> Handle.TL
            near(cropRect.right, cropRect.top) -> Handle.TR
            near(cropRect.left, cropRect.bottom) -> Handle.BL
            near(cropRect.right, cropRect.bottom) -> Handle.BR
            cropRect.contains(x, y) -> Handle.MOVE
            else -> Handle.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        when (active) {
            Handle.MOVE -> {
                val nl = (cropRect.left + dx).coerceIn(imageRect.left, imageRect.right - cropRect.width())
                val nt = (cropRect.top + dy).coerceIn(imageRect.top, imageRect.bottom - cropRect.height())
                cropRect.offsetTo(nl, nt)
            }
            Handle.TL -> {
                cropRect.left = (cropRect.left + dx).coerceIn(imageRect.left, cropRect.right - minSize)
                cropRect.top = (cropRect.top + dy).coerceIn(imageRect.top, cropRect.bottom - minSize)
            }
            Handle.TR -> {
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, imageRect.right)
                cropRect.top = (cropRect.top + dy).coerceIn(imageRect.top, cropRect.bottom - minSize)
            }
            Handle.BL -> {
                cropRect.left = (cropRect.left + dx).coerceIn(imageRect.left, cropRect.right - minSize)
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, imageRect.bottom)
            }
            Handle.BR -> {
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, imageRect.right)
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, imageRect.bottom)
            }
            Handle.NONE -> {}
        }
    }

    /**
     * The selected region mapped back to source pixels, then downscaled to fit within
     * [maxW] x [maxH] (longest edge), preserving aspect. Null if nothing is loaded.
     */
    fun getCroppedBitmap(maxW: Int, maxH: Int): Bitmap? {
        val bmp = bitmap ?: return null
        if (imageRect.isEmpty || imageRect.width() <= 0f) return null
        val perView = bmp.width / imageRect.width()   // source px per view px
        val sx = ((cropRect.left - imageRect.left) * perView).toInt().coerceIn(0, bmp.width - 1)
        val sy = ((cropRect.top - imageRect.top) * perView).toInt().coerceIn(0, bmp.height - 1)
        val sw = (cropRect.width() * perView).toInt().coerceIn(1, bmp.width - sx)
        val sh = (cropRect.height() * perView).toInt().coerceIn(1, bmp.height - sy)
        val cropped = Bitmap.createBitmap(bmp, sx, sy, sw, sh)
        val outScale = min(maxW / cropped.width.toFloat(), maxH / cropped.height.toFloat())
        if (outScale >= 1f) return cropped
        return Bitmap.createScaledBitmap(
            cropped,
            (cropped.width * outScale).toInt().coerceAtLeast(1),
            (cropped.height * outScale).toInt().coerceAtLeast(1),
            true
        )
    }
}
