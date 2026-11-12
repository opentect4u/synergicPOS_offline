package com.example.synergic_pos_offline.utils

import android.content.Context
import print.Print

/**
 * The receipt printer this software is sold with - and the only one it prints to.
 *
 * Balaji supplies the till together with one printer model, the HOP-E801 (80mm,
 * ESC/POS, USB + Ethernet; serial numbers on the label read "HTRP-80..."). Any other
 * receipt printer is refused: [ThermalPrinter] asks the printer who it is right after
 * connecting, and if the answer is not on the lists below the bill is not sent.
 *
 * ## How a printer is recognised
 *
 * The printer is asked, over whatever connection it is on (USB, LAN/WiFi, Bluetooth),
 * the standard ESC/POS identity questions - `GS I` 66 / 67 / 68: manufacturer, model
 * name, serial number. Asking the printer itself rather than trusting the connection
 * is what makes this hold on a network printer, where an IP address says nothing about
 * what is plugged in at it. A USB printer is also known by the vendor/product pair it
 * reports to Android ([UsbPrinters.addressOf]), checked on its own as well, because
 * that needs no reply from the printer at all.
 *
 * It is enough for ONE of these to match. Firmware differs between batches of the
 * same model in what it answers, so requiring all of them would lock out a genuine
 * printer over a blank serial-number reply.
 *
 * ## Adding a printer
 *
 * Plug the printer in and press Test Print in Printer Settings. A printer that is not
 * accepted is refused with what it reported ("model=..., serial=..., usb=...") - put
 * the value that identifies it into the matching list below.
 *
 * The label (barcode) printer is a TSC and speaks TSPL through its own SDK; it is not
 * covered here.
 */
object ApprovedPrinter {

    /** Off switches the whole lock off - every printer prints, as before. */
    const val ENABLED = true

    /** Accepted when the model name or manufacturer the printer reports contains one of these. */
    private val MODEL_NAMES = listOf("HOP-E801", "E801")

    /** Accepted when the serial number the printer reports starts with one of these. */
    private val SERIAL_PREFIXES = listOf("HTRP-80")

    /**
     * Accepted USB vendor:product pairs, "VVVV:PPPP" in hex as [UsbPrinters.addressOf]
     * writes them. 0456:0808 is what the HOP-E801 reports over USB.
     *
     * That pair is a stock chip id that other makers' printers report too, so on its
     * own it proves little - a USB printer is accepted only when its name, as Android
     * reads it off the device ("Hoin Manufacturer HY USB Printer"), also carries one of
     * [USB_MAKERS].
     */
    private val USB_IDS = setOf("0456:0808")
    private val USB_MAKERS = listOf("Hoin")

    /** What a printer said about itself. Blank where it did not answer. */
    data class Identity(
        val maker: String,
        val model: String,
        val serial: String,
        val usbId: String,
        val usbName: String = ""
    ) {
        override fun toString(): String =
            "model=${model.ifBlank { "-" }}, maker=${maker.ifBlank { "-" }}, " +
                "serial=${serial.ifBlank { "-" }}" +
                (if (usbId.isNotBlank()) ", usb=$usbId" else "") +
                (if (usbName.isNotBlank()) ", usbName=$usbName" else "")

        /** The USB pair and the USB name both say it is the approved printer. */
        val isApprovedUsb: Boolean
            get() = USB_IDS.any { it.equals(usbId, ignoreCase = true) } &&
                USB_MAKERS.any { usbName.contains(it, ignoreCase = true) }
    }

    /** Whether [identity] is the approved printer. */
    fun accepts(identity: Identity): Boolean {
        if (identity.isApprovedUsb) return true
        if (SERIAL_PREFIXES.any { identity.serial.startsWith(it, ignoreCase = true) }) return true
        return MODEL_NAMES.any {
            identity.model.contains(it, ignoreCase = true) || identity.maker.contains(it, ignoreCase = true)
        }
    }

    /** The operator-facing refusal, with what the printer reported so it can be added. */
    fun refusal(identity: Identity): String =
        "This printer is not supported. Please use the Balaji HOP-E801 printer. ($identity)"

    /**
     * Asks the printer on the port [Print] currently has open who it is.
     *
     * Must run on the print worker, with the port open and nothing else in flight.
     * Each question waits up to [REPLY_MS] for its answer, so a printer that answers
     * none of them costs about three seconds once per connection, not per line.
     */
    fun identify(context: Context, config: ThermalPrinter.Config): Identity {
        val device = if (config.isUsb) UsbPrinters.find(context, config.ip) else null
        val usbId = device?.let { UsbPrinters.addressOf(it) }.orEmpty()
        val usbName = device?.let { UsbPrinters.labelOf(it) }.orEmpty()
        // What Android read off the USB device settles it without a word to the printer.
        val usbOnly = Identity("", "", "", usbId, usbName)
        if (usbOnly.isApprovedUsb) return usbOnly
        val model = ask(GS_I_MODEL)
        // One good answer is enough (see the class note), so stop asking once there is one.
        if (MODEL_NAMES.any { model.contains(it, ignoreCase = true) }) return usbOnly.copy(model = model)
        val serial = ask(GS_I_SERIAL)
        if (SERIAL_PREFIXES.any { serial.startsWith(it, ignoreCase = true) }) {
            return usbOnly.copy(model = model, serial = serial)
        }
        return usbOnly.copy(maker = ask(GS_I_MAKER), model = model, serial = serial)
    }

    private const val GS_I_MAKER: Byte = 66
    private const val GS_I_MODEL: Byte = 67
    private const val GS_I_SERIAL: Byte = 68
    private const val REPLY_MS = 1000

    /**
     * `GS I n`, answered as 0x5F, the text, then NUL - the same exchange the SDK's own
     * getPrintSN() makes for the serial number. Anything already waiting to be read is
     * drained first, or a stale status byte would be taken for the answer.
     */
    private fun ask(n: Byte): String = runCatching {
        var drained = 0
        while (drained++ < 8 && Print.ReadData(50)?.isNotEmpty() == true) { /* discard */ }
        if (Print.WriteData(byteArrayOf(0x1D, 0x49, n)) == -1) return ""
        val reply = Print.ReadData(REPLY_MS) ?: return ""
        val body = if (reply.firstOrNull()?.toInt() == 0x5F) reply.drop(1) else reply.toList()
        String(body.filter { it in 0x20..0x7E }.toByteArray(), Charsets.US_ASCII).trim()
    }.getOrDefault("")
}
