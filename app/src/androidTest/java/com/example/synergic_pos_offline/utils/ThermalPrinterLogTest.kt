package com.example.synergic_pos_offline.utils

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Confirms the print job actually writes to [PrintLog], which is how a print
 * failure gets diagnosed on a till with no adb/logcat access: the operator opens
 * Printer Settings > View print log and copies out what this test checks is there.
 */
@RunWith(AndroidJUnit4::class)
class ThermalPrinterLogTest {

    @Test
    fun printJobWritesEveryStepToPrintLog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PrintLog.clear(context)

        val bitmap = Bitmap.createBitmap(384, 100, Bitmap.Config.ARGB_8888)
        // A private-range address nothing will answer: exercises exactly the
        // "cannot reach printer" path a mis-set IP or an unpaired Bluetooth MAC hits.
        val config = ThermalPrinter.Config(ip = "192.0.2.99", port = 9100, paperMm = 80, connection = "WIFI")

        val latch = CountDownLatch(1)
        ThermalPrinter.print(context, bitmap, config) { latch.countDown() }
        // Three connect attempts against an address nothing answers, each waiting out
        // its own TCP timeout plus backoff between them - allow generous headroom.
        assertTrue("print() never called back", latch.await(90, TimeUnit.SECONDS))

        val log = PrintLog.read(context)
        assertTrue("log missing job header -> $log", log.contains("print job:"))
        assertTrue("log missing port-open attempt -> $log", log.contains("opening WiFi port"))
        assertTrue("log missing port result -> $log", log.contains("port open result="))
        assertTrue("log missing job outcome -> $log", log.contains("job finished:"))
    }
}
