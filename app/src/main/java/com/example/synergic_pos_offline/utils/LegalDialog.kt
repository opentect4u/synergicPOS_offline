package com.example.synergic_pos_offline.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.synergic_pos_offline.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows one of the [LegalDocuments], to be read or to be agreed to.
 *
 * The two are the same document and the same dialog, differing only in whether there
 * is anything to do at the bottom of it - which is the point. A shop that reads the
 * terms from About App is reading exactly what it agreed to at registration, in the
 * same words and the same order, because there is one text and one screen for it.
 */
object LegalDialog {

    /**
     * Shows [title]'s document with nothing to agree to - Close is the only way out.
     */
    fun read(context: Context, title: String, body: String) {
        show(context, title, body, subtitle = null, onAgreed = null)
    }

    /**
     * Shows the registration agreement: the terms, a checkbox, and Proceed.
     *
     * [onAgreed] runs only after the box is ticked and Proceed is pressed. Proceed is
     * disabled until then rather than hidden - a greyed button an operator can see is
     * what tells them the checkbox is the thing standing between them and registering.
     *
     * Cancelling does nothing at all, which is deliberate: not agreeing has to leave
     * the operator exactly where they were, with the form they filled in still filled
     * in, rather than costing them the typing.
     */
    fun agree(context: Context, onAgreed: () -> Unit) {
        show(
            context = context,
            title = "Terms & Conditions",
            // Both documents, because the checkbox commits to both. Asking somebody
            // to agree to a privacy policy while showing them only the terms would be
            // asking them to accept something they were not given - so the policy
            // follows the terms in the same scroll, under its own heading.
            body = LegalDocuments.TERMS +
                "\n\n## Privacy Policy\n\n" + LegalDocuments.PRIVACY,
            subtitle = "Please read these before registering this device",
            onAgreed = onAgreed
        )
    }

    /**
     * Records that these terms were agreed to, and when.
     *
     * Kept because an agreement nobody can point at afterwards is not much of an
     * agreement: About App shows the date and the version, so a shop can see what it
     * accepted and when. Stored against [LegalDocuments.VERSION], so terms revised
     * later can be told apart from the ones actually accepted.
     */
    fun recordAcceptance(context: Context) {
        prefs(context).edit()
            .putString(KEY_VERSION, LegalDocuments.VERSION)
            .putString(KEY_AT, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            .apply()
    }

    /** When the terms were accepted and which edition, or null if never. */
    fun acceptance(context: Context): Pair<String, String>? {
        val at = prefs(context).getString(KEY_AT, null) ?: return null
        val version = prefs(context).getString(KEY_VERSION, null) ?: return null
        return at to version
    }

    // ---- The dialog itself ----------------------------------------------------

    private fun show(
        context: Context,
        title: String,
        body: String,
        subtitle: String?,
        onAgreed: (() -> Unit)?
    ) {
        val ctx = FixedFontScale.wrap(context)
        val accent = ThemeManager.getThemeColor(ctx)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_legal, null)

        val dialog = AlertDialog.Builder(ctx).setView(view).create()
            .also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }

        view.findViewById<TextView>(R.id.tvLegalTitle).text = title
        view.findViewById<TextView>(R.id.tvLegalSubtitle).apply {
            text = subtitle.orEmpty()
            visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        renderBody(ctx, view.findViewById(R.id.llLegalBody), body, accent)

        val checkbox = view.findViewById<MaterialCheckBox>(R.id.cbLegalAgree)
        val proceed = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val cancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        if (onAgreed == null) {
            // Reading, not agreeing: one way out, and it is not called "Cancel".
            checkbox.visibility = View.GONE
            proceed.visibility = View.GONE
            cancel.text = "Close"
        } else {
            checkbox.visibility = View.VISIBLE
            checkbox.buttonTintList = ColorStateList.valueOf(accent)
            setEnabled(proceed, false, accent)
            checkbox.setOnCheckedChangeListener { _, checked -> setEnabled(proceed, checked, accent) }
            proceed.setOnClickListener {
                if (!checkbox.isChecked) return@setOnClickListener
                dialog.dismiss()
                onAgreed()
            }
        }
        cancel.setOnClickListener { dialog.dismiss() }

        ThemeManager.applyTheme(view)
        // ThemeManager fills every MaterialButton; restore the outlined negative, and
        // put Proceed back to whatever its enabled state should be.
        cancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        cancel.setTextColor(accent)
        cancel.strokeColor = ColorStateList.valueOf(accent)
        if (onAgreed != null) setEnabled(proceed, checkbox.isChecked, accent)

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** Greys Proceed rather than hiding it - see [agree]. */
    private fun setEnabled(button: MaterialButton, enabled: Boolean, accent: Int) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
        button.backgroundTintList = ColorStateList.valueOf(accent)
        button.setTextColor(Color.WHITE)
    }

    /**
     * Lays the document out: "##" opens a heading, everything else is a paragraph.
     *
     * Built as views rather than set as one string so a heading can actually look
     * like one. A page of legal text in a single undifferentiated block is a page
     * nobody reads, and being read is the entire purpose of showing it.
     */
    private fun renderBody(ctx: Context, into: LinearLayout, body: String, accent: Int) {
        into.removeAllViews()
        val density = ctx.resources.displayMetrics.density
        body.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { block ->
            val heading = block.startsWith("##")
            into.addView(TextView(ctx).apply {
                text = if (heading) block.removePrefix("##").trim() else block.replace("\n", " ")
                textSize = if (heading) 15f else 14f
                setTextColor(
                    if (heading) accent
                    else ctx.resources.getColor(R.color.text_main, null)
                )
                if (heading) setTypeface(typeface, Typeface.BOLD)
                setLineSpacing(4 * density, 1f)
                setPadding(0, ((if (heading) 16 else 8) * density).toInt(), 0, 0)
            })
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private const val PREF = "legal_acceptance"
    private const val KEY_VERSION = "accepted_version"
    private const val KEY_AT = "accepted_at"
}
