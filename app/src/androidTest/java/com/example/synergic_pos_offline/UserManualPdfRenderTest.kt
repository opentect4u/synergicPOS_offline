package com.example.synergic_pos_offline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.utils.UserManual
import com.example.synergic_pos_offline.utils.UserManualPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The downloaded manual comes out with ink on it.
 *
 * The same guard [ReportRenderTest] puts on the printed reports. A PDF built by
 * measuring text and placing it at coordinates fails silently: nothing throws when a
 * paragraph is drawn past the bottom of the page or off its right edge, and the file
 * still opens. The only thing that can tell is looking at the pixels.
 *
 * Pages are written as PNGs into the app's files dir so they can be pulled and read:
 *   adb exec-out run-as com.example.synergic_pos_offline cat files/manual-02.png > p2.png
 */
@RunWith(AndroidJUnit4::class)
class UserManualPdfRenderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(
        chapters: List<Pair<String, List<UserManual.Block>>> = UserManual.ALL,
        name: String = "manual"
    ): List<Bitmap> {
        val file = File(context.filesDir, "$name.pdf")
        FileOutputStream(file).use { UserManualPdf.write(it, "Synergic POS", chapters) }
        assertTrue("the PDF was not written", file.length() > 0)

        val pages = mutableListOf<Bitmap>()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { doc ->
                for (i in 0 until doc.pageCount) {
                    doc.openPage(i).use { page ->
                        // Twice A4 at 72dpi, so a thin heading rule survives being
                        // rasterised and the PNGs are legible when pulled off.
                        val bmp = Bitmap.createBitmap(
                            page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888
                        ).apply { eraseColor(Color.WHITE) }
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pages += bmp
                        File(context.filesDir, "$name-%02d.png".format(i + 1))
                            .outputStream().use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                    }
                }
            }
        }
        return pages
    }

    /** Fraction of pixels that are not the page's white. */
    private fun inkOf(bmp: Bitmap): Float {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return pixels.count { it != Color.WHITE }.toFloat() / pixels.size
    }

    @Test
    fun everyPageCarriesText() {
        val pages = render()
        // A cover plus at least one page per chapter. Fewer means a chapter was lost;
        // the exact count is left open because it moves as the text is edited.
        assertTrue("only ${pages.size} pages", pages.size >= UserManual.ALL.size + 1)
        pages.forEachIndexed { i, page ->
            val ink = inkOf(page)
            assertTrue("page ${i + 1} is blank", ink > 0.001f)
            // A page more than a third covered is text drawn over itself - the failure
            // that happens when y stops advancing.
            assertTrue("page ${i + 1} is over-inked at $ink", ink < 0.33f)
        }
    }

    @Test
    fun nothingIsDrawnIntoTheMargins() {
        // The margin is where a line that was measured wrongly shows up first: wrapping
        // that overruns puts ink against the right edge, and a page break that does not
        // fire puts it against the bottom.
        assertMarginsClear(render())
    }

    /**
     * A chapter far longer than any in the manual still breaks onto further pages.
     *
     * Every chapter as written fits on one page, so nothing above this exercises the
     * page-breaking at all. That is the part worth testing: text drawn past the foot of
     * a page is not clipped and does not throw - it simply is not in the file, and the
     * only sign is a paragraph that stops mid-sentence.
     */
    @Test
    fun aLongChapterRunsOntoFurtherPages() {
        val prose = "Settings General Settings Mode decides the whole menu and the sale " +
            "screen, so it is the first real choice a shop makes when the till arrives."
        val blocks = (1..12).flatMap {
            listOf(
                UserManual.Block.Heading("Section $it"),
                UserManual.Block.Para(prose),
                UserManual.Block.Steps(listOf(prose, prose)),
                UserManual.Block.Bullets(listOf(prose, prose)),
                UserManual.Block.Caution(prose)
            )
        }
        val pages = render(listOf("Long Chapter" to blocks), name = "long")
        assertTrue("a $blocks-block chapter fit on ${pages.size} page(s)", pages.size >= 4)
        assertMarginsClear(pages)
        pages.forEachIndexed { i, page ->
            assertTrue("page ${i + 1} is blank", inkOf(page) > 0.001f)
        }
    }

    private fun assertMarginsClear(pages: List<Bitmap>) {
        val margin = 48 * 2     // points to the pixels this renders at
        pages.forEachIndexed { i, page ->
            val strip = { x: Int, y: Int, w: Int, h: Int ->
                val px = IntArray(w * h)
                page.getPixels(px, 0, w, x, y, w, h)
                px.count { it != Color.WHITE }
            }
            val right = strip(page.width - margin / 2, 0, margin / 2, page.height)
            assertEquals("page ${i + 1} has ink in the right margin", 0, right)
            // The foot keeps the page number, so only the very bottom edge is checked.
            val foot = strip(0, page.height - 20, page.width, 20)
            assertEquals("page ${i + 1} has ink at the bottom edge", 0, foot)
        }
    }
}
