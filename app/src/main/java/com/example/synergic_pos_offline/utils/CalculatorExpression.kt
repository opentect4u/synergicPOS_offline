package com.example.synergic_pos_offline.utils

/** The grocery sale screen's on-screen calculator - the arithmetic behind its keys. */
object CalculatorExpression {

    /**
     * Evaluates a flat +−×÷% expression (display symbols). Returns null if invalid.
     *
     * % WORKS THE WAY A SHOP CALCULATOR'S DOES, which is what the counter expects:
     *
     *   200 + 10%  = 220     (ten percent OF 200, added on)
     *   500 − 20%  = 400     (twenty percent of 500, taken off)
     *   200 × 10%  = 20      (ten percent of 200)
     *   50 ÷ 10%   = 500     (50 divided by a tenth)
     *   10%        = 0.1     (on its own, just the fraction)
     *
     * It used to turn the number before it into /100 and nothing more, so 200 + 10%
     * came out as 200.1 - right for a scientific calculator, wrong for anyone working
     * out a discount or a tax on a price. A percent added to or taken from a running
     * total is a percent OF that total; times and divide were already right.
     *
     * Multiplication and division bind first, as before: 50 + 200 × 10% is 50 + 20.
     */
    fun evaluate(raw: String): Double? {
        if (raw.isBlank()) return null
        val normalized = raw.replace('×', '*').replace('÷', '/').replace('−', '-')

        // A number, and whether a % followed it.
        data class Operand(val value: Double, val percent: Boolean)
        val operands = mutableListOf<Operand>()
        val ops = mutableListOf<Char>()
        val num = StringBuilder()
        var percent = false
        fun closeNumber(): Boolean {
            val v = num.toString().toDoubleOrNull() ?: return false
            operands.add(Operand(v, percent))
            num.clear(); percent = false
            return true
        }
        for (c in normalized) {
            when (c) {
                in '0'..'9', '.' -> { if (percent) return null; num.append(c) }
                '%' -> { if (num.isEmpty() || num.toString() == "-" || percent) return null; percent = true }
                '+', '-', '*', '/' -> {
                    if (num.isEmpty()) {
                        // A leading minus belongs to the first number.
                        if (c == '-' && operands.isEmpty()) { num.append('-'); continue }
                        return null
                    }
                    if (!closeNumber()) return null
                    ops.add(c)
                }
                else -> return null
            }
        }
        if (num.isEmpty() || !closeNumber()) return null

        // Split into additive terms (runs joined by × and ÷), each with its sign.
        var total = 0.0
        var i = 0
        var sign = 1.0
        while (i < operands.size) {
            // Collect one term: operands[i] and every × / ÷ that follows it.
            val first = operands[i]
            val loneFactor = i + 1 >= operands.size || ops[i] == '+' || ops[i] == '-'
            var term = if (first.percent) {
                // A percent standing alone after + or − is a percent OF the total so far.
                if (loneFactor && i > 0) total * first.value / 100.0 else first.value / 100.0
            } else first.value
            var k = i
            while (k < ops.size && (ops[k] == '*' || ops[k] == '/')) {
                val next = operands[k + 1]
                val b = if (next.percent) next.value / 100.0 else next.value
                term = if (ops[k] == '*') term * b else if (b == 0.0) return null else term / b
                k++
            }
            total += sign * term
            if (k < ops.size) sign = if (ops[k] == '-') -1.0 else 1.0
            i = k + 1
        }
        if (total.isNaN() || total.isInfinite()) return null
        return total
    }
}
