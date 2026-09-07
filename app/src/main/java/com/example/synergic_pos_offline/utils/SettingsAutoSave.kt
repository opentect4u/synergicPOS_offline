package com.example.synergic_pos_offline.utils

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioGroup

/**
 * Settings that save themselves.
 *
 * Every settings screen used to end in a Save button, and a setting was not a setting
 * until it had been pressed. That is a step the operator has to remember on a screen
 * whose whole purpose is one switch they came to flip - and the cost of forgetting it
 * is silent: the screen closes looking exactly as it would have looked had it worked,
 * and the setting is simply not on.
 *
 * So the answer to "when is this saved" is now "when you change it".
 *
 * ## A switch and a typed field are not saved at the same moment
 *
 * [onChange] is for controls with one answer per touch - a switch, a radio, a
 * dropdown. There is nothing to wait for: the tap IS the decision, so it is written
 * immediately.
 *
 * [onTyped] is for text, where the decision is not made until the typing stops. Saving
 * per keystroke would write "5", "50", "500" on the way to "5000" - three settings the
 * operator never chose, each of them live for a moment. So it waits for the typing to
 * settle, and writes again when the field is left, which is the other way a person
 * says they have finished with it.
 *
 * ## What this does NOT do
 *
 * It does not remove a confirmation. A setting that destroys something when it changes
 * still has to ask - see BillSettingsFragment's start-bill-number, which erases every
 * bill on the till. Auto-save means there is no Save button to press, not that a
 * dangerous change happens without being agreed to.
 */
object SettingsAutoSave {

    /**
     * How long typing has to stop before it counts as finished.
     *
     * Long enough to cross a pause mid-number, short enough that a field left alone
     * has been written by the time the operator looks away from it.
     */
    private const val SETTLE_MS = 700L

    /**
     * Writes as soon as any of [controls] changes.
     *
     * Attach AFTER the screen has loaded its values into them: a listener added before
     * that hears the load itself and writes back what it just read, which at best is a
     * pointless write and at worst re-saves a default over a value that was mid-flight.
     *
     * A control that already has a listener of its own is not passed here - it would be
     * replaced, and the screen would lose whatever that listener was doing. Those call
     * [save] from inside the listener they already have.
     */
    fun onChange(save: () -> Unit, vararg controls: View) {
        controls.forEach { control ->
            when (control) {
                is CompoundButton -> control.setOnCheckedChangeListener { _, _ -> save() }
                is RadioGroup -> control.setOnCheckedChangeListener { _, _ -> save() }
                // A dropdown is an EditText that is chosen from rather than typed in,
                // so it is the SELECTION that is the change, not the text arriving.
                is android.widget.AutoCompleteTextView ->
                    control.setOnItemClickListener { _, _, _, _ -> save() }
                is EditText -> onTyped(save, control)
            }
        }
    }

    /**
     * Writes once typing in any of [fields] has settled, and again when one is left.
     *
     * Both, because they are different endings. A field the operator types in and then
     * looks away from is finished by the pause; a field they type in and then tap
     * straight out of is finished by the leaving, and the pause would still be running.
     */
    fun onTyped(save: () -> Unit, vararg fields: EditText) {
        val main = Handler(Looper.getMainLooper())
        fields.forEach { field ->
            val settle = Runnable { save() }
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    main.removeCallbacks(settle)
                    main.postDelayed(settle, SETTLE_MS)
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
            field.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    main.removeCallbacks(settle)
                    save()
                }
            }
        }
    }
}
