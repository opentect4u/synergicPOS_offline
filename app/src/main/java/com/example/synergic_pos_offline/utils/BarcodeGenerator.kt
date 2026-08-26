package com.example.synergic_pos_offline.utils

/**
 * Makes a barcode for a product that did not come with one.
 *
 * Plenty of stock has no printed code - loose goods, repacks, a shop's own cooking,
 * anything bought by weight. Those still need something the gun can read off a
 * shelf-edge label, and typing a number by hand produces codes that collide, that
 * carry no check digit, and that a scanner may refuse outright.
 *
 * ## Why EAN-13, and why it starts with 2
 *
 * The codes here are real EAN-13: twelve digits and a check digit computed from them,
 * so a scanner accepts one and a label printer can lay it out without complaint.
 *
 * They begin "2", which is the prefix GS1 reserves for RESTRICTED CIRCULATION within
 * a shop. That matters: a code invented in the 2-range is guaranteed never to clash
 * with a manufacturer's barcode on a branded product, because no manufacturer is
 * allocated that range. Inventing codes in any other range risks a shop's own label
 * reading as somebody else's product.
 */
object BarcodeGenerator {

    /** GS1's restricted-circulation prefix - a shop's own codes live here. */
    private const val IN_STORE_PREFIX = "2"

    /** Digits in an EAN-13, check digit included. */
    private const val EAN13_LENGTH = 13

    /**
     * A fresh EAN-13 that [taken] does not already report as in use.
     *
     * The body is the clock, in milliseconds, which climbs and never repeats on one
     * device - so two codes made a second apart differ, and codes made in order sort
     * in order. [taken] is still consulted, and still retried against, because a
     * shop's catalogue can hold codes from a restored backup or another till that
     * this device's clock knows nothing about.
     *
     * Gives up after [ATTEMPTS] and returns the last one it built rather than looping
     * or returning nothing: a duplicate the operator can see and edit is a better
     * outcome than a button that silently does nothing.
     */
    fun nextEan13(taken: (String) -> Boolean = { false }): String {
        var candidate = ""
        repeat(ATTEMPTS) {
            candidate = build()
            if (!taken(candidate)) return candidate
        }
        return candidate
    }

    private fun build(): String {
        // 11 digits after the prefix, then the check digit makes 13. The clock gives
        // 13 digits of its own, so the low 11 are taken - the ones that actually move.
        val body = (System.currentTimeMillis() % 100_000_000_000L)
            .toString()
            .padStart(EAN13_LENGTH - 2, '0')
        val first12 = IN_STORE_PREFIX + body
        return first12 + checkDigit(first12)
    }

    /**
     * The EAN-13 check digit for [first12].
     *
     * Odd positions count once and even positions three times, summed, then taken up
     * to the next multiple of ten. This is what makes a barcode self-verifying: a
     * scanner that misreads one digit gets a total that does not agree with the digit
     * on the end, and refuses the read rather than returning the wrong product.
     */
    fun checkDigit(first12: String): Int {
        val sum = first12.take(12).withIndex().sumOf { (i, c) ->
            val d = Character.digit(c, 10).coerceAtLeast(0)
            if (i % 2 == 0) d else d * 3
        }
        return (10 - sum % 10) % 10
    }

    /** Whether [code] is a well-formed EAN-13 - 13 digits whose last one checks out. */
    fun isValidEan13(code: String): Boolean {
        val v = code.trim()
        if (v.length != EAN13_LENGTH || !v.all { it.isDigit() }) return false
        return checkDigit(v) == Character.digit(v.last(), 10)
    }

    private const val ATTEMPTS = 5
}
