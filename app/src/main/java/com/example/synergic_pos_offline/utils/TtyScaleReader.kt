package com.example.synergic_pos_offline.utils

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A weighing scale on one of the board's OWN serial ports - `/dev/ttyS7` and the like -
 * rather than on a USB-serial adapter.
 *
 * ## Why this exists beside [UsbScaleManager]
 *
 * [UsbScaleManager] finds its scale with `UsbSerialProber`, which enumerates the USB
 * bus. A hardware UART brought out on the board is not on that bus and never appears in
 * the probe, so a scale wired to the built-in serial header was invisible to the app
 * however its settings were filled in. This reads the device node directly instead.
 *
 * It is a SEPARATE object on purpose. The USB path works and is what nearly every till
 * uses, so it is left exactly as it was; selecting a `/dev/tty…` port routes to this and
 * touches none of it.
 *
 * ## What this can and cannot set
 *
 * It can open the node and read bytes. It CANNOT set the baud rate, parity or stop bits:
 * those are a `termios`/`ioctl` on the file descriptor, which plain Java has no access
 * to, and the usb-serial library's own `setParameters` only works on a USB device it
 * owns.
 *
 * So the port has to already be at the right speed. That is usually how these boards
 * ship - the UART is configured by the platform - and where it is not, `stty` is tried
 * once as a best effort. If neither applies, the reading comes through as mojibake, and
 * the log below says the baud was not set rather than leaving the operator guessing.
 *
 * ## Permission
 *
 * `/dev/ttyS*` is normally owned by root and not readable by an ordinary app. A POS
 * board that exposes its UART for this purpose usually opens it up, and a system-signed
 * build can read it regardless. Where it cannot, the failure is a plain
 * `FileNotFoundException: Permission denied`, which is reported as such - that is a
 * device-configuration answer, not something the app can work around.
 */
object TtyScaleReader {

    private const val TAG = "TtyScaleReader"

    /** The device nodes worth offering: the board's UARTs and any USB/ACM adapters. */
    private val NODE_PATTERN = Regex("^tty(S|USB|ACM)\\d+$")

    private val running = AtomicBoolean(false)
    private var reader: Thread? = null
    private var stream: FileInputStream? = null
    private val main = Handler(Looper.getMainLooper())

    private val updatePending = AtomicBoolean(false)
    @Volatile private var latestWeight: Double? = null

    // The raw stream, collapsed the same way and for the same reason - see postRaw.
    private val rawPending = AtomicBoolean(false)
    @Volatile private var latestRaw: String? = null

    /**
     * Every serial device node present on this board, as paths, sorted.
     *
     * Listed from `/dev` rather than hard-coded, so the dropdown offers what this
     * particular device actually has instead of a guess at what it might. A board with
     * no exposed UART simply offers nothing and the operator stays on USB.
     *
     * Not filtered by readability: a node that exists but cannot be opened is worth
     * offering, because the failure message it produces ("Permission denied") is what
     * tells whoever is setting the till up that the port needs opening at the platform
     * level. Hiding it would look like the port does not exist.
     */
    fun availablePorts(): List<String> = runCatching {
        File("/dev").listFiles()
            ?.map { it.name }
            ?.filter { NODE_PATTERN.matches(it) }
            ?.sortedWith(compareBy({ it.takeWhile { c -> !c.isDigit() } }, { nodeIndex(it) }))
            ?.map { "/dev/$it" }
            .orEmpty()
    }.getOrElse {
        Log.w(TAG, "Could not list /dev", it)
        emptyList()
    }

    /** ttyS7 sorts after ttyS10 as text; this is what keeps the dropdown in order. */
    private fun nodeIndex(name: String): Int = name.dropWhile { !it.isDigit() }.toIntOrNull() ?: 0

    /**
     * Opens [path] and streams parsed weights to [onWeight] until [disconnect].
     *
     * [onWeight] and [onError] are both delivered on the main thread, and readings are
     * collapsed the same way [UsbScaleManager] collapses them - a scale settling on the
     * pan sends far faster than a screen can be updated, and one Runnable per line is
     * what turns that into a frozen till.
     */
    fun connect(
        path: String,
        baudRate: Int,
        charCount: Int,
        decimalPosition: Int,
        startPoint: Int,
        endPoint: Int,
        onWeight: (Double) -> Unit,
        onError: (String) -> Unit,
        onRaw: ((String) -> Unit)? = null
    ) {
        disconnect()

        val node = File(path)
        if (!node.exists()) {
            onError("No serial port at $path")
            return
        }
        applyBaudBestEffort(path, baudRate)

        val input = try {
            FileInputStream(node)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open $path", e)
            onError(
                if (e.message?.contains("Permission denied", true) == true)
                    "No permission to read $path - the port has to be opened up on the device"
                else "Could not open $path: ${e.message}"
            )
            return
        }

        stream = input
        running.set(true)
        reader = Thread {
            val chunk = ByteArray(256)
            val buffer = StringBuilder()
            try {
                while (running.get()) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    if (read == 0) continue
                    val text = String(chunk, 0, read, Charsets.US_ASCII)
                    // Before parsing, so the popup shows what the port sent even when
                    // none of it parses - which is the case worth looking at.
                    postRaw(text, onRaw)
                    buffer.append(text)
                    drain(buffer, charCount, decimalPosition, startPoint, endPoint, onWeight)
                }
            } catch (e: Exception) {
                // A read that throws because disconnect() closed the stream under us is
                // the normal way this thread ends, not a fault worth reporting.
                if (running.get()) {
                    Log.w(TAG, "Read failed on $path", e)
                    main.post { onError("Weighing scale read error: ${e.message}") }
                }
            } finally {
                runCatching { input.close() }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** Stops reading and closes the node. Safe to call when nothing is open. */
    fun disconnect() {
        running.set(false)
        // Closed before the thread is joined: a blocking read on a tty only returns
        // once there are bytes, so waiting politely for the loop to notice the flag
        // could wait until the scale next sends something - which, on a scale switched
        // off, is never.
        runCatching { stream?.close() }
        stream = null
        reader = null
        updatePending.set(false)
        latestWeight = null
        rawPending.set(false)
        latestRaw = null
    }

    /**
     * The same collapsing as [postWeight], for the raw stream.
     *
     * A tty read returns as soon as there are bytes, so on a chatty scale this fires
     * several times a second; one Runnable per chunk is the backlog that froze the till
     * before, so the newest chunk replaces the pending one rather than queueing behind
     * it. Costs nothing when [onRaw] is null.
     */
    private fun postRaw(chunk: String, onRaw: ((String) -> Unit)?) {
        if (onRaw == null) return
        latestRaw = chunk
        if (rawPending.compareAndSet(false, true)) {
            main.post {
                rawPending.set(false)
                latestRaw?.let(onRaw)
            }
        }
    }

    /**
     * Splits every complete CR/LF-terminated line out of [buffer] and reports the last
     * one that parsed - the same rule the USB path follows, for the same reason: the
     * earlier readings in a burst are superseded before they could reach the screen.
     */
    private fun drain(
        buffer: StringBuilder,
        charCount: Int,
        decimalPosition: Int,
        startPoint: Int,
        endPoint: Int,
        onWeight: (Double) -> Unit
    ) {
        var last: Double? = null
        while (true) {
            val idx = buffer.indexOfFirst { it == '\r' || it == '\n' }
            if (idx < 0) break
            val line = buffer.substring(0, idx)
            buffer.delete(0, idx + 1)
            ScaleReading.parse(line, charCount, decimalPosition, startPoint, endPoint)
                ?.let { last = it }
        }
        // A stream with no CR/LF framing at all would otherwise grow this forever.
        if (buffer.length > 256) buffer.setLength(0)
        last?.let { postWeight(it, onWeight) }
    }

    private fun postWeight(weight: Double, onWeight: (Double) -> Unit) {
        latestWeight = weight
        if (updatePending.compareAndSet(false, true)) {
            main.post {
                updatePending.set(false)
                latestWeight?.let(onWeight)
            }
        }
    }

    /**
     * Asks the platform to set the port's speed, and shrugs if it cannot.
     *
     * Best effort by nature: `stty` is not on every Android build, and on most it
     * cannot touch a root-owned node anyway. Where the board has already configured its
     * UART - which is the usual case for one wired to a scale - this is unnecessary and
     * its failure means nothing. Logged rather than surfaced, because a reading that
     * then arrives correctly would make an error toast a lie.
     */
    private fun applyBaudBestEffort(path: String, baudRate: Int) {
        runCatching {
            val p = ProcessBuilder("/system/bin/stty", "-F", path, baudRate.toString())
                .redirectErrorStream(true)
                .start()
            p.waitFor()
            Log.i(TAG, "stty -F $path $baudRate exited ${p.exitValue()}")
        }.onFailure {
            Log.i(TAG, "stty unavailable; $path is used at whatever speed it is already set to")
        }
    }

    private inline fun CharSequence.indexOfFirst(predicate: (Char) -> Boolean): Int {
        for (i in indices) if (predicate(this[i])) return i
        return -1
    }
}
