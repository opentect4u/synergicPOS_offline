package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.synergic_pos_offline.database.AppSettingsDao
import com.example.synergic_pos_offline.database.OperatingPrinterDao
import com.example.synergic_pos_offline.database.PrinterDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import print.Print

/**
 * Prints a receipt to a PR-55 style ESC/POS thermal printer over WiFi/LAN,
 * Bluetooth or USB.
 *
 * Every call here blocks - a network printer listens on a TCP socket (9100 by
 * convention), and a USB one is written to over a bulk endpoint - so all of it must
 * stay off the main thread. Connections are opened per job and closed again: a till
 * may be shared, and holding the socket open would lock other devices out of the
 * printer between sales.
 */
object ThermalPrinter {

    private const val TAG = "ThermalPrinter"

    /**
     * Printable dot width per paper size, at the 203dpi (8 dots/mm) that every head
     * in this class runs at. These are the *printable* widths, which are narrower
     * than the paper: the head cannot reach the edges, and the unreachable margin is
     * not proportional - 58mm paper prints 48mm, 80mm prints 72mm.
     *
     * Widths are multiples of 8 because a raster line is packed into whole bytes.
     */
    private val PRINTABLE_DOTS = mapOf(
        58 to 384,
        80 to 576,
        90 to 640
    )

    /** Fallback for a size not in the table: 8mm of unreachable margin, byte-aligned. */
    private fun dotsForMm(mm: Int): Int =
        PRINTABLE_DOTS[mm] ?: (((mm - 8) * 8) / 8 * 8).coerceAtLeast(8)

    /**
     * Print head shade, 0..3 in the SDK. Middle keeps text crisp without ghosting.
     * Applied via SetPrintDensity: the trailing argument to PrintBitmap looks like a
     * density but the SDK discards it, so setting it there prints at the default.
     */
    private const val DENSITY: Byte = 1

    /**
     * Blank dots fed after the receipt before cutting.
     *
     * The cutter sits above the print head, so the last printed line has to be fed
     * past it or the cut lands through the total. 80 dots is 10mm at 203dpi, which
     * clears the offset on this class of printer and doubles as the tear-off margin
     * on one with no cutter fitted.
     */
    private const val FEED_AFTER_PRINT = 80

    /**
     * GS V 1. Partial leaves a small tab holding the slip, so a receipt cannot drop
     * on the floor before the customer takes it.
     */
    private const val PARTIAL_CUT = 1

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * A printer to send to. [ip] is an IP for WIFI/LAN, a device MAC for BLUETOOTH or
     * a "VVVV:PPPP" vendor/product pair for USB ([UsbPrinters.addressOf]);
     * [connection] selects which transport is opened. Paper width is held in mm -
     * what the operator knows - and derived to dots.
     */
    data class Config(
        val ip: String,
        val port: Int,
        val paperMm: Int,
        val connection: String = "WIFI"
    ) {
        val paperDots: Int get() = dotsForMm(paperMm)
        val isBluetooth: Boolean get() = connection.equals("BLUETOOTH", ignoreCase = true)
        val isUsb: Boolean get() = connection.equals("USB", ignoreCase = true)

        /**
         * How this printer reads to an operator. A network printer is known by its
         * address, which is worth showing - it is what they typed in and what they
         * would check. A USB printer's address is a vendor/product pair that means
         * nothing to anyone, so it is named by the port it is on instead.
         */
        val description: String get() = if (isUsb) "the USB printer" else ip
    }

    sealed class Result {
        /** The printer acknowledged the receipt and reported no fault. */
        object Success : Result()

        /**
         * The receipt was written to the printer, which never answered a status
         * request. Send-only WiFi modules behave this way, so this is not an error -
         * but it is not proof of a printed receipt either, and is worded as such.
         */
        object Sent : Result()

        data class Failure(val message: String) : Result()
    }

    /**
     * Sends [receipt] to the configured printer.
     *
     * @param onResult called on the main thread once the job finishes
     */
    fun print(context: Context, receipt: Bitmap, config: Config, onResult: (Result) -> Unit) {
        // The bitmap belongs to a view that may be gone by the time the worker
        // runs, so take a copy the printer thread owns outright.
        val copy = receipt.copy(Bitmap.Config.ARGB_8888, false)
        PrintLog.d(
            context, TAG,
            "==== print job: connection=${config.connection} address=${config.ip} " +
                "port=${config.port} paperMm=${config.paperMm} paperDots=${config.paperDots} " +
                "bitmap=${receipt.width}x${receipt.height} ===="
        )
        // A USB printer cannot be opened until the user has allowed this app to talk
        // to that device, and the prompt that asks is Android's own - it needs a live
        // main thread, so it is dealt with here rather than from the print worker. It
        // is asked for once per plug-in, so an operator sees it when they connect the
        // printer and not again for the rest of the shift.
        if (config.isUsb) {
            UsbPrinters.ensurePermission(context, config.ip) { granted, reason ->
                if (!granted) {
                    PrintLog.d(context, TAG, "USB not available: $reason")
                    copy.recycle()
                    onResult(Result.Failure(reason))
                } else {
                    dispatch(context, copy, config, onResult)
                }
            }
            return
        }
        dispatch(context, copy, config, onResult)
    }

    /** Queues an already-owned bitmap on the print worker. */
    private fun dispatch(context: Context, copy: Bitmap, config: Config, onResult: (Result) -> Unit) {
        worker.execute {
            val result = runCatching { sendWithRetry(context, copy, config) }
                .getOrElse { e ->
                    Log.e(TAG, "Printing failed", e)
                    PrintLog.d(context, TAG, "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                    Result.Failure(e.message ?: "Could not reach the printer")
                }
            copy.recycle()
            PrintLog.d(context, TAG, "job finished: $result")
            main.post { onResult(result) }
        }
    }

    /**
     * Sends [receipt] to the configured printer [copies] times in a row - Bill
     * Settings' "Two Copy" toggle, honoured wherever a bill prints (on sale, a
     * reprint, or the bill screen's own print button) by having all three funnel
     * through here rather than each deciding separately.
     *
     * Copies are sent one after another, not in parallel: [print] already queues
     * jobs on a single worker, so nothing would be gained, and stopping at the
     * first failure means an operator is not left guessing which copy - if any -
     * actually came out. [onResult] fires once, for the last copy sent (or
     * whichever one failed), so a caller written for a single [print] call needs
     * no change to use this instead.
     */
    fun printCopies(context: Context, receipt: Bitmap, config: Config, copies: Int, onResult: (Result) -> Unit) {
        fun sendOne(remaining: Int) {
            print(context, receipt, config) { result ->
                if (result is Result.Failure || remaining <= 1) {
                    onResult(result)
                } else {
                    sendOne(remaining - 1)
                }
            }
        }
        sendOne(copies.coerceAtLeast(1))
    }

    /**
     * Builds and sends a short sample slip to [config] - purpose, connection,
     * address, paper width and a timestamp - through the exact same [print] path
     * a real bill takes, so a successful test print is a genuine end-to-end check
     * that the connection actually works, not just that an address was saved.
     * Shared by every "Test Print" button (Printer Settings' cards and dialogs,
     * Operating Printer's rows) so they all print the same thing.
     */
    fun testPrint(context: Context, purpose: String, config: Config, onResult: (Result) -> Unit) {
        print(context, buildTestPrintBitmap(purpose, config), config, onResult)
    }

    private fun buildTestPrintBitmap(purpose: String, config: Config): Bitmap {
        val width = config.paperDots
        val lineHeight = 34
        val lines = listOf(
            "Purpose : $purpose",
            "Type    : ${config.connection}",
            "Address : ${config.ip}",
            "Paper   : ${config.paperMm} mm",
            "",
            "If you can read this clearly,",
            "the connection is OK.",
            SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).format(Date())
        )
        val height = lineHeight * (lines.size + 3)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val bodyPaint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            textSize = 26f
        }
        val titlePaint = Paint(bodyPaint).apply {
            textSize = 34f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        var y = lineHeight * 1.5f
        canvas.drawText("TEST PRINT", width / 2f, y, titlePaint)
        y += lineHeight * 1.5f
        lines.forEach { line ->
            canvas.drawText(line, 12f, y, bodyPaint)
            y += lineHeight
        }
        return bitmap
    }

    /** A finished attempt, and whether starting over could reasonably do better. */
    private data class Attempt(val result: Result, val retryable: Boolean)

    private const val JOB_ATTEMPTS = 3
    private const val RETRY_BACKOFF_MS = 700L

    /**
     * Runs the job, retrying a printer that refuses the connection.
     *
     * These WiFi modules intermittently accept a connection and then reset it on the
     * first byte, recovering on their own a moment later - observed on the bench,
     * several connections in a row refused and then fine again. An operator should
     * not have to work out that pressing print twice fixes it.
     *
     * Only the handshake is retried. Once any part of the receipt has gone to the
     * printer a failure might mean a half-printed slip, and starting over there
     * risks handing the customer two.
     */
    private fun sendWithRetry(context: Context, receipt: Bitmap, config: Config): Result {
        var last: Result = Result.Failure("Cannot reach printer at ${config.ip}:${config.port}")
        repeat(JOB_ATTEMPTS) { attempt ->
            PrintLog.d(context, TAG, "attempt ${attempt + 1}/$JOB_ATTEMPTS")
            val outcome = runJob(context, receipt, config)
            if (!outcome.retryable) return outcome.result
            last = outcome.result
            if (attempt < JOB_ATTEMPTS - 1) {
                Log.w(TAG, "Print attempt ${attempt + 1} failed, retrying: ${outcome.result}")
                PrintLog.d(context, TAG, "attempt ${attempt + 1} retryable failure: ${outcome.result} - retrying")
                Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1))
            }
        }
        return last
    }

    private fun runJob(context: Context, receipt: Bitmap, config: Config): Attempt {
        // Close before opening. The SDK keeps its socket in a static field, so a job
        // that died without closing - a crash, a killed process - leaves the previous
        // one dangling, and these modules serve a single client: the stale socket
        // locks every later job out until the printer times it out on its own.
        runCatching { Print.PortClose() }
        PrintLog.d(context, TAG, "closed any previous port")

        // Open the transport the printer is set to; 0 means connected. Bluetooth
        // takes the device MAC (the SDK prepends "Bluetooth,"); WiFi/LAN take the
        // "WiFi,<ip>,<port>" descriptor; USB takes the attached UsbDevice itself,
        // resolved from the saved vendor/product pair. The close below must cover this
        // too: a failed open can still leave a half-open connection behind, so each
        // retry would wedge the printer further.
        try {
            PrintLog.d(
                context, TAG,
                when {
                    config.isUsb -> "opening USB port to ${config.ip}"
                    config.isBluetooth -> "opening Bluetooth port to ${config.ip}"
                    else -> "opening WiFi port to ${config.ip}:${config.port}"
                }
            )
            // The device is re-resolved per attempt rather than held: it may have been
            // unplugged and plugged back in between them, which hands out a different
            // UsbDevice for the same printer.
            val usbDevice = if (config.isUsb) UsbPrinters.find(context, config.ip) else null
            if (config.isUsb && usbDevice == null) {
                return Attempt(
                    Result.Failure("USB printer not connected - plug it in and try again"),
                    retryable = false
                )
            }
            val opened = when {
                // The SDK keeps this context in a static field for the life of the
                // process, so it gets the application's, never an Activity's.
                usbDevice != null -> Print.PortOpen(context.applicationContext, usbDevice)
                config.isBluetooth -> Print.portOpenBT(context, config.ip)
                else -> Print.PortOpen(context, "WiFi,${config.ip},${config.port}")
            }
            PrintLog.d(context, TAG, "port open result=$opened (0 = connected)")
            if (opened != 0) {
                val where = when {
                    config.isUsb -> "USB ${config.ip}"
                    config.isBluetooth -> config.ip
                    else -> "${config.ip}:${config.port}"
                }
                return Attempt(
                    Result.Failure("Cannot reach printer at $where"),
                    retryable = true
                )
            }

            // Handshake. ESC @ first, or the job inherits whatever state the last one
            // left behind - page mode, a half-finished raster - and prints nothing.
            // A module that resets here has taken none of the receipt, so the job can
            // safely start over.
            try {
                Print.Initialize()
                Print.SetPrintDensity(DENSITY)
                PrintLog.d(context, TAG, "handshake ok (Initialize + SetPrintDensity)")
            } catch (e: Exception) {
                Log.w(TAG, "Printer refused the handshake", e)
                PrintLog.d(context, TAG, "handshake FAILED: ${e.javaClass.simpleName}: ${e.message}")
                return Attempt(
                    Result.Failure(e.message ?: "Printer refused the connection"),
                    retryable = true
                )
            }

            // Status is only ever asked for *after* the receipt is on the wire. Some
            // WiFi modules reset the connection when sent a query they do not
            // implement, so asking first can destroy a job that would have printed.

            // Scaled to the head width: sending a wider bitmap prints a cropped
            // receipt rather than a resized one.
            val scaled = scaleToPaper(receipt, config.paperDots)
            PrintLog.d(context, TAG, "sending bitmap ${scaled.width}x${scaled.height} (paperDots=${config.paperDots})")
            val printed = Print.PrintBitmap(scaled, 0, 0)
            PrintLog.d(context, TAG, "PrintBitmap returned $printed (>=0 expected)")
            if (scaled !== receipt) scaled.recycle()

            // Past here the receipt is on the wire: report what happened, never retry.
            if (printed < 0) {
                return Attempt(Result.Failure("Printer rejected the receipt"), retryable = false)
            }

            Print.PrintAndFeed(FEED_AFTER_PRINT)
            PrintLog.d(context, TAG, "fed $FEED_AFTER_PRINT dots")

            // Not every unit has a cutter fitted, and one without it should still
            // produce the receipt rather than fail the job over a tear-off.
            runCatching { Print.CutPaper(PARTIAL_CUT) }
                .onSuccess { PrintLog.d(context, TAG, "cut paper") }
                .onFailure {
                    Log.w(TAG, "Printer did not cut", it)
                    PrintLog.d(context, TAG, "cut FAILED (no cutter, or not supported): ${it.message}")
                }

            // A receipt raster is hundreds of KB, and closing the socket while it is
            // still in flight loses the tail. A status reply only comes back once the
            // printer has drained what it was sent, so it doubles as the drain wait.
            PrintLog.d(context, TAG, "awaiting drain / status (up to ${DRAIN_ATTEMPTS * DRAIN_INTERVAL_MS}ms)")
            val after = awaitDrain()
            PrintLog.d(context, TAG, "drain result status=$after (null = printer never answered)")
            if (after != null) {
                faultOf(after)?.let {
                    PrintLog.d(context, TAG, "printer reported fault: $it")
                    return Attempt(Result.Failure(it), retryable = false)
                }
                return Attempt(Result.Success, retryable = false)
            }
            // Nothing answered, so the receipt was sent but never acknowledged.
            return Attempt(Result.Sent, retryable = false)
        } finally {
            // Always release the socket, including when the job threw part-way.
            runCatching { Print.PortClose() }
            PrintLog.d(context, TAG, "port closed")
        }
    }

    // ---- Printer status ----------------------------------------------------
    //
    // DLE EOT n. Every reply has bit1 and bit4 set, so 0x12 is a healthy printer.
    // Not every WiFi module answers - some are send-only - hence null for silence.

    private const val STATUS_PAPER_SENSOR: Byte = 4
    private const val PAPER_END = 0x60      // both paper-end sensors tripped
    private const val COVER_OPEN = 0x04
    private const val OFFLINE = 0x08

    private fun readStatus(): Int? =
        runCatching { Print.GetRealTimeStatus(STATUS_PAPER_SENSOR) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { it[0].toInt() and 0xFF }

    /** The operator-facing reason this receipt will not come out, if any. */
    private fun faultOf(status: Int): String? = when {
        status and PAPER_END == PAPER_END -> "The printer is out of paper"
        status and COVER_OPEN != 0 -> "The printer cover is open"
        status and OFFLINE != 0 -> "The printer is offline"
        else -> null
    }

    /** Polls until the printer answers, i.e. until it has consumed the receipt. */
    private fun awaitDrain(): Int? {
        repeat(DRAIN_ATTEMPTS) {
            readStatus()?.let { return it }
            Thread.sleep(DRAIN_INTERVAL_MS)
        }
        return null
    }

    private const val DRAIN_ATTEMPTS = 6
    private const val DRAIN_INTERVAL_MS = 400L

    /** Fits the receipt to the paper width, preserving aspect ratio. */
    private fun scaleToPaper(source: Bitmap, paperDots: Int): Bitmap {
        if (source.width == paperDots) return source
        val height = (source.height.toFloat() / source.width * paperDots).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, paperDots, height, true)
    }

    // ---- Stored configuration ---------------------------------------------

    private const val KEY_IP = "printer_wifi_ip"
    private const val KEY_PORT = "printer_wifi_port"

    /**
     * Paper width in mm. A separate key from the "printer_paper_dots" this replaces:
     * existing tills have a dot count saved against that key from when the width was
     * never asked for and silently defaulted to 58mm, and reading it would hold them
     * at that width. Leaving it behind lets the new default take effect instead.
     */
    private const val KEY_PAPER_MM = "printer_paper_width_mm"

    private const val DEFAULT_PORT = 9100
    private const val DEFAULT_PAPER_MM = 80

    /**
     * The printer to send [purpose] to: the printer marked default in
     * md_operating_printer for that purpose's flag (B for BILL, K for KOT) if one
     * is set, otherwise the legacy md_printer selection for that purpose, or null
     * when neither has an address yet. The saved paper width flows through here,
     * so a slip prints scaled to whatever width that printer is set to.
     */
    fun configForPurpose(context: Context, purpose: String): Config? {
        operatingDefaultConfig(context, purpose)?.let { return it }
        val printer = PrinterDao(context).get(purpose) ?: return null
        val address = printer.ip?.takeIf { it.isNotBlank() } ?: return null
        return Config(
            ip = address,
            port = DEFAULT_PORT,
            paperMm = printer.paperMm ?: DEFAULT_PAPER_MM,
            connection = printer.type.uppercase()
        )
    }

    /** The Operating Printer screen's default row for [purpose]'s flag, if fully configured. */
    private fun operatingDefaultConfig(context: Context, purpose: String): Config? {
        val flag = OperatingPrinterDao.flagFor(purpose)
        if (flag.isEmpty()) return null
        val printer = OperatingPrinterDao(context).getDefault(flag) ?: return null
        val type = printer.printerType?.takeIf { it.isNotBlank() } ?: return null
        // An address is what every transport is opened by, USB included - for it, the
        // vendor/product pair of the device that was picked when the printer was set
        // up. A row saved without one is not usable, whatever its type.
        val address = printer.value?.takeIf { it.isNotBlank() } ?: return null
        return Config(
            ip = address,
            port = DEFAULT_PORT,
            paperMm = printer.paperMm,
            connection = type.uppercase()
        )
    }

    /** The saved printer, or null when none has been set up yet. */
    fun savedConfig(context: Context): Config? {
        val dao = com.example.synergic_pos_offline.database.AppSettingsDao(context)
        val rawIp: String? = dao.get(KEY_IP)
        val ip: String = rawIp?.takeIf { it.isNotBlank() } ?: return null
        
        val rawPort: String? = dao.get(KEY_PORT)
        val port: Int = rawPort?.toIntOrNull() ?: DEFAULT_PORT
        
        val rawPaper: String? = dao.get(KEY_PAPER_MM)
        val paperMm: Int = rawPaper?.toIntOrNull() ?: DEFAULT_PAPER_MM
        
        return Config(
            ip = ip,
            port = port,
            paperMm = paperMm
        )
    }

    fun saveConfig(context: Context, config: Config) {
        val dao = com.example.synergic_pos_offline.database.AppSettingsDao(context)
        dao.put(KEY_IP, config.ip)
        dao.put(KEY_PORT, config.port.toString())
        dao.put(KEY_PAPER_MM, config.paperMm.toString())
    }

    fun defaultPort() = DEFAULT_PORT
    fun defaultPaperMm() = DEFAULT_PAPER_MM
}
