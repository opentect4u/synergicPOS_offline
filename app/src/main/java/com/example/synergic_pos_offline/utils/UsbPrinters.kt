package com.example.synergic_pos_offline.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * The USB side of printing: which printers are plugged in, and permission to talk
 * to them.
 *
 * A USB printer has no address an operator can type, so it is identified by the
 * vendor and product ids the device reports - see [addressOf]. That string is what
 * gets saved against the printer in md_operating_printer, in the same column an IP
 * or a Bluetooth MAC goes in, so the rest of the print path treats all three the
 * same way: look up the saved address, open it, send the receipt.
 *
 * Android also gates every USB device behind a per-device permission the user grants
 * in a system dialog. It is not a manifest permission and cannot be pre-granted, and
 * it lapses when the device is unplugged - so it is asked for where it is needed
 * ([ensurePermission]), both when a printer is being set up and again before a print
 * job if the printer has been unplugged since.
 */
object UsbPrinters {

    /**
     * A plugged-in printer as the operator sees it. [address] is what gets saved;
     * [isPrinterClass] is true for a device that declares itself a printer, which is
     * most but not all ESC/POS units - see [canPrint].
     */
    data class Printer(
        val device: UsbDevice,
        val label: String,
        val address: String,
        val isPrinterClass: Boolean
    )

    /**
     * How a USB printer is saved: "VVVV:PPPP", its vendor and product ids in hex.
     *
     * Not the device name, which the OS makes up ("/dev/bus/usb/001/003") and
     * renumbers on every plug-in, and not the serial number, which most of these
     * printers do not report. Two identical printers on one till would collide -
     * which is a real but distant problem, and the alternative identifiers are worse.
     */
    fun addressOf(device: UsbDevice): String =
        String.format(Locale.US, "%04X:%04X", device.vendorId, device.productId)

    /** Every attached device a receipt could plausibly be sent to, printers first. */
    fun list(context: Context): List<Printer> =
        manager(context)?.deviceList?.values.orEmpty()
            .filter { canPrint(it) }
            .sortedByDescending { isPrinterClass(it) }
            .map { Printer(it, labelOf(it), addressOf(it), isPrinterClass(it)) }

    /** The attached device saved under [address], or null if it is not plugged in. */
    fun find(context: Context, address: String): UsbDevice? {
        val wanted = address.trim()
        if (wanted.isEmpty()) return null
        return manager(context)?.deviceList?.values?.firstOrNull {
            addressOf(it).equals(wanted, ignoreCase = true)
        }
    }

    /** What the device calls itself, falling back to its ids when it says nothing. */
    fun labelOf(device: UsbDevice): String {
        val product = runCatching { device.productName }.getOrNull()?.takeIf { it.isNotBlank() }
        val maker = runCatching { device.manufacturerName }.getOrNull()?.takeIf { it.isNotBlank() }
        return when {
            product != null && maker != null -> "$maker $product"
            product != null -> product
            maker != null -> "$maker printer"
            else -> "USB device ${addressOf(device)}"
        }
    }

    fun hasPermission(context: Context, device: UsbDevice): Boolean =
        manager(context)?.hasPermission(device) == true

    /**
     * Makes sure the printer saved under [address] is plugged in and permitted, then
     * calls [onResult] on the main thread - granted, plus the reason when not.
     *
     * Everything that prints goes through here rather than each screen asking on its
     * own, so an auto-print at checkout raises the same prompt a test print does
     * instead of failing silently the first time after a printer is plugged in.
     */
    fun ensurePermission(context: Context, address: String, onResult: (Boolean, String) -> Unit) {
        val device = find(context, address)
        if (device == null) {
            onResult(false, "USB printer not connected - plug it in and try again")
            return
        }
        if (hasPermission(context, device)) {
            onResult(true, "")
            return
        }
        requestPermission(context, device) { granted ->
            onResult(
                granted,
                if (granted) "" else "USB access was not allowed for ${labelOf(device)}"
            )
        }
    }

    /**
     * Raises Android's own "allow this app to access the USB device?" dialog.
     *
     * The answer comes back as a broadcast, so a receiver is registered for the one
     * reply and unregistered as soon as it arrives. The pending intent has to be
     * mutable on Android 12+: the system fills the device and the answer into it.
     */
    fun requestPermission(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        val manager = manager(app)
        if (manager == null) {
            onResult(false)
            return
        }
        val action = "${app.packageName}.USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != action) return
                runCatching { app.unregisterReceiver(this) }
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            app, 0, Intent(action).setPackage(app.packageName), flags
        )
        manager.requestPermission(device, permissionIntent)
    }

    private fun manager(context: Context): UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** True when the device declares a printer interface (USB class 7). */
    private fun isPrinterClass(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER
        }

    /**
     * Whether a receipt could be sent to this device at all.
     *
     * Class 7 is the answer for a printer that declares itself properly. Plenty of
     * cheap ESC/POS units instead expose a vendor-specific interface, and they print
     * perfectly well over its bulk endpoint - so anything with a bulk OUT endpoint is
     * offered too, rather than the operator being told their printer does not exist.
     * That still leaves out hubs, keyboards and mice, which have no bulk OUT.
     */
    private fun canPrint(device: UsbDevice): Boolean = isPrinterClass(device) || hasBulkOut(device)

    private fun hasBulkOut(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { i ->
            val iface = device.getInterface(i)
            (0 until iface.endpointCount).any { e ->
                val endpoint = iface.getEndpoint(e)
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT
            }
        }
}
