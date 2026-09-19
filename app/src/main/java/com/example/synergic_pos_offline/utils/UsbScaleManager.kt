package com.example.synergic_pos_offline.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The USB side of the weighing scale: finding it, opening it at the configured
 * baud rate, and turning its raw serial stream into a weight in kilograms.
 *
 * Mirrors [UsbPrinters] for device discovery and permission handling - USB access
 * is per-device, granted in a system dialog, and lapses on unplug - but stays
 * connected and streaming for as long as the product popup that asked for it is
 * open, rather than a one-shot query, since a scale keeps sending readings as the
 * item settles on the pan.
 */
object UsbScaleManager {

    private var port: UsbSerialPort? = null
    private var connection: UsbDeviceConnection? = null
    private var ioManager: SerialInputOutputManager? = null
    private var receiver: BroadcastReceiver? = null
    private var buffer: String = ""
    private val main = Handler(Looper.getMainLooper())

    // A continuous/fast scale stream can call onNewData - on a background thread -
    // far quicker than the main thread can apply a reading to the UI. Posting one
    // Runnable per line let the main thread's message queue grow without bound
    // under a large stream, which is what showed up as the app freezing: it wasn't
    // stuck, it was working through a backlog that grew faster than it drained.
    // These two collapse any burst down to "whichever reading is newest once the
    // main thread is free" - at most one update in flight at a time, however fast
    // the scale sends.
    private val updatePending = AtomicBoolean(false)
    @Volatile private var latestWeight: Double? = null

    /** Whether the till has a weighing scale configured at all - General Settings. */
    fun isEnabled(context: Context): Boolean = GeneralSettingsDao.isWeighingScaleEnabled(context)

    /**
     * Opens the first attached USB-serial device at the configured baud rate and
     * starts streaming parsed weights to [onWeight] until [disconnect] is called.
     *
     * [onWeight] and [onError] are both delivered on the main thread. A device
     * needing permission raises the system dialog; the connection opens once it is
     * granted, or [onError] fires if it is refused.
     */
    fun connect(context: Context, onWeight: (Double) -> Unit, onError: (String) -> Unit) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (manager == null) {
            onError("USB is not supported on this device")
            return
        }
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
        if (driver == null) {
            onError("No weighing scale connected")
            return
        }
        if (manager.hasPermission(driver.device)) {
            openAndListen(app, manager, driver, onWeight, onError)
        } else {
            requestPermission(app, manager, driver.device) { granted ->
                if (granted) openAndListen(app, manager, driver, onWeight, onError)
                else onError("USB access was not allowed for the weighing scale")
            }
        }
    }

    /** Stops streaming and releases the port. Safe to call even when not connected. */
    fun disconnect() {
        ioManager?.stop()
        runCatching { port?.close() }
        runCatching { connection?.close() }
        ioManager = null
        port = null
        connection = null
        buffer = ""
        updatePending.set(false)
        latestWeight = null
        receiver?.let { r -> runCatching { unregister(r) } }
        receiver = null
    }

    private var registeredOn: Context? = null
    private fun unregister(r: BroadcastReceiver) {
        registeredOn?.unregisterReceiver(r)
        registeredOn = null
    }

    private fun openAndListen(
        app: Context,
        manager: UsbManager,
        driver: UsbSerialDriver,
        onWeight: (Double) -> Unit,
        onError: (String) -> Unit
    ) {
        val conn = manager.openDevice(driver.device)
        if (conn == null) {
            onError("Failed to open the weighing scale")
            return
        }
        val p = driver.ports.firstOrNull()
        if (p == null) {
            runCatching { conn.close() }
            onError("Weighing scale has no serial port")
            return
        }
        val settings = GeneralSettingsDao(app).load()
        try {
            p.open(conn)
            p.setParameters(
                settings.weighingScaleBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE
            )
        } catch (e: Exception) {
            runCatching { p.close() }
            runCatching { conn.close() }
            onError("Error opening weighing scale: ${e.message}")
            return
        }

        connection = conn
        port = p
        buffer = ""
        ioManager = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val weight = feed(String(data, Charsets.US_ASCII), settings.weighingScaleCharCount, settings.weighingScaleDecimalPosition)
                if (weight != null) postWeight(weight, onWeight)
            }

            override fun onRunError(e: Exception) {
                main.post { onError("Weighing scale read error: ${e.message}") }
            }
        }).also { it.start() }
    }

    /**
     * Records [weight] as the latest reading and, if nothing is already queued to
     * apply one, posts a single Runnable to pick up whatever is latest by the time
     * the main thread runs it. A second, third or hundredth reading that arrives
     * before that Runnable runs just updates [latestWeight] in place rather than
     * queueing another post - see the comment on [updatePending].
     */
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
     * Appends [chunk] to the pending buffer, splits off every complete CR/LF-terminated
     * line, and parses each one - returning the LAST reading that parsed cleanly.
     * Earlier readings in the same chunk are superseded before they would ever
     * reach the screen anyway, so there is no reason to keep them; a line clipped
     * mid-transmission is expected every so often on a live stream and is simply
     * dropped rather than surfaced as an error.
     */
    private fun feed(chunk: String, charCount: Int, decimalPosition: Int): Double? {
        buffer += chunk
        var last: Double? = null
        while (true) {
            val idx = buffer.indexOfFirst { it == '\r' || it == '\n' }
            if (idx < 0) break
            val line = buffer.substring(0, idx)
            buffer = buffer.substring(idx + 1)
            parseWeight(line, charCount, decimalPosition)?.let { last = it }
        }
        // A line that never terminates (noise, or a scale with no CR/LF framing)
        // would otherwise grow the buffer forever - cap it and start fresh.
        if (buffer.length > 256) buffer = ""
        return last
    }

    /**
     * Reads a weight out of one raw line from the scale, using only the two things
     * General Settings asks the operator for: how many digits the weight carries
     * ([charCount]), and how many of those, counted from the right, are decimals
     * ([decimalPosition]).
     *
     * Most indicators send a fixed-width run of digits with no decimal point of its
     * own (e.g. "001250" for 1.250kg) and may pad it with a status prefix, a "kg"
     * suffix, or leading zeros that vary with the reading. Taking the LAST
     * [charCount] digits and inserting the point [decimalPosition] places from the
     * right handles both of those without needing to know the scale's exact
     * protocol - only its digit width and decimal placement, which the operator
     * reads off the scale's own display/manual.
     *
     * A leading "-" anywhere before the digits (as a net-negative or tare reading)
     * carries through as a negative value.
     */
    private fun parseWeight(rawLine: String, charCount: Int, decimalPosition: Int): Double? {
        if (charCount <= 0) return null
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return null
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < charCount) return null
        val field = digits.takeLast(charCount)
        val magnitude = field.toLongOrNull() ?: return null
        val value = magnitude / Math.pow(10.0, decimalPosition.toDouble())
        return if (trimmed.contains('-')) -value else value
    }

    private fun requestPermission(
        context: Context, manager: UsbManager, device: UsbDevice, onResult: (Boolean) -> Unit
    ) {
        val action = "${context.packageName}.USB_SCALE_PERMISSION"
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != action) return
                runCatching { context.unregisterReceiver(this) }
                if (receiver === this) receiver = null
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        receiver = r
        registeredOn = context
        ContextCompat.registerReceiver(context, r, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(action).setPackage(context.packageName), flags
        )
        manager.requestPermission(device, permissionIntent)
    }
}
