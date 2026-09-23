package com.example.synergic_pos_offline.utils

/**
 * The weighing scale's raw stream, made readable for the person setting it up.
 *
 * ## What it is for
 *
 * A scale sends a line of characters and the till takes the weight out of a WINDOW of
 * it - General Settings' Starting Point and Ending Point. Those two numbers can only be
 * chosen by someone who can see what the scale actually sends, and nothing else in the
 * app shows that: the parsed weight tells you what the window produced, never what it
 * was cut from. When the window is wrong the quantity box simply stays empty.
 *
 * So this lives at the foot of the Weighing Scale section in General Settings, beside
 * the very fields it exists to help fill in. It was first put at the bottom of the
 * product popup, which showed it to the person selling rather than the person
 * configuring - the wrong screen for a setup aid, and one where it is in the way.
 */
object ScaleStream {

    /** About two lines of the monospaced box the stream is shown in. */
    const val TAIL = 160

    /**
     * [existing] with [chunk] added, rolled forward and made printable.
     *
     * Appended rather than replaced, because a single chunk flashing past is not a
     * stream - what tells you whether a scale is framing its lines properly is seeing
     * several of them in a row. Held to [TAIL] characters, oldest dropped, so a scale
     * left running does not grow a string until the screen stutters.
     *
     * CR and LF are shown as `\r` and `\n` rather than being obeyed. A scale's framing
     * is exactly what is in question when somebody reads this, and a line break drawn
     * as a line break is a character you cannot see - it is also what would make a
     * fixed-height box lurch on every reading.
     */
    fun append(existing: CharSequence?, chunk: String): String {
        val shown = chunk
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            // Anything else unprintable becomes a dot, so a stray byte is visible as
            // something being there without pushing the rest of the line out of shape.
            .map { if (it.code < 0x20 || it.code == 0x7F) '.' else it }
            .joinToString("")
        val joined = existing?.toString().orEmpty() + shown
        return if (joined.length <= TAIL) joined else joined.takeLast(TAIL)
    }

    /**
     * The 1-based character positions of [text], as a ruler under the stream.
     *
     * Starting Point and Ending Point are counted from 1, and counting characters by eye
     * across a run of digits and spaces is exactly where an operator slips by one. The
     * ruler marks every fifth position so the two numbers can be read off rather than
     * counted, and it is built to the same width as the text above it so the columns
     * line up in the monospaced face both are set in.
     */
    fun ruler(text: CharSequence?): String {
        val width = text?.length ?: 0
        if (width == 0) return ""
        return buildString {
            for (i in 1..width) {
                append(
                    when {
                        i % 10 == 0 -> ((i / 10) % 10).toString()
                        i % 5 == 0 -> '+'
                        else -> '.'
                    }
                )
            }
        }
    }
}
