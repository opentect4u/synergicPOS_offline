package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raw scale stream shown at the foot of the product popup.
 *
 * It is a diagnostic display, so the thing that matters is that it shows what arrived -
 * including the parts that are invisible. A scale whose lines are not framed the way the
 * settings expect looks identical to a working one unless the CR and LF are drawn.
 */
class ScaleStreamTest {

    private fun append(existing: String?, chunk: String) =
        ProductEntryDialog.appendStream(existing, chunk)

    @Test
    fun `rolls chunks forward rather than replacing them`() {
        // One chunk flashing past is not a stream; several in a row is what shows
        // whether the scale is framing its lines.
        var s = append(null, "  1.250")
        s = append(s, "kg")
        assertEquals("  1.250kg", s)
    }

    @Test
    fun `shows carriage return and line feed instead of obeying them`() {
        assertEquals("""  1.250kg\r\n""", append(null, "  1.250kg\r\n"))
    }

    @Test
    fun `shows other unprintable bytes as dots`() {
        // Visible as "something is there" without pushing the line out of shape.
        assertEquals("ST.GS", append(null, "ST\u0002GS"))
        assertEquals(".", append(null, "\u007F"))
    }

    @Test
    fun `leaves ordinary text exactly as it came`() {
        val line = "ST,GS,+  1.250kg"
        assertEquals(line, append(null, line))
    }

    @Test
    fun `keeps the newest data when the stream runs long`() {
        var s: String? = null
        repeat(200) { s = append(s, "  1.250kg\r\n") }
        // Capped, so an hour on the pan does not grow a string until the popup stutters.
        assertTrue("grew to ${s!!.length}", s!!.length <= 160)
        // And it is the END that is kept - the newest reading is the one being watched.
        assertTrue(s!!.endsWith("""  1.250kg\r\n"""))
    }

    @Test
    fun `a chunk longer than the window still shows its newest end`() {
        val flood = "x".repeat(500) + "NEWEST"
        val s = append(null, flood)
        assertTrue(s.length <= 160)
        assertTrue(s.endsWith("NEWEST"))
    }

    @Test
    fun `an empty chunk leaves what is already showing`() {
        assertEquals("  1.250kg", append("  1.250kg", ""))
    }
}
