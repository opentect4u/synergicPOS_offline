package com.example.synergic_pos_offline.utils

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import com.example.tscdll.TSCActivity
import com.example.tscdll.TSCUSBActivity
import com.example.tscdll.TscWifiActivity
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Sends a TSPL job (see [TsplLabel]) to a TSC label printer through TSC's OWN
 * Android SDK - `tscsdk.jar`, package `com.example.tscdll` - rather than borrowing
 * the ESC/POS receipt SDK's raw byte pipe the way [ThermalPrinter.printRaw] used to.
 *
 * ## Why this replaces what was there
 *
 * Both SDKs happen to expose "open a transport, write bytes, close it" - `print.Print`
 * because a receipt SDK's PortOpen/WriteData/PortClose do not care what is inside the
 * bytes, and TSPL is just bytes on the same three wires (WiFi, Bluetooth, USB) an
 * ESC/POS printer uses. That worked, but every TSC-specific thing the transport ought
 * to know - the settle delay a TSC WiFi module wants after connecting, the drain wait
 * before the socket closes, the endpoint discovery a TSC USB unit needs - is something
 * `print.Print` has no reason to know, because it was written for a different
 * manufacturer's firmware. TSC's own SDK carries all of that already: see the fixed
 * sleeps in [TSCActivity.closeport], [TscWifiActivity.closeport] and
 * [TSCUSBActivity.closeport] - decompiled and confirmed rather than assumed - which is
 * why nothing here adds a drain delay of its own.
 *
 * ## Three classes, not one, and why `new`-ing an `Activity` is fine here
 *
 * The SDK gives WiFi, Bluetooth and USB a separate class each - [TscWifiActivity],
 * [TSCActivity], [TSCUSBActivity] - instead of one class with a transport switch, and
 * all three extend `android.app.Activity`. That looks alarming until you check what
 * `openport`/`sendcommand`/`closeport` actually touch: a plain `java.net.Socket`, a
 * `BluetoothSocket`, or a `UsbDeviceConnection` opened directly from the arguments
 * handed in, with no `getApplicationContext()`, no view, nothing that needs the
 * Activity to have ever been started. They were written as demo activities and never
 * refactored, but the print path itself is a plain object's methods - which is exactly
 * how TSC's own sample code uses them: `new TSCActivity()`, never `startActivity`. Each
 * function below owns one such object for the length of one job and lets it go
 * afterwards; nothing here is held between jobs.
 *
 * The one thing `new`-ing an `Activity` does cost is the thread it happens on - see
 * [newPort]. Everything after construction stays on the caller's background thread,
 * where it belongs.
 *
 * ## Reading the result
 *
 * Every method answers the string `"1"` for success and `"-1"` for failure - the SDK's
 * own contract, not a shortcut taken here - so success is a string comparison rather
 * than a caught exception. A thrown exception (a missing runtime permission on the
 * Bluetooth path, for instance) is still caught, and folded into the same "could not
 * open the port" failure an operator would get from a printer that is simply switched
 * off, since neither is something retrying blindly would fix.
 */
object TscPrinter {

    private const val TAG = "TscPrinter"

    /** What every open/send/close call in this SDK answers on success. */
    private const val OK = "1"

    /**
     * How many times the port is opened before the printer is called unreachable, and
     * how long the wait grows by between tries - see [transact].
     *
     * Three tries at 800ms and then 1600ms covers a printer still letting go of the
     * previous label's connection, which is a couple of seconds at worst. Beyond that
     * the printer really is off, asleep or on another address, and making the operator
     * watch a spinner for longer does not change the answer.
     */
    private const val OPEN_ATTEMPTS = 3
    private const val OPEN_BACKOFF_MS = 800L

    /**
     * How long [newPort] waits for the main thread to hand back a constructed port
     * before giving up. Construction is a few field initialisers and no I/O, so this
     * only ever expires if the main thread is wedged - in which case the operator has
     * a frozen till, not a printing problem, and the job should not hang on top of it.
     */
    private const val BUILD_TIMEOUT_MS = 5_000L

    /** Shown when the port object itself could not be built - see [newPort]. */
    private val UNAVAILABLE: ThermalPrinter.Result =
        ThermalPrinter.Result.Failure("Could not start the label printer - try again")

    /**
     * Builds one of the SDK's port objects on a thread that has a [Looper], whatever
     * thread we were called on.
     *
     * ## Why this exists
     *
     * All three classes extend `android.app.Activity`, and `Activity`'s constructor
     * initialises `final Handler mHandler = new Handler()`. A `Handler` built with no
     * `Looper` argument takes the *current thread's* looper, and throws when the thread
     * has none:
     *
     *     Can't create handler inside thread Thread[pool-8-thread-1,5,main]
     *     that has not called Looper.prepare()
     *
     * [ThermalPrinter.dispatchRaw] runs every label job on a plain
     * `Executors.newSingleThreadExecutor()` thread - no looper - so `TscWifiActivity()`
     * threw before a single byte was sent, the `runCatching` around the job folded the
     * exception into a failure, and the operator got that sentence as a toast after
     * tapping the barcode icon. Nothing was wrong with the printer, the address or the
     * label; the port object was simply never built.
     *
     * ## Why the main thread, and only for the constructor
     *
     * The looper this needs is just somewhere for `mHandler` to attach - the SDK's
     * `openport`/`sendcommand`/`closeport` never post to it - so the main looper does
     * the job without a thread of its own. Only the `new` runs there: it touches no
     * socket and no disk, so it cannot block the UI. Opening and writing stay on the
     * caller's background thread, which matters - the WiFi path connects a
     * `java.net.Socket`, and that on the main thread is a `NetworkOnMainThreadException`
     * instead.
     *
     * The alternative - `Looper.prepare()` on the worker - was rejected: that worker is
     * shared with the ESC/POS receipt path, and giving a long-lived shared thread a
     * looper nobody ever runs changes how unrelated code on it behaves.
     *
     * @return the port, or null if it could not be built (already reported to the log)
     */
    private fun <T> newPort(context: Context, job: PrintLog.Job, create: () -> T): T? {
        // Already on a looper thread - the main thread, or an instrumentation test that
        // prepared one. Posting elsewhere would deadlock if that thread IS the main one.
        if (Looper.myLooper() != null) {
            job.step("building the SDK port on this thread (it already has a Looper)")
            return runCatching(create).getOrElse {
                job.failed("could not build the TSC port", it)
                null
            }
        }
        job.step("building the SDK port on the main thread (this one has no Looper)")
        val task = FutureTask(Callable<T> { create() })
        Handler(Looper.getMainLooper()).post(task)
        return runCatching { task.get(BUILD_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .onSuccess { job.step("SDK port built") }
            .getOrElse {
                // Unwrap ExecutionException so the log names what actually failed rather
                // than the wrapper the future hands back.
                job.failed("could not build the TSC port", it.cause ?: it)
                task.cancel(true)
                null
            }
    }

    /**
     * Opens the transport [config] names, writes [bytes] down it, and closes it again
     * - one job, start to finish. [ThermalPrinter.printRaw] has already checked USB
     * permission before this is called, so USB here only has to find the device.
     *
     * ## Why the printer's own StrictMode settings are put back afterwards
     *
     * [TscWifiActivity.openport] does this on its way in, before it touches a socket -
     * decompiled, not guessed:
     *
     * ```
     * StrictMode.setVmPolicy(new VmPolicy.Builder()
     *     .detectLeakedSqlLiteObjects().detectLeakedClosableObjects()
     *     .penaltyLog().penaltyDeath().build());
     * ```
     *
     * `penaltyDeath` on a VM policy is process-wide and permanent: from the first label
     * printed over WiFi until the app is next killed, ANY leaked cursor or unclosed
     * stream anywhere in the till - and this app runs on SQLite - takes the whole
     * process down with it, at whatever unrelated moment the garbage collector happens
     * to notice. The SDK is a demo app that never expected to be a library, and this is
     * the demo's debugging aid left switched on.
     *
     * It also replaces the calling thread's ThreadPolicy, which is the print worker's,
     * for the life of that thread.
     *
     * Neither is ours to leave lying around, so both are read before the job and put
     * back after it. The policies are only loose for the seconds the job itself takes.
     */
    fun send(
        context: Context, bytes: ByteArray, config: ThermalPrinter.Config, job: PrintLog.Job
    ): ThermalPrinter.Result {
        val callerThreadPolicy = StrictMode.getThreadPolicy()
        val callerVmPolicy = StrictMode.getVmPolicy()
        return try {
            when {
                config.isUsb -> sendUsb(context, bytes, config, job)
                config.isBluetooth -> sendBluetooth(context, bytes, config, job)
                else -> sendWifi(context, bytes, config, job)
            }
        } finally {
            runCatching {
                StrictMode.setThreadPolicy(callerThreadPolicy)
                StrictMode.setVmPolicy(callerVmPolicy)
            }
            job.step("StrictMode policies the SDK changed have been put back")
        }
    }

    private fun sendWifi(
        context: Context, bytes: ByteArray, config: ThermalPrinter.Config, job: PrintLog.Job
    ): ThermalPrinter.Result {
        val port = newPort(context, job) { TscWifiActivity() } ?: return UNAVAILABLE
        return transact(
            context, bytes, job,
            what = "TSC WiFi ${config.ip}:${config.port}",
            open = { port.openport(config.ip, config.port) },
            write = { port.sendcommand(bytes) },
            close = { port.closeport() },
            unreachable = "Cannot reach printer at ${config.description}"
        )
    }

    private fun sendBluetooth(
        context: Context, bytes: ByteArray, config: ThermalPrinter.Config, job: PrintLog.Job
    ): ThermalPrinter.Result {
        val port = newPort(context, job) { TSCActivity() } ?: return UNAVAILABLE
        return transact(
            context, bytes, job,
            what = "TSC Bluetooth ${config.ip}",
            // Just the MAC - the SDK's own pairing/timeout overload is not needed;
            // the printer is expected already paired, the same assumption the
            // ESC/POS path made of Print.portOpenBT.
            open = { port.openport(config.ip) },
            write = { port.sendcommand(bytes) },
            close = { port.closeport() },
            unreachable = "Cannot reach printer at ${config.description}"
        )
    }

    private fun sendUsb(
        context: Context, bytes: ByteArray, config: ThermalPrinter.Config, job: PrintLog.Job
    ): ThermalPrinter.Result {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (manager == null) {
            job.step("no USB service on this device")
            return ThermalPrinter.Result.Failure("USB is not available on this device")
        }
        // Re-resolved per job, same as the ESC/POS path: the printer may have been
        // unplugged and plugged back in since the address was saved.
        val device = UsbPrinters.find(context, config.ip)
        if (device == null) {
            job.step("no USB device matching ${config.ip} is attached")
            return ThermalPrinter.Result.Failure("USB printer not connected - plug it in and try again")
        }
        job.step("USB device resolved: ${device.deviceName} vendor=${device.vendorId} product=${device.productId}")
        val port = newPort(context, job) { TSCUSBActivity() } ?: return UNAVAILABLE
        return transact(
            context, bytes, job,
            what = "TSC USB ${config.ip}",
            open = { port.openport(manager, device) },
            write = { port.sendcommand(bytes) },
            close = { port.closeport() },
            unreachable = "Cannot reach printer at ${config.description}"
        )
    }

    /**
     * The one shape every transport above follows: open, and only on success write,
     * always close. [close] runs even when [write] failed or threw, because the SDK's
     * own close is where the drain wait lives - see the class doc - and skipping it on
     * a failed write would leave that wait undone on the one path most likely to need
     * it.
     *
     * ## Why the OPEN is retried, when the job is not
     *
     * The second label of the day was the one that did not come out. A TSC printer takes
     * one connection at a time and does not hand it back the moment the app lets go of
     * it: the SDK's own `closeport` waits 1.5 seconds on the app's side, and the printer
     * takes its own time after that - a WiFi module holds the socket on port 9100 for
     * seconds, and a Bluetooth unit has to tear down the RFCOMM link. An operator who
     * prints a label, peels it off and prints the next one is back well inside that
     * window, so `openport` answered "-1", and one label printed where two were asked
     * for.
     *
     * Retrying the open is safe in a way that retrying the JOB would not be. Not one
     * byte of TSPL has left the app until the open succeeds, so there is nothing the
     * printer could have half-printed and nothing a second attempt could duplicate -
     * which is exactly the line the receipt path draws too, in [ThermalPrinter.runJob]:
     * a failure before the data goes out is retryable, a failure after it is not.
     *
     * The write is deliberately still one-shot. It is the one call that can fail with
     * the job already inside the printer.
     */
    private fun transact(
        context: Context,
        bytes: ByteArray,
        job: PrintLog.Job,
        what: String,
        open: () -> String?,
        write: () -> String?,
        close: () -> String?,
        unreachable: String
    ): ThermalPrinter.Result {
        job.step("opening $what (${bytes.size} bytes to send)")
        var opened = false
        for (attempt in 1..OPEN_ATTEMPTS) {
            // Every SDK call is logged with what it answered AND how long it took. The
            // two together are the diagnosis: "-1" after 2000ms is a printer that never
            // answered the connect, "-1" after 3ms is one that answered and refused, and
            // no line at all means the call never came back.
            val answer = runCatching(open)
            answer.exceptionOrNull()?.let { job.failed("$what openport attempt $attempt threw", it) }
            job.step(
                "$what openport attempt $attempt/$OPEN_ATTEMPTS -> " +
                    "${answer.getOrNull() ?: "threw"}  ${explain(answer.getOrNull())}"
            )
            if (answer.getOrNull() == OK) { opened = true; break }
            if (attempt < OPEN_ATTEMPTS) {
                job.step("waiting ${OPEN_BACKOFF_MS * attempt}ms for the printer to let the port go")
                Thread.sleep(OPEN_BACKOFF_MS * attempt)
            }
        }
        if (!opened) {
            // Closed even though nothing opened. A failed open is not always a clean
            // one - a socket connected and then refused, a Bluetooth link half brought
            // up - and a dangling port here is what stops the NEXT job opening at all,
            // which is the very failure this retry exists to get out of. The ESC/POS
            // path closes on the way out of a failed open for the same reason.
            val closed = runCatching(close).getOrNull()
            job.step("gave up after $OPEN_ATTEMPTS attempts; tidy-up closeport -> $closed")
            return ThermalPrinter.Result.Failure(unreachable)
        }
        return try {
            val sent = runCatching(write)
            sent.exceptionOrNull()?.let { job.failed("$what sendcommand threw", it) }
            job.step("$what sendcommand -> ${sent.getOrNull() ?: "threw"}  ${explain(sent.getOrNull())}")
            if (sent.getOrNull() != OK) {
                ThermalPrinter.Result.Failure("Printer rejected the label")
            } else {
                // Sent, not Success: the SDK never reports the label actually came
                // out, only that the write reached the printer - see printRaw's own
                // "why it does not retry" note on the same distinction.
                ThermalPrinter.Result.Sent
            }
        } finally {
            val closed = runCatching(close)
            closed.exceptionOrNull()?.let { job.failed("$what closeport threw", it) }
            job.step("$what closeport -> ${closed.getOrNull() ?: "threw"}  ${explain(closed.getOrNull())}")
        }
    }

    /**
     * The SDK's return codes in words, so the log does not need the manual beside it.
     * "-1" on a line of its own has sent more than one person looking in the wrong place.
     */
    private fun explain(answer: String?): String = when (answer) {
        OK -> "(ok)"
        "-1" -> "(SDK: failed)"
        "-2" -> "(SDK: failed, and the port could not be closed either)"
        null -> "(no answer - the call threw, see above)"
        else -> "(unrecognised code)"
    }
}
