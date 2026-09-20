package com.example.synergic_pos_offline.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Confirms the label path now goes through TSC's own SDK, and still fails cleanly -
 * and logs the attempt - against a printer that never answers.
 *
 * Companion to [ThermalPrinterLogTest], which checks the same thing for the ESC/POS
 * receipt path. This one exercises [ThermalPrinter.printRaw] instead of
 * [ThermalPrinter.print], because that is the only path [TscPrinter] sits behind -
 * see [BarcodeLabelsFragment] and [ThermalPrinter.testPrint]'s label branch.
 */
@RunWith(AndroidJUnit4::class)
class TscPrinterLogTest {

    @Test
    fun rawPrintGoesThroughTscSdkAndLogsEveryStep() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PrintLog.clear(context)

        val job = TsplLabel.sample()
        // Private range, nothing answers: exercises the same "cannot reach printer"
        // path a mis-set label printer IP hits. TscWifiActivity's own connect
        // timeout is fixed at 2 seconds, so this returns quickly and with no retry -
        // printRaw does not retry a raw job, unlike the receipt path.
        val config = ThermalPrinter.Config(ip = "192.0.2.99", port = 9100, paperMm = 50, connection = "WIFI")

        val latch = CountDownLatch(1)
        ThermalPrinter.printRaw(context, job, config) { latch.countDown() }
        // Room for all three open attempts and the backoff between them - see
        // TscPrinter.OPEN_ATTEMPTS. Each one spends the SDK's fixed 2 second connect
        // timeout before answering, and the SDK's closeport sleeps 1.5s on the way out.
        assertTrue("printRaw never called back", latch.await(45, TimeUnit.SECONDS))

        val log = PrintLog.read(context)
        assertTrue("log missing the numbered job header -> $log", log.contains("START  label print"))
        assertTrue("log missing the TSPL that was sent -> $log", log.contains("TSPL sent"))
        assertTrue("log missing per-step timings -> $log", log.contains("ms)  "))
        assertTrue("log missing TSC open attempt -> $log", log.contains("opening TSC WiFi"))
        assertTrue("log missing TSC open result -> $log", log.contains("openport attempt 1/"))
        // The retry is the point of the test: a printer that never answers must be
        // tried again rather than written off on the first "-1", or the second label of
        // the day goes missing whenever the printer is still letting go of the first.
        assertTrue("open was not retried -> $log", log.contains("openport attempt 2/"))
        assertTrue("log missing give-up line -> $log", log.contains("gave up after"))
        assertTrue("log missing the job outcome -> $log", log.contains("END after"))
    }
}
