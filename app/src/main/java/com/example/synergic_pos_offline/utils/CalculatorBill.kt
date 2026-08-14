package com.example.synergic_pos_offline.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A calculator-mode bill: a rate, a quantity and what they came to, over and over.
 *
 * No products, no tax, no customer - a calculator till has none of those, and a bill
 * that carried empty columns for them would be a grocery bill with the interesting
 * parts blank. So this is its own small shape, and [content] turns it into the slip
 * the shared renderer prints.
 */
object CalculatorBill {

    /** One line: what was multiplied, and by how much. */
    data class Line(val serial: Int, val rate: Double, val quantity: Double) {
        val amount: Double get() = BillRounding.toPaise(rate * quantity)
    }

    /** The whole bill, as it stands. */
    data class Bill(
        val billNumber: String,
        /** yyyy-MM-dd HH:mm:ss, as saved. */
        val stamp: String,
        val lines: List<Line>
    ) {
        val itemCount: Int get() = lines.size
        val totalQuantity: Double get() = BillRounding.toPaise(lines.sumOf { it.quantity })
        val totalAmount: Double get() = BillRounding.toPaise(lines.sumOf { it.amount })
        val isEmpty: Boolean get() = lines.isEmpty()
    }

    /**
     * The bill as receipt paper.
     *
     * Built on [PeriodReportRenderer] rather than a renderer of its own: the rules,
     * the column fitting and the way a slip is measured onto the roll are the same
     * problem here as on every report, and this till already has one answer to it.
     * What is different - the head line naming the bill instead of the machine - is a
     * single field.
     */
    fun content(bill: Bill): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Calculator Bill",
            period = "",
            subtitle = "",
            style = PeriodReportRenderer.Style.CLASSIC,
            headLine = Triple(
                "BILL NO: ${bill.billNumber}",
                reformat(bill.stamp, "dd-MM-yyyy"),
                reformat(bill.stamp, "HH:mm:ss")
            ),
            columns = listOf("S NO.", "RATE", "QTY", "AMOUNT"),
            // Four columns of figures with no name among them, so the serial is set
            // to the right with the rest rather than breaking the run of them.
            evenColumns = true,
            alignFirstColumnEnd = true,
            rows = bill.lines.map {
                listOf(
                    it.serial.toString(),
                    money(it.rate),
                    money(it.quantity),
                    money(it.amount)
                )
            },
            // What the lines came to as a count, ruled off from them - not a total in
            // the columns above, which is why it is a row rather than a summary line.
            footerRow = listOf("", "ITEM: ${bill.itemCount}", "QTY: ${money(bill.totalQuantity)}", ""),
            summary = listOf("TOTAL" to money(bill.totalAmount)),
            emptyNote = "Nothing on this bill."
        )

    private fun money(v: Double): String = String.format(Locale.US, "%.2f", v)

    /** "2026-08-13 17:38:28" as [pattern]; the raw value back if it will not parse. */
    private fun reformat(stamp: String, pattern: String): String = runCatching {
        SimpleDateFormat(pattern, Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(stamp)!!)
    }.getOrDefault(stamp)

    /** Now, in the form [Bill.stamp] holds. */
    fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
