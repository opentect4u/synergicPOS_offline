package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.OperatingPrinterDao
import java.util.Locale

/**
 * Renders a restaurant bill to a bitmap and sends it to a thermal printer — the
 * bill counterpart of [KotPrinter]. Laid out like the grocery bill (store header,
 * an Item/Qty/Rate/Amount table, a totals block and a footer) but with the
 * restaurant tax model (service charge + flat GST). [printer] is a BILL row from
 * the Operating Printer master (chosen by the caller: default or picked).
 */
object RestaurantBillPrinter {

    data class Line(val name: String, val qty: Int, val rate: Double, val amount: Double)

    data class BillTicket(
        val table: String,
        val customer: String,
        val cashier: String,
        val time: String,
        val items: List<Line>,
        val subtotal: Double,
        val service: Double,
        val cgst: Double,
        val sgst: Double,
        val total: Double,
        val note: String,
        val payment: String = ""
    )

    private data class Store(val name: String, val address: String, val phone: String, val gstin: String)

    fun print(
        context: Context,
        ticket: BillTicket,
        printer: OperatingPrinterDao.OperatingPrinter,
        report: (String) -> Unit
    ) {
        val config = ThermalPrinter.configFor(printer)
        if (config == null) {
            report("Bill printer '${printer.printerName}' is not fully configured")
            return
        }
        val bitmap = render(context, ticket, config.paperDots)
        ThermalPrinter.print(context, bitmap, config) { result ->
            when (result) {
                is ThermalPrinter.Result.Success -> report("Bill printed at ${printer.printerName}")
                is ThermalPrinter.Result.Sent -> report("Bill sent to ${printer.printerName}")
                is ThermalPrinter.Result.Failure -> report("Bill print failed: ${result.message}")
            }
            bitmap.recycle()
        }
    }

    private fun money(v: Double) = String.format(Locale.US, "%,.2f", v)

    private fun store(context: Context): Store {
        DatabaseHelper.getInstance(context).readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION,
            arrayOf("store_name", "address", "phone_no", "store_gstin"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst()) return Store(
                c.getString(0).orEmpty(), c.getString(1).orEmpty(),
                c.getString(2).orEmpty(), c.getString(3).orEmpty()
            )
        }
        return Store("", "", "", "")
    }

    /** Draws the bill at [width] dots (the printer's printable width) and returns it. */
    fun render(context: Context, ticket: BillTicket, width: Int): Bitmap {
        val s = store(context)
        val black = Color.BLACK
        fun paint(scale: Float, bold: Boolean, align: Paint.Align = Paint.Align.LEFT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = black
                typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
                textSize = width * scale
                textAlign = align
            }
        val storeName = paint(0.085f, true, Paint.Align.CENTER)
        val storeInfo = paint(0.040f, false, Paint.Align.CENTER)
        val meta = paint(0.042f, false)
        val itemName = paint(0.045f, false)
        val totLabel = paint(0.05f, false)
        val grand = paint(0.065f, true)
        val footer = paint(0.045f, false, Paint.Align.CENTER)

        val ruleH = maxOf(2, width / 220)
        val padX = width * 0.03f
        val padTop = width * 0.03f
        val padBottom = width * 0.12f
        val gap = width * 0.012f

        // Column x positions for the item table (numerics right-aligned).
        val xItem = padX
        val xAmount = width - padX
        val xRate = width - padX - width * 0.24f
        val xQty = width - padX - width * 0.46f

        val steps = mutableListOf<(Canvas?, Float) -> Float>()
        fun center(text: String, p: Paint) = steps.add { c, y0 ->
            val y = y0 - p.ascent(); c?.drawText(text, width / 2f, y, p); y + p.descent() + gap
        }
        fun left(text: String, p: Paint) = steps.add { c, y0 ->
            val y = y0 - p.ascent(); c?.drawText(text, padX, y, p); y + p.descent() + gap
        }
        fun totalRow(label: String, value: String, lp: Paint, bold: Boolean) = steps.add { c, y0 ->
            val y = y0 - lp.ascent()
            if (c != null) {
                lp.textAlign = Paint.Align.LEFT; c.drawText(label, padX, y, lp)
                lp.textAlign = Paint.Align.RIGHT; c.drawText(value, width - padX, y, lp)
                lp.textAlign = Paint.Align.LEFT
            }
            y + lp.descent() + gap
        }
        fun itemRow(name: String, qty: String, rate: String, amt: String) = steps.add { c, y0 ->
            val y = y0 - itemName.ascent()
            if (c != null) {
                var n = name
                val maxNameW = xQty - xItem - width * 0.02f
                if (itemName.measureText(n) > maxNameW) {
                    while (n.length > 1 && itemName.measureText("$n…") > maxNameW) n = n.dropLast(1)
                    n = "$n…"
                }
                itemName.textAlign = Paint.Align.LEFT; c.drawText(n, xItem, y, itemName)
                itemName.textAlign = Paint.Align.RIGHT
                c.drawText(qty, xQty, y, itemName)
                c.drawText(rate, xRate, y, itemName)
                c.drawText(amt, xAmount, y, itemName)
                itemName.textAlign = Paint.Align.LEFT
            }
            y + itemName.descent() + gap
        }
        fun rule() = steps.add { c, y0 ->
            val y = y0 + gap
            c?.drawLine(padX, y, width - padX, y, Paint().apply { color = black; strokeWidth = ruleH.toFloat() })
            y + ruleH + gap
        }

        // ---- Header ----
        if (s.name.isNotBlank()) center(s.name, storeName)
        if (s.address.isNotBlank()) center(s.address, storeInfo)
        if (s.phone.isNotBlank()) center("Ph: ${s.phone}", storeInfo)
        if (s.gstin.isNotBlank()) center("GSTIN: ${s.gstin}", storeInfo)
        rule()
        // ---- Meta ----
        left("Table: ${ticket.table}", meta)
        if (ticket.customer.isNotBlank()) left("Customer: ${ticket.customer}", meta)
        left("Cashier: ${ticket.cashier}    ${ticket.time}", meta)
        rule()
        // ---- Item table ----
        itemRow("Item", "Qty", "Rate", "Amt")
        rule()
        ticket.items.forEach { itemRow(it.name, it.qty.toString(), money(it.rate), money(it.amount)) }
        rule()
        // ---- Totals ----
        totalRow("Subtotal", money(ticket.subtotal), totLabel, false)
        totalRow("Service Charge (5%)", money(ticket.service), totLabel, false)
        totalRow("CGST (2.5%)", money(ticket.cgst), totLabel, false)
        totalRow("SGST (2.5%)", money(ticket.sgst), totLabel, false)
        rule()
        totalRow("TOTAL", money(ticket.total), grand, true)
        if (ticket.payment.isNotBlank()) totalRow("Paid via", ticket.payment, totLabel, false)
        if (ticket.note.isNotBlank()) { rule(); left("Note: ${ticket.note}", meta) }
        rule()
        center("Thank you! Visit again", footer)

        // Measure then draw.
        var y = padTop
        steps.forEach { y = it(null, y) }
        val bmp = Bitmap.createBitmap(width, (y + padBottom).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { drawColor(Color.WHITE) }
        var yy = padTop
        steps.forEach { yy = it(canvas, yy) }
        return bmp
    }
}
