package com.example.synergic_pos_offline.utils

/**
 * Turning one line from a weighing scale into a number.
 *
 * Split out of [UsbScaleManager] so it can be tested. That class holds a `Handler` on
 * the main looper as a field, so merely touching it from a plain JUnit test throws -
 * and this is the part worth testing: a misread here does not fail, it charges the
 * customer for the wrong weight, which nobody notices until the till and the shelf
 * disagree at stocktake.
 *
 * Pure, and free of Android and of any setting it is not handed.
 */
object ScaleReading {

    /**
     * Reads a weight out of one raw line from the scale.
     *
     * ## The start/end window, where the operator has set one
     *
     * [startPoint] and [endPoint] are character positions into the line as it arrives,
     * counted from 1 and both inclusive - exactly how a scale's manual numbers them.
     * They are the reliable way to read an indicator that sends more than the weight:
     *
     * ```
     * ST,GS,+  1.250kg
     * 1234567890123456
     *         ^     ^    start 9, end 14  ->  " 1.250"
     * ```
     *
     * Such a frame can carry digits before the reading and after it - a status code, a
     * unit like "2kg", a piece count, a checksum. The window says which characters are
     * the weight and ignores everything else, which no amount of digit counting can do.
     *
     * A window running past the end of a line is clamped rather than refused: serial
     * lines arrive clipped often enough that throwing the whole reading away would lose
     * good ones with the bad. A window with no digit in it returns null, and the caller
     * skips that line.
     *
     * ## Without one, the digit count as before
     *
     * Both points zero means no window is set, and this falls back to what shipped
     * before: the LAST [charCount] digits found anywhere in the line. That suits the
     * common indicator sending a bare fixed-width run ("001250"), and it is what
     * existing tills are already configured for - so they keep working untouched.
     *
     * ## The decimal point
     *
     * A point already in the text is believed. A scale sending "1.250" has said where
     * the point goes, and applying [decimalPosition] on top would divide it twice -
     * turning 1.250kg into 0.001kg. Only a bare digit run has the point inserted,
     * [decimalPosition] places from the right.
     *
     * A "-" carries through as a negative (a net or tare reading). It is looked for in
     * the window where there is one, and in the whole line otherwise: a window drawn to
     * exclude the sign column was drawn that way on purpose.
     */
    fun parse(
        rawLine: String,
        charCount: Int,
        decimalPosition: Int,
        startPoint: Int = 0,
        endPoint: Int = 0
    ): Double? {
        if (rawLine.isBlank()) return null

        val windowed = startPoint > 0 && endPoint >= startPoint
        val field = if (windowed) {
            val from = (startPoint - 1).coerceIn(0, rawLine.length)
            val to = endPoint.coerceIn(from, rawLine.length)
            rawLine.substring(from, to)
        } else {
            val digits = rawLine.trim().filter { it.isDigit() }
            if (charCount <= 0 || digits.length < charCount) return null
            digits.takeLast(charCount)
        }

        // Keep only what can be part of a number. A window nearly always catches a
        // stray space, and often the sign or the first letter of the unit.
        val cleaned = field.filter { it.isDigit() || it == '.' }
        if (cleaned.none { it.isDigit() }) return null

        val value = if (cleaned.contains('.')) {
            cleaned.toDoubleOrNull() ?: return null
        } else {
            val magnitude = cleaned.toLongOrNull() ?: return null
            magnitude / Math.pow(10.0, decimalPosition.coerceAtLeast(0).toDouble())
        }
        val signSource = if (windowed) field else rawLine
        return if (signSource.contains('-')) -value else value
    }
}
