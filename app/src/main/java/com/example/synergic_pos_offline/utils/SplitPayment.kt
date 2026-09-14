package com.example.synergic_pos_offline.utils

import android.view.View
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.example.synergic_pos_offline.R

/**
 * One bill, settled across more than one mode - ₹150 taken as ₹100 in notes and ₹50
 * over UPI - and the arithmetic that keeps the parts adding up to the bill.
 *
 * Drives [R.layout.view_split_payment] wherever it is included, so the grocery till and
 * the dine-in till behave identically rather than each growing its own reading of what
 * a split means.
 *
 * ## The operator types ONE number
 *
 * The whole point of a split is that the customer says "here's a hundred, I'll send the
 * rest". The counter knows the hundred. The rest is the bill minus the hundred, and
 * making the operator work that out - in front of a queue, against a total on the other
 * side of the screen - is the arithmetic this exists to remove. So typing into any row
 * fills the companion row with the balance, immediately, as the digits land.
 *
 * ## Whichever row is being typed in leads; the other follows
 *
 * [driver] is simply the row the operator touched last. The other one is always
 * `total - driver`, rewritten on every keystroke. Type in cash and UPI follows; move to
 * the UPI box and type there and cash starts following instead, from that first
 * keystroke.
 *
 * BOTH BOXES STAY EDITABLE THROUGHOUT. Neither is ever locked, greyed or spent: there
 * is no state the panel can get into where a row refuses to be corrected, because
 * "which row is being corrected" is the only state there is, and typing is what sets
 * it. An earlier version made a row STICKY once typed in, so setting both by hand left
 * nothing to absorb the balance and the split could sit not adding up, needing a button
 * to rescue it. The button is gone because the situation it rescued cannot arise.
 *
 * A consequence worth stating: the split always covers the bill. The follower is
 * derived from the leader, so the two add up by construction and [remaining] is only
 * ever non-zero before the first keystroke.
 *
 * ## Cash and UPI, and nothing else
 *
 * A card is not split against. It is handed over for the whole bill or not at all, and
 * the Card tile on the panel above already settles that sale - so a card row here would
 * be an empty box on every split for a case that does not arise. A part payment at
 * these counters is notes plus a phone.
 *
 * Two rows is also what makes the follow rule unambiguous: "the other one" is a single
 * row, so there is never a question of which of several the balance should land in.
 *
 * ## Nothing typed means nothing entered
 *
 * Before the first keystroke both rows stay blank rather than one of them holding the
 * whole bill: turning the switch on says the customer is paying more than one way, not
 * how it divides. Clearing the leader back to empty reads as zero in that mode, so the
 * follower takes the whole bill - which is the honest reading of "no cash".
 *
 * ## Over-collection is change, not an over-payment
 *
 * [remaining] floors at zero and the excess surfaces as [change] - the customer handed
 * a 500 note for their 100 of cash. That is the same reading the single-mode cash path
 * has always taken, and it is why the strip has a Change due row at all.
 */
class SplitPayment(
    private val root: View,
    private val totalOf: () -> Double,
    private val onChanged: () -> Unit
) {

    /** The modes a split can be taken in, in the order the panel lists them. */
    enum class Part(val mode: String, val label: String) {
        /** Stored as CASH, the same as an ordinary cash sale. */
        CASH("CASH", "Cash"),

        /**
         * Stored as ONLINE, NOT "UPI".
         *
         * The row is labelled UPI because that is what the customer calls it, but it is
         * the same mode the Online tile settles a whole bill as, backed by the same
         * scan-to-pay code. Writing it under a second name would split one mode across
         * two rows of every payment-wise report for no gain.
         */
        ONLINE("ONLINE", "UPI")
    }

    private data class Row(val part: Part, val fieldId: Int)

    private val rows = listOf(
        Row(Part.CASH, R.id.etSplitCash),
        Row(Part.ONLINE, R.id.etSplitOnline)
    )

    /**
     * The row the operator last typed in - the one that leads. The other follows it.
     *
     * Null until the first keystroke, which is what keeps both rows blank on a split
     * that has only just been switched on.
     */
    private var driver: Part? = null

    /** True while this class is writing a field, so its own write is not read as typing. */
    private var writing = false

    private fun field(part: Part): TextInputEditText =
        root.findViewById(rows.first { it.part == part }.fieldId)

    private fun amount(part: Part): Double = Amounts.parse(field(part).text?.toString()) ?: 0.0

    /** Whether this sale is being settled across modes at all. */
    fun isActive(): Boolean =
        root.findViewById<View>(R.id.llSplitPayment)?.visibility == View.VISIBLE &&
            root.findViewById<SwitchMaterial>(R.id.swSplitPayment)?.isChecked == true

    /** What was entered against each mode, skipping the rows that took nothing. */
    fun parts(): List<Pair<Part, Double>> =
        rows.map { it.part to amount(it.part) }.filter { it.second > 0.001 }

    /** Everything entered across the rows, over-collection included. */
    fun entered(): Double = BillRounding.toPaise(rows.sumOf { amount(it.part) })

    /** What the bill still has outstanding. Floors at zero - see [change]. */
    fun remaining(): Double = BillRounding.toPaise((totalOf() - entered()).coerceAtLeast(0.0))

    /** Cash handed over above the bill, which goes back across the counter. */
    fun change(): Double = BillRounding.toPaise((entered() - totalOf()).coerceAtLeast(0.0))

    /** Whether the parts add up to the bill, so the sale can be completed. */
    fun balances(): Boolean = remaining() <= 0.001

    /**
     * Wires the switch and the two amount rows.
     *
     * Called once, from the host screen's own view setup.
     */
    fun bind() {
        root.findViewById<SwitchMaterial>(R.id.swSplitPayment).setOnCheckedChangeListener { _, on ->
            root.findViewById<View>(R.id.llSplitBody).visibility =
                if (on) View.VISIBLE else View.GONE
            // Turning it off throws the parts away rather than leaving them to be
            // re-applied the next time it is switched on against a different bill.
            if (!on) {
                driver = null
                writeAll("")
            }
            recompute()
            onChanged()
        }

        rows.forEach { row ->
            val field = root.findViewById<TextInputEditText>(row.fieldId)

            // Tapping a box selects what is in it, so the first digit REPLACES the
            // figure rather than being appended to it. This row is usually the follower
            // when it is tapped - the operator is reaching for it precisely because
            // they want to set it themselves - and "50.00" turning into "50.005" under
            // the first keystroke is the opposite of what they reached for.
            field.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) (v as? TextInputEditText)?.selectAll()
            }

            field.addTextChangedListener(
                watcher {
                    // Our own write into the follower, not a person typing. Letting it
                    // through would hand the lead straight back to the row we had just
                    // filled, and the two would chase each other.
                    if (writing) return@watcher
                    // Typing here makes this the row that leads, whether it was leading
                    // a moment ago or not. This is the whole of the flow's state.
                    driver = row.part
                    recompute()
                    onChanged()
                }
            )
        }
    }

    /**
     * Re-runs the arithmetic against the current bill total.
     *
     * Called by the host whenever the bill itself moves - an item edited on the
     * checkout screen changes what the split has to add up to, and the auto-filled row
     * has to follow it.
     */
    fun refresh() = recompute()

    /** Puts the panel back to untouched, for a screen starting a fresh sale. */
    fun reset() {
        driver = null
        writeAll("")
        root.findViewById<SwitchMaterial>(R.id.swSplitPayment).isChecked = false
        root.findViewById<View>(R.id.llSplitBody).visibility = View.GONE
        recompute()
    }

    // ---- The arithmetic ----------------------------------------------------

    private fun recompute() {
        val total = totalOf()
        val lead = driver

        if (lead == null) {
            // Nothing typed yet: a bill on its own implies no division of itself, and a
            // row pre-filled with the whole total would be claiming one.
            writeAll("")
        } else {
            // The follower is whatever is left of the bill. Cleared to nothing where
            // the leader already covers it - a customer paying the lot in notes has no
            // UPI part, and "0.00" sitting in the box would read as one that failed.
            val follower = rows.first { it.part != lead }.part
            val rest = BillRounding.toPaise(total - amount(lead))
            write(follower, if (rest > 0.005) Amounts.editable(rest) else "")
        }

        renderSummary(total)
    }

    private fun renderSummary(total: Double) {
        val entered = entered()
        val remaining = remaining()
        val change = change()

        root.findViewById<TextView>(R.id.tvSplitBillTotal)?.text = money(total)
        root.findViewById<TextView>(R.id.tvSplitEntered)?.text = money(entered)
        root.findViewById<TextView>(R.id.tvSplitRemaining)?.text = money(remaining)

        root.findViewById<View>(R.id.rowSplitChange)?.visibility =
            if (change > 0.001) {
                root.findViewById<TextView>(R.id.tvSplitChange)?.text = money(change)
                View.VISIBLE
            } else View.GONE

        // Says which side of the bill the operator is on, in words. A bare figure
        // leaves them to work out whether they are short or have over-collected, and
        // those two call for opposite actions at the counter.
        root.findViewById<TextView>(R.id.tvSplitNote)?.text = when {
            entered <= 0.001 -> "Enter the amount taken in each mode."
            remaining > 0.001 -> "${money(remaining)} still to collect."
            change > 0.001 -> "${money(change)} to hand back."
            else -> "The split covers the bill."
        }
    }

    private fun write(part: Part, text: String) {
        val f = field(part)
        if (f.text?.toString() == text) return
        writing = true
        f.setText(text)
        // Only where the operator is actually working, so filling the companion row
        // does not drag the cursor out of the row being typed in.
        if (f.hasFocus()) f.setSelection(text.length)
        writing = false
    }

    private fun writeAll(text: String) = rows.forEach { write(it.part, text) }

    private fun money(v: Double) = "₹" + Amounts.editable(BillRounding.toPaise(v))

    private fun watcher(onChange: (String) -> Unit) = object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) = onChange(s?.toString().orEmpty())
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }
}
