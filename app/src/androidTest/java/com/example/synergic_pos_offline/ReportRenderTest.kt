package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.BillWiseReportDao
import com.example.synergic_pos_offline.database.ItemWiseReportDao
import com.example.synergic_pos_offline.database.StockReportDao
import com.example.synergic_pos_offline.utils.BillWiseReportRenderer
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.ItemWiseReportRenderer
import com.example.synergic_pos_offline.utils.StockReportRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The printed reports come out with ink on them.
 *
 * The same guard [BillItemFiguresRenderTest] puts on the bill, applied to the two
 * reports: they are built from the same kind of measured, fixed-width cells, and the
 * failure that emptied the bill's figure columns would empty theirs in exactly the
 * same way without changing anything a view-tree assertion could see.
 *
 * Renders at both paper widths and checks the slip is not a blank roll. The images
 * are written to the app's files dir so they can be pulled and looked at:
 *   adb exec-out run-as com.example.synergic_pos_offline cat files/bill-wise-576.png > out.png
 */
@RunWith(AndroidJUnit4::class)
class ReportRenderTest {

    /**
     * A period holding both regimes - a shop that moved from VAT to GST still has
     * VAT bills in its books, and each bill must report under the regime its own
     * settings snapshot records.
     */
    private fun billReport() = BillWiseReportDao.Report(
        fromDate = "2026-08-01",
        toDate = "2026-08-07",
        lines = listOf(
            BillWiseReportDao.Line(
                billNumber = "INV-001", date = "2026-08-01", payMode = "CASH", mrp = 500.0,
                cgst = 12.5, sgst = 12.5, igst = 0.0, vat = 0.0,
                discount = 10.0, roundOff = 0.5, netAmount = 515.0,
                regime = GstCalculator.TaxRegime.GST
            ),
            BillWiseReportDao.Line(
                billNumber = "INV-002", date = "2026-08-05", payMode = "UPI", mrp = 300.0,
                cgst = 0.0, sgst = 0.0, igst = 0.0, vat = 15.0,
                discount = 0.0, roundOff = -0.5, netAmount = 315.0,
                regime = GstCalculator.TaxRegime.VAT
            )
        )
    )

    private fun itemReport() = ItemWiseReportDao.Report(
        fromDate = "2026-08-01",
        toDate = "2026-08-07",
        lines = listOf(
            ItemWiseReportDao.Line(1, "TATA TEA 100", 1.0, 200.0),
            ItemWiseReportDao.Line(2, "MIXED FR RICE", 1.5, 150.0),
            ItemWiseReportDao.Line(3, "lali", 3.0, 120.0)
        )
    )

    private fun stockReport() = StockReportDao.Report(
        takenAt = "2026-08-07 17:45:00",
        lines = listOf(
            StockReportDao.Line(1, "TATA TEA 100", 12.0),
            StockReportDao.Line(2, "MIXED FR RICE", 1.5),
            StockReportDao.Line(3, "lali", 0.0)
        )
    )

    @Test
    fun reportsPrintWithInk() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        for (dots in listOf(576, 384)) {
            check("bill-wise-$dots", BillWiseReportRenderer(ctx).renderToBitmap(billReport(), "ADMIN", dots), ctx)
            check("item-wise-$dots", ItemWiseReportRenderer(ctx).renderToBitmap(itemReport(), "ADMIN", dots), ctx)
            check("stock-$dots", StockReportRenderer(ctx).renderToBitmap(stockReport(), "ADMIN", dots), ctx)
        }
    }

    private fun check(label: String, bitmap: android.graphics.Bitmap?, ctx: android.content.Context) {
        assertTrue("$label: nothing rendered", bitmap != null)
        val bmp = bitmap!!
        FileOutputStream(File(ctx.filesDir, "$label.png")).use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }

        // Ink in the right-hand third is what the figure columns are: a slip whose
        // cells were laid out but drew nothing still has its headings and rules on
        // the left, and would pass a cruder "is anything on the page" check.
        var ink = 0
        for (x in (bmp.width * 2 / 3) until bmp.width) {
            for (y in 0 until bmp.height) {
                val c = bmp.getPixel(x, y)
                val luma = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
                if (luma < 128) ink++
            }
        }
        assertTrue("$label: the figure columns printed blank", ink > 200)
    }
}
