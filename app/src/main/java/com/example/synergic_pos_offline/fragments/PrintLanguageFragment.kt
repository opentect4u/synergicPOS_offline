package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.utils.PrintLanguage
import com.example.synergic_pos_offline.utils.SettingsHighlighter
import com.example.synergic_pos_offline.utils.ThemeManager

/**
 * Print Language - which language the printer labels a slip in.
 *
 * One radio per language, and picking one stores it there and then. There is no Save
 * button on purpose: there is a single value on this screen and nothing to be
 * consistent with, so a choice that needed confirming would only be a choice an
 * operator could leave half-made. The line under the list reports what is *stored*,
 * so the screen and the printer never disagree.
 *
 * The radios are built from [PrintLanguage.Language] rather than declared in the
 * layout - a language exists once it has a row in the dictionary, and this screen
 * should not be a second place it also has to be added.
 */
class PrintLanguageFragment : Fragment(), TitledScreen {

    override val screenTitle = "Print Language"

    private lateinit var group: RadioGroup

    /** Held so the writes below are not attributed to whatever fires the listener. */
    private var applying = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_print_language, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        group = view.findViewById(R.id.rgPrintLanguage)
        val current = PrintLanguage.of(requireContext())
        val accent = ThemeManager.getThemeColor(requireContext())

        PrintLanguage.Language.values().forEach { language ->
            group.addView(radioFor(language, accent))
        }
        group.check(idOf(current))

        group.setOnCheckedChangeListener { _, checkedId ->
            if (applying) return@setOnCheckedChangeListener
            PrintLanguage.Language.values().getOrNull(checkedId - 1)?.let { choose(it) }
        }

        showScope(view, current)
        ThemeManager.applyTheme(view)
        SettingsHighlighter.apply(view, arguments?.getString(SettingsHighlighter.ARG_SETTING))
    }

    /**
     * One language's row: its English name, with the name it calls itself beside it.
     *
     * Both, rather than either alone. The English name is what somebody setting the
     * till up is looking for in a list they were told to find "Tamil" in; the native
     * name is what the person the slips are actually for will recognise, and it also
     * shows at a glance whether this device has the font to print that script at all -
     * a row of empty boxes here is a row of empty boxes on the paper.
     */
    private fun radioFor(language: PrintLanguage.Language, accent: Int): RadioButton =
        RadioButton(requireContext()).apply {
            // Ordinal + 1: a view id of 0 is indistinguishable from "no id", and
            // RadioGroup treats it as nothing checked - which would leave English,
            // the first language in the list, unable to show as the chosen one.
            id = idOf(language)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            val pad = (6 * resources.displayMetrics.density).toInt()
            setPadding(paddingLeft, pad, paddingRight, pad)
            textSize = 15f
            setTextColor(resources.getColor(R.color.text_main, null))
            buttonTintList = ColorStateList.valueOf(accent)
            text = if (language == PrintLanguage.DEFAULT) {
                "${language.englishName} (Default)"
            } else if (language.nativeName == language.englishName) {
                language.englishName
            } else {
                "${language.englishName}  ·  ${language.nativeName}"
            }
        }

    /** Stores the choice, then says on the screen what is now stored. */
    private fun choose(language: PrintLanguage.Language) {
        val stored = runCatching {
            GeneralSettingsDao(requireContext()).savePrintLanguage(language.code)
            true
        }.getOrElse { error ->
            android.util.Log.e(TAG, "Could not store the print language", error)
            false
        }

        if (!stored) {
            // Put the radio back on what is actually stored rather than leaving the
            // screen claiming a language the printer will not use.
            applying = true
            group.check(idOf(PrintLanguage.of(requireContext())))
            applying = false
            toast("Could not save the print language")
            return
        }
        view?.let { showScope(it, language) }
        toast("Bills and reports will print in ${language.englishName}")
    }

    /**
     * What the stored choice reaches, and what it deliberately does not.
     *
     * Said here because it is the question this screen raises and cannot be answered
     * from the list itself: an operator who picks Tamil and then finds their product
     * names still in English has to be able to see that this was the intent rather
     * than a fault. The shop's own words stay the shop's own words.
     */
    private fun showScope(view: View, language: PrintLanguage.Language) {
        val english = language == PrintLanguage.Language.ENGLISH
        view.findViewById<TextView>(R.id.tvPrintLanguageScope).text = buildString {
            if (english) {
                append("Bills, receipts and reports print with English labels.")
            } else {
                append("Printed labels - column headings, totals, captions and report ")
                append("titles - come out in ${language.englishName} on bills, receipts ")
                append("and reports.")
            }
            append("\n\nLeft as they are, in every language:")
            append("\n•  The store name, address and GSTIN, and your header and footer lines.")
            append("\n•  Product, customer and operator names, and your own bill captions.")
            append("\n•  GST, SGST, CGST, IGST, VAT, HSN, KOT and UDF - the statutory short forms.")
            append("\n•  Amount in words, dates, figures and the app's own screens.")
        }
    }

    /** This language's radio id - see where it is set for why it is not the ordinal. */
    private fun idOf(language: PrintLanguage.Language): Int = language.ordinal + 1

    private fun toast(message: String) =
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "PrintLanguage"
    }
}
