package com.example.synergic_pos_offline.utils

import android.content.Context

/**
 * The receipt printer this software is sold with - and the only one it prints to.
 *
 * Balaji supplies the till together with one printer, the HOP-E801 (80mm, ESC/POS),
 * connected by USB. Receipts and KOTs go to that printer and nothing else: any other
 * USB printer, and every LAN, WiFi or Bluetooth printer, is refused before a byte of
 * the bill is sent ([ThermalPrinter] checks right after connecting).
 *
 * ## How the printer is recognised
 *
 * By what the USB device reports to Android - its vendor/product pair and its name -
 * with nothing asked of the printer itself. The HOP-E801's firmware does not answer
 * the ESC/POS identity query (`GS I`): it prints the query's last byte as a letter
 * instead, so asking would put stray characters on every bill.
 *
 * Network and Bluetooth printers are not accepted because nothing they report can
 * prove they are a HOP-E801: an IP address says nothing about what is plugged in at
 * it, and this printer cannot be asked.
 *
 * The label (barcode) printer is a TSC and speaks TSPL through its own SDK; it is not
 * covered here.
 */
object ApprovedPrinter {

    /** Off switches the whole lock off - every printer prints, as before. */
    const val ENABLED = true

    /**
     * Accepted USB vendor:product pairs, "VVVV:PPPP" in hex as [UsbPrinters.addressOf]
     * writes them. 0456:0808 is what the HOP-E801 reports over USB.
     *
     * That pair is a stock chip id that other makers' printers report too, so on its
     * own it proves little - a USB printer is accepted only when its name, as Android
     * reads it off the device ("Hoin Manufacturer  HY USB Printer"), also carries one of
     * [USB_MAKERS].
     */
    private val USB_IDS = setOf("0456:0808")
    private val USB_MAKERS = listOf("Hoin")

    /** What is known about a printer. Blank where the connection does not tell. */
    data class Identity(
        val connection: String,
        val usbId: String = "",
        val usbName: String = ""
    ) {
        override fun toString(): String = when {
            !connection.equals("USB", ignoreCase = true) -> "$connection printer"
            usbId.isBlank() && usbName.isBlank() -> "USB printer, nothing reported"
            else -> "usb=${usbId.ifBlank { "-" }}, usbName=${usbName.ifBlank { "-" }}"
        }
    }

    /** Whether [identity] is the approved printer: a HOP-E801 on USB, and nothing else. */
    fun accepts(identity: Identity): Boolean =
        identity.connection.equals("USB", ignoreCase = true) &&
            USB_IDS.any { it.equals(identity.usbId, ignoreCase = true) } &&
            USB_MAKERS.any { identity.usbName.contains(it, ignoreCase = true) }

    /** The operator-facing refusal, with what the printer reported so it can be checked. */
    fun refusal(identity: Identity): String =
        if (identity.connection.equals("USB", ignoreCase = true)) {
            "This printer is not supported. Please use the Balaji HOP-E801 printer. ($identity)"
        } else {
            "Only the Balaji HOP-E801 printer connected by USB is supported. " +
                "${identity.connection} printers cannot be used."
        }

    /**
     * What Android knows about the printer [config] points at. Nothing is sent to the
     * printer; for a non-USB connection there is nothing to know.
     */
    fun identify(context: Context, config: ThermalPrinter.Config): Identity {
        val device = if (config.isUsb) UsbPrinters.find(context, config.ip) else null
        return Identity(
            connection = config.connection.uppercase(),
            usbId = device?.let { UsbPrinters.addressOf(it) }.orEmpty(),
            usbName = device?.let { UsbPrinters.labelOf(it) }.orEmpty()
        )
    }
}
