package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The printer lock covers the bill printer only; KOT and barcode printers are free. */
class PrinterLockScopeTest {

    private fun config(purpose: String) =
        ThermalPrinter.Config(ip = "192.168.1.50", port = 9100, paperMm = 80, connection = "LAN", purpose = purpose)

    @Test
    fun aBillPrinterIsLocked() {
        assertTrue(config("BILL").isLocked)
        assertTrue(config("B").isLocked)
    }

    @Test
    fun aConfigThatDoesNotSayIsLocked() {
        assertTrue(ThermalPrinter.Config(ip = "192.168.1.50", port = 9100, paperMm = 80).isLocked)
    }

    @Test
    fun kotAndBarcodePrintersAreNotLocked() {
        listOf("KOT", "K", "kot", "BARCODE", "OTHERS", "O").forEach {
            assertFalse(it, config(it).isLocked)
        }
    }
}
