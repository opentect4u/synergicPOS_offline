package com.example.synergic_pos_offline.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.oned.Code128Writer

/**
 * Draws a label job, as [TsplLabel.build] produced it, onto a bitmap - one feed of the
 * roll, 1 pixel per printer dot.
 *
 * It reads the same TSPL the printer is sent (the first `SIZE`/`TEXT`/`BARCODE` block),
 * so the preview cannot disagree with the print about where anything goes: change a
 * gap or a size and both follow. Bitmap fonts are approximated with a monospace face
 * set to the same advance width, which is the one thing a screen cannot reproduce
 * exactly.
 */
object TsplPreview {

    private val TEXT = Regex("""TEXT (-?\d+),(-?\d+),"(\d)",\d+,\d+,\d+,"(.*)"""")
    private val BARCODE = Regex("""BARCODE (-?\d+),(-?\d+),"[^"]*",(\d+),\d+,\d+,(\d+),\d+,"(.*)"""")
    private val SIZE = Regex("""SIZE (\d+) mm,\s*(\d+) mm""")

    /** Character cell (width, height) in dots for each TSPL bitmap font used by labels. */
    private fun cell(font: String): Pair<Int, Int> = when (font) {
        "1" -> 8 to 12
        "2" -> 12 to 20
        else -> 16 to 24
    }

    /**
     * @param job       bytes from [TsplLabel.build]
     * @param vGapMm    the vertical gap, drawn as a grey strip under the label so it
     *                  shows in proportion
     * @param across    stickers across the roll, outlined one each so the horizontal
     *                  gap between them shows
     * @param hGapMm    the horizontal gap, as given to [TsplLabel.build]
     */
    fun render(job: ByteArray, vGapMm: Int, across: Int, hGapMm: Int): Bitmap? = runCatching {
        val lines = String(job, Charsets.ISO_8859_1).split("\r\n")
        val size = lines.firstNotNullOfOrNull { SIZE.find(it) } ?: return null
        val widthDots = size.groupValues[1].toInt() * TsplLabel.DOTS_PER_MM
        val heightDots = size.groupValues[2].toInt() * TsplLabel.DOTS_PER_MM
        val gapDots = vGapMm.coerceAtLeast(0) * TsplLabel.DOTS_PER_MM

        val bitmap = Bitmap.createBitmap(widthDots, heightDots + gapDots, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#E3E6EA"))
        val paper = Paint().apply { color = Color.WHITE }
        canvas.drawRect(0f, 0f, widthDots.toFloat(), heightDots.toFloat(), paper)

        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.MONOSPACE
        }

        // Only the first block: the one full feed. Anything after its PRINT is a
        // part-filled last row, which a preview of "a label" does not need.
        for (line in lines) {
            if (line.startsWith("PRINT")) break
            TEXT.find(line)?.let { m ->
                val (x, y, font, content) = m.destructured
                val (w, h) = cell(font)
                ink.textSize = w / 0.6f
                canvas.drawText(content, x.toFloat(), y.toFloat() + h * 0.8f, ink)
            }
            BARCODE.find(line)?.let { m ->
                val x = m.groupValues[1].toInt()
                val y = m.groupValues[2].toInt()
                val height = m.groupValues[3].toInt()
                val narrow = m.groupValues[4].toInt()
                val modules = Code128Writer().encode(m.groupValues[5])
                var pos = x
                for (on in modules) {
                    if (on) canvas.drawRect(
                        pos.toFloat(), y.toFloat(), (pos + narrow).toFloat(), (y + height).toFloat(), ink
                    )
                    pos += narrow
                }
            }
        }

        val outline = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#90A4AE")
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
        val (stickerDots, hGapDots) = TsplLabel.stickerLayout(size.groupValues[1].toInt(), across, hGapMm)
        repeat(across.coerceAtLeast(1)) { column ->
            val left = column * (stickerDots + hGapDots) + 1f
            canvas.drawRect(left, 1f, left + stickerDots - 2f, heightDots - 1f, outline)
        }
        bitmap
    }.getOrNull()
}
