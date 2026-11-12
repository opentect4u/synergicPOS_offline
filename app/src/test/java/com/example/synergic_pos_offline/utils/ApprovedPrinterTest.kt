package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedPrinterTest {

    private fun id(
        maker: String = "", model: String = "", serial: String = "", usb: String = "", usbName: String = ""
    ) = ApprovedPrinter.Identity(maker, model, serial, usb, usbName)

    @Test
    fun theHopE801IsAcceptedOverUsbByWhatAndroidReadsOffIt() {
        // As the tablet listed the real printer: "Hoin Manufacturer HY USB Printer (0456:0808)".
        assertTrue(ApprovedPrinter.accepts(id(usb = "0456:0808", usbName = "Hoin Manufacturer HY USB Printer")))
    }

    @Test
    fun anotherMakersPrinterOnTheSameStockUsbChipIsRefused() {
        assertFalse(ApprovedPrinter.accepts(id(usb = "0456:0808", usbName = "Generic POS-80 Printer")))
        assertFalse(ApprovedPrinter.accepts(id(usb = "0456:0808")))
    }

    @Test
    fun theHopE801IsAcceptedByItsModelName() {
        assertTrue(ApprovedPrinter.accepts(id(model = "HOP-E801")))
        assertTrue(ApprovedPrinter.accepts(id(model = "hop-e801 v2.1")))
    }

    @Test
    fun theHopE801IsAcceptedByItsSerialNumberAlone() {
        // Firmware that leaves the model name blank still answers with the label's serial.
        assertTrue(ApprovedPrinter.accepts(id(serial = "HTRP-80261000001")))
    }

    @Test
    fun anyOtherPrinterIsRefused() {
        assertFalse(ApprovedPrinter.accepts(id(maker = "EPSON", model = "TM-T82", serial = "X5E1234")))
        assertFalse(ApprovedPrinter.accepts(id(model = "POS-80", usb = "0416:5011")))
    }

    @Test
    fun aPrinterThatAnswersNothingIsRefused() {
        assertFalse(ApprovedPrinter.accepts(id()))
    }
}
