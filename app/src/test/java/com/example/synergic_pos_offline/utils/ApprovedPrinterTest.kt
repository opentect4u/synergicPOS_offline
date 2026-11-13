package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedPrinterTest {

    private fun usb(id: String = "", name: String = "") = ApprovedPrinter.Identity("USB", id, name)

    @Test
    fun theHopE801OnUsbIsAccepted() {
        // As the real printer's test slip showed: USB 0456:0808, "Hoin Manufacturer  HY USB Printer".
        assertTrue(ApprovedPrinter.accepts(usb("0456:0808", "Hoin Manufacturer  HY USB Printer")))
    }

    @Test
    fun anotherMakersPrinterOnTheSameStockUsbChipIsRefused() {
        assertFalse(ApprovedPrinter.accepts(usb("0456:0808", "Generic POS-80 Printer")))
        assertFalse(ApprovedPrinter.accepts(usb("0456:0808")))
    }

    @Test
    fun anotherUsbPrinterIsRefused() {
        assertFalse(ApprovedPrinter.accepts(usb("0416:5011", "Winbond POS-80")))
        assertFalse(ApprovedPrinter.accepts(usb()))
    }

    @Test
    fun everyLanWifiAndBluetoothPrinterIsRefused() {
        listOf("LAN", "WIFI", "BLUETOOTH").forEach {
            assertFalse(it, ApprovedPrinter.accepts(ApprovedPrinter.Identity(it)))
        }
    }

    @Test
    fun aNonUsbRefusalSaysUsbIsRequired() {
        assertTrue(ApprovedPrinter.refusal(ApprovedPrinter.Identity("BLUETOOTH")).contains("connected by USB"))
    }
}
