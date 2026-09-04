package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    fun printCopies(context: Context, receipt: Bitmap, config: Config, copies: Int, onResult: (Result) -> Unit) =
        printSequence(context, List(copies.coerceAtLeast(1)) { receipt }, config, onResult)

    /**
     * Sends [receipts] one after another, as separate jobs, stopping at the first
     * failure and reporting the last result.
     *
     * Separate from [printCopies] because the slips are no longer necessarily the
     * same slip: a two-copy bill is the ORIGINAL followed by a DUPLICATE, which are
     * two different renders of the same sale rather than one render sent twice. See
     * [BillPrinter.copiesFor].
     *
     * Sequential, not batched: a thermal head takes one job at a time, and sending
     * the second before the first has been acknowledged is how two bills come out
     * interleaved on one length of paper.
     */
    fun printSequence(context: Context, receipts: List<Bitmap>, config: Config, onResult: (Result) -> Unit) {
        if (receipts.isEmpty()) { onResult(Result.Failure("Nothing to print")); return }
        fun sendFrom(index: Int) {
            print(context, receipts[index], config) { result ->
                if (result is Result.Failure || index >= receipts.lastIndex) {
                    onResult(result)
                } else {
                    sendFrom(index + 1)
                }
            }
        }
        sendFrom(0)
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
        val lineHeight = (PrintType.dots(PrintType.BODY_SP) * 1.45f).toInt()
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

        // The sample slip is set like everything else the till prints - it is meant
        // to show what this printer's output looks like, so it has to be that.
        val bodyPaint = PrintType.paint(PrintType.BODY_SP)
        val titlePaint = PrintType.paint(PrintType.STORE_NAME_SP, bold = true, align = Paint.Align.CENTER)

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
        val portKey = "${config.connection}:${config.ip}"
        // A Bluetooth port the last job left open on this same printer - see
        // [keepPortOpen]. Reusing it skips the SDP lookup and RFCOMM connect, which is
        // where a Bluetooth receipt spends most of its time before a dot is printed.
        val reusing = config.isBluetooth && openPort == portKey
        var keepOpen = false

        if (reusing) {
            PrintLog.d(context, TAG, "reusing the Bluetooth port already open to ${config.ip}")
        } else {
            // Close before opening. The SDK keeps its socket in a static field, so a job
            // that died without closing - a crash, a killed process - leaves the previous
            // one dangling, and these modules serve a single client: the stale socket
            // locks every later job out until the printer times it out on its own.
            runCatching { Print.PortClose() }
            openPort = null
            PrintLog.d(context, TAG, "closed any previous port")
        }

        // Open the transport the printer is set to; 0 means connected. Bluetooth
        // takes the device MAC (the SDK prepends "Bluetooth,"); WiFi/LAN take the
        // "WiFi,<ip>,<port>" descriptor; USB takes the attached UsbDevice itself,
        // resolved from the saved vendor/product pair. The close below must cover this
        // too: a failed open can still leave a half-open connection behind, so each
        // retry would wedge the printer further.
        try {
            if (!reusing) {
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
            }

            // Handshake. ESC @ first, or the job inherits whatever state the last one
            // left behind - page mode, a half-finished raster - and prints nothing.
            // A module that resets here has taken none of the receipt, so the job can
            // safely start over.
            //
            // It is also what makes reusing a Bluetooth port safe. A printer switched
            // off since the last receipt leaves a socket that looks open and is not,
            // and this is where that shows - before any of the bill has been sent.
            // The failure is retryable, the port is closed on the way out, and the
            // retry opens a fresh one.
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

            // The connection has now carried a whole receipt, so it is worth keeping
            // for the next one. Set here rather than at each return below so that a
            // reported paper-out or cover-open - a healthy link with an unhappy
            // printer - keeps it too: the operator is about to close the lid and
            // reprint, and should not wait on a second connect to do it.
            keepOpen = config.isBluetooth

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
            PrintLog.d(context, TAG, "awaiting drain / status")
            val after = awaitDrain(context, config)
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
            if (keepOpen) {
                keepPortOpen(portKey)
                PrintLog.d(context, TAG, "Bluetooth port held open for ${BT_IDLE_KEEP_MS}ms")
            } else {
                // Release the socket, including when the job threw part-way.
                runCatching { Print.PortClose() }
                openPort = null
                PrintLog.d(context, TAG, "port closed")
            }
        }
    }

    // ---- Holding the Bluetooth port open -------------------------------------

    /**
     * The printer [runJob] has left a port open on, or null when nothing is open.
     *
     * ## Why only Bluetooth
     *
     * Opening a port is not the same price on every transport. USB is all but free.
     * A TCP connection to a WiFi module is quick. Bluetooth is neither: the SDK's
     * BTOperator cancels discovery, does an SDP lookup for the serial-port service,
     * waits out any discovery still running and only then connects RFCOMM - one and a
     * half to three seconds before a single dot is printed, and it was being paid on
     * every receipt, including once per copy of a two-copy bill.
     *
     * The per-job open and close is right for WiFi and LAN and stays: several tills
     * can be pointed at one IP, and these modules serve a single client, so a socket
     * held between sales locks the others out. Bluetooth is a weaker case for it. A
     * printer paired to two tills can still only carry one RFCOMM channel at a time,
     * so they were already taking turns; holding the port changes when the printer
     * comes free, not whether. That is the trade [BT_IDLE_KEEP_MS] is sized for.
     *
     * ## Held, not kept
     *
     * Only for [BT_IDLE_KEEP_MS] after the last receipt. Long enough to cover what
     * actually comes in bursts - the second copy of a bill, a KOT following its bill,
     * one sale after another - and short enough that a till which has stopped printing
     * is not sitting on the printer, and a socket that has gone stale unnoticed is not
     * kept for long.
     */
    @Volatile private var openPort: String? = null

    /**
     * Which hold is current. The idle close is scheduled ahead of time, so by the time
     * it runs another receipt may have used the port and asked to keep it - this is
     * how that close knows it has been superseded and should do nothing.
     */
    @Volatile private var portGeneration = 0L

    private const val BT_IDLE_KEEP_MS = 12_000L

    private val idleCloser = Executors.newSingleThreadScheduledExecutor()
    private var pendingClose: java.util.concurrent.ScheduledFuture<*>? = null

    /** Leaves the port open, and books its release for [BT_IDLE_KEEP_MS] from now. */
    private fun keepPortOpen(key: String) {
        openPort = key
        val generation = ++portGeneration
        pendingClose?.cancel(false)
        // Closed ON THE PRINT WORKER, never on the timer's own thread. The worker runs
        // one job at a time, so a close queued there cannot land in the middle of a
        // receipt - at worst it waits behind one and finds itself superseded.
        pendingClose = idleCloser.schedule(
            { worker.execute { closeIfStillIdle(generation) } },
            BT_IDLE_KEEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS
        )
    }

    private fun closeIfStillIdle(generation: Long) {
        if (generation != portGeneration) return
        runCatching { Print.PortClose() }
        openPort = null
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

    /**
     * Printers that have never answered a status query, by address.
     *
     * This is why LAN/WiFi and Bluetooth printing was slower than USB, and the gap was
     * not in the transports. Asking a send-only module for its status is neither free
     * nor quick: the SDK's GetRealTimeStatus drains for 500ms, writes DLE EOT, blocks a
     * full second for a reply, writes the query AGAIN and blocks another second before
     * giving up - two and a half seconds to be told nothing. [awaitDrain] used to do
     * that six times over with a 400ms sleep between each, so every receipt sent to a
     * send-only printer ended in about SEVENTEEN SECONDS of waiting for an answer that
     * was never coming.
     *
     * A USB printer answers the first ask and returns at once, which is exactly why USB
     * felt fast and the other two did not. The receipt itself goes out in a single
     * WriteData call on every transport; nothing about them differs by anything like
     * that margin.
     *
     * So the question is asked once per printer per app run and the answer kept here. A
     * module that stays silent is never asked again - it gets [SEND_ONLY_SETTLE_MS] to
     * clear its buffer and the job ends.
     *
     * Deliberately not persisted. A printer swapped for one that does answer should
     * start answering again after a restart rather than being written off for good.
     */
    private val sendOnlyPrinters = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Time allowed for the tail of the receipt to clear a send-only printer before the
     * port is closed.
     *
     * Margin for the module's own buffer, not for the transfer. The SDK flushes as it
     * writes - Bluetooth in 1KB chunks, WiFi in one socket write - so the raster has
     * left the app by the time PrintBitmap returns. That is why this is a quarter of a
     * second rather than the seconds the old status loop spent.
     */
    private const val SEND_ONLY_SETTLE_MS = 250L

    /**
     * Waits for the printer to take the receipt, and reports its status if it has one.
     *
     * A status reply only comes back once the printer has drained what it was sent, so
     * on a printer that answers, the reply doubles as the drain wait - a receipt raster
     * is hundreds of KB and closing the socket with the tail in flight loses the end of
     * the bill.
     *
     * ONE ASK, not six. GetRealTimeStatus already writes the query twice and waits a
     * second for each, so the old loop was re-running a retry that had already been
     * run, at 2.5 seconds a turn. A printer that has ignored two queries over two and a
     * half seconds will not answer the third.
     *
     * A printer already known to be send-only is not asked at all - see
     * [sendOnlyPrinters]. That is what takes the cost from every receipt down to the
     * first one after the app starts.
     */
    private fun awaitDrain(context: Context, config: Config): Int? {
        val key = "${config.connection}:${config.ip}"
        if (key in sendOnlyPrinters) {
            PrintLog.d(
                context, TAG,
                "known send-only printer - settling ${SEND_ONLY_SETTLE_MS}ms rather than asking"
            )
            Thread.sleep(SEND_ONLY_SETTLE_MS)
            return null
        }
        readStatus()?.let { return it }
        // Silent, and the ask above has already been retried inside the SDK. Remember
        // it so no later receipt to this printer pays for the same silence again.
        sendOnlyPrinters.add(key)
        PrintLog.d(
            context, TAG,
            "printer never answered a status query - send-only, will not be asked again"
        )
        Thread.sleep(SEND_ONLY_SETTLE_MS)
        return null
    }

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

    /** Builds a [Config] from a chosen operating-printer row, or null if unusable. */
    fun configFor(printer: OperatingPrinterDao.OperatingPrinter): Config? {
        val type = printer.printerType?.takeIf { it.isNotBlank() } ?: return null
        val address = printer.value?.takeIf { it.isNotBlank() } ?: return null
        return Config(
            ip = address, port = DEFAULT_PORT, paperMm = printer.paperMm, connection = type.uppercase()
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
