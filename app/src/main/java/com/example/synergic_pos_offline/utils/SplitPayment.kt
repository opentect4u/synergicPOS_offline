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
 * ## The operator types the parts they know; one row takes the balance
 *
 * The whole point of a split is that the customer says "here's a hundred, I'll send the
 * rest". The counter knows the hundred. The rest is the bill minus the hundred, and
 * making the operator work that out - in front of a queue, against a total on the other
 * side of the screen - is the arithmetic this exists to remove. So typing into any row
 * fills ONE other row, the [follower], with the balance, immediately, as the digits land.
 *
 * ## Which row follows
 *
 * The one the operator has typed in least recently - never-typed rows counting as the
 * oldest, and between those the panel's own order (Cash, UPI, Card) deciding. So:
 *
 * - type cash and UPI takes the rest - the ordinary notes-plus-phone split, exactly as
 *   the panel behaved when it had only those two rows;
 * - type UPI or card first and cash takes the rest;
 * - type cash, then card, and UPI takes what the two leave;
 * - go back and correct any row and the follower is always one the operator is NOT
 *   working in, so a figure is never rewritten under their fingers.
 *
 * [history] is that order - the rows typed in, most recent last - and is the whole of
 * the flow's state.
 *
 * ALL BOXES STAY EDITABLE THROUGHOUT. None is ever locked, greyed or spent: there is no
 * state the panel can get into where a row refuses to be corrected. The follower is
 * derived from the others, so the parts add up by construction and [remaining] is only
 * ever non-zero before the first keystroke.
 *
 * ## Nothing typed means nothing entered
 *
 * Before the first keystroke every row stays blank rather than one of them holding the
 * whole bill: turning the switch on says the customer is paying more than one way, not
 * how it divides. Clearing a row back to empty reads as zero, so the follower takes
 * what it left - which is the honest reading of "no cash".
 *
 * ## Only cash gives change
 *
 * Over-collection in the cash row is change - the customer handed a 500 note for their
 * 100 of cash - and surfaces as [change], the same reading the single-mode cash path has
 * always taken. A UPI transfer or a card swipe is taken to the penny by the machine, so
 * those two together exceeding the bill is a mis-key, not money to hand back: [overpaid]
 * reports it and [balances] refuses the sale until it is corrected. Letting it through
 * would book the excess as change against the cash drawer that never received it.
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
        ONLINE("ONLINE", "UPI"),

        /** Stored as CARD, the same mode the Card tile settles a whole bill as. */
        CARD("CARD", "Card")
    }

    private data class Row(val part: Part, val fieldId: Int)

    private val rows = listOf(
        Row(Part.CASH, R.id.etSplitCash),
        Row(Part.ONLINE, R.id.etSplitOnline),
        Row(Part.CARD, R.id.etSplitCard)
    )

    /**
     * The rows the operator has typed in, most recent last - see [follower].
     *
     * Empty until the first keystroke, which is what keeps every row blank on a split
     * that has only just been switched on.
     */
    private val history = mutableListOf<Part>()

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

    /** Everything collected above the bill, whichever rows it is in. */
    private fun excess(): Double = BillRounding.toPaise((entered() - totalOf()).coerceAtLeast(0.0))

    /**
     * Cash handed over above the bill, which goes back across the counter.
     *
     * Capped at the cash part: only notes can come back - see [overpaid].
     */
    fun change(): Double = BillRounding.toPaise(minOf(excess(), amount(Part.CASH)))

    /**
     * What the UPI and card parts took above the bill on their own - money no drawer
     * can hand back, so a mis-key the sale cannot be completed with.
     */
    fun overpaid(): Double = BillRounding.toPaise((excess() - amount(Part.CASH)).coerceAtLeast(0.0))

    /** Whether the parts add up to the bill, so the sale can be completed. */
    fun balances(): Boolean = remaining() <= 0.001 && overpaid() <= 0.001

    /**
     * Wires the switch and the amount rows.
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
                history.clear()
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
                    // through would mark the row we had just filled as typed in, and
                    // the balance would chase the operator round the panel.
                    if (writing) return@watcher
                    // Typing here makes this the most recently typed row, whether it
                    // was a moment ago or not.
                    history.remove(row.part)
                    history.add(row.part)
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
        history.clear()
        writeAll("")
        root.findViewById<SwitchMaterial>(R.id.swSplitPayment).isChecked = false
        root.findViewById<View>(R.id.llSplitBody).visibility = View.GONE
        recompute()
    }

    // ---- The arithmetic ----------------------------------------------------

    /**
     * The row that takes the balance: the one typed in least recently, rows never
     * typed in counting as older than any that were, the panel's order breaking ties.
     * Never the row just typed in. Null before the first keystroke.
     */
    private fun follower(): Part? {
        val latest = history.lastOrNull() ?: return null
        return rows.map { it.part }
            .filter { it != latest }
            .minByOrNull { history.indexOf(it) }   // -1 for never typed: oldest of all
    }

    private fun recompute() {
        val total = totalOf()
        val follower = follower()

        if (follower == null) {
            // Nothing typed yet: a bill on its own implies no division of itself, and a
            // row pre-filled with the whole total would be claiming one.
            writeAll("")
        } else {
            // The follower is whatever the other rows leave of the bill. Cleared to
            // nothing where they already cover it - a customer paying the lot in notes
            // has no UPI part, and "0.00" sitting in the box would read as one that
            // failed.
            val others = rows.filter { it.part != follower }.sumOf { amount(it.part) }
            val rest = BillRounding.toPaise(total - others)
            write(follower, if (rest > 0.005) Amounts.editable(rest) else "")
        }

        renderSummary(total)
    }

    private fun renderSummary(total: Double) {
        val entered = entered()
        val remaining = remaining()
        val change = change()
        val overpaid = overpaid()

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
            overpaid > 0.001 -> "UPI and card are ${money(overpaid)} over the bill - only cash can be handed back."
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
