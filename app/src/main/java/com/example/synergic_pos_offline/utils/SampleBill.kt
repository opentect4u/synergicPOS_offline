package com.example.synergic_pos_offline.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A stand-in sale, rendered through the real [BillReceiptRenderer] wherever a bill has
 * to be shown before there is one: the Print Template preview, and Test Print - which
 * prints this, so a test slip is the shop's actual bill layout (header, logos, footer,
 * tax summary, the chosen template) and not a separate test card.
 *
 * Three lines, one of them discounted so the DISC column shows, each carrying both a
 * GST and a VAT rate so the tax summary fills in whichever regime the till is set to,
 * and a unit apiece for the layouts that print one beside the quantity.
 */
object SampleBill {

    /** Carried on one sample line and as the bill's own total, so the two agree. */
    private const val SAMPLE_DISCOUNT = 15.0

    fun draft(): BillReceiptRenderer.Draft = BillReceiptRenderer.Draft(
        billNumber = "SAMPLE",
        dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
        cashier = SessionManager.cashierName,
        customer = BillReceiptRenderer.Draft.Customer(
            name = "Sample Customer",
            phone = "9876543210",
            gstin = "22AAAAA0000A1Z5",
            address = "12 Sample Street, Kolkata"
        ),
        items = listOf(
            BillReceiptRenderer.Draft.Item(
                name = "Basmati Rice 1kg", quantity = 2.0, rate = 145.0,
                cgstRate = 2.5, sgstRate = 2.5, vatRate = 5.0, hsn = "1006", unit = "KG"
            ),
            BillReceiptRenderer.Draft.Item(
                name = "Sunflower Oil 1L", quantity = 1.0, rate = 189.5,
                cgstRate = 2.5, sgstRate = 2.5, vatRate = 5.0, hsn = "1512", unit = "LTR"
            ),
            BillReceiptRenderer.Draft.Item(
                name = "Toothpaste 100g", quantity = 3.0, rate = 55.0,
                cgstRate = 9.0, sgstRate = 9.0, vatRate = 18.0,
                discountAmount = SAMPLE_DISCOUNT, hsn = "3306", unit = "PKT"
            )
        ),
        discount = SAMPLE_DISCOUNT,
        roundOff = 0.0,
        // Only read for a bill with no lines; this one's total is summed from its own.
        netAmount = 0.0,
        paymentModes = listOf("CASH")
    )
}
